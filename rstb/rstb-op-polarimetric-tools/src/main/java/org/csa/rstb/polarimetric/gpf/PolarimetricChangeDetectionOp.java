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
import org.csa.rstb.polarimetric.gpf.support.DualPolProcessor;
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
import java.util.Map;

/**
 * Polarimetric Coherent Change Detection (P-CCD) via Generalized Likelihood Ratio Test
 * for two complex Wishart-distributed covariance/coherency matrices.
 *
 * <p>For two p×p Hermitian matrices C1, C2 each estimated from N independent looks under
 * the null hypothesis (no change), the test statistic is:</p>
 * <pre>
 *   ln Q = N · ln |C1| + N · ln |C2| − 2N · ln |(C1 + C2) / 2|
 *   T    = −2 · ln Q
 * </pre>
 * <p>Under H0, T is asymptotically χ² distributed with p² degrees of freedom (4 for C2,
 * 9 for T3/C3). The change probability is then:</p>
 * <pre>
 *   p_change = F_{χ²_{p²}}(T)
 * </pre>
 *
 * <p>Output bands: test statistic T, change probability p_change, and an optional binary
 * change mask thresholded at user-defined significance α.</p>
 *
 * <p>Reference:</p>
 * <p>Conradsen, K., Nielsen, A.A., Schou, J., Skriver, H., 2003.
 *    A test statistic in the complex Wishart distribution and its application to
 *    change detection in polarimetric SAR data.
 *    IEEE Transactions on Geoscience and Remote Sensing, 41(1), 4–19.</p>
 */
@OperatorMetadata(alias = "Polarimetric-Change-Detection",
        category = "Radar/Polarimetric",
        authors = "SkyWatch",
        version = "1.0",
        copyright = "Copyright (C) 2026 by SkyWatch Space Applications Inc.",
        description = "Polarimetric coherent change detection via GLRT (Conradsen et al. 2003)")
public final class PolarimetricChangeDetectionOp extends Operator implements DualPolProcessor, QuadPolProcessor {

    @SourceProduct(alias = "reference", description = "Reference (date 1) polarimetric covariance/coherency product")
    private Product referenceProduct;

    @SourceProduct(alias = "secondary", description = "Secondary (date 2) polarimetric covariance/coherency product, coregistered to reference")
    private Product secondaryProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(description = "The sliding window size for averaging the input matrices",
            valueSet = {"3", "5", "7", "9", "11", "13", "15"}, defaultValue = "5", label = "Window Size")
    private int windowSize = 5;

    @Parameter(description = "Number of independent looks per pixel of the input matrices (ENL). " +
            "Set to the equivalent number of looks of the input product (~1 for SLC, ~5–10 for multilooked GRD).",
            interval = "[1, 10000]", defaultValue = "5", label = "Number of Looks (ENL)")
    private double numLooks = 5;

    @Parameter(description = "Significance level α for the binary change mask. The mask is 1 where p_change ≥ 1−α.",
            interval = "[0.0, 1.0]", defaultValue = "0.0001", label = "Significance Level α")
    private double alpha = 0.0001;

    @Parameter(description = "Output the test statistic T = −2·ln Q",
            defaultValue = "true", label = "Output Test Statistic")
    private boolean outputTestStat = true;

    @Parameter(description = "Output change probability F_{χ²}(T)",
            defaultValue = "true", label = "Output Change Probability")
    private boolean outputChangeProb = true;

    @Parameter(description = "Output binary change mask",
            defaultValue = "true", label = "Output Change Mask")
    private boolean outputChangeMask = true;

    private int halfWindowSize;
    private int sourceImageWidth;
    private int sourceImageHeight;
    private PolBandUtils.MATRIX matrix;
    private int p;                     // matrix dimension (2 or 3)
    private double thresholdT;         // χ² critical value for the chosen α
    private PolBandUtils.PolSourceBand[] refBandList;
    private PolBandUtils.PolSourceBand[] secBandList;

    private static final String TEST_STAT = "TestStatistic";
    private static final String CHANGE_PROB = "ChangeProbability";
    private static final String CHANGE_MASK = "ChangeMask";

    @Override
    public void initialize() throws OperatorException {
        try {
            new InputProductValidator(referenceProduct).checkIfSARProduct();
            new InputProductValidator(secondaryProduct).checkIfSARProduct();

            final PolBandUtils.MATRIX refType = PolBandUtils.getSourceProductType(referenceProduct);
            final PolBandUtils.MATRIX secType = PolBandUtils.getSourceProductType(secondaryProduct);
            if (refType != secType) {
                throw new OperatorException("Reference and secondary products must have matching matrix type. " +
                        "Got " + refType + " vs " + secType + ".");
            }
            matrix = refType;

            switch (matrix) {
                case C2: p = 2; break;
                case C3:
                case T3:
                case FULL: p = 3; break;
                default:
                    throw new OperatorException("Polarimetric C2, C3 or T3 product is expected (got " + matrix + ").");
            }

            if (referenceProduct.getSceneRasterWidth() != secondaryProduct.getSceneRasterWidth()
                    || referenceProduct.getSceneRasterHeight() != secondaryProduct.getSceneRasterHeight()) {
                throw new OperatorException("Reference and secondary products must have matching dimensions. " +
                        "Coregister first.");
            }

            if (!outputTestStat && !outputChangeProb && !outputChangeMask) {
                throw new OperatorException("At least one output band must be selected.");
            }

            refBandList = PolBandUtils.getSourceBands(referenceProduct, matrix);
            secBandList = PolBandUtils.getSourceBands(secondaryProduct, matrix);

            sourceImageWidth = referenceProduct.getSceneRasterWidth();
            sourceImageHeight = referenceProduct.getSceneRasterHeight();
            halfWindowSize = windowSize / 2;

            // χ² critical value for α: solve P(X ≥ thresholdT) = α
            // i.e. F(thresholdT) = 1 − α. Using Wilson–Hilferty inversion below.
            thresholdT = chiSquareInverseCdf(1.0 - alpha, p * p);

            createTargetProduct();
            updateTargetProductMetadata();
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private void createTargetProduct() {
        targetProduct = new Product(referenceProduct.getName() + "_PCCD",
                referenceProduct.getProductType(),
                sourceImageWidth, sourceImageHeight);
        ProductUtils.copyProductNodes(referenceProduct, targetProduct);

        if (outputTestStat) targetProduct.addBand(TEST_STAT, ProductData.TYPE_FLOAT32);
        if (outputChangeProb) targetProduct.addBand(CHANGE_PROB, ProductData.TYPE_FLOAT32);
        if (outputChangeMask) targetProduct.addBand(CHANGE_MASK, ProductData.TYPE_INT8);
    }

    private void updateTargetProductMetadata() {
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(targetProduct);
        if (absRoot != null) {
            absRoot.setAttributeInt(AbstractMetadata.polsarData, 1);
        }
    }

    @Override
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle targetRectangle, ProgressMonitor pm)
            throws OperatorException {

        final int x0 = targetRectangle.x;
        final int y0 = targetRectangle.y;
        final int maxY = y0 + targetRectangle.height;
        final int maxX = x0 + targetRectangle.width;
        final Rectangle sourceRectangle = getSourceRectangle(x0, y0, targetRectangle.width, targetRectangle.height);

        final Band testBand = outputTestStat ? targetProduct.getBand(TEST_STAT) : null;
        final Band probBand = outputChangeProb ? targetProduct.getBand(CHANGE_PROB) : null;
        final Band maskBand = outputChangeMask ? targetProduct.getBand(CHANGE_MASK) : null;

        final ProductData testBuf = testBand != null ? targetTiles.get(testBand).getDataBuffer() : null;
        final ProductData probBuf = probBand != null ? targetTiles.get(probBand).getDataBuffer() : null;
        final ProductData maskBuf = maskBand != null ? targetTiles.get(maskBand).getDataBuffer() : null;
        final TileIndex tgtIndex = new TileIndex(targetTiles.values().iterator().next());

        // Use the first PolSourceBand list of each input (the typical case is a single polsar band group)
        final PolBandUtils.PolSourceBand refBands = refBandList[0];
        final PolBandUtils.PolSourceBand secBands = secBandList[0];

        final Tile[] refTiles = new Tile[refBands.srcBands.length];
        final Tile[] secTiles = new Tile[secBands.srcBands.length];
        final ProductData[] refBufs = new ProductData[refBands.srcBands.length];
        final ProductData[] secBufs = new ProductData[secBands.srcBands.length];
        for (int i = 0; i < refBands.srcBands.length; i++) {
            refTiles[i] = getSourceTile(refBands.srcBands[i], sourceRectangle);
            refBufs[i] = refTiles[i].getDataBuffer();
        }
        for (int i = 0; i < secBands.srcBands.length; i++) {
            secTiles[i] = getSourceTile(secBands.srcBands[i], sourceRectangle);
            secBufs[i] = secTiles[i].getDataBuffer();
        }

        try {
            if (p == 2) {
                computeC2(x0, y0, maxX, maxY, tgtIndex, testBuf, probBuf, maskBuf, refTiles, refBufs, secTiles, secBufs);
            } else {
                computeC3T3(x0, y0, maxX, maxY, tgtIndex, testBuf, probBuf, maskBuf, refTiles, refBufs, secTiles, secBufs);
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private void computeC2(int x0, int y0, int maxX, int maxY, TileIndex tgtIndex,
                           ProductData testBuf, ProductData probBuf, ProductData maskBuf,
                           Tile[] refTiles, ProductData[] refBufs,
                           Tile[] secTiles, ProductData[] secBufs) {
        final double[][] C1r = new double[2][2];
        final double[][] C1i = new double[2][2];
        final double[][] C2r = new double[2][2];
        final double[][] C2i = new double[2][2];

        for (int y = y0; y < maxY; ++y) {
            tgtIndex.calculateStride(y);
            for (int x = x0; x < maxX; ++x) {
                final int tgtIdx = tgtIndex.getIndex(x);
                getMeanCovarianceMatrixC2(x, y, halfWindowSize, halfWindowSize, sourceImageWidth, sourceImageHeight,
                        matrix, refTiles, refBufs, C1r, C1i);
                getMeanCovarianceMatrixC2(x, y, halfWindowSize, halfWindowSize, sourceImageWidth, sourceImageHeight,
                        matrix, secTiles, secBufs, C2r, C2i);
                writeStat(tgtIdx, testBuf, probBuf, maskBuf,
                        det2(C1r, C1i), det2(C2r, C2i), det2Sum(C1r, C1i, C2r, C2i));
            }
        }
    }

    private void computeC3T3(int x0, int y0, int maxX, int maxY, TileIndex tgtIndex,
                             ProductData testBuf, ProductData probBuf, ProductData maskBuf,
                             Tile[] refTiles, ProductData[] refBufs,
                             Tile[] secTiles, ProductData[] secBufs) {
        final double[][] T1r = new double[3][3];
        final double[][] T1i = new double[3][3];
        final double[][] T2r = new double[3][3];
        final double[][] T2i = new double[3][3];

        // T3 and C3 are unitarily related so determinants match; getMeanCovarianceMatrix
        // converts T3→C3 internally before averaging.
        for (int y = y0; y < maxY; ++y) {
            tgtIndex.calculateStride(y);
            for (int x = x0; x < maxX; ++x) {
                final int tgtIdx = tgtIndex.getIndex(x);
                getMeanCovarianceMatrix(x, y, halfWindowSize, halfWindowSize,
                        matrix, refTiles, refBufs, T1r, T1i);
                getMeanCovarianceMatrix(x, y, halfWindowSize, halfWindowSize,
                        matrix, secTiles, secBufs, T2r, T2i);
                writeStat(tgtIdx, testBuf, probBuf, maskBuf,
                        det3Hermitian(T1r, T1i), det3Hermitian(T2r, T2i),
                        det3HermitianAvg(T1r, T1i, T2r, T2i));
            }
        }
    }

    private void writeStat(final int tgtIdx, final ProductData testBuf, final ProductData probBuf,
                           final ProductData maskBuf,
                           final double det1, final double det2, final double detAvg) {
        if (det1 <= 0 || det2 <= 0 || detAvg <= 0) {
            // Numerically singular block — write NaN / 0
            if (testBuf != null) testBuf.setElemFloatAt(tgtIdx, Float.NaN);
            if (probBuf != null) probBuf.setElemFloatAt(tgtIdx, Float.NaN);
            if (maskBuf != null) maskBuf.setElemIntAt(tgtIdx, 0);
            return;
        }
        final double lnQ = numLooks * Math.log(det1) + numLooks * Math.log(det2) - 2.0 * numLooks * Math.log(detAvg);
        final double T = -2.0 * lnQ;
        if (testBuf != null) testBuf.setElemFloatAt(tgtIdx, (float) T);
        if (probBuf != null) probBuf.setElemFloatAt(tgtIdx, (float) chiSquareCdf(T, p * p));
        if (maskBuf != null) maskBuf.setElemIntAt(tgtIdx, T >= thresholdT ? 1 : 0);
    }

    /* ─── matrix determinants for Hermitian C2 / T3 ─────────────────────────────────── */

    private static double det2(final double[][] Cr, final double[][] Ci) {
        // det of 2x2 Hermitian: c11 · c22 − |c12|²
        final double c12r = Cr[0][1];
        final double c12i = Ci[0][1];
        return Cr[0][0] * Cr[1][1] - (c12r * c12r + c12i * c12i);
    }

    private static double det2Sum(final double[][] C1r, final double[][] C1i,
                                   final double[][] C2r, final double[][] C2i) {
        // det of (C1+C2)/2 for 2x2 Hermitian.
        final double a = 0.5 * (C1r[0][0] + C2r[0][0]);
        final double d = 0.5 * (C1r[1][1] + C2r[1][1]);
        final double br = 0.5 * (C1r[0][1] + C2r[0][1]);
        final double bi = 0.5 * (C1i[0][1] + C2i[0][1]);
        return a * d - (br * br + bi * bi);
    }

    private static double det3Hermitian(final double[][] Tr, final double[][] Ti) {
        // det = t11·t22·t33 + 2·Re(t12·t23·conj(t13)) − t11·|t23|² − t22·|t13|² − t33·|t12|²
        final double t11 = Tr[0][0], t22 = Tr[1][1], t33 = Tr[2][2];
        final double t12r = Tr[0][1], t12i = Ti[0][1];
        final double t13r = Tr[0][2], t13i = Ti[0][2];
        final double t23r = Tr[1][2], t23i = Ti[1][2];
        final double abs12sq = t12r * t12r + t12i * t12i;
        final double abs13sq = t13r * t13r + t13i * t13i;
        final double abs23sq = t23r * t23r + t23i * t23i;
        // t23 · conj(t13)
        final double conj13_t23_r = t23r * t13r + t23i * t13i;
        final double conj13_t23_i = t23i * t13r - t23r * t13i;
        // t12 · (t23·conj(t13))
        final double tripleRe = t12r * conj13_t23_r - t12i * conj13_t23_i;
        return t11 * t22 * t33 + 2.0 * tripleRe - t11 * abs23sq - t22 * abs13sq - t33 * abs12sq;
    }

    private static double det3HermitianAvg(final double[][] T1r, final double[][] T1i,
                                            final double[][] T2r, final double[][] T2i) {
        final double[][] Sr = new double[3][3];
        final double[][] Si = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                Sr[i][j] = 0.5 * (T1r[i][j] + T2r[i][j]);
                Si[i][j] = 0.5 * (T1i[i][j] + T2i[i][j]);
            }
        }
        return det3Hermitian(Sr, Si);
    }

    /* ─── χ² CDF / inverse CDF via Wilson–Hilferty ─────────────────────────────────── */

    /** χ²(k) CDF at x via the Wilson–Hilferty cube-root normal approximation. */
    private static double chiSquareCdf(final double x, final int k) {
        if (x <= 0) return 0;
        final double cubeRoot = Math.pow(x / k, 1.0 / 3.0);
        final double mean = 1.0 - 2.0 / (9.0 * k);
        final double sd = Math.sqrt(2.0 / (9.0 * k));
        return normalCdf((cubeRoot - mean) / sd);
    }

    /** χ²(k) inverse CDF at probability q via Wilson–Hilferty. */
    private static double chiSquareInverseCdf(final double q, final int k) {
        if (q <= 0) return 0;
        if (q >= 1) return Double.POSITIVE_INFINITY;
        final double z = normalInverseCdf(q);
        final double mean = 1.0 - 2.0 / (9.0 * k);
        final double sd = Math.sqrt(2.0 / (9.0 * k));
        final double cube = mean + sd * z;
        return cube > 0 ? k * cube * cube * cube : 0;
    }

    /** Standard normal CDF via Abramowitz & Stegun 26.2.17 (max abs error < 7.5e-8). */
    private static double normalCdf(final double x) {
        final double absX = Math.abs(x);
        final double t = 1.0 / (1.0 + 0.2316419 * absX);
        final double phi = Math.exp(-0.5 * x * x) / Math.sqrt(2 * Math.PI);
        final double poly = ((((1.330274429 * t - 1.821255978) * t + 1.781477937) * t - 0.356563782) * t + 0.319381530) * t;
        final double cdf = 1.0 - phi * poly;
        return x >= 0 ? cdf : 1.0 - cdf;
    }

    /** Standard normal inverse CDF via Beasley–Springer / Moro (sufficient for α thresholding). */
    private static double normalInverseCdf(final double p) {
        if (p <= 0) return Double.NEGATIVE_INFINITY;
        if (p >= 1) return Double.POSITIVE_INFINITY;
        final double a1 = -3.969683028665376e+01, a2 = 2.209460984245205e+02;
        final double a3 = -2.759285104469687e+02, a4 = 1.383577518672690e+02;
        final double a5 = -3.066479806614716e+01, a6 = 2.506628277459239e+00;
        final double b1 = -5.447609879822406e+01, b2 = 1.615858368580409e+02;
        final double b3 = -1.556989798598866e+02, b4 = 6.680131188771972e+01;
        final double b5 = -1.328068155288572e+01;
        final double c1 = -7.784894002430293e-03, c2 = -3.223964580411365e-01;
        final double c3 = -2.400758277161838e+00, c4 = -2.549732539343734e+00;
        final double c5 = 4.374664141464968e+00, c6 = 2.938163982698783e+00;
        final double d1 = 7.784695709041462e-03, d2 = 3.224671290700398e-01;
        final double d3 = 2.445134137142996e+00, d4 = 3.754408661907416e+00;
        final double pLow = 0.02425, pHigh = 1 - pLow;
        if (p < pLow) {
            final double q = Math.sqrt(-2 * Math.log(p));
            return (((((c1 * q + c2) * q + c3) * q + c4) * q + c5) * q + c6) /
                    ((((d1 * q + d2) * q + d3) * q + d4) * q + 1);
        }
        if (p <= pHigh) {
            final double q = p - 0.5;
            final double r = q * q;
            return (((((a1 * r + a2) * r + a3) * r + a4) * r + a5) * r + a6) * q /
                    (((((b1 * r + b2) * r + b3) * r + b4) * r + b5) * r + 1);
        }
        final double q = Math.sqrt(-2 * Math.log(1 - p));
        return -(((((c1 * q + c2) * q + c3) * q + c4) * q + c5) * q + c6) /
                ((((d1 * q + d2) * q + d3) * q + d4) * q + 1);
    }

    private Rectangle getSourceRectangle(final int tx0, final int ty0, final int tw, final int th) {
        final int x0 = Math.max(0, tx0 - halfWindowSize);
        final int y0 = Math.max(0, ty0 - halfWindowSize);
        final int xMax = Math.min(tx0 + tw - 1 + halfWindowSize, sourceImageWidth - 1);
        final int yMax = Math.min(ty0 + th - 1 + halfWindowSize, sourceImageHeight - 1);
        return new Rectangle(x0, y0, xMax - x0 + 1, yMax - y0 + 1);
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(PolarimetricChangeDetectionOp.class);
        }
    }
}
