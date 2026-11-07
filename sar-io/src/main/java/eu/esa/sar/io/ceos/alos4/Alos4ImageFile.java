/*
 * Copyright (C) 2026 by Mahmut Arikan
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

import eu.esa.sar.io.ceos.alos.AlosPalsarImageFile;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;

/**
 * Image file of an ALOS-4 PALSAR-3 product.
 * <p>
 * Same record structure as ALOS-2, with one difference that matters: PALSAR-3 records the slant
 * range to the first, middle and last pixel in CENTIMETRES (JAXA FTR-240031A Rev. A, Table 4.6-15
 * field 35, and the processed data record likewise), where PALSAR-2 records metres.
 * {@link eu.esa.sar.io.ceos.CEOSImageFile} returns the stored integer unscaled, so without the
 * conversion here every ALOS-4 scene is read 100 times too far away: a 820.7 km slant range comes
 * out as 82 074 km, and nothing downstream can be geocoded. The definition files have no scale
 * factor, so this cannot be done there.
 */
public class Alos4ImageFile extends AlosPalsarImageFile {

    /** Centimetres per metre: the unit PALSAR-3 stores slant ranges in, relative to the metres SNAP uses. */
    static final double CENTIMETRES_PER_METRE = 100.0;

    public Alos4ImageFile(final ImageInputStream imageStream, final int prodLevel, final String fileName)
            throws IOException {
        super(imageStream, prodLevel, fileName);
    }

    @Override
    public double getSlantRangeToFirstPixel(final int line) {
        return super.getSlantRangeToFirstPixel(line) / CENTIMETRES_PER_METRE;
    }

    @Override
    public double getSlantRangeToMidPixel(final int line) {
        return super.getSlantRangeToMidPixel(line) / CENTIMETRES_PER_METRE;
    }

    @Override
    public double getSlantRangeToLastPixel(final int line) {
        return super.getSlantRangeToLastPixel(line) / CENTIMETRES_PER_METRE;
    }
}
