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
package eu.esa.sar.io.ceos.alos;

import eu.esa.sar.io.ceos.CEOSProductReaderPlugIn;
import org.esa.snap.core.dataio.DecodeQualification;
import org.esa.snap.core.dataio.ProductReader;

import java.nio.file.Path;

/**
 * The ReaderPlugIn for ALOS PALSAR CEOS products.
 */
public class AlosPalsarProductReaderPlugIn extends CEOSProductReaderPlugIn {

    public AlosPalsarProductReaderPlugIn() {
        constants = new AlosPalsarConstants();
    }

    /**
     * Creates an instance of the actual product reader class. This method should never return <code>null</code>.
     *
     * @return a new reader instance, never <code>null</code>
     */
    @Override
    public ProductReader createReaderInstance() {
        return new AlosPalsarProductReader(this);
    }

    @Override
    protected DecodeQualification checkProductQualification(final Path path) {
        final String name = path.getFileName().toString().toUpperCase();
        if(name.endsWith(".ZIP") && (name.startsWith("ALPSR") ||
                (name.startsWith("ALOS") && !name.startsWith("ALOS2")) ||
                (name.startsWith("AL1") && !name.startsWith("AL1_NESR_PSM")) )) {
            return DecodeQualification.INTENDED;
        }
        for (String prefix : constants.getVolumeFilePrefix()) {
            if (name.startsWith(prefix) && !name.contains("ALOS2")) {
                final AlosPalsarProductReader reader = new AlosPalsarProductReader(this);
                return reader.checkProductQualification(path);
            }
        }
        return DecodeQualification.UNABLE;
    }
}
