/*
 * Copyright (C) 2017 by Array Systems Computing Inc. http://www.array.ca
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
package org.csa.rstb.polarimetric.gpf;

import Jama.Matrix;
import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.polsar.PolBandUtils;
import org.csa.rstb.polarimetric.gpf.support.DualPolProcessor;
import org.csa.rstb.polarimetric.gpf.support.QuadPolProcessor;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Mask;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.ProductNodeGroup;
import org.esa.snap.core.datamodel.VectorDataNode;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.opengis.feature.simple.SimpleFeature;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Partial-target (fire-scar / burn-scar) detection via the geometrical
 * perturbation filter.
 *
 * Reference: Marino, A.; Cloude, S. R.; Woodhouse, I. H. (2012).
 *   <i>A Polarimetric Target Detector Using the Huynen Fork.</i>
 *   IEEE Transactions on Geoscience and Remote Sensing 50(10), 4039-4047.
 *   DOI: 10.1109/TGRS.2012.2189454
 *
 * <p>Algorithm summary (paper &sect; III-V):</p>
 * <ol>
 *   <li>Estimate the feature partial scattering vector <b>t</b> for the
 *       target (e.g. a fire scar) either from a user-supplied training
 *       polygon (supervised mode) or from canonical parameters
 *       (unsupervised mode &mdash; quad-pol only,
 *       &alpha;=19&deg;, &rho;=7.7 dB, &mu;=0).</li>
 *   <li>Normalise <b>t</b> to a unit scattering mechanism
 *       <b>w</b> = <b>t</b> / ||<b>t</b>||.</li>
 *   <li>Extend <b>w</b> to an orthonormal basis by Gram-Schmidt with
 *       random complementary vectors; invert to obtain the
 *       transformation matrix <b>U</b> (which rotates <b>w</b> to
 *       (1, 0, 0, ...)).</li>
 *   <li>For every pixel: form the covariance matrix <b>C</b> (mean over
 *       a square window), convert to the feature partial scattering
 *       vector <b>t'</b>, apply <b>U</b> to get <b>t&middot;'</b>, and
 *       compute Signal-to-Clutter Ratio
 *         SCR = |t&middot;'<sub>1</sub>|<sup>2</sup> /
 *               &Sigma;<sub>i&gt;1</sub> |t&middot;'<sub>i</sub>|<sup>2</sup>.</li>
 *   <li>Detection mask:
 *         &gamma; = 1 / sqrt(1 + RedR / SCR),
 *       with RedR = SCR<sub>target</sub> (1/threshold<sup>2</sup> - 1).
 *       Output &gamma; when &gamma; &geq; threshold, else 0.</li>
 * </ol>
 *
 * <p>Supports quad-pol (T3 / C3) and dual-pol C2 inputs. Dual-pol requires
 * supervised mode (no canonical unsupervised parameters for dual-pol).</p>
 */
@OperatorMetadata(alias = "PartialTargetDetection",
        category = "Radar/Polarimetric/Target Detection",
        authors = "Jun Lu, Luis Veci",
        version = "1.0",
        copyright = "Copyright (C) 2017 by Array Systems Computing Inc.",
        description = "Marino-Cloude-Woodhouse 2012 geometrical-perturbation-filter fire-scar / partial-target detection on quad-pol or dual-pol SAR.")
public class PartialTargetDetectionOp extends Operator implements DualPolProcessor, QuadPolProcessor {

    @SourceProduct
    Product sourceProduct;

    @TargetProduct
    Product targetProduct;

    @Parameter(description = "Threshold for partial target detection", interval = "(0, 1]", defaultValue = "0.98",
            label = "Threshold")
    private double threshold = 0.98;

    @Parameter(description = "Signal to Clutter Ratio (SCR)", interval = "[2.0, 100.0]", defaultValue = "50.0",
            label = "Signal to Clutter Ratio (SCR)")
    private double scr = 50.0;

    @Parameter(description = "The sliding window size", interval = "[1, 100]", defaultValue = "5", label = "Window Size")
    private int windowSize = 5;

    // Unsupervised mode (canonical fire-scar parameters from Marino 2012 Table II) is implemented
    // but the @Parameter annotation is intentionally omitted: in production the supervised path
    // (user polygon over a known fire-scar reference patch) is the only validated workflow.
    private Boolean useSupervisedDetection = true;

    private final Map<PolBandUtils.PolSourceBand, TransformationMatrix> prodToTransMatMap = new HashMap<>();
    private PolBandUtils.PolSourceBand[] srcBandList;
    private PolBandUtils.MATRIX sourceProductType = null;
    private String fireScarGeometry = null;
    private double redR = 0.0;
    private boolean isQuadPol = false;
    private Band detMskBand = null;
    private int vectorDim = 0;
    private int matrixDim = 0;
    private int halfWindowSize = 0;
    private int sourceImageWidth = 0;
    private int sourceImageHeight = 0;

    // Canonical unsupervised parameters for fire-scar partial target (Marino 2012 Table II)
    private static final double DEFAULT_ALPHA_DEG = 19.0;
    private static final double DEFAULT_RHO_DB = 7.7;
    private static final double DEFAULT_MU = 0.0;

    public PartialTargetDetectionOp() {
    }

    @Override
    public void initialize() throws OperatorException {

        try {
            final InputProductValidator validator = new InputProductValidator(sourceProduct);
            validator.checkIfSARProduct();
            validator.checkIfTOPSARBurstProduct(false);

            sourceProductType = PolBandUtils.getSourceProductType(sourceProduct);

            checkSourceProductType(sourceProductType);

            if (useSupervisedDetection) {
                getFireScarGeometry();
            }

            srcBandList = PolBandUtils.getSourceBands(sourceProduct, sourceProductType);

            matrixDim = isQuadPol ? 3 : 2;
            vectorDim = isQuadPol ? 6 : 3;

            halfWindowSize = windowSize / 2;
            sourceImageWidth = sourceProduct.getSceneRasterWidth();
            sourceImageHeight = sourceProduct.getSceneRasterHeight();

            redR = scr * (1.0 / (threshold * threshold) - 1.0);

            createTargetProduct();
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private void checkSourceProductType(final PolBandUtils.MATRIX sourceProductType) {
        if (sourceProductType == PolBandUtils.MATRIX.UNKNOWN) {
            throw new OperatorException("Input should be a polarimetric product");
        }
        isQuadPol = PolBandUtils.isQuadPol(sourceProductType) || PolBandUtils.isFullPol(sourceProductType);
        if (!isQuadPol && !PolBandUtils.isDualPol(sourceProductType)) {
            throw new OperatorException("Input should be a quad or dual polarimetric product");
        }
        if (!isQuadPol && !useSupervisedDetection) {
            throw new OperatorException("Only supervised detection is available for dual-pol product");
        }
    }

    private void getFireScarGeometry() {
        final List<String> geometryNames = new ArrayList<>();
        final ProductNodeGroup<VectorDataNode> vectorDataNodes = sourceProduct.getVectorDataGroup();
        for (int i = 0; i < vectorDataNodes.getNodeCount(); ++i) {
            final VectorDataNode node = vectorDataNodes.get(i);
            if (!node.getFeatureCollection().isEmpty()) {
                geometryNames.add(node.getName());
            }
        }
        if (geometryNames.size() != 1) {
            throw new OperatorException("Please select sub-regions in fire scar region for target training");
        }
        fireScarGeometry = geometryNames.get(0);
    }

    private void createTargetProduct() {
        targetProduct = new Product(sourceProduct.getName(), sourceProduct.getProductType(),
                sourceImageWidth, sourceImageHeight);
        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        detMskBand = new Band("detectionMask", ProductData.TYPE_FLOAT32, sourceImageWidth, sourceImageHeight);
        detMskBand.setUnit(Unit.COHERENCE);
        targetProduct.addBand(detMskBand);
    }

    @Override
    public void computeTileStack(Map<Band, Tile> targetTileMap, Rectangle targetRectangle, ProgressMonitor pm)
            throws OperatorException {

        final int x0 = targetRectangle.x;
        final int y0 = targetRectangle.y;
        final int w = targetRectangle.width;
        final int h = targetRectangle.height;
        final int yMax = y0 + h;
        final int xMax = x0 + w;

        try {
            final Rectangle sourceRectangle = getSourceRectangle(x0, y0, w, h);

            for (final PolBandUtils.PolSourceBand bandList : srcBandList) {
                final Tile[] sourceTiles = new Tile[bandList.srcBands.length];
                final ProductData[] dataBuffers = new ProductData[bandList.srcBands.length];

                if (isQuadPol) {
                    getQuadPolDataBuffer(
                            this, bandList.srcBands, sourceRectangle, sourceProductType, sourceTiles, dataBuffers);
                } else {
                    getDataBuffer(this, bandList.srcBands, sourceRectangle, sourceProductType, sourceTiles, dataBuffers);
                }

                if (prodToTransMatMap.get(bandList) == null) {
                    estimateTransMatrix(bandList);
                }
                final TransformationMatrix transMat = prodToTransMatMap.get(bandList);

                final Tile detMskTile = targetTileMap.get(detMskBand);
                final ProductData detMskData = detMskTile.getDataBuffer();
                final TileIndex tgtIndex = new TileIndex(detMskTile);

                final double[][] CRe = new double[matrixDim][matrixDim];
                final double[][] CIm = new double[matrixDim][matrixDim];
                final double[] tRe = new double[vectorDim];
                final double[] tIm = new double[vectorDim];
                final double[] tTrRe = new double[vectorDim];
                final double[] tTrIm = new double[vectorDim];

                for (int y = y0; y < yMax; ++y) {
                    tgtIndex.calculateStride(y);
                    for (int x = x0; x < xMax; ++x) {
                        final int tgtIdx = tgtIndex.getIndex(x);

                        if (isQuadPol) {
                            getMeanCovarianceMatrix(x, y, halfWindowSize, halfWindowSize, sourceProductType,
                                    sourceTiles, dataBuffers, CRe, CIm);
                        } else {
                            getMeanCovarianceMatrixC2(x, y, halfWindowSize, halfWindowSize, sourceImageWidth,
                                    sourceImageHeight, sourceProductType, sourceTiles, dataBuffers, CRe, CIm);
                        }

                        convertToFeaturePartialScatteringVector(CRe, CIm, tRe, tIm);
                        transMat.getTransformedVector(tRe, tIm, tTrRe, tTrIm);

                        final double scrPixel = computeSCR(tTrRe, tTrIm);
                        final double gamma = 1.0 / Math.sqrt(1.0 + redR / scrPixel);
                        final double mask = (gamma < threshold) ? 0.0 : gamma;
                        detMskData.setElemFloatAt(tgtIdx, (float) mask);
                    }
                }
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        } finally {
            pm.done();
        }
    }

    private synchronized void estimateTransMatrix(final PolBandUtils.PolSourceBand bandList) {
        if (prodToTransMatMap.get(bandList) != null) {
            return;
        }

        final double[] tRe = new double[vectorDim];
        final double[] tIm = new double[vectorDim];
        ComputeFeaturePartialScatteringVector(bandList, tRe, tIm);

        final double[] wRe = new double[vectorDim];
        final double[] wIm = new double[vectorDim];
        ComputeScatteringMechanism(tRe, tIm, wRe, wIm);

        final double[][] uRe = new double[vectorDim][vectorDim];
        final double[][] uIm = new double[vectorDim][vectorDim];
        ComputeTransformationMatrix(wRe, wIm, uRe, uIm);

        prodToTransMatMap.put(bandList, new TransformationMatrix(uRe, uIm));
    }

    private void ComputeFeaturePartialScatteringVector(final PolBandUtils.PolSourceBand bandList,
                                                       final double[] tRe, final double[] tIm) {
        if (useSupervisedDetection) {
            EstimateFeaturePartialScatteringVector(bandList, tRe, tIm);
        } else {
            // Canonical unsupervised fire-scar mechanism (Marino 2012 Table II)
            final double sin = Math.sin(DEFAULT_ALPHA_DEG * Constants.DTOR);
            final double cos = Math.cos(DEFAULT_ALPHA_DEG * Constants.DTOR);
            final double rhoInv = Math.pow(10.0, -DEFAULT_RHO_DB / 10.0);
            tRe[0] = cos * cos + 2.0 * rhoInv;
            tIm[0] = 0.0;
            tRe[1] = sin * sin + rhoInv;
            tIm[1] = 0.0;
            tRe[2] = rhoInv;
            tIm[2] = 0.0;
            tRe[3] = cos * sin * Math.cos(DEFAULT_MU);
            tIm[3] = -cos * sin * Math.sin(DEFAULT_MU);
            tRe[4] = 0.0;
            tIm[4] = 0.0;
            tRe[5] = 0.0;
            tIm[5] = 0.0;
        }
    }

    private void EstimateFeaturePartialScatteringVector(
            final PolBandUtils.PolSourceBand bandList, final double[] tRe, final double[] tIm) {

        final String[] subGeometries = createSubGeometries(sourceProduct, fireScarGeometry);

        try {
            final double[] sumRe = new double[vectorDim];
            final double[] sumIm = new double[vectorDim];
            int counter = 0;
            for (final String geom : subGeometries) {

                final VectorDataNode vec = sourceProduct.getVectorDataGroup().get(geom);
                final int minX = Math.max(0, (int) vec.getEnvelope().getMinX() - 1);
                final int minY = Math.max(0, (int) vec.getEnvelope().getMinY() - 1);
                final int maxX = Math.min(sourceImageWidth - 1, (int) vec.getEnvelope().getMaxX() + 1);
                final int maxY = Math.min(sourceImageHeight - 1, (int) vec.getEnvelope().getMaxY() + 1);
                final int width = maxX - minX;
                final int height = maxY - minY;
                if (width <= 0 || height <= 0) {
                    continue;
                }

                final Tile[] sourceTiles = new Tile[bandList.srcBands.length];
                final ProductData[] dataBuffers = new ProductData[bandList.srcBands.length];
                final Rectangle sourceRectangle = getSourceRectangle(minX, minY, width, height);

                if (isQuadPol) {
                    getQuadPolDataBuffer(
                            this, bandList.srcBands, sourceRectangle, sourceProductType, sourceTiles, dataBuffers);
                } else {
                    getDataBuffer(this, bandList.srcBands, sourceRectangle, sourceProductType, sourceTiles, dataBuffers);
                }

                final Mask band = sourceProduct.getMaskGroup().get(geom);
                final int[] data = new int[width];
                final double[][] CRe = new double[matrixDim][matrixDim];
                final double[][] CIm = new double[matrixDim][matrixDim];
                final double[] tmpRe = new double[vectorDim];
                final double[] tmpIm = new double[vectorDim];

                for (int y = minY; y < maxY; ++y) {
                    band.readPixels(minX, y, width, 1, data);
                    for (int x = minX; x < maxX; ++x) {
                        if (data[x - minX] == 0) continue;

                        if (isQuadPol) {
                            getMeanCovarianceMatrix(x, y, halfWindowSize, halfWindowSize,
                                    sourceProductType, sourceTiles, dataBuffers, CRe, CIm);
                        } else {
                            getMeanCovarianceMatrixC2(x, y, halfWindowSize, halfWindowSize, sourceImageWidth,
                                    sourceImageHeight, sourceProductType, sourceTiles, dataBuffers, CRe, CIm);
                        }

                        convertToFeaturePartialScatteringVector(CRe, CIm, tmpRe, tmpIm);
                        addVectors(tmpRe, tmpIm, sumRe, sumIm);
                        counter++;
                    }
                }
            }

            if (counter > 0) {
                for (int i = 0; i < tRe.length; ++i) {
                    tRe[i] = sumRe[i] / counter;
                    tIm[i] = sumIm[i] / counter;
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        } finally {
            removeSubGeometries(sourceProduct, subGeometries);
        }
    }

    private static String[] createSubGeometries(final Product product, final String geometry) {
        final List<String> subGeometries = new ArrayList<>();
        try {
            final VectorDataNode vec = product.getVectorDataGroup().get(geometry);
            final FeatureCollection featCollection = vec.getFeatureCollection();
            int i = 1;
            final FeatureIterator f = featCollection.features();
            while (f.hasNext()) {
                final SimpleFeature feature = (SimpleFeature) f.next();
                final String subGeomName = geometry + '_' + i;
                final VectorDataNode vectorDataNode = new VectorDataNode(subGeomName, vec.getFeatureType());
                vectorDataNode.getFeatureCollection().add(feature);
                product.getVectorDataGroup().add(vectorDataNode);
                subGeometries.add(subGeomName);
                ++i;
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return subGeometries.toArray(new String[0]);
    }

    private static void removeSubGeometries(final Product product, final String[] subGeometries) {
        try {
            for (String subGeom : subGeometries) {
                final VectorDataNode vectorDataNode = product.getVectorDataGroup().get(subGeom);
                product.getVectorDataGroup().remove(vectorDataNode);
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private void ComputeScatteringMechanism(
            final double[] tRe, final double[] tIm, final double[] wRe, final double[] wIm) {
        double tNorm2 = 0.0;
        for (int i = 0; i < tRe.length; ++i) {
            tNorm2 += tRe[i] * tRe[i] + tIm[i] * tIm[i];
        }
        if (tNorm2 <= 0.0) {
            throw new OperatorException("Invalid scattering mechanism.");
        }
        final double tNorm = Math.sqrt(tNorm2);
        for (int i = 0; i < wRe.length; ++i) {
            wRe[i] = tRe[i] / tNorm;
            wIm[i] = tIm[i] / tNorm;
        }
    }

    private void ComputeTransformationMatrix(
            final double[] wRe, final double[] wIm, final double[][] uRe, final double[][] uIm) {

        final int n = wRe.length;
        final List<double[]> basisRe = new ArrayList<>(n);
        final List<double[]> basisIm = new ArrayList<>(n);
        basisRe.add(wRe);
        basisIm.add(wIm);
        for (int i = 1; i < n; ++i) {
            getNextBasisVector(basisRe, basisIm);
        }

        final Matrix MRe = new Matrix(n, n);
        final Matrix MIm = new Matrix(n, n);
        for (int i = 0; i < n; ++i) {
            final double[] colRe = basisRe.get(i);
            final double[] colIm = basisIm.get(i);
            for (int j = 0; j < n; ++j) {
                MRe.set(j, i, colRe[j]);
                MIm.set(j, i, colIm[j]);
            }
        }

        final Matrix invMRe = MIm.times(MRe.inverse()).times(MIm).plus(MRe).inverse();
        final Matrix invMIm = MRe.inverse().times(-1.0).times(MIm).times(invMRe);
        for (int i = 0; i < n; ++i) {
            for (int j = 0; j < n; ++j) {
                uRe[i][j] = invMRe.get(i, j);
                uIm[i][j] = invMIm.get(i, j);
            }
        }
    }

    private void getNextBasisVector(final List<double[]> basisRe, final List<double[]> basisIm) {
        final int numVectors = basisRe.size();
        final int dim = basisRe.get(0).length;

        boolean successful = false;
        Matrix vRe = null;
        Matrix vIm = null;
        while (!successful) {
            vRe = new Matrix(getRandomMatrix(dim, 1));
            vIm = new Matrix(getRandomMatrix(dim, 1));

            for (int i = 0; i < numVectors; ++i) {
                final Matrix pRe = new Matrix(dim, 1);
                final Matrix pIm = new Matrix(dim, 1);
                computeProjection(vRe, vIm, basisRe.get(i), basisIm.get(i), pRe, pIm);
                vRe = vRe.minus(pRe);
                vIm = vIm.minus(pIm);
            }

            if (vRe.norm2() > 0.0 || vIm.norm2() > 0.0) {
                successful = true;
            }
        }

        final double normRe = vRe.norm2();
        final double normIm = vIm.norm2();
        final double norm = Math.sqrt(normRe * normRe + normIm * normIm);
        vRe = vRe.times(1.0 / norm);
        vIm = vIm.times(1.0 / norm);

        final double[] uRe = new double[dim];
        final double[] uIm = new double[dim];
        for (int i = 0; i < dim; ++i) {
            uRe[i] = vRe.get(i, 0);
            uIm[i] = vIm.get(i, 0);
        }
        basisRe.add(uRe);
        basisIm.add(uIm);
    }

    private void computeProjection(final Matrix vRe, final Matrix vIm, final double[] bRe, final double[] bIm,
                                   final Matrix pRe, final Matrix pIm) {
        final int dim = bRe.length;
        final Matrix uRe = new Matrix(dim, 1);
        final Matrix uIm = new Matrix(dim, 1);
        for (int i = 0; i < dim; ++i) {
            uRe.set(i, 0, bRe[i]);
            uIm.set(i, 0, bIm[i]);
        }

        final double c1 = uRe.transpose().times(vRe).plus(uIm.transpose().times(vIm)).get(0, 0);
        final double c2 = uRe.transpose().times(vIm).minus(uIm.transpose().times(vRe)).get(0, 0);

        final double[][] tRe = uRe.times(c1).minus(uIm.times(c2)).getArray();
        final double[][] tIm = uRe.times(c2).plus(uIm.times(c1)).getArray();

        for (int i = 0; i < dim; ++i) {
            pRe.set(i, 0, tRe[i][0]);
            pIm.set(i, 0, tIm[i][0]);
        }
    }

    private double[][] getRandomMatrix(final int m, final int n) {
        final Random randomGenerator = new Random();
        final double[][] M = new double[m][n];
        for (int i = 0; i < m; ++i) {
            for (int j = 0; j < n; ++j) {
                M[i][j] = randomGenerator.nextDouble();
            }
        }
        return M;
    }

    private void convertToFeaturePartialScatteringVector(
            final double[][] CRe, final double[][] CIm, final double[] tRe, final double[] tIm) {

        if (isQuadPol) {
            tRe[0] = CRe[0][0];
            tRe[1] = CRe[1][1];
            tRe[2] = CRe[2][2];
            tRe[3] = CRe[1][0];
            tRe[4] = CRe[2][0];
            tRe[5] = CRe[2][1];

            tIm[0] = 0.0;
            tIm[1] = 0.0;
            tIm[2] = 0.0;
            tIm[3] = CIm[1][0];
            tIm[4] = CIm[2][0];
            tIm[5] = CIm[2][1];
        } else {
            tRe[0] = CRe[0][0];
            tRe[1] = CRe[1][1];
            tRe[2] = CRe[1][0];
            tIm[0] = 0.0;
            tIm[1] = 0.0;
            tIm[2] = CIm[1][0];
        }
    }

    private static void addVectors(final double[] tRe, final double[] tIm, final double[] sumRe, final double[] sumIm) {
        for (int i = 0; i < tRe.length; ++i) {
            sumRe[i] += tRe[i];
            sumIm[i] += tIm[i];
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

    private double computeSCR(final double[] tRe, final double[] tIm) {
        final double pt = tRe[0] * tRe[0] + tIm[0] * tIm[0];
        double pc = 0.0;
        for (int i = 1; i < tRe.length; ++i) {
            pc += tRe[i] * tRe[i] + tIm[i] * tIm[i];
        }
        return pt / pc;
    }

    public static class TransformationMatrix {
        private final double[][] TRe;
        private final double[][] TIm;

        public TransformationMatrix(final double[][] TRe, final double[][] TIm) {
            final int numRows = TRe.length;
            final int numCols = TRe[0].length;
            this.TRe = new double[numRows][numCols];
            this.TIm = new double[numRows][numCols];
            for (int i = 0; i < numRows; ++i) {
                for (int j = 0; j < numCols; ++j) {
                    this.TRe[i][j] = TRe[i][j];
                    this.TIm[i][j] = TIm[i][j];
                }
            }
        }

        public void getTransformedVector(
                final double[] inRe, final double[] inIm, final double[] outRe, final double[] outIm) {
            if (inRe.length != TRe[0].length || outRe.length != TRe.length) {
                throw new OperatorException("getTransformedVector: Invalid vector dimension for input or output vector.");
            }
            for (int i = 0; i < outRe.length; ++i) {
                double sumRe = 0.0, sumIm = 0.0;
                for (int j = 0; j < inRe.length; ++j) {
                    sumRe += TRe[i][j] * inRe[j] - TIm[i][j] * inIm[j];
                    sumIm += TRe[i][j] * inIm[j] + TIm[i][j] * inRe[j];
                }
                outRe[i] = sumRe;
                outIm[i] = sumIm;
            }
        }
    }

    /**
     * Fill {@code sourceTiles} / {@code dataBuffers} for dual-pol C2,
     * DUAL_HH_HV, DUAL_HH_VV, DUAL_VH_VV input layouts.
     */
    private static void getDataBuffer(final Operator op, final Band[] srcBands, final Rectangle sourceRectangle,
                                      final PolBandUtils.MATRIX sourceProductType,
                                      final Tile[] sourceTiles, final ProductData[] dataBuffers) {

        for (Band band : srcBands) {
            final String bandName = band.getName();
            if (sourceProductType == PolBandUtils.MATRIX.C2) {
                if (bandName.contains("C11")) {
                    sourceTiles[0] = op.getSourceTile(band, sourceRectangle);
                    dataBuffers[0] = sourceTiles[0].getDataBuffer();
                } else if (bandName.contains("C12_real")) {
                    sourceTiles[1] = op.getSourceTile(band, sourceRectangle);
                    dataBuffers[1] = sourceTiles[1].getDataBuffer();
                } else if (bandName.contains("C12_imag")) {
                    sourceTiles[2] = op.getSourceTile(band, sourceRectangle);
                    dataBuffers[2] = sourceTiles[2].getDataBuffer();
                } else if (bandName.contains("C22")) {
                    sourceTiles[3] = op.getSourceTile(band, sourceRectangle);
                    dataBuffers[3] = sourceTiles[3].getDataBuffer();
                }
            } else if (sourceProductType == PolBandUtils.MATRIX.DUAL_HH_HV) {
                if (bandName.contains("i_HH")) { sourceTiles[0] = op.getSourceTile(band, sourceRectangle); dataBuffers[0] = sourceTiles[0].getDataBuffer(); }
                else if (bandName.contains("q_HH")) { sourceTiles[1] = op.getSourceTile(band, sourceRectangle); dataBuffers[1] = sourceTiles[1].getDataBuffer(); }
                else if (bandName.contains("i_HV")) { sourceTiles[2] = op.getSourceTile(band, sourceRectangle); dataBuffers[2] = sourceTiles[2].getDataBuffer(); }
                else if (bandName.contains("q_HV")) { sourceTiles[3] = op.getSourceTile(band, sourceRectangle); dataBuffers[3] = sourceTiles[3].getDataBuffer(); }
            } else if (sourceProductType == PolBandUtils.MATRIX.DUAL_HH_VV) {
                if (bandName.contains("i_HH")) { sourceTiles[0] = op.getSourceTile(band, sourceRectangle); dataBuffers[0] = sourceTiles[0].getDataBuffer(); }
                else if (bandName.contains("q_HH")) { sourceTiles[1] = op.getSourceTile(band, sourceRectangle); dataBuffers[1] = sourceTiles[1].getDataBuffer(); }
                else if (bandName.contains("i_VV")) { sourceTiles[2] = op.getSourceTile(band, sourceRectangle); dataBuffers[2] = sourceTiles[2].getDataBuffer(); }
                else if (bandName.contains("q_VV")) { sourceTiles[3] = op.getSourceTile(band, sourceRectangle); dataBuffers[3] = sourceTiles[3].getDataBuffer(); }
            } else if (sourceProductType == PolBandUtils.MATRIX.DUAL_VH_VV) {
                if (bandName.contains("i_VH")) { sourceTiles[0] = op.getSourceTile(band, sourceRectangle); dataBuffers[0] = sourceTiles[0].getDataBuffer(); }
                else if (bandName.contains("q_VH")) { sourceTiles[1] = op.getSourceTile(band, sourceRectangle); dataBuffers[1] = sourceTiles[1].getDataBuffer(); }
                else if (bandName.contains("i_VV")) { sourceTiles[2] = op.getSourceTile(band, sourceRectangle); dataBuffers[2] = sourceTiles[2].getDataBuffer(); }
                else if (bandName.contains("q_VV")) { sourceTiles[3] = op.getSourceTile(band, sourceRectangle); dataBuffers[3] = sourceTiles[3].getDataBuffer(); }
            }
        }
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(PartialTargetDetectionOp.class);
        }
    }
}
