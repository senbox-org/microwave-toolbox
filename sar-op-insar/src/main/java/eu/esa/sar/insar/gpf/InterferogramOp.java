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
            "spacing of the inputs. Recommended for GSLC inputs so the multilook " +
            "support is set in physical units regardless of map-grid pixel size. It yields a " +
            "window that is square on the ground in both radar and map geometry " +
            "(the radar branch divides by the ground-range step, rg_spacing / sin(incidence)), " +
            "making radar-geometry and geocoded results directly comparable.",
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

    /*
        @Parameter(interval = "(1, 10]",
                description = "Degree of orbit interpolation polynomial",
                defaultValue = "3",
                label = "Orbit Interpolation Degree")
        private int orbitDegree = 3;
    */
    @Parameter(description = "GSLC TOPS only: measure each burst seam's residual phase " +
            "discontinuity directly (across-seam multilooked differences) and subtract it. This is " +
            "NOT a smooth-surface model: anything continuous across a seam - deformation, " +
            "atmosphere, orbital ramps - cancels in the across-seam difference to the extent that " +
            "it is locally LINEAR in azimuth, so unlike a fitted surface this cannot absorb a " +
            "broad signal wholesale; azimuth curvature does leak, and cumulatively with burst " +
            "index. No effect on stripmap products, which have no burst seams.",
            defaultValue = "false", label = "Subtract burst seam steps (GSLC)")
    private boolean subtractSeamSteps = false;

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

    private volatile boolean gslcSeamStepsEstimated = false;
    private final Object gslcSeamLock = new Object();
    static final double GSLC_RAMP_NORM = 1000.0;            // px, range normalisation of the seam profiles

    // Burst geometry for the seam-step correction. Burst intervals are read from the reference's
    // Original_Product_Metadata annotation (a plain metadata walk: this module must not depend on
    // sar-op-sentinel1, and the walk also works on stacks that predate any stamping). Without the
    // annotation there are no seams to correct - stripmap products have none.
    private double[] gslcBurstStartSod;                     // reference-burst azimuth start, seconds of day
    private double[] gslcBurstEndSod;                       // reference-burst azimuth end, seconds of day
    private GslcSeamSteps[] gslcSeamStepsPerPair;           // [pair], null = seam steps unmeasured
    private GslcPerBurstRamp[] gslcBurstLabelling;          // [pair], burst labelling for the seam term
    // Exact carrier-difference subtraction: when both legs carry the GSLC deramp-model band
    // (GSLCGeocodingOp outputPhaseTerms, propagated by CreateStack), the interferogram subtracts
    // the models' leg difference EXACTLY — full range and azimuth structure within every burst.
    // With the add-back sign corrected (CARRIER_DIFF_SIGN) this is the whole of the cross-
    // acquisition annotation correction; the data-driven smooth-surface models that used to
    // follow it were removed, see docs/gslc-parity/etna-ramp-ab.md.
    private static final String GSLC_CARRIER_MODEL_BAND = "azimuthCarrierPhase";

    /**
     * Sign with which {@code (m_sec - m_ref)} is added to the subtracted reference-phase surface.
     * <b>-1 (default) is correct</b>: {@code GSLCGeocodingOp} restores the carrier with exp(-j*phi), so a
     * carrier-free TOPS leg still carries {@code truth x exp(+j*m)}; the conjugate product then carries
     * {@code +(m_ref - m_sec)} and adding {@code (m_ref - m_sec)} to the subtracted surface restores the
     * classical phase. Measured on the Venezuela S1A x S1C pair (ETAD on, bursts 4-6): the GSLC carrier
     * band equals the classical Back-Geocoding deramp phase to <0.01 rad and the carrier-free legs equal the
     * classical deramped legs; with this sign the interferogram agrees with the classical one (R5b phase
     * concentration 0.961, gradient ratios 0.87 / 0.86), whereas the former +1 left a per-burst surface of
     * 2 x (m_ref - m_sec) (concentration 0.236, azimuth gradient ratio 37.9).
     * {@code -Dgslc.carrierDiffSign=+1} restores the LEGACY behaviour, only to reproduce older results.
     */
    static final double CARRIER_DIFF_SIGN = readCarrierDiffSign(System.getProperty("gslc.carrierDiffSign"));

    static double readCarrierDiffSign(final String property) {
        final String p = property == null ? "" : property.trim();
        return ("+1".equals(p) || "1".equals(p)) ? 1.0 : -1.0;
    }

    /** Amount added to the subtracted reference-phase surface for one pixel (radians). */
    public static double carrierDiffAngle(final double mRef, final double mSec) {
        return CARRIER_DIFF_SIGN * (mSec - mRef);
    }

    private Band[] gslcRefCarrierBand;                      // [pair], null = band not available
    private Band[] gslcSecCarrierBand;

    /**
     * Burst labelling in AZIMUTH TIME, plus the (now always zero) per-burst phase terms that
     * {@link #phaseAt} evaluates.
     * <p>
     * This used to be a FITTED per-burst residual-ramp model. That model was removed: the smooth
     * surface it existed to remove turned out to be an inverted carrier-difference add-back sign,
     * and with the sign corrected the fit only removed signal (docs/gslc-parity/etna-ramp-ab.md).
     * What survives is the part the seam-step correction needs — {@link #burstOfSod}, which says
     * which burst a ground point belongs to. Instances are built with zeroed coefficient arrays,
     * so {@code phaseAt} contributes nothing; the coefficient fields are kept because they define
     * the evaluation shape and because a future per-burst model would populate them.
     * <p>
     * Azimuth time — not map row — is the physical axis: iso-eta lines are TILTED ~10-12° in map
     * space, so a map-row parameterisation would mislabel points near a seam by several rows
     * across a swath. In eta the burst boundary is exact.
     */
    static final class GslcPerBurstRamp {
        final double[] ak, ck;               // range terms, rad/(N px) and rad/(N px)^2 — arrays
                                             // per burst for phaseAt's shape, but DELIBERATELY all
                                             // filled with ONE shared pooled fit: per-burst
                                             // absolute range terms were tried and measurably
                                             // absorbed the coseismic deformation fan (a=+12 vs ~0
                                             // rad/Npx on the southern bursts). The genuine
                                             // per-burst range structure of the annotation error
                                             // is removed by the seam-step corrector instead,
                                             // which only sees discontinuities. Do NOT refit
                                             // these per burst.
        final double[] etaK, bk, qk, dk;     // eta centres (sod), rad/s, rad/s^2, rad
        final double[] burstStartSod, burstEndSod;

        GslcPerBurstRamp(final double[] ak, final double[] ck, final double[] etaK, final double[] bk,
                         final double[] qk, final double[] dk,
                         final double[] burstStartSod, final double[] burstEndSod) {
            this.ak = ak; this.ck = ck; this.etaK = etaK; this.bk = bk; this.qk = qk; this.dk = dk;
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
            return dk[k] + ak[k] * xn + ck[k] * xn * xn + bk[k] * de + qk[k] * de * de;
        }

        /** Within-burst azimuth phase rate (rad/s) at azimuth time {@code etaSod}. */
        double rateAt(final double etaSod, final int k) {
            return bk[k] + 2.0 * qk[k] * (etaSod - etaK[k]);
        }

    }

    /**
     * Per-seam residual step profiles s_k(x): what remains DISCONTINUOUS at each burst seam after
     * the carrier difference — measured on S1A x S1C as a smooth range-quadratic step of 3-6 rad
     * per seam, wrapping along the swath. (It was formerly measured after a fitted per-burst ramp
     * as well; that model no longer exists, so the ambient gradient this measurement has to
     * subtract for itself is correspondingly larger.) Fitted from ACROSS-SEAM multilooked phase
     * differences, out of which a continuous field (deformation, atmosphere, residual topography)
     * cancels TO THE EXTENT THAT IT IS LOCALLY LINEAR in azimuth over the 72-row stencil: the
     * measurement is cross minus a scaled ambient gradient, not a pure difference, so azimuth
     * CURVATURE in genuine signal does leak — and because the model is applied cumulatively that
     * leak accumulates with burst index. Applied cumulatively:
     * burst m carries the sum of all seam steps below it, so the modeled surface reproduces every
     * measured discontinuity and the subtraction leaves the interferogram seam-free.
     * Each seam's profile is a TABLE of (xn, step) nodes — the unwrapped, outlier-filtered,
     * weight-smoothed per-window measurements themselves, linearly interpolated with flat
     * extrapolation (xn = x/{@link #GSLC_RAMP_NORM}; sign: phase(k+1) - phase(k)). A global
     * quadratic was tried first and measurably failed: the real profiles carry range structure
     * beyond quadratic (the quadratic matched mid-swath and left 1-2.5 rad at the swath edges).
     * A null table = seam not measurable, contributes 0. An overall 2*pi branch per seam is
     * irrelevant on a wrapped interferogram.
     */
    static final class GslcSeamSteps {
        final double[][][] tab;    // [seam][0] = xn nodes ascending, [seam][1] = step values

        GslcSeamSteps(final double[][][] tab) {
            this.tab = tab;
        }

        double stepAt(final int seam, final double x) {
            if (seam < 0 || seam >= tab.length || tab[seam] == null) return 0.0;
            final double[] xs = tab[seam][0];
            final double[] ss = tab[seam][1];
            final double xn = x / GSLC_RAMP_NORM;
            if (xn <= xs[0]) return ss[0];
            final int n = xs.length;
            if (xn >= xs[n - 1]) return ss[n - 1];
            int i = 1;
            while (xs[i] < xn) i++;
            final double t = (xn - xs[i - 1]) / (xs[i] - xs[i - 1]);
            return ss[i - 1] + t * (ss[i] - ss[i - 1]);
        }

        /** Cumulative correction for burst {@code m}: sum of all seam steps below it. */
        double cumAt(final int m, final double x) {
            double v = 0.0;
            final int top = Math.min(m, tab.length);
            for (int k = 0; k < top; k++) {
                v += stepAt(k, x);
            }
            return v;
        }
    }

    /**
     * 1-D unwrap of wrapped step samples ordered along range: each finite sample moves to the
     * 2*pi branch nearest the previous finite one. NaNs pass through untouched. The common branch
     * of the result is arbitrary — and irrelevant for a wrapped product.
     */
    static double[] unwrapStepsAlongRange(final double[] wrapped) {
        final double[] u = wrapped.clone();
        double prev = Double.NaN;
        for (int i = 0; i < u.length; i++) {
            if (!Double.isFinite(u[i])) continue;
            if (Double.isFinite(prev)) {
                u[i] += 2.0 * Math.PI * Math.round((prev - u[i]) / (2.0 * Math.PI));
            }
            prev = u[i];
        }
        return u;
    }

    /**
     * Build a seam-step TABLE from WRAPPED per-window step measurements: keep finite positive-
     * weight samples sorted by xn, unwrap along range, median-of-3 (absorbs isolated outlier
     * windows), then weighted 3-point smoothing. Returns {@code {xnNodes, stepNodes}} for
     * {@link GslcSeamSteps}, or null when fewer than 5 finite samples support the table.
     */
    static double[][] buildSeamStepTable(final double[] xn, final double[] stepWrapped,
                                         final double[] weight) {
        final int nIn = xn.length;
        final Integer[] order = new Integer[nIn];
        for (int i = 0; i < nIn; i++) order[i] = i;
        java.util.Arrays.sort(order, (i, j) -> Double.compare(xn[i], xn[j]));
        final java.util.List<double[]> pts = new java.util.ArrayList<>();
        for (int i = 0; i < nIn; i++) {
            final double s = stepWrapped[order[i]];
            final double w = weight[order[i]];
            if (Double.isFinite(s) && w > 0) {
                pts.add(new double[]{xn[order[i]], s, w});
            }
        }
        if (pts.size() < 5) return null;
        // Split at oversized gated gaps and keep the longest segment: across a long incoherent
        // run the true step can change by > pi between adjacent retained windows, so the unwrap
        // would pick a wrong 2*pi branch and interpolate a FALSE fringe across the gap. Flat
        // extrapolation beyond the kept segment invents no transition.
        {
            final int m = pts.size();
            final double[] gaps = new double[m - 1];
            for (int i = 0; i < m - 1; i++) gaps[i] = pts.get(i + 1)[0] - pts.get(i)[0];
            final double[] sortedGaps = gaps.clone();
            java.util.Arrays.sort(sortedGaps);
            final double maxGap = Math.max(6.0 * Math.max(sortedGaps[sortedGaps.length / 2], 1e-9), 12.0);
            int bestStart = 0, bestLen = 0, segStart = 0;
            for (int i = 0; i <= m - 1; i++) {
                final boolean breakHere = i == m - 1 || gaps[i] > maxGap;
                if (breakHere) {
                    final int len = i - segStart + 1;
                    if (len > bestLen) {
                        bestLen = len;
                        bestStart = segStart;
                    }
                    segStart = i + 1;
                }
            }
            if (bestLen < pts.size()) {
                pts.subList(bestStart + bestLen, pts.size()).clear();
                pts.subList(0, bestStart).clear();
            }
        }
        final int n = pts.size();
        if (n < 5) return null;
        final double[] xs = new double[n], ws = new double[n], wrapped = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = pts.get(i)[0];
            wrapped[i] = pts.get(i)[1];
            ws[i] = pts.get(i)[2];
        }
        final double[] u = unwrapStepsAlongRange(wrapped);
        // median-of-3: robust to one bad window without dragging its neighbours
        final double[] med = u.clone();
        for (int i = 1; i < n - 1; i++) {
            final double a = u[i - 1], b = u[i], c = u[i + 1];
            med[i] = Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
        }
        // End nodes have no median protection and their values flat-extrapolate over the whole
        // swath margin: clamp a deviant end to its neighbour. The threshold scales with BOTH the
        // profile's own node-to-node variation AND the actual end-pair gap (after gating the end
        // pair can sit several window spacings apart — exactly when a large genuine delta occurs).
        if (n >= 3) {
            final double[] adj = new double[n - 1];
            double medGap = 0;
            {
                final double[] gaps = new double[n - 1];
                for (int i = 0; i < n - 1; i++) {
                    adj[i] = Math.abs(med[i + 1] - med[i]);
                    gaps[i] = xs[i + 1] - xs[i];
                }
                java.util.Arrays.sort(gaps);
                medGap = Math.max(gaps[gaps.length / 2], 1e-9);
            }
            java.util.Arrays.sort(adj);
            final double base = Math.max(1.2, 3.0 * adj[adj.length / 2]);
            final double thrLo = base * Math.max(1.0, (xs[1] - xs[0]) / medGap);
            final double thrHi = base * Math.max(1.0, (xs[n - 1] - xs[n - 2]) / medGap);
            if (Math.abs(med[0] - med[1]) > thrLo) med[0] = med[1];
            if (Math.abs(med[n - 1] - med[n - 2]) > thrHi) med[n - 1] = med[n - 2];
        }
        // weighted 3-point smoothing on interior nodes; end nodes keep their median value —
        // averaging an end node with its single neighbour drags it along the local slope
        // (measured bias ~0.3 rad on a steep profile), worse than its raw noise
        final double[] sm = med.clone();
        for (int i = 1; i < n - 1; i++) {
            final double wsum = ws[i - 1] + 2.0 * ws[i] + ws[i + 1];
            sm[i] = (ws[i - 1] * med[i - 1] + 2.0 * ws[i] * med[i] + ws[i + 1] * med[i + 1]) / wsum;
        }
        return new double[][]{xs, sm};
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

    /**
     * Convert a ground distance into (range, azimuth) window sizes in pixels.
     *
     * In RADAR geometry {@code range_spacing} is SLANT range, so the ground step is
     * {@code rgSpacing / sin(incidence)}. Dividing the requested metres by the slant step
     * instead - as this did before 2026-09-18 - produced a window ~1/sin(theta) too wide
     * on the ground (~1.5x at S1 IW incidence), silently biasing any coherence comparison
     * against a geocoded product. In MAP geometry the spacing is already a ground step.
     *
     * @param isGeocoded {@code is_terrain_corrected == 1}
     */
    static int[] coherenceWindowFromMeters(final double meters, final double rgSpacing,
                                           final double azSpacing, final double incidenceDeg,
                                           final boolean isGeocoded) {
        double groundRg = rgSpacing;
        if (!isGeocoded && incidenceDeg > 1.0 && incidenceDeg < 89.0) {
            groundRg = rgSpacing / Math.sin(Math.toRadians(incidenceDeg));
        }
        final int winRg = groundRg > 0.0 ? Math.max(3, (int) Math.round(meters / groundRg)) : 3;
        final int winAz = azSpacing > 0.0 ? Math.max(3, (int) Math.round(meters / azSpacing)) : 3;
        return new int[]{winRg, winAz};
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
        final double incNear = AbstractMetadata.getAttributeDouble(abs, AbstractMetadata.incidence_near);
        final double incFar = AbstractMetadata.getAttributeDouble(abs, AbstractMetadata.incidence_far);
        final double incidenceDeg = (incNear > 0.0 && incFar > 0.0) ? 0.5 * (incNear + incFar) : 0.0;
        final boolean isGeocoded = abs.getAttributeInt(AbstractMetadata.is_terrain_corrected, 0) == 1;
        final int[] win = coherenceWindowFromMeters(cohWinSizeMeters, rgSpacing, azSpacing,
                incidenceDeg, isGeocoded);
        cohWinRg = win[0];
        cohWinAz = win[1];
        SystemUtils.LOG.info(String.format(
                "InterferogramOp: cohWinSizeMeters=%.1f m -> cohWinAz=%d, cohWinRg=%d "
                        + "(pixel spacing az=%.2f m, rg=%.2f m, incidence=%.2f deg, geocoded=%b)",
                cohWinSizeMeters, cohWinAz, cohWinRg, azSpacing, rgSpacing, incidenceDeg, isGeocoded));
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
            SystemUtils.LOG.fine("GSLC seam steps: burst-table walk failed: " + t.getMessage());
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

        // TOPS-only: carrier-free TOPS legs carry truth*exp(-j*m) so the model DIFFERENCE remains
        // in the interferogram and must be subtracted. The STRIPMAP path demodulates and fully
        // restores its f_dc carrier (a round trip) — the model difference is NOT in the data, and
        // subtracting it would INJECT a large spurious azimuth term. Stripmap azimuthCarrierPhase
        // bands are informational only (they used to be empty; they are filled now).
        if (extractGslcBurstTableSod(sourceProduct) == null) {
            SystemUtils.LOG.info("GSLC carrier-difference: stripmap stack (no TOPS burst "
                    + "annotation) — model subtraction not applicable; any azimuthCarrierPhase "
                    + "bands are informational only.");
            return;
        }

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
                        + secCarrier.getName() + "')."
                        + (CARRIER_DIFF_SIGN > 0 ? " LEGACY add-back sign in use (gslc.carrierDiffSign=+1): "
                        + "expect a per-burst surface of 2*(m_ref - m_sec) in the interferogram." : ""));
            } else if (refCarrier != null || secCarrier != null) {
                SystemUtils.LOG.warning("GSLC carrier-difference: only ONE leg of pair " + p
                        + " carries the '" + GSLC_CARRIER_MODEL_BAND + "' band — regenerate both "
                        + "GSLCs with outputPhaseTerms=true to enable exact model subtraction. "
                        + "Without it NO cross-acquisition annotation correction is applied.");
            }
        }
    }

    /**
     * Add the leg difference of the GSLC deramp-model bands into the reference-phase surface.
     * {@code GSLCGeocodingOp} restores the carrier with exp(-j·m), so carrier-free legs carry
     * {@code truth × exp(+j·m)} and the conjugate product carries {@code +(m_ref - m_sec)}; adding
     * {@code (m_ref - m_sec)} to the SUBTRACTED surface (multiplying the interferogram by
     * exp(-j·(m_ref - m_sec))) restores the classical interferometric phase. This method adds
     * {@link #CARRIER_DIFF_SIGN}{@code × (m_sec - m_ref)}, i.e. {@code (m_ref - m_sec)} by default.
     *
     * <p>History: until 2026-09-21 this added {@code (m_sec - m_ref)}, on the premise that carrier-free
     * legs carry exp(-j·m) ("sign pinned empirically: the fitted residual rates collapse"). The premise
     * was inverted: the legs carry exp(+j·m), and the old sign left 2 × (m_ref - m_sec) in every GSLC
     * TOPS interferogram, a per-burst azimuth/range surface that the residual-ramp estimator then fitted.
     * The synthetic tests could not see it (identical geometry gives m_ref = m_sec; a closure of three
     * pairs cancels it identically). See the evidence on {@link #CARRIER_DIFF_SIGN}.
     */
    private void addGslcCarrierModelDiff(final double[][] refPhase, final Rectangle rect, final int p) {
        final Tile refT = getSourceTile(gslcRefCarrierBand[p], rect);
        final Tile secT = getSourceTile(gslcSecCarrierBand[p], rect);
        for (int y = 0; y < rect.height; y++) {
            final int yy = rect.y + y;
            final double[] row = refPhase[y];
            for (int x = 0; x < rect.width; x++) {
                final int xx = rect.x + x;
                row[x] += carrierDiffAngle(refT.getSampleDouble(xx, yy), secT.getSampleDouble(xx, yy));
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

    /**
     * Measure the per-burst seam steps once, for every pair. Seam steps are the only GSLC
     * residual model left: they are measured as the DISCONTINUITY across each burst seam, so
     * anything continuous across it - deformation, atmosphere, orbital ramps - cancels in the
     * difference and cannot be absorbed. (The data-driven smooth-surface models that used to
     * live here were removed: the surface they targeted was an inverted carrier-difference
     * add-back sign, and with that corrected they only removed signal - see
     * docs/gslc-parity/etna-ramp-ab.md.)
     */
    private void estimateGslcSeamStepsOnce() {
        if (gslcSeamStepsEstimated) return;
        synchronized (gslcSeamLock) {
            if (gslcSeamStepsEstimated) return;
            gslcSeamStepsPerPair = new GslcSeamSteps[gslcReferenceI.length];
            gslcBurstLabelling = new GslcPerBurstRamp[gslcReferenceI.length];
            final boolean perBurst = gslcBurstStartSod != null && gslcBurstStartSod.length >= 2
                    && gslcRefOrbit != null && gslcRefSLC != null && gslcGeoCoding != null;
            if (!perBurst) {
                if (gslcBurstStartSod == null && extractGslcBurstTableSod(sourceProduct) != null) {
                    // The burst table is only harvested by setupGSLCReferencePhase, which runs when
                    // flat-earth or topographic phase removal is on.
                    SystemUtils.LOG.warning("GSLC seam steps: TOPS burst annotation is present but "
                            + "the correction needs the reference geometry - enable "
                            + "subtractFlatEarthPhase or subtractTopographicPhase.");
                } else {
                    SystemUtils.LOG.info("GSLC seam steps: no burst annotation - nothing to correct "
                            + "(stripmap products have no burst seams).");
                }
                gslcSeamStepsEstimated = true;
                return;
            }
            final int nB = gslcBurstStartSod.length;
            for (int p = 0; p < gslcReferenceI.length; p++) {
                try {
                    // Burst LABELLING only: the seam term is applied per burst, so it needs
                    // burstOfSod, but no phase is modelled here.
                    final double[] etaK = new double[nB];
                    for (int k = 0; k < nB; k++) {
                        etaK[k] = 0.5 * (gslcBurstStartSod[k] + gslcBurstEndSod[k]);
                    }
                    final GslcPerBurstRamp labelling = new GslcPerBurstRamp(
                            new double[nB], new double[nB], etaK, new double[nB], new double[nB],
                            new double[nB], gslcBurstStartSod, gslcBurstEndSod);
                    gslcBurstLabelling[p] = labelling;
                    gslcSeamStepsPerPair[p] = measureGslcSeamSteps(p, labelling);
                } catch (Throwable t) {
                    SystemUtils.LOG.warning("GSLC seam steps: measurement failed for pair " + p
                            + " (" + t + ") - seam correction skipped for this pair.");
                }
            }
            gslcSeamStepsEstimated = true;
        }
    }


    /**
     * Is the measured ambient azimuth gradient safe to extrapolate from the outer-band span to the
     * across-seam span?
     * <p>
     * The ambient gradient is measured as a wrapped angle over {@code spanAmb} rows and scaled by
     * {@code spanCross / spanAmb} (46/32 = 1.4375) to predict the gradient contribution over the
     * across-seam span. {@code atan2} is confined to (-pi, pi], so once the TRUE gradient over
     * spanAmb passes pi the measurement aliases and the scaled correction is wrong by
     * {@code (spanCross / spanAmb) * 2*pi} - about 9 rad, which lands whole in the reported step.
     * A single measurement cannot tell an aliased angle from a genuine one, so the only sound
     * response is to refuse the window rather than extrapolate it.
     * <p>
     * The bound is the angle at which the extrapolated correction itself reaches pi
     * ({@code pi * spanAmb / spanCross}, about 2.19 rad): beyond it the corrected step cannot be
     * distinguished from its own alias even if the ambient measurement were exact.
     */
    static boolean ambientIsExtrapolable(final double ambAngle, final double spanCross,
                                         final double spanAmb) {
        if (!Double.isFinite(ambAngle)) {
            return false;
        }
        return Math.abs(ambAngle) <= Math.PI * spanAmb / spanCross;
    }

    private GslcSeamSteps measureGslcSeamSteps(final int p, final GslcPerBurstRamp ramp) {
        try {
            final int w = sourceProduct.getSceneRasterWidth();
            final int h = sourceProduct.getSceneRasterHeight();
            final int nSeams = ramp.burstStartSod.length - 1;
            if (nSeams < 1) return null;

            final int NXW = 16;        // column windows across the swath
            final int NSUB = 8;        // sub-chunks per window (seam row re-solved per chunk: tilt)
            final int CHW = 64;        // columns per sub-chunk
            final int BL = 8;          // multilook block width
            if (w < 128 + NXW / 4 + NSUB * CHW) {
                SystemUtils.LOG.info("GSLC seam steps: scene too narrow to measure (" + w
                        + " columns) — seam-step removal skipped.");
                return null;
            }
            // row bands relative to the seam row: U2/U north, D/D2 south.
            // U=[-B_OUT,-B_IN), D=[+B_IN,+B_OUT), U2/D2 the outer bands. B_IN=8 gives the seam
            // guard ~1-row margin over the documented iso-eta tilt (tan(12 deg)*32 cols ~ 6.8 rows
            // across a half sub-chunk).
            final int B_IN = 8, B_OUT = 38, B_FAR = 72;
            final double spanCross = B_IN + B_OUT;             // 46 rows between U and D centres
            final double spanAmb = (B_FAR - B_IN) / 2.0;       // 32 rows between outer/inner centres
            SystemUtils.LOG.info("GSLC seam steps (pair " + p + "): measuring " + nSeams
                    + " seam(s) x " + NXW + " window(s)...");

            // Pass orientation decides which burst the NORTH bands see: ascending (north = later
            // time) puts burst k+1 in U; descending puts burst k there, flipping the measured
            // step's sign relative to the declared phase(k+1)-phase(k) convention.
            boolean descending = false;
            boolean oriented = false;
            for (int cx = w / 2; !oriented && cx < w - 64; cx += w / 8) {
                try {
                    final int bTop = gslcBurstAt(cx, 8, ramp);
                    final int bBot = gslcBurstAt(cx, h - 8, ramp);
                    if (bTop != bBot) {
                        descending = bTop < bBot;
                        oriented = true;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (!oriented) {
                SystemUtils.LOG.warning("GSLC seam steps: could not determine pass orientation "
                        + "for pair " + p + " — seam-step removal skipped.");
                return null;
            }

            int ambRejected = 0;
            final double[][][] tabs = new double[nSeams][][];
            final StringBuilder lg = new StringBuilder(String.format(
                    "GSLC seam steps (pair %d): measured residual discontinuities:", p));
            for (int k = 0; k < nSeams; k++) {
                final double[] xn = new double[NXW];
                final double[] st = new double[NXW];
                final double[] wt = new double[NXW];
                java.util.Arrays.fill(st, Double.NaN);
                int used = 0;
                for (int wdx = 0; wdx < NXW; wdx++) {
                    // isolated per window: one bad tile read must not abort all seams
                    try {
                        final int x0 = 64 + (int) ((long) (w - 128 - NSUB * CHW) * wdx / (NXW - 1));
                        double crossRe = 0, crossIm = 0, ambRe = 0, ambIm = 0;
                        int nBlocks = 0;
                        double xSum = 0;
                        int nSub = 0;
                        for (int sub = 0; sub < NSUB; sub++) {
                            final int cx0 = x0 + sub * CHW;
                            final int r = findGslcSeamRow(cx0 + CHW / 2.0, k, ramp, h);
                            if (r < B_FAR + 8 || r > h - B_FAR - 8) continue;
                            final Rectangle rect = new Rectangle(cx0, r - B_FAR, CHW, 2 * B_FAR);
                            final double[][] bands = gslcSeamBandPhasors(p, rect, ramp,
                                    B_FAR, B_IN, B_OUT, BL);
                            if (bands == null) continue;
                            final int nb = CHW / BL;
                            int nBefore = nBlocks;
                            for (int b = 0; b < nb; b++) {
                                final double u2r = bands[0][2 * b], u2i = bands[0][2 * b + 1];
                                final double ur = bands[1][2 * b], ui = bands[1][2 * b + 1];
                                final double dr = bands[2][2 * b], di = bands[2][2 * b + 1];
                                final double d2r = bands[3][2 * b], d2i = bands[3][2 * b + 1];
                                if ((ur == 0 && ui == 0) || (dr == 0 && di == 0)) continue;
                                // cross = U * conj(D): phase(k+1) - phase(k) on ascending
                                crossRe += ur * dr + ui * di;
                                crossIm += ui * dr - ur * di;
                                // ambient, same row spacing sense: U2*conj(U) + D*conj(D2)
                                if ((u2r != 0 || u2i != 0)) {
                                    ambRe += u2r * ur + u2i * ui;
                                    ambIm += u2i * ur - u2r * ui;
                                }
                                if ((d2r != 0 || d2i != 0)) {
                                    ambRe += dr * d2r + di * d2i;
                                    ambIm += di * d2r - dr * d2i;
                                }
                                nBlocks++;
                            }
                            if (nBlocks > nBefore) {
                                xSum += cx0 + CHW / 2.0;   // centroid over CONTRIBUTING sub-chunks
                                nSub++;
                            }
                        }
                        if (nBlocks < 24 || (crossRe == 0 && crossIm == 0)
                                || (ambRe == 0 && ambIm == 0)) {
                            continue;
                        }
                        final double conc = Math.hypot(crossRe, crossIm) / nBlocks;
                        if (conc < 0.12) continue;   // below this the step angle is unreliable
                        final double amb = Math.atan2(ambIm, ambRe);
                        if (!ambientIsExtrapolable(amb, spanCross, spanAmb)) {
                            ambRejected++;
                            continue;
                        }
                        // north-minus-south, ambient gradient removed; flipped to the declared
                        // phase(k+1)-phase(k) convention when the later burst is SOUTH (descending)
                        double raw = Math.atan2(crossIm, crossRe)
                                - (spanCross / spanAmb) * amb;
                        if (descending) raw = -raw;
                        xn[wdx] = (xSum / nSub) / GSLC_RAMP_NORM;
                        st[wdx] = Math.atan2(Math.sin(raw), Math.cos(raw));
                        wt[wdx] = conc;
                        used++;
                    } catch (Throwable t) {
                        SystemUtils.LOG.fine("GSLC seam steps: window " + wdx + " of seam " + k
                                + " failed (" + t + ") — window skipped.");
                    }
                }
                tabs[k] = buildSeamStepTable(xn, st, wt);
                if (tabs[k] != null) {
                    final GslcSeamSteps one = new GslcSeamSteps(new double[][][]{tabs[k]});
                    lg.append(String.format(" [s%d %d win: %.2f/%.2f/%.2f rad @near/mid/far]",
                            k, used, one.stepAt(0, 0.1 * w), one.stepAt(0, 0.5 * w),
                            one.stepAt(0, 0.9 * w)));
                } else {
                    lg.append(String.format(" [s%d %d win: unmeasured]", k, used));
                }
            }
            if (ambRejected > 0) {
                lg.append(String.format(" [%d window(s) refused: ambient azimuth gradient too "
                        + "steep to extrapolate without aliasing]", ambRejected));
            }
            SystemUtils.LOG.info(lg.toString());
            return new GslcSeamSteps(tabs);
        } catch (Throwable t) {
            SystemUtils.LOG.warning("GSLC seam steps: measurement failed for pair " + p + ": "
                    + t.getMessage() + " — seam-step removal skipped.");
            return null;
        }
    }

    /**
     * Multilooked unit phasors of the four row bands around a seam (U2, U, D, D2), per 8-col
     * block, on the CORRECTED interferogram. Returns {@code [band][2*block]} = re, im (0,0 =
     * band unusable in that block), or null when the rect is mostly invalid. Which burst the
     * north bands see depends on pass orientation (the caller flips the step sign accordingly)
     * — bands are indexed U2, U, D, D2 from
     * north to south.
     */
    private double[][] gslcSeamBandPhasors(final int p, final Rectangle rect,
                                           final GslcPerBurstRamp ramp,
                                           final int bFar, final int bIn, final int bOut,
                                           final int bl) throws Exception {
        final Tile ti = getSourceTile(gslcReferenceI[p], rect);
        final Tile tq = getSourceTile(gslcReferenceQ[p], rect);
        final Tile si = getSourceTile(gslcSecondaryI[p], rect);
        final Tile sq = getSourceTile(gslcSecondaryQ[p], rect);
        double[][] refPhase = gslcRemoveRefPhase
                ? computeGslcReferencePhase(rect,
                        gslcSecSLCMap.get(gslcSecondaryI[p]), gslcSecOrbitMap.get(gslcSecondaryI[p]),
                        ramp, null, true)
                : computeGslcReferencePhase(rect, null, null, ramp, null, false);
        if (gslcCarrierDiffAvailable(p)) {
            if (refPhase == null) {
                refPhase = new double[rect.height][rect.width];
            }
            addGslcCarrierModelDiff(refPhase, rect, p);
        }
        final int nb = rect.width / bl;
        final double[][] out = new double[4][2 * nb];
        final int[][] rows = {
                {0, bFar - bOut},                          // U2 = [-bFar, -bOut)
                {bFar - bOut, bFar - bIn},                 // U  = [-bOut, -bIn)
                {bFar + bIn, bFar + bOut},                 // D  = [+bIn, +bOut)
                {bFar + bOut, 2 * bFar}                    // D2 = [+bOut, +bFar)
        };
        int invalid = 0;
        final double[][] acc = new double[4][2 * nb];
        final int[][] cnt = new int[4][nb];
        for (int band = 0; band < 4; band++) {
            for (int y = rows[band][0]; y < rows[band][1]; y++) {
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
                    final int b = x / bl;
                    acc[band][2 * b] += re / mag;
                    acc[band][2 * b + 1] += im / mag;
                    cnt[band][b]++;
                }
            }
        }
        // half of all scanned band samples invalid => the rect straddles too much nodata to trust
        if (invalid > rect.width * rect.height / 2) return null;
        for (int band = 0; band < 4; band++) {
            for (int b = 0; b < nb; b++) {
                final double re = acc[band][2 * b], im = acc[band][2 * b + 1];
                final double mag = Math.hypot(re, im);
                if (cnt[band][b] < bl * (bOut - bIn) / 2 || mag <= 0) continue;
                out[band][2 * b] = re / mag;
                out[band][2 * b + 1] = im / mag;
            }
        }
        return out;
    }

    /**
     * Map row of the boundary between bursts k and k+1, by bisection on a row->burst function.
     * Handles BOTH pass orientations: on ascending scenes (north-up map, north = later time) the
     * burst index is non-increasing with row; on descending scenes it is non-decreasing. Returns
     * the first row of the side that differs from the top, or -1 when the seam is not bracketed
     * at this column — never a wrong row. Package-visible and pure, for orientation tests.
     */
    static int findSeamRowGeneric(final java.util.function.IntUnaryOperator burstAtRow,
                                  final int k, final int rowLo, final int rowHi) {
        int lo = rowLo, hi = rowHi;
        final int bLo = burstAtRow.applyAsInt(lo);
        final int bHi = burstAtRow.applyAsInt(hi);
        if (bLo >= k + 1 && bHi <= k && bHi >= 0) {
            // ascending map: burst k+1 above the seam, burst k below
            while (hi - lo > 1) {
                final int mid = (lo + hi) >>> 1;
                final int b = burstAtRow.applyAsInt(mid);
                if (b < 0) return -1;   // solve failure mid-path: abort, never converge wrong
                if (b >= k + 1) lo = mid;
                else hi = mid;
            }
            return hi;
        }
        if (bLo <= k && bLo >= 0 && bHi >= k + 1) {
            // descending map: burst k above the seam, burst k+1 below
            while (hi - lo > 1) {
                final int mid = (lo + hi) >>> 1;
                final int b = burstAtRow.applyAsInt(mid);
                if (b < 0) return -1;   // solve failure mid-path: abort, never converge wrong
                if (b <= k) lo = mid;
                else hi = mid;
            }
            return hi;
        }
        return -1;
    }

    /** Map row of the boundary between bursts k and k+1 at column {@code x}; -1 = not bracketed. */
    private int findGslcSeamRow(final double x, final int k, final GslcPerBurstRamp ramp,
                                final int h) {
        return findSeamRowGeneric(row -> {
            try {
                return gslcBurstAt(x, row, ramp);
            } catch (Throwable t) {
                return -1;
            }
        }, k, 8, h - 8);
    }

    private int gslcBurstAt(final double x, final double y, final GslcPerBurstRamp ramp)
            throws Exception {
        return ramp.burstOfSod(refAzTimeSodAt(x, y));
    }

    private void computeTileStackForGSLC(final Map<Band, Tile> targetTileMap, final Rectangle targetRectangle) {
        try {
            if (subtractSeamSteps && !gslcSeamStepsEstimated) {
                estimateGslcSeamStepsOnce();
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
                // Burst labelling carries no phase of its own; it is what locates the seam term.
                final GslcPerBurstRamp rampPB = (subtractSeamSteps && gslcBurstLabelling != null
                        && p < gslcBurstLabelling.length) ? gslcBurstLabelling[p] : null;
                final GslcSeamSteps seamSt = (rampPB != null && gslcSeamStepsPerPair != null
                        && p < gslcSeamStepsPerPair.length) ? gslcSeamStepsPerPair[p] : null;
                double[][] refPhase = gslcRemoveRefPhase
                        ? computeGslcReferencePhase(refRect,
                                gslcSecSLCMap.get(gslcSecondaryI[p]), gslcSecOrbitMap.get(gslcSecondaryI[p]),
                                rampPB, seamSt, true)
                        : null;
                if (refPhase == null && seamSt != null) {
                    // Reference-phase removal off but seam steps on: model-only surface through the
                    // same node machinery.
                    refPhase = computeGslcReferencePhase(refRect, null, null, rampPB, seamSt, false);
                }

                // Exact deramp-model difference rides the same surface, so interferogram and
                // coherence stay mutually consistent (as for flat-earth/topo and the ramp).
                if (gslcCarrierDiffAvailable(p)) {
                    if (refPhase == null) {
                        refPhase = new double[refRect.height][refRect.width];
                    }
                    addGslcCarrierModelDiff(refPhase, refRect, p);
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

        if (subtractSeamSteps) {
            final double[][] burstTable = extractGslcBurstTableSod(sourceProduct);
            if (burstTable != null) {
                gslcBurstStartSod = burstTable[0];
                gslcBurstEndSod = burstTable[1];
                SystemUtils.LOG.info("GSLC seam steps: reference burst table with "
                        + gslcBurstStartSod.length + " bursts — seam-step correction enabled.");
            } else {
                SystemUtils.LOG.info("GSLC seam steps: no burst annotation found — nothing to "
                        + "correct (stripmap products have no burst seams).");
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
                                                 final GslcSeamSteps seamSteps,
                                                 final boolean includeRefTerm) throws Exception {
        if (includeRefTerm && (secSLC == null || secOrbit == null)) {
            return null;
        }
        final int w = rect.width, h = rect.height, x0 = rect.x, y0 = rect.y;
        final int step = GSLC_REFPHASE_SUBSAMPLE;
        final int nx = (w + step - 1) / step + 1;
        final int ny = (h + step - 1) / step + 1;
        final double[][] node = new double[ny][nx];
        // Per-pixel topographic correction: the node surface is bilinear between nodes, so it
        // cannot carry terrain phase below ~2*step px of wavelength. When a DEM is active we
        // additionally store each node's height and topo-phase sensitivity d(phi)/dh, and add
        // dphidh * (h_pixel - h_bilinear) during the full-resolution interpolation — first-order
        // exact for the terrain detail the node grid misses. Escape hatch: -Dgslc.refphase.pixelTopo=false.
        final boolean pixelTopo = includeRefTerm && subtractTopographicPhase && dem != null
                && !"false".equalsIgnoreCase(System.getProperty("gslc.refphase.pixelTopo", "true"));
        final double[][] nodeDphiDh = pixelTopo ? new double[ny][nx] : null;
        final double[][] nodeH = pixelTopo ? new double[ny][nx] : null;

        final double phaseFactor = includeRefTerm ? -4.0 * Constants.PI / secSLC.getRadarWavelength() : 0.0;
        final boolean useDem = subtractTopographicPhase && dem != null;
        // Use the field, not dem.getDescriptor(): FileElevationModel (any externalDEMFile) returns a
        // null descriptor, so dereferencing it here NPE'd on every tile. defineDEM() already resolves
        // the correct value for both the auto-download and external cases.
        final double demNoData = useDem ? demNoDataValue : 0.0;
        final GeoPos geo = new GeoPos();
        final PixelPos pix = new PixelPos();
        // The reference-orbit geometry solve (geocoding -> ECEF -> orbit range/azimuth time) is
        // only needed by the flat-earth/topo term, the per-burst ramp, and the range profile —
        // gslcGeoCoding/gslcRefOrbit/gslcRefSLC are populated by setupGSLCReferencePhase() only
        // when reference-phase removal is actually requested (flat-earth or topo on), so a path
        // that needs neither must not call into them.
        final boolean needsGeo = includeRefTerm || ramp != null;

        // Nodes are evaluated at their exact uniform-grid positions, including the last node of
        // each tile which may lie past the tile edge — the bilinear interpolation below assumes
        // uniform node spacing (gx = x/step), so clamping edge nodes to the tile edge would
        // mis-position them and bias the last cell of every tile. CrsGeoCoding extrapolates
        // linearly beyond the raster, so out-of-raster node positions are well-defined.
        for (int j = 0; j < ny; j++) {
            final int yy = y0 + j * step;
            for (int i = 0; i < nx; i++) {
                final int xx = x0 + i * step;
                double v = 0.0;
                if (needsGeo) {
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
                    if (includeRefTerm) {
                        final double tSec = secOrbit.xyz2t(xyz, secSLC).x;
                        // refPhaseSec = phaseFactor * (R_sec - R_ref); angle to subtract = refPhaseRef(=0) - refPhaseSec
                        v = -(phaseFactor * Constants.lightSpeed * (tSec - tpRef.x));

                        if (nodeDphiDh != null) {
                            // topo-phase sensitivity at this node, for the per-pixel height
                            // correction below: the node grid low-passes the topographic phase
                            // (everything under ~2*step px of terrain wavelength was simply
                            // MISSING from the removal — measured on Etna as a height-correlated
                            // residual at ~44% of the full topo term at fine scale).
                            final Point xyzUp = Ellipsoid.ell2xyz(FastMath.toRadians(geo.lat),
                                    FastMath.toRadians(geo.lon), height + TOPO_SENS_DH);
                            final double tRefUp = gslcRefOrbit.xyz2t(xyzUp, gslcRefSLC).x;
                            final double tSecUp = secOrbit.xyz2t(xyzUp, secSLC).x;
                            final double vUp = -(phaseFactor * Constants.lightSpeed * (tSecUp - tRefUp));
                            nodeDphiDh[j][i] = (vUp - v) / TOPO_SENS_DH;
                            nodeH[j][i] = height;
                        }
                    }
                    if (ramp != null) {
                        // Burst labelling in azimuth time (.y of the same solve) — exact under the
                        // iso-eta tilt. phaseAt contributes nothing (the fitted per-burst model was
                        // removed); kb is what locates the seam term below. Folding it in at the
                        // nodes keeps burst seams sharp to within one interpolation cell
                        // (~GSLC_REFPHASE_SUBSAMPLE px), so the step is rendered as a ramp that
                        // wide.
                        final int kb = ramp.burstOfSod(tpRef.y);
                        v += ramp.phaseAt(xx, tpRef.y, kb);
                        if (seamSteps != null) {
                            // Measured residual seam-step profiles ride the same surface, so every
                            // remaining discontinuity is reproduced by the model and cancels.
                            v += seamSteps.cumAt(kb, xx);
                        }
                    }
                }
                node[j][i] = v;
            }
        }

        // Bilinear interpolation of the (continuous, unwrapped) phase surface to full resolution,
        // plus the per-pixel topographic correction where a DEM is active (see above).
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
                double v = v0 + (v1 - v0) * ty;

                if (nodeDphiDh != null) {
                    pix.setLocation(x0 + x + 0.5, y0 + y + 0.5);
                    gslcGeoCoding.getGeoPos(pix, geo);
                    double hPx = Double.NaN;
                    try {
                        final double e = dem.getElevation(geo);
                        if (!Double.isNaN(e) && e != demNoData) hPx = e;
                    } catch (Exception ignore) {
                        // keep NaN -> no correction for this pixel
                    }
                    if (!Double.isNaN(hPx)) {
                        final double h0 = nodeH[j0][i0] + (nodeH[j0][i0 + 1] - nodeH[j0][i0]) * tx;
                        final double h1 = nodeH[j0 + 1][i0] + (nodeH[j0 + 1][i0 + 1] - nodeH[j0 + 1][i0]) * tx;
                        final double hInterp = h0 + (h1 - h0) * ty;
                        final double s0 = nodeDphiDh[j0][i0] + (nodeDphiDh[j0][i0 + 1] - nodeDphiDh[j0][i0]) * tx;
                        final double s1 = nodeDphiDh[j0 + 1][i0] + (nodeDphiDh[j0 + 1][i0 + 1] - nodeDphiDh[j0 + 1][i0]) * tx;
                        final double dphidh = s0 + (s1 - s0) * ty;
                        v += dphidh * (hPx - hInterp);
                    }
                }
                out[y][x] = v;
            }
        }
        return out;
    }

    /** Finite-difference step (m) for the per-node topo-phase sensitivity d(phi)/dh. */
    private static final double TOPO_SENS_DH = 50.0;

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
