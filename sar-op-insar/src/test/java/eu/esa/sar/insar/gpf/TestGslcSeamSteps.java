/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc.
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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The seam-step corrector removes the burst-seam DISCONTINUITY that survives the carrier
 * difference and the per-burst azimuth ramp: measured on S1A x S1C as a smooth range-quadratic
 * step of 3-6 rad per seam that wraps along the swath. It is fitted from ACROSS-SEAM multilooked
 * phase differences — smooth signal (deformation, atmosphere, residual topography) is continuous
 * across a seam and cancels out of that difference, so unlike per-burst absolute range terms
 * (measured absorbing the coseismic fan) this correction cannot eat geophysical signal.
 * <p>
 * Measured steps are WRAPPED samples of a smooth function of range: the fitter must unwrap
 * along range before fitting, and the overall 2*pi branch is irrelevant (a 2*pi step is the
 * identity on a wrapped interferogram).
 */
public class TestGslcSeamSteps {

    private static final double N = InterferogramOp.GSLC_RAMP_NORM;

    /** truth step profile in xn = x/N: quadratic sweeping several wraps, like the measured data */
    private static double truth(final double xn) {
        return -0.9 - 0.35 * xn + 0.0062 * xn * xn;   // -0.9 .. ~-5.8 .. wraps back, over xn 0..48
    }

    private static double wrap(final double a) {
        return Math.atan2(Math.sin(a), Math.cos(a));
    }

    @Test
    public void testUnwrapAlongRangeRemovesWrapJumps() {
        final int n = 16;
        final double[] w = new double[n];
        for (int i = 0; i < n; i++) {
            w[i] = wrap(truth(3.0 * i));
        }
        final double[] u = InterferogramOp.unwrapStepsAlongRange(w);
        for (int i = 1; i < n; i++) {
            assertTrue("adjacent unwrapped samples must not jump by more than pi",
                    Math.abs(u[i] - u[i - 1]) < Math.PI);
        }
        // recovered curve equals the truth up to one common 2*pi multiple
        final double off = u[0] - truth(0.0);
        assertEquals("offset must be a multiple of 2*pi", 0.0,
                Math.abs(off - 2.0 * Math.PI * Math.round(off / (2.0 * Math.PI))), 1e-9);
        for (int i = 0; i < n; i++) {
            assertEquals(truth(3.0 * i) + off, u[i], 1e-9);
        }
    }

    /** higher-order truth: cubic component defeats a global quadratic (measured misfit mode) */
    private static double truthCubic(final double xn) {
        return -0.9 - 0.35 * xn + 0.0062 * xn * xn + 2.4e-4 * (xn - 24) * (xn - 24) * (xn - 24) / 24.0;
    }

    @Test
    public void testBuildSeamStepTableRecoversWrappedProfile() {
        // 16 windows like the measurement, moderate noise, one outlier window, two gaps: the
        // table must reproduce the truth's range dependence (2*pi-branch invariant) INCLUDING
        // the cubic structure a global quadratic provably misses at the swath edges.
        final int n = 16;
        final double[] xn = new double[n], step = new double[n], wgt = new double[n];
        final java.util.Random rng = new java.util.Random(9);
        for (int i = 0; i < n; i++) {
            xn[i] = 1.0 + 3.0 * i;
            step[i] = wrap(truthCubic(xn[i]) + 0.15 * rng.nextGaussian());
            wgt[i] = 0.4;
        }
        step[5] = wrap(step[5] + 2.6);            // outlier window: median-of-3 must absorb it
        step[9] = Double.NaN; wgt[9] = 0.0;       // unmeasurable windows (low coherence)
        step[10] = Double.NaN; wgt[10] = 0.0;
        final double[][] tab = InterferogramOp.buildSeamStepTable(xn, step, wgt);
        final InterferogramOp.GslcSeamSteps steps =
                new InterferogramOp.GslcSeamSteps(new double[][][]{tab});
        // tolerance 0.45: within the ~0.6 rad no-seam control floor of the real measurement,
        // and far below the 1-2.5 rad edge misfit that broke the global-quadratic design
        final double ref = steps.stepAt(0, 7.0 * N);
        for (double x = 1.0; x <= 46.0; x += 4.5) {
            final double got = steps.stepAt(0, x * N) - ref;
            final double want = truthCubic(x) - truthCubic(7.0);
            assertEquals("step range-dependence at xn=" + x, want, got, 0.45);
        }
    }

    @Test
    public void testBuildSeamStepTableNullOnInsufficientData() {
        assertNull(InterferogramOp.buildSeamStepTable(
                new double[]{1, 2}, new double[]{0.1, 0.2}, new double[]{1, 1}));
        assertNull(InterferogramOp.buildSeamStepTable(
                new double[]{1, 2, 3, 4},
                new double[]{Double.NaN, Double.NaN, Double.NaN, Double.NaN},
                new double[]{1, 1, 1, 1}));
    }

    @Test
    public void testSeamRowBisectionBothOrientations() {
        // ASCENDING map (north-up, north = later time): burst index DECREASES with row.
        // seam k sits where the index crosses k+1 -> k going down.
        final java.util.function.IntUnaryOperator ascending =
                row -> Math.max(0, Math.min(8, 8 - row / 100));
        // DESCENDING map: burst index INCREASES with row.
        final java.util.function.IntUnaryOperator descending =
                row -> Math.max(0, Math.min(8, row / 100));

        for (int k = 0; k < 8; k++) {
            final int rAsc = InterferogramOp.findSeamRowGeneric(ascending, k, 8, 892);
            // ascending: rows [0,100) are burst 8 ... burst k occupies [(8-k)*100, (9-k)*100)
            assertEquals("ascending seam " + k, (8 - k) * 100, rAsc);
            final int rDesc = InterferogramOp.findSeamRowGeneric(descending, k, 8, 892);
            // descending: burst k+1 starts at row (k+1)*100
            assertEquals("descending seam " + k, (k + 1) * 100, rDesc);
        }
        // seam not bracketed by the probed range -> -1 (never a wrong row)
        assertEquals(-1, InterferogramOp.findSeamRowGeneric(ascending, 8, 8, 892));
        assertEquals(-1, InterferogramOp.findSeamRowGeneric(row -> 3, 2, 8, 892));

        // a mid-row solve failure (-1 from the row->burst function) must ABORT with -1, not be
        // treated as an ordinary bisection side (which could converge on a wrong row)
        final java.util.function.IntUnaryOperator flakyMid =
                row -> (row > 300 && row < 500) ? -1 : Math.max(0, Math.min(8, 8 - row / 100));
        assertEquals("mid-row failure on the search path must abort",
                -1, InterferogramOp.findSeamRowGeneric(flakyMid, 4, 8, 892));
        // ...but with a bracket whose search path avoids the flaky rows, the seam still resolves
        assertEquals((8 - 6) * 100, InterferogramOp.findSeamRowGeneric(flakyMid, 6, 8, 292));
    }

    @Test
    public void testBuildSeamStepTableGuardsEndNodeOutliers() {
        // median-of-3 cannot protect the END nodes, and their values flat-extrapolate over the
        // whole swath margin — a deviant end window must be clamped to its neighbour.
        final int n = 10;
        final double[] xn = new double[n], step = new double[n], wgt = new double[n];
        for (int i = 0; i < n; i++) {
            xn[i] = 2.0 + 4.0 * i;
            step[i] = wrap(-0.4 - 0.02 * xn[i]);   // gentle profile, no wraps
            wgt[i] = 0.5;
        }
        final double clean0 = step[1];             // neighbour of the end node
        step[0] = wrap(step[0] + 2.6);             // gross outlier at the FIRST window
        final double[][] tab = InterferogramOp.buildSeamStepTable(xn, step, wgt);
        final InterferogramOp.GslcSeamSteps steps =
                new InterferogramOp.GslcSeamSteps(new double[][][]{tab});
        // the flat-extrapolated start of the profile must follow the clean neighbourhood,
        // not the outlier
        assertEquals(clean0, steps.stepAt(0, 0.0), 0.3);
    }

    @Test
    public void testEndNodeClampScalesWithActualEndGap() {
        // After coherence gating, the end pair can sit several window spacings apart — exactly
        // when a large GENUINE end delta occurs. The clamp threshold must scale with the actual
        // end gap, not the median gap, or it flattens real steep ends.
        final double slope = 0.3;   // rad per xn, genuine
        final double[] xn = {2, 4, 6, 8, 10, 12, 14, 22};   // end gap 8 xn vs median 2 xn
        final int n = xn.length;
        final double[] step = new double[n], wgt = new double[n];
        for (int i = 0; i < n; i++) {
            step[i] = wrap(slope * xn[i]);
            wgt[i] = 0.5;
        }
        final double[][] tab = InterferogramOp.buildSeamStepTable(xn, step, wgt);
        final InterferogramOp.GslcSeamSteps steps =
                new InterferogramOp.GslcSeamSteps(new double[][][]{tab});
        // genuine end delta = 0.3 * 8 = 2.4 rad; an unscaled 3*medianDiff (=1.8) clamp would
        // flatten the end to its neighbour. Measure against xn=12 (a symmetric interior node —
        // the node at xn=14 carries a small, known 3-point-smoothing bias from the uneven gap).
        final double got = steps.stepAt(0, 22.0 * N) - steps.stepAt(0, 12.0 * N);
        assertEquals("genuine steep end across a wide gap must survive the clamp",
                slope * 10.0, got, 0.3);
    }

    @Test
    public void testHugeGatedGapSplitsTableInsteadOfWrongBranch() {
        // Across a long gated (incoherent) run the true step can change by more than pi between
        // adjacent retained windows — the unwrap then picks a wrong 2*pi branch and interpolates
        // a FALSE fringe across the gap. The builder must split at oversized gaps and keep only
        // the longest segment (flat extrapolation beyond it = no invented transition).
        final double slope = 0.35;             // rad/xn: 30-xn gap => 10.5 rad true change
        final double[] xn = {1, 4, 7, 10, 13, 16, 46, 49, 52, 55, 58};
        final int n = xn.length;
        final double[] step = new double[n], wgt = new double[n];
        for (int i = 0; i < n; i++) {
            step[i] = wrap(slope * xn[i]);
            wgt[i] = 0.5;
        }
        final double[][] tab = InterferogramOp.buildSeamStepTable(xn, step, wgt);
        final InterferogramOp.GslcSeamSteps steps =
                new InterferogramOp.GslcSeamSteps(new double[][][]{tab});
        // beyond the kept (first, longest) segment the correction must be FLAT — no interpolated
        // pseudo-transition across the unmeasurable gap
        assertEquals("no invented transition across the gated gap", 0.0,
                steps.stepAt(0, 58.0 * N) - steps.stepAt(0, 16.0 * N), 1e-9);
        // and within the kept segment the profile is still live
        assertEquals(slope * 9.0, steps.stepAt(0, 13.0 * N) - steps.stepAt(0, 4.0 * N), 0.25);
    }

    @Test
    public void testCumulativeApplicationAcrossBursts() {
        // two seams with known tables; burst m must accumulate all seams below it, interpolate
        // linearly between nodes and extrapolate FLAT beyond the measured range
        final double[][][] tabs = {
                {{10.0, 20.0, 30.0}, {0.5, 0.7, 0.9}},
                {{10.0, 30.0}, {-1.0, -2.0}},
                null                  // unmeasured seam contributes nothing
        };
        final InterferogramOp.GslcSeamSteps steps = new InterferogramOp.GslcSeamSteps(tabs);
        assertEquals(0.0, steps.cumAt(0, 20.0 * N), 0.0);
        assertEquals("node value", 0.7, steps.cumAt(1, 20.0 * N), 1e-12);
        assertEquals("interpolated", 0.6, steps.cumAt(1, 15.0 * N), 1e-12);
        assertEquals("flat extrapolation left", 0.5, steps.cumAt(1, 2.0 * N), 1e-12);
        assertEquals("flat extrapolation right", 0.9, steps.cumAt(1, 45.0 * N), 1e-12);
        assertEquals("two seams accumulate", 0.7 - 1.5, steps.cumAt(2, 20.0 * N), 1e-12);
        assertEquals("null seam adds nothing", steps.cumAt(2, 20.0 * N), steps.cumAt(3, 20.0 * N), 0.0);
        assertEquals("burst index past the table clamps",
                steps.cumAt(2, 20.0 * N), steps.cumAt(9, 20.0 * N), 0.0);
    }
}
