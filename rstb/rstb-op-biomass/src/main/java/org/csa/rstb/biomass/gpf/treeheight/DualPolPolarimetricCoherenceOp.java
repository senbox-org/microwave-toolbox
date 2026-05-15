/*
 * Copyright (C) 2016 by Array Systems Computing Inc. http://www.array.ca
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
package org.csa.rstb.biomass.gpf.treeheight;

import Jama.Matrix;
import com.bc.ceres.core.ProgressMonitor;
import org.csa.rstb.polarimetric.gpf.support.DualPolProcessor;
import org.csa.rstb.polarimetric.gpf.decompositions.EigenDecomposition;
import eu.esa.sar.commons.polsar.PolBandUtils;
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
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;
import org.esa.snap.engine_utilities.util.Maths;
import org.jblas.ComplexDouble;
import org.jblas.ComplexDoubleMatrix;
import org.jblas.DoubleMatrix;

import java.awt.*;
import java.text.DateFormat;
import java.util.Map;

@OperatorMetadata(alias = "DualPolPolarimetricCoherence",
        category = "Radar/Biomass",
        authors = "Jun Lu, Luis Veci",
        copyright = "Copyright (C) 2017 by Array Systems Computing Inc.",
        description = "Compute Polarimetric coherence for a stack of coregistered images")
public class DualPolPolarimetricCoherenceOp extends Operator implements DualPolProcessor {

    @SourceProduct
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(valueSet = {USING_COHERENCE_AMPLITUDE, USING_POLARIMETRIC_COHERENCE},
            defaultValue = USING_COHERENCE_AMPLITUDE, label = "For tree height estimation")
    private String algorithm = USING_COHERENCE_AMPLITUDE;

    @Parameter(valueSet = {"5", "7", "9", "11", "13", "15", "17", "19"}, defaultValue = "11",
            label = "Coherence window size")
    private String windowSizeStr = "11";

    @Parameter(description = "System correction factor", interval = "(0, 1]", defaultValue = "0.97",
            label = "System Correction Factor")
    private float sysCorrFactor = 0.97f;

    @Parameter(defaultValue = "true", label = "Mask Out Poor Coherence Area")
    private boolean maskOutPoorCoherenceArea = true;

    @Parameter(description = "Minimum Gamma SNR", interval = "(0, 1]", defaultValue = "0.8",
            label = "Minimum Gamma SNR")
    private float minGammaSNR = 0.8f;

    private MetadataElement absRoot = null;
    private MetadataElement mstRoot = null;
    private MetadataElement slvRoot = null;
    private int windowSize = 0;
    private int halfWindowSize = 0;
    private int sourceImageWidth = 0;
    private int sourceImageHeight = 0;
    private Band[] mstBands = new Band[4];
    private Band[] slvBands = new Band[4];
    private Band cohAmpBand = null;
    private Band cohPhaseBand = null;
    private Band groundPhaseBand = null;
    private Band schurPhaseDiffBand = null;
    private Band specklePhaseDiffBand = null;
    private double srcNoDataValue = 0.0;
    private double firstLineUTC = 0.0; // in days
    private double lastLineUTC = 0.0; // in days
    private double lineTimeInterval = 0.0; // in days
    private double slantRangeToFirstPixel = 0.0; // in m
    private double rangeSpacing = 0.0; // in m
    private boolean isA1 = true;

    private String[] targetBandNames = null;
    private double[] polyCoeff = null;
    private SinglePolCoherenceCompensationOp.NoiseRecord[] mstNoiseRecordsHH = null;
    private SinglePolCoherenceCompensationOp.NoiseRecord[] mstNoiseRecordsVV = null;
    private SinglePolCoherenceCompensationOp.NoiseRecord[] slvNoiseRecordsHH = null;
    private SinglePolCoherenceCompensationOp.NoiseRecord[] slvNoiseRecordsVV = null;

    private static final String USING_COHERENCE_AMPLITUDE = "Using Coherence Amplitude";
    private static final String USING_POLARIMETRIC_COHERENCE = "Using Polarimetric Coherence";
    private static final String PRODUCT_SUFFIX = "_Coh";
    private static final String COHERENCE_AMPLITUDE = "coherence_amplitude";
    private static final String COHERENCE_PHASE = "coherence_phase";
    private static final String GROUND_PHASE = "ground_phase";
    private static final String SCHUR_PHASE_DIFF = "schur_phase_diff";
    private static final String SPECKLE_PHASE_DIFF = "speckle_phase_diff";
    private static final DateFormat dateFormat = ProductData.UTC.createDateFormat("yyyy-MM-dd HH:mm:ss");

    /**
     * Default constructor. The graph processing framework
     * requires that an operator has a default constructor.
     */
    public DualPolPolarimetricCoherenceOp() {
    }

    /**
     * Initializes this operator and sets the one and only target product.
     * <p>The target product can be either defined by a field of type {@link org.esa.snap.core.datamodel.Product} annotated with the
     * {@link org.esa.snap.core.gpf.annotations.TargetProduct TargetProduct} annotation or
     * by calling {@link #setTargetProduct} method.</p>
     * <p>The framework calls this method after it has created this operator.
     * Any client code that must be performed before computation of tile data
     * should be placed here.</p>
     *
     * @throws org.esa.snap.core.gpf.OperatorException If an error occurs during operator initialisation.
     * @see #getTargetProduct()
     */
    @Override
    public void initialize() throws OperatorException {
        try {
            windowSize = Integer.parseInt(windowSizeStr);
            halfWindowSize = windowSize / 2;
            absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);
            mstRoot = AbstractMetadata.getOriginalProductMetadata(sourceProduct);
            slvRoot = AbstractMetadata.getSlaveMetadata(sourceProduct.getMetadataRoot()).
                    getElement("Original_Product_Metadata");
            isA1 = algorithm.equals(USING_COHERENCE_AMPLITUDE);

            checkUserInput();

            setPolynomialCoefficients();

            getSourceBands();

            getMetadata();

            getNoiseRecords();

            createTargetProduct();

        } catch (Exception e) {
            throw new OperatorException(e);
        }
    }

    private void checkUserInput() {

        try {
            final InputProductValidator validator = new InputProductValidator(sourceProduct);
            validator.checkIfSARProduct();
            validator.checkIfCoregisteredStack();

            final String mission = absRoot.getAttributeString(AbstractMetadata.MISSION);
            final String productType = absRoot.getAttributeString(AbstractMetadata.PRODUCT_TYPE);
            if (!mission.startsWith("TDM") || !productType.contains("COSSC")) {
                throw new OperatorException("TanDEM-X dual-pol COSSC product is expected.");
            }

            final String polModeStr = mstRoot.getElement("level1Product").getElement("ProductInfo").
                    getElement("acquisitionInfo").getAttributeString("polarisationMode");

            if (polModeStr == null || !polModeStr.toLowerCase().contains("dual")) {
                throw new OperatorException("TanDEM-X dual-pol COSSC product is expected.");
            }
        } catch (Exception e) {
            throw new OperatorException(e);
        }
    }

    private void setPolynomialCoefficients() {

        switch (windowSize) {
            case 5:
                polyCoeff = new double[]{-3.9275963e+02, 9.4001544e+02, -7.4410122e+02, 8.9516026e+01, 1.1394356e+02};
                break;
            case 7:
                polyCoeff = new double[]{-4.6396957e+02, 9.4645433e+02, -5.1969393e+02, -8.3560102e+01, 1.2307414e+02};
                break;
            case 9:
                polyCoeff = new double[]{-2.0985672e+01, -2.1061918e+02, 5.4947635e+02, -4.6993969e+02, 1.5429863e+02};
                break;
            case 11:
                polyCoeff = new double[]{4.8756474e+02, -1.4483814e+03, 1.5966147e+03, -8.1103702e+02, 1.7943559e+02};
                break;
            case 13:
                polyCoeff = new double[]{4.4082194e+02, -1.2941631e+03, 1.4038590e+03, -6.9812405e+02, 1.5101747e+02};
                break;
            case 15:
                polyCoeff = new double[]{4.2376472e+02, -1.2143163e+03, 1.2838429e+03, -6.2104025e+02, 1.3098414e+02};
                break;
            case 17:
                polyCoeff = new double[]{2.8132593e+02, -8.4610783e+02, 9.4019857e+02, -4.8002336e+02, 1.0703443e+02};
                break;
            case 19:
                polyCoeff = new double[]{3.1784123e+02, -9.1320036e+02, 9.6717908e+02, -4.6862351e+02, 9.9216418e+01};
                break;
            default:
                throw new OperatorException("Invalid coherence window size: " + windowSize);
        }
    }

    private void getSourceBands() {

        final Band[] srcBands = sourceProduct.getBands();
        int cnt = 0;
        for (Band band : srcBands) {
            if (band instanceof VirtualBand) {
                continue;
            }

            final String bandName = band.getName();
            if (bandName.contains("i_HH_slv")) {
                slvBands[0] = band;
                cnt++;
            } else if (bandName.contains("q_HH_slv")) {
                slvBands[1] = band;
                cnt++;
            } else if (bandName.contains("i_VV_slv")) {
                slvBands[2] = band;
                cnt++;
            } else if (bandName.contains("q_VV_slv")) {
                slvBands[3] = band;
                cnt++;
            } else if (bandName.contains("i_HH_mst")) {
                mstBands[0] = band;
                cnt++;
            } else if (bandName.contains("q_HH_mst")) {
                mstBands[1] = band;
                cnt++;
            } else if (bandName.contains("i_VV_mst")) {
                mstBands[2] = band;
                cnt++;
            } else if (bandName.contains("q_VV_mst")) {
                mstBands[3] = band;
                cnt++;
            }
        }

        if (cnt != 8) {
            throw new OperatorException("TanDEM-X dual-pol COSSC product is expected.");
        }

        srcNoDataValue = mstBands[0].getNoDataValue();
    }

    private void getMetadata() throws Exception {

        sourceImageWidth = sourceProduct.getSceneRasterWidth();
        sourceImageHeight = sourceProduct.getSceneRasterHeight();

        firstLineUTC = AbstractMetadata.parseUTC(absRoot.getAttributeString(AbstractMetadata.first_line_time)).getMJD(); // in days
        lastLineUTC = AbstractMetadata.parseUTC(absRoot.getAttributeString(AbstractMetadata.last_line_time)).getMJD(); // in days
        lineTimeInterval = (lastLineUTC - firstLineUTC) / (sourceImageHeight - 1); // in days
        if (lineTimeInterval == 0.0) {
            throw new OperatorException("Invalid input for Line Time Interval: " + lineTimeInterval);
        }

        slantRangeToFirstPixel = absRoot.getAttributeDouble(AbstractMetadata.slant_range_to_first_pixel);
        rangeSpacing = absRoot.getAttributeDouble(AbstractMetadata.range_spacing);
    }

    private void getNoiseRecords() {

        final MetadataElement mstNoiseHH = getNoiseElement(mstRoot, "HH");
        final MetadataElement mstNoiseVV = getNoiseElement(mstRoot, "VV");
        mstNoiseRecordsHH = getRecords(mstNoiseHH);
        mstNoiseRecordsVV = getRecords(mstNoiseVV);

        final MetadataElement slvNoiseHH = getNoiseElement(slvRoot, "HH");
        final MetadataElement slvNoiseVV = getNoiseElement(slvRoot, "VV");
        slvNoiseRecordsHH = getRecords(slvNoiseHH);
        slvNoiseRecordsVV = getRecords(slvNoiseVV);
    }

    private MetadataElement getNoiseElement(final MetadataElement metaRoot, final String pol) {

        final MetadataElement level1Product = metaRoot.getElement("level1Product");
        final MetadataElement[] level1ProductElem = level1Product.getElements();

        for (MetadataElement elem : level1ProductElem) {
            if (elem.getName().equals("noise") && elem.getAttributeString("polLayer").equals(pol)) {
                return elem;
            }
        }

        throw new OperatorException("Cannot find noise records for HH or VV polarization.");
    }

    private SinglePolCoherenceCompensationOp.NoiseRecord[] getRecords(MetadataElement noise) {

        final int numOfNoiseRecords = Integer.parseInt(noise.getAttributeString("numberOfNoiseRecords"));
        final MetadataElement[] imageNoiseElem = noise.getElements();
        if (numOfNoiseRecords != imageNoiseElem.length) {
            throw new OperatorException("The number of noise records does not match the record number.");
        }

        SinglePolCoherenceCompensationOp.NoiseRecord[] noiseRecords = new SinglePolCoherenceCompensationOp.NoiseRecord[numOfNoiseRecords];
        for (int i = 0; i < numOfNoiseRecords; ++i) {
            noiseRecords[i] = new SinglePolCoherenceCompensationOp.NoiseRecord();
            noiseRecords[i].timeUTC = ReaderUtils.getTime(
                    imageNoiseElem[i], "timeUTC", dateFormat).getMJD();
            noiseRecords[i].noiseEstimateConfidence = Double.parseDouble(
                    imageNoiseElem[i].getAttributeString("noiseEstimateConfidence"));

            final MetadataElement noiseEstimate = imageNoiseElem[i].getElement("noiseEstimate");
            noiseRecords[i].validityRangeMin = Double.parseDouble(noiseEstimate.getAttributeString("validityRangeMin"));
            noiseRecords[i].validityRangeMax = Double.parseDouble(noiseEstimate.getAttributeString("validityRangeMax"));
            noiseRecords[i].referencePoint = Double.parseDouble(noiseEstimate.getAttributeString("referencePoint"));
            noiseRecords[i].polynomialDegree = Integer.parseInt(noiseEstimate.getAttributeString("polynomialDegree"));

            final MetadataElement[] coefficientElem = noiseEstimate.getElements();
            if (noiseRecords[i].polynomialDegree + 1 != coefficientElem.length) {
                throw new OperatorException(
                        "The number of coefficients does not match the polynomial degree.");
            }

            noiseRecords[i].coefficient = new double[noiseRecords[i].polynomialDegree + 1];
            for (int j = 0; j < coefficientElem.length; ++j) {
                noiseRecords[i].coefficient[j] = Double.parseDouble(coefficientElem[j].getAttributeString("coefficient"));
            }

            noiseRecords[i].azimuthIndex = (int) ((noiseRecords[i].timeUTC - firstLineUTC) / lineTimeInterval + 0.5);
            noiseRecords[i].rangeLineNoise = new double[sourceImageWidth];
            for (int x = 0; x < sourceImageWidth; ++x) {
                final double slantRgTime = 2.0 * (slantRangeToFirstPixel + x * rangeSpacing) / Constants.lightSpeed;
                if (slantRgTime >= noiseRecords[i].validityRangeMin && slantRgTime <= noiseRecords[i].validityRangeMax) {
                    noiseRecords[i].rangeLineNoise[x] = Maths.computePolynomialValue(
                            slantRgTime - noiseRecords[i].referencePoint, noiseRecords[i].coefficient);
                } else {
                    noiseRecords[i].rangeLineNoise[x] = 0.0;
                }
            }
        }

        return noiseRecords;
    }

    /**
     * Create target product
     */
    private void createTargetProduct() {

        sourceImageWidth = sourceProduct.getSceneRasterWidth();
        sourceImageHeight = sourceProduct.getSceneRasterHeight();

        targetProduct = new Product(sourceProduct.getName() + PRODUCT_SUFFIX,
                sourceProduct.getProductType(),
                sourceImageWidth,
                sourceImageHeight);

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        cohAmpBand = targetProduct.addBand(COHERENCE_AMPLITUDE, ProductData.TYPE_FLOAT32);
        cohAmpBand.setUnit("coherence");

        if (!isA1) {
            cohPhaseBand = targetProduct.addBand(COHERENCE_PHASE, ProductData.TYPE_FLOAT32);
            cohPhaseBand.setUnit(Unit.PHASE);

            groundPhaseBand = targetProduct.addBand(GROUND_PHASE, ProductData.TYPE_FLOAT32);
            groundPhaseBand.setUnit(Unit.PHASE);

            schurPhaseDiffBand = targetProduct.addBand(SCHUR_PHASE_DIFF, ProductData.TYPE_FLOAT32);
            schurPhaseDiffBand.setUnit(Unit.DEGREES);

            specklePhaseDiffBand = targetProduct.addBand(SPECKLE_PHASE_DIFF, ProductData.TYPE_FLOAT32);
            specklePhaseDiffBand.setUnit(Unit.DEGREES);
        }
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

        if (isA1) { // A1: estimate tree height using coherence amplitude
            computeTileStackA1(targetTiles, targetRectangle, pm);
        } else { // A2: estimate tree height using polarimetric coherence
            computeTileStackA2(targetTiles, targetRectangle, pm);
        }
    }

    private void computeTileStackA1(Map<Band, Tile> targetTiles, Rectangle targetRectangle, ProgressMonitor pm)
            throws OperatorException {

        try {
            final int x0 = targetRectangle.x;
            final int y0 = targetRectangle.y;
            final int w = targetRectangle.width;
            final int h = targetRectangle.height;
            final int maxY = y0 + h;
            final int maxX = x0 + w;
            //System.out.println("x0 = " + x0 + ", y0 = " + y0 + ", w = " + w + ", h = " + h);

            final Tile cohAmpTile = targetTiles.get(cohAmpBand);
            final ProductData cohAmpData = cohAmpTile.getDataBuffer();
            final TileIndex tgtIndex = new TileIndex(cohAmpTile);
            final Rectangle sourceRectangle = getSourceRectangle(x0, y0, w, h);

            final Tile[] mstTiles = new Tile[mstBands.length];
            final ProductData[] mstDataBuffers = new ProductData[mstBands.length];
            for (int i = 0; i < mstBands.length; ++i) {
                mstTiles[i] = getSourceTile(mstBands[i], sourceRectangle);
                mstDataBuffers[i] = mstTiles[i].getDataBuffer();
            }

            final Tile[] slvTiles = new Tile[slvBands.length];
            final ProductData[] slvDataBuffers = new ProductData[slvBands.length];
            for (int i = 0; i < slvBands.length; ++i) {
                slvTiles[i] = getSourceTile(slvBands[i], sourceRectangle);
                slvDataBuffers[i] = slvTiles[i].getDataBuffer();
            }

            float[][] mstTileNoiseHH = new float[h][w];
            float[][] mstTileNoiseVV = new float[h][w];
            float[][] slvTileNoiseHH = new float[h][w];
            float[][] slvTileNoiseVV = new float[h][w];
            computeTileNoise(x0, y0, w, h, mstNoiseRecordsHH, mstTileNoiseHH);
            computeTileNoise(x0, y0, w, h, mstNoiseRecordsVV, mstTileNoiseVV);
            computeTileNoise(x0, y0, w, h, slvNoiseRecordsHH, slvTileNoiseHH);
            computeTileNoise(x0, y0, w, h, slvNoiseRecordsVV, slvTileNoiseVV);

            final double[][] T11Re = new double[2][2];
            final double[][] T11Im = new double[2][2];
            final double[][] T22Re = new double[2][2];
            final double[][] T22Im = new double[2][2];
            final double[][] T12Re = new double[2][2];
            final double[][] T12Im = new double[2][2];
            final double[][] TmRe = new double[2][2];
            final double[][] TmIm = new double[2][2];

            for (int y = y0; y < maxY; ++y) {
                tgtIndex.calculateStride(y);
                final int yy = y - y0;
                for (int x = x0; x < maxX; ++x) {
                    final int idx = tgtIndex.getIndex(x);
                    final int xx = x - x0;

                    getMeanCovarianceMatrixC2(x, y, halfWindowSize, halfWindowSize, sourceImageWidth,
                            sourceImageHeight, PolBandUtils.MATRIX.DUAL_HH_VV, mstTiles, mstDataBuffers, T11Re, T11Im);

                    getMeanCovarianceMatrixC2(x, y, halfWindowSize, halfWindowSize, sourceImageWidth,
                            sourceImageHeight, PolBandUtils.MATRIX.DUAL_HH_VV, slvTiles, slvDataBuffers, T22Re, T22Im);

                    getMeanCorrelationMatrixC2(x, y, halfWindowSize, halfWindowSize, sourceImageWidth,
                            sourceImageHeight, PolBandUtils.MATRIX.DUAL_HH_VV, mstTiles, mstDataBuffers, slvDataBuffers,
                            T12Re, T12Im);

                    computeTm(T11Re, T11Im, T22Re, T22Im, TmRe, TmIm);

                    if (!checkMatrixCondition(TmRe, TmIm)) {
                        continue;
                    }

                    final double[][] EigVecRe = new double[2][2];
                    final double[][] EigVecIm = new double[2][2];
                    final double[] EigVal = new double[2];
                    EigenDecomposition.eigenDecomposition(2, TmRe, TmIm, EigVecRe, EigVecIm, EigVal);

                    final double[] wRe = {EigVecRe[0][0], EigVecRe[1][0]};
                    final double[] wIm = {EigVecIm[0][0], EigVecIm[1][0]};

                    final double[][] N1 = new double[][]{{mstTileNoiseHH[yy][xx], 0.0}, {0.0, mstTileNoiseVV[yy][xx]}};
                    final double[][] N2 = new double[][]{{slvTileNoiseHH[yy][xx], 0.0}, {0.0, slvTileNoiseVV[yy][xx]}};

                    final double gammaSNR = computeSNRCompensationFactor(T11Re, T11Im, T22Re, T22Im, N1, N2, wRe, wIm);

                    final ComplexDouble coh = computeCoherence(T11Re, T11Im, T12Re, T12Im, T22Re, T22Im, wRe, wIm);

                    final ComplexDouble cohComp = compensateCoherence(coh, gammaSNR);

                    cohAmpData.setElemFloatAt(idx, (float) cohComp.abs());
                }
            }

        } catch (Exception e) {
            throw new OperatorException(e);
        }
    }

    private void computeTileStackA2(Map<Band, Tile> targetTiles, Rectangle targetRectangle, ProgressMonitor pm)
            throws OperatorException {

        try {
            final int x0 = targetRectangle.x;
            final int y0 = targetRectangle.y;
            final int w = targetRectangle.width;
            final int h = targetRectangle.height;
            final int maxY = y0 + h;
            final int maxX = x0 + w;
            //System.out.println("x0 = " + x0 + ", y0 = " + y0 + ", w = " + w + ", h = " + h);

            final Tile cohAmpTile = targetTiles.get(cohAmpBand);
            final Tile cohPhaseTile = targetTiles.get(cohPhaseBand);
            final Tile groundPhaseTile = targetTiles.get(groundPhaseBand);
            final Tile schurPhaseDiffTile = targetTiles.get(schurPhaseDiffBand);
            final Tile specklePhaseDiffTile = targetTiles.get(specklePhaseDiffBand);
            final ProductData cohAmpData = cohAmpTile.getDataBuffer();
            final ProductData cohPhaseData = cohPhaseTile.getDataBuffer();
            final ProductData groundPhaseData = groundPhaseTile.getDataBuffer();
            final ProductData schurPhaseDiffData = schurPhaseDiffTile.getDataBuffer();
            final ProductData specklePhaseDiffData = specklePhaseDiffTile.getDataBuffer();
            final TileIndex tgtIndex = new TileIndex(cohAmpTile);

            final Rectangle sourceRectangle = getSourceRectangle(x0, y0, w, h);

            final Tile[] mstTiles = new Tile[mstBands.length];
            final ProductData[] mstDataBuffers = new ProductData[mstBands.length];
            for (int i = 0; i < mstBands.length; ++i) {
                mstTiles[i] = getSourceTile(mstBands[i], sourceRectangle);
                mstDataBuffers[i] = mstTiles[i].getDataBuffer();
            }

            final Tile[] slvTiles = new Tile[slvBands.length];
            final ProductData[] slvDataBuffers = new ProductData[slvBands.length];
            for (int i = 0; i < slvBands.length; ++i) {
                slvTiles[i] = getSourceTile(slvBands[i], sourceRectangle);
                slvDataBuffers[i] = slvTiles[i].getDataBuffer();
            }

            final double[][] T11Re = new double[2][2];
            final double[][] T11Im = new double[2][2];
            final double[][] T22Re = new double[2][2];
            final double[][] T22Im = new double[2][2];
            final double[][] T12Re = new double[2][2];
            final double[][] T12Im = new double[2][2];
            final double[][] TmRe = new double[2][2];
            final double[][] TmIm = new double[2][2];

            for (int y = y0; y < maxY; ++y) {
                tgtIndex.calculateStride(y);
                for (int x = x0; x < maxX; ++x) {
                    final int idx = tgtIndex.getIndex(x);

                    getMeanCovarianceMatrixC2(x, y, halfWindowSize, halfWindowSize, sourceImageWidth,
                            sourceImageHeight, PolBandUtils.MATRIX.DUAL_HH_VV, mstTiles, mstDataBuffers, T11Re, T11Im);

                    getMeanCovarianceMatrixC2(x, y, halfWindowSize, halfWindowSize, sourceImageWidth,
                            sourceImageHeight, PolBandUtils.MATRIX.DUAL_HH_VV, slvTiles, slvDataBuffers, T22Re, T22Im);

                    getMeanCorrelationMatrixC2(x, y, halfWindowSize, halfWindowSize, sourceImageWidth,
                            sourceImageHeight, PolBandUtils.MATRIX.DUAL_HH_VV, mstTiles, mstDataBuffers, slvDataBuffers,
                            T12Re, T12Im);

                    computeTm(T11Re, T11Im, T22Re, T22Im, TmRe, TmIm);

                    if (!checkMatrixCondition(TmRe, TmIm)) {
                        continue;
                    }

                    final double[][] EigVecRe = new double[2][2];
                    final double[][] EigVecIm = new double[2][2];
                    final double[] EigVal = new double[2];
                    EigenDecomposition.eigenDecomposition(2, TmRe, TmIm, EigVecRe, EigVecIm, EigVal);

                    final ComplexDoubleMatrix A = computeMatrixA(T12Re, T12Im, EigVecRe, EigVecIm, EigVal);

                    final ComplexDouble lambda1 = computeLambda1(A);
                    final ComplexDouble lambda2 = computeLambda2(A);

                    final ComplexDouble gamma = new ComplexDouble(0.0, 0.0);
                    final double phi0 = estimateGroundPhase(lambda1, lambda2, gamma);
                    groundPhaseData.setElemFloatAt(idx, (float) phi0);
                    //cohAmpData.setElemFloatAt(idx, (float)gamma.abs());
                    //cohPhaseData.setElemFloatAt(idx, (float)gamma.arg());

                    final ComplexDouble cohComp = compensateCoherence(gamma, 1.0);
                    cohAmpData.setElemFloatAt(idx, (float) cohComp.abs());
                    cohPhaseData.setElemFloatAt(idx, (float) cohComp.arg());

                    final double gm = lambda1.add(lambda2).div(2.0).abs();

                    final double averageSpecklePhaseSpread = polyVal(gm, polyCoeff);
                    specklePhaseDiffData.setElemFloatAt(idx, (float) averageSpecklePhaseSpread);

                    final double schurPhaseDiff = Math.abs(lambda1.mul(lambda2.conj()).arg()) * 180.0 / Math.PI;
                    schurPhaseDiffData.setElemFloatAt(idx, (float) schurPhaseDiff);
                }
            }

        } catch (Exception e) {
            throw new OperatorException(e);
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
        final int xMax = Math.min(tx0 + tw - 1 + halfWindowSize, sourceImageWidth - 1);
        final int yMax = Math.min(ty0 + th - 1 + halfWindowSize, sourceImageHeight - 1);
        final int w = xMax - x0 + 1;
        final int h = yMax - y0 + 1;
        return new Rectangle(x0, y0, w, h);
    }

    private static void computeTileNoise(final int x0, final int y0, final int w, final int h,
                                         final SinglePolCoherenceCompensationOp.NoiseRecord[] noiseRecords,
                                         float[][] tileNoise) {

        final int xMax = x0 + w;
        final int yMax = y0 + h;

        int i1 = 0, i2 = 0;
        int y1 = 0, y2 = 0;

        for (int y = y0; y < yMax; ++y) {

            for (int i = 0; i < noiseRecords.length; ++i) {
                if (noiseRecords[i].azimuthIndex <= y) {
                    i1 = i;
                    y1 = noiseRecords[i].azimuthIndex;
                } else {
                    i2 = i;
                    y2 = noiseRecords[i].azimuthIndex;
                    break;
                }
            }

            if (y1 == noiseRecords[noiseRecords.length - 1].azimuthIndex) {
                y2 = y1;
                i2 = i1;
            } else if (y1 > y2) {
                throw new OperatorException("No noise is defined for pixel with y = " + y);
            }

            for (int x = x0; x < xMax; ++x) {
                final double n1 = noiseRecords[i1].rangeLineNoise[x];
                final double n2 = noiseRecords[i2].rangeLineNoise[x];
                double mu = 0.0;
                if (y1 != y2) {
                    mu = (double) (y - y1) / (double) (y2 - y1);
                }
                tileNoise[y - y0][x - x0] = (float) Maths.interpolationLinear(n1, n2, mu);
            }
        }
    }

    private static void computeTm(final double[][] T11Re, final double[][] T11Im, final double[][] T22Re,
                                  final double[][] T22Im, final double[][] TmRe, final double[][] TmIm) {

        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                TmRe[i][j] = 0.5 * (T11Re[i][j] + T22Re[i][j]);
                TmIm[i][j] = 0.5 * (T11Im[i][j] + T22Im[i][j]);
            }
        }
    }

    private static boolean checkMatrixCondition(final double[][] TRe, final double[][] TIm) {

        final double[][] M = new double[4][4];
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                M[i][j] = TRe[i][j];
                M[i + 2][j + 2] = TRe[i][j];
                M[i][j + 2] = -TIm[i][j];
                M[i + 2][j] = TIm[i][j];
            }
        }
        Matrix Mat = new Matrix(M);
        return (Mat.det() > 0.001);
    }

    private double computeSNRCompensationFactor(final double[][] T11Re, final double[][] T11Im,
                                                final double[][] T22Re, final double[][] T22Im,
                                                final double[][] N1, final double[][] N2,
                                                final double[] wRe, final double[] wIm) {

        final ComplexDoubleMatrix w = new ComplexDoubleMatrix(new DoubleMatrix(wRe), new DoubleMatrix(wIm));
        final ComplexDoubleMatrix T11 = new ComplexDoubleMatrix(new DoubleMatrix(T11Re), new DoubleMatrix(T11Im));
        final ComplexDoubleMatrix T22 = new ComplexDoubleMatrix(new DoubleMatrix(T22Re), new DoubleMatrix(T22Im));
        final ComplexDoubleMatrix CN1 = new ComplexDoubleMatrix(new DoubleMatrix(N1));
        final ComplexDoubleMatrix CN2 = new ComplexDoubleMatrix(new DoubleMatrix(N2));

        final double wT11w = w.transpose().conj().mmul(T11).mmul(w).get(0, 0).real();
        final double wT22w = w.transpose().conj().mmul(T22).mmul(w).get(0, 0).real();
        final double wN1w = w.transpose().conj().mmul(CN1).mmul(w).get(0, 0).real();
        final double wN2w = w.transpose().conj().mmul(CN2).mmul(w).get(0, 0).real();
        final double snr1 = Math.abs(wT11w - wN1w) / wN1w;
        final double snr2 = Math.abs(wT22w - wN2w) / wN2w;

        return Math.sqrt((snr1 / (1.0 + snr1)) * (snr2 / (1.0 + snr2)));
    }

    /**
     * Compute polarimetric coherence and perform SNR and system compensations.
     *
     * @param T11Re Real part of T11 matrix
     * @param T11Im Imaginary part of T11 matrix
     * @param T12Re Real part of T12 matrix
     * @param T12Im Imaginary part of T12 matrix
     * @param T22Re Real part of T22 matrix
     * @param T22Im Imaginary part of T22 matrix
     * @param wRe   Real part of polarization vector w
     * @param wIm   Imaginary part of polarization vector w
     * @return The polarimetric coherence values
     */
    private ComplexDouble computeCoherence(
            final double[][] T11Re, final double[][] T11Im, final double[][] T12Re,
            final double[][] T12Im, final double[][] T22Re, final double[][] T22Im,
            final double[] wRe, final double[] wIm) {

        final ComplexDoubleMatrix T11 = new ComplexDoubleMatrix(new DoubleMatrix(T11Re), new DoubleMatrix(T11Im));
        final ComplexDoubleMatrix T12 = new ComplexDoubleMatrix(new DoubleMatrix(T12Re), new DoubleMatrix(T12Im));
        final ComplexDoubleMatrix T22 = new ComplexDoubleMatrix(new DoubleMatrix(T22Re), new DoubleMatrix(T22Im));
        final ComplexDoubleMatrix w = new ComplexDoubleMatrix(new DoubleMatrix(wRe), new DoubleMatrix(wIm));

        final double wT11w = w.transpose().conj().mmul(T11).mmul(w).get(0, 0).real();
        final double wT22w = w.transpose().conj().mmul(T22).mmul(w).get(0, 0).real();
        final ComplexDouble wT12w = w.transpose().conj().mmul(T12).mmul(w).get(0, 0);
        final double tmp = Math.sqrt(wT11w * wT22w);

        return new ComplexDouble(wT12w.real() / tmp, wT12w.imag() / tmp);
    }

    private ComplexDouble compensateCoherence(final ComplexDouble coh, final double gammaSNR) {

        final double cohAmplitude = coh.abs();
        if (cohAmplitude == 0.0)
            return new ComplexDouble(0.0, 0.0);

        double factor;
        if (maskOutPoorCoherenceArea && gammaSNR < minGammaSNR) {
            factor = 1.0 / cohAmplitude;
        } else {
            factor = Math.min(1.0 / (gammaSNR * sysCorrFactor), 1.0 / cohAmplitude);
        }

        return coh.mul(factor);
    }

    private ComplexDoubleMatrix computeMatrixA(
            final double[][] T12Re, final double[][] T12Im, final double[][] EigVecRe, final double[][] EigVecIm,
            final double[] EigVal) {

        final double[] d = {1.0 / Math.sqrt(EigVal[0]), 1.0 / Math.sqrt(EigVal[1])};
        final ComplexDoubleMatrix D = new ComplexDoubleMatrix(DoubleMatrix.diag(new DoubleMatrix(d), 2, 2));
        final ComplexDoubleMatrix T12 = new ComplexDoubleMatrix(new DoubleMatrix(T12Re), new DoubleMatrix(T12Im));
        final ComplexDoubleMatrix U = new ComplexDoubleMatrix(new DoubleMatrix(EigVecRe), new DoubleMatrix(EigVecIm));
        return D.mmul(U).mmul(T12).mmul(U).mmul(D);
    }

    private double polyVal(final double x, final double[] coeff) {
        double v = 0.0;
        for (int i = 0; i < coeff.length - 1; ++i) {
            v = (v + coeff[i]) * x;
        }
        return v + coeff[coeff.length - 1];
    }

    private ComplexDouble computeLambda1(final ComplexDoubleMatrix A) {

        final ComplexDouble a = A.get(0, 0);
        final ComplexDouble b = A.get(0, 1);
        final ComplexDouble c = A.get(1, 0);
        final ComplexDouble d = A.get(1, 1);
        final ComplexDouble trace = a.add(d);
        final ComplexDouble det = a.mul(d).sub(b.mul(c));
        return trace.mul(0.5).add(trace.mul(trace).div(4.0).sub(det).sqrt());
    }

    private ComplexDouble computeLambda2(final ComplexDoubleMatrix A) {

        final ComplexDouble a = A.get(0, 0);
        final ComplexDouble b = A.get(0, 1);
        final ComplexDouble c = A.get(1, 0);
        final ComplexDouble d = A.get(1, 1);
        final ComplexDouble trace = a.add(d);
        final ComplexDouble det = a.mul(d).sub(b.mul(c));
        return trace.mul(0.5).sub(trace.mul(trace).div(4.0).sub(det).sqrt());
    }

    private double estimateGroundPhase(final ComplexDouble lambda1, final ComplexDouble lambda2,
                                       final ComplexDouble gamma) {

        ComplexDouble gammaLow = lambda2;
        ComplexDouble gammaHigh = lambda1;
        final double phi1 = computeGroundPhase(gammaLow, gammaHigh);
        final double height1 = computeHeight(phi1, gammaHigh);

        gammaLow = lambda1;
        gammaHigh = lambda2;
        final double phi2 = computeGroundPhase(gammaLow, gammaHigh);
        final double height2 = computeHeight(phi2, gammaHigh);

        gamma.set(gammaHigh.real(), gammaHigh.imag());
        if (height1 < height2) {
            return phi1;
        } else {
            return phi2;
        }
    }

    private double computeGroundPhase(final ComplexDouble gammaLow, final ComplexDouble gammaHigh) {

        final double A = Math.pow(gammaLow.abs(), 2.0) - 1.0;
        final double B = 2.0 * gammaHigh.sub(gammaLow).mul(gammaLow.conj()).real();
        final double C = Math.pow(gammaHigh.sub(gammaLow).abs(), 2.0);
        final double F = (-B - Math.sqrt(B * B - 4.0 * A * C)) / (2.0 * A);

        return gammaHigh.sub(gammaLow.mul(1.0 - F)).arg();
    }

    private double computeHeight(final double groundPhase, final ComplexDouble gammaHigh) {

        double diffPhase = gammaHigh.arg() - groundPhase;
        if (diffPhase < 0.0) {
            diffPhase += Constants._TWO_PI;
        }
        return diffPhase + 0.8 * (Constants._PI - 2.0 * Math.asin(Math.pow(gammaHigh.abs(), 0.8)));

    }


    /**
     * The SPI is used to register this operator in the graph processing framework
     * via the SPI configuration file
     * {@code META-INF/services/org.esa.snap.core.gpf.OperatorSpi}.
     * This class may also serve as a factory for new operator instances.
     *
     * @see org.esa.snap.core.gpf.OperatorSpi#createOperator()
     * @see org.esa.snap.core.gpf.OperatorSpi#createOperator(java.util.Map, java.util.Map)
     */
    public static class Spi extends OperatorSpi {
        public Spi() {
            super(DualPolPolarimetricCoherenceOp.class);
        }
    }
}
