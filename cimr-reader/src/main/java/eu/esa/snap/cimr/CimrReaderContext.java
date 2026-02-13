package eu.esa.snap.cimr;

import eu.esa.snap.cimr.cimr.*;
import eu.esa.snap.cimr.grid.*;
import eu.esa.snap.cimr.netcdf.NetcdfCimrFootprintFactory;
import eu.esa.snap.cimr.netcdf.NetcdfCimrGeometryFactory;
import eu.esa.snap.cimr.netcdf.NetcdfCimrBandFactory;
import ucar.ma2.InvalidRangeException;
import ucar.nc2.NetcdfFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public class CimrReaderContext {

    private final NetcdfFile ncFile;
    private final CimrDescriptorSet descriptorSet;
    private final CimrGrid cimrGrid;
    private final GeometryBandToGridMapper mapper;
    private final NetcdfCimrGeometryFactory geometryFactory;
    private final NetcdfCimrBandFactory bandFactory;
    private final NetcdfCimrFootprintFactory footprintFactory;

    private final Map<String, CimrGeometryBand> geometryBandCache = new ConcurrentHashMap<>();
    private final Map<String, List<CimrFootprintShape>> footprintCache = new ConcurrentHashMap<>();


    public CimrReaderContext(NetcdfFile ncFile,
                             CimrDescriptorSet descriptorSet,
                             CimrGrid cimrGrid,
                             NetcdfCimrGeometryFactory geomFactory,
                             NetcdfCimrBandFactory bandFactory) {
        this.ncFile = ncFile;
        this.descriptorSet = descriptorSet;
        this.cimrGrid = cimrGrid;
        this.mapper = new GeometryBandToGridMapper();
        this.geometryFactory = geomFactory;
        this.bandFactory = bandFactory;
        this.footprintFactory = new NetcdfCimrFootprintFactory();
    }


    public CimrGrid getGlobalGrid() {
        return this.cimrGrid;
    }

    public CimrDescriptorSet getDescriptorSet() {
        return this.descriptorSet;
    }

    public GridBandDataSource getOrCreateGridForVariable(CimrBandDescriptor varDesc, boolean useAverage) {
        CimrGeometryBand geometryBand = getOrCreateGeometryBand(varDesc);
        CimrGridBuilder gridBuilder = new CimrGridBuilder(this.mapper);
        return gridBuilder.build(geometryBand, this.cimrGrid, useAverage);
    }

    public CimrGeometry getOrCreateGeometry(CimrBandDescriptor varDesc) {
        try {
            return this.geometryFactory.getOrCreateGeometry(varDesc);
        } catch (IOException | InvalidRangeException e) {
            throw new RuntimeException("Failed to build geometry for variable " + varDesc.getName(), e);
        }
    }

    private CimrGeometryBand getOrCreateGeometryBand(CimrBandDescriptor varDesc) {
        String key = getGeometryBandKey(varDesc);
        return this.geometryBandCache.computeIfAbsent(key, k -> {
            try {
                CimrGeometry geom = getOrCreateGeometry(varDesc);
                return this.bandFactory.createGeometryBand(varDesc, geom);
            } catch (IOException | InvalidRangeException e) {
                throw new RuntimeException("Failed to build geometry band for variable " + varDesc.getName(), e);
            }
        });
    }

    private String getGeometryBandKey(CimrBandDescriptor varDesc) {
        return varDesc.getBand().name() + ":" + varDesc.getValueVarName() + ":" + varDesc.getFeedIndex();
    }

    public CimrFootprints getOrCreateFootprints(CimrBandDescriptor varDesc) {
        String key = getFootprintKey(varDesc);

        List<CimrFootprintShape> shapes = this.footprintCache.computeIfAbsent(key, d -> {
            CimrBandDescriptor minorAxisDesc = this.descriptorSet.getTpVariableByName(varDesc.getFootprintVars()[0]);
            CimrBandDescriptor majorAxisDesc = this.descriptorSet.getTpVariableByName(varDesc.getFootprintVars()[1]);
            CimrBandDescriptor angleDesc = this.descriptorSet.getTpVariableByName(varDesc.getFootprintVars()[2]);

            CimrGeometryBand geometryBand = getOrCreateGeometryBand(varDesc);
            CimrGeometryBand minorAxisBand = getOrCreateGeometryBand(minorAxisDesc);
            CimrGeometryBand majorAxisBand = getOrCreateGeometryBand(majorAxisDesc);
            CimrGeometryBand angleBand = getOrCreateGeometryBand(angleDesc);

            return footprintFactory.createFootprintShapes(geometryBand, minorAxisBand, majorAxisBand, angleBand);
        });

        CimrGeometryBand geometryBand = getOrCreateGeometryBand(varDesc);
        List<Double> values = this.footprintFactory.getFootprintValues(geometryBand);

        return new CimrFootprints(shapes, values);
    }

    private String getFootprintKey(CimrBandDescriptor varDesc) {
        return varDesc.getBand().name() + ":" + varDesc.getFeedIndex();
    }

    public void clearCache() {
        this.geometryBandCache.clear();
        this.footprintCache.clear();
        this.geometryFactory.clearCache();
    }
}
