/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.iogdal.cosmo;

import eu.esa.sar.commons.io.SARFileFilter;
import eu.esa.sar.commons.io.SARProductReaderPlugIn;
import org.esa.snap.core.dataio.DecodeQualification;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.util.io.SnapFileFilter;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * GDAL-backed reader plug-in for COSMO-SkyMed GeoTIFF products (CSK and CSG).
 * <p>
 * Routes {@code .tif} and {@code .attribs.xml} inputs to the GDAL GTiff driver, which
 * handles BigTIFF (CSG products commonly exceed 4 GB). The HDF5 ({@code .h5}) path is
 * handled by {@code CosmoSkymedReaderPlugIn} in the {@code sar-io} module.
 */
public class CosmoSkymedGDALReaderPlugIn implements SARProductReaderPlugIn {

    private final static String[] FORMAT_NAMES = {"CosmoSkymedGDAL"};
    private final static String[] FORMAT_FILE_EXTENSIONS = {".tif", ".attribs.xml"};
    private final static String PLUGIN_DESCRIPTION = "Cosmo-Skymed GeoTIFF Products (GDAL)";
    private final static String[] COSMO_FILE_PREFIXES = {"csk", "csg"};

    private final static Class[] VALID_INPUT_TYPES = new Class[]{Path.class, File.class, String.class};

    @Override
    public DecodeQualification getDecodeQualification(final Object input) {
        final Path path = ReaderUtils.getPathFromInput(input);
        if (path == null || Files.isDirectory(path) || !Files.exists(path)) {
            return DecodeQualification.UNABLE;
        }
        final String fileName = path.getFileName().toString().toLowerCase();
        for (String prefix : COSMO_FILE_PREFIXES) {
            if (fileName.startsWith(prefix)) {
                for (String ext : FORMAT_FILE_EXTENSIONS) {
                    if (fileName.endsWith(ext)) {
                        return DecodeQualification.INTENDED;
                    }
                }
            }
        }
        return DecodeQualification.UNABLE;
    }

    @Override
    public ProductReader createReaderInstance() {
        return new CosmoSkymedGDALReader(this);
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
        return new String[]{".attribs.xml"};
    }

    @Override
    public String[] getProductMetadataFilePrefixes() {
        return COSMO_FILE_PREFIXES;
    }
}
