package eu.esa.snap.cimr;

import eu.esa.snap.cimr.cimr.CimrDescriptorSet;
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
import ucar.ma2.InvalidRangeException;
import ucar.nc2.NetcdfFile;

import java.io.IOException;


public class CimrReaderContextFactory {


    static final String FORMAT_VERSION_ATTRIBUTE = "format_version";


    private CimrReaderContextFactory() {}


    static CimrReaderContext create(NetcdfFile ncFile) throws IOException, InvalidRangeException {
        CimrProductDescriptor productDescriptor = loadProductDescriptor(ncFile);
        CimrDescriptorSet descriptorSet = expandDescriptorSet(productDescriptor);
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
}
