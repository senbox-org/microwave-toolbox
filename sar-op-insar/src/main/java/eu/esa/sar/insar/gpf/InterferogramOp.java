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

    @Parameter(description = "Coherence estimation window size in metres. When > 0, " +
            "overrides cohWinAz/cohWinRg at initialization by converting from the pixel " +
            "spacing of the (geocoded) inputs. Recommended for GSLC inputs so the multilook " +
            "support is set in physical units regardless of map-grid pixel size.",
            defaultValue = "0",
            label = "Coherence Window (m)")
    private double cohWinSizeMeters = 0.0;

    @Parameter(description = "Read-only status flag set at initialization when the operator " +
            "auto-detects GSLC inputs (is_terrain_corrected=1). In GSLC mode the interferogram is " +
            "the conjugate product with the flat-Earth (and, if enabled, topographic) phase removed " +
            "in map geometry. Visible for auditability.",
            defaultValue = "false",
            label = "GSLC mode auto-detected")
    private boolean gslcModeAutoDetected = false;

    @Parameter(description = "Use ground square pixel", defaultValue = "true", label = "Square Pixel")
    private Boolean squarePixel = true;

    @Parameter(defaultValue="false", label="Subtract topographic phase")
    private boolean subtractTopographicPhase = false;

    @Parameter(description = "GSLC mode only: estimate and remove the smooth residual phase ramp " +
            "left by cross-acquisition GSLC interferometry (annotation-vs-data deramp mismatch, " +
            "typically ~1 fringe per 80 px). The estimate is a low-order (quadratic) polynomial " +
            "fitted robustly to block-wise fringe gradients — deliberately too rigid to absorb " +
            "localized deformation signals. Like any ramp removal it will also absorb a genuine " +
            "scene-wide linear deformation gradient, so it is off by default.",
            defaultValue = "false", label = "Subtract residual ramp (GSLC)")
    private boolean subtractResidualRamp = false;
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
    private volatile boolean flatEarthEstimated = false;

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

    // GSLC flat-earth + topographic phase removal, recomputed in map geometry.
    private boolean gslcRemoveRefPhase = false;
    private GeoCoding gslcGeoCoding;
    private SLCImage gslcRefSLC;
    private Orbit gslcRefOrbit;
    private final Map<Band, SLCImage> gslcSecSLCMap = new HashMap<>();
    private final Map<Band, Orbit> gslcSecOrbitMap = new HashMap<>();
    private static final int GSLC_REFPHASE_SUBSAMPLE = 10; // px grid step for the smooth reference-phase surface

    // GSLC residual-ramp removal: per-pair quadratic phase polynomial
    // phi(x,y) = c0*x + c1*y + c2*x^2 + c3*x*y + c4*y^2   (x, y normalised by GSLC_RAMP_NORM)
    private volatile boolean gslcRampEstimated = false;
    private final Object gslcRampLock = new Object();
    private double[][] gslcRampCoef;                        // [pair][5], null row = estimation failed
    private static final double GSLC_RAMP_NORM = 1000.0;    // px, conditioning for the LS fit
    private static final int GSLC_RAMP_BLOCK = 384;         // px, estimation block size
    private static final int GSLC_RAMP_ML = 8;              // multilook factor inside a block

    // Per-burst extension of the residual ramp. The TOPS deramp-annotation error differs per burst
    // (each burst has its own DC/FM annotation), so across two acquisitions the residual is a
    // per-burst quadratic-in-azimuth with genuine discontinuities at burst seams — measured on the
    // 2026 Venezuela S1A x S1C pair as ~6 rad/burst around mid-scene and far larger in the northern
    // bursts, where a single scene-wide quadratic leaves dense fringes. Burst intervals are read
    // from the reference's Original_Product_Metadata annotation (a plain metadata walk: this module
    // must not depend on sar-op-sentinel1, and the walk also works on stacks that predate any
    // stamping). When the walk fails (stripmap GSLC, pruned metadata) the global fit applies.
    private double[] gslcBurstStartSod;                     // reference-burst azimuth start, seconds of day
    private double[] gslcBurstEndSod;                       // reference-burst azimuth end, seconds of day
    private GslcPerBurstRamp[] gslcRampPerBurst;            // [pair], null entry = global fallback

    // Exact carrier-difference subtraction: when both legs carry the GSLC deramp-model band
    // (GSLCGeocodingOp outputPhaseTerms, propagated by CreateStack), the interferogram subtracts
    // the models' leg difference EXACTLY — the deterministic ~70% of the cross-acquisition
    // annotation mismatch, including its full range and azimuth structure within every burst,
    // which no low-order fit can represent. subtractResidualRamp then only fits the smooth
    // annotation-ERROR remainder.
    private static final String GSLC_CARRIER_MODEL_BAND = "azimuthCarrierPhase";
    private Band[] gslcRefCarrierBand;                      // [pair], null = band not available
    private Band[] gslcSecCarrierBand;
    private static final int GSLC_RAMP_MIN_BURST_BLOCKS = 4; // fewer -> burst inherits neighbours

    /**
     * Per-burst residual-ramp model, parameterised in AZIMUTH TIME. Within burst {@code k}:
     * {@code phi_k(x, eta) = dk[k] + aN*(x/N) + c2N*(x/N)^2 + bk[k]*(eta-etaK[k]) + qk[k]*(eta-etaK[k])^2}
     * with {@code eta} the reference-orbit azimuth time (seconds of day) of the ground point.
     * <p>
     * Azimuth time — not map row — is the physical axis of the deramp-annotation error, and
     * iso-eta lines are TILTED ~10-12° in map space. A map-row parameterisation leaks each burst's
     * azimuth rate into a per-burst x-gradient (rate difference × tilt ≈ 19 rad across a swath,
     * measured), which a shared range slope cannot hold; in eta the tilt is exact. The shared x
     * terms then carry only the genuine range-direction gradient. {@code dk} are per-burst
     * constants, unobservable from gradients, estimated from the phase itself — burst seams are
     * genuine discontinuities, so no continuity is imposed across them.
     */
    static final class GslcPerBurstRamp {
        final double aN, c2N;
        final double[] etaK, bk, qk, dk;     // eta centres (sod), rad/s, rad/s^2, rad
        final double[] burstStartSod, burstEndSod;

        GslcPerBurstRamp(final double aN, final double c2N, final double[] etaK, final double[] bk,
                         final double[] qk, final double[] dk,
                         final double[] burstStartSod, final double[] burstEndSod) {
            this.aN = aN; this.c2N = c2N; this.etaK = etaK; this.bk = bk; this.qk = qk; this.dk = dk;
            this.burstStartSod = burstStartSod; this.burstEndSod = burstEndSod;
        }

        /** Burst index for an azimuth time (seconds of day); overlap resolved at the midpoint. */
        int burstOfSod(final double tSod) {
            final int n = burstStartSod.length;
            for (int k = 0; k < n - 1; k++) {
                final double boundary = 0.5 * (burstStartSod[k + 1] + burstEndSod[k]);
                if (tSod < boundary) return k;
            }
            return n - 1;
        }

        double phaseAt(final double x, final double etaSod, final int k) {
            final double xn = x / GSLC_RAMP_NORM;
            final double de = etaSod - etaK[k];
            return dk[k] + aN * xn + c2N * xn * xn + bk[k] * de + qk[k] * de * de;
        }

        /** Within-burst azimuth phase rate (rad/s) at azimuth time {@code etaSod}. */
        double rateAt(final double etaSod, final int k) {
            return bk[k] + 2.0 * qk[k] * (etaSod - etaK[k]);
        }
    }

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
    /** Written where a coherence window contains no valid sample pair, so the mask matches the ifg. */
    private static final double COHERENCE_NO_DATA = 0.0;
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
                gslcModeAutoDetected = true;
                // ETAD is deliberately NOT handled on this path, and checkETADCorrection() below is
                // unreachable here. That is correct: the ETAD tie-point grids are per-burst grids keyed
                // on burst azimuth time and two-way slant-range time, so they are meaningless once the
                // product is in map geometry. For the geocode-first chain the correction is baked into
                // the complex data upstream by S1-ETAD-Correction (run with both the geometric
                // correction and the range-delay phase enabled) and simply survives geocoding.
                //
                // What this path MUST still do is verify symmetry - see checkETADStateSymmetry.
                checkETADStateSymmetry(absRoot);
                resolveCoherenceWindowFromMeters();
                initializeGSLC();
                return;
            }
            resolveCoherenceWindowFromMeters();

            if(absRoot.containsAttribute("multireference_split")){
                refRoot = StackUtils.findSecondaryMetadataRoot(sourceProduct).getElementAt(0);
            } else{
                refRoot = absRoot;
            }

            checkUserInput();

            constructSourceMetadata();

            // Defense-in-depth: previously, if no band matched the ref/sec tags (e.g. PhaseLinking
            // output prior to the tagging fix), both maps would silently stay empty and produce a
            // target product with zero interferogram bands - a silent failure with no error. Now
            // throw a clear message identifying the actual cause.
            if (referenceMap.isEmpty() || secondaryMap.isEmpty()) {
                final StringBuilder bandList = new StringBuilder();
                for (String n : sourceProduct.getBandNames()) {
                    if (bandList.length() > 0) bandList.append(", ");
                    bandList.append(n);
                }
                throw new OperatorException("InterferogramOp: no " +
                        (referenceMap.isEmpty() ? "reference" : "secondary") +
                        " band pair found in source product. Bands must be a coregistered SLC stack " +
                        "with '_ref'/'_sec' (or legacy '_mst'/'_slv') tags in their names. " +
                        "Source bands: [" + bandList + "].");
            }

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
     * The interferogram is the complex conjugate product of reference and secondary; the flat-earth
     * (and, if enabled, topographic) phase is then removed in map geometry — see
     * {@link #setupGSLCReferencePhase()} and {@link #computeGslcReferencePhase}.
     */
    /**
     * Convert {@link #cohWinSizeMeters} (if > 0) into pixel-based
     * {@link #cohWinAz}/{@link #cohWinRg} using the source product's pixel spacing.
     * Recommended for geocoded inputs so the multilook support stays at a fixed
     * physical scale regardless of map-grid pixel size.
     */
    /**
     * Warn when the pixel-count coherence window implies a strongly elongated ground footprint.
     * <p>
     * {@code cohWinAz} and {@code cohWinRg} both default to 10, which is square only when the pixels
     * are. In S1 IW radar geometry (~2.3 m slant range x ~14 m azimuth) a 10x10 window spans roughly
     * 23 m x 140 m — a 6:1 footprint. In a geocoded product with square pixels the same numbers give
     * 1:1. So the default silently means very different things in the two geometries, and results from
     * the classical and geocode-first chains are not comparable unless this is set deliberately.
     * <p>
     * Advisory only — it never alters the result, and it stays silent when the window is already
     * sensible for the geometry at hand.
     */
    private void warnIfCoherenceWindowIsGeometryBlind() {
        try {
            final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(sourceProduct);
            if (abs == null) {
                return;
            }
            final double rgSpacing = AbstractMetadata.getAttributeDouble(abs, AbstractMetadata.range_spacing);
            final double azSpacing = AbstractMetadata.getAttributeDouble(abs, AbstractMetadata.azimuth_spacing);
            if (rgSpacing <= 0.0 || azSpacing <= 0.0 || cohWinRg <= 0 || cohWinAz <= 0) {
                return;
            }
            final double rgExtent = cohWinRg * rgSpacing;
            final double azExtent = cohWinAz * azSpacing;
            final double aspect = Math.max(rgExtent, azExtent) / Math.min(rgExtent, azExtent);
            if (aspect > 2.0) {
                SystemUtils.LOG.warning(String.format(
                        "InterferogramOp: coherence window cohWinRg=%d x cohWinAz=%d spans about "
                        + "%.0f m x %.0f m, an aspect ratio of %.1f:1, so coherence is averaged over a "
                        + "strongly elongated footprint. Consider setting 'Coherence Window (m)' "
                        + "(cohWinSizeMeters) instead: it yields a window that is square on the ground "
                        + "whatever the geometry, and makes radar-geometry and geocoded results "
                        + "directly comparable.",
                        cohWinRg, cohWinAz, rgExtent, azExtent, aspect));
            }
        } catch (Exception e) {
            SystemUtils.LOG.fine("InterferogramOp: coherence window advisory skipped: " + e.getMessage());
        }
    }

    private void resolveCoherenceWindowFromMeters() throws Exception {
        if (cohWinSizeMeters <= 0.0) {
            warnIfCoherenceWindowIsGeometryBlind();
            return;
        }
        final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(sourceProduct);
        if (abs == null) return;
        final double rgSpacing = AbstractMetadata.getAttributeDouble(abs, AbstractMetadata.range_spacing);
        final double azSpacing = AbstractMetadata.getAttributeDouble(abs, AbstractMetadata.azimuth_spacing);
        if (rgSpacing > 0.0) {
            cohWinRg = Math.max(3, (int) Math.round(cohWinSizeMeters / rgSpacing));
        }
        if (azSpacing > 0.0) {
            cohWinAz = Math.max(3, (int) Math.round(cohWinSizeMeters / azSpacing));
        }
        SystemUtils.LOG.info(String.format(
                "InterferogramOp: cohWinSizeMeters=%.1f m -> cohWinAz=%d, cohWinRg=%d (pixel spacing az=%.2f m, rg=%.2f m)",
                cohWinSizeMeters, cohWinAz, cohWinRg, azSpacing, rgSpacing));
    }

    /**
     * Pick the reference band a given secondary should be interfered against. With a single
     * reference — the usual GSLC stack — that is the only candidate. With several (one per
     * polarisation) the match is made on polarisation, so a multi-pol stack pairs like with like
     * instead of relying on band order.
     *
     * @throws OperatorException if no reference can be identified, rather than silently pairing
     *                           mismatched polarisations or truncating the stack.
     */
    private static Band selectGslcReferenceFor(final Band secI, final List<Band> refIBands) {
        if (refIBands.size() == 1) {
            return refIBands.get(0);
        }
        final String secPol = OperatorUtils.getPolarizationFromBandName(secI.getName());
        final String secSwath = extractSubSwath(secI.getName());
        if (secPol != null) {
            // Match on subswath AND polarisation. Polarisation alone is not a discriminator: two
            // references can share a polarisation and differ by subswath (or by date, in a
            // multi-reference stack), in which case the first match won and an IW2 secondary was
            // silently paired against an IW1 reference — with the output band labelled IW1.
            for (final Band refI : refIBands) {
                if (secPol.equalsIgnoreCase(OperatorUtils.getPolarizationFromBandName(refI.getName()))
                        && java.util.Objects.equals(secSwath, extractSubSwath(refI.getName()))) {
                    return refI;
                }
            }
        }
        throw new OperatorException("GSLC interferogram: cannot pair secondary band '" + secI.getName()
                + "' with a reference band — " + refIBands.size() + " reference bands were found"
                + (secPol == null
                        ? " and the secondary carries no polarisation tag to match on."
                        : " but none of them has polarisation '" + secPol + "'."));
    }

    /** Find the Q band of the same complex pair as {@code iBand} by name ("i_x" -> "q_x"). */
    /** The IW1/IW2/IW3/EW1.. subswath token in a band name, or null if it carries none. */
    private static String extractSubSwath(final String bandName) {
        final java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(?:IW|EW)[1-5]").matcher(bandName.toUpperCase());
        return m.find() ? m.group() : null;
    }

    /**
     * The {@code sec1}/{@code sec2}/... (or legacy {@code slv1}/...) discriminator CreateStack adds to
     * each secondary's bands. Returns null when the name carries none.
     */
    private static String extractSecondaryTag(final String bandName) {
        final java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(?:sec|slv)\\d+").matcher(bandName.toLowerCase());
        return m.find() ? m.group() : null;
    }

    private static Band findComplexPartner(final Band iBand, final List<Band> qBands) {
        final String iName = iBand.getName();
        if (iName.isEmpty() || Character.toLowerCase(iName.charAt(0)) != 'i') {
            return null;
        }
        final String expected = 'q' + iName.substring(1);
        for (final Band q : qBands) {
            if (q.getName().equalsIgnoreCase(expected)) {
                return q;
            }
        }
        return null;
    }

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

        // Pair the reference against EVERY secondary. A GSLC stack is one reference + N
        // secondaries, so the previous Math.min(refIBands.size(), secIBands.size()) evaluated to 1
        // and silently dropped secondaries 2..N from the output. Several reference I-bands are
        // legitimate (one per polarisation), in which case each secondary pairs with the reference
        // of its own polarisation rather than by list position.
        final List<Band> pairRefI = new ArrayList<>();
        final List<Band> pairRefQ = new ArrayList<>();
        final List<Band> pairSecI = new ArrayList<>();
        final List<Band> pairSecQ = new ArrayList<>();

        for (final Band secI : secIBands) {
            final Band refI = selectGslcReferenceFor(secI, refIBands);

            Band refQ = findComplexPartner(refI, refQBands);
            if (refQ == null && refIBands.size() == refQBands.size()) {
                refQ = refQBands.get(refIBands.indexOf(refI));   // legacy positional fallback
            }
            Band secQ = findComplexPartner(secI, secQBands);
            if (secQ == null && secIBands.size() == secQBands.size()) {
                secQ = secQBands.get(secIBands.indexOf(secI));
            }
            if (refQ == null || secQ == null) {
                throw new OperatorException("GSLC interferogram: no imaginary-part (q_) band matching '"
                        + (refQ == null ? refI.getName() : secI.getName()) + "'.");
            }

            pairRefI.add(refI);
            pairRefQ.add(refQ);
            pairSecI.add(secI);
            pairSecQ.add(secQ);
        }

        final int numPairs = pairSecI.size();
        gslcReferenceI = pairRefI.toArray(new Band[0]);
        gslcReferenceQ = pairRefQ.toArray(new Band[0]);
        gslcSecondaryI = pairSecI.toArray(new Band[0]);
        gslcSecondaryQ = pairSecQ.toArray(new Band[0]);

        // Create target product
        targetProduct = new Product(sourceProduct.getName() + PRODUCT_SUFFIX,
                sourceProduct.getProductType(), sourceImageWidth, sourceImageHeight);
        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        gslcTargetI = new Band[numPairs];
        gslcTargetQ = new Band[numPairs];
        gslcTargetCoh = includeCoherence ? new Band[numPairs] : null;

        final java.util.Set<String> usedTags = new java.util.HashSet<>();
        for (int p = 0; p < numPairs; p++) {
            // Derive tag from the reference band name, then append the secondary's date so the
            // pair is identifiable — the normal (non-GSLC) path names bands
            // "ifg_<swath>_<pol>_<refDate>_<secDate>", and dropping the secondary date here made
            // GSLC interferograms ambiguous in a multi-secondary stack.
            final String refName = gslcReferenceI[p].getName();
            final String suffix = refName.substring(refName.indexOf('_'));
            final String baseTag = suffix.replace("_ref", "").replace("_mst", "");

            final String secName = gslcSecondaryI[p].getName();
            final int lastUs = secName.lastIndexOf('_');
            final String secDate = (lastUs >= 0 && lastUs < secName.length() - 1)
                    ? secName.substring(lastUs + 1) : "";
            String tag = (secDate.isEmpty() || baseTag.endsWith('_' + secDate))
                    ? baseTag : baseTag + '_' + secDate;

            // The date alone does not identify a secondary: two acquisitions on the same day (S1A +
            // S1B, or two frames) collapse to one name and Product.addBand then rejects the
            // duplicate. CreateStack already emits a sec1/sec2/... discriminator for exactly this
            // reason, so carry it through when the date-based tag is not already unique.
            final String secTag = extractSecondaryTag(secName);
            if (secTag != null && !tag.contains(secTag)) {
                final String candidate = baseTag + '_' + secTag + (secDate.isEmpty() ? "" : '_' + secDate);
                if (usedTags.contains(tag)) {
                    tag = candidate;
                }
            }
            if (usedTags.contains(tag)) {
                throw new OperatorException("GSLC interferogram: cannot form a unique band name for "
                        + "secondary '" + secName + "' (tag '" + tag + "' already used). Rename the "
                        + "stack bands so each secondary is distinguishable.");
            }
            usedTags.add(tag);

            final String iBandName = "i_" + productTag + tag;
            gslcTargetI[p] = targetProduct.addBand(iBandName, ProductData.TYPE_FLOAT32);
            gslcTargetI[p].setUnit(Unit.REAL);
            gslcTargetI[p].setNoDataValueUsed(true);
            gslcTargetI[p].setNoDataValue(0);

            final String qBandName = "q_" + productTag + tag;
            gslcTargetQ[p] = targetProduct.addBand(qBandName, ProductData.TYPE_FLOAT32);
            gslcTargetQ[p].setUnit(Unit.IMAGINARY);
            gslcTargetQ[p].setNoDataValueUsed(true);
            gslcTargetQ[p].setNoDataValue(0);

            if (CREATE_VIRTUAL_BAND) {
                ReaderUtils.createVirtualIntensityBand(targetProduct, gslcTargetI[p], gslcTargetQ[p], '_' + productTag + tag);
                Band phaseBand = createGuardedPhaseBand(targetProduct, gslcTargetI[p], gslcTargetQ[p], '_' + productTag + tag);
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

        // A geocoded GSLC keeps the full sensor-to-target carrier, so ref*conj(sec) still contains
        // the flat-earth + topographic phase. Remove it here (recomputed in map geometry) so the GSLC
        // interferogram matches the traditional flat-earth/topo-removed result instead of showing the
        // raw geometric fringes.
        gslcRemoveRefPhase = subtractFlatEarthPhase || subtractTopographicPhase;
        if (gslcRemoveRefPhase) {
            setupGSLCReferencePhase();
        }
    }

    /**
     * Create the virtual "Phase" band with no-data handled BEFORE the {@code atan2}
     * computation, not after. Replaces {@link ReaderUtils#createVirtualPhaseBand}, whose
     * naive expression {@code atan2(q, i)} relies on the fact that {@code atan2(0, 0) = 0}
     * to encode no-data — but floating-point + signed-zero quirks mean some no-data
     * pixels end up at {@code ±π} instead of {@code 0}, so a downstream {@code phase == 0}
     * no-data check both over- and under-masks. By guarding the input we guarantee the
     * no-data sentinel is exactly the no-data value and nothing else.
     * <p>
     * The output bands of {@link InterferogramOp} write {@code 0} into both i_ifg and
     * q_ifg at no-data pixels (because they're zeroed via no-data propagation upstream),
     * so we detect no-data as {@code i_ifg == 0 && q_ifg == 0}.
     */
    private static Band createGuardedPhaseBand(final Product product, final Band iBand, final Band qBand,
                                               final String countStr) {
        // Expression: if both i and q are exactly zero (= no-data), output 0 directly;
        // otherwise compute atan2(q, i). This way the no-data sentinel (= 0) is only
        // written for actual no-data, not for valid pixels where the data happens to
        // land on a ±π branch due to signed-zero quirks of atan2.
        final String expr = "(" + iBand.getName() + " == 0 && " + qBand.getName() + " == 0) ? 0 : " +
                "atan2(" + qBand.getName() + ", " + iBand.getName() + ")";
        final org.esa.snap.core.datamodel.VirtualBand virt = new org.esa.snap.core.datamodel.VirtualBand(
                "Phase" + countStr,
                ProductData.TYPE_FLOAT32,
                iBand.getRasterWidth(),
                iBand.getRasterHeight(),
                expr);
        virt.setUnit(Unit.PHASE);
        virt.setDescription("Phase from complex data");
        virt.setNoDataValueUsed(true);
        virt.setNoDataValue(0);
        virt.setOwner(product);
        product.addBand(virt);
        return virt;
    }

    /**
     * Compute interferogram for GSLC (geocoded complex) products: reference * conj(secondary),
     * then subtract the flat-earth (+ topographic) phase recomputed in map geometry.
     */
    /**
     * Reference-acquisition burst intervals (azimuth seconds of day) from the S1 annotation carried
     * in {@code Original_Product_Metadata}. Returns {@code {start[], end[]}} or {@code null} when
     * unavailable. A plain metadata walk, deliberately free of any sar-op-sentinel1 dependency
     * (circular), and working on stacks produced before per-burst support existed.
     */
    private static double[][] extractGslcBurstTableSod(final Product product) {
        try {
            final MetadataElement opm =
                    product.getMetadataRoot().getElement(AbstractMetadata.ORIGINAL_PRODUCT_METADATA);
            if (opm == null) return null;
            final MetadataElement annotation = opm.getElement("annotation");
            if (annotation == null) return null;
            for (final MetadataElement annFile : annotation.getElements()) {
                final MetadataElement prod = annFile.getElement("product");
                if (prod == null) continue;
                final MetadataElement swathTiming = prod.getElement("swathTiming");
                if (swathTiming == null) continue;
                final MetadataElement burstList = swathTiming.getElement("burstList");
                if (burstList == null) continue;
                final MetadataElement imgAnn = prod.getElement("imageAnnotation");
                final MetadataElement imgInfo = imgAnn != null ? imgAnn.getElement("imageInformation") : null;
                if (imgInfo == null) continue;
                final double azInterval = Double.parseDouble(imgInfo.getAttributeString("azimuthTimeInterval"));
                final int linesPerBurst = Integer.parseInt(swathTiming.getAttributeString("linesPerBurst"));
                final java.util.List<Double> starts = new java.util.ArrayList<>();
                for (final MetadataElement burst : burstList.getElements()) {
                    if (!burst.getName().startsWith("burst")) continue;
                    final String azTime = burst.getAttributeString("azimuthTime", null);
                    if (azTime == null) continue;
                    starts.add(ProductData.UTC.parse(azTime, "yyyy-MM-dd'T'HH:mm:ss").getMJD());
                }
                if (starts.size() < 2 || azInterval <= 0 || linesPerBurst <= 0) continue;
                java.util.Collections.sort(starts);
                // One day anchor for the whole table keeps the axis monotone across midnight and
                // matches the seconds-of-day axis of Orbit.xyz2t / SLCImage.tAzi1.
                final double dayAnchorMjd = Math.floor(starts.get(0));
                final double[] start = new double[starts.size()];
                final double[] end = new double[starts.size()];
                for (int k = 0; k < starts.size(); k++) {
                    start[k] = (starts.get(k) - dayAnchorMjd) * 86400.0;
                    end[k] = start[k] + (linesPerBurst - 1) * azInterval;
                }
                return new double[][]{start, end};
            }
        } catch (Throwable t) {
            SystemUtils.LOG.fine("GSLC residual ramp: burst-table walk failed: " + t.getMessage());
        }
        return null;
    }

    /**
     * Pair up the GSLC deramp-model bands per interferometric pair, when the stack carries them.
     * The reference band carries the ref/mst tag; each secondary's carries the same secN/slvN tag
     * as its i/q bands. One leg without the band is a configuration smell (mixed GSLC settings) —
     * warn and fall back to data-driven-only correction rather than subtract half a model.
     */
    private void discoverGslcCarrierModelBands() {
        gslcRefCarrierBand = new Band[gslcSecondaryI.length];
        gslcSecCarrierBand = new Band[gslcSecondaryI.length];
        Band refCarrier = null;
        final java.util.List<Band> secCarriers = new java.util.ArrayList<>();
        for (final Band b : sourceProduct.getBands()) {
            if (!b.getName().startsWith(GSLC_CARRIER_MODEL_BAND)) continue;
            final String name = b.getName().toLowerCase();
            if (name.contains("_" + REFERENCE_TAG) || name.contains("_" + LEGACY_REFERENCE_TAG)) {
                refCarrier = b;
            } else {
                secCarriers.add(b);
            }
        }
        for (int p = 0; p < gslcSecondaryI.length; p++) {
            final String secTag = extractSecondaryTag(gslcSecondaryI[p].getName());
            final String secDate = dateSuffixOf(gslcSecondaryI[p].getName());
            Band secCarrier = null;
            for (final Band b : secCarriers) {
                // Prefer the secN tag, but accept a date match: CreateStack's tag counter runs per
                // band slot, so the carrier band of the same secondary can carry a different secN
                // than its i/q (observed: i_.._sec1_24Jun2026 with azimuthCarrierPhase_sec2_24Jun2026).
                final String tag = extractSecondaryTag(b.getName());
                if ((tag != null && tag.equals(secTag))
                        || (secDate != null && secDate.equals(dateSuffixOf(b.getName())))) {
                    secCarrier = b;
                    break;
                }
            }
            if (secCarrier == null && secCarriers.size() == 1 && gslcSecondaryI.length == 1) {
                secCarrier = secCarriers.get(0);   // single-pair stack: no ambiguity
            }
            if (refCarrier != null && secCarrier != null) {
                gslcRefCarrierBand[p] = refCarrier;
                gslcSecCarrierBand[p] = secCarrier;
                SystemUtils.LOG.info("GSLC carrier-difference: exact deramp-model subtraction "
                        + "active for pair " + p + " ('" + refCarrier.getName() + "' vs '"
                        + secCarrier.getName() + "').");
            } else if (refCarrier != null || secCarrier != null) {
                SystemUtils.LOG.warning("GSLC carrier-difference: only ONE leg of pair " + p
                        + " carries the '" + GSLC_CARRIER_MODEL_BAND + "' band — regenerate both "
                        + "GSLCs with outputPhaseTerms=true to enable exact model subtraction. "
                        + "Falling back to data-driven ramp removal only.");
            }
        }
    }

    /**
     * Add the leg difference of the GSLC deramp-model bands into the reference-phase surface.
     * Carrier-free legs carry {@code truth × exp(-j·m)}, so the conjugate product carries
     * {@code -(m_ref - m_sec)}; adding {@code (m_sec - m_ref)} to the subtracted surface restores
     * the classical interferometric phase. (Sign pinned empirically: with it, the fitted residual
     * rates collapse; flipped, they double.)
     */
    private void addGslcCarrierModelDiff(final double[][] refPhase, final Rectangle rect, final int p) {
        final Tile refT = getSourceTile(gslcRefCarrierBand[p], rect);
        final Tile secT = getSourceTile(gslcSecCarrierBand[p], rect);
        for (int y = 0; y < rect.height; y++) {
            final int yy = rect.y + y;
            final double[] row = refPhase[y];
            for (int x = 0; x < rect.width; x++) {
                final int xx = rect.x + x;
                row[x] += secT.getSampleDouble(xx, yy) - refT.getSampleDouble(xx, yy);
            }
        }
    }

    private boolean gslcCarrierDiffAvailable(final int p) {
        return gslcRefCarrierBand != null && p < gslcRefCarrierBand.length
                && gslcRefCarrierBand[p] != null && gslcSecCarrierBand[p] != null;
    }

    /** The trailing {@code _ddMmmyyyy} date token of a stacked band name, or null. */
    private static String dateSuffixOf(final String bandName) {
        final java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("_(\\d{2}[A-Za-z]{3}\\d{4})$").matcher(bandName);
        return m.find() ? m.group(1) : null;
    }

    /** Reference-orbit azimuth time (seconds of day) of a map pixel, via geocoding + DEM height. */
    private double refAzTimeSodAt(final double px, final double py) {
        final GeoPos geo = new GeoPos();
        gslcGeoCoding.getGeoPos(new PixelPos(px + 0.5, py + 0.5), geo);
        double height = 0.0;
        if (subtractTopographicPhase && dem != null) {
            try {
                final double e = dem.getElevation(geo);
                if (!Double.isNaN(e) && e != demNoDataValue) height = e;
            } catch (Exception ignore) {
                height = 0.0;
            }
        }
        final Point xyz = Ellipsoid.ell2xyz(FastMath.toRadians(geo.lat), FastMath.toRadians(geo.lon), height);
        return gslcRefOrbit.xyz2t(xyz, gslcRefSLC).y;
    }

    /** One estimation block: fringe gradient plus the multilooked field it was measured on, kept so
     *  the per-burst constants can be estimated afterwards without re-reading any tile. The local
     *  azimuth-time frame (eta at the centre plus its map gradients) is filled during burst
     *  labelling; the gradients carry the iso-eta TILT that maps azimuth rate into both fx and fy. */
    private static final class GslcRampBlock {
        final double xc, yc, fx, fy, weight;
        final double[] mlRe, mlIm;
        final int mw, mh, x0, y0;
        int burst = -1;
        double tSod = Double.NaN;      // reference azimuth time at (xc, yc), seconds of day
        double dEtaDx = Double.NaN;    // d(eta)/dx, s/px
        double dEtaDy = Double.NaN;    // d(eta)/dy, s/px

        GslcRampBlock(final double xc, final double yc, final double fx, final double fy,
                      final double weight, final double[] mlRe, final double[] mlIm,
                      final int mw, final int mh, final int x0, final int y0) {
            this.xc = xc; this.yc = yc; this.fx = fx; this.fy = fy; this.weight = weight;
            this.mlRe = mlRe; this.mlIm = mlIm; this.mw = mw; this.mh = mh; this.x0 = x0; this.y0 = y0;
        }

        /** Azimuth time of an arbitrary pixel, from the block's local linear eta frame. */
        double etaAt(final double x, final double y) {
            return tSod + dEtaDx * (x - xc) + dEtaDy * (y - yc);
        }
    }

    /**
     * Weighted segmented fit in AZIMUTH TIME, two decoupled stages. Stage 1, per burst: every
     * sample's {@code fy/dEtaDy} is a direct measurement of the within-burst azimuth phase rate
     * (rad/s); fit rate(eta) linear per burst (i.e. phase quadratic in eta), with a per-burst
     * outlier-trim pass — trimming against a global fit would discard exactly the
     * strongly-deviating bursts this model exists for. Stage 2, shared: subtract each sample's
     * azimuth-rate leakage {@code rate*dEtaDx} from {@code fx} (the iso-eta tilt maps azimuth rate
     * into fx, per burst); what remains is the genuine range-direction gradient, fitted as
     * {@code aN/N + 2*c2N*x/N^2} with its own trim pass. Bursts with too few samples inherit
     * neighbours by linear interpolation over burst index. {@code dk} is left at zero — constants
     * are unobservable from gradients and are filled from the phase by the caller.
     * Package-visible and pure, for unit tests.
     *
     * @param samples rows of {@code {x, etaSod, fx, fy, weight, burstIndex, dEtaDx, dEtaDy}}
     */
    static GslcPerBurstRamp fitGslcPerBurstRamp(final java.util.List<double[]> samples,
                                                final double[] startSod, final double[] endSod) {
        final int nB = startSod.length;
        final double N = GSLC_RAMP_NORM;

        // stage 1: per-burst rate(eta) = bk + 2*qk*(eta - etaK)
        final double[] etaK = new double[nB], bk = new double[nB], qk = new double[nB];
        final boolean[] fitted = new boolean[nB];
        for (int k = 0; k < nB; k++) {
            etaK[k] = 0.5 * (startSod[k] + endSod[k]);
            java.util.List<double[]> in = new java.util.ArrayList<>();
            for (final double[] s : samples) {
                if ((int) s[5] == k && Math.abs(s[7]) > 1e-12) in.add(s);
            }
            for (int pass = 0; pass < 2 && in.size() >= GSLC_RAMP_MIN_BURST_BLOCKS; pass++) {
                double t11 = 0, t12 = 0, t22 = 0, u1 = 0, u2 = 0;
                for (final double[] s : in) {
                    final double wgt = Math.sqrt(s[4]);
                    final double rate = s[3] / s[7];
                    final double j1 = 2.0 * (s[1] - etaK[k]);
                    t11 += wgt; t12 += wgt * j1; t22 += wgt * j1 * j1;
                    u1 += wgt * rate; u2 += wgt * j1 * rate;
                }
                final double d2 = t11 * t22 - t12 * t12;
                if (Math.abs(d2) > 1e-30) {
                    bk[k] = (u1 * t22 - u2 * t12) / d2;
                    qk[k] = (t11 * u2 - t12 * u1) / d2;
                } else {
                    bk[k] = (t11 > 1e-30) ? u1 / t11 : 0.0;
                    qk[k] = 0.0;
                }
                fitted[k] = true;
                if (pass == 0) {   // per-burst trim, then refit once
                    final double[] resid = new double[in.size()];
                    for (int i = 0; i < in.size(); i++) {
                        final double[] s = in.get(i);
                        resid[i] = Math.abs(s[3] / s[7] - (bk[k] + 2.0 * qk[k] * (s[1] - etaK[k])));
                    }
                    final double[] sorted = resid.clone();
                    java.util.Arrays.sort(sorted);
                    final double thr = 3.0 * Math.max(sorted[sorted.length / 2], 1e-3);
                    final java.util.List<double[]> kept = new java.util.ArrayList<>();
                    for (int i = 0; i < in.size(); i++) {
                        if (resid[i] <= thr) kept.add(in.get(i));
                    }
                    if (kept.size() < GSLC_RAMP_MIN_BURST_BLOCKS || kept.size() == in.size()) break;
                    in = kept;
                }
            }
        }
        // bursts without a fit inherit by linear interpolation over burst index; their eta centre
        // stays the burst-table midpoint, which is always defined.
        for (int k = 0; k < nB; k++) {
            if (fitted[k]) continue;
            int lo = -1, hi = -1;
            for (int i = k - 1; i >= 0; i--) if (fitted[i]) { lo = i; break; }
            for (int i = k + 1; i < nB; i++) if (fitted[i]) { hi = i; break; }
            if (lo < 0 && hi < 0) { bk[k] = 0; qk[k] = 0; continue; }
            if (lo < 0) { bk[k] = bk[hi]; qk[k] = qk[hi]; continue; }
            if (hi < 0) { bk[k] = bk[lo]; qk[k] = qk[lo]; continue; }
            final double t = (k - lo) / (double) (hi - lo);
            bk[k] = bk[lo] + t * (bk[hi] - bk[lo]);
            qk[k] = qk[lo] + t * (qk[hi] - qk[lo]);
        }

        // stage 2: shared range terms from the tilt-corrected fx residuals
        java.util.List<double[]> fxIn = new java.util.ArrayList<>();
        for (final double[] s : samples) {
            final int k = (int) s[5];
            if (k >= 0 && k < nB) fxIn.add(s);
        }
        double aN = 0.0, c2N = 0.0;
        for (int pass = 0; pass < 2 && fxIn.size() >= 3; pass++) {
            double s11 = 0, s12 = 0, s22 = 0, r1 = 0, r2 = 0;
            for (final double[] s : fxIn) {
                final int k = (int) s[5];
                final double rate = bk[k] + 2.0 * qk[k] * (s[1] - etaK[k]);
                final double fxResid = s[2] - rate * s[6];
                final double wgt = Math.sqrt(s[4]);
                final double j0 = 1.0 / N, j1 = 2.0 * s[0] / (N * N);
                s11 += wgt * j0 * j0; s12 += wgt * j0 * j1; s22 += wgt * j1 * j1;
                r1 += wgt * j0 * fxResid; r2 += wgt * j1 * fxResid;
            }
            final double det = s11 * s22 - s12 * s12;
            if (Math.abs(det) > 1e-30) {
                aN = (r1 * s22 - r2 * s12) / det;
                c2N = (s11 * r2 - s12 * r1) / det;
            } else {
                aN = (s11 > 1e-30) ? r1 / s11 : 0.0;
                c2N = 0.0;
            }
            if (pass == 0) {
                final double[] resid = new double[fxIn.size()];
                for (int i = 0; i < fxIn.size(); i++) {
                    final double[] s = fxIn.get(i);
                    final int k = (int) s[5];
                    final double rate = bk[k] + 2.0 * qk[k] * (s[1] - etaK[k]);
                    resid[i] = Math.abs(s[2] - rate * s[6] - (aN / N + 2.0 * c2N * s[0] / (N * N)));
                }
                final double[] sorted = resid.clone();
                java.util.Arrays.sort(sorted);
                final double thr = 3.0 * Math.max(sorted[sorted.length / 2], 1e-4);
                final java.util.List<double[]> kept = new java.util.ArrayList<>();
                for (int i = 0; i < fxIn.size(); i++) {
                    if (resid[i] <= thr) kept.add(fxIn.get(i));
                }
                if (kept.size() < 3 || kept.size() == fxIn.size()) break;
                fxIn = kept;
            }
        }
        return new GslcPerBurstRamp(aN, c2N, etaK, bk, qk, new double[nB], startSod, endSod);
    }

    /**
     * Estimate the per-pair residual phase ramp of the (reference-phase-removed) GSLC
     * interferogram as a quadratic phase polynomial, from block-wise fringe gradients.
     * <p>
     * Per block: form the conjugate product, remove the flat-earth/topo reference phase (when
     * enabled), multilook, and take the lag-1 phase gradient — a robust dominant-fringe estimator
     * on speckle. The (fx, fy) samples are then fitted (weighted, with one outlier-trim pass) to
     * the gradient of {@code phi = c0*x + c1*y + c2*x^2 + c3*xy + c4*y^2} — curl-free by
     * construction. Five global parameters cannot absorb localized deformation; a genuine
     * scene-wide linear gradient would be absorbed, which is why the option is off by default.
     */
    private void estimateGslcResidualRampOnce() {
        if (gslcRampEstimated) return;
        synchronized (gslcRampLock) {
            if (gslcRampEstimated) return;
            gslcRampCoef = new double[gslcReferenceI.length][];
            gslcRampPerBurst = new GslcPerBurstRamp[gslcReferenceI.length];
            final int w = sourceProduct.getSceneRasterWidth();
            final int h = sourceProduct.getSceneRasterHeight();
            final int n = GSLC_RAMP_BLOCK;
            final boolean perBurst = gslcBurstStartSod != null && gslcBurstStartSod.length >= 2
                    && gslcRefOrbit != null && gslcRefSLC != null && gslcGeoCoding != null;
            final int nB = perBurst ? gslcBurstStartSod.length : 0;
            final int stepX = Math.max(n + 64, Math.min(w, h) / 10);
            // Per-burst fitting needs several block rows PER BURST; the global grid gives ~10 rows
            // for the whole scene (~1 per burst). Overlapping blocks are statistically fine here —
            // each contributes an independent local gradient estimate to a weighted LS.
            final int stepY = perBurst ? Math.max(n / 2, h / (nB * 5)) : stepX;
            for (int p = 0; p < gslcReferenceI.length; p++) {
                try {
                    final java.util.List<GslcRampBlock> blocks = new java.util.ArrayList<>();
                    for (int y0 = 64; y0 + n < h - 64; y0 += stepY) {
                        for (int x0 = 64; x0 + n < w - 64; x0 += stepX) {
                            final GslcRampBlock b = gslcRampBlockGradient(p, new Rectangle(x0, y0, n, n));
                            if (b != null) blocks.add(b);
                        }
                    }
                    if (blocks.size() < 10) {
                        SystemUtils.LOG.warning("GSLC residual ramp: only " + blocks.size() +
                                " usable blocks for pair " + p + " — ramp removal skipped.");
                        continue;
                    }
                    final java.util.List<double[]> samples = new java.util.ArrayList<>(blocks.size());
                    for (final GslcRampBlock b : blocks) {
                        if (perBurst) {
                            try {
                                // local azimuth-time frame: value + map gradients (the gradients
                                // carry the iso-eta tilt; D well below the block size)
                                final double D = 96.0;
                                b.tSod = refAzTimeSodAt(b.xc, b.yc);
                                b.dEtaDx = (refAzTimeSodAt(b.xc + D, b.yc) - b.tSod) / D;
                                b.dEtaDy = (refAzTimeSodAt(b.xc, b.yc + D) - b.tSod) / D;
                                b.burst = gslcBurstIndexOfSod(b.tSod);
                            } catch (Throwable t) {
                                b.burst = -1;
                            }
                        }
                        samples.add(new double[]{b.xc, b.yc, b.fx, b.fy, b.weight, b.burst});
                    }
                    double[] c = fitGslcRamp(samples);
                    // one trim pass: drop gradient outliers > 3x the median residual
                    final double[] resid = new double[samples.size()];
                    for (int i = 0; i < samples.size(); i++) {
                        resid[i] = gslcRampGradResidual(samples.get(i), c);
                    }
                    final double[] sorted = resid.clone();
                    java.util.Arrays.sort(sorted);
                    final double thr = 3.0 * Math.max(sorted[sorted.length / 2], 1e-4);
                    final java.util.List<double[]> kept = new java.util.ArrayList<>();
                    for (int i = 0; i < samples.size(); i++) {
                        if (resid[i] <= thr) kept.add(samples.get(i));
                    }
                    if (kept.size() >= 10) {
                        c = fitGslcRamp(kept);
                    }
                    gslcRampCoef[p] = c;
                    SystemUtils.LOG.info(String.format(
                            "GSLC residual ramp (pair %d, %d/%d blocks): centre gradient " +
                                    "(%.4f, %.4f) rad/px; coef [%.5g %.5g %.5g %.5g %.5g] (x,y in px/%.0f)",
                            p, kept.size(), samples.size(),
                            gslcRampFx(c, w / 2.0, h / 2.0), gslcRampFy(c, w / 2.0, h / 2.0),
                            c[0], c[1], c[2], c[3], c[4], GSLC_RAMP_NORM));

                    if (perBurst) {
                        final java.util.List<double[]> pbSamples = new java.util.ArrayList<>(blocks.size());
                        for (final GslcRampBlock b : blocks) {
                            if (b.burst >= 0 && !Double.isNaN(b.dEtaDy) && Math.abs(b.dEtaDy) > 1e-12) {
                                pbSamples.add(new double[]{b.xc, b.tSod, b.fx, b.fy, b.weight,
                                        b.burst, b.dEtaDx, b.dEtaDy});
                            }
                        }
                        if (pbSamples.size() >= 10) {
                            gslcRampPerBurst[p] = fitGslcPerBurstConstants(
                                    fitGslcPerBurstRamp(pbSamples, gslcBurstStartSod, gslcBurstEndSod),
                                    blocks, p);
                        } else {
                            SystemUtils.LOG.warning("GSLC residual ramp: only " + pbSamples.size()
                                    + " burst-labelled blocks — falling back to the global fit.");
                        }
                    }
                } catch (Throwable t) {
                    SystemUtils.LOG.warning("GSLC residual ramp estimation failed for pair " + p +
                            ": " + t.getMessage() + " — ramp removal skipped.");
                }
            }
            gslcRampEstimated = true;
        }
    }

    /** Burst index for a reference azimuth time; overlap resolved at the midpoint. */
    private int gslcBurstIndexOfSod(final double tSod) {
        for (int k = 0; k < gslcBurstStartSod.length - 1; k++) {
            if (tSod < 0.5 * (gslcBurstStartSod[k + 1] + gslcBurstEndSod[k])) return k;
        }
        return gslcBurstStartSod.length - 1;
    }

    /**
     * Fill the per-burst constants {@code dk} from the phase itself: rotate every retained
     * multilooked cell by the slope-only model and take the circular mean per burst. Referenced to
     * the strongest burst so the interferogram keeps its overall constant. Bursts with no coherent
     * cells copy the nearest estimated neighbour (index distance; angles are not interpolated
     * across the wrap).
     */
    private GslcPerBurstRamp fitGslcPerBurstConstants(final GslcPerBurstRamp slopes,
                                                      final java.util.List<GslcRampBlock> blocks,
                                                      final int pairIndex) {
        final int nB = slopes.burstStartSod.length;
        final double[] sr = new double[nB], si = new double[nB];
        final int[] nCells = new int[nB];
        final int ml = GSLC_RAMP_ML;
        for (final GslcRampBlock b : blocks) {
            if (b.burst < 0 || Double.isNaN(b.tSod)) continue;
            for (int my = 0; my < b.mh; my++) {
                final double yy = b.y0 + (my + 0.5) * ml;
                for (int mx = 0; mx < b.mw; mx++) {
                    final double ar = b.mlRe[my * b.mw + mx], ai = b.mlIm[my * b.mw + mx];
                    if (ar == 0 && ai == 0) continue;
                    final double xx = b.x0 + (mx + 0.5) * ml;
                    // eta from the block's local linear frame; the cell's own burst, so cells on
                    // the far side of a seam inside a block accumulate into the right constant
                    final double eta = b.etaAt(xx, yy);
                    final int kCell = slopes.burstOfSod(eta);
                    final double model = slopes.phaseAt(xx, eta, kCell);
                    final double cs = FastMath.cos(model), sn = FastMath.sin(model);
                    sr[kCell] += ar * cs + ai * sn;
                    si[kCell] += ai * cs - ar * sn;
                    nCells[kCell]++;
                }
            }
        }
        final double[] dk = new double[nB];
        final boolean[] have = new boolean[nB];
        int kStrong = -1;
        double best = 0;
        for (int k = 0; k < nB; k++) {
            final double mag = Math.hypot(sr[k], si[k]);
            if (nCells[k] > 0 && mag > 0) {
                dk[k] = Math.atan2(si[k], sr[k]);
                have[k] = true;
                if (mag > best) { best = mag; kStrong = k; }
            }
        }
        if (kStrong >= 0) {
            final double ref = dk[kStrong];
            for (int k = 0; k < nB; k++) {
                if (have[k]) dk[k] = Math.atan2(Math.sin(dk[k] - ref), Math.cos(dk[k] - ref));
            }
        }
        for (int k = 0; k < nB; k++) {
            if (have[k]) continue;
            int nearest = -1, bestDist = Integer.MAX_VALUE;
            for (int i = 0; i < nB; i++) {
                if (have[i] && Math.abs(i - k) < bestDist) { bestDist = Math.abs(i - k); nearest = i; }
            }
            dk[k] = nearest >= 0 ? dk[nearest] : 0.0;
        }
        final StringBuilder sb = new StringBuilder();
        sb.append(String.format("GSLC residual ramp per-burst (pair %d, %d bursts, shared d/dx %.4f rad/px):",
                pairIndex, nB, slopes.aN / GSLC_RAMP_NORM));
        for (int k = 0; k < nB; k++) {
            sb.append(String.format(" [b%d n=%d rate=%.3f rad/s q=%.3g d=%.3f]",
                    k, nCells[k], slopes.bk[k], slopes.qk[k], dk[k]));
        }
        SystemUtils.LOG.info(sb.toString());
        return new GslcPerBurstRamp(slopes.aN, slopes.c2N, slopes.etaK, slopes.bk, slopes.qk, dk,
                slopes.burstStartSod, slopes.burstEndSod);
    }

    /** Dominant fringe gradient (rad/px) of one block, or null if the block is unusable. */
    private GslcRampBlock gslcRampBlockGradient(final int p, final Rectangle rect) throws Exception {
        final Tile ti = getSourceTile(gslcReferenceI[p], rect);
        final Tile tq = getSourceTile(gslcReferenceQ[p], rect);
        final Tile si = getSourceTile(gslcSecondaryI[p], rect);
        final Tile sq = getSourceTile(gslcSecondaryQ[p], rect);
        double[][] refPhase = gslcRemoveRefPhase
                ? computeGslcReferencePhase(rect,
                        gslcSecSLCMap.get(gslcSecondaryI[p]), gslcSecOrbitMap.get(gslcSecondaryI[p]),
                        null, true)
                : null;
        // The estimator must see the same surface the interferogram will subtract: with the exact
        // model difference included, the fit measures only the annotation-error remainder.
        if (gslcCarrierDiffAvailable(p)) {
            if (refPhase == null) {
                refPhase = new double[rect.height][rect.width];
            }
            addGslcCarrierModelDiff(refPhase, rect, p);
        }

        final int ml = GSLC_RAMP_ML;
        final int mw = rect.width / ml, mh = rect.height / ml;
        final double[] mlRe = new double[mh * mw];
        final double[] mlIm = new double[mh * mw];
        int invalid = 0;
        for (int y = 0; y < rect.height; y++) {
            for (int x = 0; x < rect.width; x++) {
                final double mI = ti.getSampleDouble(rect.x + x, rect.y + y);
                final double mQ = tq.getSampleDouble(rect.x + x, rect.y + y);
                final double sI = si.getSampleDouble(rect.x + x, rect.y + y);
                final double sQ = sq.getSampleDouble(rect.x + x, rect.y + y);
                if ((mI == 0 && mQ == 0) || (sI == 0 && sQ == 0)) {
                    invalid++;
                    continue;
                }
                double re = mI * sI + mQ * sQ;
                double im = mQ * sI - mI * sQ;
                if (refPhase != null) {
                    final double ang = refPhase[y][x];
                    final double cs = FastMath.cos(ang), sn = FastMath.sin(ang);
                    final double r2 = re * cs + im * sn;
                    im = -re * sn + im * cs;
                    re = r2;
                }
                final double mag = Math.hypot(re, im);
                if (mag <= 0) continue;
                final int my = y / ml, mx = x / ml;
                if (my >= mh || mx >= mw) continue;
                mlRe[my * mw + mx] += re / mag;
                mlIm[my * mw + mx] += im / mag;
            }
        }
        if (invalid > rect.width * rect.height / 5) return null;

        // lag-1 phase gradient of the multilooked field, in x and y
        double gxr = 0, gxi = 0, gyr = 0, gyi = 0, pw = 0;
        for (int my = 0; my < mh; my++) {
            for (int mx = 0; mx < mw; mx++) {
                final double ar = mlRe[my * mw + mx], ai = mlIm[my * mw + mx];
                pw += ar * ar + ai * ai;
                if (mx + 1 < mw) {
                    final double br = mlRe[my * mw + mx + 1], bi = mlIm[my * mw + mx + 1];
                    gxr += br * ar + bi * ai;
                    gxi += bi * ar - br * ai;
                }
                if (my + 1 < mh) {
                    final double br = mlRe[(my + 1) * mw + mx], bi = mlIm[(my + 1) * mw + mx];
                    gyr += br * ar + bi * ai;
                    gyi += bi * ar - br * ai;
                }
            }
        }
        final double weight = Math.sqrt(gxr * gxr + gxi * gxi) / Math.max(pw, 1e-30);
        if (weight < 0.05) return null;   // no dominant fringe in this block
        final double fx = Math.atan2(gxi, gxr) / ml;
        final double fy = Math.atan2(gyi, gyr) / ml;
        return new GslcRampBlock(rect.x + rect.width / 2.0, rect.y + rect.height / 2.0, fx, fy, weight,
                mlRe, mlIm, mw, mh, rect.x, rect.y);
    }

    /** Weighted LS fit of the 5 ramp coefficients from (x, y, fx, fy, w) gradient samples. */
    private static double[] fitGslcRamp(final java.util.List<double[]> samples) {
        // gradient model: fx = c0/N + 2*c2*x/N^2 + c3*y/N^2 ; fy = c1/N + c3*x/N^2 + 2*c4*y/N^2
        final double N = GSLC_RAMP_NORM;
        final double[][] ata = new double[5][5];
        final double[] atb = new double[5];
        for (final double[] s : samples) {
            final double x = s[0] / N, y = s[1] / N, wgt = Math.sqrt(s[4]);
            final double[][] rows = {
                    {1.0 / N, 0, 2 * x / N, y / N, 0},
                    {0, 1.0 / N, 0, x / N, 2 * y / N}};
            final double[] vals = {s[2], s[3]};
            for (int r = 0; r < 2; r++) {
                for (int i = 0; i < 5; i++) {
                    for (int j = 0; j < 5; j++) {
                        ata[i][j] += wgt * rows[r][i] * rows[r][j];
                    }
                    atb[i] += wgt * rows[r][i] * vals[r];
                }
            }
        }
        // solve 5x5 via Gaussian elimination with partial pivoting
        final double[][] m = new double[5][6];
        for (int i = 0; i < 5; i++) {
            System.arraycopy(ata[i], 0, m[i], 0, 5);
            m[i][5] = atb[i];
        }
        for (int col = 0; col < 5; col++) {
            int piv = col;
            for (int rr = col + 1; rr < 5; rr++) {
                if (Math.abs(m[rr][col]) > Math.abs(m[piv][col])) piv = rr;
            }
            final double[] tmp = m[col]; m[col] = m[piv]; m[piv] = tmp;
            final double d = m[col][col];
            if (Math.abs(d) < 1e-20) return new double[5];
            for (int j = col; j < 6; j++) m[col][j] /= d;
            for (int rr = 0; rr < 5; rr++) {
                if (rr == col) continue;
                final double f = m[rr][col];
                for (int j = col; j < 6; j++) m[rr][j] -= f * m[col][j];
            }
        }
        return new double[]{m[0][5], m[1][5], m[2][5], m[3][5], m[4][5]};
    }

    private static double gslcRampFx(final double[] c, final double x, final double y) {
        final double N = GSLC_RAMP_NORM;
        return c[0] / N + 2 * c[2] * x / (N * N) + c[3] * y / (N * N);
    }

    private static double gslcRampFy(final double[] c, final double x, final double y) {
        final double N = GSLC_RAMP_NORM;
        return c[1] / N + c[3] * x / (N * N) + 2 * c[4] * y / (N * N);
    }

    private static double gslcRampGradResidual(final double[] s, final double[] c) {
        return Math.hypot(s[2] - gslcRampFx(c, s[0], s[1]), s[3] - gslcRampFy(c, s[0], s[1]));
    }

    /** Ramp phase value at pixel (x, y). */
    private static double gslcRampPhase(final double[] c, final double x, final double y) {
        final double xn = x / GSLC_RAMP_NORM, yn = y / GSLC_RAMP_NORM;
        return c[0] * xn + c[1] * yn + c[2] * xn * xn + c[3] * xn * yn + c[4] * yn * yn;
    }

    private void computeTileStackForGSLC(final Map<Band, Tile> targetTileMap, final Rectangle targetRectangle) {
        try {
            if (subtractResidualRamp && !gslcRampEstimated) {
                estimateGslcResidualRampOnce();
            }
            final int x0 = targetRectangle.x;
            final int y0 = targetRectangle.y;
            final int w = targetRectangle.width;
            final int h = targetRectangle.height;

            // Extended rectangle for the coherence window, clamped to the image. Without the clamp
            // the border tiles ask for a negative origin (or past the last row/column); whether that
            // throws or is silently border-extended depends on the source image implementation, so
            // clamp here and truncate the window at the edges instead (see computeGSLCCoherence).
            final int cohx0 = Math.max(0, x0 - (cohWinRg - 1) / 2);
            final int cohy0 = Math.max(0, y0 - (cohWinAz - 1) / 2);
            final int cohx1 = Math.min(sourceImageWidth  - 1, x0 + w - 1 + (cohWinRg - 1) / 2);
            final int cohy1 = Math.min(sourceImageHeight - 1, y0 + h - 1 + (cohWinAz - 1) / 2);
            final Rectangle cohRect = new Rectangle(cohx0, cohy0, cohx1 - cohx0 + 1, cohy1 - cohy0 + 1);

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

                // Flat-earth + topographic reference phase (radians, unwrapped) for this pair,
                // computed in map geometry on a subsampled grid and bilinearly interpolated. Null
                // when reference-phase removal is off (or the secondary geometry is unavailable) —
                // then the raw conjugate product is written.
                // Computed over the (larger) coherence rectangle when coherence is enabled, so the
                // very same surface derotates the conjugate product inside the coherence window.
                // Estimating coherence on the un-derotated product lets dense flat-earth/topo
                // fringes cancel within the window and biases the estimate low.
                final boolean cohOn = includeCoherence && gslcTargetCoh != null
                        && targetTileMap.get(gslcTargetCoh[p]) != null;
                final Rectangle refRect = cohOn ? cohRect : targetRectangle;
                final GslcPerBurstRamp rampPB = (subtractResidualRamp && gslcRampPerBurst != null
                        && p < gslcRampPerBurst.length) ? gslcRampPerBurst[p] : null;
                double[][] refPhase = gslcRemoveRefPhase
                        ? computeGslcReferencePhase(refRect,
                                gslcSecSLCMap.get(gslcSecondaryI[p]), gslcSecOrbitMap.get(gslcSecondaryI[p]),
                                rampPB, true)
                        : null;
                if (refPhase == null && rampPB != null) {
                    // Reference-phase removal off but per-burst ramp on: ramp-only surface through
                    // the same node machinery (burst labels need the reference geometry).
                    refPhase = computeGslcReferencePhase(refRect, null, null, rampPB, false);
                }

                // Exact deramp-model difference rides the same surface, so interferogram and
                // coherence stay mutually consistent (as for flat-earth/topo and the ramp).
                if (gslcCarrierDiffAvailable(p)) {
                    if (refPhase == null) {
                        refPhase = new double[refRect.height][refRect.width];
                    }
                    addGslcCarrierModelDiff(refPhase, refRect, p);
                }

                // Residual-ramp removal rides on the same reference-phase surface so the
                // interferogram and the coherence estimator stay mutually consistent. The global
                // quadratic applies only when no per-burst model exists (stripmap GSLC, burst
                // annotation unavailable) — the per-burst model already contains the global part.
                final double[] rampC = (rampPB == null && subtractResidualRamp && gslcRampCoef != null
                        && p < gslcRampCoef.length) ? gslcRampCoef[p] : null;
                if (rampC != null) {
                    if (refPhase == null) {
                        refPhase = new double[refRect.height][refRect.width];
                    }
                    for (int j = 0; j < refRect.height; j++) {
                        final double[] row = refPhase[j];
                        final double yy = refRect.y + j;
                        for (int i = 0; i < refRect.width; i++) {
                            row[i] += gslcRampPhase(rampC, refRect.x + i, yy);
                        }
                    }
                }

                // Interferogram: primary * conj(secondary)
                // (mI + j*mQ) * (sI - j*sQ) = (mI*sI + mQ*sQ) + j*(mQ*sI - mI*sQ)
                // then rotate by exp(-j*refPhase) to strip flat-earth + topographic phase.
                //
                // Hot path: use ProductData buffers and TileIndex stride math instead of
                // per-pixel Tile.getSampleDouble(x, y), which goes through the SampleModel
                // for every sample. ~5-10x speedup on the SLC tiles that dominate this loop.
                final ProductData refDataI = refTileI.getDataBuffer();
                final ProductData refDataQ = refTileQ.getDataBuffer();
                final ProductData secDataI = secTileI.getDataBuffer();
                final ProductData secDataQ = secTileQ.getDataBuffer();
                final TileIndex refIndex = new TileIndex(refTileI);
                final TileIndex secIndex = new TileIndex(secTileI);
                final TileIndex tgtIndex = tgtTileI != null ? new TileIndex(tgtTileI) : new TileIndex(tgtTileQ);
                for (int y = y0; y < y0 + h; y++) {
                    refIndex.calculateStride(y);
                    secIndex.calculateStride(y);
                    tgtIndex.calculateStride(y);
                    final double[] refPhaseRow = refPhase != null ? refPhase[y - refRect.y] : null;
                    for (int x = x0; x < x0 + w; x++) {
                        final int refIdx = refIndex.getIndex(x);
                        final int secIdx = secIndex.getIndex(x);
                        final double mI = refDataI.getElemDoubleAt(refIdx);
                        final double mQ = refDataQ.getElemDoubleAt(refIdx);
                        final double sI = secDataI.getElemDoubleAt(secIdx);
                        final double sQ = secDataQ.getElemDoubleAt(secIdx);

                        double ifgI = mI * sI + mQ * sQ;
                        double ifgQ = mQ * sI - mI * sQ;
                        if (refPhaseRow != null) {
                            final double ang = refPhaseRow[x - refRect.x];
                            final double cs = FastMath.cos(ang);
                            final double sn = FastMath.sin(ang);
                            final double rI = ifgI * cs + ifgQ * sn;
                            final double rQ = -ifgI * sn + ifgQ * cs;
                            ifgI = rI;
                            ifgQ = rQ;
                        }

                        final int tgtIdx = tgtIndex.getIndex(x);
                        if (tgtDataI != null) tgtDataI.setElemDoubleAt(tgtIdx, ifgI);
                        if (tgtDataQ != null) tgtDataQ.setElemDoubleAt(tgtIdx, ifgQ);
                    }
                }

                // Coherence estimation
                if (includeCoherence && gslcTargetCoh != null) {
                    final Tile cohTgtTile = targetTileMap.get(gslcTargetCoh[p]);
                    if (cohTgtTile != null) {
                        computeGSLCCoherence(cohRect, targetRectangle,
                                gslcReferenceI[p], gslcReferenceQ[p], gslcSecondaryI[p], gslcSecondaryQ[p],
                                cohTgtTile, refPhase);
                    }
                }
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    /**
     * Build the reference + per-secondary orbit/SLCImage geometry and the DEM used to remove
     * the flat-earth (+ topographic) phase from geocoded GSLC interferograms in map geometry.
     */
    private void setupGSLCReferencePhase() throws Exception {
        gslcGeoCoding = sourceProduct.getSceneGeoCoding();
        if (gslcGeoCoding == null) {
            throw new OperatorException("GSLC flat-earth/topographic phase removal requires a geocoded " +
                    "product (scene map geocoding is missing).");
        }
        final MetadataElement refAbs = AbstractMetadata.getAbstractedMetadata(sourceProduct);
        gslcRefSLC = new SLCImage(refAbs, sourceProduct);
        gslcRefOrbit = new Orbit(refAbs, orbitDegree);

        if (subtractResidualRamp) {
            final double[][] burstTable = extractGslcBurstTableSod(sourceProduct);
            if (burstTable != null) {
                gslcBurstStartSod = burstTable[0];
                gslcBurstEndSod = burstTable[1];
                SystemUtils.LOG.info("GSLC residual ramp: reference burst table with "
                        + gslcBurstStartSod.length + " bursts — per-burst ramp fitting enabled.");
            } else {
                SystemUtils.LOG.info("GSLC residual ramp: no burst annotation found — "
                        + "using the scene-global quadratic fit.");
            }
        }

        discoverGslcCarrierModelBands();

        final MetadataElement secRoot = AbstractMetadata.getSecondaryMetadata(sourceProduct.getMetadataRoot());
        for (String secProdName : StackUtils.getSecondaryProductNames(sourceProduct)) {
            // getSecondaryProductNames is a bare getElementNames(), so it includes
            // Original_Product_Metadata. Constructing an SLCImage from that element throws
            // "Metadata attribute 'MISSION' not found" and aborts GSLC mode on the DEFAULT
            // parameters. Every sibling operator skips it explicitly — including this file's own
            // classic path — so do the same here.
            if (AbstractMetadata.ORIGINAL_PRODUCT_METADATA.equals(secProdName)) {
                continue;
            }
            final MetadataElement secAbs = (secRoot != null) ? secRoot.getElement(secProdName) : null;
            if (secAbs == null) continue;
            final SLCImage secSLC = new SLCImage(secAbs, sourceProduct);
            final Orbit secOrbit = new Orbit(secAbs, orbitDegree);
            final java.util.List<String> secBandNames =
                    java.util.Arrays.asList(StackUtils.getSecondaryBandNames(sourceProduct, secProdName));
            for (Band secI : gslcSecondaryI) {
                if (secBandNames.contains(secI.getName())) {
                    gslcSecSLCMap.put(secI, secSLC);
                    gslcSecOrbitMap.put(secI, secOrbit);
                }
            }
        }
        // Every paired secondary needs its own SLCImage/Orbit. Before N-pair support only pair 0
        // was ever used, so a secondary with missing Secondary_Metadata went unnoticed; now it
        // would surface as an NPE deep in the tile loop. Fail here with a name instead.
        for (final Band secI : gslcSecondaryI) {
            if (!gslcSecSLCMap.containsKey(secI)) {
                throw new OperatorException("GSLC reference-phase removal: no secondary metadata for band '"
                        + secI.getName() + "'. The stack must carry a Secondary_Metadata element for "
                        + "each secondary product.");
            }
        }

        if (subtractTopographicPhase && dem == null) {
            defineDEM();
        }
    }

    /**
     * Reference (flat-earth + topographic) interferometric phase over a tile, in map geometry.
     * For each node of a subsampled grid the ground point (lat, lon, DEM height) is projected to
     * ECEF and its one-way range time to both the reference and secondary orbits is solved; the
     * geometric phase (4&pi;/&lambda;)(R_ref &minus; R_sec) is then bilinearly interpolated to full
     * resolution. Returns the (unwrapped) angle to subtract, or {@code null} if the secondary
     * geometry is unavailable. With topographic phase off, ellipsoid height (0) is used so only the
     * flat-earth phase is removed.
     */
    private double[][] computeGslcReferencePhase(final Rectangle rect, final SLCImage secSLC,
                                                 final Orbit secOrbit, final GslcPerBurstRamp ramp,
                                                 final boolean includeRefTerm) throws Exception {
        if (includeRefTerm && (secSLC == null || secOrbit == null)) {
            return null;
        }
        final int w = rect.width, h = rect.height, x0 = rect.x, y0 = rect.y;
        final int step = GSLC_REFPHASE_SUBSAMPLE;
        final int nx = (w + step - 1) / step + 1;
        final int ny = (h + step - 1) / step + 1;
        final double[][] node = new double[ny][nx];

        final double phaseFactor = includeRefTerm ? -4.0 * Constants.PI / secSLC.getRadarWavelength() : 0.0;
        final boolean useDem = subtractTopographicPhase && dem != null;
        // Use the field, not dem.getDescriptor(): FileElevationModel (any externalDEMFile) returns a
        // null descriptor, so dereferencing it here NPE'd on every tile. defineDEM() already resolves
        // the correct value for both the auto-download and external cases.
        final double demNoData = useDem ? demNoDataValue : 0.0;
        final GeoPos geo = new GeoPos();
        final PixelPos pix = new PixelPos();

        // Nodes are evaluated at their exact uniform-grid positions, including the last node of
        // each tile which may lie past the tile edge — the bilinear interpolation below assumes
        // uniform node spacing (gx = x/step), so clamping edge nodes to the tile edge would
        // mis-position them and bias the last cell of every tile. CrsGeoCoding extrapolates
        // linearly beyond the raster, so out-of-raster node positions are well-defined.
        for (int j = 0; j < ny; j++) {
            final int yy = y0 + j * step;
            for (int i = 0; i < nx; i++) {
                final int xx = x0 + i * step;
                pix.setLocation(xx + 0.5, yy + 0.5);
                gslcGeoCoding.getGeoPos(pix, geo);

                double height = 0.0;
                if (useDem) {
                    try {
                        final double e = dem.getElevation(geo);
                        if (!Double.isNaN(e) && e != demNoData) height = e;
                    } catch (Exception ignore) {
                        height = 0.0;
                    }
                }
                final Point xyz = Ellipsoid.ell2xyz(FastMath.toRadians(geo.lat), FastMath.toRadians(geo.lon), height);
                final Point tpRef = gslcRefOrbit.xyz2t(xyz, gslcRefSLC);
                double v = 0.0;
                if (includeRefTerm) {
                    final double tSec = secOrbit.xyz2t(xyz, secSLC).x;
                    // refPhaseSec = phaseFactor * (R_sec - R_ref); angle to subtract = refPhaseRef(=0) - refPhaseSec
                    v = -(phaseFactor * Constants.lightSpeed * (tSec - tpRef.x));
                }
                if (ramp != null) {
                    // Per-burst residual ramp, evaluated in azimuth time (.y of the same solve) —
                    // exact under the iso-eta tilt. Folding it in at the nodes keeps burst seams
                    // sharp to within one interpolation cell (~GSLC_REFPHASE_SUBSAMPLE px).
                    v += ramp.phaseAt(xx, tpRef.y, ramp.burstOfSod(tpRef.y));
                }
                node[j][i] = v;
            }
        }

        // Bilinear interpolation of the (continuous, unwrapped) phase surface to full resolution.
        final double[][] out = new double[h][w];
        for (int y = 0; y < h; y++) {
            final double gy = (double) y / step;
            int j0 = (int) gy; if (j0 > ny - 2) j0 = ny - 2; if (j0 < 0) j0 = 0;
            final double ty = gy - j0;
            for (int x = 0; x < w; x++) {
                final double gx = (double) x / step;
                int i0 = (int) gx; if (i0 > nx - 2) i0 = nx - 2; if (i0 < 0) i0 = 0;
                final double tx = gx - i0;
                final double v0 = node[j0][i0] + (node[j0][i0 + 1] - node[j0][i0]) * tx;
                final double v1 = node[j0 + 1][i0] + (node[j0 + 1][i0 + 1] - node[j0 + 1][i0]) * tx;
                out[y][x] = v0 + (v1 - v0) * ty;
            }
        }
        return out;
    }

    /**
     * @param refPhase flat-earth (+ topographic) phase over {@code cohRect}, or {@code null} when
     *                 reference-phase removal is off. The conjugate product is derotated by it
     *                 before being summed; without that, fringes inside the estimation window
     *                 cancel and the coherence comes out biased low.
     */
    private void computeGSLCCoherence(final Rectangle cohRect, final Rectangle targetRect,
                                       final Band refBandI, final Band refBandQ,
                                       final Band secBandI, final Band secBandQ,
                                       final Tile cohTile, final double[][] refPhase) {

        // Precompute the derotation phasor once per tile — cos/sin per sample inside the
        // (cohWinAz × cohWinRg) window would dominate the cost of this function.
        double[][] refCos = null, refSin = null;
        if (refPhase != null) {
            refCos = new double[refPhase.length][];
            refSin = new double[refPhase.length][];
            for (int j = 0; j < refPhase.length; j++) {
                final double[] src = refPhase[j];
                final double[] c = new double[src.length];
                final double[] s = new double[src.length];
                for (int i = 0; i < src.length; i++) {
                    c[i] = FastMath.cos(src[i]);
                    s[i] = FastMath.sin(src[i]);
                }
                refCos[j] = c;
                refSin[j] = s;
            }
        }

        final Tile refI = getSourceTile(refBandI, cohRect);
        final Tile refQ = getSourceTile(refBandQ, cohRect);
        final Tile secI = getSourceTile(secBandI, cohRect);
        final Tile secQ = getSourceTile(secBandQ, cohRect);

        // Hot path: per-pixel Tile.getSampleDouble does sample-model coordinate-to-index
        // resolution + virtual dispatch on EVERY one of the (cohWinAz × cohWinRg × 4-bands)
        // calls inside the inner window. For a typical 5×5 coherence window over a
        // 1024×1024 tile that's ~100M dispatches per tile per band-pair. Switching to
        // ProductData buffers + TileIndex stride math drops a 5-10× factor out of this
        // function, which is the dominant cost of the GSLC interferogram pipeline.
        final ProductData refDataI = refI.getDataBuffer();
        final ProductData refDataQ = refQ.getDataBuffer();
        final ProductData secDataI = secI.getDataBuffer();
        final ProductData secDataQ = secQ.getDataBuffer();
        final TileIndex refIndex = new TileIndex(refI);
        final TileIndex secIndex = new TileIndex(secI);

        // Further drop the per-sample virtual dispatch by reaching for the underlying float[]
        // directly when all four source bands are TYPE_FLOAT32. SLC i/q in microwave-toolbox
        // are universally float32, so this fast path is taken on every real product; the
        // generic ProductData fallback below remains for any future non-float source.
        final boolean allFloat = refDataI instanceof ProductData.Float
                && refDataQ instanceof ProductData.Float
                && secDataI instanceof ProductData.Float
                && secDataQ instanceof ProductData.Float;
        final float[] refArrI = allFloat ? ((ProductData.Float) refDataI).getArray() : null;
        final float[] refArrQ = allFloat ? ((ProductData.Float) refDataQ).getArray() : null;
        final float[] secArrI = allFloat ? ((ProductData.Float) secDataI).getArray() : null;
        final float[] secArrQ = allFloat ? ((ProductData.Float) secDataQ).getArray() : null;

        final ProductData cohData = cohTile.getDataBuffer();
        final TileIndex cohIndex = new TileIndex(cohTile);
        final int halfAz = (cohWinAz - 1) / 2;
        final int halfRg = (cohWinRg - 1) / 2;

        final int x0 = targetRect.x;
        final int y0 = targetRect.y;
        final int w = targetRect.width;
        final int h = targetRect.height;

        // cohRect is clamped to the image, so the window must be clamped to it too: at the scene
        // border the estimate is formed over the truncated window rather than reading outside the
        // fetched tile. Interior pixels are unaffected.
        final int cohXlo = cohRect.x, cohXhi = cohRect.x + cohRect.width - 1;
        final int cohYlo = cohRect.y, cohYhi = cohRect.y + cohRect.height - 1;

        for (int y = y0; y < y0 + h; y++) {
            cohIndex.calculateStride(y);
            final int wyLo = Math.max(y - halfAz, cohYlo);
            final int wyHi = Math.min(y + halfAz, cohYhi);
            for (int x = x0; x < x0 + w; x++) {
                double sumReal = 0, sumImag = 0, sumRef = 0, sumSec = 0;
                int nValid = 0;
                final int wxLo = Math.max(x - halfRg, cohXlo);
                final int wxHi = Math.min(x + halfRg, cohXhi);

                for (int wy = wyLo; wy <= wyHi; wy++) {
                    // Stride is recomputed once per inner row (not per pixel within
                    // the row), since refIndex/secIndex share the same scanline layout.
                    refIndex.calculateStride(wy);
                    secIndex.calculateStride(wy);
                    final double[] cRow = (refCos != null) ? refCos[wy - cohRect.y] : null;
                    final double[] sRow = (refSin != null) ? refSin[wy - cohRect.y] : null;
                    if (allFloat) {
                        for (int wx = wxLo; wx <= wxHi; wx++) {
                            final int refIdx = refIndex.getIndex(wx);
                            final int secIdx = secIndex.getIndex(wx);
                            final double mi = refArrI[refIdx];
                            final double mq = refArrQ[refIdx];
                            final double si = secArrI[secIdx];
                            final double sq = secArrQ[secIdx];

                            // Skip samples where EITHER leg is the (0,0) geocoding fill. Counting a
                            // fill sample in sumRef but not in sumReal/sumSec drove the ratio toward
                            // zero, so valid pixels within half a window of a fill boundary read
                            // systematically low — indistinguishable from real decorrelation, and a
                            // geocoded product is mostly fill around its edges.
                            if ((mi == 0.0 && mq == 0.0) || (si == 0.0 && sq == 0.0)) {
                                continue;
                            }
                            ++nValid;

                            double pr = mi * si + mq * sq;
                            double pi = mq * si - mi * sq;
                            if (cRow != null) {
                                final int k = wx - cohRect.x;
                                final double cs = cRow[k], sn = sRow[k];
                                final double rot = pr * cs + pi * sn;
                                pi = -pr * sn + pi * cs;
                                pr = rot;
                            }
                            sumReal += pr;
                            sumImag += pi;
                            sumRef += mi * mi + mq * mq;
                            sumSec += si * si + sq * sq;
                        }
                    } else {
                        for (int wx = wxLo; wx <= wxHi; wx++) {
                            final int refIdx = refIndex.getIndex(wx);
                            final int secIdx = secIndex.getIndex(wx);
                            final double mi = refDataI.getElemDoubleAt(refIdx);
                            final double mq = refDataQ.getElemDoubleAt(refIdx);
                            final double si = secDataI.getElemDoubleAt(secIdx);
                            final double sq = secDataQ.getElemDoubleAt(secIdx);

                            if ((mi == 0.0 && mq == 0.0) || (si == 0.0 && sq == 0.0)) {
                                continue;   // geocoding fill on either leg — see the float path above
                            }
                            ++nValid;

                            double pr = mi * si + mq * sq;
                            double pi = mq * si - mi * sq;
                            if (cRow != null) {
                                final int k = wx - cohRect.x;
                                final double cs = cRow[k], sn = sRow[k];
                                final double rot = pr * cs + pi * sn;
                                pi = -pr * sn + pi * cs;
                                pr = rot;
                            }
                            sumReal += pr;
                            sumImag += pi;
                            sumRef += mi * mi + mq * mq;
                            sumSec += si * si + sq * sq;
                        }
                    }
                }

                // No valid sample pair in the window => no-data, so the coherence mask matches the
                // interferogram mask. Previously a non-zero coherence was written at pixels where the
                // interferogram itself is no-data, because the window still caught valid neighbours.
                final double coh;
                if (nValid == 0) {
                    coh = COHERENCE_NO_DATA;
                } else {
                    final double crossMag = Math.sqrt(sumReal * sumReal + sumImag * sumImag);
                    final double denom = Math.sqrt(sumRef * sumSec);
                    coh = (denom > 0) ? crossMag / denom : COHERENCE_NO_DATA;
                }

                cohData.setElemDoubleAt(cohIndex.getIndex(x), coh);
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
            iBand.setNoDataValueUsed(true);
            iBand.setNoDataValue(0);
            targetBandNames.add(iBand.getName());

            final String targetBandName_Q = "q_" + productTag + tag;
            final Band qBand = targetProduct.addBand(targetBandName_Q, ProductData.TYPE_FLOAT32);
            container.addBand(Unit.IMAGINARY, qBand.getName());
            qBand.setUnit(Unit.IMAGINARY);
            qBand.setNoDataValueUsed(true);
            qBand.setNoDataValue(0);
            targetBandNames.add(qBand.getName());

            if (CREATE_VIRTUAL_BAND) {
                final String countStr = '_' + productTag + tag;
                ReaderUtils.createVirtualIntensityBand(targetProduct,
                        targetProduct.getBand(targetBandName_I), targetProduct.getBand(targetBandName_Q), countStr);

                Band phaseBand = createGuardedPhaseBand(targetProduct,
                        targetProduct.getBand(targetBandName_I), targetProduct.getBand(targetBandName_Q), countStr);
                targetProduct.setQuicklookBandName(phaseBand.getName());
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
                    // Only treat a pixel as no-data when BOTH components are zero; a
                    // valid interferogram pixel can have r==0 (phase ±π/2) with i!=0.
                    if (r == 0.0f && i == 0.0f) {
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
                final int refHeightIdx = refHeightIndex.getIndex(x);
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

    /**
     * Provenance flag written by {@code S1ETADCorrectionOp}. A literal string, not that class's
     * constant: {@code sar-op-sentinel1} compile-depends on this module, so the reverse import would
     * be a Maven reactor cycle.
     */
    static final String ETAD_PHASE_APPLIED_ATTR = "etad_phase_applied";

    /**
     * Refuse to form an interferogram from a GSLC stack that mixes ETAD-corrected and uncorrected
     * acquisitions.
     * <p>
     * The classical path degrades safely: {@code checkETADCorrection} ANDs the reference and secondary
     * grid presence, so a one-sided pair gets no correction and the interferogram is uncorrected but
     * internally consistent. The geocode-first chain has no such option — the correction is baked into
     * the complex data per product, before the pair exists, and cannot be undone.
     * <p>
     * A one-sided stack therefore retains an uncompensated range-delay phase of order tens of radians
     * (the differential delay is 0.15-0.40 m, i.e. 34-91 rad at C-band): smooth, spatially correlated,
     * and indistinguishable from deformation. No downstream check can find it in the data, so this
     * throws rather than warning — a {@code SystemUtils.LOG.warning} reaches only the log file in SNAP
     * Desktop, which for a defect that silently mimics the signal being measured is not enough.
     * <p>
     * Absent provenance reads as 0 on both sides, so pre-flag stacks are unaffected.
     */
    private void checkETADStateSymmetry(final MetadataElement absRoot) {

        final int refState = safeFlag(absRoot, ETAD_PHASE_APPLIED_ATTR);
        final List<String> mismatched;
        try {
            mismatched = findETADPhaseMismatches(
                    refState, StackUtils.findSecondaryMetadataRoot(sourceProduct));
        } catch (Exception e) {
            SystemUtils.LOG.fine("InterferogramOp: ETAD symmetry check skipped: " + e.getMessage());
            return;
        }
        if (mismatched.isEmpty()) {
            return;
        }

        throw new OperatorException("InterferogramOp: ETAD state is asymmetric across this GSLC stack. "
                + "The reference has " + ETAD_PHASE_APPLIED_ATTR + '=' + refState
                + " but these secondaries differ: " + String.join(", ", mismatched) + ". "
                + "The ETAD range-delay phase was removed from one acquisition and not the other, so "
                + "the interferogram would retain an uncompensated atmospheric phase ramp of tens of "
                + "radians that is indistinguishable from deformation. Re-process every acquisition "
                + "in this stack with the same S1-ETAD-Correction configuration. (If both were in fact "
                + "corrected but one predates ETAD provenance being recorded, re-run "
                + "S1-ETAD-Correction on that acquisition so the flag is written.)");
    }

    /**
     * Secondaries whose ETAD phase state differs from the reference, described for a message.
     *
     * @param refState      the reference's {@code etad_phase_applied}
     * @param secondaryRoot the {@code Secondary_Metadata} element, may be null
     * @return one entry per mismatched secondary; empty when symmetric
     */
    static List<String> findETADPhaseMismatches(final int refState, final MetadataElement secondaryRoot) {

        final List<String> mismatched = new ArrayList<>();
        if (secondaryRoot == null) {
            return mismatched;
        }
        for (final MetadataElement sec : secondaryRoot.getElements()) {
            if (AbstractMetadata.ORIGINAL_PRODUCT_METADATA.equals(sec.getName())) {
                continue;
            }
            final int secState = safeFlag(sec, ETAD_PHASE_APPLIED_ATTR);
            if (secState != refState) {
                mismatched.add(sec.getName() + " (" + ETAD_PHASE_APPLIED_ATTR + '=' + secState + ')');
            }
        }
        return mismatched;
    }

    /**
     * Read an int flag, treating absent or non-numeric as 0. {@code getAttributeInt(name, default)}
     * returns the default only for an absent attribute; a non-numeric one throws.
     */
    private static int safeFlag(final MetadataElement elem, final String name) {
        if (elem == null) {
            return 0;
        }
        try {
            return elem.getAttributeInt(name, 0);
        } catch (Exception e) {
            return 0;
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
                refSecBurstMap.putAll(parseRefSecBurstMap(meta.getElement("ETAD_Burst_Index_Array")));
                break;
            }
        }
        return refSecBurstMap;
    }

    /**
     * Build the reference-to-secondary ETAD burst index mapping from the
     * {@code ETAD_Burst_Index_Array} element that
     * {@link eu.esa.sar.sentinel1.gpf.BackGeocodingOp} attaches to each secondary's metadata.
     * <p>
     * The current attribute names are {@code reference_bursts} / {@code secondary_bursts}. The
     * legacy {@code master_bursts} / {@code slave_bursts} names are still accepted so stacks
     * written before the terminology rename keep working. Reading the legacy names only was a bug:
     * nothing writes them, and the single-argument
     * {@link MetadataElement#getAttributeString(String)} throws for a missing attribute, so every
     * TOPS ETAD interferogram failed on its first tile.
     * <p>
     * Never throws: a null or incomplete element yields an empty map, which the callers treat as
     * "no burst mapping available".
     *
     * @param etadBurstsElem the {@code ETAD_Burst_Index_Array} element, may be null
     * @return reference burst index -> secondary burst index; empty when unavailable
     */
    static Map<Integer, Integer> parseRefSecBurstMap(final MetadataElement etadBurstsElem) {

        final Map<Integer, Integer> map = new HashMap<>();
        if (etadBurstsElem == null) {
            return map;
        }

        String refBursts = etadBurstsElem.getAttributeString("reference_bursts", "");
        String secBursts = etadBurstsElem.getAttributeString("secondary_bursts", "");
        if (refBursts.trim().isEmpty() || secBursts.trim().isEmpty()) {
            // Pre-rename stacks.
            refBursts = etadBurstsElem.getAttributeString("master_bursts", "");
            secBursts = etadBurstsElem.getAttributeString("slave_bursts", "");
        }

        final Integer[] refBurstArray = stringToIntegerArray(refBursts);
        final Integer[] secBurstArray = stringToIntegerArray(secBursts);

        final int n = Math.min(refBurstArray.length, secBurstArray.length);
        if (refBurstArray.length != secBurstArray.length) {
            SystemUtils.LOG.warning("InterferogramOp: ETAD_Burst_Index_Array has "
                    + refBurstArray.length + " reference and " + secBurstArray.length
                    + " secondary burst indices; using the first " + n + '.');
        }
        for (int i = 0; i < n; ++i) {
            map.put(refBurstArray[i], secBurstArray[i]);
        }
        return map;
    }

    /**
     * Parse a space-separated list of burst indices. The writer emits a trailing space, and an
     * empty string when every index is -1, so blank entries are skipped rather than parsed.
     */
    private static Integer[] stringToIntegerArray(final String inputStr) {
        if (inputStr == null || inputStr.trim().isEmpty()) {
            return new Integer[0];
        }
        return Stream.of(inputStr.trim().split("\\s+"))
                .filter(s -> !s.isEmpty())
                .mapToInt(Integer::parseInt).boxed().toArray(Integer[]::new);
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
