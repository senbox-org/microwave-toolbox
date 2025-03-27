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
package org.csa.rstb.classification.gpf;

import com.bc.ceres.core.ProgressMonitor;
import org.csa.rstb.classification.gpf.classifiers.HAlphaWishart;
import org.csa.rstb.classification.gpf.classifiers.HAlphaWishartC2;
import org.csa.rstb.classification.gpf.classifiers.PolClassifierBase;
import org.csa.rstb.polarimetric.gpf.support.DualPolProcessor;
import org.csa.rstb.polarimetric.gpf.support.QuadPolProcessor;
import eu.esa.sar.commons.polsar.PolBandUtils;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.IndexCoding;
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
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;
import org.esa.snap.engine_utilities.util.ResourceUtils;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Properties;

/**
 * Perform supervised Wishart classification of a given polarimetric product
 */

@OperatorMetadata(alias = "Supervised-Wishart-Classification",
        category = "Radar/Polarimetric/Polarimetric Classification",
        authors = "Jun Lu, Luis Veci",
        version = "1.0",
        copyright = "Copyright (C) 2014 by Array Systems Computing Inc.",
        description = "Perform supervised Wishart classification")
public final class SupervisedWishartClassificationOp extends Operator implements DualPolProcessor, QuadPolProcessor {

    @SourceProduct(alias = "source")
    private Product sourceProduct;
    @TargetProduct
    private Product targetProduct;

    @Parameter(description = "The training data set file", label = "Training Data Set")
    private File trainingDataSet = null;

    @Parameter(description = "The sliding window size", interval = "(1, 100]", defaultValue = "5", label = "Window Size")
    private int windowSize = 5;
    private int halfWindowSize;

    private int sourceImageWidth = 0;
    private int sourceImageHeight = 0;
    private PolBandUtils.PolSourceBand[] srcBandList;

    private PolBandUtils.MATRIX sourceProductType;

    private PolClassifierBase.ClusterInfo[] clusterCenters = null;
    private String[] classNames = null;
    private int[] clusterToClassMap = null;
    private int numClasses = 0;
    private boolean isDualPol = false;

    private static final String PRODUCT_SUFFIX = "_Class";

    /**
     * Initializes this operator and sets the one and only target product.
     * <p>The target product can be either defined by a field of type {@link Product} annotated with the
     * {@link TargetProduct TargetProduct} annotation or
     * by calling {@link #setTargetProduct} method.</p>
     * <p>The framework calls this method after it has created this operator.
     * Any client code that must be performed before computation of tile data
     * should be placed here.</p>
     *
     * @throws OperatorException If an error occurs during operator initialisation.
     * @see #getTargetProduct()
     */
    @Override
    public void initialize() throws OperatorException {

        try {
            if (!trainingDataSet.exists()) {
                throw new OperatorException("Cannot find training data set file: " + trainingDataSet.getAbsolutePath());
            }

            halfWindowSize = windowSize / 2;

            sourceProductType = PolBandUtils.getSourceProductType(sourceProduct);

            checkProductValidity();

            srcBandList = PolBandUtils.getSourceBands(sourceProduct, sourceProductType);

            getClusterCenters();

            createTargetProduct();

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private void checkProductValidity() {

        if (sourceProductType != PolBandUtils.MATRIX.T3 &&
                sourceProductType != PolBandUtils.MATRIX.C3 &&
                sourceProductType != PolBandUtils.MATRIX.FULL) {

            if (sourceProductType != PolBandUtils.MATRIX.DUAL_HH_HV &&
                    sourceProductType != PolBandUtils.MATRIX.DUAL_VH_VV &&
                    sourceProductType != PolBandUtils.MATRIX.DUAL_HH_VV &&
                    sourceProductType != PolBandUtils.MATRIX.C2) {

                throw new OperatorException("Quad-pol or dual-pol product is expected");

            } else {
                isDualPol = true;
            }
        }
    }

    /**
     * Read cluster centers from training data set file.
     *
     * @throws IOException The I/O exception
     */
    private void getClusterCenters() throws IOException {

        if (isDualPol) { // dual-pol
            getDualPolClusterCenters();
        } else { // quad-pol
            getQuadPolClusterCenters();
        }
    }

    private void getDualPolClusterCenters() throws IOException {

        final Properties clusterCenterProperties = ResourceUtils.loadProperties(trainingDataSet.getAbsolutePath());
        final int numOfClusters = Integer.parseInt(clusterCenterProperties.getProperty("number_of_clusters"));
        clusterCenters = new PolClassifierBase.ClusterInfo[numOfClusters];
        clusterToClassMap = new int[numOfClusters];
        final java.util.List<String> classNameList = new ArrayList<>();

        final double[][] Cr = new double[2][2];
        final double[][] Ci = new double[2][2];
        String currentClassName = "";
        for (int c = 0; c < numOfClusters; c++) {

            final String cluster = "cluster" + c;
            final String clusterName = clusterCenterProperties.getProperty(cluster);
            final String className = clusterName.substring(0, clusterName.lastIndexOf('_'));
            if (!className.equals(currentClassName)) {
                numClasses++;
                currentClassName = className;
                classNameList.add(className);
            }
            clusterToClassMap[c] = numClasses;

            Cr[0][0] = Double.parseDouble(clusterCenterProperties.getProperty(cluster + "_C11"));
            Cr[0][1] = Double.parseDouble(clusterCenterProperties.getProperty(cluster + "_C12_real"));
            Ci[0][1] = Double.parseDouble(clusterCenterProperties.getProperty(cluster + "_C12_imag"));
            Cr[1][1] = Double.parseDouble(clusterCenterProperties.getProperty(cluster + "_C22"));

            clusterCenters[c] = new PolClassifierBase.ClusterInfo();
            clusterCenters[c].setClusterCenter(c, Cr, Ci, 0);
        }
        classNames = classNameList.toArray(new String[0]);
    }

    private void getQuadPolClusterCenters() throws IOException {

        final Properties clusterCenterProperties = ResourceUtils.loadProperties(trainingDataSet.getAbsolutePath());
        final int numOfClusters = Integer.parseInt(clusterCenterProperties.getProperty("number_of_clusters"));
        clusterCenters = new PolClassifierBase.ClusterInfo[numOfClusters];
        clusterToClassMap = new int[numOfClusters];
        final java.util.List<String> classNameList = new ArrayList<>();

        final double[][] Tr = new double[3][3];
        final double[][] Ti = new double[3][3];
        String currentClassName = "";
        for (int c = 0; c < numOfClusters; c++) {

            final String cluster = "cluster" + c;
            final String clusterName = clusterCenterProperties.getProperty(cluster);
            final String className = clusterName.substring(0, clusterName.lastIndexOf('_'));
            if (!className.equals(currentClassName)) {
                numClasses++;
                currentClassName = className;
                classNameList.add(className);
            }
            clusterToClassMap[c] = numClasses;

            Tr[0][0] = Double.parseDouble(clusterCenterProperties.getProperty(cluster + "_T11"));
            Tr[0][1] = Double.parseDouble(clusterCenterProperties.getProperty(cluster + "_T12_real"));
            Ti[0][1] = Double.parseDouble(clusterCenterProperties.getProperty(cluster + "_T12_imag"));
            Tr[1][2] = Double.parseDouble(clusterCenterProperties.getProperty(cluster + "_T13_real"));
            Ti[1][2] = Double.parseDouble(clusterCenterProperties.getProperty(cluster + "_T13_imag"));
            Tr[1][1] = Double.parseDouble(clusterCenterProperties.getProperty(cluster + "_T22"));
            Tr[1][2] = Double.parseDouble(clusterCenterProperties.getProperty(cluster + "_T23_real"));
            Ti[1][2] = Double.parseDouble(clusterCenterProperties.getProperty(cluster + "_T23_imag"));
            Tr[2][2] = Double.parseDouble(clusterCenterProperties.getProperty(cluster + "_T33"));

            clusterCenters[c] = new PolClassifierBase.ClusterInfo();
            clusterCenters[c].setClusterCenter(c, Tr, Ti, 0);
        }
        classNames = classNameList.toArray(new String[0]);
    }

    /**
     * Create target product.
     */
    private void createTargetProduct() {

        sourceImageWidth = sourceProduct.getSceneRasterWidth();
        sourceImageHeight = sourceProduct.getSceneRasterHeight();

        targetProduct = new Product(sourceProduct.getName() + PRODUCT_SUFFIX,
                sourceProduct.getProductType(),
                sourceImageWidth,
                sourceImageHeight);

        // add index coding
        final IndexCoding indexCoding = new IndexCoding("Cluster_classes");
        for (int i = 0; i < numClasses; i++) {
            indexCoding.addIndex("class_" + (i + 1), i, classNames[i]);
        }
        targetProduct.getIndexCodingGroup().add(indexCoding);

        final String targetBandName = "supervised_wishart_class";

        final Band targetBand = new Band(targetBandName,
                ProductData.TYPE_UINT8,
                targetProduct.getSceneRasterWidth(),
                targetProduct.getSceneRasterHeight());

        targetBand.setUnit("zone_index");
        targetBand.setSampleCoding(indexCoding);

        targetProduct.addBand(targetBand);

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        AbstractMetadata.getAbstractedMetadata(targetProduct).setAttributeInt(AbstractMetadata.polsarData, 1);
    }

    /**
     * Called by the framework in order to compute a tile for the given target band.
     * <p>The default implementation throws a runtime exception with the message "not implemented".</p>
     *
     * @param targetBand The target band.
     * @param targetTile The current tile associated with the target band to be computed.
     * @param pm         A progress monitor which should be used to determine computation cancelation requests.
     * @throws OperatorException If an error occurs during computation of the target raster.
     */
    @Override
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) throws OperatorException {

        try {
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

            for (final PolBandUtils.PolSourceBand bandList : srcBandList) {

                final Tile[] sourceTiles = new Tile[bandList.srcBands.length];
                final ProductData[] dataBuffers = new ProductData[bandList.srcBands.length];
                final Rectangle sourceRectangle = getSourceRectangle(x0, y0, w, h);
                for (int i = 0; i < sourceTiles.length; ++i) {
                    sourceTiles[i] = getSourceTile(bandList.srcBands[i], sourceRectangle);
                    dataBuffers[i] = sourceTiles[i].getDataBuffer();
                }

                if (isDualPol) {

                    final double[][] Cr = new double[2][2];
                    final double[][] Ci = new double[2][2];

                    for (int y = y0; y < maxY; ++y) {
                        trgIndex.calculateStride(y);
                        for (int x = x0; x < maxX; ++x) {

                            getMeanCovarianceMatrixC2(x, y, halfWindowSize, halfWindowSize,
                                    sourceImageWidth, sourceImageHeight, sourceProductType, sourceTiles,
                                    dataBuffers, Cr, Ci);

                            targetData.setElemIntAt(
                                    trgIndex.getIndex(x),
                                    clusterToClassMap[HAlphaWishartC2.findZoneIndex(Cr, Ci, clusterCenters) - 1] - 1);
                        }
                    }

                } else { // quad-pol

                    final TileIndex srcIndex = new TileIndex(sourceTiles[0]);

                    final double[][] Tr = new double[3][3];
                    final double[][] Ti = new double[3][3];

                    for (int y = y0; y < maxY; ++y) {
                        trgIndex.calculateStride(y);
                        for (int x = x0; x < maxX; ++x) {

                            getMeanCoherencyMatrix(x, y, halfWindowSize, halfWindowSize, sourceImageWidth,
                                    sourceImageHeight, sourceProductType, srcIndex, dataBuffers, Tr, Ti);

                            targetData.setElemIntAt(
                                    trgIndex.getIndex(x),
                                    clusterToClassMap[HAlphaWishart.findZoneIndex(Tr, Ti, clusterCenters) - 1] - 1);
                        }
                    }
                }
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        } finally {
            pm.done();
        }
    }

    /**
     * Get source tile rectangle.
     *
     * @param tx0 X coordinate for the upper left corner pixel in the target tile.
     * @param ty0 Y coordinate for the upper left corner pixel in the target tile.
     * @param tw  The target tile width.
     * @param th  The target tile height.
     * @return The source tile rectangle.
     */
    private Rectangle getSourceRectangle(final int tx0, final int ty0, final int tw, final int th) {

        final int x0 = Math.max(0, tx0 - halfWindowSize);
        final int y0 = Math.max(0, ty0 - halfWindowSize);
        final int xMax = Math.min(tx0 + tw - 1 + halfWindowSize, sourceImageWidth);
        final int yMax = Math.min(ty0 + th - 1 + halfWindowSize, sourceImageHeight);
        final int w = xMax - x0 + 1;
        final int h = yMax - y0 + 1;
        return new Rectangle(x0, y0, w, h);
    }


    /**
     * The SPI is used to register this operator in the graph processing framework
     * via the SPI configuration file
     * {@code META-INF/services/org.esa.snap.core.gpf.OperatorSpi}.
     * This class may also serve as a factory for new operator instances.
     *
     * @see OperatorSpi#createOperator()
     * @see OperatorSpi#createOperator(java.util.Map, java.util.Map)
     */
    public static class Spi extends OperatorSpi {
        public Spi() {
            super(SupervisedWishartClassificationOp.class);
        }
    }
}
