/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc.
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
package eu.esa.sar.sar.gpf.geometric;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.CRSGeoCodingHandler;
import eu.esa.sar.commons.OrbitStateVectors;
import eu.esa.sar.commons.SARGeocoding;
import eu.esa.sar.commons.SARUtils;
import eu.esa.sar.commons.Sentinel1Utils;
import eu.esa.sar.commons.SolidEarthTide;
import org.apache.commons.math3.util.FastMath;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.dataop.dem.ElevationModel;
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
import org.esa.snap.dem.dataio.DEMFactory;
import org.esa.snap.dem.dataio.FileElevationModel;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.OrbitStateVector;
import org.esa.snap.engine_utilities.datamodel.PosVector;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.eo.GeoUtils;
import org.esa.snap.engine_utilities.eo.LocalGeometry;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import org.esa.snap.engine_utilities.gpf.TileGeoreferencing;
import org.esa.snap.engine_utilities.gpf.TileIndex;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Geocoded Single Look Complex (GSLC) Generator.
 * <p>
 * This operator performs Geocoded Single Look Complex (GSLC) generation by
 * transforming SAR data from slant-range geometry to a map projection while
 * preserving phase information.
 * <p>
 * Key features:
 * 1. Precise Orbit Ephemerides and High-Resolution DEM usage.
 * 2. High-Fidelity Complex Resampling of the baseband i/q (e.g., truncated Sinc).
 * 3. Optional Phase Flattening (topographic/ellipsoidal carrier removal),
 *    applied after resampling at the target's geometric slant range.
 */
@OperatorMetadata(alias = "GSLC-Terrain-Correction",
        category = "Radar/Geometric/Terrain Correction",
        authors = "Luis Veci",
        version = "1.0",
        copyright = "Copyright (C) 2026 by SkyWatch Space Applications Inc.",
        description = "Geocoded Single Look Complex (GSLC) generation with phase preservation")
public class GSLCGeocodingOp extends Operator {

    @SourceProduct(alias = "source")
    Product sourceProduct;
    @TargetProduct
    Product targetProduct;

    @Parameter(description = "The list of source bands.", alias = "sourceBands",
            rasterDataNodeType = Band.class, label = "Source Bands")
    private String[] sourceBandNames = null;

    @Parameter(description = "The digital elevation model.",
            defaultValue = "Copernicus 30m Global DEM", label = "Digital Elevation Model")
    private String demName = "Copernicus 30m Global DEM";

    static final String GRID_SQUARE_COARSEST = "SQUARE_COARSEST";
    static final String GRID_NATIVE_ANISOTROPIC = "NATIVE_ANISOTROPIC";
    static final String GRID_SQUARE_FINEST = "SQUARE_FINEST";

    @Parameter(valueSet = {GRID_SQUARE_COARSEST, GRID_NATIVE_ANISOTROPIC, GRID_SQUARE_FINEST},
            defaultValue = GRID_NATIVE_ANISOTROPIC, label = "Grid Spacing",
            description = "How the output grid step is derived when no explicit pixel spacing is given. "
                    + "SQUARE_COARSEST: square cells at the coarser axis — smallest output, but "
                    + "for Sentinel-1 IW that is the azimuth spacing (~14 m) while native ground-range "
                    + "sampling is ~3.5 m, so about 4x of range detail is discarded. "
                    + "NATIVE_ANISOTROPIC (default): rectangular cells at native sampling on both axes — "
                    + "nothing discarded, ~4x the pixels of SQUARE_COARSEST. "
                    + "SQUARE_FINEST: square cells at the finer axis — nothing discarded and pixels stay "
                    + "square, but ~16x the pixels. "
                    + "Each choice yields a different grid step and therefore a different standard-grid "
                    + "lattice, so use the same setting for every product in a stack. Products made before "
                    + "this default changed used SQUARE_COARSEST; set it explicitly to extend such a stack.")
    private String gridSpacing = GRID_NATIVE_ANISOTROPIC;

    @Parameter(label = "External DEM")
    private File externalDEMFile = null;

    @Parameter(label = "External DEM No Data Value", defaultValue = "0")
    private double externalDEMNoDataValue = 0;

    @Parameter(label = "External DEM Apply EGM", defaultValue = "true")
    private Boolean externalDEMApplyEGM = true;

    @Parameter(defaultValue = ResamplingFactory.BILINEAR_INTERPOLATION_NAME, label = "DEM Resampling Method",
            valueSet = {
                    ResamplingFactory.NEAREST_NEIGHBOUR_NAME,
                    ResamplingFactory.BILINEAR_INTERPOLATION_NAME,
                    ResamplingFactory.CUBIC_CONVOLUTION_NAME,
                    ResamplingFactory.BISINC_5_POINT_INTERPOLATION_NAME,
                    ResamplingFactory.BISINC_11_POINT_INTERPOLATION_NAME,
                    ResamplingFactory.BISINC_21_POINT_INTERPOLATION_NAME,
                    ResamplingFactory.BICUBIC_INTERPOLATION_NAME,
                    DEMFactory.DELAUNAY_INTERPOLATION
            })
    private String demResamplingMethod = ResamplingFactory.BILINEAR_INTERPOLATION_NAME;

    @Parameter(defaultValue = ResamplingFactory.BISINC_5_POINT_INTERPOLATION_NAME, label = "Image Resampling Method",
            valueSet = {
                    ResamplingFactory.NEAREST_NEIGHBOUR_NAME,
                    ResamplingFactory.BILINEAR_INTERPOLATION_NAME,
                    ResamplingFactory.CUBIC_CONVOLUTION_NAME,
                    ResamplingFactory.BISINC_5_POINT_INTERPOLATION_NAME,
                    ResamplingFactory.BISINC_11_POINT_INTERPOLATION_NAME,
                    ResamplingFactory.BISINC_21_POINT_INTERPOLATION_NAME,
                    ResamplingFactory.BICUBIC_INTERPOLATION_NAME
            })
    private String imgResamplingMethod = ResamplingFactory.BISINC_5_POINT_INTERPOLATION_NAME;

    @Parameter(description = "The pixel spacing in meters. With a north (Y) spacing also set, " +
            "this is the east/longitude (X) spacing of a rectangular cell.",
            defaultValue = "0", label = "Pixel Spacing (m)")
    private double pixelSpacingInMeter = 0;

    @Parameter(description = "The pixel spacing in degrees. With a north (Y) spacing also set, " +
            "this is the east/longitude (X) spacing of a rectangular cell.",
            defaultValue = "0", label = "Pixel Spacing (deg)")
    private double pixelSpacingInDegree = 0;

    @Parameter(description = "Optional north/latitude (Y) pixel spacing in meters for a " +
            "RECTANGULAR output cell. Leave 0 for square cells. Rectangular cells preserve the " +
            "SLC's anisotropic native resolution (S1 IW: ~3.4 m ground range x ~14 m azimuth) " +
            "without oversampling one axis; because the radar axes are rotated by the heading " +
            "from the map axes, the Nyquist-adequate north step is somewhat finer than native " +
            "azimuth (S1 IW: ~7-8 m rather than 14 m).",
            defaultValue = "0", label = "Pixel Spacing North (m)")
    private double pixelSpacingInMeterY = 0;

    @Parameter(description = "Optional north/latitude (Y) pixel spacing in degrees for a " +
            "rectangular output cell. Leave 0 for square cells.",
            defaultValue = "0", label = "Pixel Spacing North (deg)")
    private double pixelSpacingInDegreeY = 0;

    @Parameter(description = "The pixel spacing oversampling percentage (0-100%)", defaultValue = "0.0", label = "Oversampling (%)")
    private double oversamplingPercent = 0.0;

    @Parameter(description = "The coordinate reference system in well known text format", defaultValue = "WGS84(DD)")
    private String mapProjection = "WGS84(DD)";

    // GSLC output is always snapped to a global standard grid (origin 0,0): any two GSLCs of
    // overlapping scenes then land on the same lat/lon lattice, so the same ground point sits at
    // the same fractional pixel (modulo an integer offset) — the property CreateStack's geocoded
    // coregistration relies on. This is always on and not user-configurable (disabling it would
    // silently break stacking), so it is not exposed as a parameter.

    @Parameter(defaultValue = "true", label = "Mask out areas with no elevation", description = "Mask the sea with no data value (faster)")
    protected boolean nodataValueAtSea = true;

    @Parameter(defaultValue = "false", label = "Save DEM as band")
    private boolean saveDEM = false;

    @Parameter(defaultValue = "false", label = "Save latitude and longitude as band")
    private boolean saveLatLon = false;

    @Parameter(defaultValue = "false", label = "Save incidence angle from ellipsoid as band")
    private boolean saveIncidenceAngleFromEllipsoid = false;

    @Parameter(defaultValue = "false", label = "Save local incidence angle as band")
    private boolean saveLocalIncidenceAngle = false;

    @Parameter(defaultValue = "false", label = "Save projected local incidence angle as band")
    private boolean saveProjectedLocalIncidenceAngle = false;

    @Parameter(defaultValue = "false", label = "Save layover shadow mask")
    private boolean saveLayoverShadowMask = false;

    /**
     * When {@code true}, the geometric carrier {@code exp(-j·4π·R(P_terrain)/λ)} is removed
     * from each output complex pixel, leaving only the local scattering coefficient σ(P).
     * That looks tidy, but it also <em>destroys</em> the InSAR signal: for a master/slave
     * pair, both legs collapse to σ(P) and their conjugate product is identically real —
     * no fringes. The default is therefore {@code false}, which keeps the natural SLC
     * carrier so that downstream {@code master · conj(slave)} carries the differential
     * phase {@code -j·4π·(R_M − R_S)/λ} (the flat-Earth + topographic + deformation +
     * atmospheric signal). Set {@code true} only for non-InSAR use cases (e.g. amplitude
     * analysis where you want a phase-clean complex band).
     */
    @Parameter(defaultValue = "false", label = "Output phase-flattened complex data (not for InSAR)",
            description = "If true, removes the SLC geometric carrier (4π·R/λ) so each " +
                    "complex pixel holds only the local scattering coefficient. This makes " +
                    "the GSLC unusable for InSAR — the (R_master − R_slave) phase difference " +
                    "is zeroed out. Leave false (default) for InSAR pipelines.")
    private boolean outputFlattened = false;

    @Parameter(description = "Restore the native TOPS azimuth (deramp) carrier in the output. " +
            "The carrier is acquisition-specific (burst timing, FM rate) and does NOT cancel " +
            "between two acquisitions, so restoring it corrupts cross-acquisition interferometry " +
            "(tens of spurious fringes per burst for a cross-platform pair). Leave false " +
            "(carrier-free output, as OPERA CSLC) for InSAR.",
            defaultValue = "false", label = "Restore TOPS azimuth carrier")
    private boolean outputAzimuthCarrier = false;

    @Parameter(defaultValue = "true", label = "Output separable phase terms",
            description = "Also write the azimuth-carrier phase and the flattening phase as bands, so "
                    + "either term can be applied or removed after the fact instead of being baked "
                    + "into the complex data. Mirrors ISCE3 geocodeSlc's carrierPhaseRaster / "
                    + "flattenPhaseRaster, and is what makes the product's phase convention "
                    + "reversible and comparable term-by-term against that reference implementation. "
                    + "Default true: InterferogramOp's exact carrier-difference subtraction (the "
                    + "deterministic removal of the cross-acquisition deramp-model mismatch) requires "
                    + "these bands on both stack legs, so InSAR-ready is the default convention.")
    private boolean outputPhaseTerms = true;

    @Parameter(label = "Reference burst boundaries",
            description = "Reference GSLC burst time table (one row per burst, "
                    + "'burstId:firstLine:firstValid:lastValid' or 'firstLine:firstValid:lastValid', "
                    + "times in seconds) used to LOCK burst-overlap selection to the reference's "
                    + "boundaries. Each acquisition otherwise splits the ~2 km TOPS burst overlap "
                    + "at its own valid-time midpoint; the two boundaries differ wherever the "
                    + "valid-line trimming differs, and in the strip between them a stack pairs "
                    + "looks from different bursts (~4 kHz apart in Doppler centroid) — inherently "
                    + "incoherent. Bursts are matched by track-anchored burst ID when available "
                    + "(handles splits framing different burst windows), else by index. Set "
                    + "automatically by CreateStack's auto-coregistration from the reference "
                    + "GSLC's gslc_burst_valid_times stamp; rarely set by hand.")
    private String refBurstValidTimes = null;

    /** Locked overlap boundaries in this acquisition's time frame (one per seam, NaN = that seam
     *  falls back to the midpoint rule), or null => all seams use the per-acquisition midpoint
     *  rule. Built in initTOPSData from {@link #refBurstValidTimes}. */
    private double[] overlapMidLock = null;

    /** Full burst-lock state ({@link #overlapMidLock} plus the pairable-burst flags and the
     *  reference's transported partition window used to mask unpairable output), or null when no
     *  reference table is supplied. Built in initTOPSData from {@link #refBurstValidTimes}. */
    private BurstLock burstLock = null;

    /** Track-anchored S1 burst IDs in burst order (annotation swathTiming/burstList/burst/burstId,
     *  the RELATIVE id — identical for the same ground burst across platforms/passes), or null
     *  when the annotation predates burst IDs (IPF < 3.40). */
    private long[] burstIds = null;

    private Band carrierPhaseBand = null;
    private Band flatteningPhaseBand = null;

    @Parameter(defaultValue = "false", label = "Save simulated phase")
    private boolean saveSimulatedPhase = false;

    @Parameter(defaultValue = "false", label = "Save simulated unwrapped phase")
    private boolean saveSimulatedUnwrappedPhase = false;

    /**
     * Constant slant-range pixel offset added to this product's geometric solution.
     * Intended for InSAR slave geocoding: when a master and slave SLC have a residual
     * misregistration (clock-bias / processor-time delta / orbit residual) beyond what
     * orbit + DEM alone can predict, run cross-correlation between the two slant-range
     * SLCs, take the median (slave − master) GCP offset, and pass that value here.
     * The geometric model then samples each ground point at {@code geomRangeIndex +
     * rangeOffsetPixels} of the source SLC, so the slave aligns with the master at the
     * sub-pixel level. Default 0.0 (no offset).
     */
    @Parameter(defaultValue = "0.0",
            label = "Slant-range offset (pixels)",
            description = "Constant offset (in slant-range pixels) added to the geometric " +
                    "sampling position. Used to apply a cross-correlation-derived (master − " +
                    "slave) bias when geocoding a slave for InSAR.")
    private double rangeOffsetPixels = 0.0;

    /**
     * Constant azimuth pixel offset added to this product's geometric solution. See
     * {@link #rangeOffsetPixels} for usage — these two parameters work as a pair to
     * apply a scalar (Δrange, Δazimuth) coregistration bias estimated from cross-
     * correlation. Default 0.0 (no offset).
     */
    @Parameter(defaultValue = "0.0",
            label = "Azimuth offset (pixels)",
            description = "Constant offset (in azimuth pixels) added to the geometric " +
                    "sampling position. Pairs with rangeOffsetPixels for scalar-bias " +
                    "coregistration of an InSAR slave.")
    private double azimuthOffsetPixels = 0.0;

    /**
     * Spatially-varying coregistration offset fields (stripmap only). Some SLC pairs —
     * first seen on the 1995 ERS-1/ERS-2 tandem VMP products — carry a data-vs-annotation
     * registration error that DRIFTS smoothly across the scene (measured ~1.7 px in range
     * across the swath, ~2 px in azimuth along the scene on the Etna tandem pair). A scalar
     * bias cannot represent it, and the retained range carrier turns the varying range
     * component into hundreds of radians of spurious smooth fringes (~450 rad per map pixel
     * of misregistration). The classical chain absorbs exactly this in its polynomial
     * CC-warp; the GSLC chain corrects it here, at the source-index lookup, while the
     * restored carrier phase stays purely geometric.
     * <p>
     * Format: "a0,a1,a2" evaluated as {@code a0 + a1*rangeIndex + a2*azimuthIndex} (source
     * pixels, absolute indices) and added to the source range index at every output pixel.
     * Empty = disabled. Ignored (with a warning) on the TOPS path.
     */
    @Parameter(defaultValue = "",
            label = "Range offset field (px)",
            description = "Affine range-offset field 'a0,a1,a2' in source pixels, " +
                    "evaluated as a0 + a1*rangeIndex + a2*azimuthIndex and added to the " +
                    "source sampling position (stripmap only). Empty = disabled.")
    private String rangeOffsetPoly = "";

    /** See {@link #rangeOffsetPoly}; same format, applied to the azimuth index. */
    @Parameter(defaultValue = "",
            label = "Azimuth offset field (px)",
            description = "Affine azimuth-offset field 'b0,b1,b2' in source pixels, " +
                    "evaluated as b0 + b1*rangeIndex + b2*azimuthIndex and added to the " +
                    "source sampling position (stripmap only). Empty = disabled.")
    private String azimuthOffsetPoly = "";

    @Parameter(defaultValue = "false",
            label = "Apply Solid Earth Tide correction",
            description = "Apply solid Earth tide displacement to the target geometry " +
                    "before slant-range computation (~10 cm peak; required for displacement-grade InSAR).")
    private boolean applySolidEarthTide = false;

    @Parameter(defaultValue = "false",
            label = "Apply tropospheric correction",
            description = "Apply the Saastamoinen dry tropospheric path delay to the slant range " +
                    "before phase computation. Uses a standard atmosphere model (no real-time meteo).")
    private boolean applyTroposphericCorrection = false;

    private MetadataElement absRoot = null;
    private volatile ElevationModel dem = null;
    private Band elevationBand = null;
    private Band simulatedPhaseBand = null;
    private Band simulatedUnwrappedPhaseBand = null;
    private double demNoDataValue = 0.0f; // no data value for DEM
    private GeoCoding targetGeoCoding = null;

    private boolean srgrFlag = false;
    private volatile boolean isElevationModelAvailable = false;

    private int sourceImageWidth = 0;
    private int sourceImageHeight = 0;
    private int targetImageWidth = 0;
    private int targetImageHeight = 0;
    private int margin = 0;

    private double wavelength = 0.0; // in m
    private double rangeSpacing = 0.0;
    private double firstLineUTC = 0.0; // in days
    private double lastLineUTC = 0.0; // in days
    private double lineTimeInterval = 0.0; // in days
    private double lineTimeIntervalSec = 0.0; // in seconds (= lineTimeInterval × 86400)
    private double sceneMidTimeMjdUtc = 0.0; // scene midpoint UTC MJD, used by SET
    private double nearEdgeSlantRange = 0.0; // in m

    /**
     * Residual Doppler centroid (Hz) for every source range column, evaluated once at
     * scene-start time from the metadata's Doppler_Centroid_Coefficients polynomial.
     * Used by the SM-path resampler to deramp the azimuth carrier
     * {@code exp(+j·2π·f_dc(r)·(eta − eta_ref))} before sinc interpolation and re-apply
     * it after — without this, sub-pixel resampling of an SLC with non-zero residual
     * Doppler centroid (Envisat ASAR: 100–500 Hz) introduces an α-dependent phase error
     * that destroys coherence at scene scale. Reference: ISCE3 geocodeSlc.cpp carrier
     * deramp/reramp; Yague-Martinez 2016 §III.
     * <p>
     * {@code null} when there are no Doppler coefficients in the metadata, in which case
     * the resampler skips the azimuth deramp (back to the previous behaviour, which is
     * fine for products with near-zero {@code f_dc} such as Capella SM).
     */
    private double[] fdcPerSourceColumn = null;

    private CoordinateReferenceSystem targetCRS;
    private double delLat = 0.0;
    private double delLon = 0.0;
    private OrbitStateVectors orbit = null;

    private AbstractMetadata.SRGRCoefficientList[] srgrConvParams = null;
    private OrbitStateVector[] orbitStateVectors = null;
    private TiePointGrid incidenceAngle = null;

    private Resampling imgResampling = null;

    private boolean nearRangeOnLeft = true;
    private boolean skipBistaticCorrection = false;
    private double bistaticCorrectionRefRange = 0.0;
    private String mission = null;

    // TOPS burst-level processing fields
    private boolean isTOPSProduct = false;
    /** Parsed {@link #rangeOffsetPoly} / {@link #azimuthOffsetPoly}; null = field disabled. */
    private double[] rangeOffsetPolyCoef = null;
    private double[] azimuthOffsetPolyCoef = null;

    /**
     * Debug-only geometry dump, enabled with {@code -Dgslc.diagGeometry=true}. Emits the
     * per-target-pixel backward-geocoding solution (DEM height, slant range, source
     * range/azimuth index, burst) as extra bands so two independently geocoded GSLCs can be
     * compared pixel-by-pixel. Off by default; adds no parameters to the operator.
     */
    private boolean DIAG_GEOMETRY = false;
    private Band diagHeightBand, diagSlantRangeBand, diagRangeIndexBand, diagAzimuthIndexBand, diagBurstBand;
    private final java.util.concurrent.atomic.AtomicInteger diagRoundTripLogged =
            new java.util.concurrent.atomic.AtomicInteger();
    private Sentinel1Utils su = null;
    private Sentinel1Utils.SubSwathInfo[] subSwath = null;
    private int subSwathIndex = 0;

    public static final String externalDEMStr = "External DEM";
    private static final String PRODUCT_SUFFIX = "_GSLC";
    private static final double lightSpeedInMetersPerSecond = 299792458.0;

    /** Layover/shadow mask bit codes (§1e). Multiple bits may combine. */
    static final int MASK_LAYOVER   = 1 << 0; // 1
    static final int MASK_SHADOW    = 1 << 1; // 2
    static final int MASK_NO_SOURCE = 1 << 2; // 4 — target pixel had no usable source/DEM
    /** Local-incidence threshold (degrees) below which the pixel is flagged as layover. */
    static final double LAYOVER_INC_THRESHOLD_DEG = 5.0;
    /** Local-incidence threshold (degrees) above which the pixel is flagged as shadow. */
    static final double SHADOW_INC_THRESHOLD_DEG  = 85.0;

    private final List<ComplexPair> complexPairs = new ArrayList<>();

    /**
     * Multiply (iIn + j*qIn) by exp(+j*phi), given precomputed cos and sin of phi.
     * Used by both the SM and TOPS paths to apply the range-carrier flattening
     * AFTER resampling, at the target's geometric slant range, when
     * {@code outputFlattened=true}.
     * <p>
     * NOTE: the flattening must never be applied per source column BEFORE the
     * sinc kernel (a former "pre-flatten" step did exactly that). A focused SLC
     * is baseband in range — the 4πR/λ phase is a per-scatterer constant, not a
     * sample-grid carrier — so a per-column exp(+j·4πR(x)/λ) ramp aliases to
     * (4π·Δr/λ) mod 2π per pixel (−0.4989 cycles/pixel for ERS, ≈ Nyquist) and
     * destroys sub-pixel interpolation. Measured on real ERS-1 SLC data: 5-pt
     * sinc at mu=0.5 achieves γ=0.997 on raw baseband i/q vs γ=0.093
     * pre-flattened. Guarded by {@code GSLCComplexResamplingFidelityTest}.
     */
    static void multiplyByExpJPhi(final double iIn, final double qIn,
                                  final double cosPhi, final double sinPhi,
                                  final double[] out2) {
        out2[0] = iIn * cosPhi - qIn * sinPhi;
        out2[1] = qIn * cosPhi + iIn * sinPhi;
    }

    /**
     * Multiply (iIn + j*qIn) by exp(-j*phi), given precomputed cos and sin of phi.
     * Inverse of {@link #multiplyByExpJPhi}; retained to pin the sign convention
     * of the flatten/restore pair (see GSLCInSarGradeTest §1a).
     */
    static void multiplyByExpMinusJPhi(final double iIn, final double qIn,
                                       final double cosPhi, final double sinPhi,
                                       final double[] out2) {
        out2[0] = iIn * cosPhi + qIn * sinPhi;
        out2[1] = qIn * cosPhi - iIn * sinPhi;
    }

    /**
     * Evaluate the analytical TOPS deramp+demod phase at a fractional source position
     * (§1c). Inputs: the SubSwath, burst index, and fractional source pixel coords.
     * <p>
     * The phase model is (per ESA S1 IPF / DerampDemod literature):
     * <pre>
     *   phi(x, y) = -pi * kt(x) * (ta(y) - tr(x))^2  -  2 pi * fdc(x) * ta(y)
     * </pre>
     * where {@code ta = (y - firstLineInBurst) * azimuthTimeInterval}, and
     * {@code kt, tr, fdc} are the (per-burst) Doppler rate, reference time, and
     * Doppler centroid arrays sampled at integer range columns; these are
     * linearly interpolated at fractional x.
     * <p>
     * Computing the phase analytically at the resampled position is more accurate
     * than bisinc-interpolating the wrapped phase grid: the grid wraps modulo 2pi
     * across many cycles per pixel at burst edges, where the unwrapped quadratic
     * can grow to hundreds of radians.
     */
    /**
     * Classify a pixel as layover ({@link #MASK_LAYOVER}), shadow
     * ({@link #MASK_SHADOW}), or valid (0) from its local incidence angle.
     * Local incidence < {@value #LAYOVER_INC_THRESHOLD_DEG} deg means the
     * surface slopes toward the sensor more steeply than the radar look angle
     * — classic layover. Local incidence > {@value #SHADOW_INC_THRESHOLD_DEG}
     * deg means the slope is hidden from the sensor — radar shadow. A
     * non-valid incidence angle ({@link SARGeocoding#NonValidIncidenceAngle})
     * is treated as {@link #MASK_NO_SOURCE}.
     */
    static int classifyLayoverShadow(final double localIncidenceDeg) {
        if (Double.isNaN(localIncidenceDeg)
                || localIncidenceDeg == SARGeocoding.NonValidIncidenceAngle) {
            return MASK_NO_SOURCE;
        }
        int mask = 0;
        if (localIncidenceDeg < LAYOVER_INC_THRESHOLD_DEG) mask |= MASK_LAYOVER;
        if (localIncidenceDeg > SHADOW_INC_THRESHOLD_DEG)  mask |= MASK_SHADOW;
        return mask;
    }

    static double computeDerampDemodPhaseAt(final Sentinel1Utils.SubSwathInfo[] subSwath,
                                            final int subSwathIndex, final int burstIndex,
                                            final double xFrac, final double yFrac) {
        final int s = subSwathIndex - 1;
        final Sentinel1Utils.SubSwathInfo ss = subSwath[s];
        final int firstLineInBurst = burstIndex * ss.linesPerBurst;
        final double ta = (yFrac - firstLineInBurst) * ss.azimuthTimeInterval;

        final int n = ss.numOfSamples;
        final int x0 = (int) Math.floor(xFrac);
        final int x0c = Math.max(0, Math.min(n - 1, x0));
        final int x1c = Math.max(0, Math.min(n - 1, x0 + 1));
        final double fx = xFrac - x0;

        final double kt = ss.dopplerRate[burstIndex][x0c]
                + fx * (ss.dopplerRate[burstIndex][x1c] - ss.dopplerRate[burstIndex][x0c]);
        final double tr = ss.referenceTime[burstIndex][x0c]
                + fx * (ss.referenceTime[burstIndex][x1c] - ss.referenceTime[burstIndex][x0c]);
        final double fdc = ss.dopplerCentroid[burstIndex][x0c]
                + fx * (ss.dopplerCentroid[burstIndex][x1c] - ss.dopplerCentroid[burstIndex][x0c]);

        final double dt = ta - tr;
        return -Math.PI * kt * dt * dt - Constants.TWO_PI * fdc * ta;
    }

    private static class ComplexPair {
        Band srcI;
        Band srcQ;
        Band tgtI;
        Band tgtQ;
    }

    @Override
    public void initialize() throws OperatorException {
        try {
            DIAG_GEOMETRY = Boolean.getBoolean("gslc.diagGeometry");

            final InputProductValidator validator = new InputProductValidator(sourceProduct);
            validator.checkIfSARProduct();
            validator.checkIfMapProjected(false);

            if (validator.isTOPSARProduct()) {
                if (validator.isDebursted()) {
                    throw new OperatorException(
                            "Debursted TOPS SLC products are not supported for GSLC geocoding.\n" +
                            "Please use the pre-deburst split product (single subswath with burst structure).\n" +
                            "Processing chain: Apply-Orbit-File -> TOPSAR-Split -> GSLC-Terrain-Correction");
                }
                isTOPSProduct = true;
            }

            getSourceImageDimension();
            getMetadata();

            if (isTOPSProduct) {
                initTOPSData();
            } else if (refBurstValidTimes != null && !refBurstValidTimes.trim().isEmpty()) {
                SystemUtils.LOG.warning("GSLC: refBurstValidTimes is set but this is not a TOPS "
                        + "product — the burst-boundary lock applies to TOPS only and is ignored.");
            }

            imgResampling = ResamplingFactory.createResampling(imgResamplingMethod);
            if (imgResampling == null) {
                throw new OperatorException("Resampling method " + imgResamplingMethod + " is invalid");
            }

            createTargetProduct();
            computeSensorPositionsAndVelocities();
            updateTargetProductMetadata();

            if (!demName.contains(externalDEMStr)) {
                DEMFactory.checkIfDEMInstalled(demName);
            }

            if (demName.contains(externalDEMStr) && externalDEMFile == null) {
                throw new OperatorException("External DEM file is not specified. ");
            }

            DEMFactory.validateDEM(demName, sourceProduct);

            margin = getMargin();

            if (applySolidEarthTide) {
                SystemUtils.LOG.info("GSLC: applySolidEarthTide=true — IERS 2010 step-1 body " +
                        "tide displacement (Sun + Moon, degree-2 Love numbers) will be applied " +
                        "to each geocoded ground point before slant-range computation.");
            }
            if (applyTroposphericCorrection) {
                SystemUtils.LOG.info("GSLC: applyTroposphericCorrection=true — using Saastamoinen " +
                        "dry zenith delay (no real-time meteorology).");
            }

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    @Override
    public void dispose() throws OperatorException {
        if (dem != null) {
            dem.dispose();
        }
    }

    /**
     * Written by {@code S1ETADCorrectionOp}; a literal here because {@code sar-op-sentinel1} is a
     * test-scope dependency of this module and so is not on the main compile classpath.
     */
    static final String ETAD_AZIMUTH_APPLIED = "etad_azimuth_applied";

    /**
     * Reference range for the bistatic azimuth residual, or 0 to suppress the residual entirely.
     * <p>
     * The IPF applies a bulk bistatic correction using a reference range and records that in
     * {@code bistatic_correction_applied}; this operator then applies the range-dependent residual
     * (Section 4.7.3 of UZH-S1-GC-AD v1.12). But ETAD's azimuth layers contain the same term, so once
     * ETAD has resampled the pixels with an azimuth correction the residual must not be applied
     * again.
     * <p>
     * These are demonstrably the same quantity, not merely similar. Measured on a real S1B IW
     * product, ETAD's {@code bistaticCorrectionAz} varies by -0.1700 ms across the sub-swath while
     * this operator's residual, {@code (rFar - rNear)/c}, spans 0.1687 ms — a 0.8% match. The signs
     * differ because ETAD states the correction to remove from the measurement whereas this adds it
     * to the predicted time. At ~0.083 azimuth lines the term is ~1.15 m, or ~0.08 output pixels at
     * the default posting: small, but range-dependent-only and therefore common-mode, so it degrades
     * precisely the absolute geolocation ETAD is applied to improve.
     * <p>
     * ETAD's is a measured correction and this one a geometric model, so ETAD's takes precedence.
     *
     * @param skipBistatic       {@code bistatic_correction_applied == 1}, i.e. the IPF applied the bulk term
     * @param mission            mission name; the residual is a Sentinel-1 IPF behaviour only
     * @param slantRangeToFirstPixel near-range, the residual's reference
     * @param etadAzimuthApplied whether ETAD already resampled an azimuth correction into the pixels
     * @return the reference range to use, or 0.0 for "apply no residual"
     */
    static double resolveBistaticCorrectionRefRange(final boolean skipBistatic, final String mission,
                                                    final double slantRangeToFirstPixel,
                                                    final boolean etadAzimuthApplied) {
        if (!skipBistatic || mission == null || !mission.startsWith("SENTINEL-1")) {
            return 0.0;
        }
        if (etadAzimuthApplied) {
            SystemUtils.LOG.info("GSLC: suppressing the bistatic azimuth residual - the ETAD azimuth "
                    + "correction already applied to this product's pixels contains the same "
                    + "range-dependent bistatic term.");
            return 0.0;
        }
        return slantRangeToFirstPixel;
    }

    private void getMetadata() throws Exception {
        absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);

        mission = RangeDopplerGeocodingOp.getMissionType(absRoot);
        skipBistaticCorrection = absRoot.getAttributeInt(AbstractMetadata.bistatic_correction_applied, 0) == 1;
        bistaticCorrectionRefRange = resolveBistaticCorrectionRefRange(
                skipBistaticCorrection, mission,
                AbstractMetadata.getAttributeDouble(absRoot, AbstractMetadata.slant_range_to_first_pixel),
                absRoot.getAttributeInt(ETAD_AZIMUTH_APPLIED, 0) == 1);
        srgrFlag = AbstractMetadata.getAttributeBoolean(absRoot, AbstractMetadata.srgr_flag);

        // SLC products are always in slant range geometry regardless of what the metadata says.
        // The srgr_flag can be incorrectly set to true in some product readers.
        final String sampleType = absRoot.getAttributeString(AbstractMetadata.SAMPLE_TYPE);
        if (sampleType != null && sampleType.contains("COMPLEX")) {
            if (srgrFlag) {
                SystemUtils.LOG.warning("GSLC: Overriding srgr_flag to false for SLC product");
                srgrFlag = false;
            }
        }
        
        // Robust wavelength calculation
        double freq = AbstractMetadata.getAttributeDouble(absRoot, AbstractMetadata.radar_frequency);
        if (freq < 1.0e8) { // Assume MHz if less than 100 MHz
             freq *= 1.0e6;
        }
        wavelength = lightSpeedInMetersPerSecond / freq;

        rangeSpacing = AbstractMetadata.getAttributeDouble(absRoot, AbstractMetadata.range_spacing);
        if (rangeSpacing <= 0.0) {
            throw new OperatorException("Invalid input for range pixel spacing: " + rangeSpacing);
        }
        // Check if rangeSpacing is in seconds (e.g. < 0.001)
        if (rangeSpacing < 0.001) {
            rangeSpacing *= (lightSpeedInMetersPerSecond / 2.0);
            SystemUtils.LOG.info("GSLC: Converted rangeSpacing from seconds to meters: " + rangeSpacing);
        }

        firstLineUTC = AbstractMetadata.parseUTC(absRoot.getAttributeString(AbstractMetadata.first_line_time)).getMJD(); // in days
        lastLineUTC = AbstractMetadata.parseUTC(absRoot.getAttributeString(AbstractMetadata.last_line_time)).getMJD(); // in days
        lineTimeInterval = (lastLineUTC - firstLineUTC) / (sourceImageHeight - 1); // in days
        lineTimeIntervalSec = lineTimeInterval * Constants.secondsInDay;
        sceneMidTimeMjdUtc = 0.5 * (firstLineUTC + lastLineUTC); // for SET (sub-second accuracy is enough)
        if (lineTimeInterval == 0.0) {
            throw new OperatorException("Invalid input for Line Time Interval: " + lineTimeInterval);
        }

        orbitStateVectors = AbstractMetadata.getOrbitStateVectors(absRoot);
        if (orbitStateVectors == null || orbitStateVectors.length == 0) {
            throw new OperatorException("Invalid Orbit State Vectors");
        }

        // Always read near-edge slant range (needed for SLC range index and TOPS processing)
        nearEdgeSlantRange = AbstractMetadata.getAttributeDouble(absRoot, AbstractMetadata.slant_range_to_first_pixel);
        if (nearEdgeSlantRange < 10000.0) {
            nearEdgeSlantRange *= (lightSpeedInMetersPerSecond / 2.0);
            SystemUtils.LOG.info("GSLC: Converted nearEdgeSlantRange from seconds to meters: " + nearEdgeSlantRange);
        }

        if (srgrFlag) {
            srgrConvParams = AbstractMetadata.getSRGRCoefficients(absRoot);
            if (srgrConvParams == null || srgrConvParams.length == 0) {
                throw new OperatorException("Invalid SRGR Coefficients");
            }
        }

        incidenceAngle = OperatorUtils.getIncidenceAngle(sourceProduct);
        nearRangeOnLeft = SARGeocoding.isNearRangeOnLeft(incidenceAngle, sourceImageWidth);

        // Apply scalar coregistration bias (typically a cross-correlation-derived
        // slave-vs-master residual) by shifting the metadata references that the
        // geometric model bases its rangeIndex / azimuthIndex on. For a positive
        // offset O, the geometric pixel for any ground point becomes
        // {@code originalIndex + O}, i.e. the operator samples the source SLC at a
        // higher pixel column / row by that constant amount.
        if (rangeOffsetPixels != 0.0) {
            final double rangeShift = rangeOffsetPixels * rangeSpacing;
            nearEdgeSlantRange -= rangeShift;
            SystemUtils.LOG.info(String.format(
                    "GSLC: rangeOffsetPixels=%+.6f → nearEdgeSlantRange shifted by %+.4f m",
                    rangeOffsetPixels, -rangeShift));
        }
        if (azimuthOffsetPixels != 0.0) {
            final double azShiftDays = azimuthOffsetPixels * lineTimeInterval;
            firstLineUTC -= azShiftDays;
            lastLineUTC  -= azShiftDays;
            sceneMidTimeMjdUtc = 0.5 * (firstLineUTC + lastLineUTC);
            SystemUtils.LOG.info(String.format(
                    "GSLC: azimuthOffsetPixels=%+.6f → firstLineUTC shifted by %+.4f s",
                    azimuthOffsetPixels, -azShiftDays * 86400.0));
        }

        // Spatially-varying offset fields (see the rangeOffsetPoly parameter doc). Applied
        // at the source-index lookup in the stripmap tile loop; the restored carrier phase
        // stays geometric, so this corrects data-vs-annotation registration drift without
        // touching the InSAR-relevant phase model.
        rangeOffsetPolyCoef = parseOffsetPoly(rangeOffsetPoly, "rangeOffsetPoly");
        azimuthOffsetPolyCoef = parseOffsetPoly(azimuthOffsetPoly, "azimuthOffsetPoly");
        if (rangeOffsetPolyCoef != null || azimuthOffsetPolyCoef != null) {
            if (isTOPSProduct) {
                SystemUtils.LOG.warning("GSLC: rangeOffsetPoly/azimuthOffsetPoly are stripmap-only " +
                        "and are IGNORED on the TOPS path (TOPS secondaries use the ESD-based bias).");
                rangeOffsetPolyCoef = null;
                azimuthOffsetPolyCoef = null;
            } else {
                final double rgFar = sourceImageWidth - 1.0, azFar = sourceImageHeight - 1.0;
                SystemUtils.LOG.info(String.format(
                        "GSLC: offset fields active — range [%+.3f .. %+.3f] px, azimuth " +
                                "[%+.3f .. %+.3f] px across the scene (corner extremes).",
                        offsetPolyExtreme(rangeOffsetPolyCoef, rgFar, azFar, false),
                        offsetPolyExtreme(rangeOffsetPolyCoef, rgFar, azFar, true),
                        offsetPolyExtreme(azimuthOffsetPolyCoef, rgFar, azFar, false),
                        offsetPolyExtreme(azimuthOffsetPolyCoef, rgFar, azFar, true)));
                // The source rectangle is derived from UNcorrected corner solves; widen its
                // kernel margin so field-shifted lookups near tile edges stay inside it.
                final double maxAbsField = Math.max(
                        Math.max(Math.abs(offsetPolyExtreme(rangeOffsetPolyCoef, rgFar, azFar, false)),
                                 Math.abs(offsetPolyExtreme(rangeOffsetPolyCoef, rgFar, azFar, true))),
                        Math.max(Math.abs(offsetPolyExtreme(azimuthOffsetPolyCoef, rgFar, azFar, false)),
                                 Math.abs(offsetPolyExtreme(azimuthOffsetPolyCoef, rgFar, azFar, true))));
                margin += (int) Math.ceil(maxAbsField);
            }
        }

        // Build the residual Doppler centroid profile f_dc(r) per source range column.
        // For sub-pixel SLC resampling to preserve phase, the azimuth carrier
        // exp(+j·2π·f_dc(r)·(eta − eta_ref)) must be deramped before the sinc kernel
        // and re-applied at the target position; otherwise the kernel sees a
        // band-shifted signal and produces α-dependent phase errors that destroy
        // coherence at scene scale (this is the canonical "subset OK / full scene
        // noise" failure on Envisat ASAR). See research note in
        // sources/research_opera_isce3_gslc.md.
        fdcPerSourceColumn = buildFdcPerSourceColumn();
        if (!isTOPSProduct) {
            // Data-driven arbitration (SM path only — TOPS ignores this table): the Madsen
            // lag-1 estimate measures the physical spectrum centroid in the raster frame,
            // immune to annotation conventions. It replaces the annotation table when the two
            // disagree, and substitutes for it entirely when the annotation is missing or was
            // rejected by the PRF sanity gate (legacy-VMP ERS annotation quality; the CEOS
            // Doppler-units mismatch). Costs ~1500 row reads, negligible next to geocoding.
            final double prfForFdc = absRoot.getAttributeDouble(
                    AbstractMetadata.pulse_repetition_frequency, 0.0);
            if (prfForFdc > 0.0) {
                final double[] dataFdc = estimateFdcFromData(sourceProduct, prfForFdc);
                final double[] chosen = chooseFdcTable(fdcPerSourceColumn, dataFdc, prfForFdc);
                if (chosen != fdcPerSourceColumn) {
                    fdcPerSourceColumn = chosen;
                    if (chosen != null) {
                        SystemUtils.LOG.info(String.format(
                                "GSLC: azimuth deramp using the DATA-MEASURED Doppler centroid " +
                                        "(lag-1 ACF): %.1f Hz (near) … %.1f Hz (far).",
                                chosen[0], chosen[chosen.length - 1]));
                    }
                } else if (dataFdc != null && fdcPerSourceColumn != null) {
                    SystemUtils.LOG.info("GSLC: annotation Doppler centroid confirmed by the " +
                            "data-measured centroid — annotation table kept.");
                }
            }
        }
    }

    /**
     * Parse an offset-field parameter (see {@link #rangeOffsetPoly}): 3, 6 or 10 comma-
     * separated finite doubles = polynomial degree 1, 2 or 3 in the canonical monomial order
     * (constant first, degree-major: 1, rg, az, rg², rg·az, az², rg³, rg²·az, rg·az², az³ —
     * kept in sync with {@code CreateStackOp.offsetFieldTerms}). Blank/null/all-zero disables
     * the field (returns null).
     */
    static double[] parseOffsetPoly(final String value, final String paramName) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        final String[] parts = value.trim().split(",");
        if (parts.length != 3 && parts.length != 6 && parts.length != 10) {
            throw new OperatorException(paramName + " must have 3, 6 or 10 comma-separated " +
                    "values (polynomial degree 1, 2 or 3); got " + parts.length + " in '" + value + "'");
        }
        final double[] c = new double[parts.length];
        boolean anyNonZero = false;
        for (int i = 0; i < parts.length; i++) {
            try {
                c[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException e) {
                throw new OperatorException(paramName + " term " + i + " is not a number: '" +
                        parts[i].trim() + "'");
            }
            if (!Double.isFinite(c[i])) {
                throw new OperatorException(paramName + " term " + i + " is not finite.");
            }
            anyNonZero |= c[i] != 0.0;
        }
        return anyNonZero ? c : null;
    }

    /**
     * Offset field value in source pixels; 0 for a null field. Canonical monomial order as in
     * {@link #parseOffsetPoly}; the degree is implied by the coefficient count (3/6/10).
     */
    static double evalOffsetPoly(final double[] c, final double rg, final double az) {
        if (c == null) {
            return 0.0;
        }
        double v = c[0] + c[1] * rg + c[2] * az;
        if (c.length >= 6) {
            v += c[3] * rg * rg + c[4] * rg * az + c[5] * az * az;
        }
        if (c.length >= 10) {
            v += c[6] * rg * rg * rg + c[7] * rg * rg * az + c[8] * rg * az * az + c[9] * az * az * az;
        }
        return v;
    }

    /** Min or max of a polynomial field over a 7x7 scene sample grid (for logging/margins). */
    private static double offsetPolyExtreme(final double[] c, final double rgFar, final double azFar,
                                            final boolean max) {
        double ext = max ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        for (int a = 0; a <= 6; a++) {
            for (int b = 0; b <= 6; b++) {
                final double v = evalOffsetPoly(c, a * rgFar / 6.0, b * azFar / 6.0);
                ext = max ? Math.max(ext, v) : Math.min(ext, v);
            }
        }
        return ext;
    }

    /**
     * Apply the affine offset fields to a solved source position. The field is evaluated at
     * the uncorrected geometric indices (drift slopes are ~1e-4 px/px, so evaluating at the
     * corrected position would change nothing measurable) and shifts only WHERE the source
     * SLC is read — {@code slantRange}, and with it the restored carrier phase, remains the
     * geometric value for the target ground point.
     */
    private void applyOffsetField(final PositionData data) {
        if (rangeOffsetPolyCoef == null && azimuthOffsetPolyCoef == null) {
            return;
        }
        final double rg = data.rangeIndex;
        final double az = data.azimuthIndex;
        data.rangeIndex += evalOffsetPoly(rangeOffsetPolyCoef, rg, az);
        data.azimuthIndex += evalOffsetPoly(azimuthOffsetPolyCoef, rg, az);
    }

    /**
     * Evaluate the residual Doppler centroid (Hz) at every source range column from
     * the metadata's {@code Doppler_Centroid_Coefficients} polynomial. Returns
     * {@code null} if the metadata has no Doppler coefficients — the resampler
     * then skips the azimuth deramp entirely.
     */
    private double[] buildFdcPerSourceColumn() {
        final AbstractMetadata.DopplerCentroidCoefficientList[] dopList;
        try {
            dopList = AbstractMetadata.getDopplerCentroidCoefficients(absRoot);
        } catch (Throwable t) {
            SystemUtils.LOG.warning("GSLC: could not read Doppler_Centroid_Coefficients (" +
                    t.getMessage() + ") — azimuth deramp disabled.");
            return null;
        }
        if (dopList == null || dopList.length == 0 || dopList[0].coefficients == null
                || dopList[0].coefficients.length == 0) {
            SystemUtils.LOG.info("GSLC: no Doppler_Centroid_Coefficients in metadata — " +
                    "azimuth deramp disabled (assuming zero residual f_dc).");
            return null;
        }
        // Use the first entry (scene-start). For Envisat ASAR IMS this is the only
        // entry; for products with multiple entries the time-variation along azimuth
        // is small enough (< 1 Hz typically) that one entry is sufficient.
        final AbstractMetadata.DopplerCentroidCoefficientList dop = dopList[0];
        final double[] coeffs = dop.coefficients;
        // SNAP stores slant_range_time in NANOSECONDS (see DemodulateOp:306 — the canonical
        // reference for stripmap Doppler polynomial evaluation: `* Constants.oneBillionth`).
        // The polynomial coefficients are in Hz / s^j, so the polynomial argument must
        // be the (slant-range-time − reference) difference in SECONDS.
        //
        // Reference-time convention differs by mission family: ENVISAT ASAR stores an ABSOLUTE
        // two-way reference time (nonzero); the ERS CEOS reader stores 0.0, and for ERS the
        // polynomial is referenced to the FIRST RANGE SAMPLE. Evaluating ERS terms against
        // absolute time gave |f_dc| ≈ 49 kHz (29x PRF, pure garbage); referenced to the first
        // sample the same terms reproduce the DATA-MEASURED centroid (Madsen one-lag estimator,
        // ERS-1 orbit 21159: measured −337…−294 Hz across the swath, predicted −334…−284,
        // RMS 10.9 Hz). So: stored reference 0 ⇒ anchor at the first pixel's slant-range time.
        final double twoOverC = 2.0 / lightSpeedInMetersPerSecond;
        final double storedRefSrt = dop.slant_range_time * Constants.oneBillionth; // ns → s
        final double refSrt = storedRefSrt != 0.0 ? storedRefSrt : nearEdgeSlantRange * twoOverC;

        final double[] fdc = new double[sourceImageWidth];
        double maxAbsFdc = 0.0;
        for (int col = 0; col < sourceImageWidth; col++) {
            final int srcCol = nearRangeOnLeft ? col : (sourceImageWidth - 1 - col);
            final double slantRange = nearEdgeSlantRange + srcCol * rangeSpacing;
            final double srt = slantRange * twoOverC; // two-way slant-range time, seconds
            final double dt = srt - refSrt;
            double f = 0.0;
            double dtPow = 1.0;
            for (final double c : coeffs) {
                f += c * dtPow;
                dtPow *= dt;
            }
            fdc[col] = f;
            if (Math.abs(f) > maxAbsFdc) maxAbsFdc = Math.abs(f);
        }
        // Sanity gate: a Doppler centroid beyond ~1.5x PRF is not physics, it is a metadata
        // convention mismatch (first hit: ERS CEOS, whose reader stores the raw scene-record
        // cross-track Doppler terms with reference time 0, while this evaluation assumes the
        // ENVISAT convention — measured 49 kHz against a 1680 Hz PRF, ~29x aliased). Deramping
        // by such a table would destroy the resampled spectrum; skipping the deramp merely
        // costs some interpolation fidelity. Disable and say so, loudly.
        final double prf = absRoot.getAttributeDouble(AbstractMetadata.pulse_repetition_frequency, 0.0);
        if (prf > 0.0 && maxAbsFdc > 1.5 * prf) {
            SystemUtils.LOG.warning(String.format(
                    "GSLC: Doppler-centroid table evaluates to |f_dc| up to %.1f Hz against a PRF of "
                            + "%.1f Hz — physically impossible, so the metadata's Doppler convention "
                            + "does not match this evaluation (known for ERS CEOS products). "
                            + "Azimuth deramp DISABLED for safety; resampling proceeds without it.",
                    maxAbsFdc, prf));
            return null;
        }
        SystemUtils.LOG.info(String.format(
                "GSLC: residual Doppler centroid built from %d coefficient(s); " +
                        "|f_dc| range across swath = %.2f Hz (max). " +
                        "Used by the stripmap (SM) resampling path only — TOPS products are " +
                        "deramped per burst instead and ignore this table.",
                coeffs.length, maxAbsFdc));
        return fdc;
    }

    /**
     * Madsen lag-1 estimator: per-column Doppler centroid measured FROM THE DATA as the phase
     * of the azimuth lag-1 autocorrelation, {@code f_dc(col) = PRF/(2π)·angle(Σ s(az+1,col)·
     * conj(s(az,col)))}, accumulated over three 512-row blocks spread across the scene and
     * smoothed by {@link #fitFdcProfile}. Works in the raster frame directly, so it is immune
     * to every annotation convention (units, reference time, mirroring) — the reason it can
     * arbitrate the metadata polynomial on archive products. The lag-1 phase is the PRINCIPAL
     * value (f_dc modulo PRF), which is exactly what baseband-centering the interpolation
     * kernel needs. Returns null when no complex band pair exists or reading fails.
     * Ground truth pinned on the 1995 ERS tandem pair: ERS-1 −341…−296 Hz, ERS-2 −556…−491 Hz
     * (python lag-1 ACF, scatter 1.2 Hz around a range quadratic).
     */
    static double[] estimateFdcFromData(final Product slc, final double prf) {
        try {
            Band iBand = null, qBand = null;
            for (final Band b : slc.getBands()) {
                final String unit = b.getUnit();
                if (unit == null) continue;
                if (iBand == null && unit.equals(Unit.REAL)) iBand = b;
                else if (iBand != null && qBand == null && unit.equals(Unit.IMAGINARY)) { qBand = b; break; }
            }
            if (iBand == null || qBand == null) {
                return null;
            }
            final int w = slc.getSceneRasterWidth();
            final int h = slc.getSceneRasterHeight();
            final int rows = 513;
            if (h < 3 * rows || w < 64) {
                return null;
            }
            final double[] accRe = new double[w];
            final double[] accIm = new double[w];
            final float[] i0 = new float[w], q0 = new float[w], i1 = new float[w], q1 = new float[w];
            for (final int y0 : new int[]{h / 6, h / 2, 5 * h / 6}) {
                iBand.readPixels(0, y0, w, 1, i0, com.bc.ceres.core.ProgressMonitor.NULL);
                qBand.readPixels(0, y0, w, 1, q0, com.bc.ceres.core.ProgressMonitor.NULL);
                for (int y = y0 + 1; y < y0 + rows; y++) {
                    iBand.readPixels(0, y, w, 1, i1, com.bc.ceres.core.ProgressMonitor.NULL);
                    qBand.readPixels(0, y, w, 1, q1, com.bc.ceres.core.ProgressMonitor.NULL);
                    for (int c = 0; c < w; c++) {
                        // s(y,c)·conj(s(y-1,c))
                        accRe[c] += i1[c] * (double) i0[c] + q1[c] * (double) q0[c];
                        accIm[c] += q1[c] * (double) i0[c] - i1[c] * (double) q0[c];
                    }
                    System.arraycopy(i1, 0, i0, 0, w);
                    System.arraycopy(q1, 0, q0, 0, w);
                }
            }
            return fitFdcProfile(accRe, accIm, w, prf);
        } catch (Throwable t) {
            SystemUtils.LOG.warning("GSLC: data-driven Doppler-centroid estimation failed: "
                    + t.getMessage());
            return null;
        }
    }

    /**
     * Turn per-column lag-1 ACF accumulations into a smooth per-column f_dc profile: 16 range
     * bins (phase of the binned complex sum → Hz), weighted quadratic LS in normalized column,
     * evaluated per column. Quadratic because the physical centroid varies smoothly with range
     * (measured scatter around a quadratic on real ERS: 1.2 Hz); fitting rather than using raw
     * per-column angles keeps the deramp free of speckle-driven column-to-column jitter.
     * Returns null when fewer than 8 bins carry signal.
     */
    static double[] fitFdcProfile(final double[] accRe, final double[] accIm, final int width,
                                  final double prf) {
        final int NB = 16;
        final double[] bx = new double[NB];
        final double[] bf = new double[NB];
        final double[] bw = new double[NB];
        int usable = 0;
        final int per = width / NB;
        if (per < 4) {
            return null;
        }
        for (int b = 0; b < NB; b++) {
            double re = 0, im = 0;
            final int c0 = b * per, c1 = (b == NB - 1) ? width : (b + 1) * per;
            for (int c = c0; c < c1; c++) {
                re += accRe[c];
                im += accIm[c];
            }
            final double mag = Math.hypot(re, im);
            if (mag <= 0) {
                continue;
            }
            bx[usable] = (0.5 * (c0 + c1)) / width;
            bf[usable] = Math.atan2(im, re) * prf / (2.0 * Math.PI);
            bw[usable] = mag;
            usable++;
        }
        if (usable < 8) {
            return null;
        }
        // weighted quadratic LS via normal equations (3x3)
        final double[][] ata = new double[3][3];
        final double[] atb = new double[3];
        for (int k = 0; k < usable; k++) {
            final double[] row = {1.0, bx[k], bx[k] * bx[k]};
            for (int a = 0; a < 3; a++) {
                for (int b = 0; b < 3; b++) ata[a][b] += bw[k] * row[a] * row[b];
                atb[a] += bw[k] * row[a] * bf[k];
            }
        }
        final double[] c = solve3x3(ata, atb);
        if (c == null) {
            return null;
        }
        final double[] fdc = new double[width];
        for (int col = 0; col < width; col++) {
            final double x = col / (double) width;
            fdc[col] = c[0] + c[1] * x + c[2] * x * x;
        }
        return fdc;
    }

    private static double[] solve3x3(final double[][] a, final double[] b) {
        final double[][] m = {{a[0][0], a[0][1], a[0][2], b[0]},
                              {a[1][0], a[1][1], a[1][2], b[1]},
                              {a[2][0], a[2][1], a[2][2], b[2]}};
        for (int col = 0; col < 3; col++) {
            int piv = col;
            for (int r = col + 1; r < 3; r++) {
                if (Math.abs(m[r][col]) > Math.abs(m[piv][col])) piv = r;
            }
            final double[] tmp = m[col]; m[col] = m[piv]; m[piv] = tmp;
            if (Math.abs(m[col][col]) < 1e-14) return null;
            final double d = m[col][col];
            for (int j = col; j < 4; j++) m[col][j] /= d;
            for (int r = 0; r < 3; r++) {
                if (r == col) continue;
                final double f = m[r][col];
                for (int j = col; j < 4; j++) m[r][j] -= f * m[col][j];
            }
        }
        return new double[]{m[0][3], m[1][3], m[2][3]};
    }

    /**
     * Arbitrate the annotation-derived f_dc table against the data-driven estimate: the
     * annotation is kept when the two agree (median |difference| within max(25 Hz, 1.5% of
     * PRF)); otherwise the DATA wins — on archive products (legacy-VMP ERS, cross-facility
     * ASAR) the annotation quality is exactly what cannot be trusted, while the lag-1
     * estimator measures the physical spectrum centroid directly. Either side being null
     * yields the other.
     */
    static double[] chooseFdcTable(final double[] annotationFdc, final double[] dataFdc,
                                   final double prf) {
        if (annotationFdc == null) return dataFdc;
        if (dataFdc == null) return annotationFdc;
        final int n = Math.min(annotationFdc.length, dataFdc.length);
        final double[] diffs = new double[n];
        for (int c = 0; c < n; c++) {
            diffs[c] = Math.abs(annotationFdc[c] - dataFdc[c]);
        }
        java.util.Arrays.sort(diffs);
        final double medianDiff = diffs[n / 2];
        final double gate = Math.max(25.0, 0.015 * prf);
        if (medianDiff <= gate) {
            return annotationFdc;
        }
        SystemUtils.LOG.warning(String.format(
                "GSLC: annotation Doppler centroid disagrees with the data-measured centroid " +
                        "(median |diff| %.1f Hz, gate %.1f Hz) — using the DATA estimate " +
                        "(lag-1 ACF). Archive annotation quality issue (known for legacy-VMP " +
                        "ERS products).", medianDiff, gate));
        return dataFdc;
    }

    private void initTOPSData() throws Exception {
        su = new Sentinel1Utils(sourceProduct);
        subSwath = su.getSubSwath();
        su.computeDopplerRate();
        su.computeReferenceTime();

        final String[] subSwathNames = su.getSubSwathNames();
        if (subSwathNames.length != 1) {
            throw new OperatorException(
                    "GSLC for TOPS requires a split product with a single subswath. " +
                    "Please apply TOPSAR-Split first.");
        }
        subSwathIndex = 1; // always 1 for split product

        SystemUtils.LOG.info("GSLC: TOPS mode enabled for " + subSwathNames[0] +
                ", bursts=" + subSwath[0].numOfBursts +
                ", linesPerBurst=" + subSwath[0].linesPerBurst);

        // Range offset reaches the TOPS path via nearEdgeSlantRange (shifted in getMetadata).
        // The azimuth offset must be applied here: the TOPS azimuth index comes from burst
        // times, not firstLineUTC, so shift the burst times by azimuthOffsetPixels.
        if (azimuthOffsetPixels != 0.0) {
            applyAzimuthOffsetToBurstTimes(subSwath[subSwathIndex - 1], azimuthOffsetPixels);
            SystemUtils.LOG.info(String.format(
                    "GSLC TOPS: azimuthOffsetPixels=%+.6f applied to burst times (%.4f s)",
                    azimuthOffsetPixels,
                    azimuthOffsetPixels * subSwath[subSwathIndex - 1].azimuthTimeInterval));
        }

        // Track-anchored burst IDs (when the annotation carries them) — the exact key for
        // matching this acquisition's bursts to the reference's when the two TOPSAR-Splits
        // frame different burst windows, and the payload that makes this product's own stamp
        // alignable by a future secondary.
        burstIds = extractBurstIds(sourceProduct, subSwath[subSwathIndex - 1].numOfBursts);

        // Burst-boundary lock: transport the reference's overlap boundaries into this
        // acquisition's time frame so both stack legs select the same burst at every map
        // pixel. Must run AFTER applyAzimuthOffsetToBurstTimes — the boundary offset Δk is
        // computed against the same (shifted) burst times selectBurst compares at runtime.
        if (refBurstValidTimes != null && !refBurstValidTimes.trim().isEmpty()) {
            final Sentinel1Utils.SubSwathInfo ss = subSwath[subSwathIndex - 1];
            burstLock = computeBurstLock(refBurstValidTimes, burstIds,
                    ss.burstFirstLineTime, ss.burstFirstValidLineTime, ss.burstLastValidLineTime);
            overlapMidLock = burstLock == null ? null : burstLock.overlapMid;
            if (burstLock != null) {
                SystemUtils.LOG.info(String.format(
                        "GSLC TOPS: burst-overlap boundaries locked to the reference (%d of %d "
                                + "seam(s); largest boundary shift %.1f azimuth line(s) vs own "
                                + "midpoint rule; %d edge seam(s) pushed to the matched burst's "
                                + "extent). Output is masked outside the reference's pairable "
                                + "window (bursts the reference does not frame cannot form a "
                                + "coherent pair).",
                        burstLock.matchedSeams, burstLock.overlapMid.length,
                        burstLock.maxBoundaryShiftSec / ss.azimuthTimeInterval,
                        burstLock.pushedSeams));
            }
        }
    }

    /**
     * Track-anchored (relative) S1 burst IDs from the annotation carried in
     * {@code Original_Product_Metadata} (swathTiming/burstList/burst/burstId — present since
     * IPF 3.40). The relative ID names the ground burst, so it is identical across platforms and
     * passes of the same track — unlike the {@code absolute} attribute, which counts per
     * acquisition. Returns them in burst (time) order, or null when absent or inconsistent with
     * {@code expectedCount} — callers must treat null as "IDs unavailable", never as an error.
     */
    static long[] extractBurstIds(final Product product, final int expectedCount) {
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
                final java.util.List<Long> ids = new java.util.ArrayList<>();
                boolean missing = false;
                for (final MetadataElement burst : burstList.getElements()) {
                    if (!burst.getName().startsWith("burst")) continue;
                    final MetadataElement idElem = burst.getElement("burstId");
                    final String id = idElem != null ? idElem.getAttributeString("burstId", null) : null;
                    if (id == null) {
                        missing = true;
                        break;
                    }
                    ids.add(Long.parseLong(id.trim()));
                }
                if (missing || ids.size() != expectedCount) return null;
                final long[] out = new long[ids.size()];
                for (int i = 0; i < out.length; i++) out[i] = ids.get(i);
                return out;
            }
        } catch (Throwable t) {
            SystemUtils.LOG.fine("GSLC TOPS: burst-ID walk failed: " + t.getMessage());
        }
        return null;
    }

    private synchronized void getElevationModel() throws Exception {
        if (isElevationModelAvailable) return;
        if (demName.contains(externalDEMStr) && externalDEMFile != null) {
            dem = new FileElevationModel(externalDEMFile, demResamplingMethod, externalDEMNoDataValue);
            ((FileElevationModel) dem).applyEarthGravitionalModel(externalDEMApplyEGM);
            demNoDataValue = externalDEMNoDataValue;
            demName = externalDEMFile.getName();
        } else {
            dem = DEMFactory.createElevationModel(demName, demResamplingMethod);
            demNoDataValue = dem.getDescriptor().getNoDataValue();
        }

        if (elevationBand != null) {
            elevationBand.setNoDataValue(demNoDataValue);
            elevationBand.setNoDataValueUsed(true);
        }

        isElevationModelAvailable = true;
    }

    private void getSourceImageDimension() {
        sourceImageWidth = sourceProduct.getSceneRasterWidth();
        sourceImageHeight = sourceProduct.getSceneRasterHeight();
    }

    private void createTargetProduct() {
        try {
            initTargetGridFromSource();

            addSelectedBands();

            targetGeoCoding = targetProduct.getSceneGeoCoding();

            ProductUtils.copyMetadata(sourceProduct, targetProduct);
            ProductUtils.copyMasks(sourceProduct, targetProduct);
            ProductUtils.copyVectorData(sourceProduct, targetProduct);

            targetProduct.setStartTime(sourceProduct.getStartTime());
            targetProduct.setEndTime(sourceProduct.getEndTime());
            targetProduct.setDescription(sourceProduct.getDescription());

            try {
                ProductUtils.copyIndexCodings(sourceProduct, targetProduct);
            } catch (Exception e) {
                if (!imgResampling.equals(Resampling.NEAREST_NEIGHBOUR)) {
                    throw new OperatorException("Use Nearest Neighbour with Classifications: " + e.getMessage());
                }
            }

        } catch (Exception e) {
            throw new OperatorException(e);
        }
    }

    /**
     * Warn when the auto-derived square cell is materially coarser than the native ground-range
     * sampling, so the cost of the default is visible rather than silent.
     * <p>
     * The derived spacing is {@code max(azimuth, range)} — square at the coarser axis — deliberately,
     * because a single isotropic step is what guarantees that any two GSLCs land on the same standard
     * grid (see {@link #quantizePixelSpacing}). For S1 IW that coarser axis is azimuth (~14 m) while
     * native ground-range sampling is {@code range_spacing / sin(incidence)} — about 3.5 m for IW3.
     * Square 14 m cells therefore discard roughly 4x of ground-range detail, which shows up as
     * smoothed or aliased fringes in the range direction.
     * <p>
     * The default is not changed here: doing so would put new products on a different lattice from
     * every GSLC produced so far, defeating the alignment guarantee the square cell exists to provide.
     * Users who want native resolution should set {@code pixelSpacingInMeter} to the ground-range
     * figure and {@code pixelSpacingInMeterY} to the azimuth figure, which yields rectangular cells
     * that oversample neither axis.
     * <p>
     * Advisory only; never alters the result.
     */
    /**
     * Ground-range sampling for the source, i.e. {@code slantRangeSpacing / sin(incidence)}. Falls back
     * to the slant spacing when the incidence angle is unavailable, which only ever makes the derived
     * grid finer (never coarser), so it cannot silently discard resolution.
     */
    /** Slant range spacing, or 0 when unavailable. Never throws — for use in log messages. */
    private double safeRangeSpacing() {
        try {
            return AbstractMetadata.getAttributeDouble(absRoot, AbstractMetadata.range_spacing);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private double groundRangeSpacing(final double slantRangeSpacing) {
        try {
            final double incidenceNear = AbstractMetadata.getAttributeDouble(absRoot, AbstractMetadata.incidence_near);
            if (incidenceNear > 0.0 && incidenceNear < 90.0) {
                final double s = Math.sin(Math.toRadians(incidenceNear));
                if (s > 0.0) {
                    return slantRangeSpacing / s;
                }
            }
        } catch (Exception e) {
            SystemUtils.LOG.fine("GSLC: incidence angle unavailable for ground-range spacing: " + e.getMessage());
        }
        return slantRangeSpacing;
    }

    private void warnIfSquareCellsDiscardRangeResolution(final double azimuthSpacing) {
        try {
            final double rgSpacing = AbstractMetadata.getAttributeDouble(absRoot, AbstractMetadata.range_spacing);
            if (rgSpacing <= 0.0) {
                return;
            }
            final double groundRangeSpacing = groundRangeSpacing(rgSpacing);
            if (groundRangeSpacing <= 0.0) {
                return;
            }
            final double ratio = pixelSpacingInMeter / groundRangeSpacing;
            if (ratio > 1.5) {
                SystemUtils.LOG.warning(String.format(
                        "GSLC: output cells are square at %.3f m (derived from the coarser axis, "
                        + "azimuth = %.3f m) while native ground-range sampling is %.2f m, so about "
                        + "%.1fx of ground-range detail is discarded and range-direction fringes will "
                        + "be smoothed. This is the cost of the isotropic step that guarantees a common "
                        + "lattice for stacking. To keep native resolution instead, set "
                        + "pixelSpacingInMeter=%.2f and pixelSpacingInMeterY=%.3f for rectangular "
                        + "cells - but note that changes the lattice, so use the same setting for every "
                        + "product in a stack.",
                        pixelSpacingInMeter, azimuthSpacing, groundRangeSpacing, ratio,
                        groundRangeSpacing, azimuthSpacing));
            }
        } catch (Exception e) {
            SystemUtils.LOG.fine("GSLC: pixel-spacing advisory skipped: " + e.getMessage());
        }
    }

    /**
     * Quantum, in metres, that the auto-derived output pixel spacing is snapped to.
     * <p>
     * 1 mm. Inter-platform metadata differences are ~1e-5 m (Sentinel-1A annotates an azimuth
     * spacing of 13.98908 m where Sentinel-1D annotates 13.98910 m for the same IW3 mode), so a
     * 1 mm quantum is ~50x larger than the spread it has to absorb while changing the spacing
     * itself by less than 1e-4 of a pixel — geometrically irrelevant, since the grid is defined
     * by (origin, step) and both are exact.
     */
    static final double PIXEL_SPACING_QUANTUM_M = 1.0e-3;

    /**
     * Quantum for a grid step DERIVED by a {@code gridSpacing} policy. Deliberately much coarser than
     * {@link #PIXEL_SPACING_QUANTUM_M}, which quantises a user-supplied spacing.
     *
     * <p>A derived step is a function of per-scene metadata, and that metadata is not identical
     * between acquisitions: measured on the S1A/S1C Venezuela IW3 pair, {@code azimuthPixelSpacing}
     * was 13.98908 m vs 13.98960 m — 0.52 mm apart. At a 1 mm quantum those land in different bins
     * (13.989 vs 13.990), giving the two products different standard-grid lattices, and
     * CreateStackOp.verifyGeocodedLatticeAlignment then rejects the stack. Slant range happens to be
     * a mode constant (2.329562 m for S1 IW) but azimuth spacing tracks platform velocity, so it
     * varies between platforms and along an orbit.
     *
     * <p>50 mm leaves ~100x margin over that observed drift, at the cost of moving the step by at
     * most 25 mm (~0.2% for S1 IW azimuth) — irrelevant for a grid step. Quantising cannot be made
     * bulletproof: two scenes straddling a bin edge still diverge. That residual case is SAFE rather
     * than silent, because the lattice check catches it and reports it.
     */
    static final double GRID_STEP_QUANTUM_M = 5.0e-2;

    /** Quantise a policy-derived grid step. See {@link #GRID_STEP_QUANTUM_M}. */
    static double quantizeGridStep(final double spacingMeters) {
        if (!Double.isFinite(spacingMeters) || spacingMeters <= 0.0) {
            return spacingMeters;
        }
        return Math.round(spacingMeters / GRID_STEP_QUANTUM_M) * GRID_STEP_QUANTUM_M;
    }

    /**
     * Snap an auto-derived pixel spacing to {@link #PIXEL_SPACING_QUANTUM_M}.
     * <p>
     * <b>Why this matters.</b> GSLC always snaps its output origin to the global standard grid,
     * i.e. {@code origin = round(coord / step) * step}. Two products snapped with even a
     * minutely different {@code step} land on two <em>different</em> lattices, and the offset
     * between them is then an arbitrary fraction of a pixel rather than a whole number. On the
     * Venezuela S1A/S1D pair a step difference of 1.8e-10 deg (a 12 cm drift across the scene —
     * negligible on its own) produced a <b>0.219 px = 3.06 m</b> fractional origin offset, which
     * {@code CreateStackOp}'s integer-pixel offset path cannot correct and which therefore
     * misaligns every pixel of the stack. Quantising makes the derived step bit-identical for
     * every product of a given sensor/mode, so the offset is exactly integral by construction.
     * <p>
     * Only the auto-derived spacing is quantised; an explicitly supplied
     * {@code pixelSpacingInMeter}/{@code pixelSpacingInDegree} is always honoured verbatim, since
     * that is how {@code CreateStackOp} locks a secondary onto the reference's exact grid.
     *
     * @param spacingMeters derived spacing in metres
     * @return the spacing snapped to the quantum, or the input unchanged if it is not a positive
     *         finite number
     */
    static double quantizePixelSpacing(final double spacingMeters) {
        if (!Double.isFinite(spacingMeters) || spacingMeters <= 0.0) {
            return spacingMeters;
        }
        return Math.round(spacingMeters / PIXEL_SPACING_QUANTUM_M) * PIXEL_SPACING_QUANTUM_M;
    }

    /**
     * Build the output grid (CRS, origin, pixel size, dimensions) from this product's footprint
     * via {@link CRSGeoCodingHandler}. Used when no reference product is supplied.
     */
    private void initTargetGridFromSource() throws Exception {
        if (pixelSpacingInMeter <= 0.0 && pixelSpacingInDegree <= 0) {
            // Use the SLC's native slant-range spacing (a sensor/mode constant) rather
            // than the incidence-corrected ground-range projection. Two scenes of the
            // same area acquired from slightly different orbital positions have slightly
            // different incidence angles at their respective scene centres, which makes
            // SARGeocoding.getRangePixelSpacing() return slightly different values —
            // breaking lattice alignment between master and slave GSLCs. The slant-range
            // spacing in the abstracted metadata is constant for every scene from the
            // same sensor/beam, so it gives every GSLC the same pixel size by
            // construction. (The output ends up slightly oversampled compared to the
            // old ground-range default — e.g. ~7.8 m vs ~20 m for ASAR IMS — but that
            // is the price of guaranteed lattice alignment for InSAR stacking.)
            final double azimuthSpacing = SARGeocoding.getAzimuthPixelSpacing(sourceProduct);
            final double rangeSpacingNative = AbstractMetadata.getAttributeDouble(absRoot,
                    AbstractMetadata.range_spacing);
            final double nativeSpacing = Math.max(azimuthSpacing, rangeSpacingNative);

            double multiplier = 1.0;
            if (oversamplingPercent > 0.0 && oversamplingPercent < 100.0) {
                multiplier = 1.0 - (oversamplingPercent / 100.0);
            }

            // Quantise before converting to degrees — see quantizePixelSpacing(). The degree
            // spacing is derived FROM the quantised metre value (not scaled separately) so that
            // equal metre spacing guarantees a bit-identical degree spacing, and hence an
            // identical standard-grid lattice.
            final double groundRangeSpacing = groundRangeSpacing(rangeSpacingNative);
            double stepX = nativeSpacing;
            double stepY = nativeSpacing;
            if (GRID_NATIVE_ANISOTROPIC.equals(gridSpacing)) {
                // Preserve native sampling on both axes: no oversampling, and no information
                // discarded.
                //
                // stepX uses the SLANT-range spacing, NOT the incidence-corrected ground-range
                // spacing, for the reason documented in initTargetGridFromSource(): ground-range
                // spacing is slantRange/sin(incidence_near), and incidence_near differs between two
                // acquisitions of the same area. With PIXEL_SPACING_QUANTUM_M at 1 mm that
                // difference survives quantisation (a 0.3 deg incidence difference is ~24 mm at S1
                // IW), so the two GSLCs get different steps and therefore different standard-grid
                // lattices -- CreateStackOp.verifyGeocodedLatticeAlignment then rejects the stack.
                // Observed exactly that on the S1A/S1C Venezuela IW3 pair.
                //
                // Slant-range spacing is a sensor/mode constant, so every scene from the same beam
                // gets an identical step BY CONSTRUCTION. It is finer than the ground-range
                // projection (~2.3 m vs ~3.5 m for S1 IW), so nothing is discarded -- the output is
                // slightly oversampled in range instead, which is the safe direction to err.
                stepX = rangeSpacingNative;
                stepY = azimuthSpacing;
            } else if (GRID_SQUARE_FINEST.equals(gridSpacing)) {
                // Square cells fine enough to keep the finer axis: loses nothing and keeps pixels
                // square for downstream operators that count pixels, at ~16x the data for S1 IW.
                stepX = Math.min(groundRangeSpacing, azimuthSpacing);
                stepY = stepX;
            }

            // Derived steps use the coarser grid quantum so two acquisitions agree; an explicitly
            // supplied spacing keeps the fine quantum, since the user's number is authoritative.
            pixelSpacingInMeter = quantizeGridStep(stepX * multiplier);
            pixelSpacingInDegree = SARGeocoding.getPixelSpacingInDegree(pixelSpacingInMeter);

            // Set Y explicitly ONLY for the anisotropic policy. The square policies leave it unset so
            // the existing "Y unset => mirror X" fallback below runs exactly as before, keeping the
            // default path byte-identical — setting Y here unconditionally flipped that fallback's
            // else-branch and newly reached an unguarded incidence_near read further down.
            //
            // Quantise the derived Y as well: the standard-grid origin snaps each axis independently
            // (origin = round(coord/step)*step), so an unquantised Y step would put two products on
            // different northing lattices for the same reason an unquantised X step would.
            if (GRID_NATIVE_ANISOTROPIC.equals(gridSpacing)) {
                pixelSpacingInMeterY = quantizeGridStep(stepY * multiplier);
                pixelSpacingInDegreeY = SARGeocoding.getPixelSpacingInDegree(pixelSpacingInMeterY);
            }

            if (GRID_SQUARE_COARSEST.equals(gridSpacing)) {
                warnIfSquareCellsDiscardRangeResolution(azimuthSpacing);
            } else {
                SystemUtils.LOG.info(String.format(
                        "GSLC: gridSpacing=%s -> %.3f m (east) x %.3f m (north); native sampling is "
                        + "%.2f m slant range (%.2f m projected to ground) x %.3f m azimuth. "
                        + "NATIVE_ANISOTROPIC steps east by SLANT range so every scene from this beam "
                        + "gets an identical step and stacks stay lattice-aligned.",
                        gridSpacing, pixelSpacingInMeter, pixelSpacingInMeterY,
                        rangeSpacingNative, groundRangeSpacing, azimuthSpacing));
            }

            if (Double.compare(pixelSpacingInMeter, nativeSpacing * multiplier) != 0) {
                SystemUtils.LOG.info(String.format(
                        "GSLC: output pixel spacing quantised %.9f m -> %.9f m (%.0f um grid) so that "
                                + "products from different platforms land on the same lattice.",
                        nativeSpacing * multiplier, pixelSpacingInMeter, PIXEL_SPACING_QUANTUM_M * 1e6));
            }
        }
        if (pixelSpacingInMeter <= 0.0) {
            pixelSpacingInMeter = SARGeocoding.getPixelSpacingInMeter(pixelSpacingInDegree);
        }
        if (pixelSpacingInDegree <= 0) {
            pixelSpacingInDegree = SARGeocoding.getPixelSpacingInDegree(pixelSpacingInMeter);
        }

        // Optional rectangular cells: an explicit Y (northing/latitude) spacing decouples the
        // two axes so the output can approach the SLC's anisotropic native resolution
        // (S1 IW: ~3.4 m ground range x ~14 m azimuth) instead of oversampling one axis to
        // match the other. When unset, the grid is square (Y = X), the historical behaviour.
        if (pixelSpacingInMeterY <= 0.0 && pixelSpacingInDegreeY <= 0.0) {
            pixelSpacingInMeterY = pixelSpacingInMeter;
            pixelSpacingInDegreeY = pixelSpacingInDegree;
        } else {
            if (pixelSpacingInMeterY <= 0.0) {
                pixelSpacingInMeterY = SARGeocoding.getPixelSpacingInMeter(pixelSpacingInDegreeY);
            }
            if (pixelSpacingInDegreeY <= 0.0) {
                pixelSpacingInDegreeY = SARGeocoding.getPixelSpacingInDegree(pixelSpacingInMeterY);
            }
            SystemUtils.LOG.info(String.format(
                    "GSLC: rectangular output cells %.4f m (E) x %.4f m (N). Native sampling of "
                            + "the source: ~%.2f m ground range x ~%.2f m azimuth.",
                    pixelSpacingInMeter, pixelSpacingInMeterY,
                    // Via the guarded helper: this read used to be unguarded, and
                    // AbstractMetadata.getAttributeDouble THROWS when a value is NO_METADATA - so any
                    // product without incidence_near (e.g. stripmap fixtures) crashed here as soon as
                    // an explicit Y spacing was supplied. A log line must never abort processing.
                    groundRangeSpacing(safeRangeSpacing()),
                    SARGeocoding.getAzimuthPixelSpacing(sourceProduct)));
        }
        delLat = pixelSpacingInDegreeY;
        delLon = pixelSpacingInDegree;

        // GSLC always aligns to the global standard grid (origin 0,0) so scenes are stackable.
        final CRSGeoCodingHandler crsHandler = new CRSGeoCodingHandler(sourceProduct, mapProjection,
                pixelSpacingInDegree, pixelSpacingInMeter,
                pixelSpacingInDegreeY, pixelSpacingInMeterY, true, 0, 0);

        targetCRS = crsHandler.getTargetCRS();

        targetProduct = new Product(sourceProduct.getName() + PRODUCT_SUFFIX,
                sourceProduct.getProductType(), crsHandler.getTargetWidth(), crsHandler.getTargetHeight());
        targetProduct.setSceneGeoCoding(crsHandler.getCrsGeoCoding());

        targetImageWidth = targetProduct.getSceneRasterWidth();
        targetImageHeight = targetProduct.getSceneRasterHeight();
    }

    private void computeSensorPositionsAndVelocities() {
        orbit = new OrbitStateVectors(orbitStateVectors, firstLineUTC, lineTimeInterval, sourceImageHeight);
    }

    private void addSelectedBands() throws OperatorException {
        final Band[] sourceBands = OperatorUtils.getSourceBands(sourceProduct, sourceBandNames, false);

        for (int i = 0; i < sourceBands.length; i++) {
            Band b = sourceBands[i];
            if (b.getUnit() != null && b.getUnit().equals(Unit.REAL)) {
                // Look for corresponding Imaginary
                Band q = null;
                if (i + 1 < sourceBands.length && sourceBands[i + 1].getUnit().equals(Unit.IMAGINARY)) {
                    q = sourceBands[i + 1];
                    i++; // Skip next
                }
                
                if (q != null) {
                    ComplexPair pair = new ComplexPair();
                    pair.srcI = b;
                    pair.srcQ = q;
                    pair.tgtI = addTargetBand(b.getName(), Unit.REAL, b);
                    pair.tgtQ = addTargetBand(q.getName(), Unit.IMAGINARY, q);
                    complexPairs.add(pair);

                    // ERS CEOS products name their complex bands just "i"/"q" — no underscore
                    // suffix — so lastIndexOf must not feed substring unguarded (substring(-1)
                    // threw on the first real ERS attempt). Empty suffix yields plain "Intensity".
                    final int us = b.getName().lastIndexOf('_');
                    final String suffix = us >= 0 ? b.getName().substring(us) : "";
                    ReaderUtils.createVirtualIntensityBand(targetProduct, pair.tgtI, pair.tgtQ, suffix);
                }
            } else {
                // Handle other bands if needed, but GSLC focuses on complex
                addTargetBand(b.getName(), b.getUnit(), b);
            }
        }

        if (saveDEM) {
            elevationBand = addTargetBand("elevation", Unit.METERS, null);
        }

        if (saveLatLon) {
            // -999 rather than the default 0.0: both fill paths write band.getNoDataValue() for
            // pixels that failed to geocode, and 0.0 is a valid coordinate (equator/meridian).
            final Band latBand = addTargetBand("latitude", Unit.DEGREES, null);
            latBand.setNoDataValue(-999.0);
            latBand.setNoDataValueUsed(true);
            final Band lonBand = addTargetBand("longitude", Unit.DEGREES, null);
            lonBand.setNoDataValue(-999.0);
            lonBand.setNoDataValueUsed(true);
        }

        if (saveLocalIncidenceAngle) {
            addTargetBand("localIncidenceAngle", Unit.DEGREES, null);
        }

        if (saveProjectedLocalIncidenceAngle) {
            addTargetBand("projectedLocalIncidenceAngle", Unit.DEGREES, null);
        }

        if (saveIncidenceAngleFromEllipsoid) {
            addTargetBand("incidenceAngleFromEllipsoid", Unit.DEGREES, null);
        }

        if (saveLayoverShadowMask) {
            addTargetBand(targetProduct, targetImageWidth, targetImageHeight, "layoverShadowMask",
                    Unit.BIT, null, ProductData.TYPE_INT8);
        }

        if (saveSimulatedPhase) {
            simulatedPhaseBand = addTargetBand("simulatedPhase", Unit.PHASE, null);
        }

        if (saveSimulatedUnwrappedPhase) {
            // FLOAT64: the unwrapped geometric phase is 4*pi*R/lambda, which for a spaceborne
            // C-band SAR is ~2e8 radians. In float32 one ulp there is ~16 radians, so the band
            // would be pure quantisation noise and useless for any phase work.
            simulatedUnwrappedPhaseBand = addTargetBand(targetProduct, targetImageWidth, targetImageHeight,
                    "simulatedUnwrappedPhase", Unit.PHASE, null, ProductData.TYPE_FLOAT64);
        }

        if (outputPhaseTerms) {
            // float64: the carrier reaches hundreds of radians per burst and the flattening phase is
            // 4*pi*R/lambda with R ~ 9e5 m, so float32 quantisation here would be ~13 rad — useless
            // for the term-by-term comparison these bands exist to support.
            carrierPhaseBand = addTargetBand(targetProduct, targetImageWidth, targetImageHeight,
                    "azimuthCarrierPhase", Unit.PHASE, null, ProductData.TYPE_FLOAT64);
            carrierPhaseBand.setDescription("Azimuth carrier model phase at the source position of "
                    + "each output pixel (TOPS: the deramp/demod carrier; stripmap: the residual "
                    + "Doppler-centroid carrier 2*pi*f_dc(r)*eta, informational — the stripmap "
                    + "round-trip restores it so it is NOT a cross-acquisition correction term).");
            flatteningPhaseBand = addTargetBand(targetProduct, targetImageWidth, targetImageHeight,
                    "flatteningPhase", Unit.PHASE, null, ProductData.TYPE_FLOAT64);
            flatteningPhaseBand.setDescription("Geometric (range) flattening phase 4*pi*R/lambda for "
                    + "each output pixel. Multiply by exp(+j*phase) to flatten, exp(-j*phase) to "
                    + "restore the natural SLC carrier.");
        }

        if (DIAG_GEOMETRY) {
            // slant range (~9e5 m) and the source indices need float64 for the same reason:
            // float32 there is ~0.06 m, i.e. ~13 rad of carrier phase.
            diagHeightBand = addTargetBand("diag_height", Unit.METERS, null);
            diagSlantRangeBand = addTargetBand(targetProduct, targetImageWidth, targetImageHeight,
                    "diag_slantRange", Unit.METERS, null, ProductData.TYPE_FLOAT64);
            diagRangeIndexBand = addTargetBand(targetProduct, targetImageWidth, targetImageHeight,
                    "diag_rangeIndex", null, null, ProductData.TYPE_FLOAT64);
            diagAzimuthIndexBand = addTargetBand(targetProduct, targetImageWidth, targetImageHeight,
                    "diag_azimuthIndex", null, null, ProductData.TYPE_FLOAT64);
            diagBurstBand = addTargetBand("diag_burst", null, null);
        }
    }

    private static ProductData diagBuf(final Map<Band, Tile> targetTiles, final Band band) {
        if (band == null) return null;
        final Tile t = targetTiles.get(band);
        return t != null ? t.getRawSamples() : null;
    }

    private Band addTargetBand(final String bandName, final String bandUnit, final Band sourceBand) {
        return addTargetBand(targetProduct, targetImageWidth, targetImageHeight,
                bandName, bandUnit, sourceBand, ProductData.TYPE_FLOAT32);
    }

    static Band addTargetBand(final Product targetProduct, final int targetImageWidth, final int targetImageHeight,
                              final String bandName, final String bandUnit, final Band sourceBand,
                              final int dataType) {
        String name = bandName;
        int cnt = 2;
        while (targetProduct.containsBand(name)) {
            name = bandName + cnt;
            ++cnt;
        }

        if (targetProduct.getBand(name) == null) {
            final Band targetBand = new Band(name, dataType, targetImageWidth, targetImageHeight);
            targetBand.setUnit(bandUnit);
            if (sourceBand != null) {
                targetBand.setDescription(sourceBand.getDescription());
                targetBand.setNoDataValue(sourceBand.getNoDataValue());
            }
            targetBand.setNoDataValueUsed(true);
            targetProduct.addBand(targetBand);
            return targetBand;
        }
        return null;
    }

    private void updateTargetProductMetadata() throws Exception {
        final MetadataElement absTgt = AbstractMetadata.getAbstractedMetadata(targetProduct);
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.srgr_flag, 1);
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.num_output_lines, targetImageHeight);
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.num_samples_per_line, targetImageWidth);

        final GeoPos geoPosFirstNear = targetGeoCoding.getGeoPos(new PixelPos(0, 0), null);
        final GeoPos geoPosFirstFar = targetGeoCoding.getGeoPos(new PixelPos(targetImageWidth - 1, 0), null);
        final GeoPos geoPosLastNear = targetGeoCoding.getGeoPos(new PixelPos(0, targetImageHeight - 1), null);
        final GeoPos geoPosLastFar = targetGeoCoding.getGeoPos(new PixelPos(targetImageWidth - 1, targetImageHeight - 1), null);

        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.first_near_lat, geoPosFirstNear.getLat());
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.first_far_lat, geoPosFirstFar.getLat());
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.last_near_lat, geoPosLastNear.getLat());
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.last_far_lat, geoPosLastFar.getLat());
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.first_near_long, geoPosFirstNear.getLon());
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.first_far_long, geoPosFirstFar.getLon());
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.last_near_long, geoPosLastNear.getLon());
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.last_far_long, geoPosLastFar.getLon());
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.TOT_SIZE, ReaderUtils.getTotalSize(targetProduct));
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.map_projection, targetCRS.getName().getCode());
        
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.is_terrain_corrected, 1);
        if (externalDEMFile != null) {
            AbstractMetadata.setAttribute(absTgt, AbstractMetadata.DEM, externalDEMFile.getPath());
        } else {
            AbstractMetadata.setAttribute(absTgt, AbstractMetadata.DEM, demName);
        }

        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.geo_ref_system, "WGS84");
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.lat_pixel_res, delLat);
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.lon_pixel_res, delLon);

        if (pixelSpacingInMeter > 0.0 &&
                (Double.compare(pixelSpacingInMeter, SARGeocoding.getPixelSpacing(sourceProduct)) != 0
                        || Double.compare(pixelSpacingInMeterY, pixelSpacingInMeter) != 0)) {
            // Per-axis: X (easting/longitude) is closest to range, Y (northing/latitude) to
            // azimuth for near-polar orbits. InterferogramOp's metre-based coherence window
            // reads these two attributes per direction, so rectangular cells get correctly
            // rectangular windows.
            AbstractMetadata.setAttribute(absTgt, AbstractMetadata.range_spacing, pixelSpacingInMeter);
            AbstractMetadata.setAttribute(absTgt, AbstractMetadata.azimuth_spacing,
                    pixelSpacingInMeterY > 0.0 ? pixelSpacingInMeterY : pixelSpacingInMeter);
        }

        // Stamp the source SLC's file path so downstream operators (notably CreateStackOp)
        // can reload the slant-range master when it needs to cross-correlate against a raw
        // slave SLC to estimate the GSLC coregistration bias. Without this, the auto-bias
        // path can't run because the master is only available as a geocoded product.
        final File srcFile = sourceProduct.getFileLocation();
        if (srcFile != null) {
            AbstractMetadata.addAbstractedAttribute(absTgt, "gslc_source_slc_path",
                    ProductData.TYPE_ASCII, "path",
                    "File path of the source SLC this GSLC was geocoded from");
            AbstractMetadata.setAttribute(absTgt, "gslc_source_slc_path", srcFile.getAbsolutePath());
        }
        // Stamp whether the output is phase-flattened (topographic + ellipsoidal phase
        // removed) so CreateStackOp can match this state when auto-building a slave GSLC
        // from a raw SLC. Without this stamp, mixing flattened/non-flattened bands in the
        // stack produces a meaningless phase difference (noise interferogram).
        AbstractMetadata.addAbstractedAttribute(absTgt, "gslc_output_flattened",
                ProductData.TYPE_ASCII, "flag",
                "true if topographic + ellipsoidal phase has been subtracted from the GSLC carrier");
        AbstractMetadata.setAttribute(absTgt, "gslc_output_flattened",
                outputFlattened ? "true" : "false");

        // Stamp whether the TOPS azimuth carrier was restored, for the same reason: a stack
        // mixing carrier-restored and carrier-free legs contains an uncancelled per-burst
        // quadratic azimuth phase (~tens of fringes/burst for a cross-platform pair).
        // CreateStackOp reads this stamp when auto-building a slave GSLC so the slave matches
        // the master's convention. Absence of the stamp means a legacy product => carrier
        // restored (the old unconditional behaviour).
        AbstractMetadata.addAbstractedAttribute(absTgt, "gslc_azimuth_carrier",
                ProductData.TYPE_ASCII, "flag",
                "true if the native TOPS azimuth (deramp) carrier is present in the output");
        AbstractMetadata.setAttribute(absTgt, "gslc_azimuth_carrier",
                outputAzimuthCarrier ? "true" : "false");

        // CreateStackOp forwards this so an auto-built secondary uses the SAME interpolation
        // kernel as the reference — asymmetric kernels give the legs different interpolation
        // decorrelation. Absence => the secondary keeps the GSLC default.
        if (imgResamplingMethod != null) {
            AbstractMetadata.addAbstractedAttribute(absTgt, "gslc_img_resampling",
                    ProductData.TYPE_ASCII, "name", "Interpolation kernel used for the complex resampling");
            AbstractMetadata.setAttribute(absTgt, "gslc_img_resampling", imgResamplingMethod);
        }

        // Provenance for the spatially-varying coregistration fields (stripmap): record the
        // applied affine coefficients so a stacked product shows how its secondary was aligned.
        if (rangeOffsetPolyCoef != null) {
            AbstractMetadata.addAbstractedAttribute(absTgt, "gslc_range_offset_poly",
                    ProductData.TYPE_ASCII, "px",
                    "Affine range offset field a0,a1,a2 applied to the source sampling position");
            AbstractMetadata.setAttribute(absTgt, "gslc_range_offset_poly",
                    rangeOffsetPolyCoef[0] + "," + rangeOffsetPolyCoef[1] + "," + rangeOffsetPolyCoef[2]);
        }
        if (azimuthOffsetPolyCoef != null) {
            AbstractMetadata.addAbstractedAttribute(absTgt, "gslc_azimuth_offset_poly",
                    ProductData.TYPE_ASCII, "px",
                    "Affine azimuth offset field b0,b1,b2 applied to the source sampling position");
            AbstractMetadata.setAttribute(absTgt, "gslc_azimuth_offset_poly",
                    azimuthOffsetPolyCoef[0] + "," + azimuthOffsetPolyCoef[1] + "," + azimuthOffsetPolyCoef[2]);
        }

        // Stamp this product's burst valid-time table (as used at runtime, i.e. after any
        // azimuth-offset shift) so CreateStackOp can lock an auto-built secondary's burst-
        // overlap boundaries to this reference's (refBurstValidTimes). Without the lock each
        // leg splits the ~2 km TOPS burst overlap at its own midpoint, and the strip between
        // the two boundaries pairs looks from different bursts (~4 kHz apart in Doppler
        // centroid) — inherently incoherent, visible as a decorrelated line at every seam.
        if (isTOPSProduct && subSwath != null) {
            // When a lock was active this stamps the EFFECTIVE partition (locked/pushed
            // boundaries, masked window) — what this product's output actually contains — so a
            // future stack using this product as its reference locks to the right boundaries.
            final String burstTable = formatBurstValidTimes(subSwath[subSwathIndex - 1], burstIds,
                    burstLock);
            if (burstTable != null) {
                AbstractMetadata.addAbstractedAttribute(absTgt, "gslc_burst_valid_times",
                        ProductData.TYPE_ASCII, "sec",
                        "Per-burst [burstId:]firstLine:firstValid:lastValid zero-Doppler times used for burst selection");
                AbstractMetadata.setAttribute(absTgt, "gslc_burst_valid_times", burstTable);
            }
        }
    }

    @Override
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle targetRectangle, ProgressMonitor pm) throws OperatorException {
        try {
            if (!isElevationModelAvailable) {
                getElevationModel();
            }

            if (isTOPSProduct) {
                computeTileStackTOPS(targetTiles, targetRectangle);
            } else {
                computeTileStackSM(targetTiles, targetRectangle);
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private void computeTileStackSM(Map<Band, Tile> targetTiles, Rectangle targetRectangle) throws Exception {
        {
            final int x0 = targetRectangle.x;
            final int y0 = targetRectangle.y;
            final int w = targetRectangle.width;
            final int h = targetRectangle.height;

            final TileGeoreferencing tileGeoRef = new TileGeoreferencing(targetProduct, x0 - 1, y0 - 1, w + 2, h + 2);

            double[][] localDEM = new double[h + 2][w + 2];
            final boolean valid = DEMFactory.getLocalDEM(
                    dem, demNoDataValue, demResamplingMethod, tileGeoRef, x0, y0, w, h, sourceProduct,
                    nodataValueAtSea, localDEM);

            if (!valid && nodataValueAtSea) {
                fillTilesAsNoSource(targetTiles);
                return;
            }

            Rectangle sourceRectangle = getSourceRectangle(x0, y0, w, h, tileGeoRef, localDEM);

            // Ensure the source rectangle intersects with the source image bounds
            if (sourceRectangle != null) {
                final Rectangle sourceBounds = new Rectangle(0, 0, sourceImageWidth, sourceImageHeight);
                sourceRectangle = sourceRectangle.intersection(sourceBounds);
                if (sourceRectangle.isEmpty()) {
                    sourceRectangle = null;
                }
            }

            if (sourceRectangle == null) {
                 fillTilesAsNoSource(targetTiles);
                 return;
            }

            // Prepare buffers and rasters for Complex Pairs
            class ActivePair {
                GSLCResamplingRaster raster;
                ProductData bufI;
                ProductData bufQ;
                double noDataI;
                double noDataQ;
            }
            List<ActivePair> activePairs = new ArrayList<>();

            for (ComplexPair pair : complexPairs) {
                boolean processI = targetTiles.containsKey(pair.tgtI);
                boolean processQ = targetTiles.containsKey(pair.tgtQ);
                
                if (!processI && !processQ) continue;

                Tile srcTileI = getSourceTile(pair.srcI, sourceRectangle);
                Tile srcTileQ = getSourceTile(pair.srcQ, sourceRectangle);
                
                ActivePair ap = new ActivePair();
                ap.raster = new GSLCResamplingRaster(srcTileI, srcTileQ,
                        sourceImageWidth, sourceImageHeight,
                        fdcPerSourceColumn, lineTimeIntervalSec);
                ap.bufI = processI ? targetTiles.get(pair.tgtI).getRawSamples() : null;
                ap.bufQ = processQ ? targetTiles.get(pair.tgtQ).getRawSamples() : null;
                ap.noDataI = pair.tgtI.getNoDataValue();
                ap.noDataQ = pair.tgtQ.getNoDataValue();
                activePairs.add(ap);
            }

            // Prepare buffers for other bands
            ProductData bufPhase = (saveSimulatedPhase && targetTiles.containsKey(simulatedPhaseBand)) ? targetTiles.get(simulatedPhaseBand).getRawSamples() : null;
            ProductData bufUnwrappedPhase = (saveSimulatedUnwrappedPhase && targetTiles.containsKey(simulatedUnwrappedPhaseBand)) ? targetTiles.get(simulatedUnwrappedPhaseBand).getRawSamples() : null;
            
            ProductData demBuffer = (saveDEM && targetTiles.containsKey(elevationBand)) ? targetTiles.get(elevationBand).getRawSamples() : null;
            ProductData latBuffer = (saveLatLon && targetTiles.containsKey(targetProduct.getBand("latitude"))) ? targetTiles.get(targetProduct.getBand("latitude")).getRawSamples() : null;
            ProductData lonBuffer = (saveLatLon && targetTiles.containsKey(targetProduct.getBand("longitude"))) ? targetTiles.get(targetProduct.getBand("longitude")).getRawSamples() : null;
            ProductData localIncidenceAngleBuffer = (saveLocalIncidenceAngle && targetTiles.containsKey(targetProduct.getBand("localIncidenceAngle"))) ? targetTiles.get(targetProduct.getBand("localIncidenceAngle")).getRawSamples() : null;
            ProductData projectedLocalIncidenceAngleBuffer = (saveProjectedLocalIncidenceAngle && targetTiles.containsKey(targetProduct.getBand("projectedLocalIncidenceAngle"))) ? targetTiles.get(targetProduct.getBand("projectedLocalIncidenceAngle")).getRawSamples() : null;
            ProductData incidenceAngleFromEllipsoidBuffer = (saveIncidenceAngleFromEllipsoid && targetTiles.containsKey(targetProduct.getBand("incidenceAngleFromEllipsoid"))) ? targetTiles.get(targetProduct.getBand("incidenceAngleFromEllipsoid")).getRawSamples() : null;
            ProductData layoverShadowMaskBuffer = (saveLayoverShadowMask && targetTiles.containsKey(targetProduct.getBand("layoverShadowMask"))) ? targetTiles.get(targetProduct.getBand("layoverShadowMask")).getRawSamples() : null;

            // outputPhaseTerms bands, stripmap fill (the TOPS path fills its own): the values are
            // computed per pixel anyway — without this block the bands came out empty on every
            // stripmap product (first reported on the ERS tandem work).
            ProductData bufCarrier = (outputPhaseTerms && carrierPhaseBand != null
                    && targetTiles.containsKey(carrierPhaseBand))
                    ? targetTiles.get(carrierPhaseBand).getRawSamples() : null;
            ProductData bufFlattening = (outputPhaseTerms && flatteningPhaseBand != null
                    && targetTiles.containsKey(flatteningPhaseBand))
                    ? targetTiles.get(flatteningPhaseBand).getRawSamples() : null;

            final Resampling.Index resamplingIndex = imgResampling.createIndex();
            final PositionData posData = new PositionData();
            final GeoPos geoPos = new GeoPos();
            final double phaseConstant = 4.0 * Math.PI / wavelength;

            // Pre-fetch noDataValues for auxiliary bands to avoid repeated band lookups in the loop
            final double noDataPhase = (simulatedPhaseBand != null) ? simulatedPhaseBand.getNoDataValue() : 0;
            final double noDataUnwrappedPhase = (simulatedUnwrappedPhaseBand != null) ? simulatedUnwrappedPhaseBand.getNoDataValue() : 0;
            final double noDataDem = (elevationBand != null) ? elevationBand.getNoDataValue() : 0;
            final Band latBand = saveLatLon ? targetProduct.getBand("latitude") : null;
            final Band lonBand = saveLatLon ? targetProduct.getBand("longitude") : null;
            final Band localIncAngleBand = saveLocalIncidenceAngle ? targetProduct.getBand("localIncidenceAngle") : null;
            final Band projIncAngleBand = saveProjectedLocalIncidenceAngle ? targetProduct.getBand("projectedLocalIncidenceAngle") : null;
            final Band ellipIncAngleBand = saveIncidenceAngleFromEllipsoid ? targetProduct.getBand("incidenceAngleFromEllipsoid") : null;
            final double noDataLat = (latBand != null) ? latBand.getNoDataValue() : 0;
            final double noDataLon = (lonBand != null) ? lonBand.getNoDataValue() : 0;
            final double noDataLocalInc = (localIncAngleBand != null) ? localIncAngleBand.getNoDataValue() : 0;
            final double noDataProjInc = (projIncAngleBand != null) ? projIncAngleBand.getNoDataValue() : 0;
            final double noDataEllipInc = (ellipIncAngleBand != null) ? ellipIncAngleBand.getNoDataValue() : 0;

            // Unified Loop
            for (int y = y0; y < y0 + h; y++) {
                final int yy = y - y0 + 1;
                for (int x = x0; x < x0 + w; x++) {
                    final int xx = x - x0 + 1;
                    final int idx = (y - y0) * w + (x - x0);

                    final double alt = localDEM[yy][xx];
                    boolean isNoData = (alt == demNoDataValue);

                    if (!isNoData) {
                        tileGeoRef.getGeoPos(x, y, geoPos);
                        if (!getPosition(geoPos.lat, geoPos.lon, alt, posData)) {
                            isNoData = true;
                        } else {
                            // Coregistration offset fields shift only the source lookup;
                            // must run before the validity check so out-of-image corrected
                            // positions fall through to noData like any other invalid cell.
                            applyOffsetField(posData);
                            if (!isValidCell(posData.rangeIndex, posData.azimuthIndex)) {
                                isNoData = true;
                            }
                        }
                    }

                    if (isNoData) {
                        for (ActivePair ap : activePairs) {
                            if (ap.bufI != null) ap.bufI.setElemDoubleAt(idx, ap.noDataI);
                            if (ap.bufQ != null) ap.bufQ.setElemDoubleAt(idx, ap.noDataQ);
                        }
                        if (bufPhase != null) bufPhase.setElemDoubleAt(idx, noDataPhase);
                        if (bufUnwrappedPhase != null) bufUnwrappedPhase.setElemDoubleAt(idx, noDataUnwrappedPhase);
                        if (bufCarrier != null) bufCarrier.setElemDoubleAt(idx, 0.0);
                        if (bufFlattening != null) bufFlattening.setElemDoubleAt(idx, 0.0);
                        if (demBuffer != null) demBuffer.setElemDoubleAt(idx, noDataDem);
                        if (latBuffer != null) latBuffer.setElemDoubleAt(idx, noDataLat);
                        if (lonBuffer != null) lonBuffer.setElemDoubleAt(idx, noDataLon);
                        if (localIncidenceAngleBuffer != null) localIncidenceAngleBuffer.setElemDoubleAt(idx, noDataLocalInc);
                        if (projectedLocalIncidenceAngleBuffer != null) projectedLocalIncidenceAngleBuffer.setElemDoubleAt(idx, noDataProjInc);
                        if (incidenceAngleFromEllipsoidBuffer != null) incidenceAngleFromEllipsoidBuffer.setElemDoubleAt(idx, noDataEllipInc);
                        if (layoverShadowMaskBuffer != null) layoverShadowMaskBuffer.setElemIntAt(idx, MASK_NO_SOURCE);
                        continue;
                    }

                    // Valid Pixel Processing

                    // 1. Complex Resampling
                    final double rangeIndex = posData.rangeIndex;
                    final double azimuthIndex = posData.azimuthIndex;
                    final double slantRange = posData.slantRange;

                    imgResampling.computeCornerBasedIndex(rangeIndex, azimuthIndex, sourceImageWidth, sourceImageHeight, resamplingIndex);

                    final double phase = phaseConstant * slantRange;
                    final double cosPhi = FastMath.cos(phase);
                    final double sinPhi = FastMath.sin(phase);

                    // Azimuth reramp phase at the target position. The resampler removed
                    // the azimuth carrier {@code exp(+j·2π·f_dc(r)·eta·dt)} from every
                    // sample so the sinc kernel operates on a baseband signal; here we
                    // re-apply that carrier at the target's fractional (rangeIndex,
                    // azimuthIndex) so the output matches the OPERA-CSLC / NISAR-GSLC
                    // convention (azimuth carrier preserved, only the topographic /
                    // range carrier flattened when outputFlattened=true).
                    double cosAzTgt = 1.0, sinAzTgt = 0.0;
                    double phiAzTgt = 0.0;
                    if (fdcPerSourceColumn != null) {
                        final double fdcTgt = interpFdcAt(rangeIndex);
                        phiAzTgt = 2.0 * Math.PI * fdcTgt * azimuthIndex * lineTimeIntervalSec;
                        cosAzTgt = FastMath.cos(phiAzTgt);
                        sinAzTgt = FastMath.sin(phiAzTgt);
                    }
                    if (bufCarrier != null) bufCarrier.setElemDoubleAt(idx, phiAzTgt);
                    if (bufFlattening != null) bufFlattening.setElemDoubleAt(idx, phase);

                    for (ActivePair ap : activePairs) {
                        ap.raster.setReturnReal(true);
                        double iSamp = imgResampling.resample(ap.raster, resamplingIndex);

                        ap.raster.setReturnReal(false);
                        double qSamp = imgResampling.resample(ap.raster, resamplingIndex);

                        if (iSamp == ap.raster.getNoDataValue() || qSamp == ap.raster.getNoDataValue()) {
                             if (ap.bufI != null) ap.bufI.setElemDoubleAt(idx, ap.noDataI);
                             if (ap.bufQ != null) ap.bufQ.setElemDoubleAt(idx, ap.noDataQ);
                        } else {
                             // Azimuth reramp (applied for both outputFlattened modes — the
                             // natural SLC azimuth carrier is restored regardless of whether
                             // the range carrier is being flattened).
                             // (iSamp + j·qSamp) · exp(+j·phiAzTgt)
                             if (fdcPerSourceColumn != null) {
                                  final double iAz = iSamp * cosAzTgt - qSamp * sinAzTgt;
                                  final double qAz = qSamp * cosAzTgt + iSamp * sinAzTgt;
                                  iSamp = iAz;
                                  qSamp = qAz;
                             }

                             // The kernel interpolated raw baseband i/q, so iSamp/qSamp is
                             // the natural SLC sample s(p) at the target position. The
                             // flattening phase (topographic + ellipsoidal carrier removal)
                             // is applied AFTER the kernel at the target's geometric slant
                             // range — never before it, where the per-column exp(+j·4πR/λ)
                             // ramp aliases (−0.4989 cyc/px on ERS) and destroys sub-pixel
                             // interpolation. See GSLCResamplingRaster and
                             // GSLCComplexResamplingFidelityTest.
                             double iFinal, qFinal;
                             if (outputFlattened) {
                                  // s(p) · exp(+j·4π·R_tgt/λ)
                                  final double[] flattened = new double[2];
                                  multiplyByExpJPhi(iSamp, qSamp, cosPhi, sinPhi, flattened);
                                  iFinal = flattened[0];
                                  qFinal = flattened[1];
                             } else {
                                  iFinal = iSamp;
                                  qFinal = qSamp;
                             }
                             if (ap.bufI != null) ap.bufI.setElemDoubleAt(idx, iFinal);
                             if (ap.bufQ != null) ap.bufQ.setElemDoubleAt(idx, qFinal);
                        }
                    }

                    // 2. Simulated Phase
                    if (bufPhase != null) {
                        double wrappedPhase = Math.atan2(sinPhi, cosPhi);
                        bufPhase.setElemDoubleAt(idx, wrappedPhase);
                    }
                    if (bufUnwrappedPhase != null) {
                        bufUnwrappedPhase.setElemDoubleAt(idx, phase);
                    }

                    // 3. Other Bands
                    if (demBuffer != null) demBuffer.setElemDoubleAt(idx, alt);
                    if (latBuffer != null) latBuffer.setElemDoubleAt(idx, geoPos.lat);
                    if (lonBuffer != null) lonBuffer.setElemDoubleAt(idx, geoPos.lon);

                    // Local incidence is needed for the layover/shadow mask too,
                    // so compute it whenever any of those bands is requested.
                    final boolean needLocalInc = localIncidenceAngleBuffer != null
                            || projectedLocalIncidenceAngleBuffer != null
                            || layoverShadowMaskBuffer != null;
                    double localIncDeg = SARGeocoding.NonValidIncidenceAngle;
                    if (needLocalInc) {
                        final double[] localIncidenceAngles = {SARGeocoding.NonValidIncidenceAngle, SARGeocoding.NonValidIncidenceAngle};
                        final LocalGeometry localGeometry = new LocalGeometry(
                                x, y, tileGeoRef, posData.earthPoint, posData.sensorPos);

                        SARGeocoding.computeLocalIncidenceAngle(
                                localGeometry, demNoDataValue, true /*saveLocalIncidenceAngle*/,
                                saveProjectedLocalIncidenceAngle, false, x0, y0, x, y,
                                localDEM, localIncidenceAngles);
                        localIncDeg = localIncidenceAngles[0];

                        if (localIncidenceAngleBuffer != null) {
                            localIncidenceAngleBuffer.setElemDoubleAt(idx, localIncidenceAngles[0]);
                        }
                        if (projectedLocalIncidenceAngleBuffer != null) {
                            projectedLocalIncidenceAngleBuffer.setElemDoubleAt(idx, localIncidenceAngles[1]);
                        }
                    }

                    if (incidenceAngleFromEllipsoidBuffer != null && incidenceAngle != null) {
                        incidenceAngleFromEllipsoidBuffer.setElemDoubleAt(idx, incidenceAngle.getPixelDouble(posData.rangeIndex, posData.azimuthIndex));
                    }

                    if (layoverShadowMaskBuffer != null) {
                        layoverShadowMaskBuffer.setElemIntAt(idx, classifyLayoverShadow(localIncDeg));
                    }
                }
            }

        }
    }

    private void computeTileStackTOPS(Map<Band, Tile> targetTiles, Rectangle targetRectangle) throws Exception {

        final int x0 = targetRectangle.x;
        final int y0 = targetRectangle.y;
        final int w = targetRectangle.width;
        final int h = targetRectangle.height;
        final int numPixels = w * h;

        final TileGeoreferencing tileGeoRef = new TileGeoreferencing(targetProduct, x0 - 1, y0 - 1, w + 2, h + 2);

        double[][] localDEM = new double[h + 2][w + 2];
        final boolean valid = DEMFactory.getLocalDEM(
                dem, demNoDataValue, demResamplingMethod, tileGeoRef, x0, y0, w, h, sourceProduct,
                nodataValueAtSea, localDEM);

        if (!valid && nodataValueAtSea) {
            fillAllNoData(targetTiles);
            return;
        }

        // Phase 1: Backward geocode all target pixels with burst-aware azimuth mapping.
        // For TOPS burst products, the azimuth time-to-line mapping is NOT linear across the
        // full image — each burst has its own time window with gaps between bursts.
        final double[] azimuthIndices = new double[numPixels];
        final double[] rangeIndices = new double[numPixels];
        final double[] slantRanges = new double[numPixels];
        final int[] bestBurst = new int[numPixels];
        // Per-target-pixel geometry kept for the optional output bands. The SM path fills
        // elevation/latitude/longitude inline; the TOPS path is two-phase (geocode all pixels,
        // then resample burst by burst), so the values are collected here and written below.
        final double[] pxHeight = (saveDEM || DIAG_GEOMETRY) ? new double[numPixels] : null;
        // Only the CARRIER needs carrying out of the resampling loop; the flattening phase is
        // recomputed from slantRanges in the tail loop below, where it is already needed.
        final double[] pxCarrierPhase = outputPhaseTerms ? new double[numPixels] : null;
        final double[] pxLat = saveLatLon ? new double[numPixels] : null;
        final double[] pxLon = saveLatLon ? new double[numPixels] : null;
        java.util.Arrays.fill(bestBurst, -1);

        final PosVector earthPoint = new PosVector();
        final PosVector sensorPos = new PosVector();
        final GeoPos geoPos = new GeoPos();
        final Sentinel1Utils.SubSwathInfo ss = subSwath[subSwathIndex - 1];
        final double phaseConstant = 4.0 * Math.PI / wavelength;

        for (int y = y0; y < y0 + h; y++) {
            final int yy = y - y0 + 1;
            for (int x = x0; x < x0 + w; x++) {
                final int xx = x - x0 + 1;
                final int idx = (y - y0) * w + (x - x0);

                final double alt = localDEM[yy][xx];
                if (alt == demNoDataValue) continue;

                tileGeoRef.getGeoPos(x, y, geoPos);
                GeoUtils.geo2xyzWGS84(geoPos.lat, geoPos.lon, alt, earthPoint);

                // §4 SET (TOPS path): apply body-tide displacement to the ground
                // point so both rangeIndex and slant-range phase reflect the actual
                // position the radar imaged on this acquisition.
                if (applySolidEarthTide) {
                    final double[] dEcef = SolidEarthTide.computeEcefDisplacement(
                            sceneMidTimeMjdUtc, geoPos.lat, geoPos.lon, alt);
                    earthPoint.x += dEcef[0];
                    earthPoint.y += dEcef[1];
                    earthPoint.z += dEcef[2];
                }

                // Find zero-Doppler time using orbit state vector bisection (not pre-computed array).
                // For TOPS burst products, the pre-computed array uses lineTimeInterval that includes
                // burst gaps, causing incorrect orbit positions and failed Doppler searches.
                final double zeroDopplerTime = SARGeocoding.getZeroDopplerTime(
                        lineTimeInterval, wavelength, earthPoint, orbit);

                if (zeroDopplerTime == SARGeocoding.NonValidZeroDopplerTime) continue;

                // Compute slant range from orbit interpolation
                double slantRange = SARGeocoding.computeSlantRange(
                        zeroDopplerTime, orbit, earthPoint, sensorPos);

                // Bistatic correction
                double correctedTime = zeroDopplerTime;
                if (!skipBistaticCorrection) {
                    correctedTime += slantRange / Constants.lightSpeedInMetersPerDay;
                    slantRange = SARGeocoding.computeSlantRange(
                            correctedTime, orbit, earthPoint, sensorPos);
                } else if (bistaticCorrectionRefRange > 0.0) {
                    correctedTime += (slantRange - bistaticCorrectionRefRange) / Constants.lightSpeedInMetersPerDay;
                    slantRange = SARGeocoding.computeSlantRange(
                            correctedTime, orbit, earthPoint, sensorPos);
                }

                // Convert zero-Doppler time to seconds for burst lookup
                final double zeroDopplerTimeSec = correctedTime * Constants.secondsInDay;

                // Determine burst membership (overlap boundaries locked to the reference
                // acquisition's when refBurstValidTimes is set — see overlapMidLock)
                final int burst = selectBurst(zeroDopplerTimeSec, ss, overlapMidLock);
                if (burst < 0) continue;
                if (!isPairableWithReference(burstLock, burst, zeroDopplerTimeSec)) continue;

                // Compute burst-local azimuth index
                final double lineWithinBurst = (zeroDopplerTimeSec - ss.burstFirstLineTime[burst])
                        / ss.azimuthTimeInterval;
                final double azimuthIndex = burst * ss.linesPerBurst + lineWithinBurst;

                // Compute range index
                double rangeIndex;
                if (!srgrFlag) {
                    rangeIndex = (slantRange - nearEdgeSlantRange) / rangeSpacing;
                } else {
                    rangeIndex = SARGeocoding.computeRangeIndex(
                            srgrFlag, sourceImageWidth, firstLineUTC, lastLineUTC, rangeSpacing,
                            correctedTime, slantRange, nearEdgeSlantRange, srgrConvParams);
                    if (rangeIndex == -1.0) continue;
                }

                if (!nearRangeOnLeft) {
                    rangeIndex = sourceImageWidth - 1 - rangeIndex;
                }

                // Validate within burst valid region
                if (!isValidBurstSample(burst, azimuthIndex, rangeIndex, ss)) continue;
                if (!isValidCell(rangeIndex, azimuthIndex)) continue;

                // §4 troposphere: same convention as the SM path — apply the
                // Saastamoinen dry path delay to the slant range used for the
                // OUTPUT phase only, after rangeIndex is fixed.
                if (applyTroposphericCorrection) {
                    slantRange += SARGeocoding.computeAtmosphericPathDelay(
                            earthPoint, sensorPos, geoPos.lat, alt);
                }

                azimuthIndices[idx] = azimuthIndex;
                rangeIndices[idx] = rangeIndex;
                slantRanges[idx] = slantRange;
                bestBurst[idx] = burst;
                if (pxHeight != null) pxHeight[idx] = alt;
                if (pxLat != null) {
                    pxLat[idx] = geoPos.lat;
                    pxLon[idx] = geoPos.lon;
                }
            }
        }

        // Prepare output buffers
        class ActivePair {
            ComplexPair pair;
            ProductData bufI;
            ProductData bufQ;
            double noDataI;
            double noDataQ;
        }
        final List<ActivePair> activePairs = new ArrayList<>();

        for (ComplexPair pair : complexPairs) {
            boolean processI = targetTiles.containsKey(pair.tgtI);
            boolean processQ = targetTiles.containsKey(pair.tgtQ);
            if (!processI && !processQ) continue;

            ActivePair ap = new ActivePair();
            ap.pair = pair;
            ap.bufI = processI ? targetTiles.get(pair.tgtI).getRawSamples() : null;
            ap.bufQ = processQ ? targetTiles.get(pair.tgtQ).getRawSamples() : null;
            ap.noDataI = pair.tgtI.getNoDataValue();
            ap.noDataQ = pair.tgtQ.getNoDataValue();
            activePairs.add(ap);
        }

        ProductData bufPhase = (saveSimulatedPhase && targetTiles.containsKey(simulatedPhaseBand))
                ? targetTiles.get(simulatedPhaseBand).getRawSamples() : null;
        ProductData bufUnwrappedPhase = (saveSimulatedUnwrappedPhase && targetTiles.containsKey(simulatedUnwrappedPhaseBand))
                ? targetTiles.get(simulatedUnwrappedPhaseBand).getRawSamples() : null;

        // Optional geometry output bands (elevation / latitude / longitude). The SM path writes
        // these inline; without this block they came out empty for every TOPS product.
        final ProductData demBuffer = (saveDEM && elevationBand != null
                && targetTiles.containsKey(elevationBand)) ? targetTiles.get(elevationBand).getRawSamples() : null;
        final Band latBand = saveLatLon ? targetProduct.getBand("latitude") : null;
        final Band lonBand = saveLatLon ? targetProduct.getBand("longitude") : null;
        final ProductData latBuffer = (latBand != null && targetTiles.containsKey(latBand))
                ? targetTiles.get(latBand).getRawSamples() : null;
        final ProductData lonBuffer = (lonBand != null && targetTiles.containsKey(lonBand))
                ? targetTiles.get(lonBand).getRawSamples() : null;
        if (demBuffer != null || latBuffer != null || lonBuffer != null) {
            final double demNoData = elevationBand != null ? elevationBand.getNoDataValue() : 0.0;
            for (int idx = 0; idx < numPixels; idx++) {
                final boolean ok = bestBurst[idx] != -1;
                if (demBuffer != null) demBuffer.setElemDoubleAt(idx, ok ? pxHeight[idx] : demNoData);
                if (latBuffer != null) latBuffer.setElemDoubleAt(idx, ok ? pxLat[idx] : latBand.getNoDataValue());
                if (lonBuffer != null) lonBuffer.setElemDoubleAt(idx, ok ? pxLon[idx] : lonBand.getNoDataValue());
            }
        }

        if (DIAG_GEOMETRY) {
            final ProductData bufH = diagBuf(targetTiles, diagHeightBand);
            final ProductData bufR = diagBuf(targetTiles, diagSlantRangeBand);
            final ProductData bufRi = diagBuf(targetTiles, diagRangeIndexBand);
            final ProductData bufAi = diagBuf(targetTiles, diagAzimuthIndexBand);
            final ProductData bufB = diagBuf(targetTiles, diagBurstBand);
            for (int idx = 0; idx < numPixels; idx++) {
                final boolean ok = bestBurst[idx] != -1;
                if (bufH != null) bufH.setElemDoubleAt(idx, ok ? pxHeight[idx] : 0.0);
                if (bufR != null) bufR.setElemDoubleAt(idx, ok ? slantRanges[idx] : 0.0);
                if (bufRi != null) bufRi.setElemDoubleAt(idx, ok ? rangeIndices[idx] : 0.0);
                if (bufAi != null) bufAi.setElemDoubleAt(idx, ok ? azimuthIndices[idx] : 0.0);
                if (bufB != null) bufB.setElemDoubleAt(idx, ok ? bestBurst[idx] + 1 : 0.0);
            }
        }

        // Fill noData for pixels that didn't geocode
        for (int idx = 0; idx < numPixels; idx++) {
            if (bestBurst[idx] == -1) {
                for (ActivePair ap : activePairs) {
                    if (ap.bufI != null) ap.bufI.setElemDoubleAt(idx, ap.noDataI);
                    if (ap.bufQ != null) ap.bufQ.setElemDoubleAt(idx, ap.noDataQ);
                }
                if (bufPhase != null) bufPhase.setElemDoubleAt(idx, simulatedPhaseBand.getNoDataValue());
                if (bufUnwrappedPhase != null) bufUnwrappedPhase.setElemDoubleAt(idx, simulatedUnwrappedPhaseBand.getNoDataValue());
            }
        }

        // Phase 2: Process burst by burst
        final Resampling.Index resamplingIndex = imgResampling.createIndex();
        final Rectangle sourceBounds = new Rectangle(0, 0, sourceImageWidth, sourceImageHeight);

        for (int burstIndex = 0; burstIndex < ss.numOfBursts; burstIndex++) {

            // Compute source rectangle for this burst's target pixels
            final Rectangle burstSourceRect = computeBurstSourceRectangle(
                    burstIndex, bestBurst, azimuthIndices, rangeIndices, w, h, ss);
            if (burstSourceRect == null) continue;

            // Clamp to source image bounds
            final Rectangle clampedRect = burstSourceRect.intersection(sourceBounds);
            if (clampedRect.isEmpty()) continue;

            for (ActivePair ap : activePairs) {
                final Tile srcTileI = getSourceTile(ap.pair.srcI, clampedRect);
                final Tile srcTileQ = getSourceTile(ap.pair.srcQ, clampedRect);

                // Compute deramp+demod phase for this burst region
                final double[][] derampDemodPhase = su.computeDerampDemodPhase(
                        subSwath, subSwathIndex, burstIndex, clampedRect);

                // Apply deramp to source data
                final int bw = clampedRect.width;
                final int bh = clampedRect.height;
                final double[][] derampedI = new double[bh][bw];
                final double[][] derampedQ = new double[bh][bw];
                performDerampDemod(srcTileI, srcTileQ, clampedRect, derampDemodPhase, derampedI, derampedQ);

                // The deramped tile is interpolated as-is: after the TOPS azimuth
                // deramp the signal is baseband on BOTH axes — the range carrier
                // 4πR/λ is a per-scatterer constant living in the speckle, not a
                // sample-grid lattice term, so there is nothing to remove before
                // the kernel. (The former per-column "pre-flatten" injected an
                // aliased carrier — (4π·Δr/λ) mod 2π, ≈ −0.026 cyc/px for S1 but
                // −0.4989 for ERS — degrading sub-pixel interpolation. The
                // flattening phase is applied AFTER resampling, below.)
                final ArrayResamplingRaster rasterI = new ArrayResamplingRaster(derampedI, bw, bh);
                final ArrayResamplingRaster rasterQ = new ArrayResamplingRaster(derampedQ, bw, bh);

                // Resample each target pixel belonging to this burst
                for (int idx = 0; idx < numPixels; idx++) {
                    if (bestBurst[idx] != burstIndex) continue;

                    // Convert to local coordinates within the burst source rect
                    final double localRg = rangeIndices[idx] - clampedRect.x;
                    final double localAz = azimuthIndices[idx] - clampedRect.y;

                    imgResampling.computeCornerBasedIndex(localRg, localAz, bw, bh, resamplingIndex);

                    final double sampI = imgResampling.resample(rasterI, resamplingIndex);
                    final double sampQ = imgResampling.resample(rasterQ, resamplingIndex);

                    if (Double.isNaN(sampI) || Double.isNaN(sampQ)) {
                        if (ap.bufI != null) ap.bufI.setElemDoubleAt(idx, ap.noDataI);
                        if (ap.bufQ != null) ap.bufQ.setElemDoubleAt(idx, ap.noDataQ);
                        continue;
                    }

                    // The interpolated sample is in the deramped (carrier-free) azimuth domain.
                    // By default it STAYS there: the TOPS azimuth carrier is acquisition-specific
                    // (burst timing, FM rate) and does not cancel between two acquisitions —
                    // restoring it puts a per-burst quadratic azimuth phase (~150 rad/burst
                    // measured on a real S1A/S1D pair) into every cross-acquisition
                    // interferogram. Classical InSAR avoids this because Back-Geocoding resamples
                    // the secondary onto the reference's burst grid; independent per-scene
                    // geocoding cannot, so the carrier must be left off (as OPERA CSLC does).
                    double sampPhase = 0.0;
                    double convergentI = sampI;
                    double convergentQ = sampQ;

                    // The carrier is normally evaluated only when it is being restored. When the
                    // separable-terms bands are requested it must be evaluated regardless, so the
                    // band describes the term whether or not it was applied to the samples.
                    if (outputPhaseTerms && !outputAzimuthCarrier && pxCarrierPhase != null) {
                        pxCarrierPhase[idx] = computeDerampDemodPhaseAt(
                                subSwath, subSwathIndex, burstIndex,
                                rangeIndices[idx], azimuthIndices[idx]);
                    }
                    if (outputAzimuthCarrier) {
                        // §1c: reramp using the deramp+demod phase evaluated analytically
                        // at the fractional source position (not bisinc-interpolated from
                        // the phase grid, which has burst-edge bias from the modulo-2pi
                        // wraps).
                        sampPhase = computeDerampDemodPhaseAt(
                                subSwath, subSwathIndex, burstIndex,
                                rangeIndices[idx], azimuthIndices[idx]);
                        if (outputPhaseTerms && pxCarrierPhase != null) {
                            pxCarrierPhase[idx] = sampPhase;
                        }
                        final double cosReramp = FastMath.cos(sampPhase);
                        final double sinReramp = FastMath.sin(sampPhase);
                        convergentI = sampI * cosReramp + sampQ * sinReramp;
                        convergentQ = -sampI * sinReramp + sampQ * cosReramp;
                    }

                    // The kernel interpolated the raw deramped tile, so convergentI/Q is the
                    // natural (carrier-convention chosen above) SLC sample at the target
                    // position. When the flattened convention is requested, remove the
                    // topographic/ellipsoidal carrier AFTER the kernel by multiplying with
                    // exp(+j * 4 pi R / lambda) at the target's geometric slant range —
                    // applying it per source column BEFORE the kernel aliases the ramp
                    // ((4π·Δr/λ) mod 2π per pixel) and degrades sub-pixel interpolation.
                    if (outputFlattened) {
                        final double rangePhase = phaseConstant * slantRanges[idx];
                        final double cosPhi = FastMath.cos(rangePhase);
                        final double sinPhi = FastMath.sin(rangePhase);
                        final double[] flattened = new double[2];
                        multiplyByExpJPhi(convergentI, convergentQ, cosPhi, sinPhi, flattened);
                        convergentI = flattened[0];
                        convergentQ = flattened[1];
                    }

                    // Round-trip audit: with outputFlattened=false the deramp/reramp pair must
                    // cancel exactly, so at a target pixel whose source position is (near) an
                    // integer sample the output must equal the raw SLC sample in BOTH magnitude
                    // and phase. Log the individual terms wherever that holds so a non-closing
                    // term can be identified.
                    // (Audit only meaningful in the carrier-restored, non-flattened convention,
                    // where the output must equal the raw SLC sample bit-for-bit at integer
                    // source positions.)
                    if (DIAG_GEOMETRY && outputAzimuthCarrier && !outputFlattened
                            && diagRoundTripLogged.get() < 15) {
                        final double rgI = rangeIndices[idx], azI = azimuthIndices[idx];
                        if (Math.abs(rgI - Math.rint(rgI)) < 0.004 && Math.abs(azI - Math.rint(azI)) < 0.004) {
                            final int sx = (int) Math.rint(rgI), sy = (int) Math.rint(azI);
                            if (sx >= clampedRect.x && sx < clampedRect.x + bw
                                    && sy >= clampedRect.y && sy < clampedRect.y + bh) {
                                final double srcI = srcTileI.getSampleDouble(sx, sy);
                                final double srcQ = srcTileQ.getSampleDouble(sx, sy);
                                final double gridPhase = derampDemodPhase[sy - clampedRect.y][sx - clampedRect.x];
                                double d = Math.atan2(convergentQ, convergentI) - Math.atan2(srcQ, srcI);
                                d = Math.atan2(Math.sin(d), Math.cos(d));
                                SystemUtils.LOG.info(String.format(
                                        "GSLC-RT rg=%.4f az=%.4f |src|=%.2f |out|=%.2f  d(out-src)=%+.5f rad"
                                                + " | deramp=%.6f sampPhase=%.6f delta=%+.3e",
                                        rgI, azI, Math.hypot(srcI, srcQ), Math.hypot(convergentI, convergentQ),
                                        d, gridPhase, sampPhase, gridPhase - sampPhase));
                                diagRoundTripLogged.incrementAndGet();
                            }
                        }
                    }

                    if (ap.bufI != null) ap.bufI.setElemDoubleAt(idx, convergentI);
                    if (ap.bufQ != null) ap.bufQ.setElemDoubleAt(idx, convergentQ);
                }
            }
        }

        // Simulated phase bands, and the separable phase terms. This loop runs AFTER the resampling
        // section, which is where the carrier is evaluated — writing them earlier (next to the
        // geometry-solve diagnostics) emitted all zeros, because the accumulator was still empty.
        final ProductData bufCarrier = diagBuf(targetTiles, carrierPhaseBand);
        final ProductData bufFlatten = diagBuf(targetTiles, flatteningPhaseBand);
        for (int idx = 0; idx < numPixels; idx++) {
            if (bestBurst[idx] == -1) continue;
            final double phase = phaseConstant * slantRanges[idx];
            if (bufPhase != null) bufPhase.setElemDoubleAt(idx, Math.atan2(FastMath.sin(phase), FastMath.cos(phase)));
            if (bufUnwrappedPhase != null) bufUnwrappedPhase.setElemDoubleAt(idx, phase);
            if (bufFlatten != null) bufFlatten.setElemDoubleAt(idx, phase);
            if (bufCarrier != null && pxCarrierPhase != null) {
                bufCarrier.setElemDoubleAt(idx, pxCarrierPhase[idx]);
            }
        }
    }

    private void fillAllNoData(Map<Band, Tile> targetTiles) {
        fillTilesAsNoSource(targetTiles);
    }

    /**
     * Bail-out fill for tiles when no source data is available. Writes each
     * band's no-data value, except for the {@code layoverShadowMask} band which
     * gets {@link #MASK_NO_SOURCE} so downstream consumers see the audit trail.
     */
    private void fillTilesAsNoSource(Map<Band, Tile> targetTiles) {
        for (Map.Entry<Band, Tile> e : targetTiles.entrySet()) {
            final Band b = e.getKey();
            final Tile t = e.getValue();
            final ProductData data = t.getRawSamples();
            if ("layoverShadowMask".equals(b.getName())) {
                final int n = data.getNumElems();
                for (int i = 0; i < n; i++) data.setElemIntAt(i, MASK_NO_SOURCE);
            } else {
                final double noData = b.getNoDataValue();
                final int n = data.getNumElems();
                for (int i = 0; i < n; i++) data.setElemDoubleAt(i, noData);
            }
        }
    }

    /**
     * Apply a scalar azimuth coregistration offset to a TOPS subswath's burst-time references.
     * The TOPS azimuth index is derived from burst times (not firstLineUTC), so a positive
     * {@code azimuthOffsetPixels} must subtract {@code az * azimuthTimeInterval} seconds from each
     * burst's line-time references; the orbit->burst->lineWithinBurst mapping then samples the
     * source SLC at {@code originalRow + azimuthOffsetPixels} (matching the stripmap convention
     * in the firstLineUTC-shift block).
     */
    static void applyAzimuthOffsetToBurstTimes(final Sentinel1Utils.SubSwathInfo ss,
                                               final double azimuthOffsetPixels) {
        if (azimuthOffsetPixels == 0.0) return;
        final double azShiftSec = azimuthOffsetPixels * ss.azimuthTimeInterval;
        for (int i = 0; i < ss.numOfBursts; i++) {
            if (ss.burstFirstLineTime != null)      ss.burstFirstLineTime[i]      -= azShiftSec;
            if (ss.burstFirstValidLineTime != null) ss.burstFirstValidLineTime[i] -= azShiftSec;
            if (ss.burstLastValidLineTime != null)  ss.burstLastValidLineTime[i]  -= azShiftSec;
        }
    }

    static int selectBurst(double zeroDopplerTimeSec, Sentinel1Utils.SubSwathInfo ss) {
        return selectBurst(zeroDopplerTimeSec, ss, null);
    }

    /**
     * Determine which burst a pixel belongs to using valid line times. In the burst overlap the
     * split defaults to the midpoint rule (same as TOPSARDeburstOp) unless a locked boundary from
     * the reference acquisition is supplied ({@code overlapMidLock[k]} = boundary between bursts k
     * and k+1, this acquisition's time frame). The lock only partitions the true overlap — outside
     * it burst containment decides — so a locked boundary can never select a burst the pixel is
     * not valid in; if the transported boundary falls outside the physical overlap, the residual
     * mixed strip is simply as small as this acquisition's burst timing allows.
     */
    static int selectBurst(double zeroDopplerTimeSec, Sentinel1Utils.SubSwathInfo ss,
                           double[] overlapMidLock) {
        int firstBurst = -1;
        int secondBurst = -1;

        for (int i = 0; i < ss.numOfBursts; i++) {
            // Use valid line times (not full burst extent) to avoid including invalid edge samples
            if (zeroDopplerTimeSec >= ss.burstFirstValidLineTime[i] &&
                    zeroDopplerTimeSec <= ss.burstLastValidLineTime[i]) {
                if (firstBurst == -1) {
                    firstBurst = i;
                } else {
                    secondBurst = i;
                    break;
                }
            }
        }

        if (firstBurst == -1) return -1;
        if (secondBurst == -1) return firstBurst;

        // Overlap: locked boundary when supplied for this seam (NaN = unmatched seam), else
        // midpoint rule (same as TOPSARDeburstOp)
        final double lockedMid = (overlapMidLock != null && firstBurst < overlapMidLock.length)
                ? overlapMidLock[firstBurst] : Double.NaN;
        final double midTime = !Double.isNaN(lockedMid)
                ? lockedMid
                : (ss.burstLastValidLineTime[firstBurst] +
                        ss.burstFirstValidLineTime[secondBurst]) / 2.0;
        return (zeroDopplerTimeSec < midTime) ? firstBurst : secondBurst;
    }

    /**
     * Transport the REFERENCE acquisition's burst-overlap boundaries into this acquisition's time
     * frame. {@code refTable} is the reference GSLC's {@code gslc_burst_valid_times} stamp — per
     * burst either "burstId:firstLine:firstValid:lastValid" (4 fields, IDs available) or
     * "firstLine:firstValid:lastValid" (3 fields, legacy annotation without burst IDs), times in
     * seconds. For each seam the reference's own midpoint boundary m_ref = (lastValid_ref[j] +
     * firstValid_ref[j+1]) / 2 is shifted by the local time-axis offset Δ = mean of the two
     * matched bursts' SENSING start-time differences (this − ref, from firstLine, NOT firstValid):
     * burst sensing starts are synchronized between acquisitions of an interferometric pair to
     * ~ms, whereas the valid-line windows carry each processor's own trimming — the very asymmetry
     * that makes the two acquisitions' midpoint boundaries disagree in the first place. Any
     * base-time/convention difference between the two annotations also cancels in Δ.
     * <p>
     * Burst correspondence: when both sides carry track-anchored burst IDs, seam (k, k+1) of this
     * acquisition locks to the reference bursts with the SAME IDs — this handles TOPSAR-Splits
     * framing different burst windows (e.g. 10-vs-9 bursts), which index alignment cannot (the
     * candidate alignments differ by one burst cycle and timing alone cannot break the tie). Seams
     * whose IDs are absent from the reference stay NaN (= per-seam midpoint fallback in
     * {@link #selectBurst}). Without IDs on either side, index alignment is used and the burst
     * counts must match. Returns one boundary per seam, or null (with a warning) when the table is
     * unusable — the caller then falls back to the per-acquisition midpoint rule everywhere.
     */
    static double[] computeLockedOverlapMidpoints(final String refTable, final long[] idsThis,
                                                  final double[] flThis,
                                                  final double[] fvThis, final double[] lvThis) {
        final BurstLock lock = computeBurstLock(refTable, idsThis, flThis, fvThis, lvThis);
        return lock == null ? null : lock.overlapMid;
    }

    /**
     * The full burst-lock state derived from the reference table: the locked overlap boundaries
     * (see {@link #computeLockedOverlapMidpoints}), which of this acquisition's bursts exist in
     * the reference at all, and the reference's own burst-partition window transported into this
     * acquisition's time frame. Package-visible for tests.
     */
    static final class BurstLock {
        /** One boundary per seam of THIS acquisition; NaN = midpoint fallback (seam entirely
         *  outside the reference's coverage). */
        final double[] overlapMid;
        /** Per burst of THIS acquisition: does the reference frame the same ground burst?
         *  A pixel served from an unpairable burst can never form a coherent interferogram
         *  against the reference (adjacent bursts see disjoint Doppler bands). */
        final boolean[] pairable;
        /** The reference's pairable partition window, transported: before/after these times the
         *  reference either has no data or serves it from a burst this acquisition lacks. */
        final double refStartSod, refEndSod;
        /** Seams locked to a reference seam / edge seams pushed to the matched burst's extent. */
        final int matchedSeams, pushedSeams;
        /** Largest |locked − own-midpoint| over the matched seams, seconds. */
        final double maxBoundaryShiftSec;

        BurstLock(final double[] overlapMid, final boolean[] pairable,
                  final double refStartSod, final double refEndSod,
                  final int matchedSeams, final int pushedSeams,
                  final double maxBoundaryShiftSec) {
            this.overlapMid = overlapMid;
            this.pairable = pairable;
            this.refStartSod = refStartSod;
            this.refEndSod = refEndSod;
            this.matchedSeams = matchedSeams;
            this.pushedSeams = pushedSeams;
            this.maxBoundaryShiftSec = maxBoundaryShiftSec;
        }
    }

    /**
     * True when a sample from burst {@code burst} at zero-Doppler time {@code tSod} can pair
     * coherently with the reference acquisition: the burst must exist in the reference's table
     * and the time must fall inside the reference's own burst-partition window. Outside that
     * window the reference either has no data or — the extra-burst case — serves the ground from
     * a burst this acquisition lacks, so the pair would look through ~4 kHz-disjoint Doppler
     * bands and is inherently incoherent; such output is masked rather than emitted as
     * valid-looking garbage. No lock (null) means no masking.
     */
    static boolean isPairableWithReference(final BurstLock lock, final int burst,
                                           final double tSod) {
        if (lock == null) return true;
        if (burst >= 0 && burst < lock.pairable.length && !lock.pairable[burst]) return false;
        return tSod >= lock.refStartSod && tSod <= lock.refEndSod;
    }

    static BurstLock computeBurstLock(final String refTable, final long[] idsThis,
                                      final double[] flThis,
                                      final double[] fvThis, final double[] lvThis) {
        if (refTable == null || refTable.trim().isEmpty()
                || flThis == null || fvThis == null || lvThis == null) {
            return null;
        }
        try {
            final String[] rows = refTable.trim().split(",");
            final int nRef = rows.length;
            final int nThis = flThis.length;
            final long[] idsRef = new long[nRef];
            final double[] flRef = new double[nRef];
            final double[] fvRef = new double[nRef];
            final double[] lvRef = new double[nRef];
            boolean refHasIds = true;
            for (int i = 0; i < nRef; i++) {
                final String[] f = rows[i].trim().split(":");
                if (f.length != 4 && f.length != 3) {
                    throw new IllegalArgumentException(
                            "expected 'burstId:firstLine:firstValid:lastValid' or "
                                    + "'firstLine:firstValid:lastValid' per burst, got '" + rows[i] + "'");
                }
                final int t = f.length - 3;   // index of firstLine
                refHasIds &= f.length == 4;
                if (f.length == 4) {
                    idsRef[i] = Long.parseLong(f[0].trim());
                }
                flRef[i] = Double.parseDouble(f[t].trim());
                fvRef[i] = Double.parseDouble(f[t + 1].trim());
                lvRef[i] = Double.parseDouble(f[t + 2].trim());
            }

            final boolean byId = refHasIds && idsThis != null && idsThis.length == nThis;
            if (!byId && nRef != nThis) {
                SystemUtils.LOG.warning(String.format(
                        "GSLC TOPS: reference burst table has %d burst(s) but this product has %d, "
                                + "and burst IDs are unavailable to align them — burst-boundary "
                                + "lock skipped (differing TOPSAR-Split burst ranges?); falling "
                                + "back to the per-acquisition midpoint rule.",
                        nRef, nThis));
                return null;
            }

            // Per-burst correspondence: matchRef[k] = reference index framing the same ground
            // burst, -1 when the reference does not frame it.
            final int[] matchRef = new int[nThis];
            final boolean[] pairable = new boolean[nThis];
            int kFirst = -1, kLast = -1;
            for (int k = 0; k < nThis; k++) {
                matchRef[k] = -1;
                if (byId) {
                    for (int i = 0; i < nRef; i++) {
                        if (idsRef[i] == idsThis[k]) {
                            matchRef[k] = i;
                            break;
                        }
                    }
                } else {
                    matchRef[k] = k;
                }
                pairable[k] = matchRef[k] >= 0;
                if (pairable[k]) {
                    if (kFirst < 0) kFirst = k;
                    kLast = k;
                }
            }
            if (kFirst < 0) {
                SystemUtils.LOG.warning(
                        "GSLC TOPS: no common bursts between the reference table and this product "
                                + "(disjoint TOPSAR-Splits?) — burst-boundary lock skipped; falling "
                                + "back to the per-acquisition midpoint rule.");
                return null;
            }

            final double[] mid = new double[Math.max(0, nThis - 1)];
            int matched = 0;
            int pushed = 0;
            double maxShift = 0.0;
            for (int k = 0; k < nThis - 1; k++) {
                final int j = matchRef[k];
                final int j1 = matchRef[k + 1];
                if (j >= 0 && j1 == j + 1) {
                    // Both bursts framed by the reference as the same seam: transport its
                    // boundary (see the method javadoc above for the Δ convention).
                    final double mRef = (lvRef[j] + fvRef[j + 1]) / 2.0;
                    final double dk = ((flThis[k] - flRef[j]) + (flThis[k + 1] - flRef[j + 1])) / 2.0;
                    mid[k] = mRef + dk;
                    matched++;
                    final double ownMid = (lvThis[k] + fvThis[k + 1]) / 2.0;
                    maxShift = Math.max(maxShift, Math.abs(mid[k] - ownMid));
                } else if (j >= 0 && j1 < 0) {
                    // Edge seam: the reference has no seam here (its burst j continues), so the
                    // matched burst must be preferred through its whole valid extent — switching
                    // at the own midpoint would pair this acquisition's next burst against the
                    // reference's continuing one (disjoint Doppler => incoherent strip).
                    mid[k] = lvThis[k];
                    pushed++;
                } else if (j < 0 && j1 >= 0) {
                    mid[k] = fvThis[k + 1];
                    pushed++;
                } else {
                    mid[k] = Double.NaN;   // seam entirely outside the reference's coverage
                }
            }

            // The reference's own partition window, transported with the nearest matched burst's
            // sensing-start offset: where the reference has extra bursts beyond the common window
            // it switches to them at ITS midpoint rule (the reference is geocoded standalone), and
            // beyond that boundary — or beyond its coverage — nothing this acquisition emits can
            // pair with it.
            final int jFirst = matchRef[kFirst];
            final int jLast = matchRef[kLast];
            final double dFirst = flThis[kFirst] - flRef[jFirst];
            final double dLast = flThis[kLast] - flRef[jLast];
            final double refStart = (jFirst > 0
                    ? (lvRef[jFirst - 1] + fvRef[jFirst]) / 2.0
                    : fvRef[0]) + dFirst;
            final double refEnd = (jLast < nRef - 1
                    ? (lvRef[jLast] + fvRef[jLast + 1]) / 2.0
                    : lvRef[nRef - 1]) + dLast;

            return new BurstLock(mid, pairable, refStart, refEnd, matched, pushed, maxShift);
        } catch (Exception e) {
            SystemUtils.LOG.warning("GSLC TOPS: could not parse reference burst table ('" + refTable
                    + "'): " + e + " — burst-boundary lock skipped; falling back to the "
                    + "per-acquisition midpoint rule.");
            return null;
        }
    }

    /**
     * This subswath's burst time table, one row per burst: "burstId:firstLine:firstValid:lastValid"
     * when track-anchored burst IDs are available, else "firstLine:firstValid:lastValid" (seconds,
     * {@link Double#toString} round-trip precision) — the payload of the
     * {@code gslc_burst_valid_times} stamp consumed by {@link #computeLockedOverlapMidpoints}.
     */
    static String formatBurstValidTimes(final Sentinel1Utils.SubSwathInfo ss, final long[] ids) {
        return formatBurstValidTimes(ss, ids, null);
    }

    /**
     * Stamp variant carrying the EFFECTIVE partition: when this product was built with a
     * burst-boundary lock, its output switched bursts at the locked/pushed boundaries and was
     * masked to the pairable window — not at its own midpoints over its own span. A future stack
     * that uses this product as its reference reconstructs boundaries from the stamped
     * (lastValid[k] + firstValid[k+1]) / 2 midpoints and the fv[0]/lv[last] window, so the stamp
     * shifts each seam's fv/lv pair symmetrically to make the midpoint equal the effective
     * boundary (containment-clamped, NaN = own midpoint) and clamps the window ends to the
     * pairable window. Each fv/lv value participates in exactly one seam midpoint, so the shifts
     * are independent; a null lock stamps the plain table.
     */
    static String formatBurstValidTimes(final Sentinel1Utils.SubSwathInfo ss, final long[] ids,
                                        final BurstLock lock) {
        if (ss == null || ss.numOfBursts <= 0 || ss.burstFirstLineTime == null
                || ss.burstFirstValidLineTime == null || ss.burstLastValidLineTime == null) {
            return null;
        }
        final int n = ss.numOfBursts;
        final double[] fv = java.util.Arrays.copyOf(ss.burstFirstValidLineTime, n);
        final double[] lv = java.util.Arrays.copyOf(ss.burstLastValidLineTime, n);
        if (lock != null && lock.overlapMid != null) {
            for (int k = 0; k < n - 1 && k < lock.overlapMid.length; k++) {
                final double m = lock.overlapMid[k];
                if (Double.isNaN(m)) continue;
                // effective boundary as selectBurst resolves it: containment wins outside the overlap
                final double eff = Math.min(Math.max(m, fv[k + 1]), lv[k]);
                final double shift = eff - 0.5 * (lv[k] + fv[k + 1]);
                lv[k] += shift;
                fv[k + 1] += shift;
            }
            if (Double.isFinite(lock.refStartSod)) {
                fv[0] = Math.max(fv[0], lock.refStartSod);
            }
            if (Double.isFinite(lock.refEndSod)) {
                lv[n - 1] = Math.min(lv[n - 1], lock.refEndSod);
            }
        }
        final boolean withIds = ids != null && ids.length == n;
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            if (withIds) {
                sb.append(ids[i]).append(':');
            }
            sb.append(ss.burstFirstLineTime[i]).append(':')
                    .append(fv[i]).append(':')
                    .append(lv[i]);
        }
        return sb.toString();
    }

    /**
     * Check if a source pixel falls within the valid sample region of its burst.
     */
    static boolean isValidBurstSample(int burstIndex, double azimuthIndex, double rangeIndex,
                                               Sentinel1Utils.SubSwathInfo ss) {
        final int lineInBurst = (int) Math.round(azimuthIndex) - burstIndex * ss.linesPerBurst;
        if (lineInBurst < 0 || lineInBurst >= ss.linesPerBurst) return false;

        // Check valid line range
        if (lineInBurst < ss.firstValidLine[burstIndex] || lineInBurst > ss.lastValidLine[burstIndex]) {
            return false;
        }

        // Check valid sample range for this line
        final int sample = (int) Math.round(rangeIndex);
        final int firstValid = ss.firstValidSample[burstIndex][lineInBurst];
        final int lastValid = ss.lastValidSample[burstIndex][lineInBurst];
        return firstValid != -1 && sample >= firstValid && sample <= lastValid;
    }

    private Rectangle computeBurstSourceRectangle(int burstIndex, int[] bestBurst,
                                                   double[] azimuthIndices, double[] rangeIndices,
                                                   int w, int h, Sentinel1Utils.SubSwathInfo ss) {

        final int burstFirstLine = burstIndex * ss.linesPerBurst;
        final int burstLastLine = burstFirstLine + ss.linesPerBurst - 1;

        int xMin = Integer.MAX_VALUE, xMax = Integer.MIN_VALUE;
        int yMin = Integer.MAX_VALUE, yMax = Integer.MIN_VALUE;
        boolean found = false;

        for (int i = 0; i < w * h; i++) {
            if (bestBurst[i] != burstIndex) continue;
            found = true;
            int rg = (int) Math.floor(rangeIndices[i]);
            int az = (int) Math.floor(azimuthIndices[i]);
            xMin = Math.min(xMin, rg);
            xMax = Math.max(xMax, rg + 1);
            yMin = Math.min(yMin, az);
            yMax = Math.max(yMax, az + 1);
        }

        if (!found) return null;

        xMin = Math.max(xMin - margin, 0);
        xMax = Math.min(xMax + margin, sourceImageWidth - 1);
        yMin = Math.max(yMin - margin, burstFirstLine);
        yMax = Math.min(yMax + margin, burstLastLine);

        if (xMin > xMax || yMin > yMax) return null;
        return new Rectangle(xMin, yMin, xMax - xMin + 1, yMax - yMin + 1);
    }

    private static void performDerampDemod(final Tile tileI, final Tile tileQ,
                                            final Rectangle rectangle, final double[][] derampDemodPhase,
                                            final double[][] derampedI, final double[][] derampedQ) {

        final int x0 = rectangle.x;
        final int y0 = rectangle.y;
        final int xMax = x0 + rectangle.width;
        final int yMax = y0 + rectangle.height;

        // ProductData + TileIndex instead of per-pixel Tile.getSampleDouble: this is called
        // once per source-tile rectangle during resampling, so the inner-loop count is
        // (rectWidth × rectHeight × 2 bands) — typically tens of millions per tile.
        final ProductData dataI = tileI.getDataBuffer();
        final ProductData dataQ = tileQ.getDataBuffer();
        final TileIndex idx = new TileIndex(tileI);
        for (int y = y0; y < yMax; y++) {
            idx.calculateStride(y);
            final int yy = y - y0;
            for (int x = x0; x < xMax; x++) {
                final int xx = x - x0;
                final int srcIdx = idx.getIndex(x);
                final double valueI = dataI.getElemDoubleAt(srcIdx);
                final double valueQ = dataQ.getElemDoubleAt(srcIdx);
                final double cosPhase = FastMath.cos(derampDemodPhase[yy][xx]);
                final double sinPhase = FastMath.sin(derampDemodPhase[yy][xx]);
                derampedI[yy][xx] = valueI * cosPhase - valueQ * sinPhase;
                derampedQ[yy][xx] = valueI * sinPhase + valueQ * cosPhase;
            }
        }
    }

    private static class ArrayResamplingRaster implements Resampling.Raster {
        private final double[][] data;
        private final int width;
        private final int height;

        ArrayResamplingRaster(double[][] data, int width, int height) {
            this.data = data;
            this.width = width;
            this.height = height;
        }

        @Override public int getWidth() { return width; }
        @Override public int getHeight() { return height; }

        @Override
        public boolean getSamples(int[] x, int[] y, double[][] samples) {
            boolean allValid = true;
            for (int i = 0; i < y.length; i++) {
                for (int j = 0; j < x.length; j++) {
                    if (y[i] >= 0 && y[i] < height && x[j] >= 0 && x[j] < width) {
                        samples[i][j] = data[y[i]][x[j]];
                    } else {
                        samples[i][j] = Double.NaN;
                        allValid = false;
                    }
                }
            }
            return allValid;
        }
    }

    private Rectangle getSourceRectangle(final int x0, final int y0, final int w, final int h,
                                         final TileGeoreferencing tileGeoRef, final double[][] localDEM) {
        // Use a denser step to capture terrain effects
        final int step = 8;

        int xMax = Integer.MIN_VALUE;
        int xMin = Integer.MAX_VALUE;
        int yMax = Integer.MIN_VALUE;
        int yMin = Integer.MAX_VALUE;

        PositionData posData = new PositionData();
        GeoPos geoPos = new GeoPos();

        for (int y = y0; ; y += step) {
            boolean lastY = false;
            if (y >= y0 + h - 1) {
                y = y0 + h - 1;
                lastY = true;
            }

            for (int x = x0; ; x += step) {
                boolean lastX = false;
                if (x >= x0 + w - 1) {
                    x = x0 + w - 1;
                    lastX = true;
                }

                tileGeoRef.getGeoPos(x, y, geoPos);

                final double alt = localDEM[y - y0 + 1][x - x0 + 1];
                if (!Double.isNaN(alt) && alt != demNoDataValue) {
                    if (getPosition(geoPos.lat, geoPos.lon, alt, posData)) {
                        if (xMax < posData.rangeIndex) {
                            xMax = (int) Math.ceil(posData.rangeIndex);
                        }
                        if (xMin > posData.rangeIndex) {
                            xMin = (int) Math.floor(posData.rangeIndex);
                        }
                        if (yMax < posData.azimuthIndex) {
                            yMax = (int) Math.ceil(posData.azimuthIndex);
                        }
                        if (yMin > posData.azimuthIndex) {
                            yMin = (int) Math.floor(posData.azimuthIndex);
                        }
                    }
                }

                if (lastX) break;
            }
            if (lastY) break;
        }

        xMin = Math.max(xMin - margin, 0);
        xMax = Math.min(xMax + margin, sourceImageWidth - 1);
        yMin = Math.max(yMin - margin, 0);
        yMax = Math.min(yMax + margin, sourceImageHeight - 1);

        if (xMin > xMax || yMin > yMax) {
            return null;
        }
        return new Rectangle(xMin, yMin, xMax - xMin + 1, yMax - yMin + 1);
    }

    private int getMargin() {
        if (imgResampling == Resampling.BILINEAR_INTERPOLATION) {
            return 1;
        } else if (imgResampling == Resampling.NEAREST_NEIGHBOUR) {
            return 1;
        } else if (imgResampling == Resampling.CUBIC_CONVOLUTION) {
            return 2;
        } else if (imgResampling == Resampling.BISINC_5_POINT_INTERPOLATION) {
            return 3;
        } else if (imgResampling == Resampling.BISINC_11_POINT_INTERPOLATION) {
            return 6;
        } else if (imgResampling == Resampling.BISINC_21_POINT_INTERPOLATION) {
            return 11;
        } else if (imgResampling == Resampling.BICUBIC_INTERPOLATION) {
            return 2;
        } else {
            throw new OperatorException("Unhandled interpolation method");
        }
    }

    private boolean isValidCell(double x, double y) {
        return x >= margin && x < sourceImageWidth - margin && y >= margin && y < sourceImageHeight - margin;
    }

    /**
     * Linearly interpolate the residual Doppler centroid at a fractional source range
     * column. The two bracketing integer columns must be within
     * {@code [0, sourceImageWidth)}; out-of-range x clamps to the nearest valid edge.
     * Returns 0.0 if no Doppler profile is available — the caller treats this as
     * "no azimuth carrier to re-apply".
     */
    private double interpFdcAt(double xFrac) {
        if (fdcPerSourceColumn == null) return 0.0;
        if (xFrac <= 0.0) return fdcPerSourceColumn[0];
        if (xFrac >= sourceImageWidth - 1) return fdcPerSourceColumn[sourceImageWidth - 1];
        final int x0 = (int) Math.floor(xFrac);
        final double f = xFrac - x0;
        return fdcPerSourceColumn[x0] * (1.0 - f) + fdcPerSourceColumn[x0 + 1] * f;
    }

    private boolean getPosition(final double lat, final double lon, final double alt, final PositionData data) {
        GeoUtils.geo2xyzWGS84(lat, lon, alt, data.earthPoint);

        // §4 SET: displace the geocoded ground point by the IERS 2010 step-1
        // body-tide displacement at the scene's mid-time. This shifts BOTH the
        // computed slant range AND the source rangeIndex consistently — the
        // pixel the radar actually saw on this acquisition was displaced from
        // its un-tided lat/lon by ~cm. Differential SET between paired
        // acquisitions (typically a few mm) is the InSAR-relevant residual.
        if (applySolidEarthTide) {
            final double[] dEcef = SolidEarthTide.computeEcefDisplacement(
                    sceneMidTimeMjdUtc, lat, lon, alt);
            data.earthPoint.x += dEcef[0];
            data.earthPoint.y += dEcef[1];
            data.earthPoint.z += dEcef[2];
        }

        double zeroDopplerTime = SARGeocoding.getEarthPointZeroDopplerTime(firstLineUTC,
                lineTimeInterval, wavelength, data.earthPoint, orbit.sensorPosition, orbit.sensorVelocity);

        if (Double.compare(zeroDopplerTime, SARGeocoding.NonValidZeroDopplerTime) == 0) {
            return false;
        }

        data.slantRange = SARGeocoding.computeSlantRangeFast(orbit, firstLineUTC, lineTimeInterval,
                zeroDopplerTime, data.earthPoint, data.sensorPos);

        if (!skipBistaticCorrection) {
            // Full bistatic correction: product has no bulk correction applied
            zeroDopplerTime += data.slantRange / Constants.lightSpeedInMetersPerDay;
            data.slantRange = SARGeocoding.computeSlantRangeFast(orbit, firstLineUTC, lineTimeInterval,
                    zeroDopplerTime, data.earthPoint, data.sensorPos);
        } else if (bistaticCorrectionRefRange > 0.0) {
            // Bistatic residual correction (Section 4.7.3 of UZH-S1-GC-AD v1.12):
            // IPF applied bulk correction using a reference range; apply the range-dependent residual.
            zeroDopplerTime += (data.slantRange - bistaticCorrectionRefRange) / Constants.lightSpeedInMetersPerDay;
            data.slantRange = SARGeocoding.computeSlantRangeFast(orbit, firstLineUTC, lineTimeInterval,
                    zeroDopplerTime, data.earthPoint, data.sensorPos);
        }

        data.rangeIndex = SARGeocoding.computeRangeIndex(srgrFlag, sourceImageWidth, firstLineUTC, lastLineUTC,
                rangeSpacing, zeroDopplerTime, data.slantRange, nearEdgeSlantRange, srgrConvParams);

        if (data.rangeIndex == -1.0) {
            return false;
        }

        if (!nearRangeOnLeft) {
            data.rangeIndex = sourceImageWidth - 1 - data.rangeIndex;
        }

        data.azimuthIndex = (zeroDopplerTime - firstLineUTC) / lineTimeInterval;

        // §4 troposphere: apply a one-way Saastamoinen dry zenith path delay
        // to the slant range used for the OUTPUT phase. The source rangeIndex
        // is left untouched (the SLC was sampled in apparent-range; correcting
        // it would shift the resampling lookup by ~1 pixel and degrade fidelity).
        // This affects the carrier-phase term only, which is the InSAR-relevant
        // quantity.
        if (applyTroposphericCorrection) {
            final double tropoDelay = SARGeocoding.computeAtmosphericPathDelay(
                    data.earthPoint, data.sensorPos, lat, alt);
            data.slantRange += tropoDelay;
        }
        return true;
    }

    private static class PositionData {
        final PosVector earthPoint = new PosVector();
        final PosVector sensorPos = new PosVector();
        double azimuthIndex;
        double rangeIndex;
        double slantRange;
    }

    /**
     * Complex-aware {@link Resampling.Raster} for the SM path. Serves raw baseband
     * i/q samples to the sinc kernel, with only the azimuth Doppler-centroid deramp
     * applied per sample.
     * <p>
     * The RANGE carrier is deliberately NOT touched here: a focused SLC's range
     * spectrum is baseband — the absolute-range phase {@code 4πR/λ} of each target
     * is a per-scatterer constant living in the speckle, not a lattice carrier on
     * the sample grid. Multiplying each column by {@code exp(+j·4πR(x)/λ)} before
     * interpolation (the former "pre-flatten") injects a coherent carrier at the
     * aliased frequency {@code (4π·Δr/λ) mod 2π}, which for ERS is −0.4989
     * cycles/pixel (≈ Nyquist) and destroys sub-pixel interpolation (measured on
     * real ERS-1 SLC data: 5-pt sinc at mu=0.5 achieves γ=0.997 on raw baseband
     * i/q vs γ=0.093 pre-flattened). The flattening phase, when requested, is
     * applied AFTER the kernel at the target's geometric slant range (see the SM
     * tile loop). Guarded by {@code GSLCComplexResamplingFidelityTest}.
     */
    private static class GSLCResamplingRaster implements Resampling.Raster {
        private final Tile sourceTileI;
        private final Tile sourceTileQ;
        private final double noDataValue;
        private boolean returnReal;
        private final int sourceWidth;
        private final int sourceHeight;

        /**
         * Residual Doppler centroid per source range column (Hz), or {@code null} if
         * the metadata had none. When non-null, the resampler subtracts
         * {@code 2π·f_dc(x_j)·y_i·lineTimeIntervalSec} from each sample's
         * deramp phase, bringing the azimuth signal to baseband before sinc interpolation.
         * (Unlike the range carrier, the azimuth spectrum genuinely IS centred on
         * f_dc — the deramp is required physics; verified against annotated ERS
         * azimuth spectra, centroid −0.188 cyc/px matching the annotation.)
         */
        private final double[] fdcPerSourceColumn;
        private final double lineTimeIntervalSec;

        // Cache fields. getSamples is invoked twice per output pixel (real pass then
        // imaginary pass) with the identical kernel window; the served samples depend
        // only on the window itself, so the cache keys on the window origin. Adjacent
        // target pixels that resolve to the same source window also hit.
        private int lastX0 = Integer.MIN_VALUE;
        private int lastY0 = Integer.MIN_VALUE;
        private double[][] cachedI;
        private double[][] cachedQ;
        private boolean lastAllValid;

        public GSLCResamplingRaster(Tile sourceTileI, Tile sourceTileQ,
                                    int sourceWidth, int sourceHeight,
                                    double[] fdcPerSourceColumn,
                                    double lineTimeIntervalSec) {
            this.sourceTileI = sourceTileI;
            this.sourceTileQ = sourceTileQ;
            this.noDataValue = sourceTileI.getRasterDataNode().getNoDataValue();
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.fdcPerSourceColumn = fdcPerSourceColumn;
            this.lineTimeIntervalSec = lineTimeIntervalSec;
        }

        public double getNoDataValue() {
            return noDataValue;
        }

        public void setReturnReal(boolean returnReal) {
            this.returnReal = returnReal;
        }

        @Override
        public int getWidth() {
            return sourceWidth;
        }

        @Override
        public int getHeight() {
            return sourceHeight;
        }

        @Override
        public boolean getSamples(int[] x, int[] y, double[][] samples) {
            // Serve from cache when the kernel window is unchanged (always the case
            // for the second, imaginary-pass call of the same output pixel).
            if (cachedI != null && lastX0 == x[0] && lastY0 == y[0]
                    && cachedI.length == y.length && cachedI[0].length == x.length) {
                double[][] source = returnReal ? cachedI : cachedQ;
                for (int i = 0; i < y.length; i++) {
                    System.arraycopy(source[i], 0, samples[i], 0, x.length);
                }
                return lastAllValid;
            }

            boolean allValid = true;
            Rectangle rect = sourceTileI.getRectangle();

            // Ensure cache is allocated
            if (cachedI == null || cachedI.length != y.length || cachedI[0].length != x.length) {
                cachedI = new double[y.length][x.length];
                cachedQ = new double[y.length][x.length];
            }

            final int rxMin = rect.x;
            final int rxMax = rect.x + rect.width - 1;
            final int ryMin = rect.y;
            final int ryMax = rect.y + rect.height - 1;

            // Per-sample azimuth deramp (Yague-Martinez 2016 §III, ISCE3 geocodeSlc):
            //   SLC(eta, r) = a(eta,r) · exp(-j·4π·R(r)/λ) · exp(+j·2π·f_dc(r)·eta·dt)
            // The exp(-j·4πR/λ) term is a constant per scatterer (baseband in range —
            // do NOT touch it before the kernel); the azimuth carrier is removed per
            // sample by multiplying with exp(−j·phi_az), phi_az = 2π·f_dc(x_j)·y_i·dt,
            // and re-applied at the target position after resampling.
            final boolean derampAz = (fdcPerSourceColumn != null);
            final double azPhaseScale = 2.0 * Math.PI * lineTimeIntervalSec;

            // Resampling.Raster hot path: called once per output pixel during sinc
            // interpolation, each call reads kernelSize² samples. Switch from per-pixel
            // Tile.getSampleDouble (sample-model lookup + virtual dispatch per sample) to
            // ProductData buffer + TileIndex stride math — ~5-10× faster per kernel.
            final ProductData srcDataI = sourceTileI.getDataBuffer();
            final ProductData srcDataQ = sourceTileQ.getDataBuffer();
            final TileIndex srcIndex = new TileIndex(sourceTileI);
            for (int i = 0; i < y.length; i++) {
                final int yi = y[i];
                final boolean yInBounds = (yi >= ryMin && yi <= ryMax);
                if (yInBounds) {
                    srcIndex.calculateStride(yi);
                }

                for (int j = 0; j < x.length; j++) {
                    final int xj = x[j];

                    if (!yInBounds || xj < rxMin || xj > rxMax) {
                        cachedI[i][j] = noDataValue;
                        cachedQ[i][j] = noDataValue;
                        allValid = false;
                    } else {
                        final int srcIdx = srcIndex.getIndex(xj);
                        final double iVal = srcDataI.getElemDoubleAt(srcIdx);
                        final double qVal = srcDataQ.getElemDoubleAt(srcIdx);

                        if (iVal == noDataValue || qVal == noDataValue) {
                            cachedI[i][j] = noDataValue;
                            cachedQ[i][j] = noDataValue;
                            allValid = false;
                        } else if (derampAz && xj >= 0 && xj < fdcPerSourceColumn.length) {
                            // (I + jQ) · exp(−j·phi_az)
                            //   = (I·cos + Q·sin) + j(Q·cos − I·sin)
                            final double phiAz = azPhaseScale * fdcPerSourceColumn[xj] * yi;
                            final double cosAz = FastMath.cos(phiAz);
                            final double sinAz = FastMath.sin(phiAz);
                            cachedI[i][j] = iVal * cosAz + qVal * sinAz;
                            cachedQ[i][j] = qVal * cosAz - iVal * sinAz;
                        } else {
                            cachedI[i][j] = iVal;
                            cachedQ[i][j] = qVal;
                        }
                    }
                }
            }

            lastX0 = x[0];
            lastY0 = y[0];
            lastAllValid = allValid;

            // Copy to output
            double[][] source = returnReal ? cachedI : cachedQ;
            for (int i = 0; i < y.length; i++) {
                System.arraycopy(source[i], 0, samples[i], 0, x.length);
            }

            return allValid;
        }
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(GSLCGeocodingOp.class);
        }
    }
}
