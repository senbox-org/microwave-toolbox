package eu.esa.sar.io.nisar;

import eu.esa.sar.io.nisar.util.NisarXConstants;
import eu.esa.sar.io.netcdf.NetCDFReaderPlugIn;
import org.esa.snap.core.dataio.DecodeQualification;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;

import java.nio.file.Path;


public class NisarProductReaderPlugIn extends NetCDFReaderPlugIn {

    public NisarProductReaderPlugIn() {
        FORMAT_NAMES = NisarXConstants.getNisarFormatNames();
        FORMAT_FILE_EXTENSIONS = NisarXConstants.getNisarFormatFileExtensions();
        PLUGIN_DESCRIPTION = NisarXConstants.NISAR_PLUGIN_DESCRIPTION;
    }

    /**
     * Validate file extension and start
     *
     * @param path
     * @return check result
     */
    @Override
    protected DecodeQualification checkProductQualification(final Path path) {
        final String fileName = path.getFileName().toString().toUpperCase();
        if(fileName.startsWith(NisarXConstants.NISAR_FILE_PREFIX)) {
            if (fileName.endsWith(".H5") || fileName.endsWith(".TIF") || fileName.endsWith(".XML")) {
                return DecodeQualification.INTENDED;
            }
        }
        return DecodeQualification.UNABLE;
    }

    @Override
    public DecodeQualification getDecodeQualification(final Object input) {
        final Path path = ReaderUtils.getPathFromInput(input);
        return path == null ? DecodeQualification.UNABLE : this.checkProductQualification(path);
    }

    /**
     * Creates an instance of the actual product reader class.
     *
     * @return a new reader instance
     */
    @Override
    public ProductReader createReaderInstance() {
        return new NisarProductReader(this);
    }

}
