package eu.esa.snap.cimr;

import org.esa.snap.core.dataio.DecodeQualification;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.dataio.ProductReaderPlugIn;
import org.esa.snap.core.util.io.FileUtils;
import org.esa.snap.core.util.io.SnapFileFilter;

import java.io.File;
import java.util.Locale;


public class CimrL1BProductReaderPlugin implements ProductReaderPlugIn {

    private static final String EXTENSION = ".nc";
    private static final String DATE_TIME = "\\d{8}T\\d{6}";
    private static final String PRODUCT_IDENTIFIER = "[A-Za-z]{2}-[A-Za-z0-9]{2,3}-[A-Za-z0-9]{1,15}-SAT-CIMR-1B";
    private static final String FREE_FORMAT = "G_[OVDIE]_" + DATE_TIME + "_" + DATE_TIME + "_[TCOV]_[NBR]_[A-Za-z0-9_]{3}";
    private static final String NAME_PATTERN = "^W_" + PRODUCT_IDENTIFIER + "_C_[A-Z0-9_]{3,4}_"
            + DATE_TIME + "_" + FREE_FORMAT + "\\.nc$";


    @Override
    public DecodeQualification getDecodeQualification(Object input) {
        final File file = input instanceof File ? (File) input : new File(input.toString());
        final String fileName = file.getName();

        final String extension = FileUtils.getExtension(fileName);
        if (!EXTENSION.equals(extension)) {
            return DecodeQualification.UNABLE;
        }

        if (isValidCimrL1BProduct(fileName)) {
            return DecodeQualification.INTENDED;
        }

        return DecodeQualification.UNABLE;
    }

    @Override
    public Class[] getInputTypes() {
        return new Class[]{File.class, String.class};
    }

    @Override
    public ProductReader createReaderInstance() {
        return new CimrL1BProductReader(this);
    }


    @Override
    public String[] getFormatNames() {
        return new String[]{"CIMR-L1B"};
    }

    @Override
    public String[] getDefaultFileExtensions() {
        return new String[]{EXTENSION};
    }

    @Override
    public String getDescription(Locale locale) {
        return "CIMR Level 1B Data Products in NetCDF Format";
    }

    @Override
    public SnapFileFilter getProductFileFilter() {
        return new SnapFileFilter(getFormatNames()[0], getDefaultFileExtensions(), getDescription(null));
    }


    private boolean isValidCimrL1BProduct(String fileName) {
        return fileName.matches(NAME_PATTERN);
    }
}
