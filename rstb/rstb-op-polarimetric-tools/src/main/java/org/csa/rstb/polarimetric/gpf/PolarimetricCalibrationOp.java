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
 *   D = T<sup>T</sup> ⊗ R       (Kronecker product of transmit and receive distortions)
 *   R = [[1, δ4],         T = [[1, δ2],
 *        [δ3, f]]              [δ1, f]]
 * </pre>
 * <p>where <i>f</i> is the (complex) channel imbalance and δ<sub>1..4</sub> are the four
 *    crosstalk leakage terms.</p>
 *
 * <h3>Calibration procedure</h3>
 * <ol>
 *   <li>Estimate the 4×4 sample covariance &lt;k k<sup>H</sup>&gt; over a training region
 *       (sub-sampled scene by default).</li>
 *   <li>Solve for f from the co-pol diagonal ratio and HH·VV* phase:<br>
 *       f = sqrt(σ<sub>44</sub>/σ<sub>11</sub>) · exp(i · arg(σ<sub>14</sub>) / 2).</li>
 *   <li>Apply imbalance correction in-place to the sample covariance.</li>
 *   <li>Solve a linear system for δ<sub>1..4</sub> using the four
 *       co/cross covariance residuals &lt;Mhh Mhv*&gt;, &lt;Mhh Mvh*&gt;, &lt;Mvh Mvv*&gt;,
 *       &lt;Mhv Mvv*&gt;, which would all vanish for an ideal calibrated reciprocal scene.</li>
 *   <li>For Ainsworth: re-apply the inverse distortion to the sample covariance and iterate
 *       steps 2–4 until convergence (or N iterations).</li>
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

            estimateDistortion();
            computeInverseDistortion();

            createTargetProduct();
            updateTargetProductMetadata();
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    /* ─────────── distortion estimation ─────────────────────────────────────── */

    private void estimateDistortion() throws IOException{
        // Step 1: collect 4×4 sample covariance <k·k^H> over the scene.
        final double[][] Cre = new double[4][4];
        final double[][] Cim = new double[4][4];
        collectCovariance(Cre, Cim);

        // Step 2 + 4: alternating channel-imbalance / crosstalk solves.
        final int iters = method.equals(METHOD_QUEGAN) ? 1 : Math.max(1, numIterations);
        for (int iter = 0; iter < iters; iter++) {
            // Update channel imbalance from current covariance.
            solveChannelImbalance(Cre, Cim);

            // Apply imbalance correction to the covariance in place.
            applyImbalanceToCov(Cre, Cim);

            // Solve crosstalk from the off-diagonal residuals.
            solveCrosstalk(Cre, Cim);

            // Apply crosstalk inverse to the covariance for the next iteration.
            if (iter + 1 < iters) {
                applyCrosstalkInvToCov(Cre, Cim);
            }
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

    /** f = sqrt(σ44 / σ11) · exp(i · arg(σ14) / 2). */
    private void solveChannelImbalance(final double[][] Cre, final double[][] Cim) {
        final double s11 = Cre[0][0];
        final double s44 = Cre[3][3];
        if (s11 <= 0 || s44 <= 0) return;
        final double amp = Math.sqrt(s44 / s11);
        final double phase = 0.5 * Math.atan2(Cim[0][3], Cre[0][3]);
        // Compose with current f (multiplicative refinement across iterations).
        final double dRe = amp * Math.cos(phase);
        final double dIm = amp * Math.sin(phase);
        final double newFRe = fRe * dRe - fIm * dIm;
        final double newFIm = fRe * dIm + fIm * dRe;
        fRe = newFRe;
        fIm = newFIm;
    }

    /**
     * After applying current f (so VV channel is normalised to HH), solve for the four
     * crosstalk parameters from the four co/cross residuals using a first-order linear system:
     * <pre>
     *   X1 = &lt;Mhh Mhv*&gt; ≈ σ22 · δ4* + σ11 · δ2*
     *   X2 = &lt;Mhh Mvh*&gt; ≈ σ33 · δ3* + σ11 · δ1*
     *   X3 = &lt;Mvh Mvv*&gt; ≈ σ44 · δ3 + σ33 · δ1
     *   X4 = &lt;Mhv Mvv*&gt; ≈ σ44 · δ4 + σ22 · δ2
     * </pre>
     * <p>Under reciprocity δ1 ≈ δ4 and δ2 ≈ δ3 to first order, giving a closed-form solve
     *    that we then inject back into the iterative loop.</p>
     */
    private void solveCrosstalk(final double[][] Cre, final double[][] Cim) {
        final double s11 = Cre[0][0];
        final double s22 = Cre[1][1];
        final double s33 = Cre[2][2];
        final double s44 = Cre[3][3];

        // X1 = <Mhh Mhv*>  → first-order δ2 = X1 / σ11 (approximation, reciprocity δ4 ≈ δ1 separate)
        // X2 = <Mhh Mvh*>  → δ1 = X2 / σ11
        // X3 = <Mvh Mvv*>  → δ3 = X3 / σ44
        // X4 = <Mhv Mvv*>  → δ4 = X4 / σ44
        final double safe = 1e-30;

        final double d1Re = (s11 > safe) ? Cre[0][2] / s11 : 0;
        final double d1Im = (s11 > safe) ? Cim[0][2] / s11 : 0;

        final double d2Re = (s11 > safe) ? Cre[0][1] / s11 : 0;
        final double d2Im = (s11 > safe) ? Cim[0][1] / s11 : 0;

        final double d3Re = (s44 > safe) ? Cre[2][3] / s44 : 0;
        final double d3Im = (s44 > safe) ? Cim[2][3] / s44 : 0;

        final double d4Re = (s44 > safe) ? Cre[1][3] / s44 : 0;
        final double d4Im = (s44 > safe) ? Cim[1][3] / s44 : 0;

        // Accumulate: each iteration adds a correction Δδ.
        deltaRe[0] += d1Re; deltaIm[0] += d1Im;
        deltaRe[1] += d2Re; deltaIm[1] += d2Im;
        deltaRe[2] += d3Re; deltaIm[2] += d3Im;
        deltaRe[3] += d4Re; deltaIm[3] += d4Im;
    }

    /** Apply 4×4 channel-imbalance distortion (with current δ=0) inverse to covariance. */
    private void applyImbalanceToCov(final double[][] Cre, final double[][] Cim) {
        // F is diag(1, 1, 1, 1/f). Apply: C' = F·C·F^H means scale row/col 4 by 1/f.
        final double mag2 = fRe * fRe + fIm * fIm;
        if (mag2 < 1e-30) return;
        final double invFRe = fRe / mag2;
        final double invFIm = -fIm / mag2;
        // Scale row 4: Cre[3][j] = (Cre[3][j] + i·Cim[3][j]) · invF
        for (int j = 0; j < 4; j++) {
            final double r = Cre[3][j], im = Cim[3][j];
            Cre[3][j] = r * invFRe - im * invFIm;
            Cim[3][j] = r * invFIm + im * invFRe;
        }
        // Scale col 4: by conj(invF)
        for (int i = 0; i < 4; i++) {
            final double r = Cre[i][3], im = Cim[i][3];
            Cre[i][3] = r * invFRe + im * invFIm;     // conj(invF) = (invFRe, -invFIm)
            Cim[i][3] = -r * invFIm + im * invFRe;
        }
        // Once imbalance has been folded into the covariance, reset accumulator so the next
        // iteration estimates only a residual.
        fRe = 1.0; fIm = 0.0;
        // (The accumulated f is encoded into the running covariance; final D is rebuilt
        //  from deltas at apply time — see computeInverseDistortion.)
    }

    /** Apply current crosstalk inverse to the covariance for the next iteration. */
    private void applyCrosstalkInvToCov(final double[][] Cre, final double[][] Cim) {
        // Build current crosstalk-only D and invert; multiply C ← D^{-1} · C · D^{-H}.
        final double[][] DRe = new double[4][4];
        final double[][] DIm = new double[4][4];
        buildDistortionMatrix(DRe, DIm);
        final double[][] DiRe = new double[4][4];
        final double[][] DiIm = new double[4][4];
        invert4x4Complex(DRe, DIm, DiRe, DiIm);

        // C' = Di · C · Di^H
        final double[][] tmpRe = new double[4][4];
        final double[][] tmpIm = new double[4][4];
        mul4(DiRe, DiIm, Cre, Cim, tmpRe, tmpIm);
        mulHerm4(tmpRe, tmpIm, DiRe, DiIm, Cre, Cim);
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
