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

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.SARUtils;
import eu.esa.sar.commons.Sentinel1Utils;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProducts;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;
import org.esa.snap.engine_utilities.util.Maths;

import java.awt.*;
import java.text.DateFormat;
import java.util.Arrays;
import java.util.Map;

/**
 * This operator performs system and SNR compensation to source coherence.
 * <p>
 * [1] H. Chen, D.G. Goodenough, S.R. Cloude and P. Padda, "Wide area forest height mapping using TanDEM-X
 * standard mode data", IGARSS 2015, Milan, Italy.
 */

@OperatorMetadata(alias = "SinglePolCoherenceCompensation",
        category = "Radar/Biomass",
        authors = "Jun Lu, Luis Veci",
        copyright = "Copyright (C) 2017 by Array Systems Computing Inc.",
        description = "Perform system and SNR coherence compensation")
public class SinglePolCoherenceCompensationOp extends Operator {

    @SourceProducts
    private Product[] sourceProduct;

    @TargetProduct
    Product targetProduct;

    @Parameter(description = "System correction factor", interval = "(0, 1]", defaultValue = "0.97",
            label = "System Correction Factor")
    private float sysCorrFactor = 0.97f;

    @Parameter(defaultValue = "true", label = "Mask Out Poor Coherence Area")
    private boolean maskOutPoorCoherenceArea = true;

    @Parameter(description = "Minimum Gamma SNR", interval = "(0, 1]", defaultValue = "0.8",
            label = "Minimum Gamma SNR")
    private float minGammaSNR = 0.8f;

    private boolean outputSNR = false;

    private Product cohProduct = null;
    private Product srcProduct = null;
    private Band mstBandI = null;
    private Band mstBandQ = null;
    private Band slvBandI = null;
    private Band slvBandQ = null;
    private Band coherenceBand = null;
    private Band targetBand = null;
    private Band snrBand = null;
    private int sourceImageWidth = 0;
    private int sourceImageHeight = 0;
    private double wavelength = 0.0; // in m
    private double firstLineUTC = 0.0; // in days
    private double lastLineUTC = 0.0; // in days
    private double lineTimeInterval = 0.0; // in days
    private double slantRangeToFirstPixel = 0.0; // in m
    private double rangeSpacing = 0.0; // in m
    private double cohNoDataValue = 0.0;
    private double srcNoDataValue = 0.0;
    private MetadataElement absRoot = null;
    private MetadataElement origMetadataRoot = null;
    private boolean isTanDEMX = false;
    private boolean isS1 = false;
    private int numOfSubSwath = 1;
    private int halfFilterSize = 0;

    private NoiseRecord[] noiseRecords = null;
    private ThermalNoiseInfo[] noise = null;

    private static final int filterSize = 9;
    public static final DateFormat dateFormat = ProductData.UTC.createDateFormat("yyyy-MM-dd HH:mm:ss");

    /**
     * Default constructor. The graph processing framework
     * requires that an operator has a default constructor.
     */
    public SinglePolCoherenceCompensationOp() {
    }

    /**
     * Initializes this operator and sets the one and only target product.
     * <p>The target product can be either defined by a field of type {@link org.esa.snap.core.datamodel.Product}
     * annotated with the {@link org.esa.snap.core.gpf.annotations.TargetProduct TargetProduct} annotation or
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
            if (sourceProduct == null) {
                return;
            }

            if (sourceProduct.length != 2) {
                throw new OperatorException("A coregistered stack product and a coherence product are expected.");
            }

            if (sourceProduct[0].getName().toLowerCase().contains("_coh")) {
                cohProduct = sourceProduct[0];
                srcProduct = sourceProduct[1];
            } else {
                cohProduct = sourceProduct[1];
                srcProduct = sourceProduct[0];
            }

            final InputProductValidator cohValidator = new InputProductValidator(cohProduct);
            cohValidator.checkIfSARProduct();

            final InputProductValidator srcValidator = new InputProductValidator(srcProduct);
            srcValidator.checkIfSARProduct();
            srcValidator.checkIfCoregisteredStack();

            halfFilterSize = filterSize / 2;
            absRoot = AbstractMetadata.getAbstractedMetadata(cohProduct);
            origMetadataRoot = AbstractMetadata.getOriginalProductMetadata(cohProduct);

            checkMission();

            getMetadata();

            getNoiseRecords();

            getSourceBands();

            createTargetProduct();

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private void checkMission() {

        final String mission = absRoot.getAttributeString(AbstractMetadata.MISSION);
        final String productType = absRoot.getAttributeString(AbstractMetadata.PRODUCT_TYPE);
        isTanDEMX = (mission.startsWith("TDM") && productType.contains("COSSC"));

        final String acquisitionMode = absRoot.getAttributeString(AbstractMetadata.ACQUISITION_MODE);
        isS1 = mission.startsWith("SENTINEL-1") && productType.equals("SLC") &&
                (acquisitionMode.equals("IW") || acquisitionMode.equals("EW") || acquisitionMode.equals("SM"));

        if (isS1) {
            if (acquisitionMode.equals("IW")) {
                numOfSubSwath = 3;
            } else if (acquisitionMode.equals("EW")) {
                numOfSubSwath = 5;
            }
        }

        if (!isTanDEMX && !isS1) {
            throw new OperatorException("Only TanDEM-X single-pol COSSC product and Sentinel-1 SLC products are supported.");
        }
    }

    private void getMetadata() throws Exception {

        sourceImageWidth = cohProduct.getSceneRasterWidth();
        sourceImageHeight = cohProduct.getSceneRasterHeight();

        wavelength = SARUtils.getRadarWavelength(absRoot);

        firstLineUTC = AbstractMetadata.parseUTC(absRoot.getAttributeString(AbstractMetadata.first_line_time)).getMJD(); // in days
        lastLineUTC = AbstractMetadata.parseUTC(absRoot.getAttributeString(AbstractMetadata.last_line_time)).getMJD(); // in days
        lineTimeInterval = (lastLineUTC - firstLineUTC) / (sourceImageHeight - 1); // in days
        if (lineTimeInterval == 0.0) {
            throw new OperatorException("Invalid input for Line Time Interval: " + lineTimeInterval);
        }

        slantRangeToFirstPixel = absRoot.getAttributeDouble(AbstractMetadata.slant_range_to_first_pixel);
        rangeSpacing = absRoot.getAttributeDouble(AbstractMetadata.range_spacing);
    }

    /**
     * Get image noise records.
     */
    private void getNoiseRecords() {

        if (isTanDEMX) {
            getTanDEMXNoiseRecord();
        }

        if (isS1) {
            getS1NoiseVectors();
        }
    }

    private void getTanDEMXNoiseRecord() {

        final MetadataElement level1Product = origMetadataRoot.getElement("level1Product");
        final MetadataElement noise = level1Product.getElement("noise");
        final int numOfNoiseRecords = Integer.parseInt(noise.getAttributeString("numberOfNoiseRecords"));
        final MetadataElement[] imageNoiseElem = noise.getElements();
        if (numOfNoiseRecords != imageNoiseElem.length) {
            throw new OperatorException("The number of noise records does not match the record number.");
        }

        noiseRecords = new NoiseRecord[numOfNoiseRecords];
        for (int i = 0; i < numOfNoiseRecords; ++i) {
            noiseRecords[i] = new NoiseRecord();
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
    }

    private void getS1NoiseVectors() {

        String[] selectedPols = Sentinel1Utils.getProductPolarizations(absRoot);
        java.util.List<String> selectedPolList = Arrays.asList(selectedPols);

        noise = new ThermalNoiseInfo[numOfSubSwath * selectedPolList.size()];
        final MetadataElement noiseElem = origMetadataRoot.getElement("noise");
        final MetadataElement[] noiseDataSetListElem = noiseElem.getElements();

        int dataSetIndex = 0;
        for (MetadataElement dataSetListElem : noiseDataSetListElem) {

            final MetadataElement noiElem = dataSetListElem.getElement("noise");
            final MetadataElement adsHeaderElem = noiElem.getElement("adsHeader");
            final String pol = adsHeaderElem.getAttributeString("polarisation");
            if (!selectedPolList.contains(pol)) {
                continue;
            }

            final MetadataElement noiseVectorListElem = noiElem.getElement("noiseVectorList");
            final String subSwath = adsHeaderElem.getAttributeString("swath");

            noise[dataSetIndex] = new ThermalNoiseInfo(pol, subSwath,
                    Sentinel1Utils.getTime(adsHeaderElem, "startTime").getMJD(),
                    Sentinel1Utils.getTime(adsHeaderElem, "stopTime").getMJD(),
                    getNumOfLines(origMetadataRoot, pol, subSwath),
                    Integer.parseInt(noiseVectorListElem.getAttributeString("count")),
                    Sentinel1Utils.getNoiseVector(noiseVectorListElem));

            dataSetIndex++;
        }
    }

    private static int getNumOfLines(final MetadataElement origProdRoot, final String polarization, final String swath) {

        final MetadataElement annotationElem = origProdRoot.getElement("annotation");
        final MetadataElement[] annotationDataSetListElem = annotationElem.getElements();

        for (MetadataElement dataSetListElem : annotationDataSetListElem) {
            final String elemName = dataSetListElem.getName();
            if (elemName.contains(swath.toLowerCase()) && elemName.contains(polarization.toLowerCase())) {
                final MetadataElement productElem = dataSetListElem.getElement("product");
                final MetadataElement imageAnnotationElem = productElem.getElement("imageAnnotation");
                final MetadataElement imageInformationElem = imageAnnotationElem.getElement("imageInformation");
                return imageInformationElem.getAttributeInt("numberOfLines");
            }
        }

        return -1;
    }

    private void getSourceBands() {

        getCoherenceBand();

        getMstSlvBands();
    }

    private void getCoherenceBand() {

        final Band[] srcBands = cohProduct.getBands();

        for (Band band : srcBands) {
            if (band instanceof VirtualBand) {
                continue;
            }

            final String unit = band.getUnit();
            if (unit == null) {
                throw new OperatorException("band " + band.getName() + " requires a unit");
            }

            if (unit.equals(Unit.COHERENCE)) {
                coherenceBand = band;
            }
        }

        if (coherenceBand == null) {
            throw new OperatorException("Coherence band is expected.");
        }

        cohNoDataValue = coherenceBand.getNoDataValue();
    }

    private void getMstSlvBands() {

        final String masterTag = "mst";
        final String slaveTag = "slv";
        final Band[] srcBands = srcProduct.getBands();

        for (Band band : srcBands) {
            if (band instanceof VirtualBand) {
                continue;
            }

            final String bandName = band.getName();
            final String unit = band.getUnit();
            if (bandName.contains(masterTag)) {
                if (unit.equals(Unit.REAL)) {
                    mstBandI = band;
                } else if (unit.equals(Unit.IMAGINARY)) {
                    mstBandQ = band;
                }
            } else if (bandName.contains(slaveTag)) {
                if (unit.equals(Unit.REAL)) {
                    slvBandI = band;
                } else if (unit.equals(Unit.IMAGINARY)) {
                    slvBandQ = band;
                }
            }
        }

        if (mstBandI == null || mstBandQ == null || slvBandI == null || slvBandQ == null) {
            throw new OperatorException("Single polarization coregistered stack is expected.");
        }

        srcNoDataValue = mstBandI.getNoDataValue();
    }

    /**
     * Create target product.
     */
    private void createTargetProduct() {

        targetProduct = new Product(
                cohProduct.getName(),
                cohProduct.getProductType(),
                sourceImageWidth,
                sourceImageHeight);

        ProductUtils.copyProductNodes(cohProduct, targetProduct);
        ProductUtils.copyBand(coherenceBand.getName(), cohProduct, coherenceBand.getName(), targetProduct, true);

        final String targetBandName = "comp_" + coherenceBand.getName();
        targetBand = new Band(targetBandName, ProductData.TYPE_FLOAT32, sourceImageWidth, sourceImageHeight);
        targetBand.setUnit(Unit.COHERENCE);
        targetProduct.addBand(targetBand);

        if (outputSNR) {
            snrBand = new Band("SNR", ProductData.TYPE_FLOAT32, sourceImageWidth, sourceImageHeight);
            targetProduct.addBand(snrBand);
        }
    }

    /**
     * Called by the framework in order to compute a tile for the given target band.
     * <p>The default implementation throws a runtime exception with the message "not implemented".</p>
     *
     * @param targetTileMap   The target tiles associated with all target bands to be computed.
     * @param targetRectangle The rectangle of target tile.
     * @param pm              A progress monitor which should be used to determine computation cancellation requests.
     * @throws org.esa.snap.core.gpf.OperatorException If an error occurs during computation of the target raster.
     */
    @Override
    public void computeTileStack(Map<Band, Tile> targetTileMap, Rectangle targetRectangle, ProgressMonitor pm)
            throws OperatorException {

        final int x0 = targetRectangle.x;
        final int y0 = targetRectangle.y;
        final int w = targetRectangle.width;
        final int h = targetRectangle.height;
        final int yMax = y0 + h;
        final int xMax = x0 + w;
        //System.out.println("Do: tx0 = " + tx0 + " ty0 = " + ty0 + " tw = " + tw + " th = " + th);

        try {
            final Tile coherenceTile = getSourceTile(coherenceBand, targetRectangle);
            final ProductData coherenceBuffer = coherenceTile.getDataBuffer();
            final TileIndex srcIndex = new TileIndex(coherenceTile);

            ProductData snrData = null;
            if (outputSNR) {
                final Tile srnTile = targetTileMap.get(snrBand);
                snrData = srnTile.getDataBuffer();
            }

            final Tile tgtTile = targetTileMap.get(targetBand);
            final ProductData tgtDataBuffer = tgtTile.getDataBuffer();
            final TileIndex tgtIndex = new TileIndex(tgtTile);

            float[][] tileNoise = new float[h][w];
            computeTileNoise(x0, xMax, y0, yMax, tileNoise);

            float[][] tileImagePower = new float[h][w];
            computeTileImagePower(x0, y0, w, h, tileImagePower);

            float coherence = 0.0f;
            for (int y = y0; y < yMax; ++y) {
                tgtIndex.calculateStride(y);
                srcIndex.calculateStride(y);
                final int yy = y - y0;
                for (int x = x0; x < xMax; ++x) {
                    final int tgtIdx = tgtIndex.getIndex(x);
                    final int srcIdx = srcIndex.getIndex(x);
                    final int xx = x - x0;

                    coherence = coherenceBuffer.getElemFloatAt(srcIdx);
                    if (coherence == cohNoDataValue)
                        continue;

                    double snr = Math.abs(tileImagePower[yy][xx] - tileNoise[yy][xx]) / tileImagePower[yy][xx];
                    snr = Math.min(snr, 1.0);
                    if (snrData != null) {
                        snrData.setElemFloatAt(tgtIdx, (float) snr);
                    }

                    if (maskOutPoorCoherenceArea && snr < minGammaSNR) {
                        coherence = 1.0f;
                    } else {
                        coherence = (float) Math.min(coherence / (snr * sysCorrFactor), 1.0f);
                    }

                    tgtDataBuffer.setElemFloatAt(tgtIdx, coherence);
                }
            }

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        } finally {
            pm.done();
        }
    }

    private void computeTileNoise(final int x0, final int xMax, final int y0, final int yMax, float[][] tileNoise) {

        if (isTanDEMX) {
            computeTileNoiseTanDEMX(x0, xMax, y0, yMax, tileNoise);
        } else if (isS1) {
            computeTileNoiseS1(x0, xMax, y0, yMax, tileNoise);
        }
    }

    private void computeTileNoiseTanDEMX(
            final int x0, final int xMax, final int y0, final int yMax, float[][] tileNoise) {

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

    private void computeTileNoiseS1(
            final int x0, final int xMax, final int y0, final int yMax, float[][] tileNoise) {

        final ThermalNoiseInfo noiseInfo = getNoiseInfo(coherenceBand.getName());

        for (int y = y0; y < yMax; ++y) {
            final int noiseVecIdx = getNoiseVectorIndex(y, noiseInfo);
            final Sentinel1Utils.NoiseVector noiseVector0 = noiseInfo.noiseVectorList[noiseVecIdx];
            final Sentinel1Utils.NoiseVector noiseVector1 = noiseInfo.noiseVectorList[noiseVecIdx + 1];

            final double azTime = noiseInfo.firstLineTime + y * noiseInfo.lineTimeInterval;
            final double azT0 = noiseVector0.timeMJD;
            final double azT1 = noiseVector1.timeMJD;
            final double muY = (azTime - azT0) / (azT1 - azT0);

            int pixelIdx0 = getPixelIndex(x0, noiseVector0);
            int pixelIdx1 = getPixelIndex(x0, noiseVector1);

            final int maxLength0 = noiseVector0.pixels.length - 2;
            final int maxLength1 = noiseVector1.pixels.length - 2;

            for (int x = x0; x < xMax; ++x) {

                if (x > noiseVector0.pixels[pixelIdx0 + 1] && pixelIdx0 < maxLength0) {
                    pixelIdx0++;
                }
                final int x00 = noiseVector0.pixels[pixelIdx0];
                final int x01 = noiseVector0.pixels[pixelIdx0 + 1];
                final double muX0 = (double) (x - x00) / (double) (x01 - x00);
                final double noise0 = Maths.interpolationLinear(
                        noiseVector0.noiseLUT[pixelIdx0], noiseVector0.noiseLUT[pixelIdx0 + 1], muX0);

                if (x > noiseVector1.pixels[pixelIdx1 + 1] && pixelIdx1 < maxLength1) {
                    pixelIdx1++;
                }
                final int x10 = noiseVector1.pixels[pixelIdx1];
                final int x11 = noiseVector1.pixels[pixelIdx1 + 1];
                final double muX1 = (double) (x - x10) / (double) (x11 - x10);
                final double noise1 = Maths.interpolationLinear(
                        noiseVector1.noiseLUT[pixelIdx1], noiseVector1.noiseLUT[pixelIdx1 + 1], muX1);

                tileNoise[y - y0][x - x0] = (float) Maths.interpolationLinear(noise0, noise1, muY);
            }
        }
    }

    private ThermalNoiseInfo getNoiseInfo(final String targetBandName) throws OperatorException {

        for (ThermalNoiseInfo noiseInfo : noise) {
            if (targetBandName.contains(noiseInfo.polarization) && targetBandName.contains(noiseInfo.subSwath)) {
                return noiseInfo;
            }
        }
        throw new OperatorException("NoiseInfo not found for " + targetBandName);
    }

    private static int getNoiseVectorIndex(final int y, final ThermalNoiseInfo noiseInfo) {
        for (int i = 1; i < noiseInfo.count; i++) {
            if (y < noiseInfo.noiseVectorList[i].line) {
                return i - 1;
            }
        }
        return noiseInfo.count - 2;
    }

    private static int getPixelIndex(final int x, final Sentinel1Utils.NoiseVector noiseVector) {

        for (int i = 0; i < noiseVector.pixels.length; i++) {
            if (x < noiseVector.pixels[i]) {
                return i - 1;
            }
        }
        return noiseVector.pixels.length - 2;
    }

    private void computeTileImagePower(
            final int x0, final int y0, final int w, final int h, float[][] tileImagePower) {

        final Rectangle sourceRectangle = getSourceTileRectangle(x0, y0, w, h, halfFilterSize, halfFilterSize,
                sourceImageWidth, sourceImageHeight);

        final Tile mstTileI = getSourceTile(mstBandI, sourceRectangle);
        final Tile mstTileQ = getSourceTile(mstBandQ, sourceRectangle);
        final ProductData mstDataI = mstTileI.getDataBuffer();
        final ProductData mstDataQ = mstTileQ.getDataBuffer();

        final Tile slvTileI = getSourceTile(slvBandI, sourceRectangle);
        final Tile slvTileQ = getSourceTile(slvBandQ, sourceRectangle);
        final ProductData slvDataI = slvTileI.getDataBuffer();
        final ProductData slvDataQ = slvTileQ.getDataBuffer();

        final TileIndex srcIndex = new TileIndex(mstTileI);

        final int yMax = y0 + h;
        final int xMax = x0 + w;
        for (int y = y0; y < yMax; ++y) {
            final int yy = y - y0;
            for (int x = x0; x < xMax; ++x) {
                final int xx = x - x0;
                final double meanMstPwr = getMeanValue(x, y, mstDataI, mstDataQ, srcIndex);
                final double meanSlvPwr = getMeanValue(x, y, slvDataI, slvDataQ, srcIndex);
                tileImagePower[yy][xx] = (float) Math.min(meanMstPwr, meanSlvPwr);
            }
        }
    }

    private double getMeanValue(
            final int tx, final int ty, final ProductData dataI, final ProductData dataQ, final TileIndex srcIndex) {

        final int minX = Math.max(tx - halfFilterSize, 0);
        final int maxX = Math.min(tx + halfFilterSize, sourceImageWidth - 1);
        final int minY = Math.max(ty - halfFilterSize, 0);
        final int maxY = Math.min(ty + halfFilterSize, sourceImageHeight - 1);

        int numValidSamples = 0;
        double sum = 0.0;
        for (int y = minY; y <= maxY; y++) {
            srcIndex.calculateStride(y);
            for (int x = minX; x <= maxX; x++) {
                final int idx = srcIndex.getIndex(x);
                final double I = dataI.getElemDoubleAt(idx);
                final double Q = dataQ.getElemDoubleAt(idx);
                sum += I * I + Q * Q;
                numValidSamples++;
            }
        }
        return sum / numValidSamples;
    }

    private Rectangle getSourceTileRectangle(final int x0, final int y0, final int w, final int h,
                                             final int halfSizeX, final int halfSizeY,
                                             final int sourceImageWidth, final int sourceImageHeight) {
        final int sx0 = Math.max(0, x0 - halfSizeX);
        final int sy0 = Math.max(0, y0 - halfSizeY);
        final int sw = Math.min(x0 + w + halfSizeX, sourceImageWidth) - sx0;
        final int sh = Math.min(y0 + h + halfSizeY, sourceImageHeight) - sy0;
        return new Rectangle(sx0, sy0, sw, sh);
    }


    public final static class NoiseRecord {
        public double timeUTC;
        public double noiseEstimateConfidence;
        public double validityRangeMin;
        public double validityRangeMax;
        public double referencePoint;
        public int polynomialDegree;
        public double[] coefficient;
        public int azimuthIndex;
        public double[] rangeLineNoise;
    }

    public static class ThermalNoiseInfo {
        public String polarization;
        public String subSwath;
        public double firstLineTime;
        public double lastLineTime;
        public int numOfLines;
        public int count; // number of noiseVector records within the list
        public Sentinel1Utils.NoiseVector[] noiseVectorList;

        final double lineTimeInterval;

        ThermalNoiseInfo(final String pol, final String subSwath, final double firstLineTime, final double lastLineTime,
                         final int numOfLines, final int count, final Sentinel1Utils.NoiseVector[] noiseVectorList) {
            this.polarization = pol;
            this.subSwath = subSwath;
            this.firstLineTime = firstLineTime;
            this.lastLineTime = lastLineTime;
            this.numOfLines = numOfLines;
            this.count = count;
            this.noiseVectorList = noiseVectorList;

            lineTimeInterval = (lastLineTime - firstLineTime) / (numOfLines - 1);
        }
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
            super(SinglePolCoherenceCompensationOp.class);
        }
    }
}