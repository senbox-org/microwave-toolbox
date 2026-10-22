package eu.esa.snap.cimr;

import eu.esa.snap.cimr.cimr.*;
import eu.esa.snap.cimr.dddb.descriptor.CimrBandDescriptor;
import eu.esa.snap.cimr.dddb.descriptor.CimrDescriptorSet;
import eu.esa.snap.cimr.dddb.CimrProductDescriptor;
import eu.esa.snap.cimr.grid.*;
import eu.esa.snap.cimr.netcdf.NetcdfCimrFootprintFactory;
import eu.esa.snap.cimr.netcdf.NetcdfCimrGeometryFactory;
import eu.esa.snap.cimr.netcdf.NetcdfCimrBandFactory;
import ucar.ma2.InvalidRangeException;
import ucar.nc2.NetcdfFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;


public class CimrReaderContext {


    private final NetcdfFile ncFile;
    private final CimrProductDescriptor productDescriptor;
    private final CimrDescriptorSet descriptorSet;
    private final String autoGrouping;
    private final CimrGrid cimrGrid;
    private final GeometryBandToGridMapper mapper;
    private final NetcdfCimrGeometryFactory geometryFactory;
    private final NetcdfCimrBandFactory bandFactory;
    private final NetcdfCimrFootprintFactory footprintFactory;

    private final Map<String, CimrGeometryBand> footprintBandCache = new ConcurrentHashMap<>();
    private final Map<String, List<CimrFootprintShape>> footprintCache = new ConcurrentHashMap<>();


    public CimrReaderContext(NetcdfFile ncFile,
                             CimrProductDescriptor productDescriptor,
                             CimrDescriptorSet descriptorSet,
                             String autoGrouping,
                             CimrGrid cimrGrid,
                             NetcdfCimrGeometryFactory geomFactory,
                             NetcdfCimrBandFactory bandFactory) {
        this.ncFile = ncFile;
        this.productDescriptor = Objects.requireNonNull(productDescriptor, "productDescriptor");
        this.descriptorSet = descriptorSet;
        this.autoGrouping = autoGrouping;
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

    public CimrProductDescriptor getProductDescriptor() {
        return this.productDescriptor;
    }

    public String getAutoGrouping() {
        return this.autoGrouping;
    }

    public GridBandDataSource getOrCreateGridForVariable(CimrBandDescriptor varDesc) {
        CimrGeometryBand geometryBand = createGeometryBand(varDesc);
        CimrGridBuilder gridBuilder = new CimrGridBuilder(this.mapper);
        return gridBuilder.build(varDesc, geometryBand, this.cimrGrid);
    }

    public CimrGeometry getOrCreateGeometry(CimrBandDescriptor varDesc) {
        try {
            return this.geometryFactory.getOrCreateGeometry(varDesc);
        } catch (IOException | InvalidRangeException e) {
            throw new RuntimeException("Failed to build geometry for variable " + varDesc.getName(), e);
        }
    }

    private CimrGeometryBand createGeometryBand(CimrBandDescriptor varDesc) {
        try {
            CimrGeometry geom = getOrCreateGeometry(varDesc);
            return this.bandFactory.createGeometryBand(varDesc, geom);
        } catch (IOException | InvalidRangeException e) {
            throw new RuntimeException("Failed to build geometry band for variable " + varDesc.getName(), e);
        }
    }

    private CimrGeometryBand getOrCreateFootprintBand(CimrBandDescriptor varDesc) {
        String key = getGeometryBandKey(varDesc);
        return this.footprintBandCache.computeIfAbsent(key, k -> createGeometryBand(varDesc));
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

            CimrGeometryBand geometryBand = getOrCreateFootprintBand(varDesc);
            CimrGeometryBand minorAxisBand = getOrCreateFootprintBand(minorAxisDesc);
            CimrGeometryBand majorAxisBand = getOrCreateFootprintBand(majorAxisDesc);
            CimrGeometryBand angleBand = getOrCreateFootprintBand(angleDesc);

            return footprintFactory.createFootprintShapes(geometryBand, minorAxisBand, majorAxisBand, angleBand);
        });

        CimrGeometryBand geometryBand = getOrCreateFootprintBand(varDesc);
        List<Double> values = this.footprintFactory.getFootprintValues(geometryBand);

        return new CimrFootprints(shapes, values);
    }

    private String getFootprintKey(CimrBandDescriptor varDesc) {
        return varDesc.getBand().name() + ":" + varDesc.getFeedIndex();
    }

    public void clearCache() {
        this.footprintBandCache.clear();
        this.footprintCache.clear();
        this.geometryFactory.clearCache();
    }
}
