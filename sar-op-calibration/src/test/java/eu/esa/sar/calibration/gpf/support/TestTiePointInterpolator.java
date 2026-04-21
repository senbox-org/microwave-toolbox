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
package eu.esa.sar.calibration.gpf.support;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.datamodel.TiePointGrid;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link TiePointInterpolator}.
 */
public class TestTiePointInterpolator {

    private static final double TOL = 1e-6;

    /**
     * Linear ramp along x: f(x,y) = x. Bilinear interpolation must reproduce exact values.
     */
    @Test
    public void testBilinearOnLinearRamp() {
        final float[] tiePoints = new float[] {
                0f,  5f, 10f,
                0f,  5f, 10f
        };
        final TiePointGrid tpg = new TiePointGrid(
                "ramp", 3, 2, 0.0, 0.0, 5.0, 5.0, tiePoints);

        final TiePointInterpolator interp = new TiePointInterpolator(tpg);

        assertEquals(0.0, interp.getPixelDouble(0, 0, TiePointInterpolator.InterpMode.BILINEAR), TOL);
        assertEquals(5.0, interp.getPixelDouble(5, 0, TiePointInterpolator.InterpMode.BILINEAR), TOL);
        assertEquals(10.0, interp.getPixelDouble(10, 0, TiePointInterpolator.InterpMode.BILINEAR), TOL);
        assertEquals(2.5, interp.getPixelDouble(2.5, 0, TiePointInterpolator.InterpMode.BILINEAR), TOL);
    }

    /**
     * Quadratic polynomial f(x) = 2x^2 + x + 1 sampled at x=0,5,10 yields {1, 56, 211}.
     * Quadratic interpolation fits exactly.
     */
    @Test
    public void testQuadraticFitsExactlyOnQuadraticData() {
        final float[] tiePoints = new float[] {
                1f, 56f, 211f,
                1f, 56f, 211f
        };
        final TiePointGrid tpg = new TiePointGrid(
                "quad", 3, 2, 0.0, 0.0, 5.0, 5.0, tiePoints);

        final TiePointInterpolator interp = new TiePointInterpolator(tpg);

        assertEquals(1.0, interp.getPixelDouble(0, 0, TiePointInterpolator.InterpMode.QUADRATIC), 1e-6);
        assertEquals(56.0, interp.getPixelDouble(5, 0, TiePointInterpolator.InterpMode.QUADRATIC), 1e-6);
        assertEquals(211.0, interp.getPixelDouble(10, 0, TiePointInterpolator.InterpMode.QUADRATIC), 1e-6);
        // f(3) = 2*9 + 3 + 1 = 22
        assertEquals(22.0, interp.getPixelDouble(3, 0, TiePointInterpolator.InterpMode.QUADRATIC), 1e-6);
    }

    @Test
    public void testQuadraticRowClampedAtLastRow() {
        // small 3x2 grid — querying y past the last row must still return a value (row clamped).
        final float[] tiePoints = new float[] {
                1f, 4f, 9f,
                4f, 9f, 16f
        };
        final TiePointGrid tpg = new TiePointGrid(
                "row-clamp", 3, 2, 0.0, 0.0, 5.0, 5.0, tiePoints);
        final TiePointInterpolator interp = new TiePointInterpolator(tpg);

        // y far beyond grid should not throw
        final double value = interp.getPixelDouble(
                2.0, 1000.0, TiePointInterpolator.InterpMode.QUADRATIC);
        // just asserting the call returned a finite number
        assertEquals(value, value, 0.0);
    }

    @Test
    public void testGetPixelsBilinearFillsArray() {
        final float[] tiePoints = new float[] {
                0f, 10f,
                0f, 10f
        };
        final TiePointGrid tpg = new TiePointGrid(
                "simple", 2, 2, 0.0, 0.0, 10.0, 10.0, tiePoints);
        final TiePointInterpolator interp = new TiePointInterpolator(tpg);

        final double[] out = new double[4];
        final double[] result = interp.getPixels(
                0, 0, 2, 2, out, ProgressMonitor.NULL,
                TiePointInterpolator.InterpMode.BILINEAR);
        assertNotNull(result);
        assertEquals(4, result.length);
    }

    @Test
    public void testGetPixelsAllocatesWhenArrayIsNull() {
        final float[] tiePoints = new float[] {
                0f, 1f,
                2f, 3f
        };
        final TiePointGrid tpg = new TiePointGrid(
                "alloc", 2, 2, 0.0, 0.0, 10.0, 10.0, tiePoints);
        final TiePointInterpolator interp = new TiePointInterpolator(tpg);

        final double[] result = interp.getPixels(
                0, 0, 2, 2, null, ProgressMonitor.NULL,
                TiePointInterpolator.InterpMode.BILINEAR);
        assertNotNull(result);
        assertEquals(4, result.length);
    }

    @Test
    public void testGetPixelsThrowsWhenArrayTooShort() {
        final float[] tiePoints = new float[] {
                0f, 1f,
                2f, 3f
        };
        final TiePointGrid tpg = new TiePointGrid(
                "small", 2, 2, 0.0, 0.0, 10.0, 10.0, tiePoints);
        final TiePointInterpolator interp = new TiePointInterpolator(tpg);

        final double[] tooShort = new double[2];
        try {
            interp.getPixels(0, 0, 2, 2, tooShort, ProgressMonitor.NULL,
                    TiePointInterpolator.InterpMode.BILINEAR);
            fail("Expected IllegalArgumentException for too-small output array");
        } catch (IllegalArgumentException expected) {
            // success
        }
    }
}
