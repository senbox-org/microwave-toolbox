package eu.esa.sar.io.nisar;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.io.SARReader;
import eu.esa.sar.io.nisar.util.NisarXConstants;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.dataio.ProductReaderPlugIn;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.core.util.io.FileUtils;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

public class NisarProductReader extends SARReader {

    private String productType = null;
    private ProductReader reader;

    /**
     * Constructs a new abstract product reader.
     *
     * @param readerPlugIn the reader plug-in which created this reader, can be <code>null</code> for internal reader
     *                     implementations
     */
    public NisarProductReader(final ProductReaderPlugIn readerPlugIn) {
        super(readerPlugIn);
    }

    /**
     * Provides an implementation of the <code>readProductNodes</code> interface method. Clients implementing this
     * method can be sure that the input object and eventually the subset information has already been set.
     * <p/>
     * <p>This method is called as a last step in the <code>readProductNodes(input, subsetInfo)</code> method.
     */
    @Override
    protected Product readProductNodesImpl() {
        try {
            final Path inputPath = ReaderUtils.getPathFromInput(getInput());
            if(inputPath == null) {
                throw new Exception("Unable to read " + getInput());
            }
            File inputFile = inputPath.toFile();
            String fileName = inputFile.getName().toLowerCase();

            if(fileName.startsWith(NisarXConstants.NISAR_FILE_PREFIX.toLowerCase())) {
                if (fileName.endsWith(".xml")) {
                    inputFile = FileUtils.exchangeExtension(inputFile, ".h5");
                    if(!inputFile.exists()) {
                        inputFile = FileUtils.exchangeExtension(inputFile, ".tif");
                    }
                    fileName = inputFile.getName().toLowerCase();
                }

                if (fileName.contains("_rslc_")) {
                    productType = "RSLC";
                    reader = new NisarRSLCProductReader(getReaderPlugIn());
                } else if (fileName.contains("_roff_")) {
                    productType = "ROFF";
                    reader = new NisarROFFProductReader(getReaderPlugIn());
                } else if (fileName.contains("_rifg_")) {
                    productType = "RIFG";
                    reader = new NisarRIFGProductReader(getReaderPlugIn());
                } else if (fileName.contains("_gslc_")) {
                    productType = "GSLC";
                    reader = new NisarGSLCProductReader(getReaderPlugIn());
                } else if (fileName.contains("_gcov_")) {
                    productType = "GCOV";
                    reader = new NisarGCOVProductReader(getReaderPlugIn());
                }
            }
            return reader.readProductNodes(inputFile, getSubsetDef());
        } catch (Exception e) {
            SystemUtils.LOG.severe(e.getMessage());
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        if (reader != null) {
            reader.close();
        }
        super.close();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void readBandRasterDataImpl(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight,
                                          int sourceStepX, int sourceStepY, Band destBand, int destOffsetX,
                                          int destOffsetY, int destWidth, int destHeight, ProductData destBuffer,
                                          ProgressMonitor pm) throws IOException {
        if(productType.equals("RSLC")) {
            ((NisarRSLCProductReader)reader).callReadBandRasterData(sourceOffsetX, sourceOffsetY, sourceWidth, sourceHeight,
                    sourceStepX, sourceStepY, destBand, destOffsetX, destOffsetY, destWidth, destHeight, destBuffer, pm);
        } else if(productType.equals("ROFF")) {
            ((NisarROFFProductReader)reader).callReadBandRasterData(sourceOffsetX, sourceOffsetY, sourceWidth, sourceHeight,
                    sourceStepX, sourceStepY, destBand, destOffsetX, destOffsetY, destWidth, destHeight, destBuffer, pm);
        }
    }
}
