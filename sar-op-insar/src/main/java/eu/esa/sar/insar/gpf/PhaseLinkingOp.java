/*
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
package eu.esa.sar.insar.gpf;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.insar.gpf.phaselinking.ADSelector;
import eu.esa.sar.insar.gpf.phaselinking.CovarianceMatrix;
import eu.esa.sar.insar.gpf.phaselinking.EMIEstimator;
import eu.esa.sar.insar.gpf.phaselinking.EVDEstimator;
import eu.esa.sar.insar.gpf.phaselinking.KSSelector;
import eu.esa.sar.insar.gpf.phaselinking.PhaseEstimator;
import eu.esa.sar.insar.gpf.phaselinking.SHPSelector;
import eu.esa.sar.insar.gpf.phaselinking.TLogSelector;
import eu.esa.sar.insar.gpf.phaselinking.TemporalCoherence;
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
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import org.esa.snap.engine_utilities.gpf.StackUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;
import org.jlinda.core.utils.BandUtilsDoris;

import javax.media.jai.BorderExtender;
import java.awt.Rectangle;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Distributed-Scatterer InSAR phase-linking operator.
 *
 * Per pixel:
 *   1. Select Statistically Homogeneous Pixels (SHPs) inside a search window
 *      using a two-sample amplitude test (KS / AD / Welch t on log-amplitude).
 *   2. Form the N x N sample complex covariance from the SHP set; normalise
 *      to the coherence matrix T_hat.
 *   3. Estimate the optimal per-epoch phasor vector via EVD (Ferretti
 *      SqueeSAR 2011) or EMI (Ansari/De Zan/Bamler 2018).
 *   4. Emit a "phase-linked" SLC sample whose amplitude is the original
 *      |s_n(p)| and whose phase is the estimated phi_n(p) - phi_ref(p).
 *
 * The output stack is a drop-in input for InterferogramOp / CoherenceOp /
 * MultiMasterInSAROp; downstream pairs see much higher coherence over
 * distributed scatterers.
 *
 * v1 scope: single-shot full-stack EVD/EMI. Suitable for stacks up to
 * ~50 epochs; for larger N consider subsampling. Sequential ministack
 * (Dolphin) and iterative MLE refinement are deferred to v2.
 *
 * Input requirement: a coregistered SLC stack with i/q band pairs. Burst-
 * organised TOPS SLC stacks must be debursted upstream (TOPSARDeburstOp);
 * the operator throws if it sees an undebursted TOPS product.
 */
@OperatorMetadata(alias = "PhaseLinking",
        category = "Radar/Interferometric/Phase Linking",
        authors = "SkyWatch",
        version = "1.0",
        copyright = "Copyright (C) 2026 by SkyWatch Space Applications Inc.",
        description = "Distributed-scatterer phase linking (SqueeSAR / EVD / EMI) over a coregistered SLC stack.")
public class PhaseLinkingOp extends Operator {

    @SourceProduct
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(description = "SHP search window size in azimuth (pixels)",
            defaultValue = "21",
            label = "SHP Window Azimuth")
    private int windowAzimuth = 21;

    @Parameter(description = "SHP search window size in range (pixels)",
            defaultValue = "7",
            label = "SHP Window Range")
    private int windowRange = 7;

    @Parameter(valueSet = {SHP_TEST_KS, SHP_TEST_AD, SHP_TEST_TLOG},
            defaultValue = SHP_TEST_KS,
            description = "Two-sample amplitude test used to select SHPs",
            label = "SHP Test")
    private String shpTest = SHP_TEST_KS;

    @Parameter(description = "SHP test significance level",
            defaultValue = "0.05",
            label = "SHP alpha")
    private double shpAlpha = 0.05;

    @Parameter(description = "Minimum number of SHPs required to phase-link a pixel",
            defaultValue = "20",
            label = "Minimum SHPs")
    private int shpMin = 20;

    @Parameter(valueSet = {ESTIMATOR_EVD, ESTIMATOR_EMI},
            defaultValue = ESTIMATOR_EVD,
            description = "Phase estimator (EVD: dominant eigenvector; EMI: lower-bias variant)",
            label = "Phase Estimator")
    private String estimator = ESTIMATOR_EVD;

    @Parameter(description = "Reference epoch date (ddMMMyyyy, e.g. 14Sep2020). If null/empty the median epoch is used.",
            defaultValue = "",
            label = "Reference Epoch Date")
    private String referenceEpochDate = "";

    @Parameter(description = "Mask out pixels whose temporal coherence is below this threshold",
            defaultValue = "0.6",
            label = "Temporal Coherence Threshold")
    private double tempCohMin = 0.6;

    @Parameter(description = "Emit temporal coherence diagnostic band",
            defaultValue = "true",
            label = "Output Temporal Coherence")
    private boolean outputTempCoherence = true;

    @Parameter(description = "Emit SHP count diagnostic band",
            defaultValue = "false",
            label = "Output SHP Count")
    private boolean outputShpCount = false;

    public static final String SHP_TEST_KS = "KS";
    public static final String SHP_TEST_AD = "AD";
    public static final String SHP_TEST_TLOG = "TLog";
    public static final String ESTIMATOR_EVD = "EVD";
    public static final String ESTIMATOR_EMI = "EMI";

    private static final String PRODUCT_SUFFIX = "_PL";
    private static final String PL_BAND_NAME_TAG = "pl";
    private static final String TEMP_COH_BAND_NAME = "tempCoh";
    private static final String SHP_COUNT_BAND_NAME = "numSHP";

    private int sourceImageWidth;
    private int sourceImageHeight;
    private int n;
    private int refIndex;

    private static class Epoch {
        final String date;
        final Date dateParsed;
        final Band realBand;
        final Band imagBand;
        final String polarisation;
        final String subswath;
        /** True if this epoch is the reference (master) of the coregistered stack. */
        final boolean isReference;
        /** 1-based index in the order of {@link StackUtils#getSecondaryProductNames}; -1 for the reference. */
        final int secondaryIndex;
        /** Element name in the Secondary_Metadata root for this secondary product; null for the reference. */
        final String originalSecondaryProductName;

        Epoch(final String date, final Date dateParsed, final Band realBand, final Band imagBand,
              final String polarisation, final String subswath,
              final boolean isReference, final int secondaryIndex, final String originalSecondaryProductName) {
            this.date = date;
            this.dateParsed = dateParsed;
            this.realBand = realBand;
            this.imagBand = imagBand;
            this.polarisation = polarisation;
            this.subswath = subswath;
            this.isReference = isReference;
            this.secondaryIndex = secondaryIndex;
            this.originalSecondaryProductName = originalSecondaryProductName;
        }
    }

    /** Per-polarisation chronologically-sorted epoch list. */
    private final Map<String, List<Epoch>> stacksByPol = new HashMap<>();

    /** Maps source real band -> target real band (and same for imag). */
    private final Map<Band, Band> targetRealMap = new HashMap<>();
    private final Map<Band, Band> targetImagMap = new HashMap<>();

    /** Per-polarisation tempCoh / numSHP bands (or null if disabled). */
    private final Map<String, Band> tempCohBandMap = new HashMap<>();
    private final Map<String, Band> shpCountBandMap = new HashMap<>();

    @Override
    public void initialize() throws OperatorException {

        try {
            validateInput();

            sourceImageWidth = sourceProduct.getSceneRasterWidth();
            sourceImageHeight = sourceProduct.getSceneRasterHeight();

            collectEpochs();
            resolveReferenceEpoch();

            if ((windowAzimuth & 1) == 0) windowAzimuth += 1;
            if ((windowRange & 1) == 0) windowRange += 1;

            createTargetProduct();

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private void validateInput() {
        final InputProductValidator validator = new InputProductValidator(sourceProduct);
        validator.checkIfSARProduct();
        validator.checkIfCoregisteredStack();
        if (validator.isTOPSARProduct() && !validator.isDebursted()) {
            throw new OperatorException(
                    "PhaseLinkingOp requires debursted TOPS SLC input. " +
                    "Apply TOPSAR-Deburst first, or use Stripmap data.");
        }
        final boolean isComplex = AbstractMetadata.getAbstractedMetadata(sourceProduct)
                .getAttributeString(AbstractMetadata.SAMPLE_TYPE).contains("COMPLEX");
        if (!isComplex) {
            throw new OperatorException("PhaseLinkingOp requires a complex SLC stack.");
        }
    }

    private void collectEpochs() throws Exception {
        final DateFormat dateFormat = ProductData.UTC.createDateFormat("ddMMMyyyy");

        // Reference (master) of the coregistered stack
        final MetadataElement refRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);
        final String refDate = OperatorUtils.getAcquisitionDate(refRoot);
        final Date refDateParsed = dateFormat.parse(refDate);
        final String[] referenceBandNames = StackUtils.getReferenceBandNames(sourceProduct);
        addEpochsFromBandList(referenceBandNames, refDate, refDateParsed, true, -1, null);

        // Secondaries
        final String[] secondaryProductNames = StackUtils.getSecondaryProductNames(sourceProduct);
        final MetadataElement secondaryRoot = StackUtils.findSecondaryMetadataRoot(sourceProduct);
        if (secondaryRoot == null && secondaryProductNames.length > 0) {
            throw new OperatorException("PhaseLinkingOp: secondary metadata root missing in coregistered stack.");
        }
        int secIdx = 0;
        for (String secondaryProductName : secondaryProductNames) {
            final MetadataElement secMeta = (secondaryRoot != null)
                    ? secondaryRoot.getElement(secondaryProductName)
                    : null;
            if (secMeta == null) continue;
            secIdx++;
            final String date = OperatorUtils.getAcquisitionDate(secMeta);
            final Date dateParsed = dateFormat.parse(date);
            final String[] secBandNames = StackUtils.getSecondaryBandNames(sourceProduct, secondaryProductName);
            addEpochsFromBandList(secBandNames, date, dateParsed, false, secIdx, secondaryProductName);
        }

        // Sort each polarisation's list chronologically
        for (Map.Entry<String, List<Epoch>> e : stacksByPol.entrySet()) {
            e.getValue().sort(Comparator.comparing(o -> o.dateParsed));
        }

        // All polarisations must have the same epoch count - use the first as canonical
        n = 0;
        for (List<Epoch> list : stacksByPol.values()) {
            if (n == 0) {
                n = list.size();
            } else if (list.size() != n) {
                throw new OperatorException("PhaseLinkingOp: inconsistent stack size across polarisations.");
            }
        }
        if (n < 3) {
            throw new OperatorException("PhaseLinkingOp: need at least 3 epochs in the stack (found " + n + ").");
        }
    }

    private void addEpochsFromBandList(final String[] bandNames, final String date, final Date dateParsed,
                                       final boolean isReference, final int secondaryIndex,
                                       final String originalSecondaryProductName) {
        for (String bandName : bandNames) {
            final Band band = sourceProduct.getBand(bandName);
            if (band == null) continue;
            if (!BandUtilsDoris.isBandReal(band)) continue;
            final Band imagBand = findMatchingImagBand(band);
            if (imagBand == null) continue;
            final String pol = inferPolarisation(bandName);
            final String swath = inferSubswath(bandName);
            stacksByPol.computeIfAbsent(pol, k -> new ArrayList<>())
                    .add(new Epoch(date, dateParsed, band, imagBand, pol, swath,
                            isReference, secondaryIndex, originalSecondaryProductName));
        }
    }

    private Band findMatchingImagBand(final Band realBand) {
        // Convention: real band starts with "i_" and the matching imag band starts with "q_"
        // with otherwise identical suffix.
        final String realName = realBand.getName();
        if (!realName.startsWith("i_")) return null;
        final String imagName = "q_" + realName.substring(2);
        return sourceProduct.getBand(imagName);
    }

    private static String inferPolarisation(final String bandName) {
        final String upper = bandName.toUpperCase();
        if (upper.contains("_HH")) return "HH";
        if (upper.contains("_HV")) return "HV";
        if (upper.contains("_VH")) return "VH";
        if (upper.contains("_VV")) return "VV";
        return "";
    }

    private static String inferSubswath(final String bandName) {
        final String upper = bandName.toUpperCase();
        if (upper.contains("_IW1")) return "IW1";
        if (upper.contains("_IW2")) return "IW2";
        if (upper.contains("_IW3")) return "IW3";
        if (upper.contains("_EW1")) return "EW1";
        if (upper.contains("_EW2")) return "EW2";
        if (upper.contains("_EW3")) return "EW3";
        if (upper.contains("_EW4")) return "EW4";
        if (upper.contains("_EW5")) return "EW5";
        return "";
    }

    private void resolveReferenceEpoch() {
        final List<Epoch> any = stacksByPol.values().iterator().next();
        if (referenceEpochDate == null || referenceEpochDate.trim().isEmpty()) {
            // median chronological index (symmetry with SBASInversionOp)
            refIndex = n / 2;
            return;
        }
        final String target = referenceEpochDate.trim();
        for (int i = 0; i < any.size(); i++) {
            if (any.get(i).date.equalsIgnoreCase(target)) {
                refIndex = i;
                return;
            }
        }
        final StringBuilder sb = new StringBuilder();
        for (Epoch e : any) {
            sb.append(' ').append(e.date);
        }
        throw new OperatorException("PhaseLinkingOp: referenceEpochDate '" + target +
                "' not present in stack. Available:" + sb);
    }

    private void createTargetProduct() throws Exception {
        targetProduct = new Product(sourceProduct.getName() + PRODUCT_SUFFIX,
                sourceProduct.getProductType(),
                sourceProduct.getSceneRasterWidth(),
                sourceProduct.getSceneRasterHeight());

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        // New ref/sec band names accumulated for the stack metadata so downstream operators
        // (InterferogramOp, CoherenceOp, MultiMasterInSAROp) can resolve them via StackUtils.
        final List<String> refBandNames = new ArrayList<>();
        final Map<String, List<String>> secBandNamesByProduct = new HashMap<>();

        for (Map.Entry<String, List<Epoch>> entry : stacksByPol.entrySet()) {
            final String pol = entry.getKey();
            final List<Epoch> epochs = entry.getValue();

            for (Epoch e : epochs) {
                final String swath = e.subswath.isEmpty() ? "" : '_' + e.subswath;
                final String polTag = pol.isEmpty() ? "" : '_' + pol;
                final String roleTag = e.isReference
                        ? StackUtils.REF
                        : StackUtils.SEC + e.secondaryIndex;
                final String suffix = swath + polTag + roleTag + '_' + e.date;

                final String iName = "i_" + PL_BAND_NAME_TAG + suffix;
                final String qName = "q_" + PL_BAND_NAME_TAG + suffix;

                final Band tgtI = targetProduct.addBand(iName, ProductData.TYPE_FLOAT32);
                ProductUtils.copyRasterDataNodeProperties(e.realBand, tgtI);
                tgtI.setUnit(Unit.REAL);
                tgtI.setDescription("Phase-linked real component, epoch " + e.date);

                final Band tgtQ = targetProduct.addBand(qName, ProductData.TYPE_FLOAT32);
                ProductUtils.copyRasterDataNodeProperties(e.imagBand, tgtQ);
                tgtQ.setUnit(Unit.IMAGINARY);
                tgtQ.setDescription("Phase-linked imaginary component, epoch " + e.date);

                targetRealMap.put(e.realBand, tgtI);
                targetImagMap.put(e.imagBand, tgtQ);

                final String virtualSuffix = "_" + PL_BAND_NAME_TAG + suffix;
                ReaderUtils.createVirtualIntensityBand(targetProduct, tgtI, tgtQ, virtualSuffix);
                ReaderUtils.createVirtualPhaseBand(targetProduct, tgtI, tgtQ, virtualSuffix);

                if (e.isReference) {
                    refBandNames.add(iName);
                    refBandNames.add(qName);
                } else {
                    secBandNamesByProduct
                            .computeIfAbsent(e.originalSecondaryProductName, k -> new ArrayList<>())
                            .add(iName);
                    secBandNamesByProduct
                            .get(e.originalSecondaryProductName)
                            .add(qName);
                }
            }

            if (outputTempCoherence) {
                final String name = TEMP_COH_BAND_NAME + (pol.isEmpty() ? "" : '_' + pol);
                final Band b = targetProduct.addBand(name, ProductData.TYPE_FLOAT32);
                b.setUnit(Unit.COHERENCE);
                b.setNoDataValue(Double.NaN);
                b.setNoDataValueUsed(true);
                b.setDescription("Phase-linking temporal (goodness-of-fit) coherence");
                tempCohBandMap.put(pol, b);
            }
            if (outputShpCount) {
                final String name = SHP_COUNT_BAND_NAME + (pol.isEmpty() ? "" : '_' + pol);
                final Band b = targetProduct.addBand(name, ProductData.TYPE_INT32);
                b.setNoDataValue(0);
                b.setNoDataValueUsed(true);
                b.setDescription("SHP count per pixel");
                shpCountBandMap.put(pol, b);
            }
        }

        // Overwrite stale Reference_bands / Secondary_bands metadata (copied verbatim from source
        // by copyProductNodes) with the new phase-linked band names.
        if (!refBandNames.isEmpty()) {
            StackUtils.saveReferenceProductBandNames(targetProduct,
                    refBandNames.toArray(new String[0]));
        }
        for (Map.Entry<String, List<String>> me : secBandNamesByProduct.entrySet()) {
            StackUtils.saveSecondaryProductBandNames(targetProduct, me.getKey(),
                    me.getValue().toArray(new String[0]));
        }
    }

    @Override
    public void computeTileStack(final Map<Band, Tile> targetTileMap, final Rectangle targetRectangle,
                                 final ProgressMonitor pm) throws OperatorException {

        try {
            for (Map.Entry<String, List<Epoch>> entry : stacksByPol.entrySet()) {
                processPolarisation(entry.getKey(), entry.getValue(), targetTileMap, targetRectangle);
            }
        } catch (Throwable t) {
            OperatorUtils.catchOperatorException(getId(), t);
        } finally {
            pm.done();
        }
    }

    private void processPolarisation(final String pol, final List<Epoch> epochs,
                                     final Map<Band, Tile> targetTileMap,
                                     final Rectangle targetRectangle) {

        final BorderExtender border = BorderExtender.createInstance(BorderExtender.BORDER_ZERO);

        final int halfAz = windowAzimuth / 2;
        final int halfRg = windowRange / 2;

        final int extX0 = targetRectangle.x - halfRg;
        final int extY0 = targetRectangle.y - halfAz;
        final int extW = targetRectangle.width + 2 * halfRg;
        final int extH = targetRectangle.height + 2 * halfAz;
        final Rectangle extRect = new Rectangle(extX0, extY0, extW, extH);

        // Pull source tiles for all epochs once per tile
        final Tile[] srcRealTiles = new Tile[n];
        final Tile[] srcImagTiles = new Tile[n];
        final double noData = epochs.get(0).realBand.getNoDataValue();
        for (int k = 0; k < n; k++) {
            srcRealTiles[k] = getSourceTile(epochs.get(k).realBand, extRect, border);
            srcImagTiles[k] = getSourceTile(epochs.get(k).imagBand, extRect, border);
        }

        // Cache extended-tile data buffers
        final ProductData[] srcRealBufs = new ProductData[n];
        final ProductData[] srcImagBufs = new ProductData[n];
        final TileIndex[] srcIndex = new TileIndex[n];
        for (int k = 0; k < n; k++) {
            srcRealBufs[k] = srcRealTiles[k].getDataBuffer();
            srcImagBufs[k] = srcImagTiles[k].getDataBuffer();
            srcIndex[k] = new TileIndex(srcRealTiles[k]);
        }

        // Target tiles per epoch
        final Tile[] tgtRealTiles = new Tile[n];
        final Tile[] tgtImagTiles = new Tile[n];
        final TileIndex[] tgtIndex = new TileIndex[n];
        for (int k = 0; k < n; k++) {
            final Band realTgt = targetRealMap.get(epochs.get(k).realBand);
            final Band imagTgt = targetImagMap.get(epochs.get(k).imagBand);
            tgtRealTiles[k] = targetTileMap.get(realTgt);
            tgtImagTiles[k] = targetTileMap.get(imagTgt);
            tgtIndex[k] = new TileIndex(tgtRealTiles[k]);
        }

        final Band tempCohBand = tempCohBandMap.get(pol);
        final Tile tempCohTile = (tempCohBand != null) ? targetTileMap.get(tempCohBand) : null;
        final TileIndex tempCohIndex = (tempCohTile != null) ? new TileIndex(tempCohTile) : null;

        final Band shpCountBand = shpCountBandMap.get(pol);
        final Tile shpCountTile = (shpCountBand != null) ? targetTileMap.get(shpCountBand) : null;
        final TileIndex shpCountIndex = (shpCountTile != null) ? new TileIndex(shpCountTile) : null;

        // Working buffers (per-thread / per-tile)
        final SHPSelector selector = buildSelector();
        final PhaseEstimator phaseEstimator = buildEstimator();
        final CovarianceMatrix C = new CovarianceMatrix(n);
        final double[][] tRe = new double[n][n];
        final double[][] tIm = new double[n][n];
        final double[] phi = new double[n];
        final double[] centreAmp = new double[n];
        final double[] candAmp = new double[n];
        final double[] slcRe = new double[n];
        final double[] slcIm = new double[n];
        final double[] centreSlcRe = new double[n];
        final double[] centreSlcIm = new double[n];

        final int x0 = targetRectangle.x;
        final int y0 = targetRectangle.y;
        final int xN = x0 + targetRectangle.width;
        final int yN = y0 + targetRectangle.height;

        for (int y = y0; y < yN; y++) {
            for (int k = 0; k < n; k++) tgtIndex[k].calculateStride(y);
            if (tempCohIndex != null) tempCohIndex.calculateStride(y);
            if (shpCountIndex != null) shpCountIndex.calculateStride(y);
            for (int k = 0; k < n; k++) srcIndex[k].calculateStride(y);

            for (int x = x0; x < xN; x++) {

                // Centre sample series. Read every epoch (do NOT bail on the first noData) so
                // pass-through can preserve valid samples at other epochs; the covariance is only
                // usable when all epochs are valid.
                int validEpochs = 0;
                for (int k = 0; k < n; k++) {
                    final int idx = srcIndex[k].getIndex(x);
                    final double re = srcRealBufs[k].getElemDoubleAt(idx);
                    final double im = srcImagBufs[k].getElemDoubleAt(idx);
                    centreSlcRe[k] = re;
                    centreSlcIm[k] = im;
                    if (re == noData && im == noData) {
                        centreAmp[k] = 0.0;
                    } else {
                        centreAmp[k] = Math.sqrt(re * re + im * im);
                        validEpochs++;
                    }
                }
                if (validEpochs < n) {
                    // Any invalid epoch breaks the covariance assumption. Pass-through preserves
                    // whatever the source held (noData stays noData, valid epochs stay valid) so
                    // downstream Interferogram/Coherence sees the same data as the original stack.
                    passThroughCentre(x, tgtRealTiles, tgtImagTiles, tgtIndex,
                            centreSlcRe, centreSlcIm,
                            tempCohTile, tempCohIndex, Float.NaN,
                            shpCountTile, shpCountIndex, 0);
                    continue;
                }

                selector.prepareCentre(centreAmp);

                // Walk SHP window
                C.reset();
                int shpCount = 0;
                final int yMin = Math.max(y - halfAz, extY0);
                final int yMax = Math.min(y + halfAz, extY0 + extH - 1);
                final int xMin = Math.max(x - halfRg, extX0);
                final int xMax = Math.min(x + halfRg, extX0 + extW - 1);
                for (int yy = yMin; yy <= yMax; yy++) {
                    for (int k = 0; k < n; k++) srcIndex[k].calculateStride(yy);
                    for (int xx = xMin; xx <= xMax; xx++) {
                        // Build candidate amplitude series
                        boolean candValid = true;
                        for (int k = 0; k < n; k++) {
                            final int idx = srcIndex[k].getIndex(xx);
                            final double re = srcRealBufs[k].getElemDoubleAt(idx);
                            final double im = srcImagBufs[k].getElemDoubleAt(idx);
                            if (re == 0.0 && im == 0.0) { candValid = false; break; }
                            slcRe[k] = re;
                            slcIm[k] = im;
                            candAmp[k] = Math.sqrt(re * re + im * im);
                        }
                        if (!candValid) continue;
                        if (!selector.accept(centreAmp, candAmp)) continue;
                        C.accumulate(slcRe, slcIm);
                        shpCount++;
                    }
                    // restore stride to row y for the next iteration's centre read
                    for (int k = 0; k < n; k++) srcIndex[k].calculateStride(y);
                }

                // Re-set stride to current y (we walked yy above)
                for (int k = 0; k < n; k++) srcIndex[k].calculateStride(y);

                if (shpCount < shpMin) {
                    // Too few SHPs to form a reliable covariance: pass-through original samples.
                    passThroughCentre(x, tgtRealTiles, tgtImagTiles, tgtIndex,
                            centreSlcRe, centreSlcIm,
                            tempCohTile, tempCohIndex, Float.NaN,
                            shpCountTile, shpCountIndex, shpCount);
                    continue;
                }

                C.finalizeT(shpCount, tRe, tIm);
                phaseEstimator.estimate(n, tRe, tIm, refIndex, phi);
                final double gamma = TemporalCoherence.compute(n, tRe, tIm, phi);

                if (gamma < tempCohMin) {
                    // Phase-linking estimate unreliable. Pass-through original SLC samples so
                    // downstream sees no worse than the input stack; the tempCoh band records
                    // gamma as the quality signal users can mask on.
                    passThroughCentre(x, tgtRealTiles, tgtImagTiles, tgtIndex,
                            centreSlcRe, centreSlcIm,
                            tempCohTile, tempCohIndex, (float) gamma,
                            shpCountTile, shpCountIndex, shpCount);
                    continue;
                }

                // Write linked samples: amplitude from original SLC, phase from estimator
                for (int k = 0; k < n; k++) {
                    final double amp = centreAmp[k];
                    final double cs = Math.cos(phi[k]);
                    final double sn = Math.sin(phi[k]);
                    final int tgtIdx = tgtIndex[k].getIndex(x);
                    tgtRealTiles[k].getDataBuffer().setElemFloatAt(tgtIdx, (float) (amp * cs));
                    tgtImagTiles[k].getDataBuffer().setElemFloatAt(tgtIdx, (float) (amp * sn));
                }
                if (tempCohTile != null) {
                    tempCohTile.getDataBuffer().setElemFloatAt(tempCohIndex.getIndex(x), (float) gamma);
                }
                if (shpCountTile != null) {
                    shpCountTile.getDataBuffer().setElemIntAt(shpCountIndex.getIndex(x), shpCount);
                }
            }
        }
    }

    /**
     * Copies the original SLC sample series to the target (rather than zeroing) so phase linking
     * never degrades the input stack: pixels we can't refine remain usable downstream. The
     * diagnostic tempCoh / numSHP bands record the per-pixel quality so users can mask if desired.
     */
    private void passThroughCentre(final int x,
                                   final Tile[] tgtRealTiles, final Tile[] tgtImagTiles, final TileIndex[] tgtIndex,
                                   final double[] centreSlcRe, final double[] centreSlcIm,
                                   final Tile tempCohTile, final TileIndex tempCohIndex, final float tempCohValue,
                                   final Tile shpCountTile, final TileIndex shpCountIndex, final int shpCountValue) {
        for (int k = 0; k < n; k++) {
            final int tgtIdx = tgtIndex[k].getIndex(x);
            tgtRealTiles[k].getDataBuffer().setElemFloatAt(tgtIdx, (float) centreSlcRe[k]);
            tgtImagTiles[k].getDataBuffer().setElemFloatAt(tgtIdx, (float) centreSlcIm[k]);
        }
        if (tempCohTile != null) {
            tempCohTile.getDataBuffer().setElemFloatAt(tempCohIndex.getIndex(x), tempCohValue);
        }
        if (shpCountTile != null) {
            shpCountTile.getDataBuffer().setElemIntAt(shpCountIndex.getIndex(x), shpCountValue);
        }
    }

    private SHPSelector buildSelector() {
        switch (shpTest) {
            case SHP_TEST_AD:
                return new ADSelector(shpAlpha, n);
            case SHP_TEST_TLOG:
                return new TLogSelector(shpAlpha);
            case SHP_TEST_KS:
            default:
                return new KSSelector(shpAlpha, n);
        }
    }

    private PhaseEstimator buildEstimator() {
        if (ESTIMATOR_EMI.equalsIgnoreCase(estimator)) return new EMIEstimator();
        return new EVDEstimator();
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(PhaseLinkingOp.class);
        }
    }
}
