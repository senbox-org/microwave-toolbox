/*
 * Copyright (C) 2015 by Array Systems Computing Inc. http://www.array.ca
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
package eu.esa.sar.io.imageio;

import org.esa.snap.core.dataio.DecodeQualification;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.dataio.ProductReaderPlugIn;
import org.esa.snap.core.util.io.SnapFileFilter;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The ReaderPlugIn for ImageIO products.
 */
public class ImageIOReaderPlugIn implements ProductReaderPlugIn {

    private final static String[] FORMAT_NAMES = getFormatNamesList();
    private final static String[] FORMAT_FILE_EXTENSIONS = getFormatFileExtensions();
    private final static String[] IMAGEIO_FILE_EXTENSIONS = getPrunedImageIOExtensions();
    private final static String PLUGIN_DESCRIPTION = "ImageIO Products";
    private final Class[] VALID_INPUT_TYPES = new Class[]{Path.class, File.class, String.class};

    private static String[] getFormatNamesList() {
        final List<String> names = new ArrayList<>(40);
        names.add("ImageIO");
        names.addAll(Arrays.asList(ImageIO.getReaderFormatNames()));
        names.remove("geotiff");
        return names.toArray(new String[0]);
    }

    private static String[] getFormatFileExtensions() {

        final List<String> extList = new ArrayList<>(20);
        extList.addAll(Arrays.asList(ImageIO.getReaderFileSuffixes()));

        exludeExtensions(extList);

        // BEST extensions
        //addAllBestExtensions(extList);

        return extList.toArray(new String[0]);
    }

    private static void addAllBestExtensions(final List<String> extList) {
        addBESTExt(extList, "XT");
        addBESTExt(extList, "AP");
        addBESTExt(extList, "PA");
        addBESTExt(extList, "CA");
        addBESTExt(extList, "IF");
        addBESTExt(extList, "FI");
        addBESTExt(extList, "DB");
        addBESTExt(extList, "SG");
        addBESTExt(extList, "OP");
        addBESTExt(extList, "GC");
        addBESTExt(extList, "OV");
        addBESTExt(extList, "UN");
        addBESTExt(extList, "CR");
        addBESTExt(extList, "SF");
        addBESTExt(extList, "BS");
        addBESTExt(extList, "GA");
        //addBESTExt(extList, "AD");
    }

    private static void addBESTExt(final List<String> extList, final String ext) {
        extList.add(ext + 'i');
        extList.add(ext + 'f');
        extList.add(ext + 'c');
        extList.add(ext + 's');
        extList.add(ext + 't');
        extList.add(ext + 'r');
    }

    private static void exludeExtensions(final List<String> extList) {
        extList.remove("jpeg");
        extList.remove("jls");
        extList.remove("jfif");
        extList.remove("tiff");
        extList.remove("tif");
        extList.remove("n1");
    }

    private static String[] getPrunedImageIOExtensions() {
        final List<String> extList = new ArrayList<>(20);
     /*   extList.addAll(Arrays.asList(ImageIO.getReaderFileSuffixes()));

        excludeExtensions(extList);
          */

        extList.add("bmp");
        extList.add("gif");
        extList.add("jpg");
        extList.add("png");

        return extList.toArray(new String[0]);
    }

    /**
     * Checks whether the given object is an acceptable input for this product reader and if so, the method checks if it
     * is capable of decoding the input's content.
     *
     * @param input any input object
     * @return true if this product reader can decode the given input, otherwise false.
     */
    public DecodeQualification getDecodeQualification(final Object input) {
        final Path path = ReaderUtils.getPathFromInput(input);
        if (path == null) {
            return DecodeQualification.UNABLE;
        }

        final Path parentDir = path.getParent();
        if (Files.isDirectory(path) && parentDir != null && Files.isDirectory(parentDir)) {
            return checkProductQualification(path);
        }
        return DecodeQualification.UNABLE;
    }

    private static DecodeQualification checkProductQualification(final Path path) {
        final String fileExt = path.getFileName().toString().toLowerCase();
        for (String ext : FORMAT_FILE_EXTENSIONS) {
            if (!ext.isEmpty() && fileExt.endsWith(ext.toLowerCase())) {
                if (ext.equalsIgnoreCase("tif") || ext.equalsIgnoreCase("tiff"))
                    return DecodeQualification.SUITABLE;
                return DecodeQualification.INTENDED;
            }
        }

        return DecodeQualification.UNABLE;
    }

    /**
     * Returns an array containing the classes that represent valid input types for this reader.
     * <p/>
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
        return new ImageIOReader(this);
    }

    public SnapFileFilter getProductFileFilter() {
        return new FileFilter();
    }

    /**
     * Gets the names of the product formats handled by this product I/O plug-in.
     *
     * @return the names of the product formats handled by this product I/O plug-in, never <code>null</code>
     */
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
    public String[] getDefaultFileExtensions() {
        return FORMAT_FILE_EXTENSIONS;
    }

    /**
     * Gets a short description of this plug-in. If the given locale is set to <code>null</code> the default locale is
     * used.
     * <p/>
     * <p> In a GUI, the description returned could be used as tool-tip text.
     *
     * @param locale the local for the given decription string, if <code>null</code> the default locale is used
     * @return a textual description of this product reader/writer
     */
    public String getDescription(final Locale locale) {
        return PLUGIN_DESCRIPTION;
    }

    public static class FileFilter extends SnapFileFilter {

        public FileFilter() {
            super(FORMAT_NAMES[0], IMAGEIO_FILE_EXTENSIONS, PLUGIN_DESCRIPTION);
        }

        /**
         * Tests whether or not the given file is accepted by this filter. The default implementation returns
         * <code>true</code> if the given file is a directory or the path string ends with one of the registered extensions.
         * if no extension are defined, the method always returns <code>true</code>
         *
         * @param file the file to be or not be accepted.
         * @return <code>true</code> if given file is accepted by this filter
         */
        public boolean accept(final File file) {
            if (super.accept(file)) {
                if (file.isDirectory() || checkExtension(file)) {
                    return true;
                }
            }
            return false;
        }

    }
}
