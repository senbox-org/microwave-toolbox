package eu.esa.sar.io.iceye;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.io.SARReader;
import eu.esa.sar.io.iceye.util.IceyeConstants;
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

/**
 * @author Ahmad Hamouda
 */
public class IceyeProductReader extends SARReader {

    private ProductReader reader;

    /**
     * Constructs a new abstract product reader.
     *
     * @param readerPlugIn the reader plug-in which created this reader, can be
     *                     <code>null</code> for internal reader
     *                     implementations
     */
    public IceyeProductReader(final ProductReaderPlugIn readerPlugIn) {
        super(readerPlugIn);
    }

    /**
     * Provides an implementation of the <code>readProductNodes</code> interface
     * method. Clients implementing this
     * method can be sure that the input object and eventually the subset
     * information has already been set.
     * <p/>
     * <p>
     * This method is called as a last step in the
     * <code>readProductNodes(input, subsetInfo)</code> method.
     */
    @Override
    protected Product readProductNodesImpl() {
        try {
            final Path inputPath = ReaderUtils.getPathFromInput(getInput());
            if (inputPath == null) {
                throw new Exception("Unable to read " + getInput());
            }
            File inputFile = inputPath.toFile();
            String fileName = inputFile.getName().toLowerCase();

            if (fileName.startsWith(IceyeConstants.ICEYE_FILE_PREFIX.toLowerCase())) {
                if (fileName.endsWith(".xml")) {
                    final File h5File = FileUtils.exchangeExtension(inputFile, ".h5");
                    if (h5File.exists()) {
                        inputFile = h5File;
                        fileName = inputFile.getName().toLowerCase();
                    }
                }

                if (fileName.endsWith(".h5")) {
                    reader = new IceyeSLCProductReader(getReaderPlugIn());
                }
            }

            if (reader == null) {
                throw new IOException("Unable to find HDF5 file for ICEYE product: " + inputPath);
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
        ((IceyeSLCProductReader) reader).callReadBandRasterData(sourceOffsetX, sourceOffsetY, sourceWidth,
                sourceHeight,
                sourceStepX, sourceStepY, destBand, destOffsetX, destOffsetY, destWidth, destHeight, destBuffer,
                pm);
    }
}
