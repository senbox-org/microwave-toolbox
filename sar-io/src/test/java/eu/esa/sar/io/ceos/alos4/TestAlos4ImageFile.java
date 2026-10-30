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

import eu.esa.sar.io.ceos.alos.AlosPalsarConstants;
import eu.esa.sar.io.ceos.alos.AlosPalsarImageFile;
import org.junit.Test;

import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The slant range of an ALOS-4 image file, without any data file.
 * <p>
 * PALSAR-3 stores "slant range to first data" in centimetres (JAXA FTR-240031A Rev. A, Table 4.6-15
 * field 35) where PALSAR-2 stores metres, and {@link eu.esa.sar.io.ceos.CEOSImageFile} returns the
 * stored integer unscaled. A real scene recorded 82074395 for a slant range of 820.7 km, which read
 * as metres puts the scene 82 074 km away: Terrain-Flattening computes
 * rangeIndex = (slantRange - nearEdgeSlantRange) / rangeSpacing, so no DEM cell falls inside the
 * image.
 * <p>
 * The layout below is a file descriptor record (720 bytes) and one signal data record (800 prefix
 * bytes and 83072 data bytes), with the values and 0-based offsets observed on a real PALSAR-3 L1.1
 * image file typed out here.
 */
public class TestAlos4ImageFile {

    private static final int FDR_LENGTH = 720;
    private static final int RECORD_LENGTH = 83872;           // 800 prefix + 83072 data bytes
    private static final long OBSERVED_SLANT_RANGE_CM = 82074395L;
    private static final String FILE_NAME = "IMG-HH-ALOS40862510250923HBQPRD0809-1.1__-";

    private static byte[] imageFile(final long slantRangeToFirstPixel) {
        final byte[] file = new byte[FDR_LENGTH + RECORD_LENGTH];
        Arrays.fill(file, (byte) ' ');
        // file descriptor record: number 1, subtypes 50 / type 192 / 18 / 18, length 720
        System.arraycopy(new byte[]{0, 0, 0, 1, 50, (byte) 192, 18, 18, 0, 0, 2, (byte) 208}, 0, file, 0, 12);
        put(file, 236, "       1");                // number of lines per data set (observed: 23914)
        put(file, 276, " 800");                    // bytes of prefix data per record
        put(file, 280, "   83072");                // bytes of SAR data per record
        // signal data record: number 2, subtypes 50 / type 10 / 18 / 20, length 83872
        final int rec = FDR_LENGTH;
        System.arraycopy(new byte[]{0, 0, 0, 2, 50, 10, 18, 20, 0, 1, 71, (byte) 160}, 0, file, rec, 12);
        zero(file, rec + 12, 800 - 12);            // prefix: binary fields default to 0
        putInt(file, rec + 36, 2025);              // sensor acquisition year
        putInt(file, rec + 116, slantRangeToFirstPixel);
        return file;
    }

    private static void put(final byte[] file, final int offset, final String text) {
        final byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, file, offset, bytes.length);
    }

    private static void zero(final byte[] file, final int offset, final int length) {
        Arrays.fill(file, offset, offset + length, (byte) 0);
    }

    private static void putInt(final byte[] file, final int offset, final long value) {
        for (int i = 0; i < 4; i++) {
            file[offset + i] = (byte) (value >>> (8 * (3 - i)));
        }
    }

    private static MemoryCacheImageInputStream stream(final long slantRangeToFirstPixel) {
        return new MemoryCacheImageInputStream(new ByteArrayInputStream(imageFile(slantRangeToFirstPixel)));
    }

    @Test
    public void testSlantRangeToFirstPixelIsInMetres() throws IOException {
        final Alos4ImageFile img = new Alos4ImageFile(stream(OBSERVED_SLANT_RANGE_CM),
                AlosPalsarConstants.LEVEL1_1, FILE_NAME);
        assertEquals(820743.95, img.getSlantRangeToFirstPixel(0), 1e-6);
    }

    @Test
    public void testResultIsAPlausibleSpaceborneSlantRange() throws IOException {
        // An L-band SAR in a ~628 km orbit looks between a few hundred and ~2000 km.
        final Alos4ImageFile img = new Alos4ImageFile(stream(OBSERVED_SLANT_RANGE_CM),
                AlosPalsarConstants.LEVEL1_1, FILE_NAME);
        final double metres = img.getSlantRangeToFirstPixel(0);
        assertTrue("was " + metres, metres > 100_000 && metres < 5_000_000);
    }

    @Test
    public void testAlos2ImageFileStillReturnsTheStoredValueUnscaled() throws IOException {
        // ALOS-2 stores metres: the base class must keep returning the integer as it is.
        final AlosPalsarImageFile img = new AlosPalsarImageFile(stream(OBSERVED_SLANT_RANGE_CM),
                AlosPalsarConstants.LEVEL1_1, FILE_NAME);
        assertEquals(82074395.0, img.getSlantRangeToFirstPixel(0), 1e-6);
    }
}
