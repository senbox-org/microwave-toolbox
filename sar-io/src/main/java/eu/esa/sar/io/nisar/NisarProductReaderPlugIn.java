/*
 * Copyright (C) 2024 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.io.nisar;

import eu.esa.sar.io.netcdf.NetCDFReaderPlugIn;
import eu.esa.sar.io.nisar.util.NisarXConstants;
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
        if(path.getFileName() == null) {
            return DecodeQualification.UNABLE;
        }
        final String fileName = path.getFileName().toString().toUpperCase();
        for(final String prefix : NisarXConstants.NISAR_FILE_PREFIXES) {
            if(fileName.startsWith(prefix)) {
                if(fileName.endsWith(".H5") || fileName.endsWith(".TIF") || fileName.endsWith(".XML")) {
                    return DecodeQualification.INTENDED;
                }
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
