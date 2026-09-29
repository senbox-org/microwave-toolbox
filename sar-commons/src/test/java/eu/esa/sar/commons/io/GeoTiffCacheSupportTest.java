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
package eu.esa.sar.commons.io;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

public class GeoTiffCacheSupportTest {

    @Test
    public void testComputeCacheTileDims_stripedImage_usesFullWidthTiles() {
        // Sentinel-1 IW SLC measurement TIFFs are striped (RowsPerStrip=1, no TileWidth),
        // so the reader reports a "tile" that spans the full image width. Reading a
        // 512-wide cache tile would force decoding full-width strips and throw away all
        // but 512 columns - and every horizontal neighbour re-decodes the same strips.
        // Caching full-width tiles retains the decoded strip band instead.
        final int[] dims = GeoTiffCacheSupport.computeCacheTileDims(22238, 1, 22238, 13500);
        assertArrayEquals(new int[]{22238, 512}, dims);
    }

    @Test
    public void testComputeCacheTileDims_nativelyTiled_honorsNativeTiles() {
        // Internally tiled image with reasonable native tiles - keep them.
        final int[] dims = GeoTiffCacheSupport.computeCacheTileDims(256, 256, 10000, 8000);
        assertArrayEquals(new int[]{256, 256}, dims);
    }

    @Test
    public void testComputeCacheTileDims_oversizedNativeTiles_cappedAtDefault() {
        // Native tiles larger than the default and not full-width -> cap at 512.
        final int[] dims = GeoTiffCacheSupport.computeCacheTileDims(1024, 1024, 5000, 5000);
        assertArrayEquals(new int[]{512, 512}, dims);
    }

    @Test
    public void testComputeCacheTileDims_stripedNarrowScene_clampsToScene() {
        // Striped image narrower/shorter than the default tile size.
        final int[] dims = GeoTiffCacheSupport.computeCacheTileDims(300, 1, 300, 200);
        assertArrayEquals(new int[]{300, 200}, dims);
    }
}
