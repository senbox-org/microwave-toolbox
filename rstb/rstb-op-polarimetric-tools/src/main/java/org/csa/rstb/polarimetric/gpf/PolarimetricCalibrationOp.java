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
package org.csa.rstb.polarimetric.gpf;

import com.bc.ceres.core.ProgressMonitor;
import org.csa.rstb.polarimetric.gpf.support.QuadPolProcessor;
import eu.esa.sar.commons.polsar.PolBandUtils;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;

import java.awt.*;
import java.io.IOException;
import java.util.Map;

/**
 * Polarimetric calibration: channel-imbalance + crosstalk correction for full-pol scattering data.
 *
 * <p>Two methods are exposed:</p>
 * <ul>
 *   <li><b>Quegan</b> &mdash; closed-form first-iteration distortion estimate based on
 *       distributed-target reciprocity (Quegan 1994).</li>
 *   <li><b>Ainsworth</b> &mdash; iterative refinement of the Quegan estimate
 *       (Ainsworth, Ferro-Famil &amp; Lee 2006). Default 4 iterations.</li>
 * </ul>
 *
 * <h3>Model</h3>
 * <p>The measured scattering vector M = [Mhh, Mhv, Mvh, Mvv]<sup>T</sup> is related to the true
 *    scattering vector S by a 4×4 complex distortion matrix D:</p>
 * <pre>
 *   M = D · S + n
 *   D = R ⊗ T           (Kronecker product, row (2i+k), col (2j+l) = R_ij·T_kl; i,j receive, k,l transmit)
 *   R = [[1, δ4],         T = [[1, δ2],
 *        [δ3, f]]              [δ1, f]]
 * </pre>
 * <p>where <i>f</i> is the (complex) one-way co-pol channel imbalance (VV/HH amplitude ratio |f|²; the
 *    same f on receive and transmit, so no cross-pol imbalance is modelled) and δ<sub>1..4</sub> are the
 *    four crosstalk leakage terms.</p>
 * <p>By default only the cross-talk is estimated and f is kept at 1, so the scene's VV/HH ratio is left
 *    untouched. Quegan (1994) and Ainsworth et al. (2006) both state that |f| cannot be estimated from
 *    distributed targets and needs a trihedral corner reflector. Because R = R'·diag(1, f) and
 *    T = T'·diag(1, f) exactly, the f = 1 cross-talk solve finds (δ1, δ2/f, δ3, δ4/f) and removes the
 *    cross-talk while leaving any co-pol imbalance in the data.</p>
 *
 * <h3>Calibration procedure</h3>
 * <ol>
 *   <li>Estimate the 4×4 sample covariance &lt;k k<sup>H</sup>&gt; over a training region
 *       (sub-sampled scene by default).</li>
 *   <li>Correct that (original) covariance with the current distortion estimate.</li>
 *   <li>Only when {@code estimateCoPolImbalance} is set: residual imbalance from the co-pol power ratio
 *       and HH·VV* phase, assuming σ<sub>hh</sub> = σ<sub>vv</sub> and arg&lt;Shh Svv*&gt; = 0 over the
 *       scene:<br>
 *       f = (σ<sub>44</sub>/σ<sub>11</sub>)<sup>1/4</sup> · exp(−i · arg(σ<sub>14</sub>) / 2).<br>
 *       This forces the corrected VV/HH ratio to 0 dB. Otherwise the residual f is 1.</li>
 *   <li>Residual cross-talk from two 2×2 linear systems (Quegan 1994 form) that use
 *       &lt;Mhv Mhh*&gt;, &lt;Mhv Mvv*&gt;, &lt;Mvh Mhh*&gt;, &lt;Mvh Mvv*&gt;, which vanish for a
 *       calibrated reflection-symmetric scene.</li>
 *   <li>Fold the residual into the estimate. Quegan = one pass; Ainsworth = N passes (fixed-point
 *       iteration on the original covariance).</li>
 *   <li>Apply the inverse 4×4 distortion to each pixel's scattering vector and write the
 *       corrected 8-band product.</li>
 * </ol>
 *
 * <h3>Validation note</h3>
 * <p><b>This implementation is best-effort. Sign conventions and basis transformations differ
 *    between published implementations of these algorithms.</b> Users running quantitative
 *    polarimetric analyses should cross-check the resulting calibration parameters against
 *    PolSARpro or the original published reference data (e.g. AIRSAR distortion calibrations
 *    for the San Francisco scene) before adopting these results in production.</p>
 *
 * <h3>References</h3>
 * <p>[1] Quegan, S., 1994. <i>A unified algorithm for phase and crosstalk calibration of
 *    polarimetric data—theory and observations.</i> IEEE TGRS 32(1), 89–99.</p>
 * <p>[2] Ainsworth, T.L., Ferro-Famil, L., Lee, J.S., 2006. <i>Orientation angle preserving
 *    a posteriori polarimetric SAR calibration.</i> IEEE TGRS 44(4), 994–1003.</p>
 */
@OperatorMetadata(alias = "Polarimetric-Calibration",
        category = "Radar/Polarimetric",
        authors = "SkyWatch",
        version = "1.0",
        copyright = "Copyright (C) 2026 by SkyWatch Space Applications Inc.",
        description = "Quegan / Ainsworth polarimetric channel imbalance and crosstalk correction (experimental — validate before production use)")
public final class PolarimetricCalibrationOp extends Operator implements QuadPolProcessor {

    @SourceProduct(alias = "source")
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    public static final String METHOD_QUEGAN = "Quegan";
    public static final String METHOD_AINSWORTH = "Ainsworth";

    @Parameter(valueSet = {METHOD_QUEGAN, METHOD_AINSWORTH}, defaultValue = METHOD_AINSWORTH,
            label = "Calibration method")
    private String method = METHOD_AINSWORTH;

    @Parameter(description = "Number of iterations for the Ainsworth refinement",
            interval = "[1, 50]", defaultValue = "4", label = "Ainsworth Iterations")
    private int numIterations = 4;

    @Parameter(description = "Sub-sampling factor for statistics collection. 1 = use every pixel " +
            "(slow on large scenes); 10 = use 1 pixel in 100 (fast, sufficient for distributed targets).",
            interval = "[1, 100]", defaultValue = "10", label = "Stats Sub-sampling")
    private int subsample = 10;

    @Parameter(description = "Estimate the co-pol channel imbalance f (VV/HH) from the scene and remove it. " +
            "This assumes <|HH|²> = <|VV|²> and a zero HH-VV phase over the scene, and therefore forces the " +
            "corrected VV/HH ratio to 0 dB. It is normally only valid with a trihedral-based estimate " +
            "(corner reflector); distributed targets cannot determine |f|. When off (default), f = 1 and only " +
            "the cross-talk is corrected.",
            defaultValue = "false", label = "Estimate co-pol channel imbalance from the scene")
    private boolean estimateCoPolImbalance = false;

    @Parameter(description = "Output the estimated distortion parameters to the metadata",
            defaultValue = "true", label = "Save Distortion to Metadata")
    private boolean saveDistortion = true;

    private PolBandUtils.MATRIX sourceProductType;
    private PolBandUtils.PolSourceBand[] srcBandList;

    /** Channel imbalance: complex f = α · exp(iφ). */
    private double fRe = 1.0, fIm = 0.0;
    /** Crosstalk leakage parameters δ1..δ4 as complex numbers. */
    private final double[] deltaRe = new double[4];
    private final double[] deltaIm = new double[4];

    /** Inverse 4×4 distortion matrix (real and imaginary parts), pre-computed for application. */
    private double[][] DinvRe;
    private double[][] DinvIm;

    @Override
    public void initialize() throws OperatorException {
        try {
            new InputProductValidator(sourceProduct).checkIfSARProduct();

            sourceProductType = PolBandUtils.getSourceProductType(sourceProduct);
            if (sourceProductType != PolBandUtils.MATRIX.FULL) {
                throw new OperatorException("Full quad-pol scattering matrix product (8 bands) is required.");
            }
            srcBandList = PolBandUtils.getSourceBands(sourceProduct, sourceProductType);

            createTargetProduct();
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    @Override
    public void doExecute(final ProgressMonitor pm) throws OperatorException {
        try {
            pm.beginTask("Estimating polarimetric distortion", 1);
            estimateDistortion();
            computeInverseDistortion();
            updateTargetProductMetadata();
            pm.worked(1);
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        } finally {
            pm.done();
        }
    }

    /* ─────────── distortion estimation ─────────────────────────────────────── */

    private void estimateDistortion() throws IOException{
        // Step 1: collect 4×4 sample covariance <k·k^H> over the scene.
        final double[][] C0re = new double[4][4];
        final double[][] C0im = new double[4][4];
        collectCovariance(C0re, C0im);

        fRe = 1.0; fIm = 0.0;
        for (int i = 0; i < 4; i++) {
            deltaRe[i] = 0.0; deltaIm[i] = 0.0;
        }

        // Steps 2-4: each iteration corrects the ORIGINAL covariance with the accumulated distortion
        // estimate, estimates the residual imbalance and cross-talk from it and folds the residual into the
        // accumulated estimate. The fixed point is the distortion whose inverse leaves no co/cross residual.
        final int iters = method.equals(METHOD_QUEGAN) ? 1 : Math.max(1, numIterations);
        final double[][] Cre = new double[4][4];
        final double[][] Cim = new double[4][4];
        final double[] resFRe = new double[1], resFIm = new double[1];
        final double[] resDRe = new double[4], resDIm = new double[4];
        for (int iter = 0; iter < iters; iter++) {
            applyInverseDistortionToCov(C0re, C0im, Cre, Cim);

            // Residual channel imbalance (opt-in only), then remove it from the working covariance.
            // Default: f_r = 1, so f stays 1 and the co-pol imbalance is left in the data.
            if (estimateCoPolImbalance) {
                solveChannelImbalance(Cre, Cim, resFRe, resFIm);
                applyImbalanceToCov(Cre, Cim, resFRe[0], resFIm[0]);
            } else {
                resFRe[0] = 1.0;
                resFIm[0] = 0.0;
            }

            // Residual cross-talk from the co/cross residuals.
            solveCrosstalk(Cre, Cim, resDRe, resDIm);

            composeResidual(resFRe[0], resFIm[0], resDRe, resDIm);
        }
    }

    private void collectCovariance(final double[][] Cre, final double[][] Cim) throws IOException {
        // Use the first PolSourceBand group (single-stack typical case).
        final Band[] bands = srcBandList[0].srcBands;
        if (bands.length < 8) {
            throw new OperatorException("FULL polarimetric product requires 8 bands (i/q × HH/HV/VH/VV).");
        }
        final int W = sourceProduct.getSceneRasterWidth();
        final int H = sourceProduct.getSceneRasterHeight();
        final int step = Math.max(1, subsample);

        long count = 0;
        // Read row-wise from each band raster (uses SNAP's band cache, OK for sub-sampled stats).
        final float[] line = new float[W];
        final float[][] lines = new float[8][W];
        for (int y = 0; y < H; y += step) {
            for (int b = 0; b < 8; b++) {
                bands[b].readPixels(0, y, W, 1, lines[b], ProgressMonitor.NULL);
            }
            for (int x = 0; x < W; x += step) {
                final double[] kr = {lines[0][x], lines[2][x], lines[4][x], lines[6][x]};
                final double[] ki = {lines[1][x], lines[3][x], lines[5][x], lines[7][x]};
                accumulateOuter(kr, ki, Cre, Cim);
                count++;
            }
        }
        if (count == 0) {
            throw new OperatorException("No samples collected for covariance estimation.");
        }
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                Cre[i][j] /= count;
                Cim[i][j] /= count;
            }
        }
    }

    private static void accumulateOuter(final double[] kr, final double[] ki,
                                        final double[][] Cre, final double[][] Cim) {
        // C_ij += k_i · conj(k_j)
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                Cre[i][j] += kr[i] * kr[j] + ki[i] * ki[j];
                Cim[i][j] += ki[i] * kr[j] - kr[i] * ki[j];
            }
        }
    }

    /**
     * Residual co-pol imbalance, used only when {@code estimateCoPolImbalance} is set. In the model VV is
     * scaled by f² (f on receive and on transmit), so &lt;|Mvv|²&gt; = |f|⁴ σvv and &lt;Mhh Mvv*&gt; =
     * conj(f)² ρ. Assuming σvv = σhh and arg(ρ) = 0 over the scene: |f| = (σ44/σ11)^(1/4) and
     * arg(f) = −arg(σ14)/2. This forces the corrected VV/HH ratio to 0 dB.
     */
    private static void solveChannelImbalance(final double[][] Cre, final double[][] Cim,
                                              final double[] outRe, final double[] outIm) {
        final double s11 = Cre[0][0];
        final double s44 = Cre[3][3];
        if (s11 <= 0 || s44 <= 0) {
            outRe[0] = 1.0; outIm[0] = 0.0;
            return;
        }
        final double amp = Math.pow(s44 / s11, 0.25);
        final double phase = -0.5 * Math.atan2(Cim[0][3], Cre[0][3]);
        outRe[0] = amp * Math.cos(phase);
        outIm[0] = amp * Math.sin(phase);
    }

    /**
     * Residual cross-talk on an imbalance-corrected covariance (residual f = 1, either estimated and
     * removed or, by default, assumed). To first order in δ, with a
     * reflection-symmetric scene (&lt;Shh Shv*&gt; = &lt;Svv Shv*&gt; = 0) and D = R ⊗ T as built in
     * {@link #buildDistortionMatrix}:
     * <pre>
     *   Mhv ≈ Shv + δ1·Shh + δ4·Svv        Mvh ≈ Svh + δ3·Shh + δ2·Svv
     * </pre>
     * Correlating each cross-pol channel with Mhh and Mvv gives two 2×2 linear systems
     * (C_ij = &lt;M_i M_j*&gt;, channel order hh, hv, vh, vv = 1..4, Δ = C11·C44 − |C14|²):
     * <pre>
     *   δ1 = (C21·C44 − C41·C24) / Δ      δ4 = (C11·C24 − C14·C21) / Δ
     *   δ3 = (C31·C44 − C41·C34) / Δ      δ2 = (C11·C34 − C14·C31) / Δ
     * </pre>
     * This is the same form as the cross-talk solution of Quegan (1994).
     */
    private static void solveCrosstalk(final double[][] Cre, final double[][] Cim,
                                       final double[] outRe, final double[] outIm) {
        final double[] c11 = {Cre[0][0], Cim[0][0]};
        final double[] c44 = {Cre[3][3], Cim[3][3]};
        final double[] c14 = {Cre[0][3], Cim[0][3]};
        final double[] c41 = {Cre[3][0], Cim[3][0]};
        final double[] det = csub(cmul(c11, c44), cmul(c14, c41));
        if (det[0] * det[0] + det[1] * det[1] < 1e-60) {
            java.util.Arrays.fill(outRe, 0.0);
            java.util.Arrays.fill(outIm, 0.0);
            return;
        }
        final double[] c21 = {Cre[1][0], Cim[1][0]}, c24 = {Cre[1][3], Cim[1][3]};
        final double[] c31 = {Cre[2][0], Cim[2][0]}, c34 = {Cre[2][3], Cim[2][3]};

        final double[] d1 = cdiv(csub(cmul(c21, c44), cmul(c41, c24)), det);
        final double[] d4 = cdiv(csub(cmul(c11, c24), cmul(c14, c21)), det);
        final double[] d3 = cdiv(csub(cmul(c31, c44), cmul(c41, c34)), det);
        final double[] d2 = cdiv(csub(cmul(c11, c34), cmul(c14, c31)), det);

        outRe[0] = d1[0]; outIm[0] = d1[1];
        outRe[1] = d2[0]; outIm[1] = d2[1];
        outRe[2] = d3[0]; outIm[2] = d3[1];
        outRe[3] = d4[0]; outIm[3] = d4[1];
    }

    /**
     * Remove a co-pol imbalance f from the covariance: the distortion diag(1, f) ⊗ diag(1, f) scales
     * [hh, hv, vh, vv] by [1, f, f, f²], so C' = G·C·G^H with G = diag(1, 1/f, 1/f, 1/f²).
     */
    private static void applyImbalanceToCov(final double[][] Cre, final double[][] Cim,
                                            final double fr, final double fi) {
        final double mag2 = fr * fr + fi * fi;
        if (mag2 < 1e-30) return;
        final double[] invF = {fr / mag2, -fi / mag2};
        final double[][] g = {{1, 0}, invF, invF, cmul(invF, invF)};
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                // C'_ij = g_i · C_ij · conj(g_j)
                final double[] c = cmul(cmul(g[i], new double[]{Cre[i][j], Cim[i][j]}),
                        new double[]{g[j][0], -g[j][1]});
                Cre[i][j] = c[0];
                Cim[i][j] = c[1];
            }
        }
    }

    /**
     * Fold a residual (f_r, δ_r) into the accumulated estimate (f, δ). The total distortion is
     * D(f, δ) · D(f_r, 0) · D(1, δ_r); to first order in δ its receive matrix is
     * [[1, δ4·f_r + δ4_r], [δ3 + f·f_r·δ3_r, f·f_r]] and its transmit matrix is the same with δ2, δ1.
     */
    private void composeResidual(final double resFRe, final double resFIm,
                                 final double[] resDRe, final double[] resDIm) {
        final double[] f = {fRe, fIm};
        final double[] fr = {resFRe, resFIm};
        final double[] ffr = cmul(f, fr);
        // δ1 (index 0) and δ3 (index 2): δ + f·f_r·δ_r
        for (int i : new int[]{0, 2}) {
            final double[] t = cmul(ffr, new double[]{resDRe[i], resDIm[i]});
            deltaRe[i] += t[0];
            deltaIm[i] += t[1];
        }
        // δ2 (index 1) and δ4 (index 3): δ·f_r + δ_r
        for (int i : new int[]{1, 3}) {
            final double[] t = cmul(new double[]{deltaRe[i], deltaIm[i]}, fr);
            deltaRe[i] = t[0] + resDRe[i];
            deltaIm[i] = t[1] + resDIm[i];
        }
        fRe = ffr[0];
        fIm = ffr[1];
    }

    /** C = D⁻¹ · C0 · D⁻ᴴ with D built from the accumulated (f, δ). */
    private void applyInverseDistortionToCov(final double[][] C0re, final double[][] C0im,
                                             final double[][] Cre, final double[][] Cim) {
        final double[][] DRe = new double[4][4];
        final double[][] DIm = new double[4][4];
        buildDistortionMatrix(DRe, DIm);
        final double[][] DiRe = new double[4][4];
        final double[][] DiIm = new double[4][4];
        invert4x4Complex(DRe, DIm, DiRe, DiIm);

        final double[][] tmpRe = new double[4][4];
        final double[][] tmpIm = new double[4][4];
        mul4(DiRe, DiIm, C0re, C0im, tmpRe, tmpIm);
        mulHerm4(tmpRe, tmpIm, DiRe, DiIm, Cre, Cim);
    }

    private static double[] cmul(final double[] a, final double[] b) {
        return new double[]{a[0] * b[0] - a[1] * b[1], a[0] * b[1] + a[1] * b[0]};
    }

    private static double[] csub(final double[] a, final double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1]};
    }

    private static double[] cdiv(final double[] a, final double[] b) {
        final double m = b[0] * b[0] + b[1] * b[1];
        return new double[]{(a[0] * b[0] + a[1] * b[1]) / m, (a[1] * b[0] - a[0] * b[1]) / m};
    }

    /** Construct the current 4×4 distortion matrix D from f and δ1..δ4. */
    private void buildDistortionMatrix(final double[][] DRe, final double[][] DIm) {
        // Receive: R = [[1, δ4], [δ3, f]]
        // Transmit: T = [[1, δ2], [δ1, f]]
        // D = R ⊗ T  (acting on k = [Shh, Shv, Svh, Svv]^T)
        final double[][] R = new double[][]{
                {1, deltaRe[3]}, {deltaRe[2], fRe}};
        final double[][] Ri = new double[][]{
                {0, deltaIm[3]}, {deltaIm[2], fIm}};
        final double[][] T = new double[][]{
                {1, deltaRe[1]}, {deltaRe[0], fRe}};
        final double[][] Ti = new double[][]{
                {0, deltaIm[1]}, {deltaIm[0], fIm}};
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                for (int k = 0; k < 2; k++) {
                    for (int l = 0; l < 2; l++) {
                        final int row = i * 2 + k;
                        final int col = j * 2 + l;
                        // (R ⊗ T)_{(ik),(jl)} = R_{ij} · T_{kl}
                        DRe[row][col] = R[i][j] * T[k][l] - Ri[i][j] * Ti[k][l];
                        DIm[row][col] = R[i][j] * Ti[k][l] + Ri[i][j] * T[k][l];
                    }
                }
            }
        }
    }

    private void computeInverseDistortion() {
        final double[][] DRe = new double[4][4];
        final double[][] DIm = new double[4][4];
        buildDistortionMatrix(DRe, DIm);
        DinvRe = new double[4][4];
        DinvIm = new double[4][4];
        invert4x4Complex(DRe, DIm, DinvRe, DinvIm);
    }

    /* ─────────── per-pixel application ─────────────────────────────────────── */

    @Override
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle targetRectangle, ProgressMonitor pm)
            throws OperatorException {
        final int x0 = targetRectangle.x;
        final int y0 = targetRectangle.y;
        final int maxX = x0 + targetRectangle.width;
        final int maxY = y0 + targetRectangle.height;

        try {
            for (final PolBandUtils.PolSourceBand bandList : srcBandList) {
                final Tile[] srcTiles = new Tile[bandList.srcBands.length];
                final ProductData[] srcBufs = new ProductData[bandList.srcBands.length];
                for (int i = 0; i < bandList.srcBands.length; i++) {
                    srcTiles[i] = getSourceTile(bandList.srcBands[i], targetRectangle);
                    srcBufs[i] = srcTiles[i].getDataBuffer();
                }

                final Tile[] tgtTiles = new Tile[bandList.targetBands.length];
                final ProductData[] tgtBufs = new ProductData[bandList.targetBands.length];
                for (int i = 0; i < bandList.targetBands.length; i++) {
                    tgtTiles[i] = targetTiles.get(bandList.targetBands[i]);
                    tgtBufs[i] = tgtTiles[i].getDataBuffer();
                }

                final TileIndex idx = new TileIndex(srcTiles[0]);
                final double[] kr = new double[4];
                final double[] ki = new double[4];
                final double[] outR = new double[4];
                final double[] outI = new double[4];

                for (int y = y0; y < maxY; ++y) {
                    idx.calculateStride(y);
                    for (int x = x0; x < maxX; ++x) {
                        final int p = idx.getIndex(x);
                        kr[0] = srcBufs[0].getElemDoubleAt(p); ki[0] = srcBufs[1].getElemDoubleAt(p);
                        kr[1] = srcBufs[2].getElemDoubleAt(p); ki[1] = srcBufs[3].getElemDoubleAt(p);
                        kr[2] = srcBufs[4].getElemDoubleAt(p); ki[2] = srcBufs[5].getElemDoubleAt(p);
                        kr[3] = srcBufs[6].getElemDoubleAt(p); ki[3] = srcBufs[7].getElemDoubleAt(p);

                        // S = D^{-1} · M
                        for (int i = 0; i < 4; i++) {
                            double rs = 0, is = 0;
                            for (int j = 0; j < 4; j++) {
                                rs += DinvRe[i][j] * kr[j] - DinvIm[i][j] * ki[j];
                                is += DinvRe[i][j] * ki[j] + DinvIm[i][j] * kr[j];
                            }
                            outR[i] = rs; outI[i] = is;
                        }

                        tgtBufs[0].setElemFloatAt(p, (float) outR[0]); tgtBufs[1].setElemFloatAt(p, (float) outI[0]);
                        tgtBufs[2].setElemFloatAt(p, (float) outR[1]); tgtBufs[3].setElemFloatAt(p, (float) outI[1]);
                        tgtBufs[4].setElemFloatAt(p, (float) outR[2]); tgtBufs[5].setElemFloatAt(p, (float) outI[2]);
                        tgtBufs[6].setElemFloatAt(p, (float) outR[3]); tgtBufs[7].setElemFloatAt(p, (float) outI[3]);
                    }
                }
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    /* ─────────── output product ─────────────────────────────────────── */

    private void createTargetProduct() {
        targetProduct = new Product(sourceProduct.getName() + "_PolCal",
                sourceProduct.getProductType(),
                sourceProduct.getSceneRasterWidth(),
                sourceProduct.getSceneRasterHeight());
        ProductUtils.copyProductNodes(sourceProduct, targetProduct);
        for (final PolBandUtils.PolSourceBand bandList : srcBandList) {
            final Band[] tgts = new Band[bandList.srcBands.length];
            for (int i = 0; i < bandList.srcBands.length; i++) {
                final Band src = bandList.srcBands[i];
                tgts[i] = ProductUtils.copyBand(src.getName(), sourceProduct, targetProduct, false);
                tgts[i].setUnit(src.getUnit());
                tgts[i].setNoDataValue(src.getNoDataValue());
                tgts[i].setNoDataValueUsed(src.isNoDataValueUsed());
            }
            bandList.addTargetBands(tgts);
        }
    }

    private void updateTargetProductMetadata() {
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(targetProduct);
        if (absRoot != null) {
            absRoot.setAttributeInt(AbstractMetadata.polsarData, 1);
            if (saveDistortion) {
                final MetadataElement cal = new MetadataElement("Polarimetric_Calibration");
                cal.setAttributeString("method", method);
                cal.setAttributeDouble("channelImbalance_re", fRe);
                cal.setAttributeDouble("channelImbalance_im", fIm);
                for (int i = 0; i < 4; i++) {
                    cal.setAttributeDouble("delta" + (i + 1) + "_re", deltaRe[i]);
                    cal.setAttributeDouble("delta" + (i + 1) + "_im", deltaIm[i]);
                }
                absRoot.addElement(cal);
            }
        }
    }

    /* ─────────── 4×4 complex linear algebra ─────────────────────────────────── */

    /** C = A · B for 4×4 complex matrices. */
    private static void mul4(final double[][] aR, final double[][] aI,
                             final double[][] bR, final double[][] bI,
                             final double[][] cR, final double[][] cI) {
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                double r = 0, im = 0;
                for (int k = 0; k < 4; k++) {
                    r += aR[i][k] * bR[k][j] - aI[i][k] * bI[k][j];
                    im += aR[i][k] * bI[k][j] + aI[i][k] * bR[k][j];
                }
                cR[i][j] = r; cI[i][j] = im;
            }
        }
    }

    /** C = A · B^H for 4×4 complex matrices. */
    private static void mulHerm4(final double[][] aR, final double[][] aI,
                                  final double[][] bR, final double[][] bI,
                                  final double[][] cR, final double[][] cI) {
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                double r = 0, im = 0;
                for (int k = 0; k < 4; k++) {
                    // B^H_{kj} = conj(B_{jk})  →  use (bR[j][k], -bI[j][k])
                    r += aR[i][k] * bR[j][k] + aI[i][k] * bI[j][k];
                    im += aI[i][k] * bR[j][k] - aR[i][k] * bI[j][k];
                }
                cR[i][j] = r; cI[i][j] = im;
            }
        }
    }

    /**
     * Invert a 4×4 complex matrix via Gauss–Jordan elimination with partial pivoting.
     * Robust enough for distortion matrices, which are well-conditioned for δ ≪ 1.
     */
    private static void invert4x4Complex(final double[][] aR, final double[][] aI,
                                          final double[][] outR, final double[][] outI) {
        final int n = 4;
        final double[][] mR = new double[n][2 * n];
        final double[][] mI = new double[n][2 * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                mR[i][j] = aR[i][j]; mI[i][j] = aI[i][j];
            }
            mR[i][n + i] = 1; mI[i][n + i] = 0;
        }
        for (int col = 0; col < n; col++) {
            // Pivot on largest |a_{i,col}|
            int pivot = col;
            double maxMag = mR[col][col] * mR[col][col] + mI[col][col] * mI[col][col];
            for (int r = col + 1; r < n; r++) {
                final double mag = mR[r][col] * mR[r][col] + mI[r][col] * mI[r][col];
                if (mag > maxMag) { maxMag = mag; pivot = r; }
            }
            if (pivot != col) {
                final double[] swR = mR[col]; mR[col] = mR[pivot]; mR[pivot] = swR;
                final double[] swI = mI[col]; mI[col] = mI[pivot]; mI[pivot] = swI;
            }
            // Normalise pivot row
            final double pR = mR[col][col], pI = mI[col][col];
            final double pMag2 = pR * pR + pI * pI;
            if (pMag2 < 1e-30) {
                throw new OperatorException("Distortion matrix is singular; calibration cannot proceed.");
            }
            final double invR = pR / pMag2, invI = -pI / pMag2;
            for (int j = 0; j < 2 * n; j++) {
                final double r = mR[col][j], im = mI[col][j];
                mR[col][j] = r * invR - im * invI;
                mI[col][j] = r * invI + im * invR;
            }
            // Eliminate other rows
            for (int r = 0; r < n; r++) {
                if (r == col) continue;
                final double fR = mR[r][col], fI = mI[r][col];
                for (int j = 0; j < 2 * n; j++) {
                    mR[r][j] -= fR * mR[col][j] - fI * mI[col][j];
                    mI[r][j] -= fR * mI[col][j] + fI * mR[col][j];
                }
            }
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                outR[i][j] = mR[i][n + j];
                outI[i][j] = mI[i][n + j];
            }
        }
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(PolarimetricCalibrationOp.class);
        }
    }
}
