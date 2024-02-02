/*
 * Copyright (C) 2023 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
 * This operator performs ETAD correction for S-1 Stripmap SLC products.
 */
@OperatorMetadata(alias = "ETAD-Correction-SM",
        category = "Radar/Sentinel-1 TOPS",
        authors = "Jun Lu, Luis Veci",
        copyright = "Copyright (C) 2023 by SkyWatch Space Applications Inc.",
        version = "1.0",
        description = "ETAD correction of S-1 GRD products")
public class ETADCorrectionSMOp extends Operator {

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
            description = "Method for resampling ETAD corrected image from irregular grid to the regular grid.",
            label = "Resampling Type")
    private String resamplingType = ResamplingFactory.BISINC_5_POINT_INTERPOLATION_NAME;

    @Parameter(description = "Tropospheric Correction (Range)", defaultValue = "false",
            label = "Tropospheric Correction (Range)")
    private boolean troposphericCorrectionRg = false;

    @Parameter(description = "Ionospheric Correction (Range)", defaultValue = "false",
            label = "Ionospheric Correction (Range)")
    private boolean ionosphericCorrectionRg = false;

    @Parameter(description = "Geodetic Correction (Range)", defaultValue = "false",
            label = "Geodetic Correction (Range)")
    private boolean geodeticCorrectionRg = false;

    @Parameter(description = "Doppler Shift Correction (Range)", defaultValue = "false",
            label = "Doppler Shift Correction (Range)")
    private boolean dopplerShiftCorrectionRg = false;

    @Parameter(description = "Geodetic Correction (Azimuth)", defaultValue = "false",
            label = "Geodetic Correction (Azimuth)")
    private boolean geodeticCorrectionAz = false;

    @Parameter(description = "Bistatic Shift Correction (Azimuth)", defaultValue = "false",
            label = "Bistatic Shift Correction (Azimuth)")
    private boolean bistaticShiftCorrectionAz = false;

    @Parameter(description = "FM Mismatch Correction (Azimuth)", defaultValue = "false",
            label = "FM Mismatch Correction (Azimuth)")
    private boolean fmMismatchCorrectionAz = false;

    @Parameter(description = "Sum Of Azimuth Corrections", defaultValue = "false",
            label = "Sum Of Azimuth Corrections")
    private boolean sumOfAzimuthCorrections = false;

    @Parameter(description = "Sum Of Range Corrections", defaultValue = "false",
            label = "Sum Of Range Corrections")
    private boolean sumOfRangeCorrections = false;

//    @Parameter(description = "Interferometric Phase Correction (Range)", defaultValue = "false",
//            label = "Interferometric Phase Correction (Range)")
//    private boolean interferometricPhaseCorrectionRg = false;


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

    private String[] swathID = null;
    private String[] polarizations = null;

    private static final String RANGE_CORRECTION = "rangeCorrection";
    private static final String AZIMUTH_CORRECTION = "azimuthCorrection";
    private static final String INSAR_RANGE_CORRECTION = "inSARRangeCorrection";
    private static final String TROPOSPHERIC_CORRECTION_RG = "troposphericCorrectionRg";
    private static final String IONOSPHERIC_CORRECTION_RG = "ionosphericCorrectionRg";
    private static final String GEODETIC_CORRECTION_RG = "geodeticCorrectionRg";
    private static final String GEODETIC_CORRECTION_AZ = "geodeticCorrectionAz";
    private static final String BISTATIC_CORRECTION_AZ = "bistaticCorrectionAz";
    private static final String DOPPLER_RANGE_SHIFT_RG = "dopplerRangeShiftRg";
    private static final String FM_MISMATCH_CORRECTION_AZ = "fmMismatchCorrectionAz";
    private static final String SUM_OF_CORRECTION_RG = "sumOfCorrectionsRg";
    private static final String SUM_OF_CORRECTION_AZ = "sumOfCorrectionsAz";


    /**
     * Default constructor. The graph processing framework
     * requires that an operator has a default constructor.
     */
    public ETADCorrectionSMOp() {
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

            if (etadFile == null) {
                throw new OperatorException("Please select ETAD file");
            }

            if ((troposphericCorrectionRg || ionosphericCorrectionRg || geodeticCorrectionRg ||
                    dopplerShiftCorrectionRg) && sumOfRangeCorrections) {
                throw new OperatorException("Sum Of Range Corrections cannot be selected at the same time with other"
                        + " range corrections");
            }

            if ((geodeticCorrectionAz || bistaticShiftCorrectionAz || fmMismatchCorrectionAz) &&
                    sumOfAzimuthCorrections) {
                throw new OperatorException("Sum Of Azimuth Corrections cannot be selected at the same time with other"
                        + " azimuth corrections");
            }

            selectedResampling = ResamplingFactory.createResampling(resamplingType);
            if(selectedResampling == null) {
                throw new OperatorException("Resampling method "+ resamplingType + " is invalid");
            }

            getSourceProductMetadata();

            // Get ETAD product
            etadProduct = getETADProduct(etadFile);

            // Check if the ETAD product matches the SM product
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
            final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);
            if(absRoot == null) {
                throw new OperatorException("Abstracted Metadata not found");
            }

            final String acqMode = absRoot.getAttributeString(AbstractMetadata.ACQUISITION_MODE).toLowerCase();
            if (!acqMode.equals("sm")) {
                throw new OperatorException("StripMap product is expected.");
            }

            sourceImageWidth = sourceProduct.getSceneRasterWidth();
            sourceImageHeight = sourceProduct.getSceneRasterHeight();
            firstLineTime = absRoot.getAttributeUTC(AbstractMetadata.first_line_time).getMJD() * Constants.secondsInDay;
            lastLineTime = absRoot.getAttributeUTC(AbstractMetadata.last_line_time).getMJD() * Constants.secondsInDay;
            lineTimeInterval = (lastLineTime - firstLineTime) / (sourceImageHeight - 1);
            slantRangeToFirstPixel = absRoot.getAttributeDouble(AbstractMetadata.slant_range_to_first_pixel);
            rangeSpacing = absRoot.getAttributeDouble(AbstractMetadata.range_spacing);

            final MetadataElement srcOrigProdRoot = AbstractMetadata.getOriginalProductMetadata(sourceProduct);
            final MetadataElement srcAnnotation = srcOrigProdRoot.getElement("annotation");
            if (srcAnnotation == null) {
                throw new IOException("Annotation Metadata not found for product: " + sourceProduct.getName());
            }
            final MetadataElement[] elements = srcAnnotation.getElements();
            final int numOfPolarizations = elements.length;
            swathID = new String[numOfPolarizations];
            polarizations = new String[numOfPolarizations];
            for (int i = 0; i < numOfPolarizations; ++i) {
                final MetadataElement productElem = elements[i].getElement("product");
                final MetadataElement adsHeaderElem = productElem.getElement("adsHeader");
                swathID[i] = adsHeaderElem.getAttributeString("swath");
                polarizations[i] = adsHeaderElem.getAttributeString("polarisation");
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

        sourceImageWidth = sourceProduct.getSceneRasterWidth();
        sourceImageHeight = sourceProduct.getSceneRasterHeight();

        targetProduct = new Product(sourceProduct.getName(), sourceProduct.getProductType(),
                sourceImageWidth, sourceImageHeight);

        for (Band srcBand : sourceProduct.getBands()) {
            if (srcBand instanceof VirtualBand) {
                continue;
            }

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
            final int x0 = targetRectangle.x;
            final int y0 = targetRectangle.y;
            final int w = targetRectangle.width;
            final int h = targetRectangle.height;
            final int yMax = y0 + h - 1;
            final int xMax = x0 + w - 1;
            System.out.println("x0 = " + x0 + ", y0 = " + y0 + ", w = " + w + ", h = " + h);

            final PixelPos[][] slavePixPos = new PixelPos[h][w];
            computeETADCorrPixPos(x0, y0, w, h, slavePixPos);

            final int margin = selectedResampling.getKernelSize();
            final Rectangle srcRectangle = getSourceRectangle(x0, y0, w, h, margin);

            for (Band tgtBand : targetProduct.getBands()) {
                if (tgtBand instanceof VirtualBand) {
                    continue;
                }

                final Band srcBand = sourceProduct.getBand(tgtBand.getName());
                final Tile srcTile = getSourceTile(srcBand, srcRectangle);
                final ProductData srcData = srcTile.getDataBuffer();

                final Tile tgtTile = targetTileMap.get(tgtBand);
                final ProductData tgtData = tgtTile.getDataBuffer();
                final TileIndex srcIndex = new TileIndex(srcTile);
                final TileIndex tgtIndex = new TileIndex(tgtTile);

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
            }

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private void computeETADCorrPixPos(final int x0, final int y0, final int w, final int h,
                                       final PixelPos[][] slavePixPos) throws Exception {

        final double[][] azCorr = new double[h][w];
        getAzimuthTimeCorrectionForCurrentTile(x0, y0, w, h, azCorr);

        final double[][] rgCorr = new double[h][w];
        getRangeTimeCorrectionForCurrentTile(x0, y0, w, h, rgCorr);

        double azimuthTimeCalibration = 0.0;
        if (!sumOfAzimuthCorrections) {
            azimuthTimeCalibration = getInstrumentAzimuthTimeCalibration(swathID[0]);
        }

        double rangeTimeCalibration = 0.0;
        if (!sumOfRangeCorrections) {
            rangeTimeCalibration = getInstrumentRangeTimeCalibration(swathID[0]);
        }

        for (int y = y0; y < y0 + h; ++y) {
            final int yy = y - y0;
            final double azTime = firstLineTime + y * lineTimeInterval;

            for (int x = x0; x < x0 + w; ++x) {
                final int xx = x - x0;
                final double rgTime = (slantRangeToFirstPixel + x * rangeSpacing) / Constants.halfLightSpeed;

                final double azCorrTime = azTime + azCorr[yy][xx] + azimuthTimeCalibration;
                final double rgCorrTime = rgTime + rgCorr[yy][xx] + rangeTimeCalibration;

                final double yCorr = (azCorrTime - firstLineTime) / lineTimeInterval;
                final double xCorr = (rgCorrTime * Constants.halfLightSpeed - slantRangeToFirstPixel) / rangeSpacing;

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

    private void getAzimuthTimeCorrectionForCurrentTile(final int x0, final int y0, final int w, final int h,
                                                        final double[][] azCorrection) {

        if (geodeticCorrectionAz) {
            getCorrectionForCurrentTile(GEODETIC_CORRECTION_AZ, x0, y0, w, h, azCorrection);
        }

        if (bistaticShiftCorrectionAz) {
            getCorrectionForCurrentTile(BISTATIC_CORRECTION_AZ, x0, y0, w, h, azCorrection);
        }

        if (fmMismatchCorrectionAz) {
            getCorrectionForCurrentTile(FM_MISMATCH_CORRECTION_AZ, x0, y0, w, h, azCorrection);
        }

        if (sumOfAzimuthCorrections) {
            getCorrectionForCurrentTile(SUM_OF_CORRECTION_AZ, x0, y0, w, h, azCorrection);
        }
    }

    private void getRangeTimeCorrectionForCurrentTile(final int x0, final int y0, final int w, final int h,
                                                      final double[][] rgCorrection) {

        if (troposphericCorrectionRg) {
            getCorrectionForCurrentTile(TROPOSPHERIC_CORRECTION_RG, x0, y0, w, h, rgCorrection);
        }

        if (ionosphericCorrectionRg) {
            getCorrectionForCurrentTile(IONOSPHERIC_CORRECTION_RG, x0, y0, w, h, rgCorrection);
        }

        if (geodeticCorrectionRg) {
            getCorrectionForCurrentTile(GEODETIC_CORRECTION_RG, x0, y0, w, h, rgCorrection);
        }

        if (dopplerShiftCorrectionRg) {
            getCorrectionForCurrentTile(DOPPLER_RANGE_SHIFT_RG, x0, y0, w, h, rgCorrection);
        }

        if (sumOfRangeCorrections) {
            getCorrectionForCurrentTile(SUM_OF_CORRECTION_RG, x0, y0, w, h, rgCorrection);
        }
    }

    private void getInSARCorrectionForCurrentTile(final int x0, final int y0, final int w, final int h,
                                                  final double[][] inSARCorrection) {

        getCorrectionForCurrentTile(TROPOSPHERIC_CORRECTION_RG, x0, y0, w, h, inSARCorrection);

        getCorrectionForCurrentTile(GEODETIC_CORRECTION_RG, x0, y0, w, h, inSARCorrection);

        getCorrectionForCurrentTile(IONOSPHERIC_CORRECTION_RG, x0, y0, w, h, inSARCorrection, -1.0);
    }

    private void getCorrectionForCurrentTile(
            final String layer, final int x0, final int y0, final int w, final int h, final double[][] correction) {

        getCorrectionForCurrentTile(layer, x0, y0, w, h, correction, 1.0);
    }

    private void getCorrectionForCurrentTile(final String layer, final int x0, final int y0, final int w, final int h,
                                             final double[][] correction, final double scale) {

        Map<String, double[][]> correctionMap = new HashMap<>(10);
        final int xMax = x0 + w - 1;
        final int yMax = y0 + h - 1;

        for (int y = y0; y <= yMax; ++y) {
            final int yy = y - y0;
            final double azTime = firstLineTime + y * lineTimeInterval;
            for (int x = x0; x <= xMax; ++x) {
                final int xx = x - x0;
                final double rgTime = (slantRangeToFirstPixel + x * rangeSpacing) / Constants.halfLightSpeed;
                correction[yy][xx] += scale * getCorrection(layer, azTime, rgTime, correctionMap);
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

    private double getInstrumentAzimuthTimeCalibration(final String swathID) {

        if(geodeticCorrectionAz || bistaticShiftCorrectionAz || fmMismatchCorrectionAz) {
            return etadUtils.getAzimuthCalibration(swathID);
        } else {
            return 0.0;
        }
    }

    private double getInstrumentRangeTimeCalibration(final String swathID) {

        if(troposphericCorrectionRg || ionosphericCorrectionRg || geodeticCorrectionRg || dopplerShiftCorrectionRg) {
            return etadUtils.getRangeCalibration(swathID);
        } else {
            return 0.0;
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


    // ==============================================
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
        private InstrumentTimingCalibration[] instrumentTimingCalibrationList = null;

        public ETADUtils(final Product ETADProduct) throws Exception {

            etadProduct = ETADProduct;

            getMetadataRoot();

            getReferenceTimes();

            getInstrumentTimingCalibrationList();

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

        private void getInstrumentTimingCalibrationList() {

            final MetadataElement annotationElem = origProdRoot.getElement("annotation");
            final MetadataElement etadProductElem = annotationElem.getElement("etadProduct");
            final MetadataElement processingInformationElem = etadProductElem.getElement("processingInformation");
            final MetadataElement auxInputDataElem = processingInformationElem.getElement("auxInputData");
            final MetadataElement auxSetapElem = auxInputDataElem.getElement("auxSetap");
            final MetadataElement instrumentTimingCalibrationListElem = auxSetapElem.getElement("instrumentTimingCalibrationList");
            final MetadataElement[] instrumentTimingCalibrationArray = instrumentTimingCalibrationListElem.getElements();
            final int count = instrumentTimingCalibrationListElem.getAttributeInt("count");

            instrumentTimingCalibrationList = new InstrumentTimingCalibration[count];
            for (int s = 0; s < count; ++s) {
                final MetadataElement rangeCalibrationElem =
                        instrumentTimingCalibrationArray[s].getElement("rangeCalibration");
                final MetadataElement azimuthCalibrationElem =
                        instrumentTimingCalibrationArray[s].getElement("azimuthCalibration");

                instrumentTimingCalibrationList[s] = new InstrumentTimingCalibration();
                instrumentTimingCalibrationList[s].swathID =
                        instrumentTimingCalibrationArray[s].getAttributeString("swath");
                instrumentTimingCalibrationList[s].polarization =
                        instrumentTimingCalibrationArray[s].getAttributeString("polarisation");
                instrumentTimingCalibrationList[s].rangeCalibration =
                        Double.parseDouble(rangeCalibrationElem.getAttributeString("rangeCalibration"));
                instrumentTimingCalibrationList[s].azimuthCalibration =
                        Double.parseDouble(azimuthCalibrationElem.getAttributeString("azimuthCalibration"));
            }
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

        public double getRangeCalibration(final String swathID) {
            for (InstrumentTimingCalibration elem : instrumentTimingCalibrationList) {
                if (elem.swathID.equals(swathID)) {
                    return elem.rangeCalibration;
                }
            }
            return 0.0;
        }

        public double getAzimuthCalibration(final String swathID) {
            for (InstrumentTimingCalibration elem : instrumentTimingCalibrationList) {
                if (elem.swathID.equals(swathID)) {
                    return elem.azimuthCalibration;
                }
            }
            return 0.0;
        }

        public final static class InstrumentTimingCalibration {
            public double rangeCalibration;
            public double azimuthCalibration;
            public String swathID;
            public String polarization;
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
            super(ETADCorrectionSMOp.class);
        }
    }
}
