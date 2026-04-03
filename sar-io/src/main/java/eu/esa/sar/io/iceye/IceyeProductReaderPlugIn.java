package eu.esa.sar.io.iceye;

import eu.esa.sar.commons.io.SARFileFilter;
import eu.esa.sar.commons.io.SARProductReaderPlugIn;
import eu.esa.sar.io.iceye.util.IceyeConstants;
import org.esa.snap.core.dataio.DecodeQualification;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.util.io.SnapFileFilter;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;

import java.io.File;
import java.nio.file.Path;
import java.util.Locale;

/**
 * @author Ahmad Hamouda
 */
public class IceyeProductReaderPlugIn implements SARProductReaderPlugIn {

    private final String[] FILE_EXTS = { ".TIF", ".H5", ".XML", ".JSON" };

    private static final String[] PRODUCT_PREFIX = new String[] {"ICEYE_"};
    private static final String PRODUCT_FORMAT = "ICEYE";

    private static final String PLUGIN_DESCRIPTION = "ICEYE Product Format";
    private static final Class[] VALID_INPUT_TYPES = new Class[]{Path.class, File.class, String.class};

    /**
     * Validate file extension and start
     *
     * @param path
     * @return check result
     */
    protected DecodeQualification checkProductQualification(final Path path) {
        final String fileName = path.getFileName().toString().toUpperCase();
        if(fileName.startsWith(IceyeConstants.ICEYE_FILE_PREFIX)) {
            for (String ext : FILE_EXTS) {
                if (fileName.endsWith(ext)) {
                    return DecodeQualification.INTENDED;
                }
            }
        }
        return DecodeQualification.UNABLE;
    }

    @Override
    public DecodeQualification getDecodeQualification(final Object input) {
        final Path path = ReaderUtils.getPathFromInput(input);
        if (path == null || path.getFileName() == null) {
            return DecodeQualification.UNABLE;
        }
        return this.checkProductQualification(path);
    }

    /**
     * Returns an array containing the classes that represent valid input types for this reader.
     * <p>
     * <p> Intances of the classes returned in this array are valid objects for the <code>setInput</code> method of the
     * <code>ProductReader</code> interface (the method will not throw an <code>InvalidArgumentException</code> in this
     * case).
     *
     * @return an array containing valid input types, never <code>null</code>
     */
    public Class[] getInputTypes() {
        return VALID_INPUT_TYPES;
    }

    /**
     * Creates an instance of the actual product reader class. This method should never return <code>null</code>.
     *
     * @return a new reader instance, never <code>null</code>
     */
    public ProductReader createReaderInstance() {
        return new IceyeProductReader(this);
    }

    public SnapFileFilter getProductFileFilter() {
        return new SARFileFilter(this);
    }

    /**
     * Gets the names of the product formats handled by this product I/O plug-in.
     *
     * @return the names of the product formats handled by this product I/O plug-in, never <code>null</code>
     */
    public String[] getFormatNames() {
        return new String[] {PRODUCT_FORMAT};
    }

    /**
     * Gets the default file extensions associated with each of the format names returned by the <code>{@link
     * #getFormatNames}</code> method. <p>The string array returned shall always have the same length as the array
     * returned by the <code>{@link #getFormatNames}</code> method. <p>The extensions returned in the string array shall
     * always include a leading colon ('.') character, e.g. <code>".hdf"</code>
     *
     * @return the default file extensions for this product I/O plug-in, never <code>null</code>
     */
    public String[] getDefaultFileExtensions() {
        return FILE_EXTS;
    }

    /**
     * Gets a short description of this plug-in. If the given locale is set to <code>null</code> the default locale is
     * used.
     * <p>
     * <p> In a GUI, the description returned could be used as tool-tip text.
     *
     * @param locale the local for the given decription string, if <code>null</code> the default locale is used
     * @return a textual description of this product reader/writer
     */
    public String getDescription(final Locale locale) {
        return PLUGIN_DESCRIPTION;
    }

    @Override
    public String[] getProductMetadataFileExtensions() {
        return FILE_EXTS;
    }

    @Override
    public String[] getProductMetadataFilePrefixes() {
        return PRODUCT_PREFIX;
    }
}
