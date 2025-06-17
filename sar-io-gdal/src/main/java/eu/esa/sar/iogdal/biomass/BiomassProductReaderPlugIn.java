/*
 * Copyright (C) 2025 by SkyWatch Space Applications Inc. http://www.skywatch.com
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */
package eu.esa.sar.iogdal.biomass;

import eu.esa.sar.commons.io.SARFileFilter;
import eu.esa.sar.commons.io.SARProductReaderPlugIn;
import org.esa.snap.core.dataio.DecodeQualification;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.util.io.SnapFileFilter;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import org.esa.snap.engine_utilities.util.ZipUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The ReaderPlugIn for Sentinel1 products.
 */
public class BiomassProductReaderPlugIn implements SARProductReaderPlugIn {

    private final static String[] FORMAT_NAMES = new String[]{"BIOMASS"};
    private final static String[] FORMAT_FILE_EXTENSIONS = new String[]{".xml", ".zip"};
    private final static String PLUGIN_DESCRIPTION = "BIOMASS Products";      /*I18N*/

    private final static String PRODUCT_PREFIX = "BIO_S";
    final static String PRODUCT_EXT = ".XML";

    private final static Class[] VALID_INPUT_TYPES = new Class[]{Path.class, File.class, String.class};

    private final static String ANNOTATION = "annotation";
    private final static String MEASUREMENT = "measurement";

    /**
     * Checks whether the given object is an acceptable input for this product reader and if so, the method checks if it
     * is capable of decoding the input's content.
     *
     * @param input any input object
     * @return true if this product reader can decode the given input, otherwise false.
     */
    @Override
    public DecodeQualification getDecodeQualification(final Object input) {
        Path path = ReaderUtils.getPathFromInput(input);
        if (path != null) {
            if(Files.isDirectory(path)) {
                File[] files = path.toFile().listFiles();
                for(File file : files) {
                    final String filename = file.getName().toLowerCase();
                    if (filename.startsWith(PRODUCT_PREFIX.toLowerCase()) && filename.endsWith(PRODUCT_EXT.toLowerCase())) {
                        return DecodeQualification.INTENDED;
                    }
                }
                return DecodeQualification.UNABLE;
            }

            if(path.getFileName() != null) {
                final String filename = path.getFileName().toString().toLowerCase();
                if (filename.startsWith(PRODUCT_PREFIX.toLowerCase()) && filename.endsWith(PRODUCT_EXT.toLowerCase())) {
                    return DecodeQualification.INTENDED;
                }
                if (filename.endsWith(".zip") && filename.startsWith(PRODUCT_PREFIX.toLowerCase()) &&
                        ZipUtils.findInZip(path.toFile(), PRODUCT_PREFIX.toLowerCase(), PRODUCT_EXT.toLowerCase())) {
                    return DecodeQualification.INTENDED;
                }
            }
        }

        return DecodeQualification.UNABLE;
    }

    /**
     * Creates an instance of the actual product reader class. This method should never return <code>null</code>.
     *
     * @return a new reader instance, never <code>null</code>
     */
    @Override
    public ProductReader createReaderInstance() {
        return new BiomassProductReader(this);
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
    @Override
    public Class[] getInputTypes() {
        return VALID_INPUT_TYPES;
    }

    @Override
    public SnapFileFilter getProductFileFilter() {
        return new SARFileFilter(this);
    }

    /**
     * Gets the names of the product formats handled by this product I/O plug-in.
     *
     * @return the names of the product formats handled by this product I/O plug-in, never <code>null</code>
     */
    @Override
    public String[] getFormatNames() {
        return FORMAT_NAMES;
    }

    /**
     * Gets the default file extensions associated with each of the format names returned by the <code>{@link
     * #getFormatNames}</code> method. <p>The string array returned shall always have the same length as the array
     * returned by the <code>{@link #getFormatNames}</code> method. <p>The extensions returned in the string array shall
     * always include a leading colon ('.') character, e.g. <code>".hdf"</code>
     *
     * @return the default file extensions for this product I/O plug-in, never <code>null</code>
     */
    @Override
    public String[] getDefaultFileExtensions() {
        return FORMAT_FILE_EXTENSIONS;
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
    @Override
    public String getDescription(final Locale locale) {
        return PLUGIN_DESCRIPTION;
    }

    @Override
    public String[] getProductMetadataFileExtensions() {
        return new String[] {PRODUCT_EXT};
    }

    @Override
    public String[] getProductMetadataFilePrefixes() {
        return new String[] {PRODUCT_PREFIX};
    }
}
