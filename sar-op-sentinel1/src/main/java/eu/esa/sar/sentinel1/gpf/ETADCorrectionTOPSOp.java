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
import eu.esa.sar.commons.Sentinel1Utils;
import org.apache.commons.math3.util.FastMath;
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
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
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
 * The operator performs ETAD correction for split Sentinel-1 TOPS SLC products.
 * The reason that the operator cannot take the original Sentinel-1 product with 3 sub-swaths as input is because
 * 1. computeTileStack cannot handle 3 sub-swathes with different dimensions,
 * 2. if computeTile is used, then the i-band and q-band must be processed twice in order to output i and q bands separately.
 * Note: All times used in this operator are in seconds unless specified.
 */
@OperatorMetadata(alias = "ETAD-Correction-TOPS",
        category = "Radar/Sentinel-1 TOPS",
        authors = "Jun Lu, Luis Veci",
        copyright = "Copyright (C) 2023 by SkyWatch Space Applications Inc.",
        version = "1.0",
        description = "ETAD correction of S-1 TOPS SLC products")
public class ETADCorrectionTOPSOp extends Operator {

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
    private double slantRangeToFirstPixel = 0.0;
    private double rangeSpacing = 0.0;
    private Product etadProduct = null;
    private ETADUtils etadUtils = null;

    private Sentinel1Utils mSU = null;
    private Sentinel1Utils.SubSwathInfo[] mSubSwath = null;
    private int subSwathIndex = 0;
    private String swathIndexStr = null;
    private double noDataValue = 0.0;

    protected static final String PRODUCT_SUFFIX = "_etad";


    /**
     * Default constructor. The graph processing framework
     * requires that an operator has a default constructor.
     */
    public ETADCorrectionTOPSOp() {
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
            validator.checkIfSLC();

            selectedResampling = ResamplingFactory.createResampling(resamplingType);
            if(selectedResampling == null) {
                throw new OperatorException("Resampling method "+ resamplingType + " is invalid");
            }

            mSU = new Sentinel1Utils(sourceProduct);
            mSubSwath = mSU.getSubSwath();
            mSU.computeDopplerRate();
            mSU.computeReferenceTime();

            final String[] mSubSwathNames = mSU.getSubSwathNames();
            if (mSubSwathNames.length != 1) {
                throw new OperatorException("Split product is expected.");
            }
            
            final String[] mPolarizations = mSU.getPolarizations();
            subSwathIndex = 1; // subSwathIndex is always 1 because of split product
            swathIndexStr = mSubSwathNames[0].substring(2);

            getSourceProductMetadata();

            // Get ETAD product
            etadProduct = getETADProduct(etadFile);

            // Check if the ETAD product matches the SLC product
            validateETADProduct(sourceProduct, etadProduct);

            // Get ETAD product metadata
            etadUtils = new ETADUtils(etadProduct);

            // Create target product that has the same dimension and same bands as the source product does
            createTargetProduct();

            // Set flag indicating that ETAD correction has been applied
            updateTargetProductMetadata();

            final Band masterBandI = BackGeocodingOp.getBand(sourceProduct, "i_", swathIndexStr, mSU.getPolarizations()[0]);
            if(masterBandI != null && masterBandI.isNoDataValueUsed()) {
                noDataValue = masterBandI.getNoDataValue();
            }

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
            slantRangeToFirstPixel = absRoot.getAttributeDouble(AbstractMetadata.slant_range_to_first_pixel);
            rangeSpacing = absRoot.getAttributeDouble(AbstractMetadata.range_spacing);

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

//            final Band targetBand = new Band(srcBand.getName(), srcBand.getDataType(),
//                    srcBand.getRasterWidth(), srcBand.getRasterHeight());
            final Band targetBand = new Band(srcBand.getName(), ProductData.TYPE_FLOAT32,
                    srcBand.getRasterWidth(), srcBand.getRasterHeight());

            targetBand.setUnit(srcBand.getUnit());
            targetBand.setDescription(srcBand.getDescription());
            targetProduct.addBand(targetBand);
            
            if(targetBand.getUnit() != null && targetBand.getUnit().equals(Unit.IMAGINARY)) {
                int idx = targetProduct.getBandIndex(targetBand.getName());
                ReaderUtils.createVirtualIntensityBand(targetProduct, targetProduct.getBandAt(idx-1), targetBand, "");
            }

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
     * @param targetTileMap   The target tiles associated with all target bands to be computed.
     * @param targetRectangle The rectangle of target tile.
     * @param pm              A progress monitor which should be used to determine computation cancelation requests.
     * @throws OperatorException
     *          If an error occurs during computation of the target raster.
     */
    @Override
    public void computeTileStack(Map<Band, Tile> targetTileMap, Rectangle targetRectangle, ProgressMonitor pm)
            throws OperatorException {

        try {
            final int tx0 = targetRectangle.x;
            final int ty0 = targetRectangle.y;
            final int tw = targetRectangle.width;
            final int th = targetRectangle.height;
            final int tyMax = ty0 + th;
            final int txMax = tx0 + tw;
//            System.out.println("x0 = " + x0 + ", y0 = " + y0 + ", w = " + w + ", h = " + h);

            for (int burstIndex = 0; burstIndex < mSubSwath[subSwathIndex - 1].numOfBursts; burstIndex++) {
                final int firstLineIdx = burstIndex * mSubSwath[subSwathIndex - 1].linesPerBurst;
                final int lastLineIdx = firstLineIdx + mSubSwath[subSwathIndex - 1].linesPerBurst - 1;

                if (tyMax <= firstLineIdx || ty0 > lastLineIdx) {
                    continue;
                }

                final int ntx0 = tx0;
                final int ntw = tw;
                final int nty0 = Math.max(ty0, firstLineIdx);
                final int ntyMax = Math.min(tyMax, lastLineIdx + 1);
                final int nth = ntyMax - nty0;
                //System.out.println("burstIndex = " + burstIndex + ": ntx0 = " + ntx0 + ", nty0 = " + nty0 + ", ntw = " + ntw + ", nth = " + nth);

                computePartialTile(subSwathIndex, burstIndex, ntx0, nty0, ntw, nth, targetTileMap);
            }

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private void computePartialTile(final int subSwathIndex, final int mBurstIndex, final int x0, final int y0,
                                    final int w, final int h, final Map<Band, Tile> targetTileMap) {

        try {
            final PixelPos[][] slavePixPos = new PixelPos[h][w];
            computeETADCorrPixPos(x0, y0, w, h, slavePixPos);

            final int margin = selectedResampling.getKernelSize();
            final Rectangle sourceRectangle = BackGeocodingOp.getBoundingBox(slavePixPos, margin, subSwathIndex,
                    mBurstIndex, mSU.getSubSwath());

            if (sourceRectangle == null) {
                return;
            }

            final double[][] mstDerampDemodPhase = mSU.computeDerampDemodPhase(mSubSwath,
                    subSwathIndex, mBurstIndex, sourceRectangle);


            for(String polarization : mSU.getPolarizations()) {
                final Band masterBandI = BackGeocodingOp.getBand(sourceProduct, "i_", swathIndexStr, polarization);
                final Band masterBandQ = BackGeocodingOp.getBand(sourceProduct, "q_", swathIndexStr, polarization);
                final Tile masterTileI = getSourceTile(masterBandI, sourceRectangle);
                final Tile masterTileQ = getSourceTile(masterBandQ, sourceRectangle);

                if (masterTileI == null || masterTileQ == null) {
                    return;
                }

                final double[][] mstDerampDemodI = new double[sourceRectangle.height][sourceRectangle.width];
                final double[][] mstDerampDemodQ = new double[sourceRectangle.height][sourceRectangle.width];

                BackGeocodingOp.performDerampDemod(masterTileI, masterTileQ, sourceRectangle, mstDerampDemodPhase,
                        mstDerampDemodI, mstDerampDemodQ);

                final Band targetBandI = targetProduct.getBand(masterBandI.getName());
                final Band targetBandQ = targetProduct.getBand(masterBandQ.getName());
                final Tile targetTileI = targetTileMap.get(targetBandI);
                final Tile targetTileQ = targetTileMap.get(targetBandQ);

                PerformETADCorrection(x0, y0, w, h, sourceRectangle, masterTileI, masterTileQ, targetTileI,
                        targetTileQ, mstDerampDemodPhase, mstDerampDemodI, mstDerampDemodQ, slavePixPos);

            }

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private void computeETADCorrPixPos(final int x0, final int y0, final int w, final int h,
                                       final PixelPos[][] slavePixPos) {

        final int xMax = x0 + w - 1;
        final int yMax = y0 + h - 1;

        Map<String, double[][]> sumAzCorrectionMap = new HashMap<>(10);
        Map<String, double[][]> sumRgCorrectionMap = new HashMap<>(10);

        for (int y = y0; y <= yMax; ++y) {
            final int i = y - y0;
            final double azTime = firstLineTime + y * lineTimeInterval;

            for (int x = x0; x <= xMax; ++x) {
                final int j = x - x0;
                final double rgTime = (slantRangeToFirstPixel + x * rangeSpacing) / Constants.halfLightSpeed;

                final double azCorr = getCorrection("sumOfCorrectionsAz", azTime, rgTime, sumAzCorrectionMap);
                final double rgCorr = getCorrection("sumOfCorrectionsRg", azTime, rgTime, sumRgCorrectionMap);
                final double azCorrTime = azTime - azCorr;
                final double rgCorrTime = rgTime - rgCorr;
                final double yCorr = (azCorrTime - firstLineTime) / lineTimeInterval;
                final double xCorr = (rgCorrTime * Constants.halfLightSpeed - slantRangeToFirstPixel) / rangeSpacing;
                slavePixPos[i][j] = new PixelPos(xCorr, yCorr);
            }
        }
    }

    private double getCorrection(final String layer, final double azimuthTime, final double slantRangeTime,
                                 final Map<String, double[][]>layerCorrectionMap) {

        ETADCorrectionTOPSOp.ETADUtils.Burst burst = etadUtils.getBurst(azimuthTime, slantRangeTime);
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

    private void PerformETADCorrection(final int x0, final int y0, final int w, final int h,
                                       final Rectangle sourceRectangle, final Tile slaveTileI, final Tile slaveTileQ,
                                       final Tile tgtTileI, final Tile tgtTileQ, final double[][] derampDemodPhase,
                                       final double[][] derampDemodI, final double[][] derampDemodQ,
                                       final PixelPos[][] slavePixPos) {

        try {
            final BackGeocodingOp.ResamplingRaster resamplingRasterI = new BackGeocodingOp.ResamplingRaster(slaveTileI, derampDemodI);
            final BackGeocodingOp.ResamplingRaster resamplingRasterQ = new BackGeocodingOp.ResamplingRaster(slaveTileQ, derampDemodQ);
            final BackGeocodingOp.ResamplingRaster resamplingRasterPhase = new BackGeocodingOp.ResamplingRaster(slaveTileI, derampDemodPhase);

            final ProductData tgtBufferI = tgtTileI.getDataBuffer();
            final ProductData tgtBufferQ = tgtTileQ.getDataBuffer();
            final TileIndex tgtIndex = new TileIndex(tgtTileI);

            final Resampling.Index resamplingIndex = selectedResampling.createIndex();

            final int sxMin = sourceRectangle.x;
            final int syMin = sourceRectangle.y;
            final int sxMax = sourceRectangle.x + sourceRectangle.width - 1;
            final int syMax = sourceRectangle.y + sourceRectangle.height - 1;

            for (int y = y0; y < y0 + h; y++) {
                tgtIndex.calculateStride(y);
                final int yy = y - y0;
                for (int x = x0; x < x0 + w; x++) {
                    final int xx = x - x0;
                    final int tgtIdx = tgtIndex.getIndex(x);
                    final PixelPos slavePixelPos = slavePixPos[yy][xx];

                    if (slavePixelPos == null || slavePixelPos.x < sxMin || slavePixelPos.x > sxMax ||
                            slavePixelPos.y < syMin || slavePixelPos.y > syMax) {

                        tgtBufferI.setElemDoubleAt(tgtIdx, noDataValue);
                        tgtBufferQ.setElemDoubleAt(tgtIdx, noDataValue);
                        continue;
                    }

                    selectedResampling.computeCornerBasedIndex(
                            slavePixelPos.x - sourceRectangle.x, slavePixelPos.y - sourceRectangle.y,
                            sourceRectangle.width, sourceRectangle.height, resamplingIndex);

                    final double samplePhase = selectedResampling.resample(resamplingRasterPhase, resamplingIndex);
                    final double cosPhase = FastMath.cos(samplePhase);
                    final double sinPhase = FastMath.sin(samplePhase);
                    double sampleI = selectedResampling.resample(resamplingRasterI, resamplingIndex);
                    double sampleQ = selectedResampling.resample(resamplingRasterQ, resamplingIndex);

                    double rerampRemodI;
                    if (Double.isNaN(sampleI)) {
                        sampleI = noDataValue;
                        rerampRemodI = noDataValue;
                    } else {
                        rerampRemodI = sampleI * cosPhase + sampleQ * sinPhase;
                    }

                    double rerampRemodQ;
                    if (Double.isNaN(sampleQ)) {
                        rerampRemodQ = noDataValue;
                    } else {
                        rerampRemodQ = -sampleI * sinPhase + sampleQ * cosPhase;
                    }

                    tgtBufferI.setElemDoubleAt(tgtIdx, rerampRemodI);
                    tgtBufferQ.setElemDoubleAt(tgtIdx, rerampRemodQ);
                }
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException("PerformETADCorrection", e);
        }
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
        private Map<String, double[][]> layerCorrectionMap = new HashMap<>(10);

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
            super(ETADCorrectionTOPSOp.class);
        }
    }
}
