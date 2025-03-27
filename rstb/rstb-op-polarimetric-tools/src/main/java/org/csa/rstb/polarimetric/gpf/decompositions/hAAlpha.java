/*
 * Copyright (C) 2015 by Array Systems Computing Inc. http://www.array.ca
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
package org.csa.rstb.polarimetric.gpf.decompositions;

import org.apache.commons.math3.util.FastMath;
import org.csa.rstb.polarimetric.gpf.support.QuadPolProcessor;
import eu.esa.sar.commons.polsar.PolBandUtils;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.gpf.TileIndex;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Perform hAAlpha decomposition for given tile.
 */
public class hAAlpha extends DecompositionBase implements Decomposition, QuadPolProcessor {

    private final boolean outputHAAlpha;
    private final boolean outputBetaDeltaGammaLambda;
    private final boolean outputAlpha123;
    private final boolean outputLambda123;

    private static final double LOG_3 = Math.log(3);

    public hAAlpha(final PolBandUtils.PolSourceBand[] srcBandList, final PolBandUtils.MATRIX sourceProductType,
                   final int windowSize, final int srcImageWidth, final int srcImageHeight,
                   final boolean outputHAAlpha,
                   final boolean outputBetaDeltaGammaLambda,
                   final boolean outputAlpha123,
                   final boolean outputLambda123) {
        super(srcBandList, sourceProductType, windowSize, windowSize, srcImageWidth, srcImageHeight);

        this.outputHAAlpha = outputHAAlpha;
        this.outputBetaDeltaGammaLambda = outputBetaDeltaGammaLambda;
        this.outputAlpha123 = outputAlpha123;
        this.outputLambda123 = outputLambda123;
    }

    public String getSuffix() {
        return "_HA";
    }

    /**
     * Return the list of band names for the target product
     *
     * @return list of band names
     */
    public String[] getTargetBandNames() {
        final List<String> targetBandNameList = new ArrayList<>(4);

        if (!outputHAAlpha && !outputBetaDeltaGammaLambda && !outputAlpha123 && !outputLambda123) {
            throw new OperatorException("Please select decomposition parameters to output");
        }

        if (outputHAAlpha) {
            targetBandNameList.add("Entropy");
            targetBandNameList.add("Anisotropy");
            targetBandNameList.add("Alpha");
        }
        if (outputBetaDeltaGammaLambda) {
            targetBandNameList.add("Beta");
            targetBandNameList.add("Delta");
            targetBandNameList.add("Gamma");
            targetBandNameList.add("Lambda");
        }
        if (outputAlpha123) {
            targetBandNameList.add("Alpha1");
            targetBandNameList.add("Alpha2");
            targetBandNameList.add("Alpha3");
        }
        if (outputLambda123) {
            targetBandNameList.add("Lambda1");
            targetBandNameList.add("Lambda2");
            targetBandNameList.add("Lambda3");
        }

        return targetBandNameList.toArray(new String[0]);
    }

    /**
     * Sets the unit for the new target band
     *
     * @param targetBandName the band name
     * @param targetBand     the new target band
     */
    public void setBandUnit(final String targetBandName, final Band targetBand) {
        if (targetBandName.contains("Entropy")) {
            targetBand.setUnit("entropy");
        } else if (targetBandName.contains("Anisotropy")) {
            targetBand.setUnit("anisotropy");
        } else if (targetBandName.equals("Alpha") || targetBandName.contains("Alpha_")) {
            targetBand.setUnit(Unit.DEGREES);
        } else if (targetBandName.contains("Alpha1")) {
            targetBand.setUnit(Unit.DEGREES);
        } else if (targetBandName.contains("Alpha2")) {
            targetBand.setUnit(Unit.DEGREES);
        } else if (targetBandName.contains("Alpha3")) {
            targetBand.setUnit(Unit.DEGREES);
        } else if (targetBandName.contains("Beta")) {
            targetBand.setUnit(Unit.DEGREES);
        } else if (targetBandName.contains("Delta")) {
            targetBand.setUnit(Unit.DEGREES);
        } else if (targetBandName.contains("Gamma")) {
            targetBand.setUnit(Unit.DEGREES);
        } else if (targetBandName.equals("Lambda") || targetBandName.contains("Lambda_")) {
            targetBand.setUnit("lambda");
        } else if (targetBandName.contains("Lambda1")) {
            targetBand.setUnit("lambda");
        } else if (targetBandName.contains("Lambda2")) {
            targetBand.setUnit("lambda");
        } else if (targetBandName.contains("Lambda3")) {
            targetBand.setUnit("lambda");
        }
    }

    /**
     * Perform decomposition for given tile.
     *
     * @param targetTiles     The current tiles to be computed for each target band.
     * @param targetRectangle The area in pixel coordinates to be computed.
     * @param op              the polarimetric decomposition operator
     * @throws OperatorException If an error occurs during computation of the filtered value.
     */
    public void computeTile(final Map<Band, Tile> targetTiles, final Rectangle targetRectangle, final Operator op) {

        final int x0 = targetRectangle.x;
        final int y0 = targetRectangle.y;
        final int w = targetRectangle.width;
        final int h = targetRectangle.height;
        final int maxY = y0 + h;
        final int maxX = x0 + w;
        //System.out.println("x0 = " + x0 + ", y0 = " + y0 + ", w = " + w + ", h = " + h);

        final TileIndex trgIndex = new TileIndex(targetTiles.get(op.getTargetProduct().getBandAt(0)));

        final double[][] Tr = new double[3][3];
        final double[][] Ti = new double[3][3];
        final Rectangle sourceRectangle = getSourceRectangle(x0, y0, w, h);

        for (final PolBandUtils.PolSourceBand bandList : srcBandList) {
            final Tile[] sourceTiles = new Tile[bandList.srcBands.length];
            final ProductData[] dataBuffers = new ProductData[bandList.srcBands.length];
            getQuadPolDataBuffer(op, bandList.srcBands, sourceRectangle, sourceProductType, sourceTiles, dataBuffers);

            final TileIndex srcIndex = new TileIndex(sourceTiles[0]);
            final double nodatavalue = bandList.srcBands[0].getNoDataValue();

            for (int y = y0; y < maxY; ++y) {
                trgIndex.calculateStride(y);
                srcIndex.calculateStride(y);
                for (int x = x0; x < maxX; ++x) {
                    boolean isNoData = isNoData(dataBuffers, srcIndex.getIndex(x), nodatavalue);
                    if (isNoData) {
                        for (final Band band : bandList.targetBands) {
                            targetTiles.get(band).getDataBuffer().setElemFloatAt(trgIndex.getIndex(x), (float) nodatavalue);
                        }
                        continue;
                    }

                    final int idx = trgIndex.getIndex(x);

                    getMeanCoherencyMatrix(x, y, halfWindowSizeX, halfWindowSizeY, sourceImageWidth, sourceImageHeight,
                            sourceProductType, srcIndex, dataBuffers, Tr, Ti);

                    final HAAlpha data = computeHAAlpha(Tr, Ti);

                    for (final Band band : bandList.targetBands) {
                        final String targetBandName = band.getName();
                        final ProductData dataBuffer = targetTiles.get(band).getDataBuffer();
                        if (outputHAAlpha) {
                            if (targetBandName.contains("Entropy"))
                                dataBuffer.setElemFloatAt(idx, (float) data.entropy);
                            else if (targetBandName.contains("Anisotropy"))
                                dataBuffer.setElemFloatAt(idx, (float) data.anisotropy);
                            else if (targetBandName.equals("Alpha") || targetBandName.contains("Alpha_"))
                                dataBuffer.setElemFloatAt(idx, (float) data.alpha);
                        }
                        if (outputBetaDeltaGammaLambda) {
                            if (targetBandName.contains("Beta"))
                                dataBuffer.setElemFloatAt(idx, (float) data.beta);
                            else if (targetBandName.contains("Delta"))
                                dataBuffer.setElemFloatAt(idx, (float) data.delta);
                            else if (targetBandName.contains("Gamma"))
                                dataBuffer.setElemFloatAt(idx, (float) data.gamma);
                            else if (targetBandName.equals("Lambda") || targetBandName.contains("Lambda_"))
                                dataBuffer.setElemFloatAt(idx, (float) data.lambda);
                        }
                        if (outputAlpha123) {
                            if (targetBandName.contains("Alpha1"))
                                dataBuffer.setElemFloatAt(idx, (float) data.alpha1);
                            else if (targetBandName.contains("Alpha2"))
                                dataBuffer.setElemFloatAt(idx, (float) data.alpha2);
                            else if (targetBandName.contains("Alpha3"))
                                dataBuffer.setElemFloatAt(idx, (float) data.alpha3);
                        }
                        if (outputLambda123) {
                            if (targetBandName.contains("Lambda1"))
                                dataBuffer.setElemFloatAt(idx, (float) data.lambda1);
                            else if (targetBandName.contains("Lambda2"))
                                dataBuffer.setElemFloatAt(idx, (float) data.lambda2);
                            else if (targetBandName.contains("Lambda3"))
                                dataBuffer.setElemFloatAt(idx, (float) data.lambda3);
                        }
                    }
                }
            }
        }
    }

    /**
     * Compute H-A-Alpha parameters for given coherency matrix T3
     *
     * @param Tr Real part of the coherency matrix
     * @param Ti Imaginary part of the coherency matrix
     * @return The H-A-Alpha parameters
     */
    public static HAAlpha computeHAAlpha(final double[][] Tr, final double[][] Ti) {

        final double[][] EigenVectRe = new double[3][3];
        final double[][] EigenVectIm = new double[3][3];
        final double[] EigenVal = new double[3];

        final double[] lambda = new double[3];
        final double[] p = new double[3];
        final double[] alpha = new double[3];
        final double[] phi = new double[3];
        final double[] beta = new double[3];
        final double[] delta = new double[3];
        final double[] gamma = new double[3];

        EigenDecomposition.eigenDecomposition(3, Tr, Ti, EigenVectRe, EigenVectIm, EigenVal);

        double sum = 0.0;
        for (int i = 0; i < 3; ++i) {
            lambda[i] = EigenVal[i];
            sum += lambda[i];
        }

        final double EPS = Constants.EPS;
        for (int j = 0; j < 3; ++j) {
            alpha[j] = FastMath.acos(norm(EigenVectRe[0][j], EigenVectIm[0][j])) * Constants.RTOD;
            beta[j] = Math.atan2(norm(EigenVectRe[2][j], EigenVectIm[2][j]),
                    EPS + norm(EigenVectRe[1][j], EigenVectIm[1][j])) * Constants.RTOD;
            phi[j] = Math.atan2(EigenVectIm[0][j], EPS + EigenVectRe[0][j]);
            delta[j] = Math.atan2(EigenVectIm[1][j], EPS + EigenVectRe[1][j]) - phi[j];
            delta[j] = Math.atan2(FastMath.sin(delta[j]), FastMath.cos(delta[j]) + EPS) * Constants.RTOD;
            gamma[j] = Math.atan2(EigenVectIm[2][j], EPS + EigenVectRe[2][j]) - phi[j];
            gamma[j] = Math.atan2(FastMath.sin(gamma[j]), FastMath.cos(gamma[j]) + EPS) * Constants.RTOD;
            p[j] = lambda[j] / sum;
            if (p[j] < 0) {
                p[j] = 0;
            } else if (p[j] > 1) {
                p[j] = 1;
            }
        }

        double meanLambda = 0.0;
        double meanAlpha = 0.0;
        double meanBeta = 0.0;
        double meanDelta = 0.0;
        double meanGamma = 0.0;
        double entropy = 0.0;
        for (int k = 0; k < 3; ++k) {
            meanLambda += p[k] * lambda[k];
            meanAlpha += p[k] * alpha[k];
            meanBeta += p[k] * beta[k];
            meanDelta += p[k] * delta[k];
            meanGamma += p[k] * gamma[k];
            entropy -= p[k] * Math.log(p[k] + EPS);
        }

        entropy /= LOG_3;
        final double anisotropy = (p[1] - p[2]) / (p[1] + p[2] + EPS);

        return new HAAlpha(entropy, anisotropy, meanAlpha, meanBeta, meanDelta, meanGamma, meanLambda,
                alpha[0], alpha[1], alpha[2], lambda[0], lambda[1], lambda[2]);
    }

    public static double norm(final double real, final double imag) {
        return Math.sqrt(real * real + imag * imag);
    }

    public static class HAAlpha {

        public final double entropy;
        public final double anisotropy;
        public final double alpha;
        public final double beta;
        public final double delta;
        public final double gamma;
        public final double lambda;
        public final double alpha1;
        public final double alpha2;
        public final double alpha3;
        public final double lambda1;
        public final double lambda2;
        public final double lambda3;

        public HAAlpha(final double H, final double A, final double alpha, final double beta, final double delta,
                       final double gamma, final double lambda, final double alpha1, final double alpha2,
                       final double alpha3, final double lambda1, final double lambda2, final double lambda3) {

            this.entropy = H;
            this.anisotropy = A;
            this.alpha = alpha;
            this.beta = beta;
            this.delta = delta;
            this.gamma = gamma;
            this.lambda = lambda;
            this.alpha1 = alpha1;
            this.alpha2 = alpha2;
            this.alpha3 = alpha3;
            this.lambda1 = lambda1;
            this.lambda2 = lambda2;
            this.lambda3 = lambda3;
        }
    }
}
