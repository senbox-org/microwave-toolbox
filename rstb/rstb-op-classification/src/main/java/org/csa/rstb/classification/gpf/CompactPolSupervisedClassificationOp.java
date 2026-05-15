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
package org.csa.rstb.classification.gpf;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.polsar.PolBandUtils;
import org.csa.rstb.classification.gpf.classifiers.HAlphaWishartC2;
import org.csa.rstb.classification.gpf.classifiers.PolClassifierBase;
import org.csa.rstb.polarimetric.gpf.support.CompactPolProcessor;
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
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;
import org.esa.snap.engine_utilities.util.ResourceUtils;

import java.awt.Rectangle;
import java.io.File;
import java.io.IOException;
import java.util.Properties;

/**
 * Supervised Wishart classification for compact-pol products.
 *
 * Reads cluster centres (C2 covariance entries per class) from a Java
 * properties training file and assigns each pixel to the nearest cluster
 * by Wishart distance. Reference: Lee &amp; Pottier 2009 textbook
 * <i>Polarimetric Radar Imaging: From Basics to Applications</i>,
 * supervised Wishart classifier; compact-pol specialisation in
 * Cloude, Goodenough &amp; Chen 2012.
 */
@OperatorMetadata(alias = "CP-Supervised-Classification",
        category = "Radar/Polarimetric/Compact Polarimetry",
        authors = "Jun Lu, Luis Veci",
        version = "1.0",
        copyright = "Copyright (C) 2018 SkyWatch Space Applications Inc.",
        description = "Supervised Wishart classification on compact-pol products.")
public final class CompactPolSupervisedClassificationOp extends Operator implements CompactPolProcessor {

    @SourceProduct(alias = "source")
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(description = "The training data set file", label = "Training Data Set")
    private File trainingDataSet = null;

    @Parameter(valueSet = {"3", "5", "7", "9", "11", "13", "15", "17", "19"}, defaultValue = "5", label = "Window Size X")
    private String windowSizeXStr = "5";

    @Parameter(valueSet = {"3", "5", "7", "9", "11", "13", "15", "17", "19"}, defaultValue = "5", label = "Window Size Y")
    private String windowSizeYStr = "5";

    private int windowSizeX = 0;
    private int windowSizeY = 0;
    private int srcWidth = 0;
    private int srcHeight = 0;
    private PolBandUtils.PolSourceBand[] srcBandList;
    private PolBandUtils.MATRIX sourceProductType;

    private HAlphaWishartC2.ClusterInfo[] clusterCenters = null;
    private int[] clusterToClassMap = null;
    private int numClasses = 0;

    @Override
    public void initialize() throws OperatorException {

        try {
            final InputProductValidator validator = new InputProductValidator(sourceProduct);
            validator.checkIfSARProduct();
            validator.checkIfSLC();

            if (trainingDataSet == null || !trainingDataSet.exists()) {
                throw new OperatorException("Cannot find training data set file: "
                        + (trainingDataSet == null ? "<unset>" : trainingDataSet.getAbsolutePath()));
            }

            windowSizeX = Integer.parseInt(windowSizeXStr);
            windowSizeY = Integer.parseInt(windowSizeYStr);

            getClusterCenters();

            sourceProductType = PolBandUtils.getSourceProductType(sourceProduct);
            srcBandList = PolBandUtils.getSourceBands(sourceProduct, sourceProductType);

            createTargetProduct();
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    /**
     * Read cluster centres from a Java properties file. Expected keys:
     *   number_of_clusters
     *   cluster&lt;c&gt;            -- name of the form "&lt;className&gt;_&lt;index&gt;"
     *   cluster&lt;c&gt;_C11
     *   cluster&lt;c&gt;_C12_real
     *   cluster&lt;c&gt;_C12_imag
     *   cluster&lt;c&gt;_C22
     */
    private void getClusterCenters() throws IOException {

        final Properties clusterCenterProperties = ResourceUtils.loadProperties(trainingDataSet.getAbsolutePath());
        final int numOfClusters = Integer.parseInt(clusterCenterProperties.getProperty("number_of_clusters"));
        clusterCenters = new HAlphaWishartC2.ClusterInfo[numOfClusters];
        clusterToClassMap = new int[numOfClusters];

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
            }
            clusterToClassMap[c] = numClasses;

            Cr[0][0] = Double.parseDouble(clusterCenterProperties.getProperty(cluster + "_C11"));
            Cr[0][1] = Double.parseDouble(clusterCenterProperties.getProperty(cluster + "_C12_real"));
            Ci[0][1] = Double.parseDouble(clusterCenterProperties.getProperty(cluster + "_C12_imag"));
            Cr[1][1] = Double.parseDouble(clusterCenterProperties.getProperty(cluster + "_C22"));

            clusterCenters[c] = new HAlphaWishartC2.ClusterInfo();
            clusterCenters[c].setClusterCenter(c, Cr, Ci, 0);
        }
    }

    private void createTargetProduct() {

        srcWidth = sourceProduct.getSceneRasterWidth();
        srcHeight = sourceProduct.getSceneRasterHeight();

        targetProduct = new Product(sourceProduct.getName(),
                sourceProduct.getProductType(),
                srcWidth,
                srcHeight);

        final IndexCoding indexCoding = new IndexCoding("Cluster_classes");
        for (int i = 0; i < numClasses; i++) {
            indexCoding.addIndex("class_" + (i + 1), i, "Cluster " + (i + 1));
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
            final int halfWindowSizeX = windowSizeX / 2;
            final int halfWindowSizeY = windowSizeY / 2;

            for (final PolBandUtils.PolSourceBand bandList : srcBandList) {

                final Tile[] sourceTiles = new Tile[bandList.srcBands.length];
                final ProductData[] dataBuffers = new ProductData[bandList.srcBands.length];
                final Rectangle sourceRectangle = PolClassifierBase.getSourceRectangle(
                        x0, y0, w, h, windowSizeX, windowSizeY, srcWidth, srcHeight);

                for (int i = 0; i < sourceTiles.length; ++i) {
                    sourceTiles[i] = getSourceTile(bandList.srcBands[i], sourceRectangle);
                    dataBuffers[i] = sourceTiles[i].getDataBuffer();
                }

                final double[][] Cr = new double[2][2];
                final double[][] Ci = new double[2][2];

                for (int y = y0; y < maxY; ++y) {
                    trgIndex.calculateStride(y);
                    for (int x = x0; x < maxX; ++x) {

                        getMeanCovarianceMatrixC2(x, y, halfWindowSizeX, halfWindowSizeY, srcWidth, srcHeight,
                                sourceProductType, sourceTiles, dataBuffers, Cr, Ci);

                        targetData.setElemIntAt(
                                trgIndex.getIndex(x),
                                clusterToClassMap[HAlphaWishartC2.findZoneIndex(Cr, Ci, clusterCenters) - 1]);
                    }
                }
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        } finally {
            pm.done();
        }
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(CompactPolSupervisedClassificationOp.class);
        }
    }
}
