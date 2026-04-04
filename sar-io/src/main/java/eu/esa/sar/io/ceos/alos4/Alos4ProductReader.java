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
package eu.esa.sar.io.ceos.alos4;

import com.bc.ceres.core.VirtualDir;
import eu.esa.sar.io.ceos.CEOSProductDirectory;
import eu.esa.sar.io.ceos.alos2.Alos2ProductReader;
import org.esa.snap.core.dataio.DecodeQualification;
import org.esa.snap.core.dataio.ProductReaderPlugIn;

import java.nio.file.Path;

/**
 * Product reader for ALOS-4 PALSAR-3 CEOS products.
 */
public class Alos4ProductReader extends Alos2ProductReader {

    public Alos4ProductReader(final ProductReaderPlugIn readerPlugIn) {
        super(readerPlugIn);
    }

    @Override
    protected CEOSProductDirectory createProductDirectory(final VirtualDir productDir) {
        return new Alos4ProductDirectory(productDir);
    }

    @Override
    DecodeQualification checkProductQualification(final Path path) {
        try {
            dataDir = createProductDirectory(createProductDir(path));
            final Alos4ProductDirectory dataDir = (Alos4ProductDirectory) this.dataDir;
            if (dataDir.isALOS4()) {
                return DecodeQualification.INTENDED;
            }
            return DecodeQualification.UNABLE;
        } catch (Exception e) {
            return DecodeQualification.UNABLE;
        }
    }
}
