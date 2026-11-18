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
package org.csa.rstb.polarimetric.gpf;

import com.bc.ceres.core.ProgressMonitor;
import org.csa.rstb.polarimetric.gpf.decompositions.hAAlpha;
import org.csa.rstb.polarimetric.gpf.support.DualPolProcessor;
import org.csa.rstb.polarimetric.gpf.support.QuadPolProcessor;
import eu.esa.sar.commons.polsar.PolBandUtils;
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
import org.esa.snap.engine_utilities.gpf.FilterWindow;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.StackUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Compute polarimetric parameters and channel-algebra outputs for quad-pol, dual-pol or
 * compact-pol products. Two input modes are supported:
 *
 * <ul>
 *   <li><b>Intensity bands</b> &mdash; the source product carries named intensity bands
 *       ({@code _HH}, {@code _HV}, {@code _VV}, {@code _VH}). Channel ratios and indices
 *       (RFDI, CSI, VSI, BMI, ITI) are derived directly from those bands.</li>
 *   <li><b>Matrix elements</b> &mdash; the source is a C2 / C3 / T3 / FULL covariance or
 *       coherency matrix product. Diagonal elements give the channel intensities;
 *       off-diagonal elements give the complex correlations used for the new
 *       Phase_ij and Coh_ij outputs.</li>
 * </ul>
 *
 * <p>For matrix input, the operator extracts the channel powers |HH|², |HV|², |VV|², |VH|² from the diagonal of the
 * (lex-basis) covariance matrix &mdash; T3 input is converted to C3 internally. For dual-pol
 * C2 input, only the channels actually present (per the input MATRIX subtype) are available;
 * outputs that require missing channels will fail validation in {@code initialize()}.</p>
 */

@OperatorMetadata(alias = "Polarimetric-Parameters",
        category = "Radar/Polarimetric",
        authors = "Jun Lu, Luis Veci",
        version = "1.1",
        copyright = "Copyright (C) 2016 by Array Systems Computing Inc.",
        description = "Compute general polarimetric parameters and channel algebra (RFDI, CSI, ratios, span, phase, complex correlation)")
public final class PolarimetricParametersOp extends Operator implements QuadPolProcessor, DualPolProcessor {

    @SourceProduct(alias = "source")
    private Product sourceProduct;
    @TargetProduct
    private Product targetProduct;

    @Parameter(description = "Use mean coherency or covariance matrix", defaultValue = "true", label = "Use Mean Matrix")
    private boolean useMeanMatrix = true;

    @Parameter(valueSet = {"3", "5", "7", "9", "11", "13", "15", "17", "19"}, defaultValue = "5", label = "Window Size X")
    private String windowSizeXStr = "5";

    @Parameter(valueSet = {"3", "5", "7", "9", "11", "13", "15", "17", "19"}, defaultValue = "5", label = "Window Size Y")
    private String windowSizeYStr = "5";

    @Parameter(description = "Output Span", defaultValue = "true", label = "Span")
    private boolean outputSpan = true;

    @Parameter(description = "Output pedestal height", defaultValue = "false", label = "Pedestal Height")
    private boolean outputPedestalHeight = false;
    @Parameter(description = "Output RVI", defaultValue = "false", label = "Radar Vegetation Index")
    private boolean outputRVI = false;
    @Parameter(description = "Output RFDI = (HH-HV)/(HH+HV)", defaultValue = "false", label = "Radar Forest Degradation Index")
    private boolean outputRFDI = false;

    @Parameter(description = "Output CSI = VV/(VV+HH)", defaultValue = "false", label = "Canopy Structure Index")
    private boolean outputCSI = false;
    @Parameter(description = "Output VSI = (HV+VH)/(HH+VV+HV+VH)", defaultValue = "false", label = "Volume Scattering Index")
    private boolean outputVSI = false;
    @Parameter(description = "Output BMI = (HH+VV)/2", defaultValue = "false", label = "Biomass Index")
    private boolean outputBMI = false;
    @Parameter(description = "Output ITI = HH/VV", defaultValue = "false", label = "Interaction Index")
    private boolean outputITI = false;

    @Parameter(description = "Output HH/VV intensity ratio", defaultValue = "false", label = "HH/VV Ratio")
    private boolean outputHHVVRatio = false;
    @Parameter(description = "Output HH/HV intensity ratio", defaultValue = "false", label = "HH/HV Ratio")
    private boolean outputHHHVRatio = false;
    @Parameter(description = "Output VV/VH intensity ratio", defaultValue = "false", label = "VV/VH Ratio")
    private boolean outputVVVHRatio = false;

    @Parameter(description = "Output complex-correlation magnitude |c_ij|/sqrt(c_ii*c_jj) for off-diagonal channel pairs (matrix input only)",
            defaultValue = "false", label = "Complex Correlation")
    private boolean outputCoherence = false;

    @Parameter(description = "Output complex-correlation phase arg(c_ij) for off-diagonal channel pairs in radians (matrix input only)",
            defaultValue = "false", label = "Off-diagonal Phase")
    private boolean outputPhase = false;

    private FilterWindow window;
    private int sourceImageWidth = 0;
    private int sourceImageHeight = 0;

    private boolean isComplex;
    private PolBandUtils.MATRIX sourceProductType = null;
    private PolBandUtils.PolSourceBand[] srcBandList;

    /** Set when the operator runs in named-intensity-band mode. Otherwise the matrix path is used. */
    private boolean intensityBandMode = false;
    private Band hhBand = null, hvBand = null, vvBand = null, vhBand = null;
    /** Per band group (acquisition date) intensity bands [HH, HV, VV, VH], set in intensity-band mode. */
    private Band[][] groupIntensityBands;
    private boolean isStack = false;
    /** Channel pair actually held by the matrix (differs from sourceProductType only for a generic C2). */
    private PolBandUtils.MATRIX channelPairType = null;

    /**
     * Channel availability from matrix input (set in initialize() based on sourceProductType).
     * For C2 dual-pol, only the channels of the active mode are available.
     */
    private boolean hasHH, hasHV, hasVV, hasVH;

    private final static String PRODUCT_SUFFIX = "_PP";

    private enum BandType {
        Span, PedestalHeight, RVI,
        RFDI, CSI, VSI, BMI, ITI,
        HHVVRatio, HHHVRatio, VVVHRatio,
        Coh_HHVV, Coh_HHHV, Coh_VVVH,
        Phase_HHVV, Phase_HHHV, Phase_VVVH
    }

    @Override
    public void initialize() throws OperatorException {

        try {
            final InputProductValidator validator = new InputProductValidator(sourceProduct);
            validator.checkIfSARProduct();
            isComplex = validator.isComplex();

            sourceProductType = PolBandUtils.getSourceProductType(sourceProduct);
            channelPairType = getChannelPairType();
            isStack = StackUtils.isCoregisteredStack(sourceProduct);

            // Detect input mode: prefer named intensity bands when present.
            findIntensityBands();
            if (hhBand == null && hvBand == null && vvBand == null && vhBand == null) {
                intensityBandMode = false;
                deriveChannelAvailabilityFromMatrix();
            } else {
                intensityBandMode = true;
                hasHH = hhBand != null;
                hasHV = hvBand != null;
                hasVV = vvBand != null;
                hasVH = vhBand != null;
            }

            validateOutputsAgainstAvailability();

            if (intensityBandMode && sourceProductType != PolBandUtils.MATRIX.FULL) {
                // Dual-pol intensity bands (e.g. a GRD): PolBandUtils expects i/q pairs for DUAL_* types,
                // so build the band groups (one per acquisition date on a stack) from the intensity bands.
                srcBandList = getIntensityBandGroups();
            } else {
                srcBandList = PolBandUtils.getSourceBands(sourceProduct, sourceProductType);
            }
            if (intensityBandMode) {
                groupIntensityBands = new Band[srcBandList.length][];
                for (int g = 0; g < srcBandList.length; g++) {
                    groupIntensityBands[g] = findIntensityBands(srcBandList[g].suffix);
                }
            }

            window = new FilterWindow(Integer.parseInt(windowSizeXStr), Integer.parseInt(windowSizeYStr));

            sourceImageWidth = sourceProduct.getSceneRasterWidth();
            sourceImageHeight = sourceProduct.getSceneRasterHeight();

            createTargetProduct();
            updateTargetProductMetadata();
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    /**
     * Intensity bands [HH, HV, VV, VH] of one band group. On a coregistered stack only the bands of that
     * acquisition (name ending with the group's date suffix) are used, so each date gets its own outputs.
     */
    private Band[] findIntensityBands(final String groupSuffix) {
        if (!isStack || groupSuffix == null || groupSuffix.isEmpty()) {
            return new Band[]{hhBand, hvBand, vvBand, vhBand};
        }
        final Band[] bands = new Band[4];
        for (Band srcBand : sourceProduct.getBands()) {
            final String unit = srcBand.getUnit();
            if (unit == null || !unit.equals(Unit.INTENSITY) || !srcBand.getName().endsWith(groupSuffix)) continue;
            final String n = srcBand.getName().toUpperCase();
            if (n.contains("_HH")) bands[0] = srcBand;
            else if (n.contains("_HV")) bands[1] = srcBand;
            else if (n.contains("_VV")) bands[2] = srcBand;
            else if (n.contains("_VH")) bands[3] = srcBand;
        }
        return bands;
    }

    /** One group of intensity bands per acquisition (a single group when the product is not a stack). */
    private PolBandUtils.PolSourceBand[] getIntensityBandGroups() {
        final List<PolBandUtils.PolSourceBand> groups = new ArrayList<>();
        if (isStack) {
            // Same grouping and naming as PolBandUtils.getSourceBands (reference first, then each secondary).
            final List<String> dateNames = new ArrayList<>();
            final List<String[]> dateBandNames = new ArrayList<>();
            dateNames.add(sourceProduct.getName());
            dateBandNames.add(StackUtils.getReferenceBandNames(sourceProduct));
            for (String secProduct : StackUtils.getSecondaryProductNames(sourceProduct)) {
                dateNames.add(secProduct);
                dateBandNames.add(StackUtils.getSecondaryBandNames(sourceProduct, secProduct));
            }
            for (int d = 0; d < dateBandNames.size(); d++) {
                final String[] names = dateBandNames.get(d);
                if (names == null || names.length == 0) continue;
                final int idx = names[0].lastIndexOf('_');
                final String suffix = idx < 0 ? "" : names[0].substring(idx);
                final List<Band> bands = new ArrayList<>();
                for (Band b : findIntensityBands(suffix)) {
                    if (b != null) bands.add(b);
                }
                if (!bands.isEmpty()) {
                    groups.add(new PolBandUtils.PolSourceBand(dateNames.get(d), bands.toArray(new Band[0]), suffix));
                }
            }
        }
        if (groups.isEmpty()) {
            final List<Band> bands = new ArrayList<>();
            for (Band b : new Band[]{hhBand, hvBand, vhBand, vvBand}) {
                if (b != null) bands.add(b);
            }
            groups.add(new PolBandUtils.PolSourceBand(sourceProduct.getName(), bands.toArray(new Band[0]), ""));
        }
        return groups.toArray(new PolBandUtils.PolSourceBand[0]);
    }

    private void findIntensityBands() {
        for (Band srcBand : sourceProduct.getBands()) {
            final String unit = srcBand.getUnit();
            if (unit == null || !unit.equals(Unit.INTENSITY)) continue;
            final String n = srcBand.getName().toUpperCase();
            if (n.contains("_HH")) hhBand = srcBand;
            else if (n.contains("_HV")) hvBand = srcBand;
            else if (n.contains("_VV")) vvBand = srcBand;
            else if (n.contains("_VH")) vhBand = srcBand;
        }
    }

    /**
     * For a generic C2 product, take the channel pair from the abstracted-metadata polarisations (copied by
     * Polarimetric Matrix Generation). Its C2 element order follows PolBandUtils: [HH, HV], [VV, VH] or
     * [HH, VV]. Anything else (e.g. compact pol) keeps the HH/VV fallback.
     */
    private PolBandUtils.MATRIX getChannelPairType() {
        if (sourceProductType != PolBandUtils.MATRIX.C2) {
            return sourceProductType;
        }
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);
        if (absRoot == null) {
            return sourceProductType;
        }
        final java.util.Set<String> pols = new java.util.HashSet<>();
        for (String tag : AbstractMetadata.polarTags) {
            final String pol = absRoot.getAttributeString(tag, AbstractMetadata.NO_METADATA_STRING).trim().toUpperCase();
            if (!pol.isEmpty() && !pol.equals(AbstractMetadata.NO_METADATA_STRING)) {
                pols.add(pol);
            }
        }
        if (pols.size() == 2) {
            if (pols.contains("HH") && pols.contains("HV")) return PolBandUtils.MATRIX.DUAL_HH_HV;
            if (pols.contains("VV") && pols.contains("VH")) return PolBandUtils.MATRIX.DUAL_VH_VV;
            if (pols.contains("HH") && pols.contains("VV")) return PolBandUtils.MATRIX.DUAL_HH_VV;
        }
        return sourceProductType;
    }

    private void deriveChannelAvailabilityFromMatrix() {
        switch (channelPairType) {
            case C3:
            case T3:
            case FULL:
                hasHH = hasHV = hasVV = true;
                hasVH = true; // reciprocity assumed → use HV value
                break;
            case DUAL_HH_HV:
                hasHH = hasHV = true;
                hasVV = hasVH = false;
                break;
            case DUAL_VH_VV:
                hasVV = hasVH = true;
                hasHH = hasHV = false;
                break;
            case DUAL_HH_VV:
                hasHH = hasVV = true;
                hasHV = hasVH = false;
                break;
            case C2:
                // Generic C2: assume HH+VV-style co-pol pair (most permissive). User should
                // supply a typed dual-pol product when possible.
                hasHH = hasVV = true;
                hasHV = hasVH = false;
                break;
            default:
                throw new OperatorException("Unsupported source product type for matrix-element mode: " + sourceProductType);
        }
    }

    private void validateOutputsAgainstAvailability() {
        // Span / PedestalHeight / RVI need the matrix path on a complex quad-pol product.
        if (outputSpan || outputPedestalHeight || outputRVI) {
            final boolean isQuadMatrix = sourceProductType == PolBandUtils.MATRIX.C3
                    || sourceProductType == PolBandUtils.MATRIX.T3
                    || sourceProductType == PolBandUtils.MATRIX.FULL;
            if (!isQuadMatrix) {
                throw new OperatorException("A quad-pol C3/T3/FULL product is required for Span / PedestalHeight / RVI.");
            }
            if (!isComplex && (outputSpan || outputPedestalHeight)) {
                throw new OperatorException("A complex (T3/C3/FULL SLC) product is required for Span and PedestalHeight.");
            }
        }

        if ((outputHHVVRatio || outputCSI || outputBMI || outputITI) && (!hasHH || !hasVV)) {
            throw new OperatorException("HH and VV channels are required for HH/VV ratio, CSI, BMI and ITI.");
        }
        if ((outputRFDI || outputHHHVRatio) && (!hasHH || !hasHV)) {
            throw new OperatorException("HH and HV channels are required for RFDI and HH/HV ratio.");
        }
        if (outputVVVHRatio && (!hasVV || !hasVH)) {
            throw new OperatorException("VV and VH channels are required for VV/VH ratio.");
        }
        if (outputVSI && (!hasHH || !hasHV || !hasVV || !hasVH)) {
            throw new OperatorException("HH, HV, VV and VH channels are required for VSI.");
        }
        if ((outputCoherence || outputPhase) && intensityBandMode) {
            throw new OperatorException("Off-diagonal phase and complex correlation outputs require a C2/C3/T3/FULL matrix input.");
        }
    }

    private void createTargetProduct() {

        targetProduct = new Product(sourceProduct.getName() + PRODUCT_SUFFIX,
                sourceProduct.getProductType(),
                sourceProduct.getSceneRasterWidth(),
                sourceProduct.getSceneRasterHeight());

        addSelectedBands();

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);
    }

    private void addSelectedBands() throws OperatorException {

        final String[] targetBandNames = getTargetBandNames();

        for (PolBandUtils.PolSourceBand bandList : srcBandList) {
            final Band[] targetBands = OperatorUtils.addBands(targetProduct, targetBandNames, bandList.suffix);
            for (Band targetBand : targetBands) {
                targetBand.setUnit(getUnit(targetBand.getName()));
            }
            bandList.addTargetBands(targetBands);
        }

        if (targetProduct.getNumBands() == 0) {
            throw new OperatorException("No output bands selected");
        }
    }

    /** Span and BMI are powers; Coh_ and Phase_ are coherence and phase (radians); the rest are dimensionless. */
    private static String getUnit(final String bandName) {
        if (bandName.startsWith(BandType.Span.toString()) || bandName.startsWith(BandType.BMI.toString())) {
            return Unit.INTENSITY;
        } else if (bandName.startsWith("Coh_")) {
            return Unit.COHERENCE;
        } else if (bandName.startsWith("Phase_")) {
            return Unit.PHASE;
        }
        return null;
    }

    private String[] getTargetBandNames() {
        final List<String> names = new ArrayList<>();

        if (outputSpan) names.add(BandType.Span.toString());
        if (outputPedestalHeight) names.add(BandType.PedestalHeight.toString());
        if (outputRVI) names.add(BandType.RVI.toString());
        if (outputRFDI) names.add(BandType.RFDI.toString());
        if (outputCSI) names.add(BandType.CSI.toString());
        if (outputVSI) names.add(BandType.VSI.toString());
        if (outputBMI) names.add(BandType.BMI.toString());
        if (outputITI) names.add(BandType.ITI.toString());
        if (outputHHVVRatio) names.add(BandType.HHVVRatio.toString());
        if (outputHHHVRatio) names.add(BandType.HHHVRatio.toString());
        if (outputVVVHRatio) names.add(BandType.VVVHRatio.toString());

        if (outputCoherence) {
            if (hasHH && hasVV) names.add(BandType.Coh_HHVV.toString());
            if (hasHH && hasHV) names.add(BandType.Coh_HHHV.toString());
            if (hasVV && hasVH) names.add(BandType.Coh_VVVH.toString());
        }
        if (outputPhase) {
            if (hasHH && hasVV) names.add(BandType.Phase_HHVV.toString());
            if (hasHH && hasHV) names.add(BandType.Phase_HHHV.toString());
            if (hasVV && hasVH) names.add(BandType.Phase_VVVH.toString());
        }
        return names.toArray(new String[0]);
    }

    private void updateTargetProductMetadata() throws Exception {
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(targetProduct);
        if (absRoot != null) {
            absRoot.setAttributeInt(AbstractMetadata.polsarData, 1);
        }
        PolBandUtils.saveNewBandNames(targetProduct, srcBandList);
    }

    @Override
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle targetRectangle, ProgressMonitor pm)
            throws OperatorException {

        final int x0 = targetRectangle.x;
        final int y0 = targetRectangle.y;
        final int w = targetRectangle.width;
        final int h = targetRectangle.height;
        final int maxY = y0 + h;
        final int maxX = x0 + w;

        final TileIndex trgIndex = new TileIndex(targetTiles.get(targetProduct.getBandAt(0)));
        final Rectangle sourceRectangle = window.getSourceTileRectangle(x0, y0, w, h, sourceImageWidth, sourceImageHeight);

        final boolean computeT3Param = isComplex && (outputSpan || outputPedestalHeight || outputRVI);
        final boolean isQuadMatrix = sourceProductType == PolBandUtils.MATRIX.C3
                || sourceProductType == PolBandUtils.MATRIX.T3
                || sourceProductType == PolBandUtils.MATRIX.FULL;

        for (int group = 0; group < srcBandList.length; group++) {
            final PolBandUtils.PolSourceBand bandList = srcBandList[group];
            try {
                // Intensity-band tiles of this group's acquisition (when in intensity mode).
                Tile hhTile = null, hvTile = null, vvTile = null, vhTile = null;
                if (intensityBandMode) {
                    final Band[] ib = groupIntensityBands[group];
                    if (ib[0] != null) hhTile = getSourceTile(ib[0], sourceRectangle);
                    if (ib[1] != null) hvTile = getSourceTile(ib[1], sourceRectangle);
                    if (ib[2] != null) vvTile = getSourceTile(ib[2], sourceRectangle);
                    if (ib[3] != null) vhTile = getSourceTile(ib[3], sourceRectangle);
                }

                final TileData[] tileDataList = new TileData[bandList.targetBands.length];
                int i = 0;
                for (Band targetBand : bandList.targetBands) {
                    final Tile targetTile = targetTiles.get(targetBand);
                    tileDataList[i++] = new TileData(targetTile, targetBand.getName(), bandList.suffix);
                }

                final Tile[] sourceTiles = new Tile[bandList.srcBands.length];
                final ProductData[] dataBuffers = new ProductData[bandList.srcBands.length];
                for (int j = 0; j < bandList.srcBands.length; j++) {
                    sourceTiles[j] = getSourceTile(bandList.srcBands[j], sourceRectangle);
                    dataBuffers[j] = sourceTiles[j].getDataBuffer();
                }
                final TileIndex srcIndex = new TileIndex(sourceTiles[0]);

                final double[][] Tr3 = new double[3][3];
                final double[][] Ti3 = new double[3][3];
                final double[][] Cr3 = new double[3][3];
                final double[][] Ci3 = new double[3][3];
                final double[][] Cr2 = new double[2][2];
                final double[][] Ci2 = new double[2][2];
                PolarimetricParameters t3param = null;

                for (int y = y0; y < maxY; ++y) {
                    trgIndex.calculateStride(y);
                    srcIndex.calculateStride(y);
                    for (int x = x0; x < maxX; ++x) {
                        final int tgtIdx = trgIndex.getIndex(x);

                        // Span / Pedestal / RVI need T3.
                        if (computeT3Param) {
                            if (useMeanMatrix) {
                                getMeanCoherencyMatrix(x, y, window.getHalfWindowSizeX(), window.getHalfWindowSizeY(),
                                        sourceImageWidth, sourceImageHeight, sourceProductType, srcIndex, dataBuffers, Tr3, Ti3);
                            } else {
                                getCoherencyMatrixT3(srcIndex.getIndex(x), sourceProductType, dataBuffers, Tr3, Ti3);
                            }
                            t3param = computePolarimetricParameters(Tr3, Ti3);
                        }

                        // Channel powers (linear intensity, sigma0-like) and (if matrix) off-diagonal correlations.
                        // RFDI, CSI, VSI, BMI, ITI, the channel ratios and the detected-input RVI are defined on
                        // linear power. (On complex quad-pol input RVI comes from the T3 eigenvalues instead.)
                        double hhPow = 0, hvPow = 0, vvPow = 0, vhPow = 0;
                        // Off-diagonal complex correlations c_HHVV, c_HHHV, c_VVVH (real, imag).
                        double rHV = 0, iHV = 0, rHH_HV = 0, iHH_HV = 0, rVV_VH = 0, iVV_VH = 0;
                        // Diagonal channel intensities for normalisation of correlations.
                        double iHH = 0, iHV2 = 0, iVV = 0, iVH = 0;

                        if (intensityBandMode) {
                            if (hhTile != null) hhPow = Math.max(0, hhTile.getSampleFloat(x, y));
                            if (hvTile != null) hvPow = Math.max(0, hvTile.getSampleFloat(x, y));
                            if (vvTile != null) vvPow = Math.max(0, vvTile.getSampleFloat(x, y));
                            if (vhTile != null) vhPow = Math.max(0, vhTile.getSampleFloat(x, y));
                        } else if (isQuadMatrix) {
                            // Get C3 (lex basis): c11=|HH|², c22=2|HV|², c33=|VV|², plus complex off-diagonals.
                            getCovarianceMatrixC3(srcIndex.getIndex(x), sourceProductType, dataBuffers, Cr3, Ci3);
                            iHH = Cr3[0][0];
                            iHV2 = Cr3[1][1] / 2.0;       // |HV|² (the C3 c22 stores 2|HV|²)
                            iVV = Cr3[2][2];
                            iVH = iHV2;                    // reciprocity
                            hhPow = Math.max(0, iHH);
                            hvPow = Math.max(0, iHV2);
                            vvPow = Math.max(0, iVV);
                            vhPow = hvPow;
                            // c12 = <HH·HV*·sqrt(2)>; c13 = <HH·VV*>; c23 = <sqrt(2)·HV·VV*>
                            // For HHHV: <HH·HV*> = c12 / sqrt(2).
                            rHH_HV = Cr3[0][1] / Math.sqrt(2.0);
                            iHH_HV = Ci3[0][1] / Math.sqrt(2.0);
                            rHV = Cr3[0][2];
                            iHV = Ci3[0][2];
                            // c23 = <sqrt(2)·HV·VV*>, so <VV·VH*> = conj(c23) / sqrt(2) (same order as the dual-pol path).
                            rVV_VH = Cr3[1][2] / Math.sqrt(2.0);
                            iVV_VH = -Ci3[1][2] / Math.sqrt(2.0);
                        } else {
                            // C2 dual-pol path: extract from C2 diagonal per active mode.
                            getCovarianceMatrixC2(srcIndex.getIndex(x), sourceProductType, dataBuffers, Cr2, Ci2);
                            switch (channelPairType) {
                                case DUAL_HH_HV:
                                    iHH = Cr2[0][0]; iHV2 = Cr2[1][1];
                                    hhPow = Math.max(0, iHH);
                                    hvPow = Math.max(0, iHV2);
                                    rHH_HV = Cr2[0][1]; iHH_HV = Ci2[0][1];
                                    break;
                                case DUAL_VH_VV:
                                    // dual-pol scatter vector is k = [S_VV, S_VH] (PolBandUtils)
                                    iVV = Cr2[0][0]; iVH = Cr2[1][1];
                                    vhPow = Math.max(0, iVH);
                                    vvPow = Math.max(0, iVV);
                                    rVV_VH = Cr2[0][1]; iVV_VH = Ci2[0][1];
                                    break;
                                case DUAL_HH_VV:
                                case C2: // permissive fallback
                                    iHH = Cr2[0][0]; iVV = Cr2[1][1];
                                    hhPow = Math.max(0, iHH);
                                    vvPow = Math.max(0, iVV);
                                    rHV = Cr2[0][1]; iHV = Ci2[0][1];
                                    break;
                                default:
                                    break;
                            }
                        }

                        for (final TileData tile : tileDataList) {
                            switch (tile.bandType) {
                                case Span:
                                    if (t3param != null) tile.dataBuffer.setElemFloatAt(tgtIdx, (float) t3param.Span);
                                    break;
                                case PedestalHeight:
                                    if (t3param != null) tile.dataBuffer.setElemFloatAt(tgtIdx, (float) t3param.PedestalHeight);
                                    break;
                                case RVI:
                                    if (t3param != null) {
                                        tile.dataBuffer.setElemFloatAt(tgtIdx, (float) t3param.RVI);
                                    } else {
                                        // Kim & van Zyl 2009: RVI = 8 sigmaHV / (sigmaHH + sigmaVV + 2 sigmaHV)
                                        tile.dataBuffer.setElemFloatAt(tgtIdx,
                                                (float) safeDiv(8 * hvPow, hhPow + vvPow + 2 * hvPow));
                                    }
                                    break;
                                case RFDI:
                                    tile.dataBuffer.setElemFloatAt(tgtIdx,
                                            (float) safeDiv(hhPow - hvPow, hhPow + hvPow));
                                    break;
                                case CSI:
                                    tile.dataBuffer.setElemFloatAt(tgtIdx,
                                            (float) safeDiv(vvPow, vvPow + hhPow));
                                    break;
                                case BMI:
                                    tile.dataBuffer.setElemFloatAt(tgtIdx, (float) ((vvPow + hhPow) / 2.0));
                                    break;
                                case VSI:
                                    tile.dataBuffer.setElemFloatAt(tgtIdx,
                                            (float) safeDiv(hvPow + vhPow, hhPow + vvPow + hvPow + vhPow));
                                    break;
                                case ITI:
                                case HHVVRatio:
                                    tile.dataBuffer.setElemFloatAt(tgtIdx, (float) safeDiv(hhPow, vvPow));
                                    break;
                                case HHHVRatio:
                                    tile.dataBuffer.setElemFloatAt(tgtIdx, (float) safeDiv(hhPow, hvPow));
                                    break;
                                case VVVHRatio:
                                    tile.dataBuffer.setElemFloatAt(tgtIdx, (float) safeDiv(vvPow, vhPow));
                                    break;
                                case Coh_HHVV:
                                    tile.dataBuffer.setElemFloatAt(tgtIdx,
                                            (float) coherence(rHV, iHV, iHH, iVV));
                                    break;
                                case Coh_HHHV:
                                    tile.dataBuffer.setElemFloatAt(tgtIdx,
                                            (float) coherence(rHH_HV, iHH_HV, iHH, iHV2));
                                    break;
                                case Coh_VVVH:
                                    tile.dataBuffer.setElemFloatAt(tgtIdx,
                                            (float) coherence(rVV_VH, iVV_VH, iVV, iVH));
                                    break;
                                case Phase_HHVV:
                                    tile.dataBuffer.setElemFloatAt(tgtIdx, (float) Math.atan2(iHV, rHV));
                                    break;
                                case Phase_HHHV:
                                    tile.dataBuffer.setElemFloatAt(tgtIdx, (float) Math.atan2(iHH_HV, rHH_HV));
                                    break;
                                case Phase_VVVH:
                                    tile.dataBuffer.setElemFloatAt(tgtIdx, (float) Math.atan2(iVV_VH, rVV_VH));
                                    break;
                            }
                        }
                    }
                }

            } catch (Throwable e) {
                OperatorUtils.catchOperatorException(getId(), e);
            }
        }
    }

    private static double safeDiv(final double num, final double den) {
        return den == 0 ? Float.NaN : num / den;
    }

    private static double coherence(final double re, final double im, final double i1, final double i2) {
        final double denom = Math.sqrt(i1 * i2);
        return denom > 0 ? Math.sqrt(re * re + im * im) / denom : Float.NaN;
    }

    private static class TileData {
        final Tile tile;
        final ProductData dataBuffer;
        final String bandName;
        final BandType bandType;

        TileData(final Tile tile, final String bandName, final String suffix) {
            this.tile = tile;
            this.dataBuffer = tile.getDataBuffer();
            this.bandName = bandName;
            // On a stack the target band carries the date suffix (e.g. Span_01Jan2020).
            final String baseName = (suffix != null && !suffix.isEmpty() && bandName.endsWith(suffix))
                    ? bandName.substring(0, bandName.length() - suffix.length()) : bandName;
            this.bandType = BandType.valueOf(baseName);
        }
    }

    /**
     * Compute general polarimetric parameters for given coherency matrix.
     */
    public static PolarimetricParameters computePolarimetricParameters(final double[][] Tr, final double[][] Ti) {

        final PolarimetricParameters parameters = new PolarimetricParameters();

        // Span = trace(T3) = |HH|^2 + 2|HV|^2 + |VV|^2.
        parameters.Span = Tr[0][0] + Tr[1][1] + Tr[2][2];
        hAAlpha.HAAlpha data = hAAlpha.computeHAAlpha(Tr, Ti);
        parameters.PedestalHeight = data.lambda3 / data.lambda1;
        // Eigenvalue RVI = 4 lambda3 / (lambda1 + lambda2 + lambda3).
        parameters.RVI = 4.0 * data.lambda3 / (data.lambda1 + data.lambda2 + data.lambda3);

        return parameters;
    }

    public static class PolarimetricParameters {
        public double Span;
        public double PedestalHeight;
        public double RVI;
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(PolarimetricParametersOp.class);
        }
    }
}
