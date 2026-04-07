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
package eu.esa.sar.iogdal.eos4;

import eu.esa.sar.commons.io.SARFileFilter;
import eu.esa.sar.commons.io.SARProductReaderPlugIn;
import org.esa.snap.core.dataio.DecodeQualification;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.util.io.SnapFileFilter;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import org.esa.snap.engine_utilities.util.ZipUtils;

import java.io.File;
import java.nio.file.Path;
import java.util.Locale;

/**
 * ReaderPlugIn for ISRO EOS-04 (RISAT-1A) COG/GeoTIFF products.
 *
 * Detects products by the presence of BAND_META.txt metadata file
 * with GeoTIFF imagery in scene_POL/ subdirectories.
 * Uses GDAL for efficient COG/GeoTIFF reading.
 *
 * The CEOS format reader remains separate in sar-io/ceos/risat.
 */
public class EOS4CogProductReaderPlugIn implements SARProductReaderPlugIn {

    static final String BAND_HEADER_NAME = "BAND_META.txt";
    private static final String[] FORMAT_NAMES = {"EOS-04"};
    private static final String[] FORMAT_FILE_EXTENSIONS = {".txt", ".zip"};
    private static final String PLUGIN_DESCRIPTION = "EOS-04 / RISAT COG Products";
    private static final Class[] VALID_INPUT_TYPES = new Class[]{Path.class, File.class, String.class};

    @Override
    public DecodeQualification getDecodeQualification(final Object input) {
        final Path path = ReaderUtils.getPathFromInput(input);
        if (path == null) return DecodeQualification.UNABLE;

        File file = path.toFile();

        // Check BAND_META.txt directly
        if (file.isFile() && file.getName().equals(BAND_HEADER_NAME)) {
            if (isEOS04Product(file) && hasGeoTiffImagery(file.getParentFile())) {
                return DecodeQualification.INTENDED;
            }
        }

        // Check directory containing BAND_META.txt
        if (file.isDirectory()) {
            File bandMeta = new File(file, BAND_HEADER_NAME);
            if (bandMeta.exists() && isEOS04Product(bandMeta) && hasGeoTiffImagery(file)) {
                return DecodeQualification.INTENDED;
            }
        }

        // Check ZIP files
        if (file.isFile() && file.getName().toLowerCase().endsWith(".zip")) {
            if (ZipUtils.findInZip(file, "", BAND_HEADER_NAME, "") != null) {
                return DecodeQualification.INTENDED;
            }
        }

        file = findMetadataFile(path.getParent());
        if (file != null && file.isFile() && file.getName().equals(BAND_HEADER_NAME)) {
            if (isEOS04Product(file) && hasGeoTiffImagery(file.getParentFile())) {
                return DecodeQualification.INTENDED;
            }
        }

        return DecodeQualification.UNABLE;
    }

    /**
     * Check if the BAND_META.txt identifies this as an EOS-04 product.
     * EOS-04 products have SatID containing "EOS" (not "RISAT").
     */
    private boolean isEOS04Product(File bandMetaFile) {
        final String satId = readSatId(bandMetaFile);
        if (satId == null) {
            // Cannot determine — don't claim (let RISAT-1 reader handle as legacy)
            return false;
        }
        final String upper = satId.toUpperCase();
        return upper.contains("EOS") || (!upper.contains("RISAT"));
    }

    /**
     * Read the SatID field from BAND_META.txt.
     */
    private String readSatId(File bandMetaFile) {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(bandMetaFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("SatID=")) {
                    return line.substring(6).trim();
                }
            }
        } catch (Exception e) {
            // Cannot read file
        }
        return null;
    }

    /**
     * Check if the directory has GeoTIFF imagery files in scene_ subdirectories.
     */
    private boolean hasGeoTiffImagery(File dir) {
        File[] entries = dir.listFiles();
        if (entries == null) return false;
        for (File f : entries) {
            if (f.isDirectory() && f.getName().startsWith("scene_")) {
                File[] images = f.listFiles();
                if (images != null) {
                    for (File img : images) {
                        String name = img.getName().toLowerCase();
                        if (name.contains("imagery") && (name.endsWith(".tif") || name.endsWith(".tiff"))) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    @Override
    public ProductReader createReaderInstance() {
        return new EOS4CogProductReader(this);
    }

    @Override
    public Class[] getInputTypes() {
        return VALID_INPUT_TYPES;
    }

    @Override
    public SnapFileFilter getProductFileFilter() {
        return new SARFileFilter(this);
    }

    @Override
    public String[] getFormatNames() {
        return FORMAT_NAMES;
    }

    @Override
    public String[] getDefaultFileExtensions() {
        return FORMAT_FILE_EXTENSIONS;
    }

    @Override
    public String getDescription(final Locale locale) {
        return PLUGIN_DESCRIPTION;
    }

    @Override
    public String[] getProductMetadataFileExtensions() {
        return FORMAT_FILE_EXTENSIONS;
    }

    @Override
    public String[] getProductMetadataFilePrefixes() {
        return new String[]{BAND_HEADER_NAME};
    }
}
