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
package org.csa.rstb.classification.gpf.classifiers;

import org.csa.rstb.classification.gpf.PolarimetricClassificationOp;
import org.csa.rstb.polarimetric.gpf.support.QuadPolProcessor;
import org.csa.rstb.polarimetric.gpf.decompositions.FreemanDurden;
import eu.esa.sar.commons.polsar.PolBandUtils;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.IndexCoding;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.dataop.downloadable.StatusProgressMonitor;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.util.ThreadExecutor;
import org.esa.snap.core.util.ThreadRunnable;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

/**

 */
public class FreemanDurdenWishart extends PolClassifierBase implements PolClassifier, QuadPolProcessor {

    private final static String TERRAIN_CLASS = "Freeman_Durden_wishart_class";
    private final int numInitialClusters; // number of initial clusters in each category

    private boolean clusterCentersComputed = false;
    private final int maxIterations;
    private final int numFinalClasses;

    private enum Categories {vol, dbl, suf, mix}

    private Categories[][] category = null; // pixel category index
    private int[][] cluster = null; // pixel cluster index

    private final double mixedCategoryThreshold;
    private int maxClusterSize = 0;

    private int[] pvColourIndexMap = null;
    private int[] pdColourIndexMap = null;
    private int[] psColourIndexMap = null;

    public FreemanDurdenWishart(final PolBandUtils.MATRIX srcProductType,
                                final int srcWidth, final int srcHeight, final int windowSize,
                                final Map<Band, PolBandUtils.PolSourceBand> bandMap,
                                final int maxIterations, final int numInitialClasses, final int numClasses,
                                final double mixedCategoryThreshold,
                                final PolarimetricClassificationOp op) {
        super(srcProductType, srcWidth, srcHeight, windowSize, windowSize, bandMap, op);
        this.maxIterations = maxIterations;
        this.numFinalClasses = numClasses;
        this.numInitialClusters = numInitialClasses / 3;
        this.mixedCategoryThreshold = mixedCategoryThreshold;
    }

    @Override
    public boolean canProcessStacks() {
        return false;
    }

    /**
     * Return the band name for the target product
     *
     * @return band name
     */
    public String getTargetBandName() {
        return TERRAIN_CLASS;
    }

    /**
     * returns the number of classes
     *
     * @return num classes
     */
    public int getNumClasses() {
        return numFinalClasses;
    }

    public IndexCoding createIndexCoding() {
        final IndexCoding indexCoding = new IndexCoding("Cluster_classes");
        indexCoding.addIndex("no data", HAlphaWishart.NODATACLASS, "no data");

        // ps [1,30], pv [31, 60] and pd [61,90]
        for (int i = 1; i <= numInitialClusters; i++) {
            indexCoding.addIndex("surf_" + i, i, "Surface " + i);
        }
        for (int i = numInitialClusters + 1; i <= 2 * numInitialClusters; i++) {
            indexCoding.addIndex("vol_" + i, i, "Volume " + i);
        }
        for (int i = 2 * numInitialClusters + 1; i <= 3 * numInitialClusters; i++) {
            indexCoding.addIndex("dbl_" + i, i, "Double " + i);
        }
        return indexCoding;
    }

    /**
     * Perform decomposition for given tile.
     *
     * @param targetBand The target band.
     * @param targetTile The current tile associated with the target band to be computed.
     * @throws OperatorException If an error occurs during computation of the filtered value.
     */
    public void computeTile(final Band targetBand, final Tile targetTile) {
        PolBandUtils.PolSourceBand srcBandList = bandMap.get(targetBand);

        if (!clusterCentersComputed) {
            computeTerrainClusterCenters(srcBandList, op);
        }

        final Rectangle targetRectangle = targetTile.getRectangle();
        final int x0 = targetRectangle.x;
        final int y0 = targetRectangle.y;
        final int w = targetRectangle.width;
        final int h = targetRectangle.height;
        final int maxY = y0 + h;
        final int maxX = x0 + w;
        final ProductData targetData = targetTile.getDataBuffer();
        final TileIndex trgIndex = new TileIndex(targetTile);
        //System.out.println("x0 = " + x0 + ", y0 = " + y0 + ", w = " + w + ", h = " + h);

        for (int y = y0; y < maxY; ++y) {
            trgIndex.calculateStride(y);
            for (int x = x0; x < maxX; ++x) {
                targetData.setElemIntAt(trgIndex.getIndex(x), getOutputClusterIndex(x, y));
            }
        }
    }

    /**
     * Compute centers for all numClasses clusters
     *
     * @param srcBandList the input bands
     * @param op          the operator
     */
    private synchronized void computeTerrainClusterCenters(final PolBandUtils.PolSourceBand srcBandList,
                                                           final PolarimetricClassificationOp op) {

        if (clusterCentersComputed) {
            return;
        }

        category = new Categories[srcHeight][srcWidth];
        cluster = new int[srcHeight][srcWidth];
        final double[][] fdd = new double[srcHeight][srcWidth];
        final java.util.List<ClusterInfo> pvCenterList = new ArrayList<>(numInitialClusters);
        final java.util.List<ClusterInfo> pdCenterList = new ArrayList<>(numInitialClusters);
        final java.util.List<ClusterInfo> psCenterList = new ArrayList<>(numInitialClusters);

        maxClusterSize = 2 * srcHeight * srcWidth / numFinalClasses;

        final Dimension tileSize = new Dimension(256, 256);
        final Rectangle[] tileRectangles = OperatorUtils.getAllTileRectangles(op.getSourceProduct(), tileSize, 0);

        computeInitialTerrainClusterCenters(
                fdd, pvCenterList, pdCenterList, psCenterList, srcBandList, tileRectangles, op);

        computeFinalTerrainClusterCenters(
                fdd, pvCenterList, pdCenterList, psCenterList, srcBandList, tileRectangles, op);

        clusterCentersComputed = true;
    }

    /**
     * Compute initial cluster centers for clusters in all 3 categories: vol, dbl, suf.
     *
     * @param srcBandList    the input bands
     * @param tileRectangles Array of rectangles for all source tiles of the image
     * @param op             the operator
     */
    private void computeInitialTerrainClusterCenters(final double[][] fdd,
                                                     final java.util.List<ClusterInfo> pvCenterList,
                                                     final java.util.List<ClusterInfo> pdCenterList,
                                                     final java.util.List<ClusterInfo> psCenterList,
                                                     final PolBandUtils.PolSourceBand srcBandList,
                                                     final Rectangle[] tileRectangles,
                                                     final PolarimetricClassificationOp op) {

        try {
            // Step 1. Create initial 30 clusters in each of the 3 categories (vol, dbl, surf).
            //System.out.println("Step 1");
            createInitialClusters(fdd, srcBandList, tileRectangles, op);

            // Step 2. Compute cluster centers for all 90 clusters in the 3 categories
            //System.out.println("Step 2");
            getClusterCenters(pvCenterList, pdCenterList, psCenterList, srcBandList, tileRectangles, op);

            // Step 3. Merge small clusters in each category until user specified total number of clusters is reached
            //System.out.println("Step 3");
            mergeInitialClusters(pvCenterList, pdCenterList, psCenterList);

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(op.getId() + " computeInitialClusterCenters ", e);
        }
    }

    /**
     * Create 30 initial clusters in each of the 3 categories (vol, dbl and surf).
     * The pixels are first classified into 4 categories (vol, dbl, urf and mixed) based on its Freeman-Durden
     * decomposition result. Then pixels in each category (not include mixed) are grouped into 30 clusters based
     * on their power values.
     *
     * @param srcBandList    the input bands
     * @param tileRectangles Array of rectangles for all source tiles of the image
     * @param op             the operator
     */
    private void createInitialClusters(final double[][] fdd,
                                       final PolBandUtils.PolSourceBand srcBandList,
                                       final Rectangle[] tileRectangles,
                                       final PolarimetricClassificationOp op) {

        // Here fdd[][] is used in recording the dominant power of the Freeman-Durden decomposition result
        // for each pixel.

        final StatusProgressMonitor status = new StatusProgressMonitor(StatusProgressMonitor.TYPE.SUBTASK);
        status.beginTask("Creating Initial Clusters... ", tileRectangles.length);

        final int[] counter = new int[4]; // number of pixels in each of the 4 categories: vol, dbl, suf, mix

        final ThreadExecutor executor = new ThreadExecutor();

        final double[] pv = new double[srcHeight * srcWidth];
        final double[] pd = new double[srcHeight * srcWidth];
        final double[] ps = new double[srcHeight * srcWidth];

        try {
            for (final Rectangle rectangle : tileRectangles) {
                op.checkIfCancelled();

                final ThreadRunnable worker = new ThreadRunnable() {

                    final Tile[] sourceTiles = new Tile[srcBandList.srcBands.length];
                    final ProductData[] dataBuffers = new ProductData[srcBandList.srcBands.length];

                    @Override
                    public void process() {
                        final int x0 = rectangle.x;
                        final int y0 = rectangle.y;
                        final int w = rectangle.width;
                        final int h = rectangle.height;
                        final int xMax = x0 + w;
                        final int yMax = y0 + h;
                        //System.out.println("x0 = " + x0 + ", y0 = " + y0 + ", w = " + w + ", h = " + h);

                        final Rectangle sourceRectangle = getSourceRectangle(x0, y0, w, h);
                        for (int i = 0; i < sourceTiles.length; ++i) {
                            sourceTiles[i] = op.getSourceTile(srcBandList.srcBands[i], sourceRectangle);
                            dataBuffers[i] = sourceTiles[i].getDataBuffer();
                        }

                        final double[][] Cr = new double[3][3];
                        final double[][] Ci = new double[3][3];

                        for (int y = y0; y < yMax; ++y) {
                            for (int x = x0; x < xMax; ++x) {

                                getMeanCovarianceMatrix(x, y, halfWindowSizeX, halfWindowSizeY,
                                        sourceProductType, sourceTiles, dataBuffers, Cr, Ci);

                                final FreemanDurden.FDD data = FreemanDurden.getFreemanDurdenDecomposition(Cr, Ci);

                                synchronized (counter) {

                                    if (!Double.isNaN(data.pv) && !Double.isNaN(data.pd) && !Double.isNaN(data.ps)) {
                                        category[y][x] = getCategory(data.pv, data.pd, data.ps, mixedCategoryThreshold);
                                        if (category[y][x] == Categories.vol) {
                                            fdd[y][x] = data.pv;
                                            pv[counter[0]] = data.pv;
                                            counter[0] += 1;
                                        } else if (category[y][x] == Categories.dbl) {
                                            fdd[y][x] = data.pd;
                                            pd[counter[1]] = data.pd;
                                            counter[1] += 1;
                                        } else if (category[y][x] == Categories.suf) {
                                            fdd[y][x] = data.ps;
                                            ps[counter[2]] = data.ps;
                                            counter[2] += 1;
                                        } else { // Categories.mix
                                            fdd[y][x] = (data.pv + data.pd + data.ps) / 3.0;
                                            counter[3] += 1;
                                        }
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

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(op.getId() + " createInitialClusters ", e);
        } finally {
            status.done();
        }

        // for each category, compute 29 thresholds which will be used later in dividing each category into 30 small
        // clusters with roughly equal number of pixels based on the pixel values.
        final int pvClusterSize = counter[0] / numInitialClusters;
        final int pdClusterSize = counter[1] / numInitialClusters;
        final int psClusterSize = counter[2] / numInitialClusters;

        if (pvClusterSize > 0) {
            Arrays.sort(pv, 0, counter[0] - 1);
        }
        if (pdClusterSize > 0) {
            Arrays.sort(pd, 0, counter[1] - 1);
        }
        if (psClusterSize > 0) {
            Arrays.sort(ps, 0, counter[2] - 1);
        }

        final double[] pvThreshold = new double[numInitialClusters - 1];
        final double[] pdThreshold = new double[numInitialClusters - 1];
        final double[] psThreshold = new double[numInitialClusters - 1];
        for (int i = 0; i < numInitialClusters - 1; i++) {
            pvThreshold[i] = pv[(i + 1) * pvClusterSize];
            pdThreshold[i] = pd[(i + 1) * pdClusterSize];
            psThreshold[i] = ps[(i + 1) * psClusterSize];
        }

        // classify pixels into clusters within each category, record number of pixels in each cluster
        for (int y = 0; y < srcHeight; y++) {
            for (int x = 0; x < srcWidth; x++) {
                if (category[y][x] == Categories.vol) {
                    cluster[y][x] = computePixelClusterIdx(fdd[y][x], pvThreshold, numInitialClusters);
                } else if (category[y][x] == Categories.dbl) {
                    cluster[y][x] = computePixelClusterIdx(fdd[y][x], pdThreshold, numInitialClusters);
                } else if (category[y][x] == Categories.suf) {
                    cluster[y][x] = computePixelClusterIdx(fdd[y][x], psThreshold, numInitialClusters);
                }
            }
        }
    }

    /**
     * Compute the centers of the 90 clusters in the 3 categories.
     *
     * @param srcBandList    the input bands
     * @param tileRectangles Array of rectangles for all source tiles of the image
     * @param op             the operator
     */
    private void getClusterCenters(final java.util.List<ClusterInfo> pvCenterList,
                                   final java.util.List<ClusterInfo> pdCenterList,
                                   final java.util.List<ClusterInfo> psCenterList,
                                   final PolBandUtils.PolSourceBand srcBandList,
                                   final Rectangle[] tileRectangles,
                                   final PolarimetricClassificationOp op) {

        final StatusProgressMonitor status = new StatusProgressMonitor(StatusProgressMonitor.TYPE.SUBTASK);
        status.beginTask("Computing Initial Cluster Centres... ", tileRectangles.length);

        final double[][][] pvSumRe = new double[numInitialClusters][3][3];
        final double[][][] pvSumIm = new double[numInitialClusters][3][3];
        final double[][][] pdSumRe = new double[numInitialClusters][3][3];
        final double[][][] pdSumIm = new double[numInitialClusters][3][3];
        final double[][][] psSumRe = new double[numInitialClusters][3][3];
        final double[][][] psSumIm = new double[numInitialClusters][3][3];

        // counter for the number of pixels in each cluster in the 3 categories: vol, dbl, suf
        final int[][] clusterCounter = new int[3][numInitialClusters];

        final ThreadExecutor executor = new ThreadExecutor();

        try {
            for (final Rectangle rectangle : tileRectangles) {
                op.checkIfCancelled();

                final ThreadRunnable worker = new ThreadRunnable() {

                    final Tile[] sourceTiles = new Tile[srcBandList.srcBands.length];
                    final ProductData[] dataBuffers = new ProductData[srcBandList.srcBands.length];

                    @Override
                    public void process() {
                        final int x0 = rectangle.x;
                        final int y0 = rectangle.y;
                        final int w = rectangle.width;
                        final int h = rectangle.height;
                        final int xMax = x0 + w;
                        final int yMax = y0 + h;
                        //System.out.println("x0 = " + x0 + ", y0 = " + y0 + ", w = " + w + ", h = " + h);

                        for (int i = 0; i < sourceTiles.length; ++i) {
                            sourceTiles[i] = op.getSourceTile(srcBandList.srcBands[i], rectangle);
                            dataBuffers[i] = sourceTiles[i].getDataBuffer();
                        }

                        final double[][] Tr = new double[3][3];
                        final double[][] Ti = new double[3][3];

                        final TileIndex srcIndex = new TileIndex(sourceTiles[0]);
                        for (int y = y0; y < yMax; ++y) {
                            srcIndex.calculateStride(y);
                            for (int x = x0; x < xMax; ++x) {

                                getCoherencyMatrixT3(srcIndex.getIndex(x), sourceProductType, dataBuffers, Tr, Ti);

                                synchronized (clusterCounter) {

                                    if (category[y][x] == Categories.vol) { // pv
                                        computeSummationOfT3(cluster[y][x] + 1, Tr, Ti, pvSumRe, pvSumIm);
                                        clusterCounter[0][cluster[y][x]]++;
                                    } else if (category[y][x] == Categories.dbl) { // pd
                                        computeSummationOfT3(cluster[y][x] + 1, Tr, Ti, pdSumRe, pdSumIm);
                                        clusterCounter[1][cluster[y][x]]++;
                                    } else if (category[y][x] == Categories.suf) { // ps
                                        computeSummationOfT3(cluster[y][x] + 1, Tr, Ti, psSumRe, psSumIm);
                                        clusterCounter[2][cluster[y][x]]++;
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

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(op.getId() + " getClusterCenters ", e);
        } finally {
            status.done();
        }

        // compute centers for all 90 clusters
        for (int c = 0; c < numInitialClusters; c++) {
            double[][] centerRe = new double[3][3];
            double[][] centerIm = new double[3][3];

            if (clusterCounter[0][c] > 0) {
                for (int i = 0; i < 3; i++) {
                    for (int j = 0; j < 3; j++) {
                        centerRe[i][j] = pvSumRe[c][i][j] / clusterCounter[0][c];
                        centerIm[i][j] = pvSumIm[c][i][j] / clusterCounter[0][c];
                    }
                }
                ClusterInfo clusterCenter = new ClusterInfo();
                clusterCenter.setClusterCenter(c, centerRe, centerIm, clusterCounter[0][c]);
                pvCenterList.add(clusterCenter);
            }

            if (clusterCounter[1][c] > 0) {
                for (int i = 0; i < 3; i++) {
                    for (int j = 0; j < 3; j++) {
                        centerRe[i][j] = pdSumRe[c][i][j] / clusterCounter[1][c];
                        centerIm[i][j] = pdSumIm[c][i][j] / clusterCounter[1][c];
                    }
                }
                ClusterInfo clusterCenter = new ClusterInfo();
                clusterCenter.setClusterCenter(c, centerRe, centerIm, clusterCounter[1][c]);
                pdCenterList.add(clusterCenter);
            }

            if (clusterCounter[2][c] > 0) {
                for (int i = 0; i < 3; i++) {
                    for (int j = 0; j < 3; j++) {
                        centerRe[i][j] = psSumRe[c][i][j] / clusterCounter[2][c];
                        centerIm[i][j] = psSumIm[c][i][j] / clusterCounter[2][c];
                    }
                }
                ClusterInfo clusterCenter = new ClusterInfo();
                clusterCenter.setClusterCenter(c, centerRe, centerIm, clusterCounter[2][c]);
                psCenterList.add(clusterCenter);
            }
        }
    }

    /**
     * Merge clusters in each category until user specified total number of clusters is reached.
     */
    private void mergeInitialClusters(final java.util.List<ClusterInfo> pvCenterList,
                                      final java.util.List<ClusterInfo> pdCenterList,
                                      final java.util.List<ClusterInfo> psCenterList) {

        int totalNumClasses = pvCenterList.size() + pdCenterList.size() + psCenterList.size();
        while (totalNumClasses > numFinalClasses) {

            final int[] pvClusterPair = new int[2];
            final int[] pdClusterPair = new int[2];
            final int[] psClusterPair = new int[2];

            // compute the shortest wishart distance between clusters in each category
            final double pvShortestDistance = computeShortestDistance(pvCenterList, pvClusterPair);
            final double pdShortestDistance = computeShortestDistance(pdCenterList, pdClusterPair);
            final double psShortestDistance = computeShortestDistance(psCenterList, psClusterPair);
            //System.out.println("totalNumClasses: " + totalNumClasses +", pvD = " + pvShortestDistance +
            //        ", pdD = " + pdShortestDistance + ", psD = " + psShortestDistance);

            // compare the 3 shortest distances for the 3 categories and merge the shortest one
            if (pvShortestDistance < pdShortestDistance && pvShortestDistance < psShortestDistance) {
                mergeClusters(pvCenterList, pvClusterPair);
                //System.out.println("totalNumClasses: " + totalNumClasses + ", merge pv clusters (" +
                //        pvClusterPair[0] + ", " + pvClusterPair[1] + ')');
            } else if (pdShortestDistance < pvShortestDistance && pdShortestDistance < psShortestDistance) {
                mergeClusters(pdCenterList, pdClusterPair);
                //System.out.println("totalNumClasses: " + totalNumClasses + ", merge pd clusters (" +
                //        pdClusterPair[0] + ", " + pdClusterPair[1] + ')');
            } else if (psShortestDistance < pvShortestDistance && psShortestDistance < pdShortestDistance) {
                mergeClusters(psCenterList, psClusterPair);
                //System.out.println("totalNumClasses: " + totalNumClasses + ", merge ps clusters (" +
                //        psClusterPair[0] + ", " + psClusterPair[1] + ')');
            } else if (pvShortestDistance > pdShortestDistance && pvShortestDistance > psShortestDistance) {
                // i.e. pdShortestDistance == psShortestDistance
                if (pdCenterList.get(pdClusterPair[0]).size + pdCenterList.get(pdClusterPair[1]).size <=
                        psCenterList.get(psClusterPair[0]).size + psCenterList.get(psClusterPair[1]).size) {
                    mergeClusters(pdCenterList, pdClusterPair);
                    //System.out.println("totalNumClasses: " + totalNumClasses + ", merge pd clusters (" +
                    //        pdClusterPair[0] + ", " + pdClusterPair[1] + ')');
                } else {
                    mergeClusters(psCenterList, psClusterPair);
                    //System.out.println("totalNumClasses: " + totalNumClasses + ", merge ps clusters (" +
                    //        psClusterPair[0] + ", " + psClusterPair[1] + ')');
                }

            } else if (pdShortestDistance > pvShortestDistance && pdShortestDistance > psShortestDistance) {
                // i.e. pvShortestDistance == psShortestDistance
                if (pvCenterList.get(pvClusterPair[0]).size + pvCenterList.get(pvClusterPair[1]).size <=
                        psCenterList.get(psClusterPair[0]).size + psCenterList.get(psClusterPair[1]).size) {
                    mergeClusters(pvCenterList, pvClusterPair);
                    //System.out.println("totalNumClasses: " + totalNumClasses + ", merge pv clusters (" +
                    //        pvClusterPair[0] + ", " + pvClusterPair[1] + ')');
                } else {
                    mergeClusters(psCenterList, psClusterPair);
                    //System.out.println("totalNumClasses: " + totalNumClasses + ", merge ps clusters (" +
                    //        psClusterPair[0] + ", " + psClusterPair[1] + ')');
                }

            } else if (psShortestDistance > pvShortestDistance && psShortestDistance > pdShortestDistance) {
                // i.e. pdShortestDistance == pvShortestDistance
                if (pdCenterList.get(pdClusterPair[0]).size + pdCenterList.get(pdClusterPair[1]).size <=
                        pvCenterList.get(pvClusterPair[0]).size + pvCenterList.get(pvClusterPair[1]).size) {
                    mergeClusters(pdCenterList, pdClusterPair);
                    //System.out.println("totalNumClasses: " + totalNumClasses + ", merge pd clusters (" +
                    //        pdClusterPair[0] + ", " + pdClusterPair[1] + ')');
                } else {
                    mergeClusters(pvCenterList, pvClusterPair);
                    //System.out.println("totalNumClasses: " + totalNumClasses + ", merge pv clusters (" +
                    //        pvClusterPair[0] + ", " + pvClusterPair[1] + ')');
                }

            } else {
                // i.e. pvShortestDistance == pdShortestDistance == psShortestDistance
                final int pvNewClusterSize = pvCenterList.get(pvClusterPair[0]).size +
                        pvCenterList.get(pvClusterPair[1]).size;

                final int pdNewClusterSize = pdCenterList.get(pdClusterPair[0]).size +
                        pdCenterList.get(pdClusterPair[1]).size;

                final int psNewClusterSize = psCenterList.get(psClusterPair[0]).size +
                        psCenterList.get(psClusterPair[1]).size;

                if (pvNewClusterSize <= pdNewClusterSize && pvNewClusterSize <= psNewClusterSize) {
                    mergeClusters(pvCenterList, pvClusterPair);
                    //System.out.println("totalNumClasses: " + totalNumClasses + ", merge pv clusters (" +
                    //        pvClusterPair[0] + ", " + pvClusterPair[1] + ')');
                } else if (pdNewClusterSize <= pvNewClusterSize && pdNewClusterSize <= psNewClusterSize) {
                    mergeClusters(pdCenterList, pdClusterPair);
                    //System.out.println("totalNumClasses: " + totalNumClasses + ", merge pd clusters (" +
                    //        pdClusterPair[0] + ", " + pdClusterPair[1] + ')');
                } else {
                    mergeClusters(psCenterList, psClusterPair);
                    //System.out.println("totalNumClasses: " + totalNumClasses + ", merge ps clusters (" +
                    //        psClusterPair[0] + ", " + psClusterPair[1] + ')');
                }
            }

            // update the total number of clusters
            totalNumClasses--;
            //System.out.println("Total # of Classes: " + totalNumClasses);
        }
        //System.out.println("Step 3 end");
    }

    /**
     * Get the dominant category for given pixel based on its Freeman-Durden decomposition result.
     *
     * @param pv The power of volume.
     * @param pd The power of double-bounce.
     * @param ps The power of surface.
     * @return The dominant category.
     */
    private static Categories getCategory(final double pv, final double pd, final double ps,
                                          final double mixedCategoryThreshold) {

        Categories dominantCategory = Categories.mix;
        double dominantValue = 0.0;

        final double sum = pv + pd + ps;
        if (sum == 0) {
            return Categories.mix;
        }

        if (pv >= pd && pv >= ps) {
            dominantCategory = Categories.vol;
            dominantValue = pv;
        } else if (pd >= pv && pd >= ps) {
            dominantCategory = Categories.dbl;
            dominantValue = pd;
        } else if (ps >= pv && ps >= pd) {
            dominantCategory = Categories.suf;
            dominantValue = ps;
        }

        if (dominantValue / sum <= mixedCategoryThreshold) {
            return Categories.mix;
        } else {
            return dominantCategory;
        }
    }

    /**
     * Classify a pixel with a given power to one of the clusters defined by an array of thresholds.
     *
     * @param value     The power value.
     * @param threshold Array of thresholds.
     * @return Index of the cluster that the pixel is classified into.
     */
    private static int computePixelClusterIdx(
            final double value, final double[] threshold, final int numInitialClusters) {
        for (int i = 0; i < numInitialClusters - 1; i++) {
            if (value < threshold[i]) {
                return i;
            }
        }
        return numInitialClusters - 1;
    }

    /**
     * Find the two clusters with the shortest Wishart distance among all clusters in the given cluster list.
     *
     * @param clusterCenterList The cluster list.
     * @param clusterPair       The indices of the two clusters with the shortest Wishart distance.
     * @return The shortest Wishart distance.
     */
    private double computeShortestDistance(final java.util.List<ClusterInfo> clusterCenterList, final int[] clusterPair) {

        final int numClusters = clusterCenterList.size();
        double shortestDistance = Double.MAX_VALUE;
        if (numClusters <= 3) {
            return shortestDistance;
        }

        double d;
        for (int i = 0; i < numClusters - 1; i++) {
            if (clusterCenterList.get(i).size >= maxClusterSize) {
                continue;
            }

            for (int j = i + 1; j < numClusters; j++) {
                if (clusterCenterList.get(j).size >= maxClusterSize) {
                    continue;
                }

                d = HAlphaWishart.computeWishartDistance(
                        clusterCenterList.get(i).centerRe, clusterCenterList.get(i).centerIm, clusterCenterList.get(j));

                if (d < shortestDistance) {
                    shortestDistance = d;
                    clusterPair[0] = i;
                    clusterPair[1] = j;
                }
            }
        }
        return shortestDistance;
    }

    /**
     * Merge two clusters in the cluster list for given cluster indices.
     *
     * @param clusterCenterList The list of cluster centers.
     * @param clusterPair       Indices of the two clusters to merge.
     */
    private static void mergeClusters(final java.util.List<ClusterInfo> clusterCenterList, final int[] clusterPair) {

        final int idx1 = clusterPair[0];
        final int idx2 = clusterPair[1];
        final int size1 = clusterCenterList.get(idx1).size;
        final int size2 = clusterCenterList.get(idx2).size;
        final int newClusterSize = size1 + size2;

        final double[][] newCenterRe = new double[3][3];
        final double[][] newCenterIm = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                newCenterRe[i][j] = (size1 * clusterCenterList.get(idx1).centerRe[i][j] +
                        size2 * clusterCenterList.get(idx2).centerRe[i][j]) / newClusterSize;

                newCenterIm[i][j] = (size1 * clusterCenterList.get(idx1).centerIm[i][j] +
                        size2 * clusterCenterList.get(idx2).centerIm[i][j]) / newClusterSize;
            }
        }

        if (idx1 < idx2) {
            clusterCenterList.remove(idx2);
            clusterCenterList.remove(idx1);
        } else {
            clusterCenterList.remove(idx1);
            clusterCenterList.remove(idx2);
        }

        ClusterInfo clusterCenter = new ClusterInfo();
        clusterCenter.setClusterCenter(clusterCenterList.size(), newCenterRe, newCenterIm, newClusterSize);
        clusterCenterList.add(clusterCenter);
    }

    /**
     * Compute final cluster centers for all clusters using K-mean clustering method
     *
     * @param srcBandList    the input bands
     * @param tileRectangles Array of rectangles for all source tiles of the image
     * @param op             the operator
     */
    private void computeFinalTerrainClusterCenters(final double[][] fdd,
                                                   final java.util.List<ClusterInfo> pvCenterList,
                                                   final java.util.List<ClusterInfo> pdCenterList,
                                                   final java.util.List<ClusterInfo> psCenterList,
                                                   final PolBandUtils.PolSourceBand srcBandList,
                                                   final Rectangle[] tileRectangles,
                                                   final PolarimetricClassificationOp op) {

        boolean endIteration = false;

        final StatusProgressMonitor status = new StatusProgressMonitor(StatusProgressMonitor.TYPE.SUBTASK);
        status.beginTask("Computing Final Cluster Centres... ", tileRectangles.length * maxIterations);

        final int pvNumClusters = pvCenterList.size();
        final int pdNumClusters = pdCenterList.size();
        final int psNumClusters = psCenterList.size();

        final int maxNumClusters = Math.max(pvNumClusters, Math.max(pdNumClusters, psNumClusters));
        final int[][] clusterCounter = new int[3][maxNumClusters];

        try {
            for (int it = 0; (it < maxIterations && !endIteration); ++it) {
                //System.out.println("Iteration: " + it);

//                final long startTime = System.nanoTime();
//                final long endTime;
                final double[][][] pvSumRe = new double[pvNumClusters][3][3];
                final double[][][] pvSumIm = new double[pvNumClusters][3][3];
                final double[][][] pdSumRe = new double[pdNumClusters][3][3];
                final double[][][] pdSumIm = new double[pdNumClusters][3][3];
                final double[][][] psSumRe = new double[psNumClusters][3][3];
                final double[][][] psSumIm = new double[psNumClusters][3][3];

                java.util.Arrays.fill(clusterCounter[0], 0);
                java.util.Arrays.fill(clusterCounter[1], 0);
                java.util.Arrays.fill(clusterCounter[2], 0);

                final ThreadExecutor executor = new ThreadExecutor();

                for (final Rectangle rectangle : tileRectangles) {

                    final ThreadRunnable worker = new ThreadRunnable() {

                        final Tile[] sourceTiles = new Tile[srcBandList.srcBands.length];
                        final ProductData[] dataBuffers = new ProductData[srcBandList.srcBands.length];

                        @Override
                        public void process() {
                            op.checkIfCancelled();

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

                            final double[][] Tr = new double[3][3];
                            final double[][] Ti = new double[3][3];

                            for (int y = y0; y < yMax; ++y) {
                                for (int x = x0; x < xMax; ++x) {

                                    getMeanCoherencyMatrix(
                                            x, y, halfWindowSizeX, halfWindowSizeY, srcWidth, srcHeight,
                                            sourceProductType, srcIndex, dataBuffers, Tr, Ti);

                                    synchronized (clusterCounter) {

                                        if (category[y][x] == Categories.vol) { // pv
                                            cluster[y][x] = findClosestCluster(Tr, Ti, pvCenterList);
                                            computeSummationOfT3(cluster[y][x] + 1, Tr, Ti, pvSumRe, pvSumIm);
                                            clusterCounter[0][cluster[y][x]] += 1;

                                        } else if (category[y][x] == Categories.dbl) { // pd
                                            cluster[y][x] = findClosestCluster(Tr, Ti, pdCenterList);
                                            computeSummationOfT3(cluster[y][x] + 1, Tr, Ti, pdSumRe, pdSumIm);
                                            clusterCounter[1][cluster[y][x]] += 1;

                                        } else if (category[y][x] == Categories.suf) { // ps
                                            cluster[y][x] = findClosestCluster(Tr, Ti, psCenterList);
                                            computeSummationOfT3(cluster[y][x] + 1, Tr, Ti, psSumRe, psSumIm);
                                            clusterCounter[2][cluster[y][x]] += 1;

                                        } else { // mixed

                                            final int nearestPvCluster = findClosestCluster(Tr, Ti, pvCenterList);
                                            final int nearestPdCluster = findClosestCluster(Tr, Ti, pdCenterList);
                                            final int nearestPsCluster = findClosestCluster(Tr, Ti, psCenterList);

                                            final double dPv = HAlphaWishart.computeWishartDistance(
                                                    Tr, Ti, pvCenterList.get(nearestPvCluster));

                                            final double dPd = HAlphaWishart.computeWishartDistance(
                                                    Tr, Ti, pdCenterList.get(nearestPdCluster));

                                            final double dPs = HAlphaWishart.computeWishartDistance(
                                                    Tr, Ti, psCenterList.get(nearestPsCluster));

                                            if (dPv <= dPd && dPv <= dPs) { // pv
                                                cluster[y][x] = nearestPvCluster;
                                                computeSummationOfT3(cluster[y][x] + 1, Tr, Ti, pvSumRe, pvSumIm);
                                                clusterCounter[0][cluster[y][x]] += 1;
                                                category[y][x] = Categories.vol;

                                            } else if (dPd <= dPv && dPd <= dPs) { // pd
                                                cluster[y][x] = nearestPdCluster;
                                                computeSummationOfT3(cluster[y][x] + 1, Tr, Ti, pdSumRe, pdSumIm);
                                                clusterCounter[1][cluster[y][x]] += 1;
                                                category[y][x] = Categories.dbl;

                                            } else { // ps
                                                cluster[y][x] = nearestPsCluster;
                                                computeSummationOfT3(cluster[y][x] + 1, Tr, Ti, psSumRe, psSumIm);
                                                clusterCounter[2][cluster[y][x]] += 1;
                                                category[y][x] = Categories.suf;
                                            }
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

                /*
                endTime = System.nanoTime();
                final long duration = endTime - startTime;
                System.out.println("duration = " + duration);
                */
                updateClusterCenter(pvCenterList, clusterCounter[0], pvSumRe, pvSumIm);
                updateClusterCenter(pdCenterList, clusterCounter[1], pdSumRe, pdSumIm);
                updateClusterCenter(psCenterList, clusterCounter[2], psSumRe, psSumIm);
            }
            /*
            System.out.println("# of clusters in Pv: " + pvNumClusters);
            System.out.print("Pixels in each Pv cluster: ");
            for (int i = 0; i < pvNumClusters; i++) {
                System.out.print(clusterCounter[0][i] + ", ");
            }
            System.out.println();
            System.out.println("# of clusters in Pd: " + pdNumClusters);
            System.out.print("Pixels in each Pd cluster: ");
            for (int i = 0; i < pdNumClusters; i++) {
                System.out.print(clusterCounter[1][i] + ", ");
            }
            System.out.println();
            System.out.println("# of clusters in Ps: " + psNumClusters);
            System.out.print("Pixels in each Ps cluster: ");
            for (int i = 0; i < psNumClusters; i++) {
                System.out.print(clusterCounter[2][i] + ", ");
            }
            System.out.println();
            */

            // todo the average cluster power should be used in colour selection for each cluster, not used now
            // compute average power for each cluster
            final double[] pvAvgClusterPower = new double[pvNumClusters];
            final double[] pdAvgClusterPower = new double[pdNumClusters];
            final double[] psAvgClusterPower = new double[psNumClusters];

            for (int y = 0; y < srcHeight; y++) {
                for (int x = 0; x < srcWidth; x++) {
                    if (category[y][x] == Categories.vol) { // pv
                        pvAvgClusterPower[cluster[y][x]] += fdd[y][x];
                    } else if (category[y][x] == Categories.dbl) { // pd
                        pdAvgClusterPower[cluster[y][x]] += fdd[y][x];
                    } else { // ps
                        psAvgClusterPower[cluster[y][x]] += fdd[y][x];
                    }
                }
            }

            for (int c = 0; c < pvNumClusters; c++) {
                pvAvgClusterPower[c] /= clusterCounter[0][c];
            }

            for (int c = 0; c < pdNumClusters; c++) {
                pdAvgClusterPower[c] /= clusterCounter[1][c];
            }

            for (int c = 0; c < psNumClusters; c++) {
                psAvgClusterPower[c] /= clusterCounter[2][c];
            }

            // map cluster index to colour index, colour index ranges for the 3 categories are given by
            // surf: [0,29], vol: [30, 59] and dbl: [60,89], currently the clusters are evenly distributed in the
            // colour range
            pvColourIndexMap = new int[pvNumClusters];
            pdColourIndexMap = new int[pdNumClusters];
            psColourIndexMap = new int[psNumClusters];
            for (int c = 0; c < pvNumClusters; c++) {
                pvColourIndexMap[c] = numInitialClusters + getColourIndex(c, pvAvgClusterPower, numInitialClusters) + 1;
            }
            for (int c = 0; c < pdNumClusters; c++) {
                pdColourIndexMap[c] = 2 * numInitialClusters + getColourIndex(c, pdAvgClusterPower, numInitialClusters) + 1;
            }
            for (int c = 0; c < psNumClusters; c++) {
                psColourIndexMap[c] = getColourIndex(c, psAvgClusterPower, numInitialClusters) + 1;
            }

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(op.getId() + " computeInitialClusterCenters ", e);
        } finally {
            status.done();
        }
    }

    private static int getColourIndex(
            final int clusterIndex, final double[] pAvgClusterPower, final int numInitialClusters) {
        int n = 0;
        for (double p : pAvgClusterPower) {
            if (p > pAvgClusterPower[clusterIndex]) {
                n++;
            }
        }

        final int d = numInitialClusters / pAvgClusterPower.length;
        return n * d;
    }

    /**
     * Find the nearest cluster for a given T3 matrix using Wishart distance
     *
     * @param Tr             Real part of the T3 matrix
     * @param Ti             Imaginary part of the T3 matrix
     * @param clusterCenters The cluster centers
     * @return The zone index for the nearest cluster
     */
    public static int findClosestCluster(final double[][] Tr, final double[][] Ti,
                                         final java.util.List<ClusterInfo> clusterCenters) {

        double minDistance = Double.MAX_VALUE;
        int clusterIndex = -1;
        for (int c = 0; c < clusterCenters.size(); ++c) {
            final double d = HAlphaWishart.computeWishartDistance(Tr, Ti, clusterCenters.get(c));
            if (minDistance > d) {
                minDistance = d;
                clusterIndex = c;
            }
        }

        return clusterIndex;
    }

    private static void updateClusterCenter(final java.util.List<ClusterInfo> centerList, final int[] clusterCounter,
                                            final double[][][] sumRe, final double[][][] sumIm) {

        for (int c = 0; c < centerList.size(); c++) {
            final double[][] centerRe = new double[3][3];
            final double[][] centerIm = new double[3][3];
            if (clusterCounter[c] > 0) {
                for (int i = 0; i < 3; i++) {
                    for (int j = 0; j < 3; j++) {
                        centerRe[i][j] = sumRe[c][i][j] / clusterCounter[c];
                        centerIm[i][j] = sumIm[c][i][j] / clusterCounter[c];
                    }
                }
                centerList.get(c).setClusterCenter(c, centerRe, centerIm, clusterCounter[c]);
            }
        }
    }

    private int getOutputClusterIndex(final int x, final int y) {

        return category[y][x] == Categories.vol ? pvColourIndexMap[cluster[y][x]] :
                category[y][x] == Categories.dbl ? pdColourIndexMap[cluster[y][x]] :
                        psColourIndexMap[cluster[y][x]];
    }

}
