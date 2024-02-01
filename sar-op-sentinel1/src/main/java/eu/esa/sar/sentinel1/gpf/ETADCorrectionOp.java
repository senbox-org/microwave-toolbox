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
import eu.esa.sar.insar.gpf.coregistration.DEMAssistedCoregistrationOp;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.dataop.resamp.Resampling;
import org.esa.snap.core.dataop.resamp.ResamplingFactory;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.core.util.SystemUtils;
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
import java.util.StringTokenizer;

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

    @Parameter(defaultValue = ResamplingFactory.BISINC_5_POINT_INTERPOLATION_NAME,
            description = "The method to be used for resampling the image from the un-corrected grid to the etad-corrected grid.",
            label = "Resampling Type")
    private String resamplingType = ResamplingFactory.BISINC_5_POINT_INTERPOLATION_NAME;

    private Resampling selectedResampling = null;
    private int sourceImageWidth = 0;
    private int sourceImageHeight = 0;
    private double firstLineTime = 0.0;
    private double lastLineTime = 0.0;
    private double lineTimeInterval = 0.0;
    private double slantRangeTimeToFirstPixel = 0.0;
    private double groundRangeSpacing = 0.0;
    private Product etadProduct = null;
    private SRGRCoefficientList[] srgrConvParams = null;
    private GRSRCoefficientList[] grsrConvParams = null;
    private ETADUtils etadUtils = null;

    protected static final String SUM_OF_CORRECTIONS_RG = "sumOfCorrectionsRg";
    protected static final String SUM_OF_CORRECTIONS_AZ = "sumOfCorrectionsAz";
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

            selectedResampling = ResamplingFactory.createResampling(resamplingType);
            if(selectedResampling == null) {
                throw new OperatorException("Resampling method "+ resamplingType + " is invalid");
            }

            getSourceProductMetadata();

            etadProduct = getETADProduct(etadFile);

            validateETADProduct(sourceProduct, etadProduct);

            etadUtils = new ETADUtils(etadProduct);

            createTargetProduct();

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

            final MetadataElement origProdRoot = AbstractMetadata.getOriginalProductMetadata(sourceProduct);
            final MetadataElement annotationElem = origProdRoot.getElement("annotation");
            final MetadataElement imgElem = annotationElem.getElementAt(0);
            final MetadataElement productElem = imgElem.getElement("product");
            final MetadataElement coordConvElem = productElem.getElement("coordinateConversion");
            final MetadataElement grsrCoefficientsElem = addGRSRCoefficients(coordConvElem);
            final MetadataElement srgrCoefficientsElem = addSRGRCoefficients(coordConvElem);
            grsrConvParams = getGRSRCoefficients(grsrCoefficientsElem);
            srgrConvParams = getSRGRCoefficients(srgrCoefficientsElem);
            if (grsrConvParams == null || grsrConvParams.length == 0) {
                throw new OperatorException("Invalid GRSR Coefficients for product " + sourceProduct.getName());
            }
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
            final int x0 = targetRectangle.x;
            final int y0 = targetRectangle.y;
            final int w = targetRectangle.width;
            final int h = targetRectangle.height;
            final int xMax = x0 + w - 1;
            final int yMax = y0 + h - 1;

            final PixelPos[][] slavePixPos = new PixelPos[h][w];
            computeETADCorrPixPos(x0, y0, w, h, slavePixPos);

            final int margin = selectedResampling.getKernelSize();
            final Rectangle srcRectangle = getSourceRectangle(x0, y0, w, h, margin);
            final Band srcBand = sourceProduct.getBand(targetBand.getName());
            final Tile srcTile = getSourceTile(srcBand, srcRectangle);
            final ProductData srcData = srcTile.getDataBuffer();

            final ProductData tgtData = targetTile.getDataBuffer();
            final TileIndex srcIndex = new TileIndex(srcTile);
            final TileIndex tgtIndex = new TileIndex(targetTile);

            final ResamplingRaster slvResamplingRaster = new ResamplingRaster(srcTile, srcData);
            final Resampling.Index resamplingIndex = selectedResampling.createIndex();

            for (int y = y0; y <= yMax; ++y) {
                tgtIndex.calculateStride(y);
                srcIndex.calculateStride(y);
                final int yy = y - y0;

                for (int x = x0; x <= xMax; ++ x) {
                    final int tgtIdx = tgtIndex.getIndex(x);
                    final int xx = x - x0;
                    final PixelPos slavePixelPos = slavePixPos[yy][xx];

                    selectedResampling.computeCornerBasedIndex(slavePixelPos.x, slavePixelPos.y,
                            sourceImageWidth, sourceImageHeight, resamplingIndex);

                    final double v = selectedResampling.resample(slvResamplingRaster, resamplingIndex);
                    tgtData.setElemDoubleAt(tgtIdx, v);
                }
            }

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private void computeETADCorrPixPos(final int x0, final int y0, final int w, final int h,
                                       final PixelPos[][] slavePixPos) throws Exception {

        Map<String, double[][]> sumAzCorrectionMap = new HashMap<>(10);
        Map<String, double[][]> sumRgCorrectionMap = new HashMap<>(10);

        for (int y = y0; y < y0 + h; ++y) {
            final int yy = y - y0;
            final double azTime = firstLineTime + y * lineTimeInterval;
            final double azimuthTimeInDays = azTime / Constants.secondsInDay;
            final double[] grsrCoefficients = getGRSRCoefficients(azimuthTimeInDays, grsrConvParams);
            final double[] srgrCoefficients = getSRGRCoefficients(azimuthTimeInDays, srgrConvParams);

            for (int x = x0; x < x0 + w; ++x) {
                final int xx = x - x0;
                final double groundRange = x * groundRangeSpacing;
                final double slantRange = Maths.computePolynomialValue(
                        groundRange - grsrConvParams[0].ground_range_origin, grsrCoefficients);
                final double rgTime = slantRange / Constants.halfLightSpeed;

                final double azCorr = getCorrection(SUM_OF_CORRECTIONS_AZ, azTime, rgTime, sumAzCorrectionMap);
                final double rgCorr = getCorrection(SUM_OF_CORRECTIONS_RG, azTime, rgTime, sumRgCorrectionMap);
                final double azCorrTime = azTime + azCorr;
                final double rgCorrTime = rgTime + rgCorr;
                final double slantRangeCorr = rgCorrTime * Constants.halfLightSpeed;
                final double groundRangeCorr = Maths.computePolynomialValue(
                        slantRangeCorr - srgrConvParams[0].slant_range_origin, srgrCoefficients);

                final double yCorr = (azCorrTime - firstLineTime) / lineTimeInterval;
                final double xCorr = groundRangeCorr / groundRangeSpacing;
                slavePixPos[yy][xx] = new PixelPos(xCorr, yCorr);
            }
        }
    }


    private Rectangle getSourceRectangle(final int tx0, final int ty0, final int tw, final int th, final int margin) {

        final int x0 = Math.max(0, tx0 - margin);
        final int y0 = Math.max(0, ty0 - margin);
        final int xMax = Math.min(tx0 + tw - 1 + margin, sourceImageWidth - 1);
        final int yMax = Math.min(ty0 + th - 1 + margin, sourceImageHeight - 1);
        final int w = xMax - x0 + 1;
        final int h = yMax - y0 + 1;
        return new Rectangle(x0, y0, w, h);
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

    private static class ResamplingRaster implements Resampling.Raster {

        private final Tile tile;
        private final ProductData dataBuffer;

        private ResamplingRaster(final Tile tile, final ProductData dataBuffer) {
            this.tile = tile;
            this.dataBuffer = dataBuffer;
        }

        public final int getWidth() {
            return tile.getWidth();
        }

        public final int getHeight() {
            return tile.getHeight();
        }

        public boolean getSamples(final int[] x, final int[] y, final double[][] samples) throws Exception {
            boolean allValid = true;

            try {
                final TileIndex index = new TileIndex(tile);
                final int maxX = x.length;
                for (int i = 0; i < y.length; i++) {
                    index.calculateStride(y[i]);
                    for (int j = 0; j < maxX; j++) {
                        double v = dataBuffer.getElemDoubleAt(index.getIndex(x[j]));
                        samples[i][j] = v;
                    }
                }

            } catch (Exception e) {
                SystemUtils.LOG.severe(e.getMessage());
                allValid = false;
            }

            return allValid;
        }
    }

    // todo: The following code should eventually moved into SARGeocoding and AbstractedMetadata
    private MetadataElement addGRSRCoefficients(final MetadataElement coordinateConversion) {

        DateFormat sentinelDateFormat = ProductData.UTC.createDateFormat("yyyy-MM-dd HH:mm:ss");

        if (coordinateConversion == null)
            return null;

        final MetadataElement coordinateConversionList = coordinateConversion.getElement("coordinateConversionList");
        if (coordinateConversionList == null)
            return null;

        final MetadataElement grsrCoefficientsElem = new MetadataElement("GRSR_Coefficients");

        int listCnt = 1;
        for (MetadataElement elem : coordinateConversionList.getElements()) {
            final MetadataElement grsrListElem = new MetadataElement("grsr_coef_list" + '.' + listCnt);
            grsrCoefficientsElem.addElement(grsrListElem);
            ++listCnt;

            final ProductData.UTC utcTime = ReaderUtils.getTime(elem, "azimuthTime", sentinelDateFormat);
            grsrListElem.setAttributeUTC("zero_doppler_time", utcTime);

            final double grOrigin = elem.getAttributeDouble("gr0", 0);
            AbstractMetadata.addAbstractedAttribute(grsrListElem, "ground_range_origin",
                    ProductData.TYPE_FLOAT64, "m", "Ground Range Origin");
            AbstractMetadata.setAttribute(grsrListElem, "ground_range_origin", grOrigin);

            final String coeffStr = elem.getElement("grsrCoefficients").getAttributeString("grsrCoefficients", "");
            if (!coeffStr.isEmpty()) {
                final StringTokenizer st = new StringTokenizer(coeffStr);
                int cnt = 1;
                while (st.hasMoreTokens()) {
                    final double coefValue = Double.parseDouble(st.nextToken());

                    final MetadataElement coefElem = new MetadataElement("coefficient" + '.' + cnt);
                    grsrListElem.addElement(coefElem);
                    ++cnt;
                    AbstractMetadata.addAbstractedAttribute(coefElem, "grsr_coef",
                            ProductData.TYPE_FLOAT64, "", "GRSR Coefficient");
                    AbstractMetadata.setAttribute(coefElem, "grsr_coef", coefValue);
                }
            }
        }
        return grsrCoefficientsElem;
    }

    private MetadataElement addSRGRCoefficients(final MetadataElement coordinateConversion) {

        DateFormat sentinelDateFormat = ProductData.UTC.createDateFormat("yyyy-MM-dd HH:mm:ss");

        if (coordinateConversion == null)
            return null;

        final MetadataElement coordinateConversionList = coordinateConversion.getElement("coordinateConversionList");
        if (coordinateConversionList == null)
            return null;

        final MetadataElement srgrCoefficientsElem = new MetadataElement("SRGR_Coefficients");

        int listCnt = 1;
        for (MetadataElement elem : coordinateConversionList.getElements()) {
            final MetadataElement srgrListElem = new MetadataElement("srgr_coef_list" + '.' + listCnt);
            srgrCoefficientsElem.addElement(srgrListElem);
            ++listCnt;

            final ProductData.UTC utcTime = ReaderUtils.getTime(elem, "azimuthTime", sentinelDateFormat);
            srgrListElem.setAttributeUTC("zero_doppler_time", utcTime);

            final double srOrigin = elem.getAttributeDouble("sr0", 0);
            AbstractMetadata.addAbstractedAttribute(srgrListElem, "slant_range_origin",
                    ProductData.TYPE_FLOAT64, "m", "Slant Range Origin");
            AbstractMetadata.setAttribute(srgrListElem, "slant_range_origin", srOrigin);

            final String coeffStr = elem.getElement("srgrCoefficients").getAttributeString("srgrCoefficients", "");
            if (!coeffStr.isEmpty()) {
                final StringTokenizer st = new StringTokenizer(coeffStr);
                int cnt = 1;
                while (st.hasMoreTokens()) {
                    final double coefValue = Double.parseDouble(st.nextToken());

                    final MetadataElement coefElem = new MetadataElement("coefficient" + '.' + cnt);
                    srgrListElem.addElement(coefElem);
                    ++cnt;
                    AbstractMetadata.addAbstractedAttribute(coefElem, "srgr_coef",
                            ProductData.TYPE_FLOAT64, "", "SRGR Coefficient");
                    AbstractMetadata.setAttribute(coefElem, "srgr_coef", coefValue);
                }
            }
        }
        return srgrCoefficientsElem;
    }

    private static GRSRCoefficientList[] getGRSRCoefficients(final MetadataElement grsrCoefficientsElem) {

        if(grsrCoefficientsElem != null) {
            final MetadataElement[] grsr_coef_listElem = grsrCoefficientsElem.getElements();
            final GRSRCoefficientList[] grsrCoefficientList = new GRSRCoefficientList[grsr_coef_listElem.length];
            int k = 0;
            for (MetadataElement listElem : grsr_coef_listElem) {
                final GRSRCoefficientList grsrList = new GRSRCoefficientList();
                grsrList.time = listElem.getAttributeUTC("zero_doppler_time");
                grsrList.timeMJD = grsrList.time.getMJD();
                grsrList.ground_range_origin = listElem.getAttributeDouble("ground_range_origin");

                final int numSubElems = listElem.getNumElements();
                grsrList.coefficients = new double[numSubElems];
                for (int i = 0; i < numSubElems; ++i) {
                    final MetadataElement coefElem = listElem.getElementAt(i);
                    grsrList.coefficients[i] = coefElem.getAttributeDouble("grsr_coef", 0.0);
                }
                grsrCoefficientList[k++] = grsrList;
            }
            return grsrCoefficientList;
        }
        return null;
    }

    private static SRGRCoefficientList[] getSRGRCoefficients(final MetadataElement srgrCoefficientsElem) {

        if(srgrCoefficientsElem != null) {
            final MetadataElement[] srgr_coef_listElem = srgrCoefficientsElem.getElements();
            final SRGRCoefficientList[] srgrCoefficientList = new SRGRCoefficientList[srgr_coef_listElem.length];
            int k = 0;
            for (MetadataElement listElem : srgr_coef_listElem) {
                final SRGRCoefficientList srgrList = new SRGRCoefficientList();
                srgrList.time = listElem.getAttributeUTC("zero_doppler_time");
                srgrList.timeMJD = srgrList.time.getMJD();
                srgrList.slant_range_origin = listElem.getAttributeDouble("slant_range_origin");

                final int numSubElems = listElem.getNumElements();
                srgrList.coefficients = new double[numSubElems];
                for (int i = 0; i < numSubElems; ++i) {
                    final MetadataElement coefElem = listElem.getElementAt(i);
                    srgrList.coefficients[i] = coefElem.getAttributeDouble("srgr_coef", 0.0);
                }
                srgrCoefficientList[k++] = srgrList;
            }
            return srgrCoefficientList;
        }
        return null;
    }

    private static double[] getGRSRCoefficients(final double zeroDopplerTime,
                                                final GRSRCoefficientList[] grsrConvParams) throws Exception {

        if(grsrConvParams == null || grsrConvParams.length == 0) {
            throw new Exception("SARGeoCoding: grsrConvParams not set");
        }

        final double[] grsrCoefficients = new double[grsrConvParams[0].coefficients.length];

        int idx = 0;
        if (grsrConvParams.length == 1) {
            System.arraycopy(grsrConvParams[0].coefficients, 0, grsrCoefficients, 0, grsrConvParams[0].coefficients.length);
        } else {
            for (int i = 0; i < grsrConvParams.length && zeroDopplerTime >= grsrConvParams[i].timeMJD; i++) {
                idx = i;
            }

            if (idx == grsrConvParams.length - 1) {
                idx--;
            }

            final double mu = (zeroDopplerTime - grsrConvParams[idx].timeMJD) /
                    (grsrConvParams[idx + 1].timeMJD - grsrConvParams[idx].timeMJD);

            for (int i = 0; i < grsrCoefficients.length; i++) {
                grsrCoefficients[i] = Maths.interpolationLinear(grsrConvParams[idx].coefficients[i],
                        grsrConvParams[idx + 1].coefficients[i], mu);
            }
        }

        return grsrCoefficients;
    }

    private static double[] getSRGRCoefficients(final double zeroDopplerTime,
                                                final SRGRCoefficientList[] srgrConvParams) throws Exception {

        if(srgrConvParams == null || srgrConvParams.length == 0) {
            throw new Exception("SARGeoCoding: srgrConvParams not set");
        }

        final double[] srgrCoefficients = new double[srgrConvParams[0].coefficients.length];

        int idx = 0;
        if (srgrConvParams.length == 1) {
            System.arraycopy(srgrConvParams[0].coefficients, 0, srgrCoefficients, 0, srgrConvParams[0].coefficients.length);
        } else {
            for (int i = 0; i < srgrConvParams.length && zeroDopplerTime >= srgrConvParams[i].timeMJD; i++) {
                idx = i;
            }

            if (idx == srgrConvParams.length - 1) {
                idx--;
            }

            final double mu = (zeroDopplerTime - srgrConvParams[idx].timeMJD) /
                    (srgrConvParams[idx + 1].timeMJD - srgrConvParams[idx].timeMJD);

            for (int i = 0; i < srgrCoefficients.length; i++) {
                srgrCoefficients[i] = Maths.interpolationLinear(srgrConvParams[idx].coefficients[i],
                        srgrConvParams[idx + 1].coefficients[i], mu);
            }
        }

        return srgrCoefficients;
    }

    public static class GRSRCoefficientList {
        public ProductData.UTC time = null;
        public double timeMJD = 0;
        public double ground_range_origin = 0.0;
        public double[] coefficients = null;
    }

    public static class SRGRCoefficientList {
        public ProductData.UTC time = null;
        public double timeMJD = 0;
        public double slant_range_origin = 0.0;
        public double[] coefficients = null;
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
