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
package eu.esa.sar.insar.gpf;

import com.bc.ceres.core.ProgressMonitor;
import org.apache.commons.math3.util.FastMath;
import eu.esa.sar.commons.Sentinel1Utils;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.dataop.dem.ElevationModel;
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
import org.esa.snap.core.util.StringUtils;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.dem.dataio.DEMFactory;
import org.esa.snap.dem.dataio.FileElevationModel;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.PosVector;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.eo.GeoUtils;
import org.esa.snap.engine_utilities.gpf.*;
import org.esa.snap.engine_utilities.util.Maths;
import org.jblas.*;
import org.jlinda.core.*;
import org.jlinda.core.Point;
import org.jlinda.core.Window;
import org.jlinda.core.geom.DemTile;
import org.jlinda.core.geom.TopoPhase;
import org.jlinda.core.utils.*;

import javax.media.jai.BorderExtender;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;


@OperatorMetadata(alias = "Interferogram",
        category = "Radar/Interferometric/Products",
        authors = "Petar Marinkovic, Jun Lu",
        version = "1.0",
        description = "Compute interferograms from stack of coregistered S-1 images", internal = false)
public class InterferogramOp extends Operator {
    @SourceProduct
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(defaultValue = "true", label = "Subtract flat-earth phase")
    private boolean subtractFlatEarthPhase = true;

    @Parameter(valueSet = {"1", "2", "3", "4", "5", "6", "7", "8"},
            description = "Order of 'Flat earth phase' polynomial",
            defaultValue = "5",
            label = "Degree of \"Flat Earth\" polynomial")
    private int srpPolynomialDegree = 5;

    @Parameter(valueSet = {"301", "401", "501", "601", "701", "801", "901", "1001"},
            description = "Number of points for the 'flat earth phase' polynomial estimation",
            defaultValue = "501",
            label = "Number of \"Flat Earth\" estimation points")
    private int srpNumberPoints = 501;

    @Parameter(valueSet = {"1", "2", "3", "4", "5"},
            description = "Degree of orbit (polynomial) interpolator",
            defaultValue = "3",
            label = "Orbit interpolation degree")
    private int orbitDegree = 3;

    @Parameter(defaultValue = "true", label = "Output coherence estimation")
    private boolean includeCoherence = true;

    @Parameter(description = "Size of coherence estimation window in Azimuth direction",
            defaultValue = "10",
            label = "Coherence Azimuth Window Size")
    private int cohWinAz = 10;

    @Parameter(description = "Size of coherence estimation window in Range direction",
            defaultValue = "10",
            label = "Coherence Range Window Size")
    private int cohWinRg = 10;

    @Parameter(description = "Use ground square pixel", defaultValue = "true", label = "Square Pixel")
    private Boolean squarePixel = true;

    @Parameter(defaultValue="false", label="Subtract topographic phase")
    private boolean subtractTopographicPhase = false;
    /*
        @Parameter(interval = "(1, 10]",
                description = "Degree of orbit interpolation polynomial",
                defaultValue = "3",
                label = "Orbit Interpolation Degree")
        private int orbitDegree = 3;
    */
    @Parameter(description = "The digital elevation model.",
            defaultValue = "Copernicus 30m Global DEM",
            label = "Digital Elevation Model")
    private String demName = "Copernicus 30m Global DEM";

    @Parameter(label = "External DEM")
    private File externalDEMFile = null;

    @Parameter(label = "DEM No Data Value", defaultValue = "0")
    private double externalDEMNoDataValue = 0;

    @Parameter(label = "External DEM Apply EGM", defaultValue = "true")
    private Boolean externalDEMApplyEGM = true;

    @Parameter(label = "Tile Extension [%]",
            description = "Define extension of tile for DEM simulation (optimization parameter).",
            defaultValue = "100")
    private String tileExtensionPercent = "100";

    @Parameter(defaultValue = "false", label = "Output Flat Earth Phase")
    private boolean outputFlatEarthPhase = false;

    @Parameter(defaultValue = "false", label = "Output Topographic Phase")
    private boolean outputTopoPhase = false;

    @Parameter(defaultValue = "false", label = "Output Elevation")
    private boolean outputElevation = false;

    @Parameter(defaultValue = "false", label = "Output Lat/Lon")
    private boolean outputLatLon = false;

    // flat_earth_polynomial container
    private final Map<String, DoubleMatrix> flatEarthPolyMap = new HashMap<>();
    private boolean flatEarthEstimated = false;

    // source
    private final Map<String, CplxContainer> referenceMap = new HashMap<>();
    private final Map<String, CplxContainer> secondaryMap = new HashMap<>();

    private String[] polarisations;
    private String[] subswaths = new String[]{""};

    // target
    private final Map<String, ProductContainer> targetMap = new HashMap<>();

    // operator tags
    private String productTag = "ifg";
    private int sourceImageWidth;
    private int sourceImageHeight;

    private ElevationModel dem = null;
    private double demNoDataValue = 0;
    private double demSamplingLat;
    private double demSamplingLon;

    private boolean isTOPSARBurstProduct = false;
    private Sentinel1Utils su = null;
    private Sentinel1Utils.SubSwathInfo[] subSwath = null;
    private int numSubSwaths = 0;
    private org.jlinda.core.Point[] refSceneCentreXYZ = null;
    private int subSwathIndex = 0;
    private MetadataElement refRoot = null;
    private boolean subtractETADPhase = false;
    private boolean performHeightCorrection = false;
    private boolean etadPhaseStatsComputed = false;
    private Band refETADPhaseBand = null;
    private Band refETADHeightBand = null;
    private Band secETADPhaseBand = null;
    private Band secETADHeightBand = null;
    private Band secETADGradientBand = null;

    // GSLC interferogram mode: input is geocoded complex (phase-flattened) stack
    private boolean isGSLCProduct = false;
    private Band[] gslcReferenceI, gslcReferenceQ, gslcSecondaryI, gslcSecondaryQ;
    private Band[] gslcTargetI, gslcTargetQ, gslcTargetCoh;

    private static final boolean CREATE_VIRTUAL_BAND = true;
    private static final boolean OUTPUT_ETAD_IFG = true;
    private static final String PRODUCT_SUFFIX = "_Ifg";
    private static final String FLAT_EARTH_PHASE = "flat_earth_phase";
    private static final String TOPO_PHASE = "topo_phase";
    private static final String COHERENCE = "coherence";
    private static final String ELEVATION = "elevation";
    private static final String LATITUDE = " orthorectifiedLat";
    private static final String LONGITUDE = "orthorectifiedLon";
    private static final String ETAD_PHASE_CORRECTION = "etadPhaseCorrection";
    private static final String ETAD_HEIGHT = "etadHeight";
    private static final String ETAD_GRADIENT = "etadGradient";
    private static final String REFERENCE_TAG = "ref";
    private static final String SECONDARY_TAG = "sec";
    private static final String LEGACY_REFERENCE_TAG = "mst";
    private static final String LEGACY_SECONDARY_TAG = "slv";
    private static final String ETAD = "ETAD";
    private static final String ETAD_IFG = "etad_ifg";

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
            // Check if this is a GSLC (geocoded complex) stack
            final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);
            if (absRoot != null && absRoot.getAttributeInt(AbstractMetadata.is_terrain_corrected, 0) == 1) {
                isGSLCProduct = true;
                initializeGSLC();
                return;
            }

            if(absRoot.containsAttribute("multireference_split")){
                refRoot = StackUtils.findSecondaryMetadataRoot(sourceProduct).getElementAt(0);
            } else{
                refRoot = absRoot;
            }

            checkUserInput();

            constructSourceMetadata();
            constructTargetMetadata();

            if (subtractTopographicPhase) {
                defineDEM();
            }

            checkETADCorrection();

            createTargetProduct();

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    /**
     * Initialize for GSLC (geocoded complex) interferogram.
     * GSLC products are already phase-flattened and geocoded — the interferogram is simply
     * the complex conjugate multiplication of primary and secondary, with no flat-earth or
     * topographic phase subtraction needed.
     */
    private void initializeGSLC() throws Exception {
        sourceImageWidth = sourceProduct.getSceneRasterWidth();
        sourceImageHeight = sourceProduct.getSceneRasterHeight();

        // Find complex band pairs: reference (ref) and secondary (sec)
        final List<Band> refIBands = new ArrayList<>();
        final List<Band> refQBands = new ArrayList<>();
        final List<Band> secIBands = new ArrayList<>();
        final List<Band> secQBands = new ArrayList<>();

        for (Band band : sourceProduct.getBands()) {
            final String name = band.getName().toLowerCase();
            final String unit = band.getUnit();
            if (unit == null) continue;

            if (unit.equals(Unit.REAL)) {
                if (name.contains(REFERENCE_TAG) || name.contains(LEGACY_REFERENCE_TAG)) {
                    refIBands.add(band);
                } else if (name.contains(SECONDARY_TAG) || name.contains(LEGACY_SECONDARY_TAG)) {
                    secIBands.add(band);
                }
            } else if (unit.equals(Unit.IMAGINARY)) {
                if (name.contains(REFERENCE_TAG) || name.contains(LEGACY_REFERENCE_TAG)) {
                    refQBands.add(band);
                } else if (name.contains(SECONDARY_TAG) || name.contains(LEGACY_SECONDARY_TAG)) {
                    secQBands.add(band);
                }
            }
        }

        if (refIBands.isEmpty() || refQBands.isEmpty() || secIBands.isEmpty() || secQBands.isEmpty()) {
            throw new OperatorException("GSLC interferogram requires reference and secondary complex (I/Q) bands. " +
                    "Band names must contain 'ref' and 'sec' tags.");
        }

        final int numPairs = Math.min(refIBands.size(), secIBands.size());
        gslcReferenceI = refIBands.subList(0, numPairs).toArray(new Band[0]);
        gslcReferenceQ = refQBands.subList(0, numPairs).toArray(new Band[0]);
        gslcSecondaryI = secIBands.subList(0, numPairs).toArray(new Band[0]);
        gslcSecondaryQ = secQBands.subList(0, numPairs).toArray(new Band[0]);

        // Create target product
        targetProduct = new Product(sourceProduct.getName() + PRODUCT_SUFFIX,
                sourceProduct.getProductType(), sourceImageWidth, sourceImageHeight);
        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        gslcTargetI = new Band[numPairs];
        gslcTargetQ = new Band[numPairs];
        gslcTargetCoh = includeCoherence ? new Band[numPairs] : null;

        for (int p = 0; p < numPairs; p++) {
            // Derive tag from reference band name
            final String refName = gslcReferenceI[p].getName();
            final String suffix = refName.substring(refName.indexOf('_'));
            final String tag = suffix.replace("_ref", "").replace("_mst", "");

            final String iBandName = "i_" + productTag + tag;
            gslcTargetI[p] = targetProduct.addBand(iBandName, ProductData.TYPE_FLOAT32);
            gslcTargetI[p].setUnit(Unit.REAL);

            final String qBandName = "q_" + productTag + tag;
            gslcTargetQ[p] = targetProduct.addBand(qBandName, ProductData.TYPE_FLOAT32);
            gslcTargetQ[p].setUnit(Unit.IMAGINARY);

            if (CREATE_VIRTUAL_BAND) {
                ReaderUtils.createVirtualIntensityBand(targetProduct, gslcTargetI[p], gslcTargetQ[p], '_' + productTag + tag);
                Band phaseBand = ReaderUtils.createVirtualPhaseBand(targetProduct, gslcTargetI[p], gslcTargetQ[p], '_' + productTag + tag);
                targetProduct.setQuicklookBandName(phaseBand.getName());
            }

            if (includeCoherence) {
                final String cohBandName = "coh" + tag;
                gslcTargetCoh[p] = targetProduct.addBand(cohBandName, ProductData.TYPE_FLOAT32);
                gslcTargetCoh[p].setUnit(Unit.COHERENCE);
                gslcTargetCoh[p].setNoDataValueUsed(true);
                gslcTargetCoh[p].setNoDataValue(0);
            }
        }
    }

    /**
     * Compute interferogram for GSLC (geocoded complex) products.
     * No flat-earth or topographic phase subtraction — just complex conjugate multiplication.
     */
    private void computeTileStackForGSLC(final Map<Band, Tile> targetTileMap, final Rectangle targetRectangle) {
        try {
            final int x0 = targetRectangle.x;
            final int y0 = targetRectangle.y;
            final int w = targetRectangle.width;
            final int h = targetRectangle.height;

            // Extended rectangle for coherence window
            final int cohx0 = x0 - (cohWinRg - 1) / 2;
            final int cohy0 = y0 - (cohWinAz - 1) / 2;
            final int cohw = w + cohWinRg - 1;
            final int cohh = h + cohWinAz - 1;
            final Rectangle cohRect = new Rectangle(cohx0, cohy0, cohw, cohh);

            for (int p = 0; p < gslcReferenceI.length; p++) {
                final Tile refTileI = getSourceTile(gslcReferenceI[p], targetRectangle);
                final Tile refTileQ = getSourceTile(gslcReferenceQ[p], targetRectangle);
                final Tile secTileI = getSourceTile(gslcSecondaryI[p], targetRectangle);
                final Tile secTileQ = getSourceTile(gslcSecondaryQ[p], targetRectangle);

                final Tile tgtTileI = targetTileMap.get(gslcTargetI[p]);
                final Tile tgtTileQ = targetTileMap.get(gslcTargetQ[p]);

                if (tgtTileI == null && tgtTileQ == null) continue;

                final ProductData tgtDataI = tgtTileI != null ? tgtTileI.getDataBuffer() : null;
                final ProductData tgtDataQ = tgtTileQ != null ? tgtTileQ.getDataBuffer() : null;

                // Interferogram: primary * conj(secondary)
                // (mI + j*mQ) * (sI - j*sQ) = (mI*sI + mQ*sQ) + j*(mQ*sI - mI*sQ)
                for (int y = y0; y < y0 + h; y++) {
                    for (int x = x0; x < x0 + w; x++) {
                        final double mI = refTileI.getSampleDouble(x, y);
                        final double mQ = refTileQ.getSampleDouble(x, y);
                        final double sI = secTileI.getSampleDouble(x, y);
                        final double sQ = secTileQ.getSampleDouble(x, y);

                        final int idx = tgtTileI.getDataBufferIndex(x, y);
                        if (tgtDataI != null) tgtDataI.setElemDoubleAt(idx, mI * sI + mQ * sQ);
                        if (tgtDataQ != null) tgtDataQ.setElemDoubleAt(idx, mQ * sI - mI * sQ);
                    }
                }

                // Coherence estimation
                if (includeCoherence && gslcTargetCoh != null) {
                    final Tile cohTgtTile = targetTileMap.get(gslcTargetCoh[p]);
                    if (cohTgtTile != null) {
                        computeGSLCCoherence(cohRect, targetRectangle,
                                gslcReferenceI[p], gslcReferenceQ[p], gslcSecondaryI[p], gslcSecondaryQ[p],
                                cohTgtTile);
                    }
                }
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private void computeGSLCCoherence(final Rectangle cohRect, final Rectangle targetRect,
                                       final Band refBandI, final Band refBandQ,
                                       final Band secBandI, final Band secBandQ,
                                       final Tile cohTile) {

        final Tile refI = getSourceTile(refBandI, cohRect);
        final Tile refQ = getSourceTile(refBandQ, cohRect);
        final Tile secI = getSourceTile(secBandI, cohRect);
        final Tile secQ = getSourceTile(secBandQ, cohRect);

        final ProductData cohData = cohTile.getDataBuffer();
        final int halfAz = (cohWinAz - 1) / 2;
        final int halfRg = (cohWinRg - 1) / 2;

        final int x0 = targetRect.x;
        final int y0 = targetRect.y;
        final int w = targetRect.width;
        final int h = targetRect.height;

        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                double sumReal = 0, sumImag = 0, sumRef = 0, sumSec = 0;

                for (int wy = y - halfAz; wy <= y + halfAz; wy++) {
                    for (int wx = x - halfRg; wx <= x + halfRg; wx++) {
                        final double mi = refI.getSampleDouble(wx, wy);
                        final double mq = refQ.getSampleDouble(wx, wy);
                        final double si = secI.getSampleDouble(wx, wy);
                        final double sq = secQ.getSampleDouble(wx, wy);

                        // cross-correlation: ref * conj(sec)
                        sumReal += mi * si + mq * sq;
                        sumImag += mq * si - mi * sq;

                        // auto-correlations
                        sumRef += mi * mi + mq * mq;
                        sumSec += si * si + sq * sq;
                    }
                }

                final double crossMag = Math.sqrt(sumReal * sumReal + sumImag * sumImag);
                final double denom = Math.sqrt(sumRef * sumSec);
                final double coh = (denom > 0) ? crossMag / denom : 0.0;

                cohData.setElemDoubleAt(cohTile.getDataBufferIndex(x, y), coh);
            }
        }
    }

    private void checkUserInput() {

        try {
            final InputProductValidator validator = new InputProductValidator(sourceProduct);
            validator.checkIfSARProduct();
            validator.checkIfCoregisteredStack();
            validator.checkIfSLC();
            isTOPSARBurstProduct = validator.isTOPSARProduct() && !validator.isDebursted();

            if (isTOPSARBurstProduct) {
                final String mProcSysId = refRoot.getAttributeString(AbstractMetadata.ProcessingSystemIdentifier);
                final float mVersion = Float.parseFloat(mProcSysId.substring(mProcSysId.lastIndexOf(' ')));

                MetadataElement secondaryElem = StackUtils.findSecondaryMetadataRoot(sourceProduct);
                if (secondaryElem == null) {
                    secondaryElem = sourceProduct.getMetadataRoot().getElement("Slave Metadata");
                }
                MetadataElement[] secondaryRoot = secondaryElem.getElements();
                for (MetadataElement secRoot : secondaryRoot) {
                    final String sProcSysId = secRoot.getAttributeString(AbstractMetadata.ProcessingSystemIdentifier);
                    final float sVersion = Float.parseFloat(sProcSysId.substring(sProcSysId.lastIndexOf(' ')));
                    if ((mVersion < 2.43 && sVersion >= 2.43 && refRoot.getAttribute("EAP Correction") == null) ||
                            (sVersion < 2.43 && mVersion >= 2.43 && secRoot.getAttribute("EAP Correction") == null)) {
                        throw new OperatorException("Source products cannot be InSAR pairs: one is EAP phase corrected" +
                                " and the other is not. Apply EAP Correction.");
                    }
                }

                su = new Sentinel1Utils(sourceProduct);
                subswaths = su.getSubSwathNames();
                subSwath = su.getSubSwath();
                numSubSwaths = su.getNumOfSubSwath();
                subSwathIndex = 1; // subSwathIndex is always 1 because of split product
            }

            final String[] polarisationsInBandNames = OperatorUtils.getPolarisations(sourceProduct);
            polarisations = getPolsSharedByRefSec(sourceProduct, polarisationsInBandNames);

            sourceImageWidth = sourceProduct.getSceneRasterWidth();
            sourceImageHeight = sourceProduct.getSceneRasterHeight();
        } catch (Exception e) {
            throw new OperatorException(e);
        }
    }

    public static String[] getPolsSharedByRefSec(final Product sourceProduct, final String[] polarisationsInBandNames) {

        final List<String> polarisations = new ArrayList<>();

        for (String pol : polarisationsInBandNames) {
            if ((checkPolarisation(sourceProduct, REFERENCE_TAG, pol) || checkPolarisation(sourceProduct, LEGACY_REFERENCE_TAG, pol)) &&
                    (checkPolarisation(sourceProduct, SECONDARY_TAG, pol) || checkPolarisation(sourceProduct, LEGACY_SECONDARY_TAG, pol))) {
                polarisations.add(pol);
            }
        }

        if (!polarisations.isEmpty()) {
            return polarisations.toArray(new String[0]);
        } else {
            return new String[]{""};
        }
    }

    private static boolean checkPolarisation(final Product product, final String tag, final String polarisation) {

        for (String name:product.getBandNames()) {
            if (name.toLowerCase().contains(tag.toLowerCase()) &&
                    name.toLowerCase().contains(polarisation.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private void getRefApproxSceneCentreXYZ() {

        final int numOfBursts = subSwath[subSwathIndex - 1].numOfBursts;
        refSceneCentreXYZ = new Point[numOfBursts];

        for (int b = 0; b < numOfBursts; b++) {
            final double firstLineTime = subSwath[subSwathIndex - 1].burstFirstLineTime[b];
            final double lastLineTime = subSwath[subSwathIndex - 1].burstLastLineTime[b];
            final double slrTimeToFirstPixel = subSwath[subSwathIndex - 1].slrTimeToFirstPixel;
            final double slrTimeToLastPixel = subSwath[subSwathIndex - 1].slrTimeToLastPixel;
            final double latUL = su.getLatitude(firstLineTime, slrTimeToFirstPixel, subSwathIndex);
            final double latUR = su.getLatitude(firstLineTime, slrTimeToLastPixel, subSwathIndex);
            final double latLL = su.getLatitude(lastLineTime, slrTimeToFirstPixel, subSwathIndex);
            final double latLR = su.getLatitude(lastLineTime, slrTimeToLastPixel, subSwathIndex);

            final double lonUL = su.getLongitude(firstLineTime, slrTimeToFirstPixel, subSwathIndex);
            final double lonUR = su.getLongitude(firstLineTime, slrTimeToLastPixel, subSwathIndex);
            final double lonLL = su.getLongitude(lastLineTime, slrTimeToFirstPixel, subSwathIndex);
            final double lonLR = su.getLongitude(lastLineTime, slrTimeToLastPixel, subSwathIndex);

            final double lat = (latUL + latUR + latLL + latLR) / 4.0;
            final double lon = (lonUL + lonUR + lonLL + lonLR) / 4.0;

            final PosVector refSceneCenter = new PosVector();
            GeoUtils.geo2xyzWGS84(lat, lon, 0.0, refSceneCenter);
            refSceneCentreXYZ[b] = new Point(refSceneCenter.toArray());
        }
    }

    private void constructFlatEarthPolynomials() throws Exception {

        for (String keyReference : referenceMap.keySet()) {

            CplxContainer reference = referenceMap.get(keyReference);

            for (String keySecondary : secondaryMap.keySet()) {

                CplxContainer secondary = secondaryMap.get(keySecondary);

                flatEarthPolyMap.put(secondary.name, estimateFlatEarthPolynomial(
                        reference.metaData, reference.orbit, secondary.metaData, secondary.orbit, sourceImageWidth,
                        sourceImageHeight, srpPolynomialDegree, srpNumberPoints, sourceProduct));
            }
        }
    }

    private void constructFlatEarthPolynomialsForTOPSARProduct() throws Exception {

        for (String keyReference : referenceMap.keySet()) {

            CplxContainer reference = referenceMap.get(keyReference);

            for (String keySecondary : secondaryMap.keySet()) {

                CplxContainer secondary = secondaryMap.get(keySecondary);

                for (int s = 0; s < numSubSwaths; s++) {

                    final int numBursts = subSwath[s].numOfBursts;

                    for (int b = 0; b < numBursts; b++) {

                        final String polynomialName = secondary.name + '_' + s + '_' + b;

                        flatEarthPolyMap.put(polynomialName, estimateFlatEarthPolynomial(
                                reference, secondary, s + 1, b, refSceneCentreXYZ, orbitDegree, srpPolynomialDegree,
                                srpNumberPoints, subSwath, su));
                    }
                }
            }
        }
    }

    private void constructTargetMetadata() {

        for (String keyReference : referenceMap.keySet()) {

            CplxContainer reference = referenceMap.get(keyReference);

            for (String keySecondary : secondaryMap.keySet()) {
                final CplxContainer secondary = secondaryMap.get(keySecondary);

                if (reference.polarisation == null || reference.polarisation.equals(secondary.polarisation)) {
                    // generate name for product bands
                    final String productName = keyReference + '_' + keySecondary;

                    final ProductContainer product = new ProductContainer(productName, reference, secondary, true);

                    // put ifg-product bands into map
                    targetMap.put(productName, product);
                }
            }
        }
    }

    private void constructSourceMetadata() throws Exception {

        // get sourceReference & sourceSecondary MetadataElement

        // organize metadata
        // put sourceReference metadata into the referenceMap
        metaMapPut(REFERENCE_TAG, refRoot, sourceProduct, referenceMap);

        // put sourceSecondary metadata into secondaryMap
        MetadataElement secondaryElem = StackUtils.findSecondaryMetadataRoot(sourceProduct);
        MetadataElement[] secondaryRoot = secondaryElem.getElements();
        for (MetadataElement meta : secondaryRoot) {
            if (!meta.getName().equals(AbstractMetadata.ORIGINAL_PRODUCT_METADATA))
                metaMapPut(SECONDARY_TAG, meta, sourceProduct, secondaryMap);
        }
    }

    private void metaMapPut(final String tag,
                            final MetadataElement root,
                            final Product product,
                            final Map<String, CplxContainer> map) throws Exception {

        for (String swath : subswaths) {
            final String subswath = swath.isEmpty() ? "" : '_' + swath.toUpperCase();

            for (String polarisation : polarisations) {
                final String pol = polarisation.isEmpty() ? "" : '_' + polarisation.toUpperCase();

                // map key: ORBIT NUMBER
                String mapKey = root.getAttributeInt(AbstractMetadata.ABS_ORBIT) + subswath + pol;

                // metadata: construct classes and define bands
                final String date = OperatorUtils.getAcquisitionDate(root);
                final SLCImage meta = new SLCImage(root, product);
                final Orbit orbit = new Orbit(root, orbitDegree);

                // TODO: resolve multilook factors
                meta.setMlAz(1);
                meta.setMlRg(1);

                Band bandReal = null;
                Band bandImag = null;
                for (String bandName : product.getBandNames()) {
                    final boolean isRefTag = tag.equals(REFERENCE_TAG);
                    final boolean matchesRef = isRefTag && (bandName.contains(REFERENCE_TAG) || bandName.contains(LEGACY_REFERENCE_TAG));
                    final boolean matchesSec = !isRefTag && ((bandName.contains(tag) || bandName.contains(LEGACY_SECONDARY_TAG)) && bandName.contains(date));
                    if (matchesRef || matchesSec) {
                        if (subswath.isEmpty() || bandName.contains(subswath)) {
                            if (pol.isEmpty() || bandName.contains(pol)) {
                                final Band band = product.getBand(bandName);
                                if (BandUtilsDoris.isBandReal(band)) {
                                    bandReal = band;
                                } else if (BandUtilsDoris.isBandImag(band)) {
                                    bandImag = band;
                                }
                            }
                        }
                    }
                }
                if(bandReal != null && bandImag != null) {
                    map.put(mapKey, new CplxContainer(date, meta, orbit, bandReal, bandImag));
                }
            }
        }
    }

    private void checkETADCorrection() {

        if (isTOPSARBurstProduct) {
            boolean hasRefETADPhaseTPG = false;
            boolean hasRefETADHeightTPG = false;
            boolean hasSecETADPhaseTPG = false;
            boolean hasSecETADHeightTPG = false;
            boolean hasSecETADGradientTPG = false;
            final TiePointGrid[] tpgs = sourceProduct.getTiePointGrids();
            for (TiePointGrid tpg : tpgs) {
                final String tpgName = tpg.getName();
                if (tpgName.startsWith(ETAD_PHASE_CORRECTION) && tpgName.contains(REFERENCE_TAG)) {
                    hasRefETADPhaseTPG = true;
                } else if (tpgName.startsWith(ETAD_HEIGHT) && tpgName.contains(REFERENCE_TAG)) {
                    hasRefETADHeightTPG = true;
                } else if (tpgName.startsWith(ETAD_PHASE_CORRECTION) && tpgName.contains(SECONDARY_TAG)) {
                    hasSecETADPhaseTPG = true;
                } else if (tpgName.startsWith(ETAD_HEIGHT) && tpgName.contains(SECONDARY_TAG)) {
                    hasSecETADHeightTPG = true;
                } else if (tpgName.startsWith(ETAD_GRADIENT) && tpgName.contains(SECONDARY_TAG)) {
                    hasSecETADGradientTPG = true;
                }
            }
            subtractETADPhase = hasRefETADPhaseTPG & hasSecETADPhaseTPG;
            performHeightCorrection = hasRefETADHeightTPG & hasSecETADHeightTPG & hasSecETADGradientTPG;

        } else {

            boolean hasRefETADPhaseBand = false;
            boolean hasRefETADHeightBand = false;
            boolean hasSecETADPhaseBand = false;
            boolean hasSecETADHeightBand = false;
            boolean hasSecETADGradientBand = false;
            for (Band band : sourceProduct.getBands()) {
                final String bandName = band.getName();
                if (bandName.contains(ETAD_PHASE_CORRECTION) && bandName.contains(REFERENCE_TAG)) {
                    hasRefETADPhaseBand = true;
                    refETADPhaseBand = band;
                }
                if (bandName.contains(ETAD_HEIGHT) && bandName.contains(REFERENCE_TAG)) {
                    hasRefETADHeightBand = true;
                    refETADHeightBand = band;
                }
                if (bandName.contains(ETAD_PHASE_CORRECTION) && bandName.contains(SECONDARY_TAG)) {
                    hasSecETADPhaseBand = true;
                    secETADPhaseBand = band;
                }
                if (bandName.contains(ETAD_HEIGHT) && bandName.contains(SECONDARY_TAG)) {
                    hasSecETADHeightBand = true;
                    secETADHeightBand = band;
                }
                if (bandName.contains(ETAD_GRADIENT) && bandName.contains(SECONDARY_TAG)) {
                    hasSecETADGradientBand = true;
                    secETADGradientBand = band;
                }
            }
            subtractETADPhase = hasRefETADPhaseBand & hasSecETADPhaseBand;
            performHeightCorrection = hasRefETADHeightBand & hasSecETADHeightBand & hasSecETADGradientBand;
        }
    }
/*
    private synchronized void computeETADPhaseStatistics() {

        if (etadPhaseStatsComputed) return;

        final double refNoDataValue = refETADPhaseBand.getNoDataValue();
        final double secNoDataValue = secETADPhaseBand.getNoDataValue();
        final int w = refETADPhaseBand.getRasterWidth();
        final int h = refETADPhaseBand.getRasterHeight();
        final int rgStep = w / 407;
        final int azStep = h / 108;

        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        double sum = 0.0;
        double sum2 = 0.0;
        int count = 0;
        for (int y = azStep/2; y < h; y += azStep) {
            for (int x = rgStep/2; x < w; x += rgStep) {
                final double refETADCorr = getPixelValue(x, y, refETADPhaseBand);
                final double secETADCorr = getPixelValue(x, y, secETADPhaseBand);

                if (refETADCorr == refNoDataValue || secETADCorr == secNoDataValue) {
                    continue;
                }

                final double diffPhase = refETADCorr - secETADCorr;
                if (min > diffPhase) {
                    min = diffPhase;
                }
                if (max < diffPhase) {
                    max = diffPhase;
                }
                sum += diffPhase;
                sum2 += diffPhase * diffPhase;
                count++;
            }
        }

        double mean = 0.0, std = 0.0;
        if (count > 0) {
            mean = sum / count;
            std = Math.sqrt(sum2 / count  - mean * mean);
        }

        final MetadataElement absTgt = AbstractMetadata.getAbstractedMetadata(targetProduct);
        MetadataElement etadElem = absTgt.getElement(ETAD);
        if (etadElem == null) {
            etadElem = new MetadataElement(ETAD);
            absTgt.addElement(etadElem);
        }

        addAttrib(etadElem, "min", min);
        addAttrib(etadElem, "max", max);
        addAttrib(etadElem, "mean", mean);
        addAttrib(etadElem, "std", std);

        etadPhaseStatsComputed = true;
    }*/

    private static void addAttrib(final MetadataElement elem, final String tag, final double value) {
        final MetadataAttribute attrib = new MetadataAttribute(tag, ProductData.TYPE_FLOAT32);
        attrib.getData().setElemDouble(value);
        elem.addAttribute(attrib);
    }

    private double getPixelValue(final int x, final int y, final Band band) {

        final Rectangle srcRect = new Rectangle(x, y, 2, 2);
        final Tile tile = getSourceTile(band, srcRect);
        final ProductData data = tile.getDataBuffer();
        final TileIndex index = new TileIndex(tile);
        index.calculateStride(y);
        return data.getElemDoubleAt(index.getIndex(x));
    }

    private void createTargetProduct() throws Exception {

        // construct target product
        targetProduct = new Product(sourceProduct.getName() + PRODUCT_SUFFIX,
                                    sourceProduct.getProductType(),
                                    sourceProduct.getSceneRasterWidth(),
                                    sourceProduct.getSceneRasterHeight());

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);
        for (String key : targetMap.keySet()) {
            final List<String> targetBandNames = new ArrayList<>();

            final ProductContainer container = targetMap.get(key);
            final CplxContainer reference = container.sourceRef;
            final CplxContainer secondary = container.sourceSec;

            final String subswath = reference.subswath.isEmpty() ? "" : '_' + reference.subswath.toUpperCase();
            final String pol = getPolarisationTag(reference);
            final String tag = subswath + pol + '_' + reference.date + '_' + secondary.date;
            final String targetBandName_I = "i_" + productTag + tag;
            final Band iBand = targetProduct.addBand(targetBandName_I, ProductData.TYPE_FLOAT32);
            container.addBand(Unit.REAL, iBand.getName());
            iBand.setUnit(Unit.REAL);
            targetBandNames.add(iBand.getName());

            final String targetBandName_Q = "q_" + productTag + tag;
            final Band qBand = targetProduct.addBand(targetBandName_Q, ProductData.TYPE_FLOAT32);
            container.addBand(Unit.IMAGINARY, qBand.getName());
            qBand.setUnit(Unit.IMAGINARY);
            targetBandNames.add(qBand.getName());

            if (CREATE_VIRTUAL_BAND) {
                final String countStr = '_' + productTag + tag;
                ReaderUtils.createVirtualIntensityBand(targetProduct,
                        targetProduct.getBand(targetBandName_I), targetProduct.getBand(targetBandName_Q), countStr);

                Band phaseBand = ReaderUtils.createVirtualPhaseBand(targetProduct,
                        targetProduct.getBand(targetBandName_I), targetProduct.getBand(targetBandName_Q), countStr);

                targetProduct.setQuicklookBandName(phaseBand.getName());
                phaseBand.setNoDataValueUsed(true);
                targetBandNames.add(phaseBand.getName());
            }

            if (includeCoherence) {
                final String targetBandCoh = "coh" + tag;
                final Band coherenceBand = targetProduct.addBand(targetBandCoh, ProductData.TYPE_FLOAT32);
                coherenceBand.setNoDataValueUsed(true);
                coherenceBand.setNoDataValue(reference.realBand.getNoDataValue());
                container.addBand(COHERENCE, coherenceBand.getName());
                coherenceBand.setUnit(Unit.COHERENCE);
                targetBandNames.add(coherenceBand.getName());
            }

            if (subtractTopographicPhase && outputTopoPhase) {
                final String targetBandTgp = "topo" + tag;
                final Band tgpBand = targetProduct.addBand(targetBandTgp, ProductData.TYPE_FLOAT32);
                container.addBand(TOPO_PHASE, tgpBand.getName());
                tgpBand.setUnit(Unit.PHASE);
                targetBandNames.add(tgpBand.getName());
            }

            if (subtractFlatEarthPhase && outputFlatEarthPhase) {
                final String targetBandFep = "fep" + tag;
                final Band fepBand = targetProduct.addBand(targetBandFep, ProductData.TYPE_FLOAT32);
                container.addBand(FLAT_EARTH_PHASE, fepBand.getName());
                fepBand.setUnit(Unit.PHASE);
                targetBandNames.add(fepBand.getName());
            }

            if (subtractTopographicPhase && outputElevation && targetProduct.getBand("elevation") == null) {
                final Band elevBand = targetProduct.addBand("elevation", ProductData.TYPE_FLOAT32);
                elevBand.setNoDataValueUsed(true);
                elevBand.setNoDataValue(demNoDataValue);
                container.addBand(ELEVATION, elevBand.getName());
                elevBand.setUnit(Unit.METERS);
                targetBandNames.add(elevBand.getName());
            }

            if (subtractTopographicPhase && outputLatLon && targetProduct.getBand("orthorectifiedLat") == null) {
                // add latitude band
                final Band latBand = targetProduct.addBand("orthorectifiedLat", ProductData.TYPE_FLOAT32);
                latBand.setNoDataValueUsed(true);
                latBand.setNoDataValue(Double.NaN);
                container.addBand(LATITUDE, latBand.getName());
                latBand.setUnit(Unit.DEGREES);
                targetBandNames.add(latBand.getName());
            }

            if (subtractTopographicPhase && outputLatLon && targetProduct.getBand("orthorectifiedLon") == null) {
                // add longitude band
                final Band lonBand = targetProduct.addBand("orthorectifiedLon", ProductData.TYPE_FLOAT32);
                lonBand.setNoDataValueUsed(true);
                lonBand.setNoDataValue(Double.NaN);
                container.addBand(LONGITUDE, lonBand.getName());
                lonBand.setUnit(Unit.DEGREES);
                targetBandNames.add(lonBand.getName());
            }

            if (subtractETADPhase && OUTPUT_ETAD_IFG) {
                final String targetBandEtad = ETAD_IFG + tag;
                final Band etadIfgBand = targetProduct.addBand(targetBandEtad, ProductData.TYPE_FLOAT32);
                container.addBand(ETAD_IFG, etadIfgBand.getName());
                etadIfgBand.setUnit(Unit.PHASE);
                targetBandNames.add(etadIfgBand.getName());
            }

            String secProductName = StackUtils.findOriginalSecondaryProductName(sourceProduct, container.sourceSec.realBand);
            StackUtils.saveSecondaryProductBandNames(targetProduct, secProductName,
                                                 targetBandNames.toArray(new String[0]));
        }

        for(String bandName : sourceProduct.getBandNames()) {
            if(bandName.startsWith("elevation")) {
                ProductUtils.copyBand(bandName, sourceProduct, targetProduct, true);
            }
        }
    }

    static String getPolarisationTag(final CplxContainer reference) {
        return (reference.polarisation == null || reference.polarisation.isEmpty()) ? "" : '_' + reference.polarisation.toUpperCase();
    }

    public static DoubleMatrix estimateFlatEarthPolynomial(
            final SLCImage referenceMetadata, final Orbit referenceOrbit, final SLCImage secondaryMetadata,
            final Orbit secondaryOrbit, final int sourceImageWidth, final int sourceImageHeight,
            final int srpPolynomialDegree, final int srpNumberPoints, final Product sourceProduct)
            throws Exception {

        long minLine = 0;
        long maxLine = sourceImageHeight;
        long minPixel = 0;
        long maxPixel = sourceImageWidth;

        int numberOfCoefficients = PolyUtils.numberOfCoefficients(srpPolynomialDegree);

        int[][] position = MathUtils.distributePoints(srpNumberPoints, new Window(minLine, maxLine, minPixel, maxPixel));

        // setup observation and design matrix
        DoubleMatrix y = new DoubleMatrix(srpNumberPoints);
        DoubleMatrix A = new DoubleMatrix(srpNumberPoints, numberOfCoefficients);

        double referenceMinPi4divLam = (-4 * Math.PI * org.jlinda.core.Constants.SOL) / referenceMetadata.getRadarWavelength();
        double secondaryMinPi4divLam = (-4 * Math.PI * org.jlinda.core.Constants.SOL) / secondaryMetadata.getRadarWavelength();
        final boolean isBiStaticStack = StackUtils.isBiStaticStack(sourceProduct);

        // Loop through vector or distributedPoints()
        for (int i = 0; i < srpNumberPoints; ++i) {

            double line = position[i][0];
            double pixel = position[i][1];

            // compute azimuth/range time for this pixel
            final double referenceTimeRange = referenceMetadata.pix2tr(pixel + 1);

            // compute xyz of this point : sourceReference
            org.jlinda.core.Point xyzReference = referenceOrbit.lp2xyz(line + 1, pixel + 1, referenceMetadata);
            org.jlinda.core.Point secondaryTimeVector = secondaryOrbit.xyz2t(xyzReference, secondaryMetadata);

            double secondaryTimeRange;
            if (isBiStaticStack) {
                secondaryTimeRange = 0.5 * (secondaryTimeVector.x + referenceTimeRange);
            } else {
                secondaryTimeRange = secondaryTimeVector.x;
            }

            // observation vector
            y.put(i, (referenceMinPi4divLam * referenceTimeRange) - (secondaryMinPi4divLam * secondaryTimeRange));

            // set up a system of equations
            // ______Order unknowns: A00 A10 A01 A20 A11 A02 A30 A21 A12 A03 for degree=3______
            double posL = PolyUtils.normalize2(line, minLine, maxLine);
            double posP = PolyUtils.normalize2(pixel, minPixel, maxPixel);

            int index = 0;

            for (int j = 0; j <= srpPolynomialDegree; j++) {
                for (int k = 0; k <= j; k++) {
                    A.put(i, index, (FastMath.pow(posL, (double) (j - k)) * FastMath.pow(posP, (double) k)));
                    index++;
                }
            }
        }

        // Fit polynomial through computed vector of phases
        DoubleMatrix Atranspose = A.transpose();
        DoubleMatrix N = Atranspose.mmul(A);
        DoubleMatrix rhs = Atranspose.mmul(y);

        return Solve.solve(N, rhs);
    }

    /**
     * Create a flat earth phase polynomial for a given burst in TOPSAR product.
     */
    public static DoubleMatrix estimateFlatEarthPolynomial(
            final CplxContainer reference, final CplxContainer secondary, final int subSwathIndex, final int burstIndex,
            final Point[] refSceneCentreXYZ, final int orbitDegree, final int srpPolynomialDegree,
            final int srpNumberPoints, final Sentinel1Utils.SubSwathInfo[] subSwath, final Sentinel1Utils su)
            throws Exception {

        final double[][] referenceOSV = getAdjacentOrbitStateVectors(reference, refSceneCentreXYZ[burstIndex]);
        final double[][] secondaryOSV = getAdjacentOrbitStateVectors(secondary, refSceneCentreXYZ[burstIndex]);
        final Orbit referenceOrbit = new Orbit(referenceOSV, orbitDegree);
        final Orbit secondaryOrbit = new Orbit(secondaryOSV, orbitDegree);

        long minLine = 0;
        long maxLine = subSwath[subSwathIndex - 1].linesPerBurst - 1;
        long minPixel = 0;
        long maxPixel = subSwath[subSwathIndex - 1].samplesPerBurst - 1;

        int numberOfCoefficients = PolyUtils.numberOfCoefficients(srpPolynomialDegree);

        int[][] position = MathUtils.distributePoints(srpNumberPoints, new Window(minLine, maxLine, minPixel, maxPixel));

        // setup observation and design matrix
        DoubleMatrix y = new DoubleMatrix(srpNumberPoints);
        DoubleMatrix A = new DoubleMatrix(srpNumberPoints, numberOfCoefficients);

        double referenceMinPi4divLam = (-4 * Constants.PI * Constants.lightSpeed) / reference.metaData.getRadarWavelength();
        double secondaryMinPi4divLam = (-4 * Constants.PI * Constants.lightSpeed) / secondary.metaData.getRadarWavelength();

        // Loop through vector or distributedPoints()
        for (int i = 0; i < srpNumberPoints; ++i) {

            double line = position[i][0];
            double pixel = position[i][1];

            // compute azimuth/range time for this pixel
            final double refRgTime = subSwath[subSwathIndex - 1].slrTimeToFirstPixel +
                    pixel * su.rangeSpacing / Constants.lightSpeed;

            final double refAzTime = line2AzimuthTime(line, subSwathIndex, burstIndex, subSwath);

            // compute xyz of this point : sourceReference
            Point xyzReference = referenceOrbit.lph2xyz(
                    refAzTime, refRgTime, 0.0, refSceneCentreXYZ[burstIndex]);

            Point secondaryTimeVector = secondaryOrbit.xyz2t(xyzReference, secondary.metaData.getSceneCentreAzimuthTime());

            final double secondaryTimeRange = secondaryTimeVector.x;

            // observation vector
            y.put(i, (referenceMinPi4divLam * refRgTime) - (secondaryMinPi4divLam * secondaryTimeRange));

            // set up a system of equations
            // ______Order unknowns: A00 A10 A01 A20 A11 A02 A30 A21 A12 A03 for degree=3______
            double posL = PolyUtils.normalize2(line, minLine, maxLine);
            double posP = PolyUtils.normalize2(pixel, minPixel, maxPixel);

            int index = 0;

            for (int j = 0; j <= srpPolynomialDegree; j++) {
                for (int k = 0; k <= j; k++) {
                    A.put(i, index, (FastMath.pow(posL, (double) (j - k)) * FastMath.pow(posP, (double) k)));
                    index++;
                }
            }
        }

        // Fit polynomial through computed vector of phases
        DoubleMatrix Atranspose = A.transpose();
        DoubleMatrix N = Atranspose.mmul(A);
        DoubleMatrix rhs = Atranspose.mmul(y);

        return Solve.solve(N, rhs);
    }

    private static double[][] getAdjacentOrbitStateVectors(
            final CplxContainer container, final Point sceneCentreXYZ) {

        try {
            double[] time = container.orbit.getTime();
            double[] dataX = container.orbit.getData_X();
            double[] dataY = container.orbit.getData_Y();
            double[] dataZ = container.orbit.getData_Z();

            final int numOfOSV = dataX.length;
            double minDistance = 0.0;
            int minIdx = 0;
            for (int i = 0; i < numOfOSV; i++) {
                final double dx = dataX[i] - sceneCentreXYZ.x;
                final double dy = dataY[i] - sceneCentreXYZ.y;
                final double dz = dataZ[i] - sceneCentreXYZ.z;
                final double distance = Math.sqrt(dx * dx + dy * dy + dz * dz) / 1000.0;
                if (i == 0) {
                    minDistance = distance;
                    minIdx = i;
                    continue;
                }

                if (distance < minDistance) {
                    minDistance = distance;
                    minIdx = i;
                }
            }

            int stIdx, edIdx;
            if (minIdx < 3) {
                stIdx = 0;
                edIdx = Math.min(7, numOfOSV - 1);
            } else if (minIdx > numOfOSV - 5) {
                stIdx = Math.max(numOfOSV - 8, 0);
                edIdx = numOfOSV - 1;
            } else {
                stIdx = minIdx - 3;
                edIdx = minIdx + 4;
            }

            final double[][] adjacentOSV = new double[edIdx - stIdx + 1][4];
            int k = 0;
            for (int i = stIdx; i <= edIdx; i++) {
                adjacentOSV[k][0] = time[i];
                adjacentOSV[k][1] = dataX[i];
                adjacentOSV[k][2] = dataY[i];
                adjacentOSV[k][3] = dataZ[i];
                k++;
            }

            return adjacentOSV;
        } catch (Throwable e) {
            SystemUtils.LOG.warning("Unable to getAdjacentOrbitStateVectors " + e.getMessage());
        }
        return null;
    }

    private static double line2AzimuthTime(final double line, final int subSwathIndex, final int burstIndex,
                                           final Sentinel1Utils.SubSwathInfo[] subSwath) {

        final double firstLineTimeInDays = subSwath[subSwathIndex - 1].burstFirstLineTime[burstIndex] /
                Constants.secondsInDay;

        final double firstLineTime = (firstLineTimeInDays - (int) firstLineTimeInDays) * Constants.secondsInDay;

        return firstLineTime + line * subSwath[subSwathIndex - 1].azimuthTimeInterval;
    }

    private synchronized void estimateFlatEarth() throws OperatorException {
        if(flatEarthEstimated)
            return;
        if (subtractFlatEarthPhase) {
            try {
                if (isTOPSARBurstProduct) {

                    getRefApproxSceneCentreXYZ();
                    constructFlatEarthPolynomialsForTOPSARProduct();
                } else {
                    constructFlatEarthPolynomials();
                }
                flatEarthEstimated = true;
            } catch (Exception e) {
                OperatorUtils.catchOperatorException(getId(), e);
            }
        }
    }

    private void defineDEM() throws IOException {

        String demResamplingMethod = ResamplingFactory.BILINEAR_INTERPOLATION_NAME;

        if (externalDEMFile == null) {
            dem = DEMFactory.createElevationModel(demName, demResamplingMethod);
            demNoDataValue = dem.getDescriptor().getNoDataValue();
            demSamplingLat = dem.getDescriptor().getTileWidthInDegrees() * (1.0f /
                    dem.getDescriptor().getTileWidth()) * org.jlinda.core.Constants.DTOR;

            demSamplingLon = demSamplingLat;

        } else {

            dem = new FileElevationModel(externalDEMFile, demResamplingMethod, externalDEMNoDataValue);
            ((FileElevationModel) dem).applyEarthGravitionalModel(externalDEMApplyEGM);
            demNoDataValue = externalDEMNoDataValue;
            demName = externalDEMFile.getName();

            try {
                demSamplingLat =
                        (dem.getGeoPos(new PixelPos(0, 1)).getLat() - dem.getGeoPos(new PixelPos(0, 0)).getLat()) *
                                org.jlinda.core.Constants.DTOR;
                demSamplingLon =
                        (dem.getGeoPos(new PixelPos(1, 0)).getLon() - dem.getGeoPos(new PixelPos(0, 0)).getLon()) *
                                org.jlinda.core.Constants.DTOR;
            } catch (Exception e) {
                throw new OperatorException("The DEM '" + demName + "' cannot be properly interpreted.");
            }
        }
    }

    /**
     * Called by the framework in order to compute a tile for the given target band.
     * <p>The default implementation throws a runtime exception with the message "not implemented".</p>
     *
     * @param targetTileMap   The target tiles associated with all target bands to be computed.
     * @param targetRectangle The rectangle of target tile.
     * @param pm              A progress monitor which should be used to determine computation cancelation requests.
     * @throws OperatorException If an error occurs during computation of the target raster.
     */
    @Override
    public void computeTileStack(Map<Band, Tile> targetTileMap, Rectangle targetRectangle, ProgressMonitor pm)
            throws OperatorException {

            if (isGSLCProduct) {
                computeTileStackForGSLC(targetTileMap, targetRectangle);
                return;
            }

            if (subtractFlatEarthPhase && !flatEarthEstimated) {
                estimateFlatEarth();
            }

            if (isTOPSARBurstProduct) {
                computeTileStackForTOPSARProduct(targetTileMap, targetRectangle, pm);
            } else {
                computeTileStackForNormalProduct(targetTileMap, targetRectangle, pm);
            }
    }

    private void computeTileStackForNormalProduct(
            final Map<Band, Tile> targetTileMap, Rectangle targetRectangle, final ProgressMonitor pm)
            throws OperatorException {
        try {
            final BorderExtender border = BorderExtender.createInstance(BorderExtender.BORDER_ZERO);

            final int y0 = targetRectangle.y;
            final int yN = y0 + targetRectangle.height - 1;
            final int x0 = targetRectangle.x;
            final int xN = targetRectangle.x + targetRectangle.width - 1;
            final Window tileWindow = new Window(y0, yN, x0, xN);

            DemTile demTile = null;
            if (subtractTopographicPhase) {
                demTile = TopoPhase.getDEMTile(tileWindow, targetMap, dem, demNoDataValue,
                        demSamplingLat, demSamplingLon, tileExtensionPercent);

                if (demTile.getData().length < 3 || demTile.getData()[0].length < 3) {
                    throw new OperatorException("The resolution of the selected DEM is too low, " +
                            "please select DEM with higher resolution.");
                }
            }

            // parameters for coherence calculation
            final int cohx0 = targetRectangle.x - (cohWinRg - 1) / 2;
            final int cohy0 = targetRectangle.y - (cohWinAz - 1) / 2;
            final int cohw = targetRectangle.width + cohWinRg - 1;
            final int cohh = targetRectangle.height + cohWinAz - 1;
            final Rectangle rect = new Rectangle(cohx0, cohy0, cohw, cohh);

            final Window cohTileWindow = new Window(
                    cohy0, cohy0 + cohh - 1, cohx0, cohx0 + cohw - 1);

            DemTile cohDemTile = null;
            if (subtractTopographicPhase) {
                cohDemTile = TopoPhase.getDEMTile(cohTileWindow, targetMap, dem, demNoDataValue,
                        demSamplingLat, demSamplingLon, tileExtensionPercent);
            }

            for (String ifgKey : targetMap.keySet()) {

                final ProductContainer product = targetMap.get(ifgKey);

                final Tile refTileReal = getSourceTile(product.sourceRef.realBand, targetRectangle, border);
                final Tile refTileImag = getSourceTile(product.sourceRef.imagBand, targetRectangle, border);
                final ComplexDoubleMatrix dataReference = TileUtilsDoris.pullComplexDoubleMatrix(refTileReal, refTileImag);

                final Tile secTileReal = getSourceTile(product.sourceSec.realBand, targetRectangle, border);
                final Tile secTileImag = getSourceTile(product.sourceSec.imagBand, targetRectangle, border);
                final ComplexDoubleMatrix dataSecondary = TileUtilsDoris.pullComplexDoubleMatrix(secTileReal, secTileImag);

                if (subtractFlatEarthPhase) {
                    final DoubleMatrix flatEarthPhase = computeFlatEarthPhase(
                            x0, xN, dataReference.columns, y0, yN, dataReference.rows,
                            0, sourceImageWidth - 1, 0, sourceImageHeight - 1, product.sourceSec.name);

                    final ComplexDoubleMatrix complexReferencePhase = new ComplexDoubleMatrix(
                            MatrixFunctions.cos(flatEarthPhase), MatrixFunctions.sin(flatEarthPhase));

                    dataSecondary.muli(complexReferencePhase);

                    if (outputFlatEarthPhase) {
                        saveFlatEarthPhase(x0, xN, y0, yN, flatEarthPhase, product, targetTileMap);
                    }
                }

                if (subtractTopographicPhase) {
                    final TopoPhase topoPhase = TopoPhase.computeTopoPhase(
                            product, tileWindow, demTile, outputElevation, false);

                    final ComplexDoubleMatrix ComplexTopoPhase = new ComplexDoubleMatrix(
                            MatrixFunctions.cos(new DoubleMatrix(topoPhase.demPhase)),
                            MatrixFunctions.sin(new DoubleMatrix(topoPhase.demPhase)));

                    dataSecondary.muli(ComplexTopoPhase);

                    if (outputTopoPhase) {
                        saveTopoPhase(x0, xN, y0, yN, topoPhase.demPhase, product, targetTileMap);
                    }

                    if (outputElevation) {
                        saveElevation(x0, xN, y0, yN, topoPhase.elevation, product, targetTileMap);
                    }

                    if (outputLatLon) {
                        final TopoPhase topoPhase1 = TopoPhase.computeTopoPhase(
                                product, tileWindow, demTile, false, true);

                        saveLatLon(x0, xN, y0, yN, topoPhase1.latitude, topoPhase1.longitude, product, targetTileMap);
                    }
                }

                if (subtractETADPhase) {
                    final double[][] etadPhase = computeETADPhase(targetRectangle);

                    if (etadPhase != null) {
                        final ComplexDoubleMatrix ComplexETADPhase = new ComplexDoubleMatrix(
                                MatrixFunctions.cos(new DoubleMatrix(etadPhase)),
                                MatrixFunctions.sin(new DoubleMatrix(etadPhase)));

                        dataSecondary.muli(ComplexETADPhase);

                        if (OUTPUT_ETAD_IFG) {
                            saveETADPhase(x0, xN, y0, yN, etadPhase, product, targetTileMap);
                        }
                    }
                }

                dataReference.muli(dataSecondary.conji());

                saveInterferogram(dataReference, product, targetTileMap, targetRectangle);

                // coherence calculation
                if (includeCoherence) {
                    final Tile refTileReal2 = getSourceTile(product.sourceRef.realBand, rect, border);
                    final Tile refTileImag2 = getSourceTile(product.sourceRef.imagBand, rect, border);
                    final Tile secTileReal2 = getSourceTile(product.sourceSec.realBand, rect, border);
                    final Tile secTileImag2 = getSourceTile(product.sourceSec.imagBand, rect, border);
                    final ComplexDoubleMatrix dataReference2 =
                            TileUtilsDoris.pullComplexDoubleMatrix(refTileReal2, refTileImag2);

                    final ComplexDoubleMatrix dataSecondary2 =
                            TileUtilsDoris.pullComplexDoubleMatrix(secTileReal2, secTileImag2);

                    if (subtractFlatEarthPhase) {
                        final DoubleMatrix flatEarthPhase = computeFlatEarthPhase(
                                cohx0, cohx0 + cohw - 1, cohw, cohy0, cohy0 + cohh - 1, cohh,
                                0, sourceImageWidth - 1, 0, sourceImageHeight - 1, product.sourceSec.name);

                        final ComplexDoubleMatrix complexReferencePhase = new ComplexDoubleMatrix(
                                MatrixFunctions.cos(flatEarthPhase), MatrixFunctions.sin(flatEarthPhase));

                        dataSecondary2.muli(complexReferencePhase);
                    }

                    if (subtractTopographicPhase) {
                        final TopoPhase topoPhase = TopoPhase.computeTopoPhase(
                                product, cohTileWindow, cohDemTile, false);

                        final ComplexDoubleMatrix ComplexTopoPhase = new ComplexDoubleMatrix(
                                MatrixFunctions.cos(new DoubleMatrix(topoPhase.demPhase)),
                                MatrixFunctions.sin(new DoubleMatrix(topoPhase.demPhase)));

                        dataSecondary2.muli(ComplexTopoPhase);
                    }

                    for (int i = 0; i < dataReference2.length; i++) {
                        double tmp = norm(dataReference2.get(i));
                        dataReference2.put(i, dataReference2.get(i).mul(dataSecondary2.get(i).conj()));
                        dataSecondary2.put(i, new ComplexDouble(norm(dataSecondary2.get(i)), tmp));
                    }

                    DoubleMatrix cohMatrix = SarUtils.coherence3(dataReference2, dataSecondary2, cohWinAz, cohWinRg);

                    saveCoherence(cohMatrix, product, targetTileMap, targetRectangle);
                }
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        } finally {
            pm.done();
        }
    }

    private DoubleMatrix computeFlatEarthPhase(final int xMin, final int xMax, final int xSize,
                                               final int yMin, final int yMax, final int ySize,
                                               final int minPixel, final int maxPixel,
                                               final int minLine, final int maxLine,
                                               final String polynomialName) {

        DoubleMatrix rangeAxisNormalized = normalizeDoubleMatrix(DoubleMatrix.linspace(xMin, xMax, xSize), minPixel, maxPixel);
        DoubleMatrix azimuthAxisNormalized = normalizeDoubleMatrix(DoubleMatrix.linspace(yMin, yMax, ySize), minLine, maxLine);

        final DoubleMatrix polyCoeffs = flatEarthPolyMap.get(polynomialName);

        return PolyUtils.polyval(azimuthAxisNormalized, rangeAxisNormalized,
                polyCoeffs, PolyUtils.degreeFromCoefficients(polyCoeffs.length));
    }

    private void saveElevation(final int x0, final int xN, final int y0, final int yN, final double[][] elevation,
                               final ProductContainer product, final Map<Band, Tile> targetTileMap) {
        if (product.getBandName(ELEVATION) == null) {
            return;
        }
        final Band elevationBand = targetProduct.getBand(product.getBandName(ELEVATION));
        final Tile elevationTile = targetTileMap.get(elevationBand);
        final ProductData elevationData = elevationTile.getDataBuffer();
        final TileIndex tgtIndex = new TileIndex(elevationTile);
        for (int y = y0; y <= yN; y++) {
            tgtIndex.calculateStride(y);
            final int yy = y - y0;
            for (int x = x0; x <= xN; x++) {
                final int tgtIdx = tgtIndex.getIndex(x);
                final int xx = x - x0;
                elevationData.setElemFloatAt(tgtIdx, (float)elevation[yy][xx]);
            }
        }
    }

    private void saveLatLon(final int x0, final int xN, final int y0, final int yN,
                            final double[][] latitude, final double[][] longitude,
                            final ProductContainer product, final Map<Band, Tile> targetTileMap) {

        if (product.getBandName(LATITUDE) == null || product.getBandName(LONGITUDE) == null) {
            return;
        }

        final Band latBand = targetProduct.getBand(product.getBandName(LATITUDE));
        final Tile latTile = targetTileMap.get(latBand);
        final ProductData latData = latTile.getDataBuffer();
        final Band lonBand = targetProduct.getBand(product.getBandName(LONGITUDE));
        final Tile lonTile = targetTileMap.get(lonBand);
        final ProductData lonData = lonTile.getDataBuffer();

        final TileIndex tgtIndex = new TileIndex(latTile);

        for (int y = y0; y <= yN; y++) {
            tgtIndex.calculateStride(y);
            final int yy = y - y0;
            for (int x = x0; x <= xN; x++) {
                final int tgtIdx = tgtIndex.getIndex(x);
                final int xx = x - x0;

                latData.setElemFloatAt(tgtIdx, (float) (latitude[yy][xx] * 180.0/Math.PI));
                lonData.setElemFloatAt(tgtIdx, (float) (longitude[yy][xx] * 180.0/Math.PI));
            }
        }
    }

    private void saveTopoPhase(final int x0, final int xN, final int y0, final int yN, final double[][] topoPhase,
                               final ProductContainer product, final Map<Band, Tile> targetTileMap) {

        final Band topoPhaseBand = targetProduct.getBand(product.getBandName(TOPO_PHASE));
        final Tile topoPhaseTile = targetTileMap.get(topoPhaseBand);
        final ProductData topoPhaseData = topoPhaseTile.getDataBuffer();
        final TileIndex tgtIndex = new TileIndex(topoPhaseTile);

        for (int y = y0; y <= yN; y++) {
            tgtIndex.calculateStride(y);
            final int yy = y - y0;
            for (int x = x0; x <= xN; x++) {
                final int tgtIdx = tgtIndex.getIndex(x);
                final int xx = x - x0;
                topoPhaseData.setElemFloatAt(tgtIdx, (float)topoPhase[yy][xx]);
            }
        }
    }

    private void saveFlatEarthPhase(final int x0, final int xN, final int y0, final int yN, final DoubleMatrix refPhase,
                                    final ProductContainer product, final Map<Band, Tile> targetTileMap) {

        final Band flatEarthPhaseBand = targetProduct.getBand(product.getBandName(FLAT_EARTH_PHASE));
        final Tile flatEarthPhaseTile = targetTileMap.get(flatEarthPhaseBand);
        final ProductData flatEarthPhaseData = flatEarthPhaseTile.getDataBuffer();

        final TileIndex tgtIndex = new TileIndex(flatEarthPhaseTile);
        for (int y = y0; y <= yN; y++) {
            tgtIndex.calculateStride(y);
            final int yy = y - y0;
            for (int x = x0; x <= xN; x++) {
                final int tgtIdx = tgtIndex.getIndex(x);
                final int xx = x - x0;
                flatEarthPhaseData.setElemFloatAt(tgtIdx, (float)refPhase.get(yy, xx));
            }
        }
    }

    // Save flat-earth phase in [-PI, PI]
//    private void saveFlatEarthPhase(final int x0, final int xN, final int y0, final int yN, final ComplexDoubleMatrix complexReferencePhase,
//                                    final ProductContainer product, final Map<Band, Tile> targetTileMap) {
//
//        final Band flatEarthPhaseBand = targetProduct.getBand(product.getBandName(FLAT_EARTH_PHASE));
//        final Tile flatEarthPhaseTile = targetTileMap.get(flatEarthPhaseBand);
//        final ProductData flatEarthPhaseData = flatEarthPhaseTile.getDataBuffer();
//
//        final TileIndex tgtIndex = new TileIndex(flatEarthPhaseTile);
//        for (int y = y0; y <= yN; y++) {
//            tgtIndex.calculateStride(y);
//            final int yy = y - y0;
//            for (int x = x0; x <= xN; x++) {
//                final int tgtIdx = tgtIndex.getIndex(x);
//                final int xx = x - x0;
//                final double real = complexReferencePhase.get(yy, xx).real();
//                final double imag = complexReferencePhase.get(yy, xx).imag();
//                flatEarthPhaseData.setElemFloatAt(tgtIdx, (float)Math.atan2(imag, real));
//            }
//        }
//    }

    private void saveETADPhase(final int x0, final int xN, final int y0, final int yN, final double[][] etadPhase,
                               final ProductContainer product, final Map<Band, Tile> targetTileMap) {

        final Band etadIfgBand = targetProduct.getBand(product.getBandName(ETAD_IFG));
        final Tile etadIfgTile = targetTileMap.get(etadIfgBand);
        final ProductData etadIfgData = etadIfgTile.getDataBuffer();
        final TileIndex tgtIndex = new TileIndex(etadIfgTile);

        for (int y = y0; y <= yN; y++) {
            tgtIndex.calculateStride(y);
            final int yy = y - y0;
            for (int x = x0; x <= xN; x++) {
                final int tgtIdx = tgtIndex.getIndex(x);
                final int xx = x - x0;
                etadIfgData.setElemFloatAt(tgtIdx, (float)etadPhase[yy][xx]);
            }
        }
    }

    private void saveInterferogram(final ComplexDoubleMatrix dataIfg, final ProductContainer product,
                                   final Map<Band, Tile> targetTileMap, final Rectangle targetRectangle) {

        final int x0 = targetRectangle.x;
        final int y0 = targetRectangle.y;
        final int maxX = x0 + targetRectangle.width;
        final int maxY = y0 + targetRectangle.height;
        final Band targetBand_I = targetProduct.getBand(product.getBandName(Unit.REAL));
        final Tile tileOutReal = targetTileMap.get(targetBand_I);
        final Band targetBand_Q = targetProduct.getBand(product.getBandName(Unit.IMAGINARY));
        final Tile tileOutImag = targetTileMap.get(targetBand_Q);
        final TileIndex tgtIndex = new TileIndex(tileOutReal);

        final ProductData samplesReal = tileOutReal.getDataBuffer();
        final ProductData samplesImag = tileOutImag.getDataBuffer();
        final DoubleMatrix dataReal = dataIfg.real();
        final DoubleMatrix dataImag = dataIfg.imag();

        final boolean refNoDataValueUsed = product.sourceRef.realBand.isNoDataValueUsed();
        final double refNoDataValue = product.sourceRef.realBand.getNoDataValue();

        if (refNoDataValueUsed) {

            for (int y = y0; y < maxY; y++) {
                tgtIndex.calculateStride(y);
                final int yy = y - y0;
                for (int x = x0; x < maxX; x++) {
                    final int tgtIdx = tgtIndex.getIndex(x);
                    final int xx = x - x0;

                    final float r = (float) dataReal.get(yy, xx);
                    final float i = (float) dataImag.get(yy, xx);
                    if (r == 0.0f) {
                        samplesReal.setElemFloatAt(tgtIdx, (float) refNoDataValue);
                        samplesImag.setElemFloatAt(tgtIdx, (float) refNoDataValue);
                    } else {
                        samplesReal.setElemFloatAt(tgtIdx, r);
                        samplesImag.setElemFloatAt(tgtIdx, i);
                    }
                }
            }

        } else {

            for (int y = y0; y < maxY; y++) {
                tgtIndex.calculateStride(y);
                final int yy = y - y0;
                for (int x = x0; x < maxX; x++) {
                    final int tgtIdx = tgtIndex.getIndex(x);
                    final int xx = x - x0;
                    samplesReal.setElemFloatAt(tgtIdx, (float) dataReal.get(yy, xx));
                    samplesImag.setElemFloatAt(tgtIdx, (float) dataImag.get(yy, xx));
                }
            }
        }
    }

    private void saveCoherence(final DoubleMatrix cohMatrix, final ProductContainer product,
                               final Map<Band, Tile> targetTileMap, final Rectangle targetRectangle) {

        final int x0 = targetRectangle.x;
        final int y0 = targetRectangle.y;
        final int maxX = x0 + targetRectangle.width;
        final int maxY = y0 + targetRectangle.height;

        final Band coherenceBand = targetProduct.getBand(product.getBandName(Unit.COHERENCE));
        final Tile coherenceTile = targetTileMap.get(coherenceBand);
        final ProductData coherenceData = coherenceTile.getDataBuffer();

        final double srcNoDataValue = product.sourceRef.realBand.getNoDataValue();
        final Tile secTileReal = getSourceTile(product.sourceSec.realBand, targetRectangle);
        final ProductData srcSecData = secTileReal.getDataBuffer();
        final TileIndex srcSecIndex = new TileIndex(secTileReal);

        final TileIndex tgtIndex = new TileIndex(coherenceTile);
        for (int y = y0; y < maxY; y++) {
            tgtIndex.calculateStride(y);
            srcSecIndex.calculateStride(y);
            final int yy = y - y0;
            for (int x = x0; x < maxX; x++) {
                final int tgtIdx = tgtIndex.getIndex(x);
                final int xx = x - x0;

                if (srcSecData.getElemDoubleAt(srcSecIndex.getIndex(x)) == srcNoDataValue) {
                    coherenceData.setElemFloatAt(tgtIdx, (float) srcNoDataValue);
                } else {
                    final double coh = cohMatrix.get(yy, xx);
                    coherenceData.setElemFloatAt(tgtIdx, (float) coh);
                }
            }
        }
    }

    private static double norm(final ComplexDouble number) {
        return number.real() * number.real() + number.imag() * number.imag();
    }

    private void computeTileStackForTOPSARProduct(
            final Map<Band, Tile> targetTileMap, final Rectangle targetRectangle, final ProgressMonitor pm)
            throws OperatorException {

        try {
            final int tx0 = targetRectangle.x;
            final int ty0 = targetRectangle.y;
            final int tw = targetRectangle.width;
            final int th = targetRectangle.height;
            final int txMax = tx0 + tw;
            final int tyMax = ty0 + th;
            //System.out.println("tx0 = " + tx0 + ", ty0 = " + ty0 + ", tw = " + tw + ", th = " + th);

            for (int burstIndex = 0; burstIndex < subSwath[subSwathIndex - 1].numOfBursts; burstIndex++) {
                final int firstLineIdx = burstIndex * subSwath[subSwathIndex - 1].linesPerBurst;
                final int lastLineIdx = firstLineIdx + subSwath[subSwathIndex - 1].linesPerBurst - 1;

                if (tyMax <= firstLineIdx || ty0 > lastLineIdx) {
                    continue;
                }

                final int ntx0 = tx0;
                final int ntw = tw;
                final int nty0 = Math.max(ty0, firstLineIdx);
                final int ntyMax = Math.min(tyMax, lastLineIdx + 1);
                final int nth = ntyMax - nty0;
                final Rectangle partialTileRectangle = new Rectangle(ntx0, nty0, ntw, nth);
                //System.out.println("burst = " + burstIndex + ": ntx0 = " + ntx0 + ", nty0 = " + nty0 + ", ntw = " + ntw + ", nth = " + nth);

                computePartialTile(subSwathIndex, burstIndex, firstLineIdx, partialTileRectangle, targetTileMap);
            }

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        } finally {
            pm.done();
        }
    }

    private void computePartialTile(final int subSwathIndex, final int burstIndex,
                                    final int firstLineIdx, final Rectangle targetRectangle,
                                    final Map<Band, Tile> targetTileMap) {

        try {
            final BorderExtender border = BorderExtender.createInstance(BorderExtender.BORDER_ZERO);

            final int y0 = targetRectangle.y;
            final int yN = y0 + targetRectangle.height - 1;
            final int x0 = targetRectangle.x;
            final int xN = x0 + targetRectangle.width - 1;

            final Window tileWindow = new Window(y0 - firstLineIdx, yN - firstLineIdx, x0, xN);
            final SLCImage refMeta = targetMap.values().iterator().next().sourceRef.metaData.clone();
            updateRefMetaData(burstIndex, refMeta);
            final Orbit refOrbit = targetMap.values().iterator().next().sourceRef.orbit;

            DemTile demTile = null;
            if (subtractTopographicPhase) {
                demTile = TopoPhase.getDEMTile(tileWindow, refMeta, refOrbit, dem,
                        demNoDataValue, demSamplingLat, demSamplingLon, tileExtensionPercent);

                if (demTile == null) {
                    throw new OperatorException("The selected DEM has no overlap with the image or is invalid.");
                }

                if (demTile.getData().length < 3 || demTile.getData()[0].length < 3) {
                    throw new OperatorException("The resolution of the selected DEM is too low, " +
                            "please select DEM with higher resolution.");
                }
            }

            final int cohx0 = targetRectangle.x - (cohWinRg - 1) / 2;
            final int cohy0 = targetRectangle.y - (cohWinAz - 1) / 2;
            final int cohw = targetRectangle.width + cohWinRg - 1;
            final int cohh = targetRectangle.height + cohWinAz - 1;
            final Rectangle rect = new Rectangle(cohx0, cohy0, cohw, cohh);

            final Window cohTileWindow = new Window(
                    cohy0 - firstLineIdx, cohy0 + cohh - 1 - firstLineIdx, cohx0, cohx0 + cohw - 1);

            DemTile cohDemTile = null;
            if (subtractTopographicPhase) {
                cohDemTile = TopoPhase.getDEMTile(cohTileWindow, refMeta, refOrbit, dem,
                        demNoDataValue, demSamplingLat, demSamplingLon, tileExtensionPercent);
            }

            final int minLine = 0;
            final int maxLine = subSwath[subSwathIndex - 1].linesPerBurst - 1;
            final int minPixel = 0;
            final int maxPixel = subSwath[subSwathIndex - 1].samplesPerBurst - 1;

            for (String ifgKey : targetMap.keySet()) {

                final ProductContainer product = targetMap.get(ifgKey);
                final SLCImage secMeta = product.sourceSec.metaData.clone();
                updateSecMetaData(product, burstIndex, secMeta);
                final Orbit secOrbit = product.sourceSec.orbit;

                /// check out results from reference ///
                final Tile refTileReal = getSourceTile(product.sourceRef.realBand, targetRectangle, border);
                final Tile refTileImag = getSourceTile(product.sourceRef.imagBand, targetRectangle, border);
                final ComplexDoubleMatrix dataReference = TileUtilsDoris.pullComplexDoubleMatrix(refTileReal, refTileImag);

                /// check out results from secondary ///
                final Tile secTileReal = getSourceTile(product.sourceSec.realBand, targetRectangle, border);
                final Tile secTileImag = getSourceTile(product.sourceSec.imagBand, targetRectangle, border);
                final ComplexDoubleMatrix dataSecondary = TileUtilsDoris.pullComplexDoubleMatrix(secTileReal, secTileImag);

                final String polynomialName = product.sourceSec.name + '_' + (subSwathIndex - 1) + '_' + burstIndex;
                if (subtractFlatEarthPhase) {
                    final DoubleMatrix flatEarthPhase = computeFlatEarthPhase(
                            x0, xN, dataReference.columns, y0 - firstLineIdx, yN - firstLineIdx, dataReference.rows,
                            minPixel, maxPixel, minLine, maxLine, polynomialName);

                    final ComplexDoubleMatrix complexReferencePhase = new ComplexDoubleMatrix(
                            MatrixFunctions.cos(flatEarthPhase), MatrixFunctions.sin(flatEarthPhase));

                    dataSecondary.muli(complexReferencePhase);

                    if (outputFlatEarthPhase) {
                        saveFlatEarthPhase(x0, xN, y0, yN, flatEarthPhase, product, targetTileMap);
                    }
                }

                if (subtractTopographicPhase) {
                    TopoPhase topoPhase = TopoPhase.computeTopoPhase(
                            refMeta, refOrbit, secMeta, secOrbit, tileWindow, demTile, outputElevation, false);

                    final ComplexDoubleMatrix ComplexTopoPhase = new ComplexDoubleMatrix(
                            MatrixFunctions.cos(new DoubleMatrix(topoPhase.demPhase)),
                            MatrixFunctions.sin(new DoubleMatrix(topoPhase.demPhase)));

                    dataSecondary.muli(ComplexTopoPhase);

                    if (outputTopoPhase) {
                        saveTopoPhase(x0, xN, y0, yN, topoPhase.demPhase, product, targetTileMap);
                    }

                    if (outputElevation) {
                        saveElevation(x0, xN, y0, yN, topoPhase.elevation, product, targetTileMap);
                    }

                    if (outputLatLon) {
                        TopoPhase topoPhase1 = TopoPhase.computeTopoPhase(
                                refMeta, refOrbit, secMeta, secOrbit, tileWindow, demTile, false, true);

                        saveLatLon(x0, xN, y0, yN, topoPhase1.latitude, topoPhase1.longitude, product, targetTileMap);
                    }
                }

                if (subtractETADPhase) {
                    final String refDate = getTimeStamp(product.sourceRef.date);
                    final String secDate = getTimeStamp(product.sourceSec.date);

                    final Map<Integer, Integer> refSecBurstMap = createRefSecBurstMap(product.sourceSec.date);

                    final double[][] etadPhase = computeETADPhase(targetRectangle, burstIndex, refSecBurstMap,
                            refDate, secDate);

                    if (etadPhase != null) {
                        final ComplexDoubleMatrix ComplexETADPhase = new ComplexDoubleMatrix(
                                MatrixFunctions.cos(new DoubleMatrix(etadPhase)),
                                MatrixFunctions.sin(new DoubleMatrix(etadPhase)));

                        dataSecondary.muli(ComplexETADPhase);

                        if (OUTPUT_ETAD_IFG) {
                            saveETADPhase(x0, xN, y0, yN, etadPhase, product, targetTileMap);
                        }
                    }
                }

                dataReference.muli(dataSecondary.conji());

                saveInterferogram(dataReference, product, targetTileMap, targetRectangle);

                // coherence calculation
                if (includeCoherence) {
                    final Tile refTileReal2 = getSourceTile(product.sourceRef.realBand, rect, border);
                    final Tile refTileImag2 = getSourceTile(product.sourceRef.imagBand, rect, border);
                    final Tile secTileReal2 = getSourceTile(product.sourceSec.realBand, rect, border);
                    final Tile secTileImag2 = getSourceTile(product.sourceSec.imagBand, rect, border);
                    final ComplexDoubleMatrix dataReference2 =
                            TileUtilsDoris.pullComplexDoubleMatrix(refTileReal2, refTileImag2);

                    final ComplexDoubleMatrix dataSecondary2 =
                            TileUtilsDoris.pullComplexDoubleMatrix(secTileReal2, secTileImag2);

                    if (subtractFlatEarthPhase) {
                        final DoubleMatrix flatEarthPhase = computeFlatEarthPhase(
                                cohx0, cohx0 + cohw - 1, cohw, cohy0 - firstLineIdx, cohy0 + cohh - 1 - firstLineIdx, cohh,
                                minPixel, maxPixel, minLine, maxLine, polynomialName);

                        final ComplexDoubleMatrix complexReferencePhase = new ComplexDoubleMatrix(
                                MatrixFunctions.cos(flatEarthPhase), MatrixFunctions.sin(flatEarthPhase));

                        dataSecondary2.muli(complexReferencePhase);
                    }

                    if (subtractTopographicPhase) {
                        TopoPhase topoPhase = TopoPhase.computeTopoPhase(
                                refMeta, refOrbit, secMeta, secOrbit, cohTileWindow, cohDemTile, false);

                        final ComplexDoubleMatrix ComplexTopoPhase = new ComplexDoubleMatrix(
                                MatrixFunctions.cos(new DoubleMatrix(topoPhase.demPhase)),
                                MatrixFunctions.sin(new DoubleMatrix(topoPhase.demPhase)));

                        dataSecondary2.muli(ComplexTopoPhase);
                    }

                    for (int i = 0; i < dataReference2.length; i++) {
                        double tmp = norm(dataReference2.get(i));
                        dataReference2.put(i, dataReference2.get(i).mul(dataSecondary2.get(i).conj()));
                        dataSecondary2.put(i, new ComplexDouble(norm(dataSecondary2.get(i)), tmp));
                    }

                    DoubleMatrix cohMatrix = SarUtils.coherence3(dataReference2, dataSecondary2, cohWinAz, cohWinRg);

                    saveCoherence(cohMatrix, product, targetTileMap, targetRectangle);
                }
            }

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private String getTimeStamp(final String dateString) {
        return StringUtils.createValidName('_' + dateString, new char[]{'_', '.'}, '_');
    }

    private void updateRefMetaData(final int burstIndex, final SLCImage refMeta) {

        final double burstFirstLineTimeMJD = subSwath[subSwathIndex - 1].burstFirstLineTime[burstIndex] /
                Constants.secondsInDay;

        final double burstFirstLineTimeSecondsOfDay = (burstFirstLineTimeMJD - (int)burstFirstLineTimeMJD) *
                Constants.secondsInDay;

        refMeta.settAzi1(burstFirstLineTimeSecondsOfDay);

        refMeta.setCurrentWindow(new Window(0, subSwath[subSwathIndex - 1].linesPerBurst - 1,
                0, subSwath[subSwathIndex - 1].samplesPerBurst - 1));

        refMeta.setOriginalWindow(new Window(0, subSwath[subSwathIndex - 1].linesPerBurst - 1,
                0, subSwath[subSwathIndex - 1].samplesPerBurst - 1));

        refMeta.setApproxGeoCentreOriginal(getApproxGeoCentre(subSwathIndex, burstIndex));
    }

    private void updateSecMetaData(final ProductContainer product, final int burstIndex, final SLCImage secMeta) {

        final double secBurstFirstLineTimeMJD = secMeta.getMjd() - product.sourceRef.metaData.getMjd() +
                subSwath[subSwathIndex - 1].burstFirstLineTime[burstIndex] / Constants.secondsInDay;

        final double secBurstFirstLineTimeSecondsOfDay = (secBurstFirstLineTimeMJD - (int)secBurstFirstLineTimeMJD) *
                Constants.secondsInDay;

        secMeta.settAzi1(secBurstFirstLineTimeSecondsOfDay);

        secMeta.setCurrentWindow(new Window(0, subSwath[subSwathIndex - 1].linesPerBurst - 1,
                0, subSwath[subSwathIndex - 1].samplesPerBurst - 1));

        secMeta.setOriginalWindow(new Window(0, subSwath[subSwathIndex - 1].linesPerBurst - 1,
                0, subSwath[subSwathIndex - 1].samplesPerBurst - 1));
    }

    private GeoPoint getApproxGeoCentre(final int subSwathIndex, final int burstIndex) {

        final int cols = subSwath[subSwathIndex - 1].latitude[0].length;

        double lat = 0.0, lon = 0.0;
        for (int r = burstIndex; r <= burstIndex + 1; r++) {
            for (int c = 0; c < cols; c++) {
                lat += subSwath[subSwathIndex - 1].latitude[r][c];
                lon += subSwath[subSwathIndex - 1].longitude[r][c];
            }
        }

        return new GeoPoint(lat / (2*cols), lon / (2*cols));
    }

    public static DoubleMatrix normalizeDoubleMatrix(DoubleMatrix matrix, final double min, final double max) {
        matrix.subi(0.5 * (min + max));
        matrix.divi(0.25 * (max - min));
        return matrix;
    }

    // For S1 SM SLC product
    private double[][] computeETADPhase(final Rectangle rectangle) {

        if (refETADPhaseBand == null || secETADPhaseBand == null) {
            return null;
        }

        if (!performHeightCorrection) {
            return computeETADPhaseWithoutHeightCompensation(rectangle);
        } else {
            return computeETADPhaseWithHeightCompensation(rectangle);
        }
    }

    private double[][] computeETADPhaseWithoutHeightCompensation(final Rectangle rectangle) {

        final int x0 = rectangle.x;
        final int y0 = rectangle.y;
        final int w = rectangle.width;
        final int h = rectangle.height;
        final int xMax = x0 + w;
        final int yMax = y0 + h;

        final Tile refETADPhaseTile = getSourceTile(refETADPhaseBand, rectangle);
        final ProductData refETADPhaseData = refETADPhaseTile.getDataBuffer();
        final TileIndex refPhaseIndex = new TileIndex(refETADPhaseTile);

        final Tile secETADPhaseTile = getSourceTile(secETADPhaseBand, rectangle);
        final ProductData secETADPhaseData = secETADPhaseTile.getDataBuffer();
        final TileIndex secPhaseIndex = new TileIndex(secETADPhaseTile);

        final double refNoDataValue = refETADPhaseBand.getNoDataValue();
        final double secNoDataValue = secETADPhaseBand.getNoDataValue();

        final double[][] etadPhase = new double[h][w];
        for (int y = y0; y < yMax; ++y) {
            refPhaseIndex.calculateStride(y);
            secPhaseIndex.calculateStride(y);
            final int yy = y - y0;

            for (int x = x0; x < xMax; ++x) {
                final int refPhaseIdx = refPhaseIndex.getIndex(x);
                final int secPhaseIdx = secPhaseIndex.getIndex(x);
                final int xx = x - x0;

                final double refETADPhase = refETADPhaseData.getElemDoubleAt(refPhaseIdx);
                final double secETADPhase = secETADPhaseData.getElemDoubleAt(secPhaseIdx);

                if (refETADPhase == refNoDataValue || secETADPhase == secNoDataValue) {
                    etadPhase[yy][xx] = refNoDataValue;
                } else {
                    etadPhase[yy][xx] = refETADPhase - secETADPhase;
                }
            }
        }
        return etadPhase;
    }

    private double[][] computeETADPhaseWithHeightCompensation(final Rectangle rectangle) {

        final int x0 = rectangle.x;
        final int y0 = rectangle.y;
        final int w = rectangle.width;
        final int h = rectangle.height;
        final int xMax = x0 + w;
        final int yMax = y0 + h;

        final Tile refETADPhaseTile = getSourceTile(refETADPhaseBand, rectangle);
        final ProductData refETADPhaseData = refETADPhaseTile.getDataBuffer();
        final TileIndex refPhaseIndex = new TileIndex(refETADPhaseTile);

        final Tile refETADHeightTile = getSourceTile(refETADHeightBand, rectangle);
        final ProductData refETADHeightData = refETADHeightTile.getDataBuffer();
        final TileIndex refHeightIndex = new TileIndex(refETADHeightTile);

        final Tile secETADPhaseTile = getSourceTile(secETADPhaseBand, rectangle);
        final ProductData secETADPhaseData = secETADPhaseTile.getDataBuffer();
        final TileIndex secPhaseIndex = new TileIndex(secETADPhaseTile);

        final Tile secETADHeightTile = getSourceTile(secETADHeightBand, rectangle);
        final ProductData secETADHeightData = secETADHeightTile.getDataBuffer();
        final TileIndex secHeightIndex = new TileIndex(secETADHeightTile);

        final Tile secETADGradientTile = getSourceTile(secETADGradientBand, rectangle);
        final ProductData secETADGradientData = secETADGradientTile.getDataBuffer();
        final TileIndex secGradientIndex = new TileIndex(secETADGradientTile);

        final double refNoDataValue = refETADPhaseBand.getNoDataValue();
        final double secNoDataValue = secETADPhaseBand.getNoDataValue();

        final double[][] etadPhase = new double[h][w];
        for (int y = y0; y < yMax; ++y) {
            refPhaseIndex.calculateStride(y);
            refHeightIndex.calculateStride(y);
            secPhaseIndex.calculateStride(y);
            secHeightIndex.calculateStride(y);
            secGradientIndex.calculateStride(y);
            final int yy = y - y0;

            for (int x = x0; x < xMax; ++x) {
                final int refPhaseIdx = refPhaseIndex.getIndex(x);
                final int refHeightIdx = refPhaseIndex.getIndex(x);
                final int secPhaseIdx = secPhaseIndex.getIndex(x);
                final int secHeightIdx = secHeightIndex.getIndex(x);
                final int secGradientIdx = secGradientIndex.getIndex(x);
                final int xx = x - x0;

                final double refETADPhase = refETADPhaseData.getElemDoubleAt(refPhaseIdx);
                final double secETADPhase = secETADPhaseData.getElemDoubleAt(secPhaseIdx);
                final double refETADHeight = refETADHeightData.getElemDoubleAt(refHeightIdx);
                final double secETADHeight = secETADHeightData.getElemDoubleAt(secHeightIdx);
                final double secETADGradient = secETADGradientData.getElemDoubleAt(secGradientIdx);

                if (refETADPhase == refNoDataValue || secETADPhase == secNoDataValue) {
                    etadPhase[yy][xx] = refNoDataValue;
                } else {
                    etadPhase[yy][xx] = refETADPhase - secETADPhase - secETADGradient * (refETADHeight - secETADHeight);
                }
            }
        }
        return etadPhase;
    }

    //vvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvv For S1 TOPS IW SLC product vvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvv
    private double[][] computeETADPhase(final Rectangle rectangle, final int burstIndex,
                                        final Map<Integer, Integer> refSecBurstMap,
                                        final String refDate, final String secDate) {

        if (!performHeightCorrection) {
            return computeETADPhaseWithoutHeightCompensation(rectangle, burstIndex, refSecBurstMap, refDate, secDate);
        } else {
            return computeETADPhaseWithHeightCompensation(rectangle, burstIndex, refSecBurstMap, refDate, secDate);
        }
    }

    private Map<Integer, Integer> createRefSecBurstMap(final String secondaryProductDate) {

        final Map<Integer, Integer> refSecBurstMap = new HashMap<>();
        MetadataElement secondaryElem = StackUtils.findSecondaryMetadataRoot(sourceProduct);
        if (secondaryElem == null) {
            return null;
        }
        final MetadataElement[] secondaryRoot = secondaryElem.getElements();
        for (MetadataElement meta : secondaryRoot) {
            if(meta.getName().contains(secondaryProductDate)) {
                final MetadataElement etadBurstsElem = meta.getElement("ETAD_Burst_Index_Array");
                final String refBursts = etadBurstsElem.getAttributeString("master_bursts");
                final String secBursts = etadBurstsElem.getAttributeString("slave_bursts");
                final Integer[] refBurstArray = stringToIntegerArray(refBursts);
                final Integer[] secBurstArray = stringToIntegerArray(secBursts);
                for (int i = 0; i < refBurstArray.length; ++i) {
                    refSecBurstMap.put(refBurstArray[i], secBurstArray[i]);
                }
                break;
            }
        }
        return refSecBurstMap;
    }

    private Integer[] stringToIntegerArray(final String inputStr) {
        String[] inputStrArray = inputStr.split(" ");
        return Stream.of(inputStrArray).mapToInt(Integer::parseInt).boxed().toArray(Integer[]::new);
    }


    private double[][] computeETADPhaseWithoutHeightCompensation(final Rectangle rectangle, final int prodBurstIndex,
                                                                 final Map<Integer, Integer> refSecBurstMap,
                                                                 final String refDate, final String secDate) {

        final int x0 = rectangle.x;
        final int y0 = rectangle.y;
        final int w = rectangle.width;
        final int h = rectangle.height;
        final int xMax = x0 + w;
        final int yMax = y0 + h;

        final double burstAzTime = 0.5 * (subSwath[subSwathIndex - 1].burstFirstLineTime[prodBurstIndex] +
                subSwath[subSwathIndex - 1].burstLastLineTime[prodBurstIndex]);

        final Burst refBurst = getETADBurst(burstAzTime, subSwath[subSwathIndex - 1].subSwathName, sourceProduct);
        if (refBurst == null) {
            return null;
        }
        final int secBurstIndex = refSecBurstMap.get(refBurst.bIndex);

        final double[][] refETADPhaseBurstData = getETADBurstData(ETAD_PHASE_CORRECTION, refBurst.bIndex, refDate, "ref");
        final double[][] secETADPhaseBurstData = getETADBurstData(ETAD_PHASE_CORRECTION, secBurstIndex, secDate, "sec");

        final double[][] etadPhase = new double[h][w];
        for (int y = y0; y < yMax; ++y) {
            final int yy = y - y0;
            final double azTime = subSwath[subSwathIndex - 1].burstFirstLineTime[prodBurstIndex] +
                    (y - prodBurstIndex * subSwath[subSwathIndex - 1].linesPerBurst) *
                            subSwath[subSwathIndex - 1].azimuthTimeInterval;

            for (int x = x0; x < xMax; ++x) {
                final int xx = x - x0;
                final double rgTime = 2.0 * (subSwath[subSwathIndex - 1].slrTimeToFirstPixel + x * su.rangeSpacing /
                        Constants.lightSpeed);
                final double refETADPhase = getETADData(azTime, rgTime, refETADPhaseBurstData, refBurst);
                final double secETADPhase = getETADData(azTime, rgTime, secETADPhaseBurstData, refBurst);
                etadPhase[yy][xx] = refETADPhase - secETADPhase;
            }
        }
        return etadPhase;
    }

    public static Burst getETADBurst(final double burstAzTime, final String subSwathName, final Product sourceProduct) {

        final MetadataElement etadElem = sourceProduct.getMetadataRoot().getElement("ETAD_Product_Metadata");
        final MetadataElement annotationElem = etadElem.getElement("annotation");
        final MetadataElement etadProductElem = annotationElem.getElement("etadProduct");
        final MetadataElement etadBurstListElem = etadProductElem.getElement("etadBurstList");
        final MetadataElement[] elements = etadBurstListElem.getElements();

        for (MetadataElement elem : elements) {
            final MetadataElement burstCoverageElem = elem.getElement("burstCoverage");
            final MetadataElement burstDataElem = elem.getElement("burstData");
            final String swathID = burstDataElem.getAttributeString("swathID").toLowerCase();
            if (!subSwathName.toLowerCase().equals(swathID)) {
                continue;
            }
            final MetadataElement temporalCoverageElem = burstCoverageElem.getElement("temporalCoverage");
            final double azimuthTimeMin = getTime(temporalCoverageElem, "azimuthTimeMin").getMJD()*Constants.secondsInDay;
            final double azimuthTimeMax = getTime(temporalCoverageElem, "azimuthTimeMax").getMJD()*Constants.secondsInDay;
            if (burstAzTime > azimuthTimeMin && burstAzTime < azimuthTimeMax) {
                final MetadataElement rangeTimeMinElem = temporalCoverageElem.getElement("rangeTimeMin");
                final MetadataElement rangeTimeMaxElem = temporalCoverageElem.getElement("rangeTimeMax");
                final MetadataElement gridInformationElem = elem.getElement("gridInformation");
                final MetadataElement gridSamplingElem = gridInformationElem.getElement("gridSampling");
                final MetadataElement azimuth = gridSamplingElem.getElement("azimuth");
                final MetadataElement rangeElem = gridSamplingElem.getElement("range");

                final Burst burst = new Burst();
                burst.bIndex = Integer.parseInt(burstDataElem.getAttributeString("bIndex"));
                burst.azimuthTimeMin = azimuthTimeMin;
                burst.azimuthTimeMax = azimuthTimeMax;
                burst.rangeTimeMin = Double.parseDouble(rangeTimeMinElem.getAttributeString("rangeTimeMin"));
                burst.rangeTimeMax = Double.parseDouble(rangeTimeMaxElem.getAttributeString("rangeTimeMax"));
                burst.gridSamplingAzimuth = Double.parseDouble(azimuth.getAttributeString("azimuth"));
                burst.gridSamplingRange = Double.parseDouble(rangeElem.getAttributeString("range"));
                return burst;
            }
        }
        return null;
    }

    private double[][] getETADBurstData(final String layer, final int burstIndex, final String prodDate, final String suffix) {

        final TiePointGrid[] tpgs = sourceProduct.getTiePointGrids();
        float[] tiePoints = null;
        int w = 0, h = 0;
        for (TiePointGrid tpg : tpgs) {
            final String tpgName = tpg.getName();
            if (tpgName.startsWith(layer) && tpgName.contains(burstIndex + "_" + suffix) && tpgName.contains(prodDate)) {
                tiePoints = tpg.getTiePoints();
                w = tpg.getGridWidth();
                h = tpg.getGridHeight();
                break;
            }
        }

        if (tiePoints == null) {
            return null;
        }

        final double[][] etadData = new double[h][w];
        for (int r = 0; r < h; ++r) {
            for (int c = 0; c < w; ++c) {
                etadData[r][c] = tiePoints[r*w + c];
            }
        }
        return etadData;
    }

    private static ProductData.UTC getTime(final MetadataElement elem, final String tag) {

        DateFormat sentinelDateFormat = ProductData.UTC.createDateFormat("yyyy-MM-dd HH:mm:ss");
        String start = elem.getAttributeString(tag, AbstractMetadata.NO_METADATA_STRING);
        start = start.replace("T", " ");
        return AbstractMetadata.parseUTC(start, sentinelDateFormat);
    }

    private double getETADData(final double azimuthTime, final double slantRangeTime, final double[][] data,
                               final Burst burst) {

        if (burst == null) {
            return 0.0;
        }

        final double i = (azimuthTime - burst.azimuthTimeMin) / burst.gridSamplingAzimuth;
        final double j = (slantRangeTime - burst.rangeTimeMin) / burst.gridSamplingRange;
        final int i0 = (int)i;
        final int i1 = i0 + 1;
        final int j0 = (int)j;
        final int j1 = j0 + 1;
        final double c00 = data[i0][j0];
        final double c01 = data[i0][j1];
        final double c10 = data[i1][j0];
        final double c11 = data[i1][j1];
        return Maths.interpolationBiLinear(c00, c01, c10, c11, j - j0, i - i0);
    }

    private double[][] computeETADPhaseWithHeightCompensation(final Rectangle rectangle, final int prodBurstIndex,
                                                              final Map<Integer, Integer> refSecBurstMap,
                                                              final String refDate, final String secDate) {

        final int x0 = rectangle.x;
        final int y0 = rectangle.y;
        final int w = rectangle.width;
        final int h = rectangle.height;
        final int xMax = x0 + w;
        final int yMax = y0 + h;

        final double refBurstAzTime = 0.5 * (subSwath[subSwathIndex - 1].burstFirstLineTime[prodBurstIndex] +
                subSwath[subSwathIndex - 1].burstLastLineTime[prodBurstIndex]);

        final Burst refBurst = getETADBurst(refBurstAzTime, subSwath[subSwathIndex - 1].subSwathName, sourceProduct);
        if (refBurst == null || !refSecBurstMap.containsKey(refBurst.bIndex)) {
            return null;
        }

        final int secBurstIndex = refSecBurstMap.get(refBurst.bIndex);

        final double[][] refETADPhaseBurstData = getETADBurstData(ETAD_PHASE_CORRECTION, refBurst.bIndex, refDate, "ref");
        final double[][] refETADHeightBurstData = getETADBurstData(ETAD_HEIGHT, refBurst.bIndex, refDate, "ref");
        final double[][] secETADPhaseBurstData = getETADBurstData(ETAD_PHASE_CORRECTION, secBurstIndex, secDate, "sec");
        final double[][] secETADHeightBurstData = getETADBurstData(ETAD_HEIGHT, secBurstIndex, secDate, "sec");
        final double[][] secETADGradientBurstData = getETADBurstData(ETAD_GRADIENT, secBurstIndex, secDate, "sec");

        final double[][] etadPhase = new double[h][w];
        for (int y = y0; y < yMax; ++y) {
            final int yy = y - y0;
            final double azTime = subSwath[subSwathIndex - 1].burstFirstLineTime[prodBurstIndex] +
                    (y - prodBurstIndex * subSwath[subSwathIndex - 1].linesPerBurst) *
                            subSwath[subSwathIndex - 1].azimuthTimeInterval;

            for (int x = x0; x < xMax; ++x) {
                final int xx = x - x0;
                final double rgTime = 2.0 * (subSwath[subSwathIndex - 1].slrTimeToFirstPixel + x * su.rangeSpacing /
                        Constants.lightSpeed);

                final double refETADPhase = getETADData(azTime, rgTime, refETADPhaseBurstData, refBurst);
                final double secETADPhase = getETADData(azTime, rgTime, secETADPhaseBurstData, refBurst);
                final double refETADHeight = getETADData(azTime, rgTime, refETADHeightBurstData, refBurst);
                final double secETADHeight = getETADData(azTime, rgTime, secETADHeightBurstData, refBurst);
                final double secETADGradient = getETADData(azTime, rgTime, secETADGradientBurstData, refBurst);

                etadPhase[yy][xx] = refETADPhase - secETADPhase - secETADGradient * (refETADHeight - secETADHeight);
            }
        }
        return etadPhase;
    }

    public final static class Burst {
        public String swathID;
        public int bIndex;
        public double rangeTimeMin;
        public double rangeTimeMax;
        public double azimuthTimeMin;
        public double azimuthTimeMax;
        public double gridSamplingAzimuth;
        public double gridSamplingRange;
    }
    //^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

    public int getBurstIndex(final int y, final int linesPerBurst) {
        return y / linesPerBurst;
    }


    /**
     * The SPI is used to register this operator in the graph processing framework
     * via the SPI configuration file
     * {@code META-INF/services/org.esa.snap.core.gpf.OperatorSpi}.
     * This class may also serve as a factory for new operator instances.
     *
     * @see OperatorSpi#createOperator()
     * @see OperatorSpi#createOperator(Map, Map)
     */
    public static class Spi extends OperatorSpi {

        public Spi() {
            super(InterferogramOp.class);
        }
    }

}
