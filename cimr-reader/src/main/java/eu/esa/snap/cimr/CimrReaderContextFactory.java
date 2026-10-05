package eu.esa.snap.cimr;

import eu.esa.snap.cimr.cimr.CimrDescriptorSet;
import eu.esa.snap.cimr.cimr.CimrBandDescriptor;
import eu.esa.snap.cimr.cimr.CimrDimensions;
import eu.esa.snap.cimr.dddb.CimrDDDB;
import eu.esa.snap.cimr.dddb.CimrDescriptorExpander;
import eu.esa.snap.cimr.dddb.CimrProductDescriptor;
import eu.esa.snap.cimr.grid.CimrBoundingBox;
import eu.esa.snap.cimr.grid.CimrGrid;
import eu.esa.snap.cimr.grid.CimrGridFactory;
import eu.esa.snap.cimr.netcdf.NetcdfCimrBandFactory;
import eu.esa.snap.cimr.netcdf.NetcdfCimrGeometryFactory;
import eu.esa.snap.cimr.netcdf.NcUtil;
import org.esa.snap.dataio.netcdf.util.DataTypeUtils;
import ucar.ma2.InvalidRangeException;
import ucar.nc2.Group;
import ucar.nc2.NetcdfFile;
import ucar.nc2.Variable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


public class CimrReaderContextFactory {


    static final String FORMAT_VERSION_ATTRIBUTE = "format_version";


    private CimrReaderContextFactory() {}


    static CimrReaderContext create(NetcdfFile ncFile) throws IOException, InvalidRangeException {
        CimrProductDescriptor productDescriptor = loadProductDescriptor(ncFile);
        CimrDescriptorSet descriptorSet = withRasterDataTypes(ncFile, expandDescriptorSet(productDescriptor));
        CimrDimensions dimensions = CimrDimensions.from(ncFile);

        NetcdfCimrGeometryFactory geometryFactory = new NetcdfCimrGeometryFactory(ncFile, descriptorSet.getGeometries(), dimensions);
        CimrBoundingBox bBox = geometryFactory.getBoundingBox(CimrGridFactory.DEFAULT_CELL_SIZE_DEG);

        CimrGrid cimrGrid = CimrGridFactory.createPlateCarreeFromBoundingBox(bBox);
        NetcdfCimrBandFactory bandFactory = new NetcdfCimrBandFactory(ncFile, dimensions);

        return new CimrReaderContext(ncFile, descriptorSet, productDescriptor.getAutoGrouping(), cimrGrid, geometryFactory, bandFactory);
    }

    static CimrDescriptorSet loadDescriptorSet(NetcdfFile ncFile) throws IOException {
        return expandDescriptorSet(loadProductDescriptor(ncFile));
    }

    private static CimrProductDescriptor loadProductDescriptor(NetcdfFile ncFile) throws IOException {
        final CimrDDDB dddb = CimrDDDB.getInstance();
        return dddb.getProductDescriptor(CimrL1BProductReader.PRODUCT_TYPE, getFormatVersion(ncFile));
    }

    private static CimrDescriptorSet expandDescriptorSet(CimrProductDescriptor productDescriptor) throws IOException {
        final CimrDDDB dddb = CimrDDDB.getInstance();
        final CimrDescriptorExpander expander = new CimrDescriptorExpander(dddb);
        return expander.expand(productDescriptor);
    }

    static String getFormatVersion(NetcdfFile ncFile) {
        return NcUtil.getGlobalAttributeString(ncFile, FORMAT_VERSION_ATTRIBUTE);
    }

    static CimrDescriptorSet withRasterDataTypes(NetcdfFile ncFile, CimrDescriptorSet descriptorSet) {
        List<CimrBandDescriptor> measurements = withRasterDataTypes(ncFile, descriptorSet.getMeasurements());
        List<CimrBandDescriptor> geometries = withRasterDataTypes(ncFile, descriptorSet.getGeometries());
        List<CimrBandDescriptor> tiepointVariables = withRasterDataTypes(ncFile, descriptorSet.getTiepointVariables());

        return new CimrDescriptorSet(measurements, geometries, tiepointVariables);
    }

    private static List<CimrBandDescriptor> withRasterDataTypes(NetcdfFile ncFile, List<CimrBandDescriptor> descriptors) {
        List<CimrBandDescriptor> typedDescriptors = new ArrayList<>(descriptors.size());
        for (CimrBandDescriptor descriptor : descriptors) {
            typedDescriptors.add(withRasterDataType(ncFile, descriptor));
        }
        return typedDescriptors;
    }

    private static CimrBandDescriptor withRasterDataType(NetcdfFile ncFile, CimrBandDescriptor descriptor) {
        Group group = NcUtil.findGroupOrThrow(ncFile, descriptor.getGroupPath());
        Variable variable = NcUtil.findVarOrThrow(group, descriptor.getValueVarName());
        int rasterDataType = DataTypeUtils.getRasterDataType(variable);
        if (rasterDataType == -1) {
            throw new IllegalArgumentException("Unsupported raster data type for variable " + variable.getFullName()
                    + ": " + variable.getDataType());
        }
        return descriptor.withRasterDataType(rasterDataType);
    }
}
