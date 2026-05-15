/*
 * Copyright (C) 2018 SkyWatch Space Applications Inc. https://www.skywatch.com
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
package org.csa.rstb.classification.gpf.classifiers;

import eu.esa.sar.commons.polsar.PolBandUtils;
import org.csa.rstb.classification.gpf.PolarimetricClassificationOp;
import org.csa.rstb.polarimetric.gpf.decompositions.HAlphaC2;
import org.csa.rstb.polarimetric.gpf.decompositions_cp.CP_HAlpha;
import org.csa.rstb.polarimetric.gpf.support.CompactPolProcessor;
import org.csa.rstb.polarimetric.gpf.support.HaAlphaDescriptor;
import org.csa.rstb.polarimetric.gpf.support.StokesParameters;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.dataop.downloadable.StatusProgressMonitor;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.util.ThreadExecutor;
import org.esa.snap.core.util.ThreadRunnable;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;

import java.awt.Rectangle;
import java.util.Map;

/**
 * Unsupervised H/&alpha; Wishart classifier for compact-pol C2 covariance
 * input. Specialises {@link HAlphaWishartC2} by replacing the initial
 * cluster-centre computation with a compact-pol-aware Stokes-vector
 * pipeline (Cloude, Goodenough &amp; Chen, "Compact Decomposition Theory",
 * IEEE Geoscience and Remote Sensing Letters 9(1), Jan 2012).
 */
public class CP_HAlphaWishartC2 extends HAlphaWishartC2 implements CompactPolProcessor {

    private final String compactMode;
    private final boolean useRCMConvention;

    public CP_HAlphaWishartC2(final PolBandUtils.MATRIX srcProductType,
                              final int srcWidth, final int srcHeight, final String compactMode,
                              final int windowSizeX, final int windowSizeY,
                              final Map<Band, PolBandUtils.PolSourceBand> bandMap,
                              final int maxIterations,
                              final PolarimetricClassificationOp op) {
        super(srcProductType, srcWidth, srcHeight, windowSizeX, windowSizeY, bandMap, maxIterations, op);
        this.compactMode = compactMode;
        // The new PolBandUtils API no longer exposes useRCMConvention(); the
        // CP_HAlpha helpers tolerate a false value (the convention only
        // affects downstream PedestalHeight / RVI extraction which is not
        // yet implemented).
        this.useRCMConvention = false;
    }

    @Override
    protected void computeInitialClusterCenters(final int targetBandIndex,
                                                final PolBandUtils.PolSourceBand srcBandList,
                                                final Rectangle[] tileRectangles,
                                                final PolarimetricClassificationOp op) {

        final StatusProgressMonitor status = new StatusProgressMonitor(StatusProgressMonitor.TYPE.SUBTASK);
        status.beginTask("Computing Initial Cluster Centres... ", tileRectangles.length);

        final double[][][] sumRe = new double[9][2][2];
        final double[][][] sumIm = new double[9][2][2];
        final double[][][] centerRe = new double[9][2][2];
        final double[][][] centerIm = new double[9][2][2];
        final int[] counter = new int[9];
        final Double noDataValue = srcBandList.srcBands[0].getNoDataValue();

        final ThreadExecutor executor = new ThreadExecutor();

        try {
            for (final Rectangle rectangle : tileRectangles) {
                op.checkIfCancelled();

                final ThreadRunnable worker = new ThreadRunnable() {

                    final Tile[] sourceTiles = new Tile[srcBandList.srcBands.length];
                    final ProductData[] dataBuffers = new ProductData[srcBandList.srcBands.length];
                    final double[][] Cr = new double[2][2];
                    final double[][] Ci = new double[2][2];
                    final double[] g = new double[4];

                    @Override
                    public void process() {
                        final int x0 = rectangle.x;
                        final int y0 = rectangle.y;
                        final int w = rectangle.width;
                        final int h = rectangle.height;
                        final int xMax = x0 + w;
                        final int yMax = y0 + h;

                        final Rectangle sourceRectangle = getSourceRectangle(x0, y0, w, h);
                        for (int i = 0; i < sourceTiles.length; ++i) {
                            sourceTiles[i] = op.getSourceTile(srcBandList.srcBands[i], sourceRectangle);
                            dataBuffers[i] = sourceTiles[i].getDataBuffer();
                        }
                        final TileIndex srcIndex = new TileIndex(sourceTiles[0]);

                        for (int y = y0; y < yMax; ++y) {
                            srcIndex.calculateStride(y);
                            for (int x = x0; x < xMax; ++x) {
                                if (noDataValue.equals(dataBuffers[0].getElemDoubleAt(srcIndex.getIndex(x))))
                                    continue;

                                getMeanCovarianceMatrixC2(x, y, halfWindowSizeX, halfWindowSizeY, srcWidth,
                                        srcHeight, sourceProductType, sourceTiles, dataBuffers, Cr, Ci);

                                StokesParameters.computeCompactPolStokesVector(Cr, Ci, g);

                                final HAlphaC2.HAAlpha data = CP_HAlpha.computeHAAlphaByT3(Cr, Ci, g, compactMode);

                                if (!Double.isNaN(data.entropy) && !Double.isNaN(data.anisotropy) && !Double.isNaN(data.alpha)) {
                                    synchronized (counter) {
                                        final int zoneIndex = HaAlphaDescriptor.getZoneIndex(data.entropy, data.alpha,
                                                useLeeHAlphaPlaneDefinition);
                                        counter[zoneIndex - 1] += 1;
                                        computeSummationOfC2(zoneIndex, Cr, Ci, sumRe, sumIm);
                                    }
                                }
                            }
                        }
                    }
                };
                executor.execute(worker);

                status.worked(1);
            }

            executor.complete();

            for (int z = 0; z < 9; ++z) {
                final int count = counter[z];
                if (count > 0) {
                    for (int i = 0; i < 2; ++i) {
                        for (int j = 0; j < 2; ++j) {
                            centerRe[z][i][j] = sumRe[z][i][j] / count;
                            centerIm[z][i][j] = sumIm[z][i][j] / count;
                        }
                    }
                    clusterCenters[targetBandIndex][z] = new ClusterInfo();
                    clusterCenters[targetBandIndex][z].setClusterCenter(z + 1, centerRe[z], centerIm[z], counter[z]);
                }
            }

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(op.getId() + " computeInitialClusterCenters ", e);
        } finally {
            status.done();
        }
    }
}
