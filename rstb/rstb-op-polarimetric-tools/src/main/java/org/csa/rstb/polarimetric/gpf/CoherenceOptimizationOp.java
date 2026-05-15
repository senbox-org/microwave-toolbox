/*
 * Copyright (C) 2016 by Array Systems Computing Inc. http://www.array.ca
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

import Jama.EigenvalueDecomposition;
import Jama.Matrix;
import com.bc.ceres.core.ProgressMonitor;
import org.csa.rstb.polarimetric.gpf.support.QuadPolProcessor;
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
import org.esa.snap.core.util.StringUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import org.esa.snap.engine_utilities.gpf.StackUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;

import java.awt.Rectangle;
import java.util.Map;

/**
 * Polarimetric SAR Interferometry coherence optimisation.
 *
 * For a quad-pol coregistered master + N-slave stack, recovers the three
 * "optimum" complex coherences per slave per pixel by:
 *
 *   1. Estimating, inside a sliding window, the 3 x 3 Pauli-basis coherency
 *      matrices T11 (master), T22 (slave) and the cross-Hermitian T12.
 *   2. Forming V1 = T11^(-1) T12 T22^(-1) T12^H  and
 *              V2 = T22^(-1) T12^H T11^(-1) T12   (general complex 3x3).
 *   3. Solving the general complex eigenvalue problem of V1 and V2 (via the
 *      2N x 2N real-symmetric embedding consumed by Jama's
 *      {@link EigenvalueDecomposition}).
 *   4. For each paired eigenvector (w1, w2), computing the optimum
 *      coherence
 *          gamma_opt = (w1^H T12 w2) / sqrt( (w1^H T11 w1) (w2^H T22 w2) ).
 *
 * Reference: Cloude, S. R. and Papathanassiou, K. P. (1998).
 *   "Polarimetric SAR interferometry." IEEE Transactions on Geoscience and
 *   Remote Sensing, 36(5), 1551-1565. DOI: 10.1109/36.718859
 *
 * Inputs: full-polarisation coregistered stack (HH, HV, VH, VV; master tag
 * 'mst' and slave tag 'slv') with i_/q_ band pairs per polarisation per
 * acquisition.
 *
 * Output: three coherence-magnitude or complex bands per slave acquisition,
 * named coh_opt_{1,2,3}_&lt;slaveDate&gt; (or i_coh_opt_*, q_coh_opt_* when
 * outputInComplex=true, with virtual intensity + phase bands).
 */
@OperatorMetadata(alias = "CoherenceOptimization",
        category = "Radar/Polarimetric",
        authors = "Jun Lu, Luis Veci",
        version = "1.0",
        copyright = "Copyright (C) 2016 by Array Systems Computing Inc.",
        description = "Estimate the three optimum PolInSAR coherences (Cloude & Papathanassiou 1998) from a coregistered quad-pol stack.")
public class CoherenceOptimizationOp extends Operator implements QuadPolProcessor {

    @SourceProduct
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(description = "The sliding window size", interval = "[1, 100]", defaultValue = "5", label = "Window Size")
    private int windowSize = 5;

    @Parameter(description = "Output optimum coherence in complex (i/q) instead of magnitude", defaultValue = "false",
            label = "Output optimum coherence in complex")
    private boolean outputInComplex = false;

    private int halfWindowSize = 0;
    private int sourceImageWidth = 0;
    private int sourceImageHeight = 0;
    private int numOfSlaveProducts = 0;
    private final Band[] masterBands = new Band[8];
    private Band[] slaveBands = null;
    private String[] slaveDates = null;
    private String[] targetBandNames = null;

    private static final String PRODUCT_NAME = "coherence_opt";

    @Override
    public void initialize() throws OperatorException {
        try {
            halfWindowSize = windowSize / 2;
            getSourceBands();
            createTargetProduct();
        } catch (Exception e) {
            throw new OperatorException(e);
        }
    }

    private void getSourceBands() {

        final String masterTag = "mst";
        final MetadataElement masterMetaData = AbstractMetadata.getAbstractedMetadata(sourceProduct);
        final String masterDate = OperatorUtils.getAcquisitionDate(masterMetaData);
        getBands(masterTag, masterDate, masterMetaData, masterBands, 0);

        final String slaveTag = "slv";
        final String slaveMetadataRoot = AbstractMetadata.SLAVE_METADATA_ROOT;
        final MetadataElement[] slaveMetaData = sourceProduct.getMetadataRoot().getElement(slaveMetadataRoot).getElements();

        numOfSlaveProducts = slaveMetaData.length;
        slaveBands = new Band[numOfSlaveProducts * 8];
        slaveDates = new String[numOfSlaveProducts];
        for (int i = 0; i < numOfSlaveProducts; i++) {
            slaveDates[i] = OperatorUtils.getAcquisitionDate(slaveMetaData[i]);
            getBands(slaveTag, slaveDates[i], slaveMetaData[i], slaveBands, i * 8);
        }
    }

    /**
     * Discover the 8 i/q bands (HH, HV, VH, VV × i/q) for a master or
     * slave acquisition and slot them into {@code sourceBands} at the
     * given {@code idx}.
     */
    private void getBands(
            final String tag, final String date, final MetadataElement root, final Band[] sourceBands, final int idx)
            throws OperatorException {

        boolean hasHH_i = false, hasHH_q = false;
        boolean hasHV_i = false, hasHV_q = false;
        boolean hasVV_i = false, hasVV_q = false;
        boolean hasVH_i = false, hasVH_q = false;

        String[] masterBandNames = null;
        if (tag.contains("mst")) {
            masterBandNames = StackUtils.getMasterBandNames(sourceProduct);
        }

        final int numOfBands = sourceProduct.getNumBands();
        final String[] bandNames = sourceProduct.getBandNames();
        for (int i = 0; i < numOfBands; i++) {
            final String bandName = bandNames[i];

            if (masterBandNames != null) {
                if (!StringUtils.contains(masterBandNames, bandName)) {
                    continue;
                }
            } else {
                if (!bandName.contains(tag) || !bandName.contains(date)) {
                    continue;
                }
            }

            final Band band = sourceProduct.getBand(bandName);
            final Unit.UnitType bandUnit = Unit.getUnitType(band);
            final String pol = OperatorUtils.getBandPolarization(band.getName(), root);

            if (pol.contains("hh") && bandUnit == Unit.UnitType.REAL) {
                sourceBands[idx] = band;
                hasHH_i = true;
            } else if (pol.contains("hh") && bandUnit == Unit.UnitType.IMAGINARY) {
                sourceBands[idx + 1] = band;
                hasHH_q = true;
            } else if (pol.contains("hv") && bandUnit == Unit.UnitType.REAL) {
                sourceBands[idx + 2] = band;
                hasHV_i = true;
            } else if (pol.contains("hv") && bandUnit == Unit.UnitType.IMAGINARY) {
                sourceBands[idx + 3] = band;
                hasHV_q = true;
            } else if (pol.contains("vh") && bandUnit == Unit.UnitType.REAL) {
                sourceBands[idx + 4] = band;
                hasVH_i = true;
            } else if (pol.contains("vh") && bandUnit == Unit.UnitType.IMAGINARY) {
                sourceBands[idx + 5] = band;
                hasVH_q = true;
            } else if (pol.contains("vv") && bandUnit == Unit.UnitType.REAL) {
                sourceBands[idx + 6] = band;
                hasVV_i = true;
            } else if (pol.contains("vv") && bandUnit == Unit.UnitType.IMAGINARY) {
                sourceBands[idx + 7] = band;
                hasVV_q = true;
            }
        }

        if (!hasHH_i || !hasHH_q || !hasHV_i || !hasHV_q || !hasVV_i || !hasVV_q || !hasVH_i || !hasVH_q) {
            throw new OperatorException("Full polarisation coregistered stack is expected.");
        }
    }

    private void createTargetProduct() {
        sourceImageWidth = sourceProduct.getSceneRasterWidth();
        sourceImageHeight = sourceProduct.getSceneRasterHeight();

        targetProduct = new Product(PRODUCT_NAME,
                sourceProduct.getProductType(),
                sourceImageWidth,
                sourceImageHeight);

        addSelectedBands();

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);
    }

    private void addSelectedBands() {

        final String[] prefix;
        final int n;
        if (outputInComplex) {
            n = 6;
            prefix = new String[]{
                    "i_coh_opt_1", "q_coh_opt_1",
                    "i_coh_opt_2", "q_coh_opt_2",
                    "i_coh_opt_3", "q_coh_opt_3"};
        } else {
            n = 3;
            prefix = new String[]{"coh_opt_1", "coh_opt_2", "coh_opt_3"};
        }

        targetBandNames = new String[numOfSlaveProducts * n];

        for (int i = 0; i < numOfSlaveProducts; i++) {

            for (int j = 0; j < n; j++) {
                targetBandNames[i * n + j] = prefix[j] + '_' + slaveDates[i];
            }

            if (outputInComplex) {
                for (int j = 0; j < n; j = j + 2) {
                    final Band targetBandI = targetProduct.addBand(targetBandNames[i * n + j], ProductData.TYPE_FLOAT32);
                    targetBandI.setUnit(Unit.REAL);
                    final Band targetBandQ = targetProduct.addBand(targetBandNames[i * n + j + 1], ProductData.TYPE_FLOAT32);
                    targetBandQ.setUnit(Unit.IMAGINARY);

                    final String suffix = "_coh_opt_" + (j / 2 + 1) + '_' + slaveDates[i];
                    ReaderUtils.createVirtualIntensityBand(targetProduct, targetBandI, targetBandQ, suffix);
                    ReaderUtils.createVirtualPhaseBand(targetProduct, targetBandI, targetBandQ, suffix);
                }
            } else {
                for (int j = 0; j < n; j++) {
                    final Band targetBand = targetProduct.addBand(targetBandNames[i * n + j], ProductData.TYPE_FLOAT32);
                    targetBand.setUnit(Unit.INTENSITY);
                }
            }
        }
    }

    @Override
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle targetRectangle, ProgressMonitor pm)
            throws OperatorException {
        try {
            final int x0 = targetRectangle.x;
            final int y0 = targetRectangle.y;
            final int w = targetRectangle.width;
            final int h = targetRectangle.height;
            final int maxY = y0 + h;
            final int maxX = x0 + w;

            final TileIndex trgIndex = new TileIndex(targetTiles.get(getTargetProduct().getBandAt(0)));
            final Rectangle sourceRectangle = getSourceRectangle(x0, y0, w, h);

            final Tile sourceTile = getSourceTile(masterBands[0], sourceRectangle);
            final ProductData[] masterDataBuffers = new ProductData[masterBands.length];
            for (int i = 0; i < masterBands.length; ++i) {
                masterDataBuffers[i] = getSourceTile(masterBands[i], sourceRectangle).getDataBuffer();
            }

            final int n = 8;
            final int m = outputInComplex ? 6 : 3;
            for (int i = 0; i < numOfSlaveProducts; ++i) {

                final ProductData[] slaveDataBuffers = new ProductData[n];
                for (int j = 0; j < slaveDataBuffers.length; ++j) {
                    slaveDataBuffers[j] = getSourceTile(slaveBands[i * n + j], sourceRectangle).getDataBuffer();
                }

                final ProductData[] targetDataBuffers = new ProductData[m];
                for (int j = 0; j < targetDataBuffers.length; ++j) {
                    targetDataBuffers[j] = targetTiles.get(targetProduct.getBand(targetBandNames[i * m + j])).getDataBuffer();
                }

                computeOptimumCoherence(x0, y0, maxX, maxY, trgIndex, sourceTile,
                        masterDataBuffers, slaveDataBuffers, targetDataBuffers);
            }
        } catch (Exception e) {
            throw new OperatorException(e);
        }
    }

    private void computeOptimumCoherence(final int x0, final int y0, final int maxX, final int maxY,
                                         final TileIndex trgIndex, final Tile sourceTile,
                                         final ProductData[] masterDataBuffers, final ProductData[] slaveDataBuffers,
                                         final ProductData[] targetDataBuffers) {

        final double[][] T11Re = new double[3][3];
        final double[][] T11Im = new double[3][3];
        final double[][] T12Re = new double[3][3];
        final double[][] T12Im = new double[3][3];
        final double[][] T22Re = new double[3][3];
        final double[][] T22Im = new double[3][3];

        final double[][] V1Re = new double[3][3];
        final double[][] V1Im = new double[3][3];
        final double[][] V2Re = new double[3][3];
        final double[][] V2Im = new double[3][3];

        final double[][] EigenVect1Re = new double[3][3];
        final double[][] EigenVect1Im = new double[3][3];
        final double[][] EigenVect2Re = new double[3][3];
        final double[][] EigenVect2Im = new double[3][3];
        final double[] EigenVal1 = new double[3];
        final double[] EigenVal2 = new double[3];

        final double noDataValue = 0.0;

        for (int y = y0; y < maxY; ++y) {
            trgIndex.calculateStride(y);
            for (int x = x0; x < maxX; ++x) {
                final int idx = trgIndex.getIndex(x);

                getMeanMatrices(x, y, sourceTile, masterDataBuffers, slaveDataBuffers,
                        T11Re, T11Im, T12Re, T12Im, T22Re, T22Im);

                final boolean T11Valid = checkMatrixCondition(T11Re, T11Im);
                final boolean T22Valid = T11Valid && checkMatrixCondition(T22Re, T22Im);
                if (!T11Valid || !T22Valid) {
                    for (ProductData targetDataBuffer : targetDataBuffers) {
                        targetDataBuffer.setElemFloatAt(idx, (float) noDataValue);
                    }
                    continue;
                }

                computeV1V2Matrices(T11Re, T11Im, T12Re, T12Im, T22Re, T22Im, V1Re, V1Im, V2Re, V2Im);

                eigenDecompGeneral(3, V1Re, V1Im, EigenVect1Re, EigenVect1Im, EigenVal1);
                eigenDecompGeneral(3, V2Re, V2Im, EigenVect2Re, EigenVect2Im, EigenVal2);

                final CohData data = computeCoherence(T11Re, T11Im, T12Re, T12Im, T22Re, T22Im,
                        EigenVect1Re, EigenVect1Im, EigenVect2Re, EigenVect2Im);

                if (outputInComplex) {
                    targetDataBuffers[0].setElemFloatAt(idx, (float) data.i_coh_opt_1);
                    targetDataBuffers[1].setElemFloatAt(idx, (float) data.q_coh_opt_1);
                    targetDataBuffers[2].setElemFloatAt(idx, (float) data.i_coh_opt_2);
                    targetDataBuffers[3].setElemFloatAt(idx, (float) data.q_coh_opt_2);
                    targetDataBuffers[4].setElemFloatAt(idx, (float) data.i_coh_opt_3);
                    targetDataBuffers[5].setElemFloatAt(idx, (float) data.q_coh_opt_3);
                } else {
                    targetDataBuffers[0].setElemFloatAt(idx,
                            (float) (data.i_coh_opt_1 * data.i_coh_opt_1 + data.q_coh_opt_1 * data.q_coh_opt_1));
                    targetDataBuffers[1].setElemFloatAt(idx,
                            (float) (data.i_coh_opt_2 * data.i_coh_opt_2 + data.q_coh_opt_2 * data.q_coh_opt_2));
                    targetDataBuffers[2].setElemFloatAt(idx,
                            (float) (data.i_coh_opt_3 * data.i_coh_opt_3 + data.q_coh_opt_3 * data.q_coh_opt_3));
                }
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

    private void getMeanMatrices(final int x, final int y, final Tile sourceTile,
                                 final ProductData[] masterDataBuffers, final ProductData[] slaveDataBuffers,
                                 final double[][] T11Re, final double[][] T11Im,
                                 final double[][] T12Re, final double[][] T12Im,
                                 final double[][] T22Re, final double[][] T22Im) {

        final double[][] S1Re = new double[2][2];
        final double[][] S1Im = new double[2][2];
        final double[][] S2Re = new double[2][2];
        final double[][] S2Im = new double[2][2];
        final double[][] tempTr = new double[3][3];
        final double[][] tempTi = new double[3][3];

        final Matrix T11ReMat = new Matrix(3, 3);
        final Matrix T11ImMat = new Matrix(3, 3);
        final Matrix T12ReMat = new Matrix(3, 3);
        final Matrix T12ImMat = new Matrix(3, 3);
        final Matrix T22ReMat = new Matrix(3, 3);
        final Matrix T22ImMat = new Matrix(3, 3);

        final TileIndex srcIndex = new TileIndex(sourceTile);

        final int xSt = Math.max(x - halfWindowSize, 0);
        final int xEd = Math.min(x + halfWindowSize, sourceImageWidth - 1);
        final int ySt = Math.max(y - halfWindowSize, 0);
        final int yEd = Math.min(y + halfWindowSize, sourceImageHeight - 1);
        final int num = (yEd - ySt + 1) * (xEd - xSt + 1);

        for (int yy = ySt; yy <= yEd; ++yy) {
            srcIndex.calculateStride(yy);
            for (int xx = xSt; xx <= xEd; ++xx) {
                final int idx = srcIndex.getIndex(xx);

                getComplexScatterMatrix(idx, masterDataBuffers, S1Re, S1Im);
                getComplexScatterMatrix(idx, slaveDataBuffers, S2Re, S2Im);

                computeCoherencyMatrixT3(S1Re, S1Im, tempTr, tempTi);
                T11ReMat.plusEquals(new Matrix(tempTr));
                T11ImMat.plusEquals(new Matrix(tempTi));

                computeCoherencyMatrixT3(S2Re, S2Im, tempTr, tempTi);
                T22ReMat.plusEquals(new Matrix(tempTr));
                T22ImMat.plusEquals(new Matrix(tempTi));

                computeCorrelationMatrix(S1Re, S1Im, S2Re, S2Im, tempTr, tempTi);
                T12ReMat.plusEquals(new Matrix(tempTr));
                T12ImMat.plusEquals(new Matrix(tempTi));
            }
        }

        T11ReMat.timesEquals(1.0 / num);
        T11ImMat.timesEquals(1.0 / num);
        T12ReMat.timesEquals(1.0 / num);
        T12ImMat.timesEquals(1.0 / num);
        T22ReMat.timesEquals(1.0 / num);
        T22ImMat.timesEquals(1.0 / num);

        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                T11Re[i][j] = T11ReMat.get(i, j);
                T11Im[i][j] = T11ImMat.get(i, j);
                T12Re[i][j] = T12ReMat.get(i, j);
                T12Im[i][j] = T12ImMat.get(i, j);
                T22Re[i][j] = T22ReMat.get(i, j);
                T22Im[i][j] = T22ImMat.get(i, j);
            }
        }
    }

    /** Pauli-basis 3x3 cross-correlation matrix for two complex 2x2 scatterers. */
    private static void computeCorrelationMatrix(final double[][] scatter1Re, final double[][] scatter1Im,
                                                 final double[][] scatter2Re, final double[][] scatter2Im,
                                                 final double[][] Tr, final double[][] Ti) {

        final double s1HHr = scatter1Re[0][0];
        final double s1HHi = scatter1Im[0][0];
        final double s1HVr = scatter1Re[0][1];
        final double s1HVi = scatter1Im[0][1];
        final double s1VHr = scatter1Re[1][0];
        final double s1VHi = scatter1Im[1][0];
        final double s1VVr = scatter1Re[1][1];
        final double s1VVi = scatter1Im[1][1];

        final double s2HHr = scatter2Re[0][0];
        final double s2HHi = scatter2Im[0][0];
        final double s2HVr = scatter2Re[0][1];
        final double s2HVi = scatter2Im[0][1];
        final double s2VHr = scatter2Re[1][0];
        final double s2VHi = scatter2Im[1][0];
        final double s2VVr = scatter2Re[1][1];
        final double s2VVi = scatter2Im[1][1];

        final double k11r = (s1HHr + s1VVr) / Constants.sqrt2;
        final double k11i = (s1HHi + s1VVi) / Constants.sqrt2;
        final double k12r = (s1HHr - s1VVr) / Constants.sqrt2;
        final double k12i = (s1HHi - s1VVi) / Constants.sqrt2;
        final double k13r = (s1HVr + s1VHr) / Constants.sqrt2;
        final double k13i = (s1HVi + s1VHi) / Constants.sqrt2;

        final double k21r = (s2HHr + s2VVr) / Constants.sqrt2;
        final double k21i = (s2HHi + s2VVi) / Constants.sqrt2;
        final double k22r = (s2HHr - s2VVr) / Constants.sqrt2;
        final double k22i = (s2HHi - s2VVi) / Constants.sqrt2;
        final double k23r = (s2HVr + s2VHr) / Constants.sqrt2;
        final double k23i = (s2HVi + s2VHi) / Constants.sqrt2;

        Tr[0][0] = k11r * k21r + k11i * k21i;
        Ti[0][0] = k11i * k21r - k11r * k21i;

        Tr[0][1] = k11r * k22r + k11i * k22i;
        Ti[0][1] = k11i * k22r - k11r * k22i;

        Tr[0][2] = k11r * k23r + k11i * k23i;
        Ti[0][2] = k11i * k23r - k11r * k23i;

        Tr[1][0] = k12r * k21r + k12i * k21i;
        Ti[1][0] = k12i * k21r - k12r * k21i;

        Tr[1][1] = k12r * k22r + k12i * k22i;
        Ti[1][1] = k12i * k22r - k12r * k22i;

        Tr[1][2] = k12r * k23r + k12i * k23i;
        Ti[1][2] = k12i * k23r - k12r * k23i;

        Tr[2][0] = k13r * k21r + k13i * k21i;
        Ti[2][0] = k13i * k21r - k13r * k21i;

        Tr[2][1] = k13r * k22r + k13i * k22i;
        Ti[2][1] = k13i * k22r - k13r * k22i;

        Tr[2][2] = k13r * k23r + k13i * k23i;
        Ti[2][2] = k13i * k23r - k13r * k23i;
    }

    /** Reject T matrices whose 6x6 real embedding has near-zero determinant. */
    private static boolean checkMatrixCondition(final double[][] TRe, final double[][] TIm) {
        final double[][] M = new double[6][6];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                M[i][j] = TRe[i][j];
                M[i + 3][j + 3] = TRe[i][j];
                M[i][j + 3] = -TIm[i][j];
                M[i + 3][j] = TIm[i][j];
            }
        }
        return new Matrix(M).det() > 0.001;
    }

    private static void computeV1V2Matrices(final double[][] T11Re, final double[][] T11Im,
                                            final double[][] T12Re, final double[][] T12Im,
                                            final double[][] T22Re, final double[][] T22Im,
                                            final double[][] V1Re, final double[][] V1Im,
                                            final double[][] V2Re, final double[][] V2Im) {

        final double[][] invT11Re = new double[3][3];
        final double[][] invT11Im = new double[3][3];
        inverseComplexMatrix(3, T11Re, T11Im, invT11Re, invT11Im);
        final Matrix invT11ReMat = new Matrix(invT11Re);
        final Matrix invT11ImMat = new Matrix(invT11Im);

        final double[][] invT22Re = new double[3][3];
        final double[][] invT22Im = new double[3][3];
        inverseComplexMatrix(3, T22Re, T22Im, invT22Re, invT22Im);
        final Matrix invT22ReMat = new Matrix(invT22Re);
        final Matrix invT22ImMat = new Matrix(invT22Im);

        final Matrix T12ReMat = new Matrix(T12Re);
        final Matrix T12ImMat = new Matrix(T12Im);

        // V1 = T11^-1 T12 T22^-1 T12^H
        Matrix Tmp1Mat = invT11ReMat.times(T12ReMat).minus(invT11ImMat.times(T12ImMat));
        Matrix Tmp2Mat = invT11ImMat.times(T12ReMat).plus(invT11ReMat.times(T12ImMat));
        Matrix Tmp3Mat = invT22ReMat.times(T12ReMat.transpose()).plus(invT22ImMat.times(T12ImMat.transpose()));
        Matrix Tmp4Mat = invT22ImMat.times(T12ReMat.transpose()).minus(invT22ReMat.times(T12ImMat.transpose()));

        final Matrix V1ReMat = Tmp1Mat.times(Tmp3Mat).minus(Tmp2Mat.times(Tmp4Mat));
        final Matrix V1ImMat = Tmp1Mat.times(Tmp4Mat).plus(Tmp2Mat.times(Tmp3Mat));

        for (int j = 0; j < 3; ++j) {
            for (int i = 0; i < 3; ++i) {
                V1Re[j][i] = V1ReMat.get(j, i);
                V1Im[j][i] = V1ImMat.get(j, i);
            }
        }

        // V2 = T22^-1 T12^H T11^-1 T12
        Tmp1Mat = invT22ReMat.times(T12ReMat.transpose()).plus(invT22ImMat.times(T12ImMat.transpose()));
        Tmp2Mat = invT22ImMat.times(T12ReMat.transpose()).minus(invT22ReMat.times(T12ImMat.transpose()));
        Tmp3Mat = invT11ReMat.times(T12ReMat).minus(invT11ImMat.times(T12ImMat));
        Tmp4Mat = invT11ImMat.times(T12ReMat).plus(invT11ReMat.times(T12ImMat));

        final Matrix V2ReMat = Tmp1Mat.times(Tmp3Mat).minus(Tmp2Mat.times(Tmp4Mat));
        final Matrix V2ImMat = Tmp1Mat.times(Tmp4Mat).plus(Tmp2Mat.times(Tmp3Mat));

        for (int j = 0; j < 3; ++j) {
            for (int i = 0; i < 3; ++i) {
                V2Re[j][i] = V2ReMat.get(j, i);
                V2Im[j][i] = V2ImMat.get(j, i);
            }
        }
    }

    /** Eigenvalue decomposition of a general complex matrix via the 2N x 2N real embedding. */
    private static void eigenDecompGeneral(final int n, final double[][] HMr, final double[][] HMi,
                                           final double[][] EigenVectRe, final double[][] EigenVectIm,
                                           final double[] EigenVal) {
        final int n2 = n * 2;
        final double[][] H = new double[n2][n2];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                H[i][j] = HMr[i][j];
                H[n + i][n + j] = HMr[i][j];
                H[i][n + j] = -HMi[i][j];
                H[n + i][j] = HMi[i][j];
            }
        }

        final Matrix M = new Matrix(H);
        final EigenvalueDecomposition Evd = M.eig();
        final Matrix V = Evd.getV();
        final Matrix D = Evd.getD();

        final double[][] d = D.getArray();
        final double[][] v = V.getArray();
        double x;
        for (int i = 0; i < n2; i++) {
            for (int j = i + 1; j < n2; j++) {
                if (Math.abs(d[j][j]) > Math.abs(d[i][i])) {
                    x = d[i][i];
                    d[i][i] = d[j][j];
                    d[j][j] = x;
                    for (int k = 0; k < n2; k++) {
                        x = v[k][i];
                        v[k][i] = v[k][j];
                        v[k][j] = x;
                    }
                }
            }
        }

        for (int i = 0; i < n; i++) {
            final int i2 = i * 2;
            EigenVal[i] = d[i2][i2];
            for (int j = 0; j < n; j++) {
                EigenVectRe[j][i] = v[j][i2];
                EigenVectIm[j][i] = v[n + j][i2];
            }
        }
    }

    private static void inverseComplexMatrix(
            final int n, final double[][] Mr, final double[][] Mi, final double[][] invMr, final double[][] invMi) {

        final int n2 = n * 2;
        final double[][] M = new double[n2][n2];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                M[i][j] = Mr[i][j];
                M[i + n][j + n] = Mr[i][j];
                M[i][j + n] = -Mi[i][j];
                M[i + n][j] = Mi[i][j];
            }
        }

        final double[][] I = new double[n2][n];
        for (int i = 0; i < n2; i++) {
            for (int j = 0; j < n; j++) {
                I[i][j] = (i == j) ? 1.0 : 0.0;
            }
        }

        final Matrix MMat = new Matrix(M);
        final Matrix IMat = new Matrix(I);
        final Matrix invMMat = MMat.inverse().times(IMat);

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                invMr[i][j] = invMMat.get(i, j);
                invMi[i][j] = invMMat.get(i + n, j);
            }
        }
    }

    private static CohData computeCoherence(
            final double[][] T11Re, final double[][] T11Im,
            final double[][] T12Re, final double[][] T12Im,
            final double[][] T22Re, final double[][] T22Im,
            final double[][] EigenVect1Re, final double[][] EigenVect1Im,
            final double[][] EigenVect2Re, final double[][] EigenVect2Im) {

        final double[] i_coh_opt = new double[3];
        final double[] q_coh_opt = new double[3];

        final Matrix T11ReMat = new Matrix(T11Re);
        final Matrix T11ImMat = new Matrix(T11Im);
        final Matrix T12ReMat = new Matrix(T12Re);
        final Matrix T12ImMat = new Matrix(T12Im);
        final Matrix T22ReMat = new Matrix(T22Re);
        final Matrix T22ImMat = new Matrix(T22Im);

        for (int i = 0; i < 3; ++i) {
            final double[] w1Re = {EigenVect1Re[0][i], EigenVect1Re[1][i], EigenVect1Re[2][i]};
            final double[] w1Im = {EigenVect1Im[0][i], EigenVect1Im[1][i], EigenVect1Im[2][i]};
            final double[] w2Re = {EigenVect2Re[0][i], EigenVect2Re[1][i], EigenVect2Re[2][i]};
            final double[] w2Im = {EigenVect2Im[0][i], EigenVect2Im[1][i], EigenVect2Im[2][i]};

            final Matrix w1ReMat = new Matrix(w1Re, 3);
            final Matrix w1ImMat = new Matrix(w1Im, 3);
            final Matrix w2ReMat = new Matrix(w2Re, 3);
            final Matrix w2ImMat = new Matrix(w2Im, 3);

            final Matrix w1ReMatT = w1ReMat.transpose();
            final Matrix w1ImMatT = w1ImMat.transpose();
            final Matrix w2ReMatT = w2ReMat.transpose();
            final Matrix w2ImMatT = w2ImMat.transpose();

            double tmp1 = w1ReMatT.times(T11ReMat).times(w1ReMat).get(0, 0);
            double tmp2 = w1ReMatT.times(T11ImMat).times(w1ImMat).get(0, 0);
            double tmp3 = w1ImMatT.times(T11ReMat).times(w1ImMat).get(0, 0);
            double tmp4 = w1ImMatT.times(T11ImMat).times(w1ReMat).get(0, 0);
            final double w1T11w1 = tmp1 - tmp2 + tmp3 + tmp4;

            tmp1 = w2ReMatT.times(T22ReMat).times(w2ReMat).get(0, 0);
            tmp2 = w2ReMatT.times(T22ImMat).times(w2ImMat).get(0, 0);
            tmp3 = w2ImMatT.times(T22ReMat).times(w2ImMat).get(0, 0);
            tmp4 = w2ImMatT.times(T22ImMat).times(w2ReMat).get(0, 0);
            final double w2T22w2 = tmp1 - tmp2 + tmp3 + tmp4;

            final double normProduct = Math.sqrt(w1T11w1 * w2T22w2);

            tmp1 = w1ReMatT.times(T12ReMat).times(w2ReMat).get(0, 0);
            tmp2 = w1ReMatT.times(T12ImMat).times(w2ImMat).get(0, 0);
            tmp3 = w1ImMatT.times(T12ReMat).times(w2ImMat).get(0, 0);
            tmp4 = w1ImMatT.times(T12ImMat).times(w2ReMat).get(0, 0);
            final double w1T12w2Re = tmp1 - tmp2 + tmp3 + tmp4;

            tmp1 = w1ReMatT.times(T12ReMat).times(w2ImMat).get(0, 0);
            tmp2 = w1ReMatT.times(T12ImMat).times(w2ReMat).get(0, 0);
            tmp3 = w1ImMatT.times(T12ReMat).times(w2ReMat).get(0, 0);
            tmp4 = w1ImMatT.times(T12ImMat).times(w2ImMat).get(0, 0);
            final double w1T12w2Im = tmp1 + tmp2 - tmp3 + tmp4;

            i_coh_opt[i] = w1T12w2Re / normProduct;
            q_coh_opt[i] = w1T12w2Im / normProduct;
        }

        return new CohData(i_coh_opt[0], q_coh_opt[0], i_coh_opt[1], q_coh_opt[1], i_coh_opt[2], q_coh_opt[2]);
    }

    public static class CohData {
        public final double i_coh_opt_1;
        public final double q_coh_opt_1;
        public final double i_coh_opt_2;
        public final double q_coh_opt_2;
        public final double i_coh_opt_3;
        public final double q_coh_opt_3;

        public CohData(final double i_coh_opt_1, final double q_coh_opt_1,
                       final double i_coh_opt_2, final double q_coh_opt_2,
                       final double i_coh_opt_3, final double q_coh_opt_3) {
            this.i_coh_opt_1 = i_coh_opt_1;
            this.q_coh_opt_1 = q_coh_opt_1;
            this.i_coh_opt_2 = i_coh_opt_2;
            this.q_coh_opt_2 = q_coh_opt_2;
            this.i_coh_opt_3 = i_coh_opt_3;
            this.q_coh_opt_3 = q_coh_opt_3;
        }
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(CoherenceOptimizationOp.class);
        }
    }
}
