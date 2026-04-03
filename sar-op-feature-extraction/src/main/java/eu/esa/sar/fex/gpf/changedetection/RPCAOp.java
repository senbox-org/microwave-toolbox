/*
 * Copyright (C) 2025 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.fex.gpf.changedetection;

import Jama.Matrix;
import Jama.SingularValueDecomposition;
import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.engine_utilities.gpf.*;

import java.awt.*;
import java.util.HashMap;
import java.util.Map;

/**
 * This operator performs change detection on a time series of SAR amplitude images based on Robust Principal
 * Component Analysis (RPCA). It is assumed that the input product is a stack of multiple co-registered amplitude
 * images of the same pass.
 * [1]	C. Schwartz, L. P. Ramos, L. T. Duarte, M. S. Pinho, M. I. Pettersson, V. T. Vu and R. Machado, "Change
 * Detection in UWB SAR Images Based on Robust Principal Component Analysis", Remote Sens. 2020, 12, 1916.
 */

@OperatorMetadata(alias = "RPCA-Change-Detection",
        category = "Raster/Change Detection",
        authors = "Jun Lu, Luis Veci",
        version = "1.0",
        copyright = "Copyright (C) 2025 by SkyWatch Space Applications Inc.",
        description = "RPCA Change Detection.")
public class RPCAOp extends Operator {

    @SourceProduct(alias = "source")
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct = null;

    @Parameter(description = "The list of source bands.", alias = "sourceBands",
            rasterDataNodeType = Band.class, label = "Source Bands")
    private String[] sourceBandNames;

    @Parameter(description = "Mask threshold", interval = "(0, 1)", defaultValue = "0.05", label = "Mask threshold")
    private float maskThreshold = 0.05f;

    @Parameter(description = "Regularization parameter", interval = "(0, 1)", defaultValue = "0.01", label = "Lambda")
    private float lambda = 0.01f;

    @Parameter(description = "Include source bands", defaultValue = "false", label = "Include source bands")
    private boolean includeSourceBands = false;

    private int sourceImageWidth = 0;
    private int sourceImageHeight = 0;
    private HashMap<Band, Band> targetBandToSourceBandMap = new HashMap<>(2);
    private Band[] bandsToDecompose = null;

    private double rho = 0.0;
    private double rhoInv = 0.0;
    private double overlapPercentage = 0.0;
    private double lambdaOverRho = 0.0;
    private int maxIteration = 50;
    private double tolerance = 1.0e-14;

    private static final String SPARSE_NAME = "_sparse";
    private static final String MASK_NAME = "_change";

    @Override
    public void initialize() throws OperatorException {

        try {
            createTargetProduct();

            setParameters();

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private void setParameters() {

        final Band[] sourceBands = OperatorUtils.getSourceBands(sourceProduct, sourceBandNames, true);
        double sumMean = 0.0;
        for (final Band srcBand : sourceBands) {
            final Stx stx = srcBand.getStx();
            sumMean += stx.getMean();
        }
        rho = 1.0 / (4.0 * sumMean);
        rhoInv = 1.0 / rho;
        lambdaOverRho = lambda * rhoInv;
    }

    private void createTargetProduct() {

        sourceImageWidth = sourceProduct.getSceneRasterWidth();
        sourceImageHeight = sourceProduct.getSceneRasterHeight();
        targetProduct = new Product(sourceProduct.getName(), sourceProduct.getProductType(), sourceImageWidth,
                sourceImageHeight);

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        addSelectedBands();
    }

    private void addSelectedBands() {

        final Band[] sourceBands = OperatorUtils.getSourceBands(sourceProduct, sourceBandNames, true);
        bandsToDecompose = new Band[sourceBands.length];
        int b = 0;
        for (final Band srcBand : sourceBands) {
            final String bandName = srcBand.getName();
            if (includeSourceBands) {
                ProductUtils.copyBand(bandName, sourceProduct, bandName, targetProduct, true);
            }

            Band tgtBand = targetProduct.addBand(bandName + SPARSE_NAME, ProductData.TYPE_FLOAT32);
            tgtBand.setUnit(srcBand.getUnit());
            tgtBand.setDescription("Detected targets in image");
            targetBandToSourceBandMap.put(tgtBand, srcBand);
            bandsToDecompose[b++] = tgtBand;

            //create mask
            String expression = tgtBand.getName() + " > "+ maskThreshold + " ? 1 : 0";
            final Mask mask = new Mask(bandName + MASK_NAME, sourceImageWidth, sourceImageHeight, Mask.BandMathsType.INSTANCE);
            mask.setDescription("Change");
            mask.getImageConfig().setValue("color", Color.RED);
            mask.getImageConfig().setValue("transparency", 0.7);
            mask.getImageConfig().setValue("expression", expression);
            mask.setNoDataValue(0);
            mask.setNoDataValueUsed(true);
            targetProduct.getMaskGroup().add(mask);
        }
    }

    /**
     * Called by the framework in order to compute the stack of tiles for the given target bands.
     * <p>The default implementation throws a runtime exception with the message "not implemented".</p>
     *
     * @param targetTiles     The current tiles to be computed for each target band.
     * @param targetRectangle The area in pixel coordinates to be computed (same for all rasters in <code>targetRasters</code>).
     * @param pm              A progress monitor which should be used to determine computation cancelation requests.
     * @throws OperatorException if an error occurs during computation of the target rasters.
     */
    @Override
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle targetRectangle, ProgressMonitor pm)
            throws OperatorException {

        try {
            final int x0 = targetRectangle.x;
            final int y0 = targetRectangle.y;
            final int w = targetRectangle.width;
            final int h = targetRectangle.height;
            final int xMax = x0 + w;
            final int yMax = y0 + h;
            //System.out.println("x0 = " + x0 + ", y0 = " + y0 + ", w = " + w + ", h = " + h);

            final int xOverlap = (int)(w * overlapPercentage);
            final int yOverlap = (int)(h * overlapPercentage);
            final int sx0 = Math.max(x0 - xOverlap, 0);
            final int sy0 = Math.max(y0 - yOverlap, 0);
            final int sxMax = Math.min(xMax + xOverlap, sourceImageWidth);
            final int syMax = Math.min(yMax + yOverlap, sourceImageHeight);
            final int sw = sxMax - sx0;
            final int sh = syMax - sy0;
            final Rectangle sourceRectangle = new Rectangle(sx0, sy0, sw, sh);

            final double[][] matX = new double[sw * sh][bandsToDecompose.length];
            for (int c = 0; c < bandsToDecompose.length; ++c) {

                final Band srcBand = targetBandToSourceBandMap.get(bandsToDecompose[c]);
                final Tile srcTile = getSourceTile(srcBand, sourceRectangle);
                final ProductData srcData = srcTile.getDataBuffer();
                final TileIndex srcIndex = new TileIndex(srcTile);

                for (int y = sy0; y < syMax; ++y) {
                    srcIndex.calculateStride(y);
                    final int yy = y - sy0;

                    for (int x = sx0; x < sxMax; ++x) {
                        final int srcIdx = srcIndex.getIndex(x);
                        final int xx = x - sx0;
                        final int r = xx * sh + yy;
                        matX[r][c] = srcData.getElemDoubleAt(srcIdx);
                    }
                }
            }

            final double[][] matS = performRPCA(matX);

            for (int c = 0; c < bandsToDecompose.length; ++c) {
                final Tile tgtTile = targetTiles.get(bandsToDecompose[c]);
                final ProductData tgtData = tgtTile.getDataBuffer();
                final TileIndex tgtIndex = new TileIndex(tgtTile);

                for (int y = y0; y < yMax; ++y) {
                    tgtIndex.calculateStride(y);
                    final int yy = y - sy0;

                    for (int x = x0; x < xMax; ++x) {
                        final int tgtIdx = tgtIndex.getIndex(x);
                        final int xx = x - sx0;
                        final int r = xx * sh + yy;
                        tgtData.setElemDoubleAt(tgtIdx, Math.max(matS[r][c], 0.0));
                    }
                }
            }

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private double[][] performRPCA(final double[][] matX) {

        final int m = matX.length;
        final int n = matX[0].length;

        final Matrix X = new Matrix(matX);
        final Matrix S = new Matrix(new double[m][n]);
        final Matrix Y = new Matrix(new double[m][n]);

        for (int i = 0; i < maxIteration; ++i) {

            final SingularValueDecomposition Svd = X.minus(S).plus(Y.times(rhoInv)).svd(); // A = USV'
            final Matrix Sigma = Svd.getS();
            final Matrix U = Svd.getU();
            final Matrix V = Svd.getV();
            final Matrix L = U.times(shrink(Sigma, rhoInv)).times(V.transpose());

            final Matrix Tmp0 = shrink(X.minus(L).plus(Y.times(rhoInv)), lambdaOverRho);
            S.timesEquals(0.0).plusEquals(Tmp0);

            final Matrix Tmp1 = X.minus(L).minus(S);
            Y.plusEquals(Tmp1.times(rho));

            final double error = norm2(Tmp1);
//            if (i % 10 == 0) {
//                System.out.println("i = " + i + ", error = " + error);
//            }
            if (error < tolerance) {
//                System.out.println("Break: i = " + i + ", error = " + error);
                break;
            }
        }

        return S.getArray();
    }

    private double norm1(final Matrix A) {

        final double[] dataArray = A.getColumnPackedCopy();
        double n1 = 0.0;
        for (double v : dataArray) {
            n1 += Math.abs(v);
        }
        return n1;
    }

    private double norm2(final Matrix A) {

        final int cols = A.getColumnDimension();
        final int rows = A.getRowDimension();
        final double[][] dataArray = A.getArray();
        double n2 = 0.0;
        for (int r = 0; r < rows; ++r) {
            for (int c = 0; c < cols; ++c) {
                n2 += dataArray[r][c] * dataArray[r][c];
            }
        }
        return n2;
    }

    private Matrix shrink(final Matrix A, final double threshold) {

        final int cols = A.getColumnDimension();
        final int rows = A.getRowDimension();
        final double[][] dataA = A.getArray();
        final double[][] dataB = new double[rows][cols];
        for (int r = 0; r < rows; ++r) {
            for (int c = 0; c < cols; ++c) {
                dataB[r][c] = dataA[r][c] > threshold? dataA[r][c] - threshold :
                        dataA[r][c] < -threshold? dataA[r][c] + threshold : 0.0;
            }
        }
        return new Matrix(dataB);
    }


    /**
     * Operator SPI.
     */
    public static class Spi extends OperatorSpi {

        public Spi() {
            super(RPCAOp.class);
        }
    }
}
