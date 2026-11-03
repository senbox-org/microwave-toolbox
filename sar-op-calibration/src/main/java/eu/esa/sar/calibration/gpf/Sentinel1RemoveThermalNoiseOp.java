/*
 * Copyright (C) 2014 by Array Systems Computing Inc. http://www.array.ca
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
package eu.esa.sar.calibration.gpf;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.calibration.gpf.calibrators.Sentinel1Calibrator;
import eu.esa.sar.calibration.gpf.support.NoiseSubtraction;
import eu.esa.sar.calibration.gpf.support.S1IpfVersion;
import eu.esa.sar.calibration.gpf.support.S1NoiseLutBuilder;
import eu.esa.sar.calibration.gpf.support.S1NoiseRecalibration;
import eu.esa.sar.commons.Sentinel1Utils;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.VirtualBand;
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
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;
import org.esa.snap.engine_utilities.util.Maths;

import java.awt.Rectangle;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Apply thermal noise correction to Sentinel-1 Level-1 products.
 */
@OperatorMetadata(alias = "ThermalNoiseRemoval",
        category = "Radar/Radiometric",
        authors = "Cecilia Wong, Jun Lu, Luis Veci",
        copyright = "Copyright (C) 2014 by Array Systems Computing Inc.",
        version = "1.0",
        description = "Removes thermal noise from products")
public final class Sentinel1RemoveThermalNoiseOp extends Operator {

    @SourceProduct
    private Product sourceProduct;
    @TargetProduct
    private Product targetProduct;

    @Parameter(description = "The list of polarisations", label = "Polarisations")
    private String[] selectedPolarisations;

    @Parameter(description = "Remove thermal noise", defaultValue = "true", label = "Remove Thermal Noise")
    private Boolean removeThermalNoise = true;

    @Parameter(description = "Output noise", defaultValue = "false", label = "Output Noise")
    private Boolean outputNoise = false;

    @Parameter(description = "Re-introduce thermal noise", defaultValue = "false", label = "Re-Introduce Thermal Noise")
    private Boolean reIntroduceThermalNoise = false;

    @Parameter(description = "Clip negative values after noise removal (disable when data will be spatially or temporally averaged downstream to avoid positive bias, see MPC-0392 TN v2.2 Section 6.2)",
            defaultValue = "true", label = "Clip Negative Values")
    private Boolean clipNegativeValues = true;

    @Parameter(description = "Apply the noise retro-calibration constants of Recchia et al. 2025 (IPF 3.1.0 and later)",
            defaultValue = "false", label = "Apply Noise Retro-Calibration")
    private Boolean applyNoiseRecalibration = false;

    @Parameter(description = "Value assigned to pixels clipped after noise removal (0 = ESA MPC-0392 Eq-2)",
            defaultValue = "1.0E-5", label = "Clip Floor Value")
    private Double clipFloorValue = 1.0E-5;

    private MetadataElement absRoot = null;
    private MetadataElement origMetadataRoot = null;
    private boolean absoluteCalibrationPerformed = false;
    private boolean isComplex = false;
    private boolean inputSigmaBand = false;
    private boolean inputBetaBand = false;
    private boolean inputGammaBand = false;
    private boolean inputDNBand = false;
    private boolean isTOPSARSLC = false;
    private boolean isSM = false;
    private String productType = null;
    private int numOfSubSwath = 1;
    private int subsetOffsetX = 0;
    private int subsetOffsetY = 0;
    private ThermalNoiseInfo[] noise = null;
    private Sentinel1Calibrator.CalibrationInfo[] calibration = null;
    private List<String> selectedPolList = null;
    private final HashMap<String, String[]> targetBandNameToSourceBandName = new HashMap<>(2);
    private final HashMap<String, String> targetNoiseBandNameToImageBandName = new HashMap<>(2);
    private final Map<String, S1NoiseLutBuilder> noiseBuilders = new HashMap<>();
    private final Map<String, Integer> linesPerBurstMap = new HashMap<>();
    private final Map<String, double[]> burstTimesMap = new HashMap<>();
    private final Map<String, Integer> firstBurstLineMap = new HashMap<>();

    // For after IPF 2.9.0 ...
    private double version = 0.0f;
    private boolean isTOPS = false;
    private boolean isGRD = false;

    /** Default clip floor (historic SNAP value); read by other calibrators, never written. */
    public static final float trgFloorValue = 1e-5f;

    private final AtomicBoolean warnedDuplicatePixel = new AtomicBoolean();

    public static final String NOISE_EQUIVALENT_SIGMA_ZERO = "NESZ";
    public static final String NOISE_EQUIVALENT_BETA_ZERO = "NEBZ";
    public static final String NOISE_EQUIVALENT_GAMMA_ZERO = "NEGZ";
    public static final String NOISE_EQUIVALENT_POWER = "NEP";

    private static final String PRODUCT_SUFFIX = "_NR";

    /**
     * Default constructor. The graph processing framework
     * requires that an operator has a default constructor.
     */
    public Sentinel1RemoveThermalNoiseOp() {
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
            validator.checkIfSentinel1Product();
            validator.checkAcquisitionMode(new String[] {"IW","EW","SM"});
            validator.checkProductType(new String[] {"SLC","GRD"});

            validator.checkIfMapProjected(false);

            absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);
            origMetadataRoot = AbstractMetadata.getOriginalProductMetadata(sourceProduct);

            final String mode = absRoot.getAttributeString(AbstractMetadata.ACQUISITION_MODE, "");
            if (absRoot.getAttributeString(AbstractMetadata.PRODUCT_TYPE, "").contains("SLC") &&
                    (mode.contains("IW") || mode.contains("EW")) && validator.isDebursted()) {
                // noise vectors are annotated per burst (MPC-0392); apply before TOPS Deburst
                throw new OperatorException("Source product should NOT be a deburst product: apply thermal noise removal before TOPS Deburst");
            }
            if (clipFloorValue == null || clipFloorValue.isNaN() || clipFloorValue.isInfinite() || clipFloorValue < 0) {
                throw new OperatorException("Clip floor value must be a finite, non-negative number");
            }
            checkRasterNotResampled();

            getSubsetOffset();

            getIPFVersion();

            getProductType();

            getAcquisitionMode();

            // Stripmap vectors are ~100 dB too low with no published rescaling. IW vectors rescaled by
            // knoise * Adn^2 (ESA MPC-0243) still give an NESZ 20+ dB below the noise floor observed in IPF 2.34
            // IW SLCs, so removal would remove almost nothing.
            if (version < 2.50) {
                throw new OperatorException("Noise vectors of products before IPF 2.50 are not usable for " +
                        "thermal noise removal (ESA MPC-0243)");
            }

            getThermalNoiseCorrectionFlag();

            setSelectedPolarisations();

            if (version < 2.9 || !isTOPS) { // SM SLC/GRD do not have noise azimuth vectors
                noise = getThermalNoiseVectors(origMetadataRoot, selectedPolList, numOfSubSwath);
                if (isTOPSARSLC) {
                    setBurstTiming();
                }
                if (applyNoiseRecalibration) {
                    // the retro-calibration constants are published for the TOPS swaths only (IPF 2.9+ noise model)
                    SystemUtils.LOG.warning("Noise retro-calibration is only available for IW/EW products from IPF " +
                            S1NoiseRecalibration.MIN_IPF_VERSION + " on; not applied to this " +
                            (isSM ? "stripmap" : "IPF " + version) + " product");
                }
            } else {
                createNoiseBuilders();
            }

            getSampleType();

            getCalibrationFlag();

            if (absoluteCalibrationPerformed) {
                getCalibrationVectors();
            }

            createTargetProduct();

            updateTargetProductMetadata();

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    /**
     * The noise annotation is given on the original L1 raster; a multilooked or resampled product changes the
     * pixel spacing recorded in the abstracted metadata.
     */
    private void checkRasterNotResampled() {
        final MetadataElement annotationElem = origMetadataRoot.getElement("annotation");
        if (annotationElem == null) {
            return;
        }
        // the reader sets the abstracted spacings to the average over all annotation data sets
        double rangeSum = 0, azimuthSum = 0;
        int n = 0;
        for (MetadataElement elem : annotationElem.getElements()) {
            final MetadataElement productElem = elem.getElement("product");
            final MetadataElement imageAnnotation = productElem == null ? null : productElem.getElement("imageAnnotation");
            final MetadataElement imageInformation = imageAnnotation == null ? null :
                    imageAnnotation.getElement("imageInformation");
            if (imageInformation == null || !imageInformation.containsAttribute("rangePixelSpacing") ||
                    !imageInformation.containsAttribute("azimuthPixelSpacing")) {
                return;
            }
            rangeSum += imageInformation.getAttributeDouble("rangePixelSpacing");
            azimuthSum += imageInformation.getAttributeDouble("azimuthPixelSpacing");
            n++;
        }
        if (n == 0) {
            return;
        }
        if (differs(absRoot.getAttributeDouble(AbstractMetadata.range_spacing, 0), rangeSum / n) ||
                differs(absRoot.getAttributeDouble(AbstractMetadata.azimuth_spacing, 0), azimuthSum / n)) {
            throw new OperatorException("Thermal noise removal must be applied before multilooking or resampling");
        }
    }

    private static boolean differs(final double a, final double b) {
        return a > 0 && b > 0 && Math.abs(a - b) > 1e-3 * b;
    }

    /**
     * Get subset x and y offsets from abstract metadata.
     */
    private void getSubsetOffset() {
        subsetOffsetX = absRoot.getAttributeInt(AbstractMetadata.subset_offset_x);
        subsetOffsetY = absRoot.getAttributeInt(AbstractMetadata.subset_offset_y);
    }

    /**
     * Get product type from abstracted metadata.
     */
    private void getProductType() {
        productType = absRoot.getAttributeString(AbstractMetadata.PRODUCT_TYPE);
        String mode = absRoot.getAttributeString(AbstractMetadata.ACQUISITION_MODE);

        isTOPSARSLC = productType.contains("SLC") && (mode.contains("IW") || mode.contains("EW"));

        isTOPS = mode.contains("IW") || mode.contains("EW");
        isGRD = productType.contains("GRD");
        isSM = mode.contains("SM");
    }

    /**
     * Get acquisition mode from abstracted metadata.
     */
    private void getAcquisitionMode() {

        final String acquisitionMode = absRoot.getAttributeString(AbstractMetadata.ACQUISITION_MODE);

        if (productType.equals("SLC")) {
            if (acquisitionMode.equals("IW")) {
                numOfSubSwath = 3;
            } else if (acquisitionMode.equals("EW")) {
                numOfSubSwath = 5;
            }
        }
    }

    /**
     * Get thermal noise correction flag from the original product metadata.
     */
    private void getThermalNoiseCorrectionFlag() {

        final MetadataElement annotationElem = origMetadataRoot.getElement("annotation");
        final MetadataElement[] annotationDataSetListElem = annotationElem.getElements();
        final MetadataElement productElem = annotationDataSetListElem[0].getElement("product");
        final MetadataElement imageAnnotationElem = productElem.getElement("imageAnnotation");
        final MetadataElement processingInformationElem = imageAnnotationElem.getElement("processingInformation");

        Boolean thermalNoiseCorrectionPerformed = Boolean.parseBoolean(
                processingInformationElem.getAttribute("thermalNoiseCorrectionPerformed").getData().getElemString());

        if (removeThermalNoise && thermalNoiseCorrectionPerformed) {
            throw new OperatorException("Thermal noise correction has already been performed for the product");
        }

        if (reIntroduceThermalNoise && !thermalNoiseCorrectionPerformed) {
            throw new OperatorException("Thermal noise correction has never been performed for the product");
        }
    }

    /**
     * Get thermal noise vectors from the original product metadata.
     */
    public static ThermalNoiseInfo[] getThermalNoiseVectors(final MetadataElement origMetadataRoot,
                                                            final List<String> selectedPolList,
                                                            final int numOfSubSwath) throws IOException {

        final ThermalNoiseInfo[] noise = new ThermalNoiseInfo[numOfSubSwath * selectedPolList.size()];
        if(origMetadataRoot == null) {
            throw new IOException("Unable to find original product metadata");
        }
        final MetadataElement noiseElem = origMetadataRoot.getElement("noise");
        if(noiseElem == null) {
            throw new IOException("Unable to find noise element in original product metadata");
        }
        final MetadataElement[] noiseDataSetListElem = noiseElem.getElements();

        int dataSetIndex = 0;
        for (MetadataElement dataSetListElem : noiseDataSetListElem) {

            final MetadataElement noiElem = dataSetListElem.getElement("noise");
            final MetadataElement adsHeaderElem = noiElem.getElement("adsHeader");
            // the ADS header may be sparse; fall back to the data set name (s1a-iw1-slc-vh-...)
            final String upperName = dataSetListElem.getName().toUpperCase();
            final String pol = adsHeaderElem.getAttributeString("polarisation", getBandPol(upperName));
            if (!selectedPolList.contains(pol)) {
                continue;
            }

            MetadataElement noiseVectorListElem = noiElem.getElement("noiseVectorList");
            // Called by S1CalibrationTPGAction
            if (noiseVectorListElem == null) {
                noiseVectorListElem = noiElem.getElement("noiseRangeVectorList");
            }
            final String subSwath = adsHeaderElem.getAttributeString("swath", getBandSwath(upperName));
            if (dataSetIndex >= noise.length) {
                throw new IOException("More noise data sets than expected for " + selectedPolList);
            }

            final Sentinel1Utils.NoiseVector[] noiseVectors = Sentinel1Utils.getNoiseVector(noiseVectorListElem);
            noise[dataSetIndex] = new ThermalNoiseInfo(pol, subSwath,
                    Sentinel1Utils.getTime(adsHeaderElem, "startTime").getMJD(),
                    Sentinel1Utils.getTime(adsHeaderElem, "stopTime").getMJD(),
                    Sentinel1Calibrator.getNumOfLines(origMetadataRoot, pol, subSwath),
                    noiseVectors.length, noiseVectors);
            noise[dataSetIndex].dataSetName = dataSetListElem.getName();

            dataSetIndex++;
        }

        // a TOPSAR-Split product keeps fewer data sets than swaths x polarisations: no trailing null entries
        return Arrays.copyOf(noise, dataSetIndex);
    }

    /**
     * TOPS SLC lines are not uniformly spaced in time (the bursts overlap): map each line to its burst's
     * azimuthTime. The burst list, linesPerBurst and azimuthTimeInterval are not changed by TOPSAR-Split, unlike
     * numberOfLines, so the mapping also holds for a Split product.
     */
    private void setBurstTiming() {
        for (ThermalNoiseInfo info : noise) {
            final MetadataElement annotation = info.dataSetName == null ? null : getAnnotation(info.dataSetName);
            final MetadataElement productElem = annotation == null ? null : annotation.getElement("product");
            final MetadataElement swathTiming = productElem == null ? null : productElem.getElement("swathTiming");
            final MetadataElement burstList = swathTiming == null ? null : swathTiming.getElement("burstList");
            final MetadataElement imageAnnotation = productElem == null ? null : productElem.getElement("imageAnnotation");
            final MetadataElement imageInformation = imageAnnotation == null ? null :
                    imageAnnotation.getElement("imageInformation");
            if (burstList == null || burstList.getNumElements() == 0 || imageInformation == null ||
                    !imageInformation.containsAttribute("azimuthTimeInterval")) {
                continue;
            }
            final int linesPerBurst = swathTiming.getAttributeInt("linesPerBurst", 0);
            if (linesPerBurst <= 0) {
                continue;
            }
            final MetadataElement[] bursts = burstList.getElements();
            final double[] burstTimes = new double[bursts.length];
            for (int i = 0; i < bursts.length; i++) {
                burstTimes[i] = Sentinel1Utils.getTime(bursts[i], "azimuthTime").getMJD();
            }
            final double dt = imageInformation.getAttributeDouble("azimuthTimeInterval") / Constants.secondsInDay;
            info.setBurstTiming(burstTimes, linesPerBurst, dt,
                    firstBurstLine(info.noiseVectorList, burstTimes, linesPerBurst, dt, info.firstLineTime));
        }
    }

    private void getSampleType() {
        final String sampleType = absRoot.getAttributeString(AbstractMetadata.SAMPLE_TYPE);
        if (sampleType.equals("COMPLEX")) {
            isComplex = true;
        }
    }

    /**
     * Get absolute calibration flag from the abstracted metadata.
     */
    private void getCalibrationFlag() {
        absoluteCalibrationPerformed =
                absRoot.getAttribute(AbstractMetadata.abs_calibration_flag).getData().getElemBoolean();

        if (absoluteCalibrationPerformed) {
            if (isComplex) {
                // Currently the calibrated complex product can only be sigma0, this should be changed later if
                // complex beta0 and gamma0 are available
                inputSigmaBand = true;
            } else {
                final String[] sourceBandNames = sourceProduct.getBandNames();
                for (String bandName : sourceBandNames) {
                    if (bandName.contains("Sigma0")) {
                        inputSigmaBand = true;
                    } else if (bandName.contains("Gamma0")) {
                        inputGammaBand = true;
                    } else if (bandName.contains("Beta0")) {
                        inputBetaBand = true;
                    } else if (bandName.contains("DN")) {
                        inputDNBand = true;
                    }
                }

                if (!inputSigmaBand && !inputGammaBand && !inputBetaBand && !inputDNBand) {
                    throw new OperatorException("For calibrated product, Sigma0 or Gamma0 or Beta0 or DN band is expected");
                }
            }
        }
    }

    /**
     * Get calibration vectors from the original product metadata.
     */
    private void getCalibrationVectors() throws IOException {

        calibration = Sentinel1Calibrator.getCalibrationVectors(
                sourceProduct,
                selectedPolList,
                inputSigmaBand,
                inputBetaBand,
                inputGammaBand,
                inputDNBand);
    }

    /**
     * Set user selected polarisations.
     */
    private void setSelectedPolarisations() {

        String[] selectedPols = selectedPolarisations;
        if (selectedPols == null || selectedPols.length == 0) {
            selectedPols = Sentinel1Utils.getProductPolarizations(absRoot);
        }
        selectedPolList = Arrays.asList(selectedPols);
    }

    /**
     * Create a target product for output.
     */
    private void createTargetProduct() {

        targetProduct = new Product(sourceProduct.getName() + PRODUCT_SUFFIX,
                sourceProduct.getProductType(),
                sourceProduct.getSceneRasterWidth(),
                sourceProduct.getSceneRasterHeight());

        addSelectedBands();
        if (targetBandNameToSourceBandName.isEmpty()) {
            throw new OperatorException("No bands found for the selected polarisations " + selectedPolList);
        }

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);
    }

    /**
     * Add user selected bands to target product.
     */
    private void addSelectedBands() {

        final Band[] sourceBands = sourceProduct.getBands();
        for (int i = 0; i < sourceBands.length; i++) {

            final Band srcBand = sourceBands[i];
            if (srcBand instanceof VirtualBand) {
                continue;
            }

            final String unit = srcBand.getUnit();
            if (unit == null) {
                throw new OperatorException("band " + srcBand.getName() + " requires a unit");
            }

            // dB bands (intensity_db, amplitude_db) are not linear power and are not corrected
            if (unit.contains(Unit.DB) ||
                    (!unit.contains(Unit.REAL) && !unit.contains(Unit.AMPLITUDE) && !unit.contains(Unit.INTENSITY))) {
                continue;
            }

            String[] srcBandNames;
            if (unit.contains(Unit.REAL)) { // SLC

                if (i + 1 >= sourceBands.length) {
                    throw new OperatorException("Real and imaginary bands are not in pairs");
                }

                final String nextUnit = sourceBands[i + 1].getUnit();
                if (nextUnit == null || !nextUnit.contains(Unit.IMAGINARY)) {
                    throw new OperatorException("Real and imaginary bands are not in pairs");
                }

                srcBandNames = new String[2];
                srcBandNames[0] = srcBand.getName();
                srcBandNames[1] = sourceBands[i + 1].getName();
                ++i;

            } else { // GRD

                srcBandNames = new String[1];
                srcBandNames[0] = srcBand.getName();
            }

            final String pol = srcBandNames[0].substring(srcBandNames[0].lastIndexOf("_") + 1);
            if (!selectedPolList.contains(pol)) {
                continue;
            }

            final String targetBandName = createTargetBandName(srcBandNames[0]);
            if (targetProduct.getBand(targetBandName) == null) {

                targetBandNameToSourceBandName.put(targetBandName, srcBandNames);

                final Band targetBand = new Band(
                        targetBandName,
                        ProductData.TYPE_FLOAT32,
                        srcBand.getRasterWidth(),
                        srcBand.getRasterHeight());

                targetBand.setUnit(Unit.INTENSITY);
                targetBand.setDescription(srcBand.getDescription());
                // clipped pixels must not be taken for no-data (clip floor 0 with no-data 0, ESA Eq-2)
                final boolean floorIsNoData = clipNegativeValues && clipFloorValue == srcBand.getNoDataValue();
                targetBand.setNoDataValue(floorIsNoData ? Double.NaN : srcBand.getNoDataValue());
                targetBand.setNoDataValueUsed(true);
                targetProduct.addBand(targetBand);
            }
        }

        // add target noise band
        if (outputNoise) {
            for (String targetBandName : targetBandNameToSourceBandName.keySet()) {
                final Band targetBand = targetProduct.getBand(targetBandName);
                final String targetNoiseBandName = createTargetNoiseBandName(targetBandName);
                if (targetProduct.getBand(targetNoiseBandName) == null) {

                    targetNoiseBandNameToImageBandName.put(targetNoiseBandName, targetBandName);

                    final Band targetNoiseBand = new Band(
                            targetNoiseBandName,
                            ProductData.TYPE_FLOAT32,
                            targetBand.getRasterWidth(),
                            targetBand.getRasterHeight());

                    targetNoiseBand.setUnit(Unit.DB);
                    targetNoiseBand.setDescription(targetBand.getDescription());
                    // any finite value (0 included) is a valid dB level; unknown noise is NaN
                    targetNoiseBand.setNoDataValue(Double.NaN);
                    targetNoiseBand.setNoDataValueUsed(true);
                    targetProduct.addBand(targetNoiseBand);
                }
            }
        }
    }

    /**
     * Create target band name for given source bane name.
     *
     * @param sourceBandName Source band name string.
     * @return Target band name string.
     */
    private String createTargetBandName(final String sourceBandName) {

        final String pol = sourceBandName.substring(sourceBandName.indexOf('_'));

        if (absoluteCalibrationPerformed) {
            if (isComplex) {
                return "Sigma0" + pol;
            } else {
                return sourceBandName;
            }
        }

        return "Intensity" + pol;
    }

    /**
     * Create target noise band name for given target bane name.
     *
     * @param targetBandName Target band name string.
     * @return Target noise band name string.
     */
    private String createTargetNoiseBandName(final String targetBandName) {

        if (targetBandName.contains("Intensity")) {
            return targetBandName.replace("Intensity", NOISE_EQUIVALENT_POWER);
        } else if (targetBandName.contains("Sigma0")) {
            return targetBandName.replace("Sigma0", NOISE_EQUIVALENT_SIGMA_ZERO);
        } else if (targetBandName.contains("Beta0")) {
            return targetBandName.replace("Beta0", NOISE_EQUIVALENT_BETA_ZERO);
        } else if (targetBandName.contains("Gamma0")) {
            return targetBandName.replace("Gamma0", NOISE_EQUIVALENT_GAMMA_ZERO);
        } else {
            throw new OperatorException("Invalid target band name: " + targetBandName);
        }
    }

    private boolean isNoiseBand(final String targetBandName) {
        return targetBandName.contains(NOISE_EQUIVALENT_POWER) || targetBandName.contains(NOISE_EQUIVALENT_SIGMA_ZERO) ||
                targetBandName.contains(NOISE_EQUIVALENT_BETA_ZERO) || targetBandName.contains(NOISE_EQUIVALENT_GAMMA_ZERO);
    }

    /**
     * Update target product metadata.
     */
    private void updateTargetProductMetadata() {

        final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(targetProduct);
        final String[] targetBandNames = targetProduct.getBandNames();
        Sentinel1Utils.updateBandNames(abs, selectedPolList, targetBandNames);

        final MetadataElement origMetadataRoot = AbstractMetadata.getOriginalProductMetadata(targetProduct);
        final MetadataElement annotationElem = origMetadataRoot.getElement("annotation");
        final MetadataElement[] annotationDataSetListElem = annotationElem.getElements();
        for (MetadataElement elem : annotationDataSetListElem) {
            final MetadataElement productElem = elem.getElement("product");
            final MetadataElement imageAnnotationElem = productElem.getElement("imageAnnotation");
            final MetadataElement processingInformationElem = imageAnnotationElem.getElement("processingInformation");
            if (removeThermalNoise) {
                processingInformationElem.getAttribute("thermalNoiseCorrectionPerformed").getData().setElems("true");
            }

            if (reIntroduceThermalNoise) {
                processingInformationElem.getAttribute("thermalNoiseCorrectionPerformed").getData().setElems("false");
            }
        }
    }

    private double[][] populateNoiseAzimuthBlock(
            final int x0, final int y0, final int w, final int h, final String targetBandName) {

        if (version >= 2.9 && !isSM) {
            final String pol = getBandPol(targetBandName);
            if (isGRD) {
                final S1NoiseLutBuilder builder = noiseBuilders.get(pol);
                return builder == null ? null : builder.buildGRD(x0, y0, w, h);
            } else if (isTOPSARSLC) {
                final String key = getBandSwath(targetBandName) + '_' + pol;
                return noiseBuilders.get(key).buildTopsSLC(x0, y0, w, h, linesPerBurstMap.get(key),
                        burstTimesMap.get(key), firstBurstLineMap.get(key));
            }
        }
        return null;
    }

    /**
     * Parse the IPF 2.9+ TOPS noise annotation once: one builder per polarisation (GRD) or per swath and
     * polarisation (SLC, key "IW1_VV").
     */
    private void createNoiseBuilders() {
        final MetadataElement noiseElem = origMetadataRoot.getElement("noise");
        for (MetadataElement dataSetListElem : noiseElem.getElements()) {
            final MetadataElement noiElem = dataSetListElem.getElement("noise");
            final MetadataElement adsHeaderElem = noiElem.getElement("adsHeader");
            final String imageName = dataSetListElem.getName();
            // the ADS header may be sparse; fall back to the data set name (s1a-iw1-slc-vh-...)
            final String upperName = imageName.toUpperCase();
            final String pol = adsHeaderElem.getAttributeString("polarisation", getBandPol(upperName));
            if (!selectedPolList.contains(pol)) {
                continue;
            }
            final MetadataElement annotation = getAnnotation(imageName);
            if (annotation == null) {
                throw new OperatorException("No annotation found for noise data set " + imageName);
            }
            final MetadataElement imageInformation = annotation.getElement("product")
                    .getElement("imageAnnotation").getElement("imageInformation");
            final double t0 = Sentinel1Utils.getTime(imageInformation, "productFirstLineUtcTime").getMJD();
            final double dt = imageInformation.getAttributeDouble("azimuthTimeInterval") / Constants.secondsInDay;

            final MetadataElement noiseAzimuthVectorListElem = noiElem.getElement("noiseAzimuthVectorList");
            final Sentinel1Utils.NoiseAzimuthVector[] azimuthVectors =
                    Sentinel1Utils.getAzimuthNoiseVector(noiseAzimuthVectorListElem);
            final Sentinel1Utils.NoiseVector[] rangeVectors =
                    Sentinel1Utils.getNoiseVector(noiElem.getElement("noiseRangeVectorList"));

            if (isGRD) {
                final MetadataElement firstVector = noiseAzimuthVectorListElem.getNumElements() == 0 ?
                        noiseAzimuthVectorListElem : noiseAzimuthVectorListElem.getElementAt(0);
                if (firstVector.getAttributeString("slice", null) != null) {
                    throw new OperatorException("Noise removal should be applied prior to slice assembly");
                }
                noiseBuilders.put(pol, new S1NoiseLutBuilder(rangeVectors, azimuthVectors, t0, dt,
                        getSwathStartEndTimes(annotation, t0, dt), recalibrationFactors(pol, azimuthVectors, null)));
            } else {
                final MetadataElement firstAzimuthVector = noiseAzimuthVectorListElem.getNumElements() == 0 ?
                        noiseAzimuthVectorListElem : noiseAzimuthVectorListElem.getElementAt(0);
                if (firstAzimuthVector.getAttributeString("slice", null) != null) {
                    throw new OperatorException("Noise removal should be applied prior to slice assembly");
                }
                if (azimuthVectors.length == 0) {
                    throw new OperatorException("No noise azimuth vector annotated for noise data set " + imageName);
                }
                final String swath = adsHeaderElem.getAttributeString("swath", getBandSwath(upperName));
                final String key = swath + '_' + pol;
                final MetadataElement swathTiming = annotation.getElement("product").getElement("swathTiming");
                final int linesPerBurst = swathTiming.getAttributeInt("linesPerBurst");
                final MetadataElement[] bursts = swathTiming.getElement("burstList").getElements();
                final double[] burstTimes = new double[bursts.length];
                for (int i = 0; i < bursts.length; i++) {
                    burstTimes[i] = Sentinel1Utils.getTime(bursts[i], "azimuthTime").getMJD();
                }
                noiseBuilders.put(key, new S1NoiseLutBuilder(rangeVectors, azimuthVectors, t0, dt,
                        new HashMap<>(), recalibrationFactors(pol, azimuthVectors, swath)));
                linesPerBurstMap.put(key, linesPerBurst);
                burstTimesMap.put(key, burstTimes);
                // image line of the first burst in the (possibly TOPSAR-Split trimmed) burst list
                final double swathStartTime = adsHeaderElem.containsAttribute("startTime") ?
                        Sentinel1Utils.getTime(adsHeaderElem, "startTime").getMJD() : Double.NaN;
                firstBurstLineMap.put(key, firstBurstLine(rangeVectors, burstTimes, linesPerBurst, dt, swathStartTime));
            }
        }
    }

    /** Per-swath retro-calibration factors (Recchia et al. 2025), or none when not requested or not valid. */
    private Map<String, Double> recalibrationFactors(final String pol,
                                                     final Sentinel1Utils.NoiseAzimuthVector[] azimuthVectors,
                                                     final String dataSetSwath) {
        final Map<String, Double> factors = new HashMap<>();
        if (!applyNoiseRecalibration) {
            return factors;
        }
        if (version < S1NoiseRecalibration.MIN_IPF_VERSION) {
            SystemUtils.LOG.warning("Noise retro-calibration is valid from IPF 3.1.0 on; not applied to IPF " + version);
            return factors;
        }
        final String mission = absRoot.getAttributeString(AbstractMetadata.MISSION, "");   // SENTINEL-1A
        if (!mission.startsWith("SENTINEL-1") || mission.length() != "SENTINEL-1A".length()) {
            SystemUtils.LOG.warning("Noise retro-calibration: unknown platform '" + mission + "'; not applied");
            return factors;
        }
        final String platform = "S1" + mission.charAt(mission.length() - 1);
        if (dataSetSwath != null) {       // SLC: every block belongs to the data set's swath
            factors.put(S1NoiseLutBuilder.ANY_SWATH, recalibrationFactor(platform, dataSetSwath, pol));
        } else {
            for (Sentinel1Utils.NoiseAzimuthVector az : azimuthVectors) {
                if (az.swath != null && !factors.containsKey(az.swath)) {
                    factors.put(az.swath, recalibrationFactor(platform, az.swath, pol));
                }
            }
        }
        if (factors.values().stream().anyMatch(f -> f != 1.0)) {
            // the constants apply to products generated before the AUX_CAL update that introduced them; the AUX_CAL
            // used is not identifiable from the annotation, so a double application cannot be detected here
            SystemUtils.LOG.warning("Noise retro-calibration (Recchia et al. 2025) applied to " + platform + " " + pol +
                    " IPF " + version + ". If this product was generated with the AUX_CAL that already carries the " +
                    "updated noise constants, the correction is applied twice: disable Apply Noise Retro-Calibration " +
                    "for such products (this cannot be checked automatically)");
        }
        return factors;
    }

    private static double recalibrationFactor(final String platform, final String swath, final String pol) {
        final double factor = S1NoiseRecalibration.linearFactor(platform, swath, pol.charAt(1));
        if (factor == 1.0) {
            SystemUtils.LOG.warning("Noise retro-calibration: no published constant for " + platform + " " + swath +
                    " Rx " + pol.charAt(1) + "; not applied");
        }
        return factor;
    }

    /**
     * Image line (original swath coordinates) of the first burst in the product's burst list. A burst list that
     * starts at the swath start (noise ADS startTime, which TOPSAR-Split does not change) is untrimmed and starts at
     * line 0 by construction. Only for a list trimmed by TOPSAR-Split is the line estimated, from the annotated line
     * of the range vector nearest in time to the first burst (which before IPF 3.71 may be shifted).
     *
     * @param swathStartTime noise ADS startTime (MJD), NaN when not annotated
     */
    static int firstBurstLine(final Sentinel1Utils.NoiseVector[] vectors, final double[] burstTimes,
                              final int linesPerBurst, final double lineTimeInterval, final double swathStartTime) {
        if (burstTimes.length == 0 || vectors.length == 0 || linesPerBurst <= 0) {
            return 0;
        }
        final double firstBurstTime = burstTimes[0];
        if (!Double.isNaN(swathStartTime) &&
                Math.abs(firstBurstTime - swathStartTime) < 0.5 * linesPerBurst * lineTimeInterval) {
            return 0;
        }
        Sentinel1Utils.NoiseVector nearest = vectors[0];
        for (Sentinel1Utils.NoiseVector v : vectors) {
            if (Math.abs(v.timeMJD - firstBurstTime) < Math.abs(nearest.timeMJD - firstBurstTime)) {
                nearest = v;
            }
        }
        return Math.max(0, (int) Math.round(nearest.line / (double) linesPerBurst) * linesPerBurst);
    }

    private MetadataElement getAnnotation(final String imageName) {
        for (MetadataElement elem : origMetadataRoot.getElement("annotation").getElements()) {
            if (elem.getName().equals(imageName)) {
                return elem;
            }
        }
        return null;
    }

    /** Per swath, the {start, end} time of the swath in the merged GRD image (swathMerging bounds). */
    private static Map<String, double[]> getSwathStartEndTimes(final MetadataElement annotation,
                                                               final double t0, final double dt) {
        final Map<String, double[]> times = new HashMap<>();
        final MetadataElement swathMerging = annotation.getElement("product").getElement("swathMerging");
        if (swathMerging == null) {
            return times;
        }
        for (MetadataElement swathMerge : swathMerging.getElement("swathMergeList").getElements()) {
            final MetadataElement[] bounds = swathMerge.getElement("swathBoundsList").getElements();
            final int startLine = bounds[0].getAttributeInt("firstAzimuthLine");
            final int endLine = bounds[bounds.length - 1].getAttributeInt("lastAzimuthLine");
            times.put(swathMerge.getAttributeString("swath"), new double[]{t0 + startLine * dt, t0 + endLine * dt});
        }
        return times;
    }

    /**
     * Called by the framework in order to compute a tile for the given target band.
     * <p>The default implementation throws a runtime exception with the message "not implemented".</p>
     *
     * @param targetBand The target band.
     * @param targetTile The current tile associated with the target band to be computed.
     * @param pm         A progress monitor which should be used to determine computation cancelation requests.
     * @throws OperatorException If an error occurs during computation of the target raster.
     */
    @Override
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) throws OperatorException {

        // Here the noise is output separately from the image. The reason that computeTile is used instead of
        // computeTileStack is because we need to handle S-1 SLC product in which the image bands from different
        // sub-swaths have different size and computeTileStack cannot handle bands in different sizes properly.
        final String targetBandName = targetBand.getName();
        if (!isNoiseBand(targetBandName)) {
            computeTileImage(targetBandName, targetTile, pm);
        } else { // noise band
            computeTileNoise(targetBandName, targetTile, pm);
        }
    }

    private void computeTileImage(String targetBandName, Tile targetTile, ProgressMonitor pm) throws OperatorException {

        final Rectangle targetTileRectangle = targetTile.getRectangle();
        final int x0 = targetTileRectangle.x;
        final int y0 = targetTileRectangle.y;
        final int w = targetTileRectangle.width;
        final int h = targetTileRectangle.height;
        final int sx0 = subsetOffsetX + x0; // tile start x coordinate in original image
        final int sy0 = subsetOffsetY + y0; // tile start y coordinate in original image
        //System.out.println("x0 = " + x0 + ", y0 = " + y0 + ", w = " + w + ", h = " + h + ", target band = " + targetBandName);

        try {
            double[][] noiseBlock = null;
            if (version >= 2.9 && !isSM) {
                noiseBlock = populateNoiseAzimuthBlock(sx0, sy0, w, h, targetBandName);
            }

            Tile sourceRaster1 = null;
            ProductData srcData1 = null;
            ProductData srcData2 = null;
            Band sourceBand1 = null;

            final String[] srcBandNames = targetBandNameToSourceBandName.get(targetBandName);
            if (srcBandNames.length == 1) {
                sourceBand1 = sourceProduct.getBand(srcBandNames[0]);
                sourceRaster1 = getSourceTile(sourceBand1, targetTileRectangle);
                srcData1 = sourceRaster1.getDataBuffer();
            } else {
                sourceBand1 = sourceProduct.getBand(srcBandNames[0]);
                final Band sourceBand2 = sourceProduct.getBand(srcBandNames[1]);
                sourceRaster1 = getSourceTile(sourceBand1, targetTileRectangle);
                final Tile sourceRaster2 = getSourceTile(sourceBand2, targetTileRectangle);
                srcData1 = sourceRaster1.getDataBuffer();
                srcData2 = sourceRaster2.getDataBuffer();
            }

            final double srcNoDataValue = sourceBand1.getNoDataValue();
            final double tgtNoDataValue = targetProduct.getBand(targetBandName).getNoDataValue();
            final Unit.UnitType bandUnit = Unit.getUnitType(sourceBand1);
            final ProductData tgtData = targetTile.getDataBuffer();
            final TileIndex srcIndex = new TileIndex(sourceRaster1);
            final TileIndex tgtIndex = new TileIndex(targetTile);
            final int maxY = y0 + h;
            final int maxX = x0 + w;

            final boolean complexData = bandUnit == Unit.UnitType.REAL || bandUnit == Unit.UnitType.IMAGINARY;

            Sentinel1Calibrator.CalibrationInfo calInfo = null;
            Sentinel1Calibrator.CALTYPE calType = null;
            if (absoluteCalibrationPerformed) {
                calInfo = getCalInfo(targetBandName);
                calType = Sentinel1Calibrator.getCalibrationType(targetBandName);
            }
            // looked up once per tile, not per row
            final ThermalNoiseInfo noiseInfo = (version < 2.9 || isSM) ? getNoiseInfo(targetBandName) : null;

            double dn, dn2, i, q;
            int srcIdx, tgtIdx;
            final double[] lut = new double[w]; // reused across rows in this tile
            for (int y = y0; y < maxY; ++y) {
                srcIndex.calculateStride(y);
                tgtIndex.calculateStride(y);
                final int sy = y + subsetOffsetY;

                if (absoluteCalibrationPerformed) {
                    final int calVecIdx = calInfo.getCalibrationVectorIndex(sy);
                    final Sentinel1Utils.CalibrationVector vec0 = calInfo.getCalibrationVector(calVecIdx);
                    final Sentinel1Utils.CalibrationVector vec1 = calInfo.getCalibrationVector(calVecIdx + 1);
                    final float[] vec0LUT = Sentinel1Calibrator.getVector(calType, vec0);
                    final float[] vec1LUT = Sentinel1Calibrator.getVector(calType, vec1);
                    final Sentinel1Utils.CalibrationVector calVec = calInfo.calibrationVectorList[calVecIdx];
                    final int pixelIdx0 = calVec.getPixelIndex(sx0);

                    if (version < 2.9 || isSM) {
                        computeTileScaledNoiseLUT(sy, sx0, w, noiseInfo, calInfo, vec0.timeMJD, vec1.timeMJD,
                                vec0LUT, vec1LUT, vec0.pixels, pixelIdx0, lut);
                    } else {
                        computeTileScaledNoiseLUT(sy, sx0, sy0, w, noiseBlock, calInfo, vec0.timeMJD, vec1.timeMJD,
                                vec0LUT, vec1LUT, vec0.pixels, pixelIdx0, lut);
                    }

                } else {
                    if (version < 2.9 || isSM) {
                        computeTileNoiseLUT(sy, sx0, w, noiseInfo, lut);
                    } else {
                        computeTileNoiseLUT(sy - sy0, sx0, w, noiseBlock, lut);
                    }
                    if (reIntroduceThermalNoise) {      // the calibrated path negates in computeTileScaledNoiseLUT
                        for (int k = 0; k < w; k++) {
                            lut[k] = -lut[k];
                        }
                    }
                }

                for (int x = x0; x < maxX; ++x) {
                    final int xx = x - x0;
                    srcIdx = srcIndex.getIndex(x);
                    tgtIdx = tgtIndex.getIndex(x);
                    if (bandUnit == Unit.UnitType.AMPLITUDE) {
                        dn = srcData1.getElemDoubleAt(srcIdx);
                        dn2 = dn * dn;
                    } else if (complexData) {
                        i = srcData1.getElemDoubleAt(srcIdx);
                        q = srcData2.getElemDoubleAt(srcIdx);
                        dn2 = i * i + q * q;
                    } else if (bandUnit == Unit.UnitType.INTENSITY) {
                        dn2 = srcData1.getElemDoubleAt(srcIdx);
                    } else {
                        throw new OperatorException("Unhandled unit");
                    }

                    if(Double.isNaN(dn2) || dn2 == srcNoDataValue) {
                        tgtData.setElemDoubleAt(tgtIdx, tgtNoDataValue);
                        continue;
                    }

                    if (!removeThermalNoise && !reIntroduceThermalNoise) {   // only the noise band is wanted
                        tgtData.setElemDoubleAt(tgtIdx, dn2);
                        continue;
                    }

                    // zero noise = missing denoising data (MPC-0392 Eq-1); clipping per Eq-2, kept negatives for Eq-3
                    tgtData.setElemDoubleAt(tgtIdx,
                            NoiseSubtraction.apply(dn2, lut[xx], clipNegativeValues, clipFloorValue));
                }
            }
        } catch (Throwable e) {
            throw new OperatorException(e.getMessage() != null ? e.getMessage() : e.toString(), e);
        }
    }

    private void computeTileNoise(String targetNoiseBandName, Tile targetTile, ProgressMonitor pm) throws OperatorException {

        final Rectangle targetTileRectangle = targetTile.getRectangle();
        final int x0 = targetTileRectangle.x;
        final int y0 = targetTileRectangle.y;
        final int w = targetTileRectangle.width;
        final int h = targetTileRectangle.height;
        final int sx0 = subsetOffsetX + x0; // tile start x coordinate in original image
        final int sy0 = subsetOffsetY + y0; // tile start y coordinate in original image
        //System.out.println("x0 = " + x0 + ", y0 = " + y0 + ", w = " + w + ", h = " + h + ", target band = " + targetBandName);

        try {
            final String targetBandName = targetNoiseBandNameToImageBandName.get(targetNoiseBandName);
            double[][] noiseBlock = null;
            if (version >= 2.9 && !isSM) {
                noiseBlock = populateNoiseAzimuthBlock(sx0, sy0, w, h, targetBandName);
            }

            final Band noiseBand = targetProduct.getBand(targetNoiseBandName);
            final double noDataValue = noiseBand.getNoDataValue();

            final ProductData tgtData = targetTile.getDataBuffer();
            final TileIndex tgtIndex = new TileIndex(targetTile);
            final int maxY = y0 + h;
            final int maxX = x0 + w;

            Sentinel1Calibrator.CalibrationInfo calInfo = null;
            Sentinel1Calibrator.CALTYPE calType = null;
            if (absoluteCalibrationPerformed) {
                calInfo = getCalInfo(targetBandName);
                calType = Sentinel1Calibrator.getCalibrationType(targetBandName);
            }
            // looked up once per tile, not per row
            final ThermalNoiseInfo noiseInfo = (version < 2.9 || isSM) ? getNoiseInfo(targetBandName) : null;

            int tgtIdx;
            for (int y = y0; y < maxY; ++y) {
                tgtIndex.calculateStride(y);
                final int sy = y + subsetOffsetY;

                double[] lut = new double[w];
                if (absoluteCalibrationPerformed) {
                    final int calVecIdx = calInfo.getCalibrationVectorIndex(sy);
                    final Sentinel1Utils.CalibrationVector vec0 = calInfo.getCalibrationVector(calVecIdx);
                    final Sentinel1Utils.CalibrationVector vec1 = calInfo.getCalibrationVector(calVecIdx + 1);
                    final float[] vec0LUT = Sentinel1Calibrator.getVector(calType, vec0);
                    final float[] vec1LUT = Sentinel1Calibrator.getVector(calType, vec1);
                    final Sentinel1Utils.CalibrationVector calVec = calInfo.calibrationVectorList[calVecIdx];
                    final int pixelIdx0 = calVec.getPixelIndex(sx0);

                    if (version < 2.9 || isSM) {
                        computeTileScaledNoiseLUT(sy, sx0, w, noiseInfo, calInfo, vec0.timeMJD, vec1.timeMJD,
                                vec0LUT, vec1LUT, vec0.pixels, pixelIdx0, lut);
                    } else {
                        computeTileScaledNoiseLUT(sy, sx0, sy0, w, noiseBlock, calInfo, vec0.timeMJD, vec1.timeMJD,
                                vec0LUT, vec1LUT, vec0.pixels, pixelIdx0, lut);
                    }

                } else {
                    if (version < 2.9 || isSM) {
                        computeTileNoiseLUT(sy, sx0, w, noiseInfo, lut);
                    } else {
                        computeTileNoiseLUT(sy - sy0, sx0, w, noiseBlock, lut);
                    }
                }

                for (int x = x0; x < maxX; ++x) {
                    final int xx = x - x0;
                    tgtIdx = tgtIndex.getIndex(x);
                    // Zero noise value means missing denoising data (Eq-1, Section 6.1 of MPC-0392 v2.2)
                    // the LUT is negated when re-introducing noise; the band shows the noise level itself
                    final double noiseLevel = Math.abs(lut[xx]);
                    tgtData.setElemDoubleAt(tgtIdx, noiseLevel > 0 && Double.isFinite(noiseLevel) ?
                            10.0 * Math.log10(noiseLevel) : noDataValue);
                }
            }
        } catch (Throwable e) {
            throw new OperatorException(e.getMessage() != null ? e.getMessage() : e.toString(), e);
        }
    }

    /**
     * Get thermal noise information for given target band.
     *
     * @param targetBandName Target band name.
     * @return The ThermalNoiseInfo object.
     */
    private ThermalNoiseInfo getNoiseInfo(final String targetBandName) throws OperatorException {

        for (ThermalNoiseInfo noiseInfo : noise) {
            if (noiseInfo == null) {
                continue;
            }
            if (isTOPSARSLC) {
                if (targetBandName.contains(noiseInfo.polarization) && targetBandName.contains(noiseInfo.subSwath)) {
                    return noiseInfo;
                }
            } else {
                if (targetBandName.contains(noiseInfo.polarization)) {
                    return noiseInfo;
                }
            }
        }
        throw new OperatorException("NoiseInfo not found for "+targetBandName);
    }

    /**
     * Get calibration information for given target band.
     *
     * @param targetBandName Target band name.
     * @return The CalibrationInfo object.
     */
    private Sentinel1Calibrator.CalibrationInfo getCalInfo(final String targetBandName) {

        for (Sentinel1Calibrator.CalibrationInfo cal : calibration) {
            final String pol = cal.polarization;
            final String ss = cal.subSwath;
            if (isTOPSARSLC) {
                if (targetBandName.contains(pol) && targetBandName.contains(ss)) {
                    return cal;
                }
            } else {
                if (targetBandName.contains(pol)) {
                    return cal;
                }
            }
        }
        return null;
    }

    /**
     * Compute scaled noise LUTs for the given range line.
     *
     * @param y         Index of the given range line.
     * @param x0        X coordinate of the upper left corner pixel of the given tile.
     * @param w         Tile width.
     * @param noiseInfo Object of ThermalNoiseInfo class.
     * @param calInfo   Object of CalibrationInfo class.
     * @param lut       The scaled noise LUT.
     */
    private void computeTileScaledNoiseLUT(final int y, final int x0, final int w,
                                           final ThermalNoiseInfo noiseInfo,
                                           final Sentinel1Calibrator.CalibrationInfo calInfo,
                                           final double azT0, final double azT1,
                                           final float[] vec0LUT, final float[] vec1LUT,
                                           final int[] vec0Pixels, final int pixelIdx0,
                                           final double[] lut) {

        final double[] noiseLut = new double[w];
        computeTileNoiseLUT(y, x0, w, noiseInfo, noiseLut);

        final double[] calLut = new double[w];
        computeTileCalibrationLUTs(y, x0, w, calInfo, azT0, azT1, vec0LUT, vec1LUT, vec0Pixels, pixelIdx0, calLut);

        if (removeThermalNoise) {
            for (int i = 0; i < w; i++) {
                lut[i] = noiseLut[i] / (calLut[i]*calLut[i]);
            }
        } else { // reIntroduceThermalNoise
            for (int i = 0; i < w; i++) {
                lut[i] = -noiseLut[i] / (calLut[i]*calLut[i]);
            }
        }
    }

    private void computeTileScaledNoiseLUT(final int y, final int x0, final int y0, final int w,
                                           final double[][] noiseBlock,
                                           final Sentinel1Calibrator.CalibrationInfo calInfo,
                                           final double azT0, final double azT1,
                                           final float[] vec0LUT, final float[] vec1LUT,
                                           final int[] vec0Pixels, final int pixelIdx0,
                                           final double[] lut) {

        final double[] calLut = new double[w];
        computeTileCalibrationLUTs(y, x0, w, calInfo, azT0, azT1,
                vec0LUT, vec1LUT, vec0Pixels, pixelIdx0, calLut);

        final int yy = y - y0;
        if (removeThermalNoise) {
            for (int i = 0; i < w; i++) {
                lut[i] = noiseBlock[yy][i] / (calLut[i]*calLut[i]);
            }
        } else { // reIntroduceThermalNoise
            for (int i = 0; i < w; i++) {
                lut[i] = -noiseBlock[yy][i] / (calLut[i]*calLut[i]);
            }
        }
    }

    /**
     * Compute calibration LUTs for the given range line.
     *
     * @param y       Index of the given range line.
     * @param x0      X coordinate of the upper left corner pixel of the given tile.
     * @param w       Tile width.
     * @param calInfo Object of CalibrationInfo class.
     * @param lut     LUT for calibration.
     */
    private static void computeTileCalibrationLUTs(final int y, final int x0, final int w,
                                                  final Sentinel1Calibrator.CalibrationInfo calInfo,
                                                  final double azT0, final double azT1,
                                                  final float[] vec0LUT, final float[] vec1LUT,
                                                  final int[] vec0Pixels, int pixelIdx0,
                                                  final double[] lut) {
        final double azTime = calInfo.firstLineTime + y * calInfo.lineTimeInterval;
        double muX, muY = (azTime - azT0) / (azT1 - azT0);

        int pixelIdx = pixelIdx0;
        final int maxX = x0 + w;
        for (int x = x0; x < maxX; x++) {
            if (x > vec0Pixels[pixelIdx + 1]) {
                pixelIdx++;
            }

            muX = (double) (x - vec0Pixels[pixelIdx]) / (double) (vec0Pixels[pixelIdx + 1] - vec0Pixels[pixelIdx]);
            lut[x - x0] = Maths.interpolationBiLinear(
                    vec0LUT[pixelIdx], vec0LUT[pixelIdx + 1], vec1LUT[pixelIdx], vec1LUT[pixelIdx + 1], muX, muY);
        }
    }

    /**
     * Compute noise LUTs for the given range line.
     *
     * @param y         Index of the given range line.
     * @param x0        X coordinate of the upper left corner pixel of the given tile.
     * @param w         Tile width.
     * @param noiseInfo Object of ThermalNoiseInfo class.
     * @param lut       The noise LUT.
     */
    private void computeTileNoiseLUT(final int y, final int x0, final int w,
                                     final ThermalNoiseInfo noiseInfo, final double[] lut) {
        try {
            final Sentinel1Utils.NoiseVector[] vectors = noiseInfo.noiseVectorList;
            if (vectors.length == 0) {
                throw new OperatorException("No noise vectors annotated for " + noiseInfo.subSwath + ' ' +
                        noiseInfo.polarization);
            }
            final double azTime = noiseInfo.lineTime(y);
            // a single annotated vector applies to all lines
            final int noiseVecIdx = vectors.length == 1 ? 0 : getNoiseVectorIndex(azTime, noiseInfo);
            final Sentinel1Utils.NoiseVector noiseVector0 = vectors[noiseVecIdx];
            final Sentinel1Utils.NoiseVector noiseVector1 = vectors[Math.min(noiseVecIdx + 1, vectors.length - 1)];

            final double azT0 = noiseVector0.timeMJD;
            final double azT1 = noiseVector1.timeMJD;
            // beyond the first/last vector the nearest one is used (no extrapolation in time)
            final double muY = azT1 == azT0 ? 0.0 : Math.max(0.0, Math.min(1.0, (azTime - azT0) / (azT1 - azT0)));

            int pixelIdx0 = getPixelIndex(x0, noiseVector0);
            int pixelIdx1 = getPixelIndex(x0, noiseVector1);

            final int maxLength0 = noiseVector0.pixels.length - 2;
            final int maxLength1 = noiseVector1.pixels.length - 2;
            final int maxX = x0 + w;
            for (int x = x0; x < maxX; x++) {

                // advance past repeated pixel indices too
                while (pixelIdx0 < maxLength0 && x > noiseVector0.pixels[pixelIdx0 + 1]) {
                    pixelIdx0++;
                }
                final double noise0 = interpolRange(noiseVector0, pixelIdx0, x);

                while (pixelIdx1 < maxLength1 && x > noiseVector1.pixels[pixelIdx1 + 1]) {
                    pixelIdx1++;
                }
                final double noise1 = interpolRange(noiseVector1, pixelIdx1, x);

                lut[x - x0] = Maths.interpolationLinear(noise0, noise1, muY);
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException("computeTileNoiseLUT", e);
        }
    }

    /** Linear in range between vector samples idx and idx+1; a repeated pixel index gives the left value. */
    private double interpolRange(final Sentinel1Utils.NoiseVector vector, final int idx, final int x) {
        if (vector.pixels.length < 2) {
            return vector.noiseLUT[0];
        }
        final int xa = vector.pixels[idx];
        final int xb = vector.pixels[idx + 1];
        if (xa == xb) {     // should never happen
            if (warnedDuplicatePixel.compareAndSet(false, true)) {
                SystemUtils.LOG.warning("noise vector duplicate pixel indices: " + xa + " (further occurrences not logged)");
            }
            return vector.noiseLUT[idx];
        }
        return Maths.interpolationLinear(vector.noiseLUT[idx], vector.noiseLUT[idx + 1],
                (double) (x - xa) / (double) (xb - xa));
    }

    private static void computeTileNoiseLUT(final int yy, final int x0, final int w,
                                            final double[][] noiseBlock, final double[] lut) {
        try {
            final int maxX = x0 + w;
            for (int x = x0; x < maxX; x++) {
                final int xx = x - x0;
                lut[xx] = noiseBlock[yy][xx];
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException("computeTileNoiseLUT", e);
        }
    }

    /**
     * Get index of the noise vector in the list for a given line.
     *
     * @param azTime    Azimuth time.
     * @param noiseInfo Object of ThermalNoiseInfo class.
     * @return The noise vector index.
     */
    private static int getNoiseVectorIndex(final double azTime, final ThermalNoiseInfo noiseInfo) {
        final int count = noiseInfo.noiseVectorList.length;
        for (int i = 1; i < count; i++) {
            if (azTime < noiseInfo.noiseVectorList[i].timeMJD) {
                return i - 1;
            }
        }
        return Math.max(0, count - 2);
    }

    /**
     * Get pixel index in a given noise vector for a given pixel.
     *
     * @param x           Pixel coordinate.
     * @param noiseVector Noise vector.
     * @return The pixel index.
     */
    private static int getPixelIndex(final int x, final Sentinel1Utils.NoiseVector noiseVector) {

        for (int i = 0; i < noiseVector.pixels.length; i++) {
            if (x < noiseVector.pixels[i]) {
                return Math.max(0, i - 1);
            }
        }
        return Math.max(0, noiseVector.pixels.length - 2);
    }

    private void getIPFVersion() {
        version = S1IpfVersion.get(sourceProduct);
    }

    private static String getBandPol(final String bandName) {

        if (bandName.contains("HH")) {
            return "HH";
        } else if (bandName.contains("HV")) {
            return "HV";
        } else if (bandName.contains("VV")) {
            return "VV";
        } else if (bandName.contains("VH")) {
            return "VH";
        }
        return "";
    }

    private static String getBandSwath(final String bandName) {

        if (bandName.contains("IW1")) {
            return "IW1";
        } else if (bandName.contains("IW2")) {
            return "IW2";
        } else if (bandName.contains("IW3")) {
            return "IW3";
        } else if (bandName.contains("EW1")) {
            return "EW1";
        } else if (bandName.contains("EW2")) {
            return "EW2";
        } else if (bandName.contains("EW3")) {
            return "EW3";
        } else if (bandName.contains("EW4")) {
            return "EW4";
        } else if (bandName.contains("EW5")) {
            return "EW5";
        }
        return "";
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

        String dataSetName;
        // TOPS SLC burst timing (null: lines uniformly spaced between firstLineTime and lastLineTime)
        private double[] burstTimes;
        private int linesPerBurst;
        private double azimuthTimeInterval;
        private int firstBurstLine;

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

        void setBurstTiming(final double[] burstTimes, final int linesPerBurst, final double azimuthTimeInterval,
                            final int firstBurstLine) {
            this.burstTimes = burstTimes;
            this.linesPerBurst = linesPerBurst;
            this.azimuthTimeInterval = azimuthTimeInterval;
            this.firstBurstLine = firstBurstLine;
        }

        /** Azimuth time (MJD) of an original-image line. */
        double lineTime(final int y) {
            if (burstTimes == null) {
                return firstLineTime + y * lineTimeInterval;
            }
            final int b = Math.max(0, Math.min(burstTimes.length - 1, Math.floorDiv(y - firstBurstLine, linesPerBurst)));
            return burstTimes[b] + (y - firstBurstLine - b * linesPerBurst) * azimuthTimeInterval;
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
            super(Sentinel1RemoveThermalNoiseOp.class);
        }
    }
}
