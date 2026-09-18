package eu.esa.sar.iogdal.iceye;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.io.SARReader;
import it.geosolutions.imageio.plugins.tiff.TIFFField;
import it.geosolutions.imageioimpl.plugins.tiff.TIFFImageMetadata;
import it.geosolutions.imageioimpl.plugins.tiff.TIFFImageReader;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.dataio.ProductReaderPlugIn;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.util.io.FileUtils;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Dispatches an ICEYE GeoTIFF delivery to the reader that understands its
 * metadata schema.
 * <p>
 * ICEYE ships two incompatible flavours of GeoTIFF product. Both are named
 * {@code ICEYE_*.tif} and both carry their metadata in the TIFF's GDAL metadata
 * tag, so the file name does not identify them - the name of the metadata item
 * does:
 * <ul>
 *   <li>{@code ICEYE_PROPERTIES} - the Open Data Initiative delivery, a flat
 *       STAC property set, handled by {@link IceyeOpenDataProductReader};</li>
 *   <li>{@code METADATA_JSON} - the AML/CPX delivery, a nested document,
 *       handled by {@link IceyeAMLCPXProductReader};</li>
 *   <li>neither - the legacy GRD delivery, whose metadata is spread over
 *       individual GDAL items, handled by {@link IceyeGRDProductReader}.</li>
 * </ul>
 *
 * @author Ahmad Hamouda
 */
public class IceyeCOGReader extends SARReader {

    private static final int TIFF_TAG_GDAL_METADATA = 42112;

    private ProductReader reader;

    /**
     * Constructs a new abstract product reader.
     *
     * @param readerPlugIn the reader plug-in which created this reader, can be
     *                     <code>null</code> for internal reader
     *                     implementations
     */
    public IceyeCOGReader(final ProductReaderPlugIn readerPlugIn) {
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
    protected Product readProductNodesImpl() throws IOException {
        final Path inputPath = ReaderUtils.getPathFromInput(getInput());
        if (inputPath == null) {
            throw new IOException("Unable to interpret " + getInput() + " as a file path");
        }

        final File inputFile = resolveImageFile(inputPath.toFile());
        reader = createReaderFor(inputFile);
        return reader.readProductNodes(inputFile, getSubsetDef());
    }

    /**
     * The side-car .json and .xml carry the metadata, the .tif carries the
     * raster; whichever the user picked, the reader needs the raster. HDF5
     * deliveries are not this reader's business - they go to the native ICEYE
     * reader in sar-io.
     */
    private static File resolveImageFile(final File input) throws IOException {
        final String name = input.getName().toLowerCase();
        if (name.endsWith(IceyeOpenDataConstants.TIF_EXTENSION)) {
            return input;
        }
        final File tif = FileUtils.exchangeExtension(input, IceyeOpenDataConstants.TIF_EXTENSION);
        if (!tif.exists()) {
            throw new IOException("Cannot open " + input.getName() + ": the image file "
                    + tif.getName() + " is not beside it");
        }
        return tif;
    }

    private ProductReader createReaderFor(final File inputFile) throws IOException {
        final String metadataItem = findMetadataItemName(inputFile);
        final String name = inputFile.getName().toLowerCase();

        if (IceyeOpenDataConstants.ICEYE_PROPERTIES.equals(metadataItem)) {
            return name.contains("slc") || name.contains("cpx")
                    ? new IceyeOpenDataSLCReader(getReaderPlugIn())
                    : new IceyeOpenDataGRDReader(getReaderPlugIn());
        }
        if (IceyeOpenDataConstants.METADATA_JSON.equals(metadataItem)) {
            if (name.endsWith("aml.tif")) {
                return new IceyeAMLProductReader(getReaderPlugIn());
            }
            return new IceyeCPXProductReader(getReaderPlugIn());
        }
        return new IceyeGRDProductReader(getReaderPlugIn());
    }

    /**
     * Returns whichever of the two known metadata items the COG carries, or
     * null if it carries neither.
     */
    private static String findMetadataItemName(final File inputFile) throws IOException {
        try (ImageInputStream stream = ImageIO.createImageInputStream(inputFile)) {
            if (stream == null) {
                throw new IOException("Unable to open " + inputFile.getName());
            }
            final java.util.Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            while (readers.hasNext()) {
                final ImageReader imageReader = readers.next();
                if (!(imageReader instanceof TIFFImageReader)) {
                    continue;
                }
                final TIFFImageReader tiffReader = (TIFFImageReader) imageReader;
                tiffReader.setInput(stream, false);
                final TIFFImageMetadata tiffMetadata = (TIFFImageMetadata) tiffReader.getImageMetadata(0);
                if (tiffMetadata == null) {
                    return null;
                }
                final TIFFField field = tiffMetadata.getRootIFD().getTIFFField(TIFF_TAG_GDAL_METADATA);
                if (field == null) {
                    return null;
                }
                final String xml = field.getAsString(0);
                if (xml.contains(IceyeOpenDataConstants.ICEYE_PROPERTIES)) {
                    return IceyeOpenDataConstants.ICEYE_PROPERTIES;
                }
                if (xml.contains(IceyeOpenDataConstants.METADATA_JSON)) {
                    return IceyeOpenDataConstants.METADATA_JSON;
                }
                return null;
            }
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        if (reader != null) {
            reader.close();
            reader = null;
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
        // All band data is accessed via source images set from GDAL/GeoTiff readers
    }
}
