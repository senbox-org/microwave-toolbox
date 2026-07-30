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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The auto-derived output grid step, and the resolution consequences of each policy.
 * <p>
 * Real S1A IW3 numbers from the product used to diagnose this: slant range spacing 2.329562 m,
 * incidence 41.764 deg at near range, azimuth spacing 13.98908 m. Ground range sampling is therefore
 * {@code 2.329562 / sin(41.764) = 3.498 m} — four times finer than azimuth.
 * <p>
 * The historical default derives {@code max(azimuth, slantRange)}, i.e. square cells at the coarser
 * axis, which for this product means 13.989 m square and about 4x of ground-range detail discarded.
 * These tests pin what each policy costs so the trade cannot be changed unknowingly.
 */
public class TestGSLCGridSpacing {

    private static final double SLANT_RANGE_SPACING = 2.329562;
    private static final double INCIDENCE_NEAR_DEG = 41.764;
    private static final double AZIMUTH_SPACING = 13.98908;

    private static final double GROUND_RANGE_SPACING =
            SLANT_RANGE_SPACING / Math.sin(Math.toRadians(INCIDENCE_NEAR_DEG));

    @Test
    public void groundRangeIsAboutFourTimesFinerThanAzimuthForS1Iw() {
        assertEquals(3.498, GROUND_RANGE_SPACING, 0.005);
        assertEquals(4.0, AZIMUTH_SPACING / GROUND_RANGE_SPACING, 0.05);
    }

    /** The default: square at the coarser axis. Smallest output, discards ~4x of range detail. */
    @Test
    public void squareCoarsestDerivesTheAzimuthSpacingAndCostsRangeDetail() {
        final double step = Math.max(AZIMUTH_SPACING, SLANT_RANGE_SPACING);

        assertEquals(AZIMUTH_SPACING, step, 1e-9);
        assertEquals("range detail discarded", 4.0, step / GROUND_RANGE_SPACING, 0.05);
    }

    /** Rectangular native: nothing discarded, ~4x the pixels of the default. */
    @Test
    public void nativeAnisotropicPreservesBothAxesAtFourTimesTheData() {
        final double stepX = GROUND_RANGE_SPACING;
        final double stepY = AZIMUTH_SPACING;

        assertEquals("no range detail lost", 1.0, stepX / GROUND_RANGE_SPACING, 1e-9);
        assertEquals("no azimuth oversampling", 1.0, stepY / AZIMUTH_SPACING, 1e-9);

        final double defaultStep = Math.max(AZIMUTH_SPACING, SLANT_RANGE_SPACING);
        final double dataRatio = (defaultStep * defaultStep) / (stepX * stepY);
        assertEquals("about 4x the pixels of the default", 4.0, dataRatio, 0.2);
    }

    /** Square at the finest axis: nothing discarded, pixels stay square, ~16x the data. */
    @Test
    public void squareFinestPreservesBothAxesAtSixteenTimesTheData() {
        final double step = Math.min(GROUND_RANGE_SPACING, AZIMUTH_SPACING);

        assertEquals(GROUND_RANGE_SPACING, step, 1e-9);

        final double defaultStep = Math.max(AZIMUTH_SPACING, SLANT_RANGE_SPACING);
        final double dataRatio = (defaultStep * defaultStep) / (step * step);
        assertEquals("about 16x the pixels of the default", 16.0, dataRatio, 0.5);
    }

    /**
     * Both derived steps must be quantised, not just X. The standard-grid origin snaps each axis
     * independently ({@code origin = round(coord/step)*step}), so an unquantised Y step puts two
     * products on different northing lattices for exactly the reason an unquantised X step would —
     * which is what the quantisation exists to prevent.
     */
    @Test
    public void bothAxesQuantiseToTheSameLatticeQuantum() {
        final double x = GSLCGeocodingOp.quantizePixelSpacing(GROUND_RANGE_SPACING);
        final double y = GSLCGeocodingOp.quantizePixelSpacing(AZIMUTH_SPACING);

        assertEquals(x, Math.round(x / GSLCGeocodingOp.PIXEL_SPACING_QUANTUM_M)
                * GSLCGeocodingOp.PIXEL_SPACING_QUANTUM_M, 1e-12);
        assertEquals(y, Math.round(y / GSLCGeocodingOp.PIXEL_SPACING_QUANTUM_M)
                * GSLCGeocodingOp.PIXEL_SPACING_QUANTUM_M, 1e-12);
        assertTrue("quantisation must not move the step by a geometrically relevant amount",
                Math.abs(x - GROUND_RANGE_SPACING) <= GSLCGeocodingOp.PIXEL_SPACING_QUANTUM_M);
    }

    /** The three policy tokens must stay distinct and stable — they appear in saved graphs. */
    @Test
    public void policyTokensAreDistinctAndStable() {
        assertEquals("SQUARE_COARSEST", GSLCGeocodingOp.GRID_SQUARE_COARSEST);
        assertEquals("NATIVE_ANISOTROPIC", GSLCGeocodingOp.GRID_NATIVE_ANISOTROPIC);
        assertEquals("SQUARE_FINEST", GSLCGeocodingOp.GRID_SQUARE_FINEST);
        assertTrue(!GSLCGeocodingOp.GRID_SQUARE_COARSEST.equals(GSLCGeocodingOp.GRID_NATIVE_ANISOTROPIC));
        assertTrue(!GSLCGeocodingOp.GRID_SQUARE_COARSEST.equals(GSLCGeocodingOp.GRID_SQUARE_FINEST));
        assertTrue(!GSLCGeocodingOp.GRID_NATIVE_ANISOTROPIC.equals(GSLCGeocodingOp.GRID_SQUARE_FINEST));
    }

    /**
     * The default is now NATIVE_ANISOTROPIC: no resolution discarded. This is a deliberate grid change
     * from the historical SQUARE_COARSEST — the derived step differs, so the standard-grid lattice
     * differs, and a stack begun before the change must set SQUARE_COARSEST explicitly to be extended.
     * <p>
     * Pinned so the default cannot drift back silently, and so the compatibility note stays attached
     * to a failing test rather than only to a comment.
     */
    @Test
    public void defaultPolicyIsNativeAnisotropic() throws Exception {
        final java.lang.reflect.Field f = GSLCGeocodingOp.class.getDeclaredField("gridSpacing");
        f.setAccessible(true);
        assertEquals(GSLCGeocodingOp.GRID_NATIVE_ANISOTROPIC, f.get(new GSLCGeocodingOp()));
    }
}
