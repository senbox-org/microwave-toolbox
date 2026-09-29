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
 * The ReaderPlugIn for BIOMASS products.
 */
public class BiomassProductReaderPlugIn implements SARProductReaderPlugIn {

    private final static String[] FORMAT_NAMES = new String[]{"BIOMASS"};
    private final static String[] FORMAT_FILE_EXTENSIONS = new String[]{".xml", ".zip"};
    private final static String PLUGIN_DESCRIPTION = "BIOMASS Products";      /*I18N*/

    /**
     * Recognised filename prefixes for BIOMASS product directories / entry-point XML files.
     * <ul>
     *     <li>{@code BIO_S}  — Level-1 SAR products (SCS, DGM, STA).</li>
     *     <li>{@code BIO_FP} — Level-2 geophysical products (FH, FD, GN, AGB).</li>
     * </ul>
     */
    private final static String[] PRODUCT_PREFIXES = {"BIO_S", "BIO_FP"};
    final static String PRODUCT_EXT = ".XML";

    /**
     * Opt-in switch for the EXPERIMENTAL BioPAL-prototype reader. Off by default so recognition of
     * official products (and non-BIOMASS data) is never affected. Set {@code -Dbiomass.biopal.reader=true}
     * to let this plug-in claim BioPAL output as {@link DecodeQualification#SUITABLE}.
     * See {@link BiomassBioPALProductDirectory}.
     */
    static final String BIOPAL_READER_PROPERTY = "biomass.biopal.reader";
    private static final int BIOPAL_SCAN_MAX_DEPTH = 4;

    private final static Class[] VALID_INPUT_TYPES = new Class[]{Path.class, File.class, String.class};

    private final static String ANNOTATION = "annotation";
    private final static String MEASUREMENT = "measurement";

    /** Returns true when {@code filename} starts with any of the known BIOMASS prefixes. */
    private static boolean hasBiomassPrefix(final String filenameLower) {
        for (final String prefix : PRODUCT_PREFIXES) {
            if (filenameLower.startsWith(prefix.toLowerCase())) return true;
        }
        return false;
    }

    /**
     * Checks whether the given object is an acceptable input for this product reader and if so, the method checks if it
     * is capable of decoding the input's content.
     *
     * @param input any input object
     * @return true if this product reader can decode the given input, otherwise false.
     */
    @Override
    public DecodeQualification getDecodeQualification(final Object input) {
        final Path path = ReaderUtils.getPathFromInput(input);
        if (path == null) return DecodeQualification.UNABLE;

        final DecodeQualification official = getOfficialQualification(path);
        if (official == DecodeQualification.INTENDED) {
            return official;
        }
        // Experimental, opt-in only: BioPAL prototype output. SUITABLE (not INTENDED) so any
        // genuinely-intended reader still wins. Gated by a system property so the default build
        // behaves exactly as before.
        if (Boolean.getBoolean(BIOPAL_READER_PROPERTY) && looksLikeBioPAL(path)) {
            return DecodeQualification.SUITABLE;
        }
        return official;
    }

    private DecodeQualification getOfficialQualification(final Path path) {
        if (Files.isDirectory(path)) {
            final File[] files = path.toFile().listFiles();
            if (files == null) return DecodeQualification.UNABLE;
            for (final File file : files) {
                final String filename = file.getName().toLowerCase();
                if (hasBiomassPrefix(filename) && filename.endsWith(PRODUCT_EXT.toLowerCase())) {
                    return DecodeQualification.INTENDED;
                }
            }
            return DecodeQualification.UNABLE;
        }

        if (path.getFileName() != null) {
            final String filename = path.getFileName().toString().toLowerCase();
            if (hasBiomassPrefix(filename) && filename.endsWith(PRODUCT_EXT.toLowerCase())) {
                return DecodeQualification.INTENDED;
            }
            if (filename.endsWith(".zip") && hasBiomassPrefix(filename)) {
                // Check each candidate prefix in the zip's entries.
                for (final String prefix : PRODUCT_PREFIXES) {
                    if (ZipUtils.findInZip(path.toFile(), prefix.toLowerCase(), PRODUCT_EXT.toLowerCase())) {
                        return DecodeQualification.INTENDED;
                    }
                }
            }
        }

        return DecodeQualification.UNABLE;
    }

    /** True when the input is, or contains, a BioPAL product GeoTIFF (AGB/FH/FD). Bounded scan. */
    private static boolean looksLikeBioPAL(final Path path) {
        final File f = path.toFile();
        if (f.isFile()) {
            return BiomassBioPALProductDirectory.classify(f.getName()) != null;
        }
        return f.isDirectory() && containsBioPALProduct(f, 0);
    }

    private static boolean containsBioPALProduct(final File dir, final int depth) {
        if (depth > BIOPAL_SCAN_MAX_DEPTH) return false;
        final File[] files = dir.listFiles();
        if (files == null) return false;
        for (final File f : files) {
            if (f.isFile() && BiomassBioPALProductDirectory.classify(f.getName()) != null) {
                return true;
            }
        }
        for (final File f : files) {
            if (f.isDirectory() && containsBioPALProduct(f, depth + 1)) {
                return true;
            }
        }
        return false;
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
        return PRODUCT_PREFIXES.clone();
    }
}
