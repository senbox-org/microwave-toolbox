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
package org.csa.rstb.polarimetric.gpf.decompositions_cp;

import org.csa.rstb.polarimetric.gpf.support.CompactPolProcessor;
import org.csa.rstb.polarimetric.gpf.support.StokesParameters;
import org.csa.rstb.polarimetric.gpf.decompositions.Decomposition;
import org.csa.rstb.polarimetric.gpf.decompositions.DecompositionBase;
import eu.esa.sar.commons.polsar.PolBandUtils;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.TileIndex;

import java.awt.*;
import java.util.Map;

/**
 * m-α (Cloude/Raney) decomposition for compact-polarimetric data.
 * <p>
 * Three RGB power components computed per pixel from the Stokes vector:
 * <pre>
 *   Pd (double-bounce) = m · g0 · sin²(α_s)        (Red)
 *   Pv (volume)        = (1 − m) · g0              (Green)
 *   Ps (surface)       = m · g0 · cos²(α_s)        (Blue)
 * </pre>
 * where m is the degree of polarization and α_s is the compact-pol alpha angle.
 * Output bands carry the band-name suffix "_MAlpha" with channels MAlpha_r, MAlpha_g, MAlpha_b.
 *
 * Reference: Cloude, S. R., Goodenough, D. G., & Chen, H. (2012).
 *   Compact decomposition theory. IEEE GRSL 9(1), 28–32.
 */
public class CP_MAlpha extends DecompositionBase implements Decomposition, CompactPolProcessor {

    private final String compactMode;

    private static final String RED = "MAlpha_r";
    private static final String GREEN = "MAlpha_g";
    private static final String BLUE = "MAlpha_b";

    public CP_MAlpha(final PolBandUtils.PolSourceBand[] srcBandList, final PolBandUtils.MATRIX sourceProductType,
                     final String compactMode, final int windowSizeX, final int windowSizeY,
                     final int srcImageWidth, final int srcImageHeight) {
        super(srcBandList, sourceProductType, windowSizeX, windowSizeY, srcImageWidth, srcImageHeight);
        this.compactMode = compactMode;
    }

    public String getSuffix() {
        return "_MAlpha";
    }

    public String[] getTargetBandNames() {
        return new String[]{RED, GREEN, BLUE};
    }

    public void setBandUnit(final String targetBandName, final Band targetBand) {
        targetBand.setUnit(Unit.INTENSITY);
    }

    public void computeTile(final Map<Band, Tile> targetTiles, final Rectangle targetRectangle, final Operator op) {

        final int x0 = targetRectangle.x;
        final int y0 = targetRectangle.y;
        final int w = targetRectangle.width;
        final int h = targetRectangle.height;
        final int maxY = y0 + h;
        final int maxX = x0 + w;

        final Rectangle sourceRectangle = getSourceRectangle(x0, y0, w, h);
        for (final PolBandUtils.PolSourceBand bandList : srcBandList) {

            final TargetInfo[] targetInfo = new TargetInfo[bandList.targetBands.length];
            int j = 0;
            for (Band targetBand : bandList.targetBands) {
                final String targetBandName = targetBand.getName();
                if (targetBandName.contains(RED)) {
                    targetInfo[j] = new TargetInfo(targetTiles.get(targetBand), TargetBandColour.R);
                } else if (targetBandName.contains(GREEN)) {
                    targetInfo[j] = new TargetInfo(targetTiles.get(targetBand), TargetBandColour.G);
                } else if (targetBandName.contains(BLUE)) {
                    targetInfo[j] = new TargetInfo(targetTiles.get(targetBand), TargetBandColour.B);
                }
                ++j;
            }
            final TileIndex trgIndex = new TileIndex(targetInfo[0].tile);

            final double[][] Cr = new double[2][2];
            final double[][] Ci = new double[2][2];
            final double[] g = new double[4];

            final Tile[] sourceTiles = new Tile[bandList.srcBands.length];
            final ProductData[] dataBuffers = new ProductData[bandList.srcBands.length];
            for (int i = 0; i < bandList.srcBands.length; i++) {
                sourceTiles[i] = op.getSourceTile(bandList.srcBands[i], sourceRectangle);
                dataBuffers[i] = sourceTiles[i].getDataBuffer();
            }

            for (int y = y0; y < maxY; ++y) {
                trgIndex.calculateStride(y);
                for (int x = x0; x < maxX; ++x) {
                    final int index = trgIndex.getIndex(x);

                    getMeanCovarianceMatrixC2(x, y, halfWindowSizeX, halfWindowSizeY, sourceImageWidth,
                            sourceImageHeight, sourceProductType, sourceTiles, dataBuffers, Cr, Ci);

                    StokesParameters.computeCompactPolStokesVector(Cr, Ci, g);
                    final StokesParameters sp = StokesParameters.computeStokesParameters(g, compactMode);

                    final double m = sp.DegreeOfPolarization;
                    final double sinAs = Math.sin(sp.Alphas);
                    final double cosAs = Math.cos(sp.Alphas);
                    final double polPower = m * g[0];

                    final double pd = polPower * sinAs * sinAs;
                    final double pv = (1.0 - m) * g[0];
                    final double ps = polPower * cosAs * cosAs;

                    for (TargetInfo target : targetInfo) {
                        final double v;
                        if (target.colour == TargetBandColour.R) {
                            v = Math.sqrt(Math.max(pd, 0));
                        } else if (target.colour == TargetBandColour.G) {
                            v = Math.sqrt(Math.max(pv, 0));
                        } else {
                            v = Math.sqrt(Math.max(ps, 0));
                        }
                        target.dataBuffer.setElemFloatAt(index, (float) v);
                    }
                }
            }
        }
    }
}
