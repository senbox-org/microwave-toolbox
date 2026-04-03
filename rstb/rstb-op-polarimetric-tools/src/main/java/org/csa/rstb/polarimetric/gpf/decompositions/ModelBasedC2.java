/*
 * Copyright (C) 20123 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
import org.csa.rstb.polarimetric.gpf.PolarimetricDecompositionOp;
import org.csa.rstb.polarimetric.gpf.support.DualPolProcessor;
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
import java.util.Map;

/**
 * Perform model-based decomposition for given dual-pol product.
 * <p>
 * [1]	L. Mascolo, S. R. Cloude, and J. M. Lopez–Sanchez, "Model-based decomposition of dual-pol SAR data: 
        application to Sentinel-1", IEEE Transactions on Geoscience and Remote Sensing, vol. 60, id. 3137588, 2022.
 */
public class ModelBasedC2 extends DecompositionBase implements Decomposition, DualPolProcessor {

    private String pol = null;
    private int sign = 1;

    public ModelBasedC2(final PolBandUtils.PolSourceBand[] srcBandList, final PolBandUtils.MATRIX sourceProductType,
                        final int windowSizeX, final int windowSizeY, final String polarization,
                        final int srcImageWidth, final int srcImageHeight) {
        super(srcBandList, sourceProductType, windowSizeX, windowSizeY, srcImageWidth, srcImageHeight);

        pol = polarization;
        if (polarization.equals(PolarimetricDecompositionOp.VH_VV)) {
            sign = -1;
        }
    }

    public String getSuffix() {
        return "_MB";
    }

    /**
     * Return the list of band names for the target product
     *
     * @return list of band names
     */
    public String[] getTargetBandNames() {
        return new String[]{"Surface_r", "Volume_g", "Ratio_b", "Alpha", "Delta_h", "Rho_s", "Span_v"};
    }

    /**
     * Sets the unit for the new target band
     *
     * @param targetBandName the band name
     * @param targetBand     the new target band
     */
    public void setBandUnit(final String targetBandName, final Band targetBand) {
        if (targetBandName.contains("Surface_r")) {
            targetBand.setUnit(Unit.DB);
        } else if (targetBandName.contains("Volume_g")) {
            targetBand.setUnit(Unit.DB);
        } else if (targetBandName.contains("Ratio_b")) {
            targetBand.setUnit(Unit.DB);
        } else if (targetBandName.equals("Alpha")) {
            targetBand.setUnit(Unit.DEGREES);
        } else if (targetBandName.equals("Delta_h")) {
            targetBand.setUnit(Unit.DEGREES);
        } else if (targetBandName.equals("Rho_s")) {
            targetBand.setUnit(Unit.COHERENCE);
        } else if (targetBandName.equals("Span_v")) {
            targetBand.setUnit(Unit.INTENSITY);
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

        final double[][] Cr = new double[2][2]; // real part of covariance matrix
        final double[][] Ci = new double[2][2]; // imaginary part of covariance matrix
        final Rectangle sourceRectangle = getSourceRectangle(x0, y0, w, h);

        for (final PolBandUtils.PolSourceBand bandList : srcBandList) {

            final TargetInfo[] targetInfo = new TargetInfo[bandList.targetBands.length];
            int j = 0;
            for (Band targetBand : bandList.targetBands) {
                final String targetBandName = targetBand.getName();
                if (targetBandName.contains("Surface_r")) {
                    targetInfo[j] = new TargetInfo(targetTiles.get(targetBand), TargetBandColour.R);
                } else if (targetBandName.contains("Volume_g")) {
                    targetInfo[j] = new TargetInfo(targetTiles.get(targetBand), TargetBandColour.G);
                } else if (targetBandName.contains("Ratio_b")) {
                    targetInfo[j] = new TargetInfo(targetTiles.get(targetBand), TargetBandColour.B);
                } else if (targetBandName.contains("Alpha")) {
                    targetInfo[j] = new TargetInfo(targetTiles.get(targetBand), null);
                } else if (targetBandName.contains("Delta_h")) {
                    targetInfo[j] = new TargetInfo(targetTiles.get(targetBand), TargetBandColour.H);
                } else if (targetBandName.contains("Rho_s")) {
                    targetInfo[j] = new TargetInfo(targetTiles.get(targetBand), TargetBandColour.S);
                } else if (targetBandName.contains("Span_v")) {
                    targetInfo[j] = new TargetInfo(targetTiles.get(targetBand), TargetBandColour.V);
                }
                ++j;
            }
            final TileIndex trgIndex = new TileIndex(targetInfo[0].tile);

            final Tile[] sourceTiles = new Tile[bandList.srcBands.length];
            final ProductData[] dataBuffers = new ProductData[bandList.srcBands.length];
            for (int i = 0; i < bandList.srcBands.length; ++i) {
                sourceTiles[i] = op.getSourceTile(bandList.srcBands[i], sourceRectangle);
                dataBuffers[i] = sourceTiles[i].getDataBuffer();
            }

            for (int y = y0; y < maxY; ++y) {
                trgIndex.calculateStride(y);

                for (int x = x0; x < maxX; ++x) {
                    final int index = trgIndex.getIndex(x);

                    getMeanCovarianceMatrixC2(x, y, halfWindowSizeX, halfWindowSizeY, sourceImageWidth,
                            sourceImageHeight, sourceProductType, sourceTiles, dataBuffers, Cr, Ci);

                    ModelBased data = getModelBasedDecomposition(Cr, Ci, sign, pol);

                    for (TargetInfo target : targetInfo) {
                        double v = 0.0;
                        if (target.colour == TargetBandColour.R) {
                            v = data.surface;
                        } else if (target.colour == TargetBandColour.G) {
                            v = data.volume;
                        } else if (target.colour == TargetBandColour.B) {
                            v = data.ratio;
                        } else if (target.colour == TargetBandColour.H) {
                            v = data.delta;
                        } else if (target.colour == TargetBandColour.S) {
                            v = data.rho;
                        } else if (target.colour == TargetBandColour.V) {
                            v = data.span;
                        } else if (target.colour == null) {
                            v = data.alpha;
                        }

                        target.dataBuffer.setElemFloatAt(index, (float) v);
                    }
                }
            }
        }
    }

    public static ModelBased getModelBasedDecomposition(final double[][] Cr, final double[][] Ci,
                                                        final int sign, final String pol) {

        final double[] stokesVector = getStokesVector(Cr, Ci, pol);
        final double mv = computeMv(stokesVector, sign);
        final double ms = computeMs(stokesVector, mv);
        final double alpha = computeAlpha(stokesVector, mv, sign);
        final double delta = computeDelta(stokesVector);

        final ModelBased data = new ModelBased();
        data.surface = 10.0 * FastMath.log10(ms);
        data.volume = 10.0 * FastMath.log10(mv);
        data.ratio = data.surface - data.volume;
        data.alpha = alpha;
        data.delta = delta;
        data.rho = norm(Cr[0][1], Ci[0][1]) / FastMath.sqrt(Cr[0][0] * Cr[1][1]);
        data.span = Cr[0][0] + Cr[1][1];

        return data;
    }

    private static double[] getStokesVector(final double[][] Cr, final double[][] Ci, final String pol) {

        final double[] stokesVector = new double[4];
        if (pol.equals(PolarimetricDecompositionOp.VH_VV)) {
            stokesVector[0] = Cr[1][1] + Cr[0][0];
            stokesVector[1] = Cr[1][1] - Cr[0][0];
            stokesVector[2] = 2.0 * Cr[1][0];
            stokesVector[3] = 2.0 * Ci[1][0];
        } else {
            stokesVector[0] = Cr[0][0] + Cr[1][1];
            stokesVector[1] = Cr[0][0] - Cr[1][1];
            stokesVector[2] = 2.0 * Cr[0][1];
            stokesVector[3] = 2.0 * Ci[0][1];
        }

        return stokesVector;
    }

    private static double computeMv(final double[] s, final int sign) {

        final double a = 0.75;
        final double b = -2.0*s[0] + sign*s[1];
        final double c = s[0]*s[0] - s[1]*s[1] - s[2]*s[2] - s[3]*s[3];
        final double d = FastMath.sqrt(b*b - 4.0*a*c);
        final double mv1 = (-b + d) / (2.0*a);
        final double mv2 = (-b - d) / (2.0*a);

        if (mv1 >= 0.0 && mv1 <= s[0]) {
            return mv1;
        } else if (mv2 >= 0.0 && mv2 <= s[0]) {
            return mv2;
        } else {
            return 0.0;
        }
    }

    private static double computeMs(final double[] s, final double mv) {
        return s[0] - mv;
    }

    private static double computeAlpha(final double[] s, final double mv, final int sign) {
        return 0.5 * FastMath.acos((sign * s[1] - 0.5 * mv) / (s[0] - mv)) * Constants._RTOD;
//        return 0.5 * FastMath.acos((s[1] - sign * 0.5 * mv) / (s[0] - mv)) * Constants._RTOD;
    }

    private static double computeDelta(final double[] s) {
        return FastMath.atan2(s[3], s[2]) * Constants._RTOD;
    }

    private static double norm(final double real, final double imag) {
        return FastMath.sqrt(real * real + imag * imag);
    }

    public static class ModelBased {
        public double surface;
        public double volume;
        public double ratio;
        public double alpha;
        public double delta;
        public double rho;
        public double span;
    }
}
