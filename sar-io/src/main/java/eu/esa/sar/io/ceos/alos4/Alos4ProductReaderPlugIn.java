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

import eu.esa.sar.io.ceos.CEOSProductReaderPlugIn;
import org.esa.snap.core.dataio.DecodeQualification;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.engine_utilities.util.ZipUtils;

import java.nio.file.Path;

/**
 * ReaderPlugIn for ALOS-4 PALSAR-3 CEOS products.
 */
public class Alos4ProductReaderPlugIn extends CEOSProductReaderPlugIn {

    public Alos4ProductReaderPlugIn() {
        constants = new Alos4Constants();
    }

    @Override
    public ProductReader createReaderInstance() {
        return new Alos4ProductReader(this);
    }

    @Override
    protected DecodeQualification checkProductQualification(final Path path) {
        final String name = path.getFileName().toString().toUpperCase();
        if (name.contains("ALOS4")) {
            for (String prefix : constants.getVolumeFilePrefix()) {
                if (name.startsWith(prefix)) {
                    final Alos4ProductReader reader = new Alos4ProductReader(this);
                    return reader.checkProductQualification(path);
                }
            }
        }
        if (name.endsWith(".ZIP") && (ZipUtils.findInZip(path.toFile(), "vol-alos4", ""))) {
            return DecodeQualification.INTENDED;
        }
        return DecodeQualification.UNABLE;
    }
}
