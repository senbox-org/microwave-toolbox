/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. https://www.skywatch.com
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
package eu.esa.sar.insar.gpf;

import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TestHerraezPhaseUnwrapOp {

    private static final OperatorSpi spi = new HerraezPhaseUnwrapOp.Spi();

    @Test
    public void spi_creates_operator() {
        final HerraezPhaseUnwrapOp op = (HerraezPhaseUnwrapOp) spi.createOperator();
        assertNotNull(op);
    }

    @Test
    public void operator_metadata_alias_and_category() {
        final OperatorMetadata md = HerraezPhaseUnwrapOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("Herraez-Phase-Unwrap", md.alias());
        assertEquals("Radar/Interferometric/Unwrapping", md.category());
    }

    /** Constant zero phase => output all zeros. */
    @Test
    public void constant_zero_yields_zero() {
        final int w = 8, h = 8;
        final float[] phase = new float[w * h];
        final boolean[] valid = new boolean[w * h];
        Arrays.fill(valid, true);

        final float[] unw = HerraezPhaseUnwrapOp.unwrap(phase, valid, w, h, 8);
        for (float v : unw) {
            assertEquals(0.0f, v, 1.0e-6f);
        }
    }

    /** Constant nonzero phase => output equals input (no unwrap needed). */
    @Test
    public void constant_nonzero_yields_same() {
        final int w = 8, h = 8;
        final float[] phase = new float[w * h];
        Arrays.fill(phase, 1.5f);
        final boolean[] valid = new boolean[w * h];
        Arrays.fill(valid, true);

        final float[] unw = HerraezPhaseUnwrapOp.unwrap(phase, valid, w, h, 8);
        for (float v : unw) {
            assertEquals(1.5f, v, 1.0e-5f);
        }
    }

    /**
     * Generate a synthetic 2-D linear ramp whose true phase exceeds 2pi,
     * wrap it to (-pi, pi], unwrap with Herraez, and verify recovery up to
     * a global integer 2pi constant.
     */
    @Test
    public void linear_ramp_unwraps_to_within_global_constant() {
        final int w = 32, h = 32;
        final float[] truth = new float[w * h];
        final float[] phase = new float[w * h];
        final boolean[] valid = new boolean[w * h];
        Arrays.fill(valid, true);

        // Phase ramp: 0.4 rad / pixel in x, 0.2 rad / pixel in y
        // total range: ~12.8 + 6.4 = 19.2 rad (well beyond 2pi)
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                final int k = y * w + x;
                truth[k] = 0.4f * x + 0.2f * y;
                phase[k] = (float) wrap(truth[k]);
            }
        }

        final float[] unw = HerraezPhaseUnwrapOp.unwrap(phase, valid, w, h, 8);

        // Recover the global 2pi constant by matching the first pixel
        final double offset = unw[0] - truth[0];
        // Verify it's a multiple of 2pi
        final double offsetMod = offset - 2.0 * Math.PI * Math.round(offset / (2.0 * Math.PI));
        assertEquals("global offset is a multiple of 2pi", 0.0, offsetMod, 0.05);

        // Verify all pixels match truth + offset
        for (int k = 0; k < unw.length; k++) {
            assertEquals("pixel " + k + " unwrapped", truth[k] + offset, unw[k], 0.05);
        }
    }

    /**
     * Synthetic radial parabolic phase exceeding 2pi at the corners.
     * Verify unwrapping recovers continuity (max-min of unwrap matches the
     * true dynamic range up to a 2pi global constant).
     */
    @Test
    public void parabolic_phase_unwrap_preserves_range() {
        final int w = 32, h = 32;
        final float[] truth = new float[w * h];
        final float[] phase = new float[w * h];
        final boolean[] valid = new boolean[w * h];
        Arrays.fill(valid, true);

        final int cx = w / 2;
        final int cy = h / 2;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                final int k = y * w + x;
                final double r2 = (x - cx) * (x - cx) + (y - cy) * (y - cy);
                truth[k] = (float) (0.05 * r2);  // peak ~0.05 * (16^2+16^2) = 25.6 rad
                phase[k] = (float) wrap(truth[k]);
            }
        }

        final float[] unw = HerraezPhaseUnwrapOp.unwrap(phase, valid, w, h, 8);

        // Dynamic range of unwrapped should match truth's dynamic range
        double minTruth = Double.POSITIVE_INFINITY, maxTruth = Double.NEGATIVE_INFINITY;
        double minUnw = Double.POSITIVE_INFINITY, maxUnw = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < unw.length; k++) {
            minTruth = Math.min(minTruth, truth[k]);
            maxTruth = Math.max(maxTruth, truth[k]);
            minUnw = Math.min(minUnw, unw[k]);
            maxUnw = Math.max(maxUnw, unw[k]);
        }
        final double truthRange = maxTruth - minTruth;
        final double unwRange = maxUnw - minUnw;
        assertEquals("dynamic range preserved", truthRange, unwRange, 0.05);
    }

    /** Invalid pixels (masked out) should not corrupt valid-region unwrap. */
    @Test
    public void masked_pixels_are_skipped() {
        final int w = 16, h = 16;
        final float[] phase = new float[w * h];
        final boolean[] valid = new boolean[w * h];
        // First half-row invalid; rest valid with a small ramp
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                final int k = y * w + x;
                if (y == 0 && x < 8) {
                    valid[k] = false;
                    phase[k] = Float.NaN;
                } else {
                    valid[k] = true;
                    phase[k] = (float) wrap(0.2 * x + 0.1 * y);
                }
            }
        }
        final float[] unw = HerraezPhaseUnwrapOp.unwrap(phase, valid, w, h, 8);
        // No exception thrown; valid pixels finite, invalid pixels untouched
        // (the operator's initialize() resets them to NaN, but the static
        // method preserves them, which is the expected behaviour).
        for (int k = 0; k < unw.length; k++) {
            if (valid[k]) {
                assertTrue("valid pixels must be finite at k=" + k + " unw=" + unw[k],
                        !Float.isNaN(unw[k]) && !Float.isInfinite(unw[k]));
            }
        }
    }

    private static double wrap(final double d) {
        return d - 2.0 * Math.PI * Math.floor((d + Math.PI) / (2.0 * Math.PI));
    }
}
