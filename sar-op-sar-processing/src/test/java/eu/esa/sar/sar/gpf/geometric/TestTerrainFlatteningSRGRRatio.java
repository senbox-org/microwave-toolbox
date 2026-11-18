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
package eu.esa.sar.sar.gpf.geometric;

import com.bc.ceres.annotation.STTM;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The slant/ground range ratio Terrain-Flattening divides the simulated area by on GRD products.
 * <p>
 * The polynomial is a real Sentinel-1 IW GRD ground-to-slant-range polynomial (S1A annotation,
 * mid-scene coordinateConversion, gr0 = 0, 10 m spacing, 26498 samples). The ratio used to be
 * evaluated at {@code x * rangeSpacing}, ignoring the ground range origin that ProductSubsetBuilder
 * shifts for subsets: a subset starting at column 10500 was normalised with the ratio of the swath's
 * first column, 0.78 dB too low.
 */
public class TestTerrainFlatteningSRGRRatio {

    private static final double[] S1_GRSR = {
            799780.840707286, 0.5082772590104973, 5.310670592328546e-07, -3.395734639457396e-13,
            3.780989858228026e-20, 2.044351648585588e-25, -2.470813902515626e-31, 1.170099522427398e-37,
            -3.414436373046815e-45};
    private static final double SPACING = 10.0;
    private static final int FULL_WIDTH = 26498;

    private static double polynomialDerivative(final double[] c, final double x) {
        double d = 0.0;
        for (int i = c.length - 1; i >= 1; i--) {
            d = d * x + i * c[i];
        }
        return d;
    }

    private static double ratio(final int x, final double gr0, final int width, final boolean nearRangeOnLeft) {
        return TerrainFlatteningOp.computeSlantToGroundRangeRatio(x, S1_GRSR, gr0, width, nearRangeOnLeft, SPACING);
    }

    @Test

    @STTM("SNAP-4377")
    public void testRatioIsPolynomialDerivativeAtPixelGroundRange() {
        for (int x : new int[]{0, 1, 5000, 13249, 26497}) {
            final double expected = polynomialDerivative(S1_GRSR, x * SPACING);
            assertEquals("x=" + x, expected, ratio(x, 0.0, FULL_WIDTH, true), expected * 1e-7);
        }
    }

    /** The ratio at a ground position must not depend on whether it is read from the full product or a subset. */
    @Test
    @STTM("SNAP-4377")
    public void testSubsetUsesGroundRangeOrigin() {
        final int subsetX0 = 10500;
        final int subsetWidth = 5000;
        final double subsetGr0 = subsetX0 * SPACING; // ProductSubsetBuilder: gr0 + colIndex * rangeSpacing
        for (int xs : new int[]{0, 1, 2500, 4999}) {
            final double full = ratio(subsetX0 + xs, 0.0, FULL_WIDTH, true);
            assertEquals("subset x=" + xs, full, ratio(xs, subsetGr0, subsetWidth, true), full * 1e-12);
        }
    }

    /** Pins the size of the original error, so a regression to x * rangeSpacing is unmistakable. */
    @Test
    @STTM("SNAP-4377")
    public void testIgnoringTheOriginWouldBe078dBLow() {
        final double withOrigin = ratio(0, 105000.0, 5000, true);
        final double withoutOrigin = ratio(0, 0.0, 5000, true);
        assertEquals(0.784, 10.0 * Math.log10(withOrigin / withoutOrigin), 0.005);
    }

    /** RS2/RCM descending: column 0 is far range, the near-range column is width - 1. */
    @Test
    @STTM("SNAP-4377")
    public void testFlippedImageReadsFromTheFarEnd() {
        final int width = 5000;
        final double gr0 = 30000.0;
        for (int x : new int[]{0, 1, 2500, 4999}) {
            assertEquals("x=" + x, ratio(x, gr0, width, true), ratio(width - 1 - x, gr0, width, false), 1e-15);
        }
        // and it is not symmetric, so the flip matters: far range has the larger dSR/dGR
        final double nearColumnWhenFlipped = ratio(width - 1, gr0, width, false);
        final double farColumnWhenFlipped = ratio(0, gr0, width, false);
        assertEquals(polynomialDerivative(S1_GRSR, gr0), nearColumnWhenFlipped, nearColumnWhenFlipped * 1e-7);
        assertEquals(polynomialDerivative(S1_GRSR, gr0 + (width - 1) * SPACING), farColumnWhenFlipped,
                farColumnWhenFlipped * 1e-7);
    }

    /** On a flat ellipsoid dSR/dGR = sin(incidence); S1 IW spans roughly 30-46 degrees. */
    @Test
    @STTM("SNAP-4377")
    public void testRatioIsSinOfAPlausibleIncidenceAngle() {
        final double near = Math.toDegrees(Math.asin(ratio(0, 0.0, FULL_WIDTH, true)));
        final double far = Math.toDegrees(Math.asin(ratio(FULL_WIDTH - 1, 0.0, FULL_WIDTH, true)));
        assertEquals(30.5, near, 1.0);
        assertEquals(46.0, far, 1.5);
    }
}
