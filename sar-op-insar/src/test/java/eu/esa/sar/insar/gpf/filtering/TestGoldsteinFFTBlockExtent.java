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
package eu.esa.sar.insar.gpf.filtering;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The Goldstein FFT block is square in PIXELS, which makes it strongly anisotropic on the ground
 * wherever the sampling is anisotropic — and Sentinel-1 IW always is.
 * <p>
 * For IW3 (slant range 2.3296 m, incidence ~41.8 deg, azimuth 13.989 m) the ground range spacing is
 * {@code 2.3296 / sin(41.8) = 3.498 m}, so a 64x64 block spans about 224 m x 895 m — a 4:1 footprint,
 * meaning the filter resolves fringe frequencies four times better in azimuth than in range. That is a
 * pre-existing property of the classical chain, not something introduced by geocoding.
 * <p>
 * The {@code fftSizeMeters} parameter derives per-axis sizes so the block is square on the ground
 * instead. These tests pin the snapping rule and the S1 IW worked example.
 */
public class TestGoldsteinFFTBlockExtent {

    // Real IW3 numbers, from the S1A product used to diagnose this.
    private static final double GROUND_RANGE_SPACING = 2.329562 / Math.sin(Math.toRadians(41.764));
    private static final double AZIMUTH_SPACING = 13.98908;

    @Test
    public void exactPowersOfTwoAreUnchanged() {
        assertEquals(32, GoldsteinFilterOp.pow2Clamp(32));
        assertEquals(64, GoldsteinFilterOp.pow2Clamp(64));
        assertEquals(256, GoldsteinFilterOp.pow2Clamp(256));
    }

    @Test
    public void snapsToTheNearestPowerOfTwo() {
        assertEquals("70 is nearer 64 than 128", 64, GoldsteinFilterOp.pow2Clamp(70));
        assertEquals("100 is nearer 128 than 64", 128, GoldsteinFilterOp.pow2Clamp(100));
        assertEquals("95 is nearer 128 (33) than 64 (31)... 64", 64, GoldsteinFilterOp.pow2Clamp(95));
        assertEquals(128, GoldsteinFilterOp.pow2Clamp(97));
    }

    @Test
    public void clampsToTheSupportedRange() {
        assertEquals(8, GoldsteinFilterOp.pow2Clamp(1));
        assertEquals(8, GoldsteinFilterOp.pow2Clamp(0));
        assertEquals(8, GoldsteinFilterOp.pow2Clamp(-5));
        assertEquals(512, GoldsteinFilterOp.pow2Clamp(5000));
    }

    /**
     * The point of the whole change: for a 900 m block on S1 IW3 the derived sizes must differ by ~4x
     * and the resulting GROUND extents must match closely. A square-in-pixels block cannot do both.
     */
    @Test
    public void s1IwDerivationGivesAPhysicallySquareBlock() {
        final double extentMeters = 900.0;

        final int nx = GoldsteinFilterOp.pow2Clamp(extentMeters / GROUND_RANGE_SPACING);
        final int ny = GoldsteinFilterOp.pow2Clamp(extentMeters / AZIMUTH_SPACING);

        assertEquals("range axis needs ~4x more pixels for the same ground distance", 256, nx);
        assertEquals(64, ny);

        final double groundX = nx * GROUND_RANGE_SPACING;
        final double groundY = ny * AZIMUTH_SPACING;
        final double aspect = Math.max(groundX, groundY) / Math.min(groundX, groundY);

        assertTrue(String.format("block should be near-square on the ground, got %.0f m x %.0f m "
                + "(aspect %.3f)", groundX, groundY, aspect), aspect < 1.05);
    }

    /**
     * And the contrast: the existing square-in-pixels default is 4:1 on the ground for the same
     * product. This is what the new option exists to fix.
     */
    @Test
    public void squareInPixelsIsFourToOneOnTheGroundForS1Iw() {
        final int square = 64;
        final double groundX = square * GROUND_RANGE_SPACING;
        final double groundY = square * AZIMUTH_SPACING;
        final double aspect = groundY / groundX;

        assertEquals("a 64x64 block spans ~224 m in ground range", 224.0, groundX, 5.0);
        assertEquals("...and ~895 m in azimuth", 895.0, groundY, 5.0);
        assertTrue("aspect should be about 4:1, got " + aspect, aspect > 3.8 && aspect < 4.2);
    }
}
