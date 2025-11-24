package eu.esa.snap.cimr;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.snap.cimr.cimr.*;
import eu.esa.snap.cimr.config.CimrConfigLoader;
import eu.esa.snap.cimr.grid.GlobalGrid;
import eu.esa.snap.cimr.grid.GlobalGridFactory;
import eu.esa.snap.cimr.netcdf.NetcdfCimrGeometryFactory;
import eu.esa.snap.cimr.netcdf.NetcdfCimrBandFactory;
import org.esa.snap.core.dataio.AbstractProductReader;
import org.esa.snap.core.dataio.ProductReaderPlugIn;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.dataio.netcdf.util.NetcdfFileOpener;
import ucar.nc2.NetcdfFile;

import java.io.File;
import java.io.IOException;


public class CimrL1BProductReader extends AbstractProductReader {

    NetcdfFile ncFile;
    CimrReaderContext readerContext;


    public CimrL1BProductReader(ProductReaderPlugIn readerPlugIn) {
        super(readerPlugIn);
    }

    @Override
    protected Product readProductNodesImpl() throws IOException {
        final String path = getInputPath();

        try {
            this.ncFile = NetcdfFileOpener.open(path);
            assert this.ncFile != null;

            this.readerContext = initContext(this.ncFile);
            CimrGridProduct cimrGridProduct = CimrGridProduct.buildLazy(this.readerContext, true);

            // TODO: name and type from Metadata
            Product snapProduct = CimrSnapProductBuilder.buildProduct("CIMR_L1B", "CIMR_L1B", cimrGridProduct, path);

            return snapProduct;

        } catch (Exception e) {
            throw new IOException("Failed to read CIMR product from " + path, e);
        }
    }

    @Override
    protected void readBandRasterDataImpl(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight, int sourceStepX, int sourceStepY, Band destBand, int destOffsetX, int destOffsetY, int destWidth, int destHeight, ProductData destBuffer, ProgressMonitor pm) throws IOException {
        // TODO add direct exception here, this should not be called
    }

    @Override
    public void close() throws IOException {
        if (this.ncFile != null) {
            this.ncFile.close();
            this.ncFile = null;
        }
        if (this.readerContext != null) {
            this.readerContext.clearCache();
            this.readerContext = null;
        }
        super.close();
    }


    private String getInputPath() {
        Object input = getInput();
        if (!(input instanceof String || input instanceof File)) {
            throw new IllegalArgumentException("Unsupported input: " + input);
        }

        if (input instanceof File) {
            return ((File) input).getPath();
        }
        return (String) input;
    }

    private CimrReaderContext initContext(NetcdfFile ncFile) throws IOException {
        CimrDescriptorSet descriptorSet = CimrConfigLoader.load("cimr-l1b-config.json");
        CimrDimensions dimensions = CimrDimensions.from(ncFile);

        GlobalGrid globalGrid = GlobalGridFactory.createGlobalPlateCarree(0.1);
        NetcdfCimrGeometryFactory geometryFactory = new NetcdfCimrGeometryFactory(ncFile, descriptorSet.getGeometries(), dimensions);
        NetcdfCimrBandFactory bandFactory = new NetcdfCimrBandFactory(ncFile, dimensions);

        return new CimrReaderContext(ncFile, descriptorSet, globalGrid, geometryFactory, bandFactory);
    }
}
