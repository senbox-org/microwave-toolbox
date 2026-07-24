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
package eu.esa.sar.io.sentinel1;

import eu.esa.sar.commons.io.GeoTiffCacheSupport;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

/**
 * Verifies that reading a striped Sentinel-1 IW SLC through the GeoTIFF ProductCache
 * (full-width tile geometry) yields exactly the same samples as reading it directly
 * without the cache. Guards the striped-TIFF caching fix.
 */
public class TestSentinel1SLCCacheRead {

    private final static File slcFile = new File(TestData.inputSAR +
            "S1/SLC/S1A_IW_SLC__1SDV_20240504T180410_20240504T180437_053725_0686E4_637E.SAFE.zip");

    @Test
    public void testCachedRead_matchesUncached_forStripedSLC() throws Exception {
        assumeTrue(slcFile + " not found", slcFile.exists());

        // region kept within a single full-width cache tile row-band (tileHeight=512)
        final int x = 1000, y = 600, w = 64, h = 64;

        final int[] uncached = readRegion(false, x, y, w, h);
        final int[] cached = readRegion(true, x, y, w, h);

        assertArrayEquals("cached read must equal uncached read", uncached, cached);
    }

    private int[] readRegion(final boolean useCache, final int x, final int y, final int w, final int h) throws Exception {
        final boolean prev = GeoTiffCacheSupport.USE_PRODUCT_CACHE;
        GeoTiffCacheSupport.USE_PRODUCT_CACHE = useCache;
        try {
            final ProductReader reader = new Sentinel1ProductReaderPlugIn().createReaderInstance();
            try (Product product = reader.readProductNodes(slcFile, null)) {
                final Band band = product.getBand("i_IW1_VH");
                assertNotNull("i_IW1_VH band", band);

                final ProductData data = band.createCompatibleRasterData(w, h);
                band.readRasterData(x, y, w, h, data);

                final int[] out = new int[w * h];
                for (int i = 0; i < out.length; i++) {
                    out[i] = data.getElemIntAt(i);
                }

                // read the same region again to exercise the cache-hit path
                final ProductData data2 = band.createCompatibleRasterData(w, h);
                band.readRasterData(x, y, w, h, data2);
                for (int i = 0; i < out.length; i++) {
                    if (out[i] != data2.getElemIntAt(i)) {
                        throw new AssertionError("repeat read differs at " + i);
                    }
                }
                return out;
            }
        } finally {
            GeoTiffCacheSupport.USE_PRODUCT_CACHE = prev;
        }
    }
}
