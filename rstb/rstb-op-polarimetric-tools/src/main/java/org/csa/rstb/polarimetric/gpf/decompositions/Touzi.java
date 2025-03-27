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
import eu.esa.sar.commons.polsar.PolBandUtils.MATRIX;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.gpf.TileIndex;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Perform Touzi decomposition for given tile.
 */
public class Touzi extends DecompositionBase implements Decomposition, QuadPolProcessor {

    private final boolean outputTouziParamSet0;
    private final boolean outputTouziParamSet1;
    private final boolean outputTouziParamSet2;
    private final boolean outputTouziParamSet3;

    public Touzi(final PolBandUtils.PolSourceBand[] srcBandList, final MATRIX sourceProductType,
                 final int windowSize, final int srcImageWidth, final int srcImageHeight,
                 final boolean outputTouziParamSet0,
                 final boolean outputTouziParamSet1,
                 final boolean outputTouziParamSet2,
                 final boolean outputTouziParamSet3) {
        super(srcBandList, sourceProductType, windowSize, windowSize, srcImageWidth, srcImageHeight);

        this.outputTouziParamSet0 = outputTouziParamSet0;
        this.outputTouziParamSet1 = outputTouziParamSet1;
        this.outputTouziParamSet2 = outputTouziParamSet2;
        this.outputTouziParamSet3 = outputTouziParamSet3;
    }

    public String getSuffix() {
        return "_Touzi";
    }

    /**
     * Return the list of band names for the target product
     *
     * @return list of band names
     */
    public String[] getTargetBandNames() {
        final List<String> targetBandNameList = new ArrayList<>(4);

        if (!outputTouziParamSet0 && !outputTouziParamSet1 && !outputTouziParamSet2 && !outputTouziParamSet3) {
            throw new OperatorException("Please select decomposition parameters to output");
        }

        if (outputTouziParamSet0) {
            targetBandNameList.add("Psi");
            targetBandNameList.add("Tau");
            targetBandNameList.add("Alpha");
            targetBandNameList.add("Phi");
        }
        if (outputTouziParamSet1) {
            targetBandNameList.add("Psi1");
            targetBandNameList.add("Tau1");
            targetBandNameList.add("Alpha1");
            targetBandNameList.add("Phi1");
        }
        if (outputTouziParamSet2) {
            targetBandNameList.add("Psi2");
            targetBandNameList.add("Tau2");
            targetBandNameList.add("Alpha2");
            targetBandNameList.add("Phi2");
        }
        if (outputTouziParamSet3) {
            targetBandNameList.add("Psi3");
            targetBandNameList.add("Tau3");
            targetBandNameList.add("Alpha3");
            targetBandNameList.add("Phi3");
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
        targetBand.setUnit("rad");
    }

    /**
     * Perform decomposition for given tile.
     *
     * @param targetTiles     The current tiles to be computed for each target band.
     * @param targetRectangle The area in pixel coordinates to be computed.
     * @param op              the polarimetric decomposition operator
     * @throws OperatorException If an error occurs during computation of the filtered value.
     */
    public void computeTile(final Map<Band, Tile> targetTiles, final Rectangle targetRectangle,
                            final Operator op) {

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

        for (final PolBandUtils.PolSourceBand bandList : srcBandList) {

            final Tile[] sourceTiles = new Tile[bandList.srcBands.length];
            final ProductData[] dataBuffers = new ProductData[bandList.srcBands.length];
            final Rectangle sourceRectangle = getSourceRectangle(x0, y0, w, h);
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

                    final TDD data = getTouziDecomposition(Tr, Ti);

                    for (final Band band : bandList.targetBands) {
                        final String targetBandName = band.getName();
                        final ProductData dataBuffer = targetTiles.get(band).getDataBuffer();
                        if (outputTouziParamSet0) {
                            if (targetBandName.equals("Psi") || targetBandName.contains("Psi_"))
                                dataBuffer.setElemFloatAt(idx, (float) data.psiMean);
                            else if (targetBandName.equals("Tau") || targetBandName.contains("Tau_"))
                                dataBuffer.setElemFloatAt(idx, (float) data.tauMean);
                            else if (targetBandName.equals("Alpha") || targetBandName.contains("Alpha_"))
                                dataBuffer.setElemFloatAt(idx, (float) data.alphaMean);
                            else if (targetBandName.equals("Phi") || targetBandName.contains("Phi_"))
                                dataBuffer.setElemFloatAt(idx, (float) data.phiMean);
                        }
                        if (outputTouziParamSet1) {
                            if (targetBandName.contains("Psi1"))
                                dataBuffer.setElemFloatAt(idx, (float) data.psi1);
                            else if (targetBandName.contains("Tau1"))
                                dataBuffer.setElemFloatAt(idx, (float) data.tau1);
                            else if (targetBandName.contains("Alpha1"))
                                dataBuffer.setElemFloatAt(idx, (float) data.alpha1);
                            else if (targetBandName.contains("Phi1"))
                                dataBuffer.setElemFloatAt(idx, (float) data.phi1);
                        }
                        if (outputTouziParamSet2) {
                            if (targetBandName.contains("Psi2"))
                                dataBuffer.setElemFloatAt(idx, (float) data.psi2);
                            else if (targetBandName.contains("Tau2"))
                                dataBuffer.setElemFloatAt(idx, (float) data.tau2);
                            else if (targetBandName.contains("Alpha2"))
                                dataBuffer.setElemFloatAt(idx, (float) data.alpha2);
                            else if (targetBandName.contains("Phi2"))
                                dataBuffer.setElemFloatAt(idx, (float) data.phi2);
                        }
                        if (outputTouziParamSet3) {
                            if (targetBandName.contains("Psi3"))
                                dataBuffer.setElemFloatAt(idx, (float) data.psi3);
                            else if (targetBandName.contains("Tau3"))
                                dataBuffer.setElemFloatAt(idx, (float) data.tau3);
                            else if (targetBandName.contains("Alpha3"))
                                dataBuffer.setElemFloatAt(idx, (float) data.alpha3);
                            else if (targetBandName.contains("Phi3"))
                                dataBuffer.setElemFloatAt(idx, (float) data.phi3);
                        }
                    }
                }
            }
        }
    }

    public static TDD getTouziDecomposition(final double[][] Tr, final double[][] Ti) {

        final double[][] EigenVectRe = new double[3][3];
        final double[][] EigenVectIm = new double[3][3];
        final double[] EigenVal = new double[3];
        final double[] psi = new double[3];
        final double[] tau = new double[3];
        final double[] alpha = new double[3];
        final double[] phi = new double[3];
        final double[] vr = new double[3];
        final double[] vi = new double[3];
        double p1, p2, p3, psiMean, tauMean, alphaMean, phiMean;
        double phase, c, s, tmp1r, tmp1i, tmp2r, tmp2i;

        EigenDecomposition.eigenDecomposition(3, Tr, Ti, EigenVectRe, EigenVectIm, EigenVal);

        for (int k = 0; k < 3; ++k) {
            for (int l = 0; l < 3; ++l) {
                vr[l] = EigenVectRe[l][k];
                vi[l] = EigenVectIm[l][k];
            }

            phase = Math.atan2(vi[0], vr[0] + Constants.EPS);
            c = FastMath.cos(phase);
            s = FastMath.sin(phase);
            for (int l = 0; l < 3; ++l) {
                tmp1r = vr[l];
                tmp1i = vi[l];
                vr[l] = tmp1r * c + tmp1i * s;
                vi[l] = tmp1i * c - tmp1r * s;
            }

            psi[k] = 0.5 * Math.atan2(vr[2], vr[1] + Constants.EPS);

            tmp1r = vr[1];
            tmp1i = vi[1];
            tmp2r = vr[2];
            tmp2i = vi[2];
            c = FastMath.cos(2.0 * psi[k]);
            s = FastMath.sin(2.0 * psi[k]);
            vr[1] = tmp1r * c + tmp2r * s;
            vi[1] = tmp1i * c + tmp2i * s;
            vr[2] = -tmp1r * s + tmp2r * c;
            vi[2] = -tmp1i * s + tmp2i * c;

            tau[k] = 0.5 * Math.atan2(-vi[2], vr[0] + Constants.EPS);

            phi[k] = Math.atan2(vi[1], vr[1] + Constants.EPS);

            alpha[k] = Math.atan(Math.sqrt((vr[1] * vr[1] + vi[1] * vi[1]) / (vr[0] * vr[0] + vi[2] * vi[2])));

            if ((psi[k] < -Constants.PI / 4.0) || (psi[k] > Constants.PI / 4.0)) {
                tau[k] = -tau[k];
                phi[k] = -phi[k];
            }

        }

        final double sum = EigenVal[0] + EigenVal[1] + EigenVal[2];
        p1 = EigenVal[0] / sum;
        p2 = EigenVal[1] / sum;
        p3 = EigenVal[2] / sum;

        psiMean = p1 * psi[0] + p2 * psi[1] + p3 * psi[2];
        tauMean = p1 * tau[0] + p2 * tau[1] + p3 * tau[2];
        alphaMean = p1 * alpha[0] + p2 * alpha[1] + p3 * alpha[2];
        phiMean = p1 * phi[0] + p2 * phi[1] + p3 * phi[2];

        return new TDD(psiMean, tauMean, alphaMean, phiMean, psi, tau, alpha, phi);
    }

    public static class TDD {
        public final double psiMean;
        public final double tauMean;
        public final double alphaMean;
        public final double phiMean;

        public final double psi1;
        public final double tau1;
        public final double alpha1;
        public final double phi1;

        public final double psi2;
        public final double tau2;
        public final double alpha2;
        public final double phi2;

        public final double psi3;
        public final double tau3;
        public final double alpha3;
        public final double phi3;

        public TDD(final double psiMean, final double tauMean, final double alphaMean, final double phiMean,
                   final double[] psi, final double[] tau, final double[] alpha, final double[] phi) {

            this.psiMean = psiMean;
            this.tauMean = tauMean;
            this.alphaMean = alphaMean;
            this.phiMean = phiMean;

            this.psi1 = psi[0];
            this.psi2 = psi[1];
            this.psi3 = psi[2];

            this.tau1 = tau[0];
            this.tau2 = tau[1];
            this.tau3 = tau[2];

            this.alpha1 = alpha[0];
            this.alpha2 = alpha[1];
            this.alpha3 = alpha[2];

            this.phi1 = phi[0];
            this.phi2 = phi[1];
            this.phi3 = phi[2];
        }
    }
}
