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
package eu.esa.sar.insar.gpf.coregistration;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Spec for {@link CreateStackOp#fitAffineOffsetField} — the affine offset-field fit that
 * upgrades the GSLC auto-coregistration from a constant CC bias to a spatially-varying
 * correction. Motivated by 1995 ERS-1/ERS-2 tandem VMP pairs whose data-vs-annotation
 * registration drifts ~1.7 px in range across the swath; a constant bias leaves that drift
 * in place and the retained range carrier renders it as hundreds of radians of spurious
 * smooth fringes in the GSLC interferogram.
 */
public class TestGslcOffsetFieldFit {

    private static final int W = 4900, H = 26000;

    /** Synthetic GCPs on a grid covering the scene, offsets from a known affine field. */
    private static double[][] syntheticGcps(final double a0, final double a1, final double a2,
                                            final double noise, final int nOutliers) {
        final Random rnd = new Random(42);
        final int nx = 8, ny = 10;
        final int n = nx * ny;
        final double[] x = new double[n], y = new double[n], d = new double[n];
        int k = 0;
        for (int j = 0; j < ny; j++) {
            for (int i = 0; i < nx; i++) {
                x[k] = (i + 0.5) * W / nx;
                y[k] = (j + 0.5) * H / ny;
                d[k] = a0 + a1 * x[k] + a2 * y[k] + noise * rnd.nextGaussian();
                k++;
            }
        }
        for (int m = 0; m < nOutliers; m++) {
            d[rnd.nextInt(n)] += (m % 2 == 0 ? 3.0 : -4.0);
        }
        return new double[][]{x, y, d};
    }

    @Test
    public void testRecoversFieldWithNoiseAndOutliers() {
        // the measured ERS tandem drift scale: ~1.7 px across a 4900-px swath
        final double a0 = 0.41, a1 = -3.5e-4, a2 = 6.0e-5;
        final double[][] g = syntheticGcps(a0, a1, a2, 0.02, 5);
        final double[] c = CreateStackOp.fitAffineOffsetField(g[0], g[1], g[2], W, H);
        assertNotNull("fit must accept a well-spread GCP set", c);
        assertEquals(a0, c[0], 0.03);
        assertEquals(a1, c[1], 2e-5);
        assertEquals(a2, c[2], 2e-6);
        // trimmed outliers must not drag the plane: prediction error small at all corners
        for (final double[] corner : new double[][]{{0, 0}, {W - 1, 0}, {0, H - 1}, {W - 1, H - 1}}) {
            final double truth = a0 + a1 * corner[0] + a2 * corner[1];
            final double pred = c[0] + c[1] * corner[0] + c[2] * corner[1];
            assertEquals(truth, pred, 0.05);
        }
    }

    @Test
    public void testRejectsTooFewGcps() {
        final double[][] g = syntheticGcps(0.4, -3e-4, 5e-5, 0.02, 0);
        final double[] x = java.util.Arrays.copyOf(g[0], 10);
        final double[] y = java.util.Arrays.copyOf(g[1], 10);
        final double[] d = java.util.Arrays.copyOf(g[2], 10);
        assertNull(CreateStackOp.fitAffineOffsetField(x, y, d, W, H));
    }

    @Test
    public void testRejectsPoorSpread() {
        // all GCPs clustered in one corner: a slope from these would be pure extrapolation
        final Random rnd = new Random(7);
        final int n = 60;
        final double[] x = new double[n], y = new double[n], d = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = 100 + rnd.nextDouble() * 0.2 * W;
            y[i] = 200 + rnd.nextDouble() * 0.2 * H;
            d[i] = 0.4 - 3e-4 * x[i] + rnd.nextGaussian() * 0.02;
        }
        assertNull(CreateStackOp.fitAffineOffsetField(x, y, d, W, H));
    }

    @Test
    public void testRejectsImplausiblySteepDrift() {
        // 60 px of drift across the swath is not coregistration, it is a broken match set
        final double[][] g = syntheticGcps(0.0, 60.0 / W, 0.0, 0.0, 0);
        assertNull(CreateStackOp.fitAffineOffsetField(g[0], g[1], g[2], W, H));
    }

    // ---- block FFT cross-correlation (the field estimator's measurement core) ----------

    /** Smooth random amplitude field (speckle-like but band-limited so sub-pixel is defined). */
    private static double[] smoothAmplitude(final int n, final long seed) {
        final Random rnd = new Random(seed);
        final double[] a = new double[n * n];
        for (int k = 0; k < a.length; k++) a[k] = rnd.nextDouble();
        // separable box smoothing, 2 passes
        for (int pass = 0; pass < 2; pass++) {
            final double[] b = a.clone();
            for (int r = 0; r < n; r++) {
                for (int c = 0; c < n; c++) {
                    double s = 0;
                    for (int dc = -2; dc <= 2; dc++) s += b[r * n + (((c + dc) % n + n) % n)];
                    a[r * n + c] = s / 5;
                }
            }
            final double[] b2 = a.clone();
            for (int r = 0; r < n; r++) {
                for (int c = 0; c < n; c++) {
                    double s = 0;
                    for (int dr = -2; dr <= 2; dr++) s += b2[(((r + dr) % n + n) % n) * n + c];
                    a[r * n + c] = s / 5;
                }
            }
        }
        return a;
    }

    @Test
    public void testBlockCrossCorrelate_RecoversIntegerShift() {
        final int n = 256;
        final double[] a = smoothAmplitude(n, 11);
        final double[] b = new double[n * n];
        final int sRow = 3, sCol = -5;   // b(x) = a(x + s)  =>  a(x) = b(x - s): expected d = s
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                b[r * n + c] = a[(((r + sRow) % n + n) % n) * n + (((c + sCol) % n + n) % n)];
            }
        }
        final double[] d = CreateStackOp.blockCrossCorrelate(a, b, n, 8);
        assertNotNull(d);
        assertEquals(sRow, d[0], 0.05);
        assertEquals(sCol, d[1], 0.05);
    }

    @Test
    public void testBlockCrossCorrelate_RejectsExcessiveShift() {
        final int n = 256;
        final double[] a = smoothAmplitude(n, 12);
        final double[] b = new double[n * n];
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                b[r * n + c] = a[(((r + 20) % n + n) % n) * n + c];
            }
        }
        assertNull(CreateStackOp.blockCrossCorrelate(a, b, n, 8));
    }

    @Test
    public void testParabolicPeakOffset() {
        // symmetric peak -> 0; peak leaning right -> positive, bounded to [-0.5, 0.5]
        assertEquals(0.0, CreateStackOp.parabolicPeakOffset(1.0, 2.0, 1.0), 1e-12);
        assertTrue(CreateStackOp.parabolicPeakOffset(1.0, 2.0, 1.9) > 0.2);
        assertTrue(Math.abs(CreateStackOp.parabolicPeakOffset(1.0, 1.0, 1.0)) <= 0.5);
    }

    @Test
    public void testMaxAbsOffsetAtCorners() {
        // scalar fallback
        assertEquals(0.75, CreateStackOp.maxAbsOffsetAtCorners(null, 0.75, W, H), 1e-12);
        // field: extremes live at the corners of an affine plane
        final double[] c = {0.4, -3.5e-4, 6.0e-5};
        double expected = 0;
        for (final double[] corner : new double[][]{{0, 0}, {W - 1, 0}, {0, H - 1}, {W - 1, H - 1}}) {
            expected = Math.max(expected, Math.abs(c[0] + c[1] * corner[0] + c[2] * corner[1]));
        }
        assertEquals(expected, CreateStackOp.maxAbsOffsetAtCorners(c, 0.0, W, H), 1e-12);
        assertTrue(expected > 1.0); // the ERS-scale field genuinely exceeds the rebuild threshold
    }

    // ---- cross-check frame alignment (fix round 3) --------------------------------------

    /**
     * {@link CreateStackOp#estimateSlcBiasByGcpField}'s block-CC cross-check compares the GCP
     * field (fitted in the RAW-SLAVE pixel frame: positions were built as nested-stack-frame +
     * {@code initOffset}) against the block field (fitted in the MASTER pixel frame directly —
     * {@code estimateSlcBiasByBlocks}' sample positions are master-raster block centres, verified
     * by inspection). Evaluating both fields at the SAME (x, y) without correcting for that frame
     * difference silently reintroduces exactly the kind of apples-to-oranges mismatch fix-round-1
     * eliminated for drift — it was dormant on the ERS fixture only because that pair's measured
     * {@code initOffset} happens to be {0, 0}.
     * <p>
     * This constructs two synthetic AFFINE fields that represent the identical physical field,
     * one expressed in each frame, related by a coordinate shift of (37, -170) — i.e.
     * {@code polyB(x, y) == polyA(x + 37, y - 170)} for every (x, y). The cross-check helper must
     * thread that same shift into its own evaluation to see them agree.
     */
    @Test
    public void medianFieldDiffOverScene_threadsCoordinateOffsetBetweenFrames() {
        final double offX = 37.0, offY = -170.0;
        final double c = 0.5, a = 0.0010, b = 0.0020;
        // polyA: the field expressed in ITS OWN (raw-slave) frame: F(u, v) = c + a*u + b*v
        final double[] polyA = {c, a, b};
        // polyB: the SAME physical field expressed in the OTHER (master) frame, i.e.
        // polyB(x, y) must equal polyA(x + offX, y + offY) at every (x, y) — algebraically,
        // an affine field's coordinate shift folds entirely into the constant term.
        final double[] polyB = {c + a * offX + b * offY, a, b};

        // Correctly threaded: polyA evaluated with the frame offset, polyB evaluated natively
        // (offset 0) -- both then describe the SAME physical positions.
        final double diffCorrect = CreateStackOp.medianFieldDiffOverScene(
                polyA, offX, offY, polyB, 0.0, 0.0, W, H);
        assertEquals("frame-aligned evaluation of two representations of the identical field "
                + "must show ~zero disagreement", 0.0, diffCorrect, 1e-9);

        // The bug: both evaluated at the same raw (x, y) with NO offset applied to either side
        // (what the pre-fix-round-3 code did unconditionally).
        final double diffIgnored = CreateStackOp.medianFieldDiffOverScene(
                polyA, 0.0, 0.0, polyB, 0.0, 0.0, W, H);
        assertTrue("ignoring the inter-frame coordinate offset must produce a grossly nonzero "
                + "diff (got " + diffIgnored + ")", diffIgnored > 0.1);
    }

    /** Same frame-alignment requirement for the median-vs-median branch (constants-only block). */
    @Test
    public void medianFieldOverScene_threadsCoordinateOffset() {
        final double offX = 37.0, offY = -170.0;
        final double c = 0.5, a = 0.0010, b = 0.0020;
        final double[] polyA = {c, a, b};

        // Evaluating polyA at (x, y) directly (offset 0,0) vs. shifted (x+offX, y+offY) must
        // differ by exactly the physically-expected amount over this affine field's grid.
        final double medNative = CreateStackOp.medianFieldOverScene(polyA, W, H, 0.0, 0.0);
        final double medShifted = CreateStackOp.medianFieldOverScene(polyA, W, H, offX, offY);
        assertEquals("shifting every sample by a constant offset on an affine field must shift "
                + "the median by exactly a*offX + b*offY", a * offX + b * offY,
                medShifted - medNative, 1e-9);
    }
}
