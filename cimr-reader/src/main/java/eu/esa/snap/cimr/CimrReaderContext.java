package eu.esa.snap.cimr;

import eu.esa.snap.cimr.cimr.CimrBandDescriptor;
import eu.esa.snap.cimr.cimr.CimrDescriptorSet;
import eu.esa.snap.cimr.cimr.CimrGridBuilder;
import eu.esa.snap.cimr.grid.*;
import eu.esa.snap.cimr.netcdf.NetcdfCimrGeometryFactory;
import eu.esa.snap.cimr.netcdf.NetcdfCimrBandFactory;
import ucar.ma2.InvalidRangeException;
import ucar.nc2.NetcdfFile;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public class CimrReaderContext {

    private final NetcdfFile ncFile;
    private final CimrDescriptorSet descriptorSet;
    private final GlobalGrid globalGrid;
    private final GeometryBandToGridMapper mapper;
    private final NetcdfCimrGeometryFactory geometryFactory;
    private final NetcdfCimrBandFactory bandFactory;

    private final Map<CimrBandDescriptor, GridBandDataSource> bandCache = new ConcurrentHashMap<>();


    public CimrReaderContext(NetcdfFile ncFile,
                             CimrDescriptorSet descriptorSet,
                             GlobalGrid globalGrid,
                             NetcdfCimrGeometryFactory geomFactory,
                             NetcdfCimrBandFactory bandFactory) {
        this.ncFile = ncFile;
        this.descriptorSet = descriptorSet;
        this.globalGrid = globalGrid;
        this.mapper = new GeometryBandToGridMapper();
        this.geometryFactory = geomFactory;
        this.bandFactory = bandFactory;
    }


    public GlobalGrid getGlobalGrid() {
        return this.globalGrid;
    }

    public CimrDescriptorSet getDescriptorSet() {
        return this.descriptorSet;
    }

    public GridBandDataSource getOrCreateGridForVariable(CimrBandDescriptor varDesc, boolean useAverage) {
        return this.bandCache.computeIfAbsent(varDesc, d -> {
            try {
                CimrGeometry geom = getOrCreateGeometry(d);
                CimrGeometryBand geometryBand = this.bandFactory.createGeometryBand(d, geom);
                CimrGridBuilder gridBuilder = new CimrGridBuilder(this.mapper);
                return gridBuilder.build(geometryBand, this.globalGrid, useAverage);
            } catch (IOException | InvalidRangeException e) {
                throw new RuntimeException("Failed to build grid for variable " + d.getName(), e);
            }
        });
    }

    public CimrGeometry getOrCreateGeometry(CimrBandDescriptor varDesc) {
        try {
            return this.geometryFactory.getOrCreateGeometry(varDesc);
        } catch (IOException | InvalidRangeException e) {
            throw new RuntimeException("Failed to build geometry for variable " + varDesc.getName(), e);
        }
    }

    public void clearCache() {
        this.bandCache.clear();
        this.geometryFactory.clearCache();
    }
}
