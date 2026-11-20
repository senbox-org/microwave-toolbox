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
package org.csa.rstb.polarimetric.gpf;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Synthetic-distortion regression tests for {@link PolarimetricCalibrationOp}.
 * <p>
 * Clutter: reciprocal, reflection-symmetric, HH ~ CN(0,1), VV = g (0.5 HH + sqrt(0.75) CN(0,1)) (so
 * sigma_VV / sigma_HH = g^2 and &lt;HH VV*&gt; is real), HV = VH ~ CN(0, -15 dB). With g = 1 (0 dB) the scene
 * satisfies the equal-power, zero-phase assumption of the optional scene-based co-pol imbalance estimate.
 * <p>
 * Distortion, in the operator's own convention (k = [HH, HV, VH, VV], D = R (x) T,
 * R = [[1, d4], [d3, f]], T = [[1, d2], [d1, f]]): cross-talk |d| = -25 dB with phases 0.3, 1.1, 2.0,
 * -0.7 rad and imbalance f = 1.15 exp(0.25 i) (VV/HH amplitude |f|^2 = 1.32).
 * <p>
 * By default the operator keeps f = 1 and solves only the cross-talk. Because R = [[1, d4/f], [d3, 1]] diag(1, f)
 * and T = [[1, d2/f], [d1, 1]] diag(1, f) exactly, the cross-talk solve then finds (d1, d2/f, d3, d4/f) and the
 * corrected data is diag(1, f, f, f^2) S: cross-talk removed, co-pol imbalance left untouched.
 */
public class TestPolarimetricCalibrationOp {

    private static final int W = 128, H = 128;
    private static final double D_AMP = Math.pow(10, -25.0 / 20.0);
    private static final double[] D_PHASE = {0.3, 1.1, 2.0, -0.7};
    private static final double F_AMP = 1.15, F_PHASE = 0.25;

    /*
     * Tolerances:
     * - Each run starts from f = 1, delta = 0. The first pass is a first-order solve. It neglects terms of
     *   order (sigma_x / sigma_hh) * delta, i.e. about 2 * (-15 dB) = 6 % of delta, and the f^2 / delta coupling.
     *   Later passes re-solve on the original covariance corrected by the accumulated estimate, so by pass 2
     *   only the sample-statistics error is left: about 3-4 % per delta on 128 x 128 looks (numpy prototype:
     *   1 pass 5-6 %, 2-8 passes 2.6-4.3 %).
     * - The 15 % delta bound and the 0.02 bound on f therefore hold for Quegan (1 pass) and for the default
     *   4 passes as well as for 8.
     * - The co/cross correlation bound of 0.02 is far above the residual of even a single pass (about -50 dB).
     */

    // ----- default (estimateCoPolImbalance = false): f = 1, true VV/HH preserved -------------------

    /** C1 regression: a scene with no distortion and a true VV/HH of +6 dB must keep +6 dB. */
    @Test
    public void testDefaultPreservesTrueCoPolRatio() throws Exception {
        for (String method : new String[]{PolarimetricCalibrationOp.METHOD_AINSWORTH,
                PolarimetricCalibrationOp.METHOD_QUEGAN}) {
            final Product src = createProduct(6.0, 0.0, 1.0, 0.0);
            final Product trg = runOp(src, method, null, null);

            final double[][][] kIn = readScatteringVectors(src);
            final double[][][] kOut = readScatteringVectors(trg);
            final double inDb = 10 * Math.log10(power(kIn, 3) / power(kIn, 0));
            final double outDb = 10 * Math.log10(power(kOut, 3) / power(kOut, 0));
            assertEquals(method + ": input VV/HH (dB)", 6.0, inDb, 0.1);
            assertEquals(method + ": VV/HH after (dB)", 6.0, outDb, 0.1);
            assertEquals(method + ": VV/HH after vs before (dB)", inDb, outDb, 0.02);

            final MetadataElement cal = getCal(trg);
            assertEquals(method + ": channelImbalance_re", 1.0, cal.getAttributeDouble("channelImbalance_re"), 0.0);
            assertEquals(method + ": channelImbalance_im", 0.0, cal.getAttributeDouble("channelImbalance_im"), 0.0);
        }
    }

    @Test
    public void testCrosstalkRemovedWithDefaultImbalance() throws Exception {
        runCrosstalkOnly(PolarimetricCalibrationOp.METHOD_AINSWORTH, 8);
    }

    @Test
    public void testAinsworthDefaultIterations() throws Exception {
        runCrosstalkOnly(PolarimetricCalibrationOp.METHOD_AINSWORTH, null);   // default numIterations = 4
    }

    @Test
    public void testQueganSinglePass() throws Exception {
        runCrosstalkOnly(PolarimetricCalibrationOp.METHOD_QUEGAN, null);      // Quegan = one pass
    }

    // ----- opt-in (estimateCoPolImbalance = true) on an equal-power scene: f recovered ----------------

    @Test
    public void testEstimateCoPolImbalanceRecoversF() throws Exception {
        runWithImbalanceEstimate(PolarimetricCalibrationOp.METHOD_AINSWORTH, 8);
    }

    @Test
    public void testEstimateCoPolImbalanceQuegan() throws Exception {
        runWithImbalanceEstimate(PolarimetricCalibrationOp.METHOD_QUEGAN, null);
    }

    private static void runCrosstalkOnly(final String method, final Integer numIterations) throws Exception {
        final Product src = createProduct(0.0, D_AMP, F_AMP, F_PHASE);
        final Product trg = runOp(src, method, numIterations, null);

        final MetadataElement cal = getCal(trg);
        assertEquals(method, cal.getAttributeString("method"));
        // f is not estimated: the metadata records the f that was used.
        assertEquals("channelImbalance_re", 1.0, cal.getAttributeDouble("channelImbalance_re"), 0.0);
        assertEquals("channelImbalance_im", 0.0, cal.getAttributeDouble("channelImbalance_im"), 0.0);

        // Cross-talk: with f fixed at 1 the solve finds (d1, d2/f, d3, d4/f), see the class comment.
        final double[] f = polar(F_AMP, F_PHASE);
        for (int i = 0; i < 4; i++) {
            double[] expected = polar(D_AMP, D_PHASE[i]);
            if (i == 1 || i == 3) {
                expected = cdiv(expected, f);
            }
            final double re = cal.getAttributeDouble("delta" + (i + 1) + "_re");
            final double im = cal.getAttributeDouble("delta" + (i + 1) + "_im");
            final double err = Math.hypot(re - expected[0], im - expected[1]);
            final double rel = err / Math.hypot(expected[0], expected[1]);
            assertTrue("delta" + (i + 1) + " relative error " + rel + " (est " + re + ", " + im + ")", rel < 0.15);
        }

        final double[][][] kIn = readScatteringVectors(src);
        final double[][][] kOut = readScatteringVectors(trg);
        assertTrue("input |corr(HH,HV)| should be large", Math.abs(corr(kIn, 0, 1)) > 0.1);
        assertTrue("|corr(HH,HV)| after", Math.abs(corr(kOut, 0, 1)) < 0.02);
        assertTrue("|corr(HH,VH)| after", Math.abs(corr(kOut, 0, 2)) < 0.02);
        assertTrue("|corr(VV,HV)| after", Math.abs(corr(kOut, 3, 1)) < 0.02);
        assertTrue("|corr(VV,VH)| after", Math.abs(corr(kOut, 3, 2)) < 0.02);

        // Co-pol imbalance left untouched: VV/HH stays at the input's |f|^4.
        final double inDb = 10 * Math.log10(power(kIn, 3) / power(kIn, 0));
        final double outDb = 10 * Math.log10(power(kOut, 3) / power(kOut, 0));
        assertEquals("VV/HH after vs before (dB)", inDb, outDb, 0.1);
        assertEquals("VV/HH after (dB)", 40 * Math.log10(F_AMP), outDb, 0.15);
        // Cross-pol power: |f|^2 * sigma_x, i.e. the cross-talk solve does not distort HV.
        final double hvDb = 10 * Math.log10(power(kOut, 1) / power(kOut, 0));
        assertEquals("HV/HH after (dB)", -15.0 + 20 * Math.log10(F_AMP), hvDb, 0.3);
    }

    private static void runWithImbalanceEstimate(final String method, final Integer numIterations) throws Exception {
        final Product src = createProduct(0.0, D_AMP, F_AMP, F_PHASE);
        final Product trg = runOp(src, method, numIterations, Boolean.TRUE);

        final MetadataElement cal = getCal(trg);
        assertEquals(method, cal.getAttributeString("method"));

        // Channel imbalance recovered (valid here because the clutter has sigma_HH = sigma_VV, real <HH VV*>).
        final double fRe = cal.getAttributeDouble("channelImbalance_re");
        final double fIm = cal.getAttributeDouble("channelImbalance_im");
        final double fErr = Math.hypot(fRe - F_AMP * Math.cos(F_PHASE), fIm - F_AMP * Math.sin(F_PHASE));
        assertTrue("channel imbalance error " + fErr + " (f = " + fRe + ", " + fIm + ")", fErr < 0.02);

        // Cross-talk, each term within 15 % of its true value (sample-statistics limited).
        for (int i = 0; i < 4; i++) {
            final double re = cal.getAttributeDouble("delta" + (i + 1) + "_re");
            final double im = cal.getAttributeDouble("delta" + (i + 1) + "_im");
            final double err = Math.hypot(re - D_AMP * Math.cos(D_PHASE[i]), im - D_AMP * Math.sin(D_PHASE[i]));
            assertTrue("delta" + (i + 1) + " relative error " + err / D_AMP + " (est " + re + ", " + im + ")",
                    err / D_AMP < 0.15);
        }

        // Corrected data: co/cross correlations removed, VV/HH power forced to the clutter's ~1 (0 dB).
        final double[][][] k = readScatteringVectors(trg);
        assertTrue("|corr(HH,HV)| after", Math.abs(corr(k, 0, 1)) < 0.02);
        assertTrue("|corr(HH,VH)| after", Math.abs(corr(k, 0, 2)) < 0.02);
        assertTrue("|corr(VV,HV)| after", Math.abs(corr(k, 3, 1)) < 0.02);
        assertTrue("|corr(VV,VH)| after", Math.abs(corr(k, 3, 2)) < 0.02);
        assertEquals("VV/HH power after", 1.0, power(k, 3) / power(k, 0), 0.03);
    }

    // ----- graph behaviour ----------------------------------------------------------------------------

    /**
     * GPF initializes every node of a graph before it executes any of them, and a downstream operator
     * (Multilook, Terrain-Correction, ...) copies or reads this product's metadata in its own initialize().
     * The polsarData flag and the estimated distortion must therefore be in the metadata by the time
     * getTargetProduct() returns, not only after doExecute().
     */
    @Test
    public void testMetadataCompleteBeforeDownstreamCopy() throws Exception {
        final Product src = createProduct(0.0, D_AMP, F_AMP, F_PHASE);
        final PolarimetricCalibrationOp op =
                (PolarimetricCalibrationOp) new PolarimetricCalibrationOp.Spi().createOperator();
        op.setSourceProduct(src);
        op.setParameter("subsample", 1);
        final Product trg = op.getTargetProduct();

        // what a downstream operator copies during graph initialisation
        final Product downstream = new Product("downstream", trg.getProductType(),
                trg.getSceneRasterWidth(), trg.getSceneRasterHeight());
        ProductUtils.copyMetadata(trg, downstream);

        // then GPF executes the graph
        op.execute(ProgressMonitor.NULL);

        final MetadataElement downstreamAbs = AbstractMetadata.getAbstractedMetadata(downstream);
        assertEquals("polsarData", 1, downstreamAbs.getAttributeInt(AbstractMetadata.polsarData, 0));
        final MetadataElement downstreamCal = downstreamAbs.getElement("Polarimetric_Calibration");
        assertNotNull("Polarimetric_Calibration missing from the downstream copy", downstreamCal);
        final MetadataElement cal = getCal(trg);
        for (int i = 1; i <= 4; i++) {
            for (String part : new String[]{"_re", "_im"}) {
                final String name = "delta" + i + part;
                assertEquals(name, cal.getAttributeDouble(name), downstreamCal.getAttributeDouble(name), 0.0);
            }
        }
        assertTrue("cross-talk was not estimated",
                Math.hypot(cal.getAttributeDouble("delta1_re"), cal.getAttributeDouble("delta1_im")) > 0.5 * D_AMP);
        downstream.dispose();
    }

    // ---------------------------------------------------------------------------------------------

    private static Product runOp(final Product src, final String method, final Integer numIterations,
                                 final Boolean estimateCoPolImbalance) {
        final PolarimetricCalibrationOp op =
                (PolarimetricCalibrationOp) new PolarimetricCalibrationOp.Spi().createOperator();
        op.setSourceProduct(src);
        op.setParameter("method", method);
        if (numIterations != null) {
            op.setParameter("numIterations", numIterations);
        }
        if (estimateCoPolImbalance != null) {
            op.setParameter("estimateCoPolImbalance", estimateCoPolImbalance);
        }
        op.setParameter("subsample", 1);
        final Product trg = op.getTargetProduct();
        op.execute(ProgressMonitor.NULL);
        return trg;
    }

    private static MetadataElement getCal(final Product trg) {
        final MetadataElement cal = AbstractMetadata.getAbstractedMetadata(trg).getElement("Polarimetric_Calibration");
        assertNotNull(cal);
        return cal;
    }

    /**
     * @param vvOverHhDb true VV/HH power ratio of the clutter (dB)
     * @param dAmp       cross-talk amplitude (0 = none), phases D_PHASE
     * @param fAmp       co-pol imbalance amplitude
     * @param fPhase     co-pol imbalance phase (rad)
     */
    private static Product createProduct(final double vvOverHhDb, final double dAmp,
                                         final double fAmp, final double fPhase) {
        final Random rnd = new Random(42);
        final double sx = Math.pow(10, -1.5);
        final double g = Math.pow(10, vvOverHhDb / 20.0);
        final double[][] dRe = new double[4][4], dIm = new double[4][4];
        buildDistortion(dAmp, fAmp, fPhase, dRe, dIm);

        final int n = W * H;
        final double[][] mRe = new double[4][n], mIm = new double[4][n];
        final double[] kr = new double[4], ki = new double[4];
        for (int p = 0; p < n; p++) {
            final double hhR = cn(rnd, 1), hhI = cn(rnd, 1);
            final double vvR = g * (0.5 * hhR + Math.sqrt(0.75) * cn(rnd, 1));
            final double vvI = g * (0.5 * hhI + Math.sqrt(0.75) * cn(rnd, 1));
            final double hvR = cn(rnd, sx), hvI = cn(rnd, sx);
            kr[0] = hhR; ki[0] = hhI;
            kr[1] = hvR; ki[1] = hvI;
            kr[2] = hvR; ki[2] = hvI;
            kr[3] = vvR; ki[3] = vvI;
            for (int i = 0; i < 4; i++) {
                double re = 0, im = 0;
                for (int j = 0; j < 4; j++) {
                    re += dRe[i][j] * kr[j] - dIm[i][j] * ki[j];
                    im += dRe[i][j] * ki[j] + dIm[i][j] * kr[j];
                }
                mRe[i][p] = re;
                mIm[i][p] = im;
            }
        }
        return SyntheticPolProducts.createFullProduct(W, H, mRe, mIm);
    }

    /** Real or imaginary part of a CN(0, power) sample. */
    private static double cn(final Random rnd, final double power) {
        return Math.sqrt(power / 2) * rnd.nextGaussian();
    }

    /** D = R (x) T acting on [HH, HV, VH, VV]: D[(2i+k)][(2j+l)] = R[i][j] T[k][l]. */
    private static void buildDistortion(final double dAmp, final double fAmp, final double fPhase,
                                        final double[][] dRe, final double[][] dIm) {
        final double[] d1 = polar(dAmp, D_PHASE[0]), d2 = polar(dAmp, D_PHASE[1]);
        final double[] d3 = polar(dAmp, D_PHASE[2]), d4 = polar(dAmp, D_PHASE[3]);
        final double[] f = polar(fAmp, fPhase), one = {1, 0};
        final double[][][] r = {{one, d4}, {d3, f}};
        final double[][][] t = {{one, d2}, {d1, f}};
        for (int i = 0; i < 2; i++)
            for (int j = 0; j < 2; j++)
                for (int k = 0; k < 2; k++)
                    for (int l = 0; l < 2; l++) {
                        dRe[2 * i + k][2 * j + l] = r[i][j][0] * t[k][l][0] - r[i][j][1] * t[k][l][1];
                        dIm[2 * i + k][2 * j + l] = r[i][j][0] * t[k][l][1] + r[i][j][1] * t[k][l][0];
                    }
    }

    private static double[] polar(final double amp, final double phase) {
        return new double[]{amp * Math.cos(phase), amp * Math.sin(phase)};
    }

    private static double[] cdiv(final double[] a, final double[] b) {
        final double m = b[0] * b[0] + b[1] * b[1];
        return new double[]{(a[0] * b[0] + a[1] * b[1]) / m, (a[1] * b[0] - a[0] * b[1]) / m};
    }

    private static double[][][] readScatteringVectors(final Product product) throws Exception {
        final String[] pols = {"HH", "HV", "VH", "VV"};
        final double[][][] k = new double[4][2][];
        for (int c = 0; c < 4; c++) {
            final float[] re = SyntheticPolProducts.readBand(product, "i_" + pols[c]);
            final float[] im = SyntheticPolProducts.readBand(product, "q_" + pols[c]);
            k[c][0] = new double[re.length];
            k[c][1] = new double[re.length];
            for (int p = 0; p < re.length; p++) {
                k[c][0][p] = re[p];
                k[c][1][p] = im[p];
            }
        }
        return k;
    }

    private static double power(final double[][][] k, final int c) {
        double s = 0;
        for (int p = 0; p < k[c][0].length; p++) {
            s += k[c][0][p] * k[c][0][p] + k[c][1][p] * k[c][1][p];
        }
        return s / k[c][0].length;
    }

    /** |&lt;a b*&gt;| / sqrt(&lt;|a|^2&gt; &lt;|b|^2&gt;). */
    private static double corr(final double[][][] k, final int a, final int b) {
        double re = 0, im = 0;
        final int n = k[a][0].length;
        for (int p = 0; p < n; p++) {
            re += k[a][0][p] * k[b][0][p] + k[a][1][p] * k[b][1][p];
            im += k[a][1][p] * k[b][0][p] - k[a][0][p] * k[b][1][p];
        }
        return Math.hypot(re / n, im / n) / Math.sqrt(power(k, a) * power(k, b));
    }
}
