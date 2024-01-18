/*
 * Copyright (C) 20123 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.sentinel1.gpf;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.SARGeocoding;
import org.esa.snap.core.dataio.ProductIO;
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
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.gpf.*;
import org.esa.snap.engine_utilities.util.Maths;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.HashMap;
import java.util.Map;

/**
 * The operator performs ETAD correction for S-1 GRD products.
 * Note: All times used in this operator are in seconds unless specified.
 */
@OperatorMetadata(alias = "ETAD-Correction",
        category = "Radar/Sentinel-1 TOPS",
        authors = "Jun Lu, Luis Veci",
        copyright = "Copyright (C) 2023 by SkyWatch Space Applications Inc.",
        version = "1.0",
        description = "ETAD correction of S-1 GRD products")
public class ETADCorrectionOp extends Operator {

    @SourceProduct(alias = "source")
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(description = "The list of source bands.", alias = "sourceBands",
            rasterDataNodeType = Band.class, label = "Source Band")
    private String[] sourceBandNames;

    @Parameter(label = "ETAD product")
    private File etadFile = null;

    private int sourceImageWidth = 0;
    private int sourceImageHeight = 0;
    private double firstLineTime = 0.0;
    private double lastLineTime = 0.0;
    private double lineTimeInterval = 0.0;
    private double slantRangeTimeToFirstPixel = 0.0;
    private double groundRangeSpacing = 0.0;
    private Product etadProduct = null;
    private AbstractMetadata.SRGRCoefficientList[] srgrConvParams = null;
    private ETADUtils etadUtils = null;
    protected static final String PRODUCT_SUFFIX = "_etad";


    /**
     * Default constructor. The graph processing framework
     * requires that an operator has a default constructor.
     */
    public ETADCorrectionOp() {
    }

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
            final InputProductValidator validator = new InputProductValidator(sourceProduct);
            validator.checkIfSARProduct();
            validator.checkIfSentinel1Product();
            validator.checkIfGRD();

            getSourceProductMetadata();

            // Get ETAD product
            etadProduct = getETADProduct(etadFile);

            // Check if the ETAD product matches the GRD product
            validateETADProduct(sourceProduct, etadProduct);

            // Get ETAD product metadata
            etadUtils = new ETADUtils(etadProduct);

            // Create target product that has the same dimension and same bands as the source product does
            createTargetProduct();

            // Set flag indicating that ETAD correction has been applied
            updateTargetProductMetadata();

            // todo Should create different correctors for GRD, SM and TOPS products similar to the calibrator for calibrationOp

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private void getSourceProductMetadata() {

        try {
            sourceImageWidth = sourceProduct.getSceneRasterWidth();
            sourceImageHeight = sourceProduct.getSceneRasterHeight();

            final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);
            firstLineTime = absRoot.getAttributeUTC(AbstractMetadata.first_line_time).getMJD() * Constants.secondsInDay;
            lastLineTime = absRoot.getAttributeUTC(AbstractMetadata.last_line_time).getMJD() * Constants.secondsInDay;
            lineTimeInterval = (lastLineTime - firstLineTime) / (sourceImageHeight - 1);

            slantRangeTimeToFirstPixel = absRoot.getAttributeDouble(AbstractMetadata.slant_range_to_first_pixel) / Constants.halfLightSpeed;
            groundRangeSpacing = absRoot.getAttributeDouble(AbstractMetadata.range_spacing);
            srgrConvParams = AbstractMetadata.getSRGRCoefficients(absRoot);
            if (srgrConvParams == null || srgrConvParams.length == 0) {
                throw new OperatorException("Invalid SRGR Coefficients for product " + sourceProduct.getName());
            }

        } catch(Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private Product getETADProduct(final File etadFile) {

        try {
            return ProductIO.readProduct(etadFile);
        } catch(Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
        return null;

    }

    private void validateETADProduct(final Product sourceProduct, final Product etadProduct) {

        try {
            final MetadataElement srcOrigProdRoot = AbstractMetadata.getOriginalProductMetadata(sourceProduct);
            final MetadataElement srcAnnotation = srcOrigProdRoot.getElement("annotation");
            if (srcAnnotation == null) {
                throw new IOException("Annotation Metadata not found for product: " + sourceProduct.getName());
            }
            final MetadataElement srcProdElem = srcAnnotation.getElements()[0].getElement("product");
            final MetadataElement adsHeaderElem = srcProdElem.getElement("adsHeader");
            final double srcStartTime = ETADUtils.getTime(adsHeaderElem, "startTime").getMJD()* Constants.secondsInDay;
            final double srcStopTime = ETADUtils.getTime(adsHeaderElem, "stopTime").getMJD()* Constants.secondsInDay;

            final MetadataElement etadOrigProdRoot = AbstractMetadata.getOriginalProductMetadata(etadProduct);
            final MetadataElement etadAnnotation = etadOrigProdRoot.getElement("annotation");
            if (etadAnnotation == null) {
                throw new IOException("Annotation Metadata not found for ETAD product: " + etadProduct.getName());
            }
            final MetadataElement etadProdElem = etadAnnotation.getElement("etadProduct");
            final MetadataElement etadHeaderElem = etadProdElem.getElement("etadHeader");
            final double etadStartTime = ETADUtils.getTime(etadHeaderElem, "startTime").getMJD()* Constants.secondsInDay;
            final double etadStopTime = ETADUtils.getTime(etadHeaderElem, "stopTime").getMJD()* Constants.secondsInDay;

            if (srcStartTime < etadStartTime || srcStopTime > etadStopTime) {
                throw new OperatorException("The selected ETAD product does not match the source product");
            }

        } catch(Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    /**
     * Create target product.
     */
    public Product createTargetProduct() {

        targetProduct = new Product(sourceProduct.getName() + PRODUCT_SUFFIX,
                sourceProduct.getProductType(), sourceImageWidth, sourceImageHeight);

        for (Band srcBand : sourceProduct.getBands()) {
            if (srcBand instanceof VirtualBand) {
                continue;
            }

            final Band targetBand = new Band(srcBand.getName(), ProductData.TYPE_FLOAT32,
                    srcBand.getRasterWidth(), srcBand.getRasterHeight());

            targetBand.setUnit(srcBand.getUnit());
            targetBand.setDescription(srcBand.getDescription());
            targetProduct.addBand(targetBand);
        }

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        return targetProduct;
    }

    /**
     * Update the metadata in the target product.
     */
    private void updateTargetProductMetadata() {

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(targetProduct);
        AbstractMetadata.setAttribute(absRoot, "etad_correction_flag", 1);
    }

    /**
     * Called by the framework in order to compute a tile for the given target band.
     * <p>The default implementation throws a runtime exception with the message "not implemented".</p>
     *
     * @param targetBand The target band.
     * @param targetTile The current tile associated with the target band to be computed.
     * @param pm         A progress monitor which should be used to determine computation cancellation requests.
     * @throws OperatorException If an error occurs during computation of the target raster.
     */
    @Override
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) throws OperatorException {

        try {
            // Get source tile which is larger than the target tile
            final Rectangle targetRectangle = targetTile.getRectangle();
            final int tx0 = targetRectangle.x;
            final int ty0 = targetRectangle.y;
            final int tw = targetRectangle.width;
            final int th = targetRectangle.height;
            final int txMax = tx0 + tw - 1;
            final int tyMax = ty0 + th - 1;

            final Rectangle srcRectangle = getSourceRectangle(tx0, ty0, tw, th);
            final int sx0 = srcRectangle.x;
            final int sy0 = srcRectangle.y;
            final int sw = srcRectangle.width;
            final int sh = srcRectangle.height;
            final int sxMax = sx0 + sw - 1;
            final int syMax = sy0 + sh - 1;

            // Compute range/azimuth corrections for the source tile using bi-linear interpolation
            final double[] azTime = new double[sh];
            final double[][] rgTime = new double[sh][sw];
            computeAzRgTimesForCurrentTile(srcRectangle, azTime, rgTime);

            final double[][] azTimeCorr = new double[sh][sw];
            final double[][] rgTimeCorr = new double[sh][sw];
            getAzTimeCorrectionForCurrentTile(srcRectangle, azTime, rgTime, azTimeCorr);
            getRgTimeCorrectionForCurrentTile(srcRectangle, azTime, rgTime, rgTimeCorr);

            // Resample the source tile image to target tile
            final Band srcBand = sourceProduct.getBand(targetBand.getName());
            final Tile srcTile = getSourceTile(srcBand, srcRectangle);
            final ProductData srcData = srcTile.getDataBuffer();

            final ProductData tgtData = targetTile.getDataBuffer();
            final TileIndex srcIndex = new TileIndex(srcTile);
            final TileIndex tgtIndex = new TileIndex(targetTile);

            int y0, y1, x0, x1, i0, i1, j0, j1;
            for (int y = ty0; y <= tyMax; ++y) {
                tgtIndex.calculateStride(y);
                srcIndex.calculateStride(y);
                final int i = y - sy0;

                for (int x = tx0; x <= txMax; ++ x) {
                    final int tgtIdx = tgtIndex.getIndex(x);

                    final int j = x - sx0;
                    final double at = azTime[i];
                    final double rt = rgTime[i][j];
                    final double atc = azTimeCorr[i][j];
                    final double rtc = rgTimeCorr[i][j];
                    if (at > atc) {
                        i1 = Math.min(i + 1, sh - 1);
                        i0 = i1 - 1;
                        y1 = Math.min(y + 1, syMax);
                        y0 = y1 - 1;
                    } else {
                        i0 = Math.max(i - 1, 0);
                        i1 = i0 + 1;
                        y0 = Math.max(y - 1, 0);
                        y1 = y0 + 1;
                    }

                    if (rt > rtc) {
                        j1 = Math.min(j + 1, sw - 1);
                        j0 = j1 - 1;
                        x1 = Math.min(x + 1, sxMax);
                        x0 = x1 - 1;
                    } else {
                        j0 = Math.max(j - 1, 0);
                        j1 = j0 + 1;
                        x0 = Math.max(x - 1, 0);
                        x1 = x0 + 1;
                    }

                    final double v00 = getPixelValue(x0, y0, srcIndex, srcData);
                    final double v01 = getPixelValue(x1, y0, srcIndex, srcData);
                    final double v10 = getPixelValue(x0, y1, srcIndex, srcData);
                    final double v11 = getPixelValue(x1, y1, srcIndex, srcData);
                    final double atc00 = azTimeCorr[i0][j0];
                    final double rtc00 = rgTimeCorr[i0][j0];
                    final double atc01 = azTimeCorr[i0][j1];
                    final double rtc01 = rgTimeCorr[i0][j1];
                    final double atc10 = azTimeCorr[i1][j0];
                    final double rtc10 = rgTimeCorr[i1][j0];
                    final double atc11 = azTimeCorr[i1][j1];
                    final double rtc11 = rgTimeCorr[i1][j1];

                    final double v = IrregularGridBiLinearInterpolation(
                            at, rt, atc00, rtc00, atc01, rtc01, atc10, rtc10, atc11, rtc11, v00, v01, v10, v11);

                    tgtData.setElemDoubleAt(tgtIdx, v);
                }
            }

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private Rectangle getSourceRectangle(final int tx0, final int ty0, final int tw, final int th) {

        final int margin = 1;
        final int x0 = Math.max(0, tx0 - margin);
        final int y0 = Math.max(0, ty0 - margin);
        final int xMax = Math.min(tx0 + tw - 1 + margin, sourceImageWidth - 1);
        final int yMax = Math.min(ty0 + th - 1 + margin, sourceImageHeight - 1);
        final int w = xMax - x0 + 1;
        final int h = yMax - y0 + 1;
        return new Rectangle(x0, y0, w, h);
    }

    private double getPixelValue(final int x, final int y, final TileIndex srcIndex, final ProductData srcData) {

        srcIndex.calculateStride(y);
        final int srcIdx = srcIndex.getIndex(x);
        return srcData.getElemDoubleAt(srcIdx);
    }

    private void computeAzRgTimesForCurrentTile(
            final Rectangle srcRectangle, final double[] azTime, final double[][] rgTime) throws Exception {

        final int sx0 = srcRectangle.x;
        final int sy0 = srcRectangle.y;
        final int sw = srcRectangle.width;
        final int sh = srcRectangle.height;
        final int sxMax = sx0 + sw - 1;
        final int syMax = sy0 + sh - 1;

        for (int y = sy0; y <= syMax; ++y) {
            final int j = y - sy0;
            azTime[j] = firstLineTime + y * lineTimeInterval;
            final double azimuthTimeInDays = azTime[j] / Constants.secondsInDay;
            final double[] srgrCoefficients = SARGeocoding.getSRGRCoefficients(azimuthTimeInDays, srgrConvParams);

            for (int x = sx0; x <= sxMax; ++x) {
                final int i = x - sx0;
                final double groundRange = x * groundRangeSpacing;
                final double slantRange = Maths.computePolynomialValue(groundRange, srgrCoefficients);
                rgTime[j][i] = slantRange / Constants.halfLightSpeed;
            }
        }
    }

    private void getAzTimeCorrectionForCurrentTile(final Rectangle srcRectangle, final double[] azTime,
                                                   final double[][] rgTime, final double[][] azTimeCorr) {

        Map<String, double[][]> sumAzCorrectionMap = new HashMap<>(10);
        final int sx0 = srcRectangle.x;
        final int sy0 = srcRectangle.y;
        final int sw = srcRectangle.width;
        final int sh = srcRectangle.height;
        final int sxMax = sx0 + sw - 1;
        final int syMax = sy0 + sh - 1;

        for (int y = sy0; y <= syMax; ++y) {
            final int i = y - sy0;
            for (int x = sx0; x <= sxMax; ++x) {
                final int j = x - sx0;
                final double corr = getCorrection("sumOfCorrectionsAz", azTime[i], rgTime[i][j], sumAzCorrectionMap);
                azTimeCorr[i][j] = azTime[i] - corr;
            }
        }
    }

    private void getRgTimeCorrectionForCurrentTile(final Rectangle srcRectangle, final double[] azTime,
                                                   final double[][] rgTime, final double[][] rgTimeCorr) {

        Map<String, double[][]> sumRgCorrectionMap = new HashMap<>(10);
        final int sx0 = srcRectangle.x;
        final int sy0 = srcRectangle.y;
        final int sw = srcRectangle.width;
        final int sh = srcRectangle.height;
        final int sxMax = sx0 + sw - 1;
        final int syMax = sy0 + sh - 1;

        for (int y = sy0; y <= syMax; ++y) {
            final int i = y - sy0;
            for (int x = sx0; x <= sxMax; ++x) {
                final int j = x - sx0;
                final double corr = getCorrection("sumOfCorrectionsRg", azTime[i], rgTime[i][j], sumRgCorrectionMap);
                rgTimeCorr[i][j] = rgTime[i][j] - corr;
            }
        }
    }

    private double getCorrection(final String layer, final double azimuthTime, final double slantRangeTime,
                                 final Map<String, double[][]>layerCorrectionMap) {

        ETADUtils.Burst burst = etadUtils.getBurst(azimuthTime, slantRangeTime);
        if (burst == null) {
            return 0.0;
        }

        final String bandName = etadUtils.createBandName(burst.swathID, burst.bIndex, layer);
        double[][] layerCorrection = layerCorrectionMap.get(bandName);
        if (layerCorrection == null) {
            layerCorrection = etadUtils.getLayerCorrectionForCurrentBurst(burst, bandName);
            layerCorrectionMap.put(bandName, layerCorrection);
        }
        final double i = (azimuthTime - burst.azimuthTimeMin) / burst.gridSamplingAzimuth;
        final double j = (slantRangeTime - burst.rangeTimeMin) / burst.gridSamplingRange;
        final int i0 = (int)i;
        final int i1 = i0 + 1;
        final int j0 = (int)j;
        final int j1 = j0 + 1;
        final double c00 = layerCorrection[i0][j0];
        final double c01 = layerCorrection[i0][j1];
        final double c10 = layerCorrection[i1][j0];
        final double c11 = layerCorrection[i1][j1];
        return Maths.interpolationBiLinear(c00, c01, c10, c11, j - j0, i - i0);
    }

    private double IrregularGridBiLinearInterpolation(final double y, final double x, final double y1, final double x1,
                                                      final double y2, final double x2, final double y3, final double x3,
                                                      final double y4, final double x4, final double v1, final double v2,
                                                      final double v3, final double v4) {

        final double t12 = (x - x1) / (x2 - x1);
        final double y12 = y1 + t12 * (y2 - y1);
        final double v12 = v1 + t12 * (v2 - v1);

        final double t34 = (x - x3) / (x4 - x3);
        final double y34 = y3 + t34 * (y4 - y3);
        final double v34 = v3 + t34 * (v4 - v3);

        final double t = (y - y12) / (y34 - y12);
        return v12 + t * (v34 - v12);
    }
    
    public static class ETADUtils {

        private Product etadProduct = null;
        private MetadataElement absRoot = null;
        private MetadataElement origProdRoot = null;
        private double azimuthTimeMin = 0.0;
        private double azimuthTimeMax = 0.0;
        private double rangeTimeMin = 0.0;
        private double rangeTimeMax = 0.0;
        private int numInputProducts = 0;
        private int numSubSwaths = 0;
        private InputProduct[] inputProducts = null;

        public ETADUtils(final Product ETADProduct) throws Exception {

            etadProduct = ETADProduct;

            getMetadataRoot();

            getReferenceTimes();

            getInputProductMetadata();
        }
        
        private void getMetadataRoot() throws IOException {

            final MetadataElement root = etadProduct.getMetadataRoot();
            if (root == null) {
                throw new IOException("Root Metadata not found");
            }

            absRoot = AbstractMetadata.getAbstractedMetadata(etadProduct);
            if (absRoot == root) {
                throw new IOException(AbstractMetadata.ABSTRACT_METADATA_ROOT + " not found.");
            }

            origProdRoot = AbstractMetadata.getOriginalProductMetadata(etadProduct);
            if (origProdRoot == root) {
                throw new IOException("Original_Product_Metadata not found.");
            }

            final String mission = absRoot.getAttributeString(AbstractMetadata.MISSION);
            if (!mission.startsWith("SENTINEL-1")) {
                throw new IOException(mission + " is not a valid mission for Sentinel1 product.");
            }
        }

        private void getReferenceTimes() {

            final MetadataElement annotationElem = origProdRoot.getElement("annotation");
            azimuthTimeMin = getTime(annotationElem, "azimuthTimeMin").getMJD()*Constants.secondsInDay;
            azimuthTimeMax = getTime(annotationElem, "azimuthTimeMax").getMJD()*Constants.secondsInDay;
            rangeTimeMin = annotationElem.getAttributeDouble("rangeTimeMin");
            rangeTimeMax = annotationElem.getAttributeDouble("rangeTimeMax");
        }

        private void getInputProductMetadata() {

            final MetadataElement annotationElem = origProdRoot.getElement("annotation");
            final MetadataElement etadProductElem = annotationElem.getElement("etadProduct");
            final MetadataElement productComponentsElem = etadProductElem.getElement("productComponents");
            final MetadataElement inputProductListElem = productComponentsElem.getElement("inputProductList");

            numInputProducts = Integer.parseInt(productComponentsElem.getAttributeString("numberOfInputProducts"));
            numSubSwaths = Integer.parseInt(productComponentsElem.getAttributeString("numberOfSwaths"));
            inputProducts = new InputProduct[numInputProducts];

            final MetadataElement[] inputProductElemArray = inputProductListElem.getElements();
            for (int p = 0; p < numInputProducts; ++p) {
                inputProducts[p] = new InputProduct();
                inputProducts[p].startTime = getTime(inputProductElemArray[p], "startTime").getMJD()*Constants.secondsInDay;
                inputProducts[p].stopTime = getTime(inputProductElemArray[p], "stopTime").getMJD()*Constants.secondsInDay;
                inputProducts[p].pIndex = Integer.parseInt(inputProductElemArray[p].getAttributeString("pIndex"));
                final MetadataElement swathListElem = inputProductElemArray[p].getElement("swathList");
                final int numSwaths = Integer.parseInt(swathListElem.getAttributeString("count"));
                final MetadataElement[] swaths = swathListElem.getElements();
                inputProducts[p].swathArray = new SubSwath[numSwaths];

                for (int s = 0; s < numSwaths; ++s) {
                    inputProducts[p].swathArray[s] = new SubSwath();
                    inputProducts[p].swathArray[s].swathID = swaths[s].getAttributeString("swathID");
                    inputProducts[p].swathArray[s].sIndex = Integer.parseInt(swaths[s].getAttributeString("sIndex"));
                    final MetadataElement bIndexListElem = swaths[s].getElement("bIndexList");
                    final int numBursts = Integer.parseInt(bIndexListElem.getAttributeString("count"));
                    inputProducts[p].swathArray[s].bIndexArray = new int[numBursts];
                    inputProducts[p].swathArray[s].burstMap = new HashMap<>(numBursts);

                    int bIndex = -1;
                    for (int b = 0; b < numBursts; ++b) {
                        final MetadataAttribute bIndexStr = bIndexListElem.getAttributeAt(b);
                        if (bIndexStr.getName().equals("bIndex")) {
                            bIndex = Integer.parseInt(bIndexStr.getData().toString());
                            inputProducts[p].swathArray[s].bIndexArray[b] = bIndex;
                        }
                        if (bIndex != -1) {
                            inputProducts[p].swathArray[s].burstMap.put(bIndex,
                                    createBurst(inputProducts[p].swathArray[s].sIndex,
                                            inputProducts[p].swathArray[s].bIndexArray[b]));
                        }
                    }
                }
            }
        }

        private static ProductData.UTC getTime(final MetadataElement elem, final String tag) {

            DateFormat sentinelDateFormat = ProductData.UTC.createDateFormat("yyyy-MM-dd HH:mm:ss");
            String start = elem.getAttributeString(tag, AbstractMetadata.NO_METADATA_STRING);
            start = start.replace("T", " ");
            return AbstractMetadata.parseUTC(start, sentinelDateFormat);
        }

        private Burst createBurst(final int sIndex, final int bIndex) {

            final MetadataElement annotationElem = origProdRoot.getElement("annotation");
            final MetadataElement etadProductElem = annotationElem.getElement("etadProduct");
            final MetadataElement etadBurstListElem = etadProductElem.getElement("etadBurstList");
            final MetadataElement[] elements = etadBurstListElem.getElements();

            final Burst burst = new Burst();
            for (MetadataElement elem : elements) {
                // ID information
                final MetadataElement burstDataElem = elem.getElement("burstData");
                final int sIdx = Integer.parseInt(burstDataElem.getAttributeString("sIndex"));
                if (sIdx != sIndex) {
                    continue;
                }
                final int bIdx = Integer.parseInt(burstDataElem.getAttributeString("bIndex"));
                if (bIdx != bIndex) {
                    continue;
                }
                burst.bIndex = bIdx;
                burst.sIndex = sIdx;
                burst.pIndex = Integer.parseInt(burstDataElem.getAttributeString("pIndex"));
                burst.swathID = burstDataElem.getAttributeString("swathID");

                // coverage information
                final MetadataElement burstCoverageElem = elem.getElement("burstCoverage");
                final MetadataElement temporalCoverageElem = burstCoverageElem.getElement("temporalCoverage");
                final MetadataElement rangeTimeMinElem = temporalCoverageElem.getElement("rangeTimeMin");
                final MetadataElement rangeTimeMaxElem = temporalCoverageElem.getElement("rangeTimeMax");
                burst.rangeTimeMin = Double.parseDouble(rangeTimeMinElem.getAttributeString("rangeTimeMin"));
                burst.rangeTimeMax = Double.parseDouble(rangeTimeMaxElem.getAttributeString("rangeTimeMax"));
                burst.azimuthTimeMin = getTime(temporalCoverageElem, "azimuthTimeMin").getMJD()*Constants.secondsInDay;
                burst.azimuthTimeMax = getTime(temporalCoverageElem, "azimuthTimeMax").getMJD()*Constants.secondsInDay;

                // grid information
                final MetadataElement gridInformationElem = elem.getElement("gridInformation");
                final MetadataElement gridStartAzimuthTimeElem = gridInformationElem.getElement("gridStartAzimuthTime");
                final MetadataElement gridStartRangeTimeElem = gridInformationElem.getElement("gridStartRangeTime");
                final MetadataElement gridDimensionsElem = gridInformationElem.getElement("gridDimensions");
                final MetadataElement gridSamplingElem = gridInformationElem.getElement("gridSampling");
                final MetadataElement azimuth = gridSamplingElem.getElement("azimuth");
                final MetadataElement rangeElem = gridSamplingElem.getElement("range");
                burst.gridStartAzimuthTime = Double.parseDouble(gridStartAzimuthTimeElem.getAttributeString("gridStartAzimuthTime"));
                burst.gridStartRangeTime = Double.parseDouble(gridStartRangeTimeElem.getAttributeString("gridStartRangeTime"));
                burst.gridSamplingAzimuth = Double.parseDouble(azimuth.getAttributeString("azimuth"));
                burst.gridSamplingRange = Double.parseDouble(rangeElem.getAttributeString("range"));
                burst.azimuthExtent = Integer.parseInt(gridDimensionsElem.getAttributeString("azimuthExtent"));
                burst.rangeExtent = Integer.parseInt(gridDimensionsElem.getAttributeString("rangeExtent"));
            }
            return burst;
        }

        private String createBandName(final String swathID, final int bIndex, final String tag) {

            if (bIndex < 10) {
                return swathID + "_" + "Burst000" + bIndex + "_" + tag;
            } else if (bIndex < 100) {
                return swathID + "_" + "Burst00" + bIndex + "_" + tag;
            } else {
                return swathID + "_" + "Burst0" + bIndex + "_" + tag;
            }
        }

        public int getProductIndex(final double azimuthTime) {

            for (InputProduct prod : inputProducts) {
                if (prod.startTime <= azimuthTime && azimuthTime <= prod.stopTime) {
                    return prod.pIndex;
                }
            }
            return -1;
        }

        public int getSwathIndex(final int pIndex, final double slantRangeTime) {

            final SubSwath[] swathArray = inputProducts[pIndex - 1].swathArray;
            for (SubSwath swath : swathArray) {
                final Burst firstBurst = swath.burstMap.get(swath.bIndexArray[0]);
                if (slantRangeTime > firstBurst.rangeTimeMin && slantRangeTime < firstBurst.rangeTimeMax) {
                    return swath.sIndex;
                }
            }
            return -1;
        }

        public int getBurstIndex(final int pIndex, final int sIndex, final double azimuthTime) {

            final int[] bIndexArray = inputProducts[pIndex - 1].swathArray[sIndex - 1].bIndexArray;
            for (int bIndex : bIndexArray) {
                final Burst burst = inputProducts[pIndex - 1].swathArray[sIndex - 1].burstMap.get(bIndex);
                if (azimuthTime > burst.azimuthTimeMin && azimuthTime < burst.azimuthTimeMax) {
                    return bIndex;
                }
            }
            return -1;
        }

        private Burst getBurst(final double azimuthTime, final double slantRangeTime) {

            final int pIndex = getProductIndex(azimuthTime);
            if (pIndex == -1) {
                return null;
            }

            final int sIndex = getSwathIndex(pIndex, slantRangeTime);
            if (sIndex == -1) {
                return null;
            }

            int bIndex = getBurstIndex(pIndex, sIndex, azimuthTime);
            if (bIndex == -1) {
                if (pIndex + 1 <= numInputProducts) {
                    bIndex = getBurstIndex(pIndex + 1, sIndex, azimuthTime);
                    if (bIndex != -1) {
                        return inputProducts[pIndex].swathArray[sIndex - 1].burstMap.get(bIndex);
                    }
                }

                if (pIndex - 1 >= 1) {
                    bIndex = getBurstIndex(pIndex - 1, sIndex, azimuthTime);
                    if (bIndex != -1) {
                        return inputProducts[pIndex - 2].swathArray[sIndex - 1].burstMap.get(bIndex);
                    }
                }

                return null;
            }

            return inputProducts[pIndex - 1].swathArray[sIndex - 1].burstMap.get(bIndex);
        }

        private double[][] getLayerCorrectionForCurrentBurst(final Burst burst, final String bandName) {

            try {
                final Band layerBand = etadProduct.getBand(bandName);
                layerBand.readRasterDataFully(ProgressMonitor.NULL);
                final ProductData layerData = layerBand.getData();
                final double[][] correction = new double[burst.azimuthExtent][burst.rangeExtent];
                for (int a = 0; a < burst.azimuthExtent; ++a) {
                    for (int r = 0; r < burst.rangeExtent; ++r) {
                        correction[a][r] = layerData.getElemDoubleAt(a * burst.rangeExtent + r);
                    }
                }
                return correction;

            } catch (Exception e) {
                OperatorUtils.catchOperatorException("getLayerCorrectionForCurrentBurst", e);
            }

            return null;
        }

        public final static class InputProduct {
            public double startTime;
            public double stopTime;
            public int pIndex;
            public SubSwath[] swathArray;
        }

        public final static class SubSwath {
            public String swathID;
            public int sIndex;
            public int[] bIndexArray;
            public Map<Integer, Burst> burstMap;
        }

        public final static class Burst {
            public String swathID;
            public int bIndex;
            public int sIndex;
            public int pIndex;

            public double rangeTimeMin;
            public double rangeTimeMax;
            public double azimuthTimeMin;
            public double azimuthTimeMax;

            public double gridStartAzimuthTime;
            public double gridStartRangeTime;
            public double gridSamplingAzimuth;
            public double gridSamplingRange;
            public int azimuthExtent;
            public int rangeExtent;
        }
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
            super(ETADCorrectionOp.class);
        }
    }
}
