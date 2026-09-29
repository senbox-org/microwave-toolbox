/*
 * Copyright (C) 2024 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
import eu.esa.sar.cloud.opendata.DataSpaces;
import eu.esa.sar.sentinel1.gpf.etadcorrectors.ETADUtils;
import eu.esa.sar.sentinel1.gpf.etadcorrectors.Corrector;
import eu.esa.sar.sentinel1.gpf.etadcorrectors.GRDCorrector;
import eu.esa.sar.sentinel1.gpf.etadcorrectors.SMCorrector;
import eu.esa.sar.sentinel1.gpf.etadcorrectors.TOPSCorrector;
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
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.gpf.*;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * The operator performs ETAD correction for S-1 TOPS SLC / Stripmap SLC / GRD products.
 */
@OperatorMetadata(alias = "S1-ETAD-Correction",
        category = "Radar/Sentinel-1 TOPS",
        authors = "Jun Lu, Luis Veci",
        copyright = "Copyright (C) 2023 by SkyWatch Space Applications Inc.",
        version = "1.0",
        description = "ETAD correction of S-1 TOPS/SM/GRD products")
public class S1ETADCorrectionOp extends Operator {

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
            description = "Method for resampling image from the un-corrected grid to the etad-corrected grid.",
            label = "Resampling Type")
    private String resamplingType = ResamplingFactory.BISINC_5_POINT_INTERPOLATION_NAME;

    @Parameter(description = "Resampling Image", defaultValue = "true",
            label = "Resampling Image")
    private boolean resamplingImage = true;

    @Parameter(description = "Output Phase Corrections", defaultValue = "false",
            label = "Output Phase Corrections")
    private boolean outputPhaseCorrections = false;

    @Parameter(description = "Also write the applied range-delay phase as an 'etadPhase' band, so the "
            + "size of the correction can be inspected without reprocessing. Diagnostic only: adds a "
            + "non-complex band, which changes what coregistration and stacking see.",
            defaultValue = "false", label = "Output ETAD phase as a band (diagnostic)")
    private boolean outputETADPhaseBand = false;

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

    @Parameter(description = "Sum Of Azimuth Corrections", defaultValue = "true",
            label = "Sum Of Azimuth Corrections")
    private boolean sumOfAzimuthCorrections = true;

    @Parameter(description = "Sum Of Range Corrections", defaultValue = "true",
            label = "Sum Of Range Corrections")
    private boolean sumOfRangeCorrections = true;

    private String productType = null;
    private String acquisitionMode = null;
    private Corrector etadCorrector;
    private MetadataElement absRoot = null;

    private Resampling selectedResampling = null;
    private ETADUtils etadUtils = null;
    private Product etadProduct = null;


    /**
     * Default constructor. The graph processing framework
     * requires that an operator has a default constructor.
     */
    public S1ETADCorrectionOp() {
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
            validateSourceProduct();

            getSourceProductMetadata();

            getResampling();

            // NOTE: ETAD download + corrector construction stay here because
            // etadCorrector.createTargetProduct() depends on the loaded ETAD
            // product, and GPF requires targetProduct to be assigned by the
            // time initialize() returns (getTargetProduct() does not trigger
            // doExecute()). The UI dialog will block during ETAD download —
            // a limitation we accept until the corrector exposes a way to
            // build a skeleton target product without the ETAD content.
            createETADUtils();
            getETADCorrector();
            updateTargetProductMetadata();

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    @Override
    public void dispose() {
        if (etadUtils != null) {
            etadUtils.dispose();
        }
        if(etadCorrector != null) {
            etadCorrector.dispose();
        }
    }

    private void validateSourceProduct() {

        final InputProductValidator validator = new InputProductValidator(sourceProduct);
        validator.checkIfSARProduct();
        validator.checkIfSentinel1Product();
    }

    private void getSourceProductMetadata() {

        absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);
        productType = absRoot.getAttributeString(AbstractMetadata.PRODUCT_TYPE);
        acquisitionMode = absRoot.getAttributeString(AbstractMetadata.ACQUISITION_MODE);

        if (resamplingImage && noCorrectionLayerSelected()) {
            throw new OperatorException("No correction layer is selected");
        }

        if(!resamplingImage) {
            outputPhaseCorrections = true;
        }

        if (outputPhaseCorrections && !((acquisitionMode.equals("IW") || acquisitionMode.equals("SM")) && productType.equals("SLC"))) {
            throw new OperatorException("Option 2 is for Sentinel-1 IW SLC and SM SLC product only");
        }

        checkCombinedModeSupported(resamplingImage, outputPhaseCorrections, acquisitionMode);
    }

    /**
     * Applying the geometric correction and the range-delay phase in a single pass is implemented in
     * {@code TOPSCorrector} only.
     * <p>
     * {@code SMCorrector} would fail: it dispatches its target product on
     * {@code outputPhaseCorrections} but its tiles on {@code resamplingImage}, and it never bakes
     * phase into pixels. With both flags set the target gets correction bands that the resampling
     * tile path cannot fill, and it dies on a null tile entry when a computed band is pulled.
     * <p>
     * Guarding here rather than fixing {@code SMCorrector} keeps this change small and makes the
     * limitation explicit to the user. It also underwrites the {@code etad_phase_applied}
     * provenance flag: with this guard in place, reaching {@link #writeETADProvenance} with both
     * flags true implies TOPS, which does bake the phase in.
     *
     * @throws OperatorException if the combined mode is requested for a non-IW acquisition
     */
    static void checkCombinedModeSupported(final boolean resamplingImage,
                                           final boolean outputPhaseCorrections,
                                           final String acquisitionMode) {

        if (resamplingImage && outputPhaseCorrections && !"IW".equals(acquisitionMode)) {
            throw new OperatorException("ETAD: applying the geometric correction and the "
                    + "range-delay phase correction in a single pass is implemented for IW (TOPS) "
                    + "only, not " + acquisitionMode + ". Either enable image resampling alone "
                    + "(geometric correction only), or disable it to emit the phase corrections as "
                    + "tie-point grids for the classical InSAR chain.");
        }
    }

    private void createETADUtils() throws Exception {
        if(etadUtils != null) {
            return;
        }

        if(etadFile == null) {
            ETADSearch etadSearch = new ETADSearch();
            DataSpaces.Result[] results = etadSearch.search(sourceProduct);

            if (results.length == 0) {
                throw new OperatorException("ETAD product not found");
            }

            File outputFolder = new File(SystemUtils.getCacheDir(), "etad");
            etadFile = etadSearch.download(selectBestOverlap(sourceProduct, results), outputFolder);
        }

        // disposed of in etadUtils.dispose()
        etadProduct = ProductIO.readProduct(etadFile);

        validateETADProduct(sourceProduct, etadProduct);

        etadUtils = new ETADUtils(etadProduct);
    }

    /**
     * The search window is padded by ±5 s, so whenever the scene starts or ends within 5 s of a
     * slice boundary the ADJACENT slice of the same datatake matches too — and taking
     * {@code results[0]} then downloads an ETAD covering only the couple of seconds of overlap
     * (observed live: 224932_225000 slice selected for a 224958_225025 scene, caught by
     * {@code validateETADProduct}). Pick the candidate with maximum temporal overlap instead.
     */
    static DataSpaces.Result selectBestOverlap(final Product sourceProduct, final DataSpaces.Result[] results) {
        if (results.length == 1) {
            return results[0];
        }
        DataSpaces.Result best = null;
        double bestOverlap = Double.NEGATIVE_INFINITY;
        try {
            final double srcStart = sourceProduct.getStartTime().getMJD() * Constants.secondsInDay;
            final double srcEnd = sourceProduct.getEndTime().getMJD() * Constants.secondsInDay;
            for (final DataSpaces.Result r : results) {
                try {
                    final double s = ProductData.UTC.parse(r.getStartTime().replace("Z", ""),
                            "yyyy-MM-dd'T'HH:mm:ss").getMJD() * Constants.secondsInDay;
                    final double e = ProductData.UTC.parse(r.getEndTime().replace("Z", ""),
                            "yyyy-MM-dd'T'HH:mm:ss").getMJD() * Constants.secondsInDay;
                    final double overlap = Math.min(srcEnd, e) - Math.max(srcStart, s);
                    if (overlap > bestOverlap) {
                        bestOverlap = overlap;
                        best = r;
                    }
                } catch (Exception oneResult) {
                    SystemUtils.LOG.warning("ETAD search: cannot parse ContentDate of '"
                            + r.getName() + "': " + oneResult.getMessage());
                }
            }
        } catch (Exception all) {
            best = null;
        }
        if (best == null) {
            return results[0];
        }
        SystemUtils.LOG.info(String.format(
                "ETAD search: %d candidates; selected '%s' with %.1f s overlap of the scene.",
                results.length, best.getName(), bestOverlap));
        return best;
    }

    private void getResampling() {

        if (resamplingImage) {
            selectedResampling = ResamplingFactory.createResampling(resamplingType);
            if(selectedResampling == null) {
                throw new OperatorException("Resampling method "+ resamplingType + " is invalid");
            }
        }
    }

    private void getETADCorrector() {

        etadCorrector = createETADCorrector();
        etadCorrector.setTroposphericCorrectionRg(troposphericCorrectionRg);
        etadCorrector.setIonosphericCorrectionRg(ionosphericCorrectionRg);
        etadCorrector.setGeodeticCorrectionRg(geodeticCorrectionRg);
        etadCorrector.setDopplerShiftCorrectionRg(dopplerShiftCorrectionRg);
        etadCorrector.setGeodeticCorrectionAz(geodeticCorrectionAz);
        etadCorrector.setBistaticShiftCorrectionAz(bistaticShiftCorrectionAz);
        etadCorrector.setFmMismatchCorrectionAz(fmMismatchCorrectionAz);
        etadCorrector.setSumOfAzimuthCorrections(sumOfAzimuthCorrections);
        etadCorrector.setSumOfRangeCorrections(sumOfRangeCorrections);
        etadCorrector.setResamplingImage(resamplingImage);
        etadCorrector.setOutputPhaseCorrections(outputPhaseCorrections);
        etadCorrector.setOutputETADPhaseBand(outputETADPhaseBand);
        etadCorrector.setEtadUtils(etadUtils);
        etadCorrector.setEtadProduct(etadProduct);
        etadCorrector.initialize();
        targetProduct = etadCorrector.createTargetProduct();
    }

    private boolean noCorrectionLayerSelected() {
        return !troposphericCorrectionRg && !ionosphericCorrectionRg && !geodeticCorrectionRg &&
               !dopplerShiftCorrectionRg && !geodeticCorrectionAz && !bistaticShiftCorrectionAz &&
               !fmMismatchCorrectionAz && !sumOfAzimuthCorrections && !sumOfRangeCorrections;
    }

    private Corrector createETADCorrector() {

        if (acquisitionMode.equals("IW") && productType.equals("SLC")) { // TOPS SLC
            return new TOPSCorrector(sourceProduct, etadUtils, selectedResampling);
        } else if (acquisitionMode.equals("IW") && productType.equals("GRD")) { // GRD
            return new GRDCorrector(sourceProduct, etadUtils, selectedResampling);
        } else if (acquisitionMode.equals("SM") && productType.equals("SLC")) { // SM SLC
            return new SMCorrector(sourceProduct, etadUtils, selectedResampling);
        } else {
            throw new OperatorException("The source product is currently not supported for ETAD correction");
        }
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

            // Containment with tolerance. Legitimate pairs can have only tens of MILLISECONDS of
            // margin (measured: 35-57 ms on S1A/S1C Venezuela slices), so exact containment is one
            // reprocessing baseline away from a false rejection — while a wrong date is off by a
            // day and a neighbouring slice of the same pass by ~25 s. 2 s separates the two cleanly.
            final double tolerance = 2.0;
            if (srcStartTime < etadStartTime - tolerance || srcStopTime > etadStopTime + tolerance) {
                throw new OperatorException(String.format(
                        "The selected ETAD product does not match the source product: source '%s' senses "
                                + "%s to %s but ETAD '%s' covers %s to %s. Select the ETAD product of the "
                                + "same mission, date and slice.",
                        sourceProduct.getName(),
                        ETADUtils.getTime(adsHeaderElem, "startTime").format(),
                        ETADUtils.getTime(adsHeaderElem, "stopTime").format(),
                        etadProduct.getName(),
                        ETADUtils.getTime(etadHeaderElem, "startTime").format(),
                        ETADUtils.getTime(etadHeaderElem, "stopTime").format()));
            }

        } catch(Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    /**
     * Update the metadata in the target product.
     */
    private void updateTargetProductMetadata() {

        writeETADProvenance(targetProduct, etadProduct != null ? etadProduct.getName() : null,
                resamplingImage, outputPhaseCorrections, azimuthCorrectionsSelected());
    }

    /**
     * True when any ETAD azimuth layer is selected. These are the layers that carry the bistatic
     * shift, which {@code GSLCGeocodingOp} would otherwise apply a second time.
     */
    private boolean azimuthCorrectionsSelected() {
        return sumOfAzimuthCorrections || geodeticCorrectionAz || bistaticShiftCorrectionAz
                || fmMismatchCorrectionAz;
    }

    /** Set to 1 whenever this operator has run. */
    public static final String ETAD_CORRECTION_APPLIED = "etad_correction_applied";
    /** Set to 1 when the image was resampled to the ETAD-corrected geometry. */
    public static final String ETAD_GEOMETRY_APPLIED = "etad_geometry_applied";
    /** Set to 1 when the ETAD range-delay phase was removed from the complex data. */
    public static final String ETAD_PHASE_APPLIED = "etad_phase_applied";
    /** Name of the ETAD product used. */
    public static final String ETAD_PRODUCT = "etad_product";
    /**
     * Set to 1 when an ETAD AZIMUTH correction was resampled into the pixels.
     * <p>
     * Recorded separately from {@link #ETAD_GEOMETRY_APPLIED} because the azimuth layers include the
     * bistatic shift, and {@code GSLCGeocodingOp} applies its own bistatic azimuth residual
     * unconditionally for Sentinel-1. Measurement on a real IW product shows the two describe the
     * same range-dependent quantity to within 1% (ETAD across-swath span -0.1700 ms versus GSLC's
     * (rFar-rNear)/c = 0.1687 ms), so the geocoder must not re-apply it. Without this flag the
     * geocoder cannot tell whether ETAD's azimuth terms were selected.
     */
    public static final String ETAD_AZIMUTH_APPLIED = "etad_azimuth_applied";

    /**
     * Record what this ETAD run actually applied, so downstream operators can distinguish a
     * corrected product from a raw one and refuse to double-correct.
     * <p>
     * {@code etad_geometry_applied} tracks resampling of the image. {@code etad_phase_applied}
     * tracks removal of the range-delay phase from the complex data, which happens only in the
     * TOPS combined mode ({@code TOPSCorrector} computes {@code etadRangePhase} when
     * {@code outputPhaseCorrections} and folds it into the reramp angle). In the InSAR (grid) mode
     * the corrections are emitted as tie-point grids and nothing is applied to the pixels, so both
     * geometry and phase are recorded as 0 there even though {@code outputPhaseCorrections} is
     * forced true.
     * <p>
     * Attributes are created before being set: {@code AbstractMetadata.setAttribute(..., int)} does
     * not auto-create a missing attribute, which is why the former {@code etad_correction_flag}
     * write silently did nothing.
     *
     * @param targetProduct         product to annotate
     * @param etadProductName       ETAD product name, may be null
     * @param resamplingImage       whether the image was resampled
     * @param outputPhaseCorrections whether phase corrections were requested
     * @param azimuthCorrectionsSelected whether any ETAD azimuth layer was selected; only meaningful
     *                                   when the image was resampled
     */
    public static void writeETADProvenance(final Product targetProduct, final String etadProductName,
                                           final boolean resamplingImage,
                                           final boolean outputPhaseCorrections,
                                           final boolean azimuthCorrectionsSelected) {

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(targetProduct);
        if (absRoot == null) {
            return;
        }

        setIntFlag(absRoot, ETAD_CORRECTION_APPLIED, 1);
        setIntFlag(absRoot, ETAD_GEOMETRY_APPLIED, resamplingImage ? 1 : 0);
        setIntFlag(absRoot, ETAD_PHASE_APPLIED, (resamplingImage && outputPhaseCorrections) ? 1 : 0);
        // Only the resampling mode moves pixels, so an azimuth selection alone changes nothing.
        setIntFlag(absRoot, ETAD_AZIMUTH_APPLIED,
                (resamplingImage && azimuthCorrectionsSelected) ? 1 : 0);
        // Retained for backward compatibility, and now actually written.
        setIntFlag(absRoot, "etad_correction_flag", 1);

        if (etadProductName != null && !etadProductName.isEmpty()) {
            if (!absRoot.containsAttribute(ETAD_PRODUCT)) {
                AbstractMetadata.addAbstractedAttribute(absRoot, ETAD_PRODUCT,
                        ProductData.TYPE_ASCII, "", "ETAD product used for correction");
            }
            AbstractMetadata.setAttribute(absRoot, ETAD_PRODUCT, etadProductName);
        }
    }

    private static void setIntFlag(final MetadataElement absRoot, final String name, final int value) {
        if (!absRoot.containsAttribute(name)) {
            AbstractMetadata.addAbstractedAttribute(absRoot, name, ProductData.TYPE_UINT8, "flag",
                    "ETAD correction provenance");
        }
        AbstractMetadata.setAttribute(absRoot, name, value);
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
            // JL: This should only for InSAR case
//            if(outputPhaseCorrections && !etadCorrector.hasETADData()) {
//                etadCorrector.loadETADData();
//            }

            etadCorrector.computeTileStack(targetTileMap, targetRectangle, pm, this);
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        } finally {
            pm.done();
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
            super(S1ETADCorrectionOp.class);
        }
    }
}
