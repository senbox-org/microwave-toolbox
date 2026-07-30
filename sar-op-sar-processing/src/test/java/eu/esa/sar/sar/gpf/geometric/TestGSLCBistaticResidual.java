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

/**
 * The bistatic azimuth residual must not be applied twice.
 * <p>
 * {@code Sentinel1Level1Directory} sets {@code bistatic_correction_applied = 1} for every Sentinel-1
 * product, so this operator's range-dependent residual branch is ALWAYS live — it is the one
 * correction here not gated behind a parameter that defaults to false. ETAD's azimuth layers carry
 * the same term.
 * <p>
 * Measured on a real S1B IW product ({@code ETADBistaticMeasurement}): ETAD's
 * {@code bistaticCorrectionAz} spans -0.1700 ms across the sub-swath, this operator's
 * {@code (rFar - rNear)/c} residual spans 0.1687 ms — the same quantity to within 0.8%. Roughly
 * 1.15 m, or ~0.08 output pixels, and being range-dependent-only it is common-mode, so it degrades
 * exactly the absolute geolocation ETAD is applied to improve.
 * <p>
 * Hermetic: the decision is a pure function of four inputs, so no product, orbit or DEM is needed.
 */
public class TestGSLCBistaticResidual {

    private static final double R_NEAR = 800_400.0;   // m, from the reference IW1 fixture

    /** The pre-existing behaviour for a normal Sentinel-1 product must be untouched. */
    @Test
    public void sentinel1WithoutEtadUsesNearRangeAsReference() {
        assertEquals(R_NEAR, GSLCGeocodingOp.resolveBistaticCorrectionRefRange(
                true, "SENTINEL-1B", R_NEAR, false), 0.0);
    }

    /** THE FIX: an ETAD azimuth correction in the pixels suppresses our residual. */
    @Test
    public void etadAzimuthCorrectionSuppressesTheResidual() {
        assertEquals("ETAD already applied the same range-dependent bistatic term",
                0.0, GSLCGeocodingOp.resolveBistaticCorrectionRefRange(
                        true, "SENTINEL-1B", R_NEAR, true), 0.0);
    }

    /**
     * ETAD in grids-only mode moves no pixels, so {@code etad_azimuth_applied} is 0 there and the
     * residual must still be applied. Guards against suppressing on the mere presence of ETAD.
     */
    @Test
    public void etadWithoutAzimuthResamplingLeavesTheResidualInPlace() {
        assertEquals(R_NEAR, GSLCGeocodingOp.resolveBistaticCorrectionRefRange(
                true, "SENTINEL-1A", R_NEAR, false), 0.0);
    }

    /** No IPF bulk correction recorded means no residual to apply, ETAD or not. */
    @Test
    public void noBulkCorrectionMeansNoResidual() {
        assertEquals(0.0, GSLCGeocodingOp.resolveBistaticCorrectionRefRange(
                false, "SENTINEL-1B", R_NEAR, false), 0.0);
        assertEquals(0.0, GSLCGeocodingOp.resolveBistaticCorrectionRefRange(
                false, "SENTINEL-1B", R_NEAR, true), 0.0);
    }

    /** The residual models a Sentinel-1 IPF behaviour and must not leak to other missions. */
    @Test
    public void nonSentinel1MissionsGetNoResidual() {
        assertEquals(0.0, GSLCGeocodingOp.resolveBistaticCorrectionRefRange(
                true, "RADARSAT-2", R_NEAR, false), 0.0);
        assertEquals(0.0, GSLCGeocodingOp.resolveBistaticCorrectionRefRange(
                true, "ENVISAT", R_NEAR, false), 0.0);
    }

    /** A missing mission string must not NPE. */
    @Test
    public void nullMissionIsSafe() {
        assertEquals(0.0, GSLCGeocodingOp.resolveBistaticCorrectionRefRange(
                true, null, R_NEAR, false), 0.0);
    }
}
