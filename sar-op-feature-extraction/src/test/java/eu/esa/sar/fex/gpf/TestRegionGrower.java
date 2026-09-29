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
package eu.esa.sar.fex.gpf;

import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.Tile;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RegionGrower} that exercise the clustering logic over
 * small in-memory tiles backed by Mockito.
 */
public class TestRegionGrower {

    private static Tile mockTile(final int w, final int h, final double[] pixels) {
        final Tile tile = mock(Tile.class);
        final ProductData data = ProductData.createInstance(pixels);

        when(tile.getMinX()).thenReturn(0);
        when(tile.getMinY()).thenReturn(0);
        when(tile.getWidth()).thenReturn(w);
        when(tile.getHeight()).thenReturn(h);
        when(tile.getScanlineStride()).thenReturn(w);
        when(tile.getScanlineOffset()).thenReturn(0);
        when(tile.getDataBuffer()).thenReturn(data);
        when(tile.getDataBufferIndex(anyInt(), anyInt())).thenAnswer(new Answer<Integer>() {
            @Override
            public Integer answer(InvocationOnMock inv) {
                final int x = inv.getArgument(0);
                final int y = inv.getArgument(1);
                return y * w + x;
            }
        });
        return tile;
    }

    @Test
    public void testAllPixelsBelowThresholdProducesNoClusters() {
        final double[] pixels = new double[16]; // 4x4 zeros
        final Tile tile = mockTile(4, 4, pixels);

        final RegionGrower grower = new RegionGrower(tile);
        final double[] out = new double[16];
        grower.run(0.5, out);

        assertEquals("no samples above threshold", 0, grower.getNumSamples());
        assertEquals("no clusters detected", 0, grower.getMaxClusterSize());
    }

    @Test
    public void testCopiesDataIntoProvidedArray() {
        final double[] pixels = {
                0.0, 0.1, 0.0,
                0.2, 0.3, 0.4,
                0.0, 0.5, 0.6
        };
        final Tile tile = mockTile(3, 3, pixels);
        final RegionGrower grower = new RegionGrower(tile);

        final double[] copy = new double[9];
        grower.run(10.0 /* nothing above */, copy);

        for (int i = 0; i < pixels.length; i++) {
            assertEquals(pixels[i], copy[i], 1e-9);
        }
    }

    @Test
    public void testSinglePixelAboveThresholdProducesClusterOfOne() {
        final double[] pixels = new double[25]; // 5x5
        pixels[12] = 1.0; // center pixel
        final Tile tile = mockTile(5, 5, pixels);

        final RegionGrower grower = new RegionGrower(tile);
        grower.run(0.5, new double[25]);

        assertEquals(1, grower.getNumSamples());
        assertEquals(1, grower.getMaxClusterSize());
    }

    @Test
    public void testContiguousBlockMergesIntoSingleCluster() {
        // 5x5 with a 2x2 block of ones in the interior.
        final double[] pixels = new double[25];
        pixels[6] = 1.0;  // (1,1)
        pixels[7] = 1.0;  // (2,1)
        pixels[11] = 1.0; // (1,2)
        pixels[12] = 1.0; // (2,2)
        final Tile tile = mockTile(5, 5, pixels);

        final RegionGrower grower = new RegionGrower(tile);
        grower.run(0.5, new double[25]);

        assertEquals("all four above-threshold pixels should be counted", 4, grower.getNumSamples());
        assertEquals("four contiguous pixels form one cluster of size 4", 4, grower.getMaxClusterSize());
    }

    @Test
    public void testTwoDisjointBlocksYieldIndependentClusters() {
        // 6x3 with two disjoint 1x2 patches (separated by a zero row).
        final int w = 6;
        final int h = 3;
        final double[] pixels = new double[w * h];
        // Block A: (0,0) and (1,0).
        pixels[0] = 1.0;
        pixels[1] = 1.0;
        // Block B: (4,2) and (5,2).
        pixels[w * 2 + 4] = 1.0;
        pixels[w * 2 + 5] = 1.0;

        final Tile tile = mockTile(w, h, pixels);
        final RegionGrower grower = new RegionGrower(tile);
        grower.run(0.5, new double[w * h]);

        assertEquals(4, grower.getNumSamples());
        // maxClusterSize is the largest of the two equal clusters = 2.
        assertEquals(2, grower.getMaxClusterSize());
    }

    @Test
    public void testDiagonalPixelsJoinViaEightConnectivity() {
        // clustering uses 8-connected neighbourhood, so these diagonal pixels merge.
        final int w = 4;
        final int h = 4;
        final double[] pixels = new double[w * h];
        pixels[0] = 1.0;          // (0,0)
        pixels[w + 1] = 1.0;      // (1,1)
        pixels[2 * w + 2] = 1.0;  // (2,2)
        final Tile tile = mockTile(w, h, pixels);

        final RegionGrower grower = new RegionGrower(tile);
        grower.run(0.5, new double[w * h]);

        assertEquals(3, grower.getNumSamples());
        assertEquals("diagonal pixels should chain into one cluster", 3, grower.getMaxClusterSize());
    }

    @Test
    public void testMaxClusterSizeIsZeroForEmptyTileWithSinglePixel() {
        final double[] pixels = { 1.0 };
        final Tile tile = mockTile(1, 1, pixels);
        final RegionGrower grower = new RegionGrower(tile);
        grower.run(0.5, new double[1]);

        assertTrue(grower.getNumSamples() == 1);
        assertTrue(grower.getMaxClusterSize() == 1);
    }
}
