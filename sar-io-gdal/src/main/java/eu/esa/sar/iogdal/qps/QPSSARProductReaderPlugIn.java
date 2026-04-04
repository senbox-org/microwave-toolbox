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
package eu.esa.sar.iogdal.qps;

import eu.esa.sar.commons.io.SARFileFilter;
import eu.esa.sar.commons.io.SARProductReaderPlugIn;
import org.esa.snap.core.dataio.DecodeQualification;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.util.io.SnapFileFilter;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;

import java.io.File;
import java.nio.file.Path;
import java.util.Locale;

/**
 * ReaderPlugIn for iQPS QPS-SAR products.
 *
 * Detects GeoTIFF products from iQPS satellites (QPS-SAR-1 through QPS-SAR-N).
 * Products are identified by filename containing "QPS" or "QPSSAR" prefix
 * with .tif extension.
 */
public class QPSSARProductReaderPlugIn implements SARProductReaderPlugIn {

    private static final String[] FORMAT_NAMES = {"QPS-SAR"};
    private static final String[] FORMAT_FILE_EXTENSIONS = {".tif", ".xml", ".json"};
    private static final String PLUGIN_DESCRIPTION = "iQPS QPS-SAR Products";
    private static final Class[] VALID_INPUT_TYPES = new Class[]{Path.class, File.class, String.class};

    @Override
    public DecodeQualification getDecodeQualification(final Object input) {
        final Path path = ReaderUtils.getPathFromInput(input);
        if (path == null || path.getFileName() == null) {
            return DecodeQualification.UNABLE;
        }
        final String fileName = path.getFileName().toString().toUpperCase();

        // Detect by filename pattern: QPS-SAR*, QPSSAR*, QPS_SAR*
        if (isQPSSAR(fileName)) {
            final String ext = fileName.substring(fileName.lastIndexOf('.'));
            if (ext.equalsIgnoreCase(".TIF") || ext.equalsIgnoreCase(".TIFF") ||
                    ext.equalsIgnoreCase(".XML") || ext.equalsIgnoreCase(".JSON")) {
                return DecodeQualification.INTENDED;
            }
        }
        return DecodeQualification.UNABLE;
    }

    static boolean isQPSSAR(String fileNameUpper) {
        return fileNameUpper.startsWith("QPS-SAR") ||
                fileNameUpper.startsWith("QPSSAR") ||
                fileNameUpper.startsWith("QPS_SAR");
    }

    @Override
    public ProductReader createReaderInstance() {
        return new QPSSARProductReader(this);
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
        return new String[]{"QPS-SAR", "QPSSAR", "QPS_SAR"};
    }
}
