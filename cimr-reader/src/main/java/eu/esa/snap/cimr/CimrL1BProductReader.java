package eu.esa.snap.cimr;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.snap.cimr.cimr.*;
import eu.esa.snap.cimr.dddb.descriptor.CimrBandDescriptor;
import eu.esa.snap.cimr.metadata.CimrProductMetadata;
import eu.esa.snap.cimr.metadata.CimrProductMetadataReader;
import org.esa.snap.core.dataio.AbstractProductReader;
import org.esa.snap.core.dataio.ProductReaderPlugIn;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.dataio.netcdf.util.NetcdfFileOpener;
import ucar.nc2.NetcdfFile;

import java.awt.*;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;


public class CimrL1BProductReader extends AbstractProductReader {


    static final String PRODUCT_TYPE = "CIMR_L1B";

    private NetcdfFile ncFile;
    private CimrReaderContext readerContext;


    public CimrL1BProductReader(ProductReaderPlugIn readerPlugIn) {
        super(readerPlugIn);
    }


    @Override
    protected Product readProductNodesImpl() throws IOException {
        final String path = getInputPath();

        try {
            this.ncFile = NetcdfFileOpener.open(path);
            assert this.ncFile != null;

            this.readerContext = CimrReaderContextFactory.create(this.ncFile);
            CimrGridProduct cimrGridProduct = CimrGridProduct.buildLazy(this.readerContext, false);

            CimrProductMetadata productMetadata = CimrProductMetadataReader.read(this.ncFile, path, PRODUCT_TYPE);
            return CimrSnapProductBuilder.buildProduct(productMetadata, cimrGridProduct, path, this.readerContext.getAutoGrouping());

        } catch (Exception e) {
            closeAfterFailedRead(e);
            throw new IOException("Failed to read CIMR product from " + path, e);
        }
    }

    @Override
    protected void readBandRasterDataImpl(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight, int sourceStepX, int sourceStepY, Band destBand, int destOffsetX, int destOffsetY, int destWidth, int destHeight, ProductData destBuffer, ProgressMonitor pm) throws IOException {
        final RenderedImage image = destBand.getSourceImage();
        final Raster data = image.getData(new Rectangle(sourceOffsetX, sourceOffsetY, sourceWidth, sourceHeight));

        int destIndex = 0;
        for (int destY = 0; destY < destHeight; destY++) {
            final int sourceY = sourceOffsetY + destY * sourceStepY;
            for (int destX = 0; destX < destWidth; destX++) {
                final int sourceX = sourceOffsetX + destX * sourceStepX;
                destBuffer.setElemDoubleAt(destIndex++, data.getSampleDouble(sourceX, sourceY, 0));
            }
        }
    }

    @Override
    public void close() throws IOException {
        try {
            if (this.readerContext != null) {
                this.readerContext.clearCache();
            }
        } finally {
            this.readerContext = null;
            try {
                if (this.ncFile != null) {
                    this.ncFile.close();
                }
            } finally {
                this.ncFile = null;
                super.close();
            }
        }
    }

    public CimrFootprints getFootprints(String name) {
        CimrBandDescriptor desc = this.readerContext.getDescriptorSet().getMeasurementByName(name);
        if (desc == null) {
            desc = this.readerContext.getDescriptorSet().getTpVariableByName(name);
        }
        if (desc == null) {
            return new CimrFootprints( List.of(), List.of());
        }
        return this.readerContext.getOrCreateFootprints(desc);
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

    private void closeAfterFailedRead(Exception readFailure) {
        try {
            close();
        } catch (IOException closeException) {
            readFailure.addSuppressed(closeException);
        }
    }
}
