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
package eu.esa.sar.fex.gpf.texture;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link GaborFilter}.
 */
public class TestGaborFilter {

    @Test
    public void testCreateGaborFilterNonNullResult() {
        final double[][] filter = GaborFilter.createGarborFilter(4.0, 0.6, 1.0, 2.0, 0.3);
        assertNotNull(filter);
        assertTrue("filter width should be positive", filter.length > 0);
        assertNotNull(filter[0]);
        assertTrue("filter height should be positive", filter[0].length > 0);
    }

    @Test
    public void testCreateGaborFilterDimensionsAreOdd() {
        // The Gabor kernel is centered: width = 2*xmax + 1, height = 2*ymax + 1.
        final double[][] filter = GaborFilter.createGarborFilter(4.0, 0.0, 0.0, 2.0, 0.5);
        assertEquals("filter width must be odd (centered kernel)", 1, filter.length % 2);
        assertEquals("filter height must be odd (centered kernel)", 1, filter[0].length % 2);
    }

    @Test
    public void testCreateGaborFilterIsNormalizedToUnitSum() {
        final double[][] filter = GaborFilter.createGarborFilter(4.0, 0.6, 1.0, 2.0, 0.3);
        double sum = 0.0;
        for (double[] row : filter) {
            for (double v : row) {
                sum += v;
            }
        }
        // The filter divides every element by the raw sum, so normalised sum is 1.
        assertEquals("Gabor filter is normalised so its sum should be 1.0",
                1.0, sum, 1e-9);
    }

    @Test
    public void testCreateGaborFilterShapeGrowsWithSigma() {
        final double[][] small = GaborFilter.createGarborFilter(4.0, 0.0, 0.0, 1.0, 1.0);
        final double[][] large = GaborFilter.createGarborFilter(4.0, 0.0, 0.0, 5.0, 1.0);
        assertTrue("filter extent should grow with sigma",
                large.length > small.length);
    }

    @Test
    public void testApplyGaborFilterIdentityKernel() {
        final int[][] in = {
                { 10, 20, 30 },
                { 40, 50, 60 },
                { 70, 80, 90 }
        };
        // 3x3 identity kernel (center=1) — output should match input exactly.
        final double[][] identity = new double[3][3];
        identity[1][1] = 1.0;

        final int[][] out = GaborFilter.applyGarborFilter(in, identity);

        assertNotNull(out);
        assertEquals(in.length, out.length);
        assertEquals(in[0].length, out[0].length);
        for (int x = 0; x < in.length; x++) {
            for (int y = 0; y < in[0].length; y++) {
                assertEquals("identity kernel should return the input pixel",
                        in[x][y], out[x][y]);
            }
        }
    }

    @Test
    public void testApplyGaborFilterWithConstantKernelProducesAveraged() {
        final int[][] in = new int[5][5];
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                in[x][y] = 100;
            }
        }
        // 3x3 kernel with uniform weight 1/9.
        final double[][] avg = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                avg[i][j] = 1.0 / 9.0;
            }
        }

        final int[][] out = GaborFilter.applyGarborFilter(in, avg);

        // Interior pixel should match the constant input within rounding.
        assertEquals(100, out[2][2]);
    }
}
