/*
 * Copyright (C) 2020 by Microwave Remote Sensing Lab, IITBombay http://www.mrslab.in
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

import org.apache.commons.math3.util.FastMath;
import com.bc.ceres.core.ProgressMonitor;
import org.csa.rstb.polarimetric.gpf.support.CompactPolProcessor;
import org.csa.rstb.polarimetric.gpf.support.StokesParameters;
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
 * Generate Radar Vegetation Indices for compact-pol product
 *
 * Note: 1. This operator currently computes only one vegetation index (CpRVI). But it can be modified to compute
 *          multiple vegetation indies.
 *       2. The input to the operator is assumed to be C2 product, i.e. product with 4 bands: c11, c12_real, c12_imag
 *          and c22.
 *       3. The operator provides window size as an input parameter for averaging the input covariance matrix C2.
 *          If the input C2 matrix has already averaged, then no further averaging is needed, line 58-59, 194-197
 *          should be modified. See comments embedded.
 *
 * Reference:
 * [1] D. Mandal et al., "A Radar Vegetation Index for Crop Monitoring Using Compact Polarimetric SAR Data,"
        in IEEE Transactions on Geoscience and Remote Sensing, vol. 58, no. 9, pp. 6321-6335, Sept. 2020,
        doi: 10.1109/TGRS.2020.2976661.
 */

@OperatorMetadata(alias = "Compactpol-Radar-Vegetation-Index",
        category = "Radar/Polarimetric/Radar Vegetation Index",
        authors = "Dipankar Mandal et al.",
        version = "1.0",
        copyright = "Copyright (C) 2020 by Microwave Remote Sensing Lab, IITBombay",
        description = "Compact-pol Radar Vegetation Indices generation")
public final class CPRVIOp extends Operator implements CompactPolProcessor {

    @SourceProduct(alias = "source")
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    // If the input covariance matrix C2 has already been averaged, then comment out the following two lines and
    // set windowSize to 0
    @Parameter(description = "The sliding window size", valueSet = {"3", "5", "7", "9", "11", "13", "15", "17", "19"},
            defaultValue = "3", label = "Window Size")
    private String windowSizeStr = "3";

    private int windowSize = 0;
    private int halfWindowSize = 0;
    private int sourceImageWidth = 0;
    private int sourceImageHeight = 0;

    private PolBandUtils.MATRIX sourceProductType = null;
    private PolBandUtils.PolSourceBand[] srcBandList;

    private final static String COMPACT_POL_RVI = "CpRVI";

    @Override
    public void initialize() throws OperatorException {

        try {
            final InputProductValidator validator = new InputProductValidator(sourceProduct);
            validator.checkIfSARProduct();

            sourceProductType = PolBandUtils.getSourceProductType(sourceProduct);
            if (sourceProductType != PolBandUtils.MATRIX.LCHCP && sourceProductType != PolBandUtils.MATRIX.RCHCP &&
                    sourceProductType != PolBandUtils.MATRIX.C2) {
                throw new OperatorException("Compact pol source product or C2 covariance matrix product is expected.");
            }

            srcBandList = PolBandUtils.getSourceBands(sourceProduct, sourceProductType);
            windowSize = Integer.parseInt(windowSizeStr);
            halfWindowSize = windowSize / 2;
            sourceImageWidth = sourceProduct.getSceneRasterWidth();
            sourceImageHeight = sourceProduct.getSceneRasterHeight();

            createTargetProduct();

            updateTargetProductMetadata();
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    /**
     * Create target product.
     */
    private void createTargetProduct() {

        targetProduct = new Product(sourceProduct.getName(),
                sourceProduct.getProductType(),
                sourceProduct.getSceneRasterWidth(),
                sourceProduct.getSceneRasterHeight());

        addSelectedBands();

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);
    }

    /**
     * Add bands to the target product.
     *
     * @throws OperatorException The exception.
     */
    private void addSelectedBands() throws OperatorException {

        // If more indices are computed, add their names here
        final String[] targetBandNames = new String[]{COMPACT_POL_RVI};

        for (PolBandUtils.PolSourceBand bandList : srcBandList) {
            final Band[] targetBands = OperatorUtils.addBands(targetProduct, targetBandNames, bandList.suffix);
            bandList.addTargetBands(targetBands);
        }
    }

    /**
     * Update metadata in the target product.
     */
    private void updateTargetProductMetadata() throws Exception {

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(targetProduct);
        if (absRoot != null) {
            absRoot.setAttributeInt(AbstractMetadata.polsarData, 1);
        }
        PolBandUtils.saveNewBandNames(targetProduct, srcBandList);
    }


    /**
     * Called by the framework in order to compute the stack of tiles for the given target bands.
     * <p>The default implementation throws a runtime exception with the message "not implemented".</p>
     *
     * @param targetTiles     The current tiles to be computed for each target band.
     * @param targetRectangle The area in pixel coordinates to be computed (same for all rasters in <code>targetRasters</code>).
     * @param pm              A progress monitor which should be used to determine computation cancelation requests.
     * @throws org.esa.snap.core.gpf.OperatorException if an error occurs during computation of the target rasters.
     */
    @Override
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle targetRectangle, ProgressMonitor pm)
            throws OperatorException {

        final int x0 = targetRectangle.x;
        final int y0 = targetRectangle.y;
        final int w = targetRectangle.width;
        final int h = targetRectangle.height;
        final int maxY = y0 + h;
        final int maxX = x0 + w;
        //System.out.println("x0 = " + x0 + ", y0 = " + y0 + ", w = " + w + ", h = " + h);

        final double[][] Cr = new double[2][2]; // real part of covariance matrix
        final double[][] Ci = new double[2][2]; // imaginary part of covariance matrix
        final double[] g = new double[4];       // Stokes vector
        final double[][] K_T = new double[4][4]; // 4x4 Kennaugh matrix

        final double[][] K_depol = { //Random target & Ideal Depolarizer
                            { 1, 0, 0, 0 },
                            { 0, 0, 0, 0 },
                            { 0, 0, 0, 0 },
                            { 0, 0, 0, 0 }
                        };

        for (final PolBandUtils.PolSourceBand bandList : srcBandList) {
            try {
                final TileData[] tileDataList = new TileData[bandList.targetBands.length];
                int i = 0;
                for (Band targetBand : bandList.targetBands) {
                    final Tile targetTile = targetTiles.get(targetBand);
                    tileDataList[i++] = new TileData(targetTile, targetBand.getName());
                }
                final TileIndex tgtIndex = new TileIndex(tileDataList[0].tile);

                final Tile[] sourceTiles = new Tile[bandList.srcBands.length];
                final ProductData[] dataBuffers = new ProductData[bandList.srcBands.length];
                final Rectangle sourceRectangle = getSourceRectangle(x0, y0, w, h);

                for (int j = 0; j < bandList.srcBands.length; j++) {
                    final Band srcBand = bandList.srcBands[j];
                    sourceTiles[j] = getSourceTile(srcBand, sourceRectangle);
                    dataBuffers[j] = sourceTiles[j].getDataBuffer();
                }
                final TileIndex srcIndex = new TileIndex(sourceTiles[0]);

                for (int y = y0; y < maxY; ++y) {
                    tgtIndex.calculateStride(y);
                    srcIndex.calculateStride(y);

                    for (int x = x0; x < maxX; ++x) {
                        final int tgtIdx = tgtIndex.getIndex(x);
                        final int srcIdx = srcIndex.getIndex(x);

                        // If input covariance matrix has been average then don't need to average it again.
                        // Comment out the following two lines and use the line below them, i.e. getCovarianceMatrixC2
                        getMeanCovarianceMatrixC2(x, y, halfWindowSize, halfWindowSize, sourceImageWidth,
                                sourceImageHeight, sourceProductType, sourceTiles, dataBuffers, Cr, Ci);

                        // Compute Stokes vector
                        StokesParameters.computeCompactPolStokesVector(Cr, Ci, g);

                        // Compute Kennaugh matrix
                        K_T[0][0] = 0.5*g[0];
                        K_T[0][1] = 0.5*0;
                        K_T[0][2] = 0.5*g[2];
                        K_T[0][3] = 0.5*0;

                        K_T[1][0] = 0.5*K_T[0][1];
                        K_T[1][1] = 0.5*0;
                        K_T[1][2] = 0.5*0;
                        K_T[1][3] = 0.5*g[1];

                        K_T[2][0] = 0.5*K_T[0][2];
                        K_T[2][1] = 0.5*K_T[1][2];
                        K_T[2][2] = 0.5*0;
                        K_T[2][3] = 0.5*0;

                        K_T[3][0] = 0.5*K_T[0][3];
                        K_T[3][1] = 0.5*K_T[1][3];
                        K_T[3][2] = 0.5*K_T[2][3];
                        K_T[3][3] = 0.5*g[3];

                        // Compute Geodesic distance between K_T and K_depol
                        final double[][] K_TT = new double[4][4];
                        final double[][] K_depolT = new double[4][4];
                        final double[][] num1 = new double[4][4];

                        matrixtranspose(K_T,K_TT);
                        matrixmultiply(K_TT,K_depol,num1);
                        final double num = num1[0][0] + num1[1][1] + num1[2][2] + num1[3][3]; //trace of matrix

                        final double[][] num2 = new double[4][4];
                        matrixmultiply(K_TT,K_T,num2);
                        final double num3 = num2[0][0] + num2[1][1] + num2[2][2] + num2[3][3]; //trace of matrix
                        final double den1 = Math.sqrt(Math.abs(num3));

                        final double[][] num4 = new double[4][4];
                        matrixtranspose(K_depol,K_depolT);
                        matrixmultiply(K_depolT,K_depol,num4);
                        final double num5 = num4[0][0] + num4[1][1] + num4[2][2] + num4[3][3]; //trace of matrix
                        final double den2 = Math.sqrt(Math.abs(num5));

                        final double den = den1*den2;
                        final double tempaa = 2*FastMath.acos(num/den)*180/Math.PI;
                        final double GD_t1_depol = tempaa/180;

                        //-----------
                        // Modulating factor calculation
                        final double SC = (g[0] - g[3])/2;
                        final double OC = (g[0] + g[3])/2;
                        final double min_sc_oc = Math.min(SC,OC);
                        final double max_sc_oc = Math.max(SC,OC);

                        final double lambda = (3.0/2.0)*GD_t1_depol;
                        final double fp22 = (min_sc_oc/max_sc_oc);

                        // CpRVI final calculation
                        final double cpRVI = (1 - lambda) * FastMath.pow(fp22, 2*lambda);




                        for (final TileData tileData : tileDataList) {
                            // Can add more indices here
                            if (tileData.bandName.contains(COMPACT_POL_RVI)) {
                                tileData.dataBuffer.setElemFloatAt(tgtIdx, (float) cpRVI);
                            }
                        }
                    }
                }

            } catch (Throwable e) {
                OperatorUtils.catchOperatorException(getId(), e);
            }
        }
    }

    private Rectangle getSourceRectangle(final int tx0, final int ty0, final int tw, final int th) {
        final int x0 = Math.max(0, tx0 - halfWindowSize);
        final int y0 = Math.max(0, ty0 - halfWindowSize);
        final int xMax = Math.min(tx0 + tw - 1 + halfWindowSize, sourceImageWidth - 1);
        final int yMax = Math.min(ty0 + th - 1 + halfWindowSize, sourceImageHeight - 1);
        final int w = xMax - x0 + 1;
        final int h = yMax - y0 + 1;
        return new Rectangle(x0, y0, w, h);
    }

    private static class TileData {
        final Tile tile;
        final ProductData dataBuffer;
        final String bandName;

        public TileData(final Tile tile, final String bandName) {
            this.tile = tile;
            this.dataBuffer = tile.getDataBuffer();
            this.bandName = bandName;
        }
    }


    /**
     * The SPI is used to register this operator in the graph processing framework
     * via the SPI configuration file
     * {@code META-INF/services/org.esa.snap.core.gpf.OperatorSpi}.
     * This class may also serve as a factory for new operator instances.
     *
     * @see OperatorSpi#createOperator()
     * @see OperatorSpi#createOperator(Map, Map)
     */
    public static class Spi extends OperatorSpi {
        public Spi() {
            super(CPRVIOp.class);
        }
    }
}