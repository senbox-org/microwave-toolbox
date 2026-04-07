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
package eu.esa.sar.iogdal.alos4;

import org.esa.snap.core.dataio.DecodeQualification;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.dataio.ProductReaderPlugIn;
import org.esa.snap.core.util.io.FileUtils;
import org.esa.snap.core.util.io.SnapFileFilter;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import org.esa.snap.engine_utilities.util.ZipUtils;

import java.io.File;
import java.nio.file.Path;
import java.util.Locale;

public class Alos4GeoTiffProductReaderPlugIn implements ProductReaderPlugIn {
    private static final String[] FORMAT_NAMES = new String[]{"ALOS-4 GeoTIFF"};

    @Override
    public DecodeQualification getDecodeQualification(Object input) {
        try {
            final Path inputPath = ReaderUtils.getPathFromInput(input);
            if (inputPath == null) {
                return DecodeQualification.UNABLE;
            }
            final String extension = FileUtils.getExtension(inputPath.toFile());

            if (extension != null && extension.toUpperCase().equals(".ZIP")) {
                return checkZIPFile(inputPath);
            }
            return checkFileName(inputPath.toFile());

        } catch (Exception e) {
            return DecodeQualification.UNABLE;
        }
    }

    private DecodeQualification checkFileName(File inputFile) {
        boolean hasValidImage = false;
        boolean hasMetadata = false;

        final File[] files = inputFile.getParentFile() != null ? inputFile.getParentFile().listFiles() : null;
        if (files != null) {
            for (File f : files) {
                String name = f.getName().toUpperCase();
                if (name.startsWith("SUMMARY") && name.endsWith(".TXT") && name.contains("ALOS4")) {
                    hasMetadata = true;
                }
                if (name.contains("ALOS4") && (name.endsWith("TIF") || name.endsWith("TIFF")) &&
                        (name.contains("IMG-") &&
                                (name.contains("-HH-") || name.contains("-HV-") || name.contains("-VH-") || name.contains("-VV-")))) {
                    hasValidImage = true;
                }
            }
        }
        if (hasMetadata && hasValidImage)
            return DecodeQualification.INTENDED;

        return DecodeQualification.UNABLE;
    }

    private DecodeQualification checkZIPFile(Path imageIOInputPath) {
        boolean hasValidImage =
                ZipUtils.findInZip(imageIOInputPath.toFile(), "", ".TIF", "IMG-HH") != null ||
                ZipUtils.findInZip(imageIOInputPath.toFile(), "", ".TIF", "IMG-HV") != null ||
                ZipUtils.findInZip(imageIOInputPath.toFile(), "", ".TIF", "IMG-VV") != null ||
                ZipUtils.findInZip(imageIOInputPath.toFile(), "", ".TIF", "IMG-VH") != null;
        boolean hasMetadata = ZipUtils.findInZip(imageIOInputPath.toFile(), "", ".TXT", "SUMMARY") != null;
        boolean isAlos4 = ZipUtils.findInZip(imageIOInputPath.toFile(), "", "", "ALOS4") != null;

        if (hasMetadata && hasValidImage && isAlos4) {
            return DecodeQualification.INTENDED;
        }
        return DecodeQualification.UNABLE;
    }

    @Override
    public Class[] getInputTypes() {
        return new Class[]{String.class, File.class, Path.class};
    }

    @Override
    public ProductReader createReaderInstance() {
        return new Alos4GeoTiffProductReader(this);
    }

    @Override
    public String[] getFormatNames() {
        return FORMAT_NAMES;
    }

    @Override
    public String[] getDefaultFileExtensions() {
        return new String[]{"txt", "zip"};
    }

    @Override
    public String getDescription(Locale locale) {
        return "ALOS-4 GeoTIFF data product.";
    }

    @Override
    public SnapFileFilter getProductFileFilter() {
        return new SnapFileFilter(FORMAT_NAMES[0], getDefaultFileExtensions(), getDescription(null));
    }
}
