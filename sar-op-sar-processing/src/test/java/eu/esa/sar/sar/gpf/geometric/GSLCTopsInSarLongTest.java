package eu.esa.sar.sar.gpf.geometric;

import com.bc.ceres.test.LongTestRunner;
import eu.esa.sar.commons.Sentinel1Utils;
import eu.esa.sar.commons.test.ProcessorTest;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.GeoCoding;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.jlinda.core.Ellipsoid;
import org.jlinda.core.Orbit;
import org.jlinda.core.Point;
import org.jlinda.core.SLCImage;
import org.junit.AfterClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.media.jai.JAI;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Task 1.3 Layer-2 TOPS leg contract tests, file-gated on a real S1 IW3 2-burst fixture:
 * {@link #testGeometryContract_TopsS1Fixture()} and {@link #testFaithfulPhase_TopsS1Fixture()}.
 * Reuses the Task 1.1/1.2 helpers from {@link GSLCGeometryContractTest} and the
 * {@code ers_faithful.py}-derived method in {@link GSLCFaithfulPhaseTest} (extended here for the
 * carrier-free TOPS deramp/reramp).
 * <p>
 * Split out from {@link GSLCTopsInSarTest} (which keeps the fast, deterministic Layer-1 synthetic
 * unit tests) and gated with {@link LongTestRunner} per the project's long-test convention
 * (`-Denable.long.tests=true`): the tests here need a full, un-subset TOPS GSLC (built once and
 * shared across the class — see {@code sharedGslc()}) plus one locked GSLC, far too heavy for
 * every plain {@code mvn test} run of this module even when the fixture happens to be present. {@code LongTestRunner} gates at the class level only
 * (see {@code com.bc.ceres.test.LongTestRunner}), so the fast Layer-1 tests could not stay in the
 * same class without also being gated — hence the split.
 */
@RunWith(LongTestRunner.class)
public class GSLCTopsInSarLongTest extends ProcessorTest {

    private final static OperatorSpi spi = new GSLCGeocodingOp.Spi();

    /** S1 IW3 2-burst split+orbit fixture (Venezuela pair); rebuildable via the
     *  TOPSAR-Split(IW3, VV, bursts 4-5) + Apply-Orbit-File recipe documented in project memory. */
    private final static File mFile = new File("E:/Output/gslcdiag/m.dim");
    private final static File sFile = new File("E:/Output/gslcdiag/s.dim");

    private static final int MIN_VALID_RASTER_PIXELS = 1000;
    private static final double RG_FRAC_TOL = 0.02;
    private static final double AZ_FRAC_TOL = 0.02;
    private static final int MIN_CANDIDATES = 200;
    private static final int MIN_PER_BURST_CANDIDATES = 200;
    private static final double AZ_OFFSET_MIN = 0.0;
    private static final double AZ_OFFSET_MAX = 8.0;
    private static final double AZ_OFFSET_STEP = 0.25;
    private static final int NUM_COLUMN_BINS = 6;

    /**
     * Shared master GSLC (and its source product), built ONCE with the union of the parameters
     * the tests need — BISINC resampling plus all three diagnostic bands — and reused by every
     * test in this class. Each GSLC build geocodes the full un-subset 2-burst scene (the
     * dominant cost of this class); before sharing, the three tests rebuilt an identical
     * product four times. The extra diagnostic bands are pull-computed, so tests that never
     * read them pay nothing; the I/Q samples are unaffected by diagnostic-band settings.
     * Built lazily (not in @BeforeClass) so the per-test file-gating assumes run first;
     * disposed once in {@link #disposeSharedProducts()}.
     */
    private static Product sharedSrc = null;
    private static Product sharedGslc = null;

    private static synchronized Product sharedGslc() throws Exception {
        if (sharedGslc == null) {
            sharedSrc = TestUtils.readSourceProduct(mFile);
            final GSLCGeocodingOp op = (GSLCGeocodingOp) spi.createOperator();
            op.setSourceProduct(sharedSrc);
            op.setParameter("demName", "Copernicus 30m Global DEM");
            op.setParameter("imgResamplingMethod", "BISINC_5_POINT_INTERPOLATION");
            op.setParameter("outputFlattened", false);
            op.setParameter("nodataValueAtSea", false);
            op.setParameter("saveSimulatedUnwrappedPhase", true);
            op.setParameter("saveDEM", true);
            op.setParameter("outputPhaseTerms", true);
            sharedGslc = op.getTargetProduct();
        }
        return sharedGslc;
    }

    @AfterClass
    public static void disposeSharedProducts() {
        if (sharedGslc != null) {
            sharedGslc.dispose();
            sharedGslc = null;
        }
        if (sharedSrc != null) {
            sharedSrc.dispose();
            sharedSrc = null;
        }
    }

    /**
     * Geometry drift contract on a real TOPS product (carrier-free default): reuses
     * {@link GSLCGeometryContractTest#measureGeometryDrift} unchanged — {@code simulatedUnwrappedPhase}
     * is float64 {@code 4*pi*R/lambda} on TOPS too, independent of the azimuth-carrier convention.
     * Per the plan, a TOPS split product is geocoded WHOLE (not subset — deburst metadata is
     * sensitive to range/azimuth cropping).
     */
    @Test
    public void testGeometryContract_TopsS1Fixture() throws Exception {
        assumeTrue(mFile + " not found", mFile.exists());
        assumeTrue(sFile + " not found", sFile.exists());

        final Product gslc = sharedGslc();
        final MetadataElement srcAbs = AbstractMetadata.getAbstractedMetadata(sharedSrc);

        final int nValidRaster = GSLCGeometryContractTest.countValidSimPhasePixels(
                gslc, MIN_VALID_RASTER_PIXELS);
        assumeTrue("TOPS fixture geocoded too few valid simulatedUnwrappedPhase pixels (" +
                        nValidRaster + ") — DEM unavailable in this environment?",
                nValidRaster >= MIN_VALID_RASTER_PIXELS);

        final double[] r = GSLCGeometryContractTest.measureGeometryDrift(gslc, srcAbs);
        System.out.printf(
                "GSLCTopsInSarLongTest[geometryContract]: meanResidual=%.4f m, columnDrift=%.6f m, " +
                        "rowDrift=%.6f m%n", r[0], r[1], r[2]);

        assertTrue("mean R_jlinda-R_snap residual should be sanity-bounded (a constant offset " +
                        "up to a few metres is allowed); got " + r[0],
                Math.abs(r[0]) < 5.0);
        assertTrue("TOPS R drift across scene must stay below 1 cm; got columnDrift=" + r[1] +
                        " rowDrift=" + r[2],
                Math.abs(r[1]) < 0.01 && Math.abs(r[2]) < 0.01);
    }

    /**
     * Faithful-phase contract on a real TOPS product. Unlike stripmap, the default carrier-free
     * TOPS output is DERAMPED, so the direct {@code GSLC*conj(SLC)} comparison needs the (inverse)
     * deramp model phase (the {@code azimuthCarrierPhase} diagnostic band, filled when the GSLC is
     * built with {@code outputPhaseTerms=true}) re-applied before comparing against the raw source
     * SLC sample: {@code GSLC(P)*exp(-j*azimuthCarrierPhase(P))*conj(SLC[az,rg])}
     * — see {@link #evaluateTopsPhasor} for the sign derivation. Candidates additionally require a
     * near-integer AZIMUTH position (not just range): the deramp phase is steep, so at a
     * non-integer azimuth the BISINC-resampled value is a genuine blend across several source rows,
     * not a stand-in for any single raw sample.
     */
    @Test
    public void testFaithfulPhase_TopsS1Fixture() throws Exception {
        assumeTrue(mFile + " not found", mFile.exists());
        assumeTrue(sFile + " not found", sFile.exists());

        final Product gslc = sharedGslc();
        final int nValidRaster = GSLCGeometryContractTest.countValidSimPhasePixels(
                gslc, MIN_VALID_RASTER_PIXELS);
        assumeTrue("TOPS fixture geocoded too few valid simulatedUnwrappedPhase pixels (" +
                        nValidRaster + ") — DEM unavailable in this environment?",
                nValidRaster >= MIN_VALID_RASTER_PIXELS);

        final TopsFaithfulStats stats = computeTopsFaithfulStats(gslc, sharedSrc);
        System.out.printf(
                "GSLCTopsInSarLongTest[faithfulPhase]: conc=%.4f, bestAzOffset=%.2f, nCandidates=%d, " +
                        "columnBinTrend=%.4f rad%n",
                stats.concentration, stats.bestAzOffset, stats.nCandidates, stats.columnBinTrend);

        // Printed UNCONDITIONALLY (not just on failure): this is also the proof that the
        // candidate set genuinely spans both bursts, not just a narrow along-track band.
        System.out.println("GSLCTopsInSarLongTest[faithfulPhase]: per-burst breakdown:");
        for (int b = 0; b < stats.burstConcentration.length; b++) {
            System.out.printf("  burst %d: concentration=%.4f (n=%d)%n",
                    b, stats.burstConcentration[b], stats.burstCount[b]);
        }

        for (int b = 0; b < stats.burstCount.length; b++) {
            assertTrue("burst " + b + " contributed only " + stats.burstCount[b] +
                            " candidates (< " + MIN_PER_BURST_CANDIDATES + ") — candidate collection " +
                            "did not genuinely cover both bursts of the 2-burst scene",
                    stats.burstCount[b] >= MIN_PER_BURST_CANDIDATES);
        }

        assertTrue("TOPS faithful concentration " + stats.concentration, stats.concentration > 0.7);
        assertTrue("per-column-bin phase trend across swath " + stats.columnBinTrend + " rad",
                Math.abs(stats.columnBinTrend) < 0.5);
    }

    private static final class TopsCandidate {
        final int x;
        final double rg;
        final double frac;
        final double az;
        final double carrierPhase;
        final double gI;
        final double gQ;

        TopsCandidate(final int x, final double rg, final double frac, final double az,
                      final double carrierPhase, final double gI, final double gQ) {
            this.x = x;
            this.rg = rg;
            this.frac = frac;
            this.az = az;
            this.carrierPhase = carrierPhase;
            this.gI = gI;
            this.gQ = gQ;
        }
    }

    private static final class TopsFaithfulStats {
        final double concentration;
        final double bestAzOffset;
        final int nCandidates;
        final double columnBinTrend;
        final double[] burstConcentration;
        final int[] burstCount;

        TopsFaithfulStats(final double concentration, final double bestAzOffset, final int nCandidates,
                           final double columnBinTrend, final double[] burstConcentration,
                           final int[] burstCount) {
            this.concentration = concentration;
            this.bestAzOffset = bestAzOffset;
            this.nCandidates = nCandidates;
            this.columnBinTrend = columnBinTrend;
            this.burstConcentration = burstConcentration;
            this.burstCount = burstCount;
        }
    }

    /**
     * TOPS-local variant of {@link GSLCFaithfulPhaseTest#computeFaithfulStats}: same
     * range-candidate selection / azimuth-offset scan / column-bin-trend method, but re-applies
     * the TOPS deramp model (the {@code azimuthCarrierPhase} band) to the GSLC sample before
     * comparing it against the raw source SLC sample, and additionally drops candidates whose
     * carrier phase is exactly 0 (nodata/unfilled — burst-overlap pixels can otherwise map
     * ambiguously) and bins a per-burst concentration breakdown for diagnostics.
     */
    private static TopsFaithfulStats computeTopsFaithfulStats(final Product gslc, final Product srcSlc)
            throws Exception {
        final MetadataElement srcAbs = AbstractMetadata.getAbstractedMetadata(srcSlc);

        double freq = AbstractMetadata.getAttributeDouble(srcAbs, AbstractMetadata.radar_frequency);
        if (freq < 1.0e8) { // metadata sometimes stores MHz
            freq *= 1.0e6;
        }
        final double wavelength = Constants.lightSpeed / freq;
        final double nearEdgeSlantRange = AbstractMetadata.getAttributeDouble(
                srcAbs, AbstractMetadata.slant_range_to_first_pixel);
        final double rangeSpacing = AbstractMetadata.getAttributeDouble(srcAbs, AbstractMetadata.range_spacing);

        final SLCImage slcImage = new SLCImage(srcAbs, null);
        final Orbit orbit = new Orbit(srcAbs, 3);

        // Mirror the production azimuth solve's bistatic handling (GSLCGeocodingOp.getPosition +
        // resolveBistaticCorrectionRefRange): the operator shifts its source azimuth by the
        // range-dependent bistatic term — on an IPF-bulk-corrected S1 product the residual
        // (R - R_near)/c, 0 → ~0.08 lines across the sub-swath. jlinda's xyz2t is a pure
        // zero-Doppler solve without it, so the test's predicted azimuth must add the same term
        // or every candidate compares the GSLC sample against a raw row up to that far away —
        // at the TOPS deramp slope (tens of rad/line near burst edges) that alone drags the
        // concentration from ~0.95 to ~0.6 with a range-dependent column-bin trend.
        final boolean bulkBistaticApplied =
                srcAbs.getAttributeInt(AbstractMetadata.bistatic_correction_applied, 0) == 1;
        final double bistaticRefRange = GSLCGeocodingOp.resolveBistaticCorrectionRefRange(
                bulkBistaticApplied,
                RangeDopplerGeocodingOp.getMissionType(srcAbs),
                nearEdgeSlantRange,
                srcAbs.getAttributeInt(GSLCGeocodingOp.ETAD_AZIMUTH_APPLIED, 0) == 1);

        final Band simPhaseBand = gslc.getBand("simulatedUnwrappedPhase");
        final Band elevBand = gslc.getBand("elevation");
        final Band carrierBand = gslc.getBand("azimuthCarrierPhase");
        if (simPhaseBand == null || elevBand == null || carrierBand == null) {
            throw new IllegalStateException("GSLC product must contain simulatedUnwrappedPhase, elevation " +
                    "and azimuthCarrierPhase bands (saveSimulatedUnwrappedPhase=true, saveDEM=true, " +
                    "outputPhaseTerms=true)");
        }
        final Band[] gslcIQ = findComplexPair(gslc);
        final Band[] srcIQ = findComplexPair(srcSlc);

        final GeoCoding geoCoding = gslc.getSceneGeoCoding();
        if (geoCoding == null) {
            throw new IllegalStateException("GSLC product has no scene geo-coding");
        }

        final int w = gslc.getSceneRasterWidth();
        final int h = gslc.getSceneRasterHeight();
        final int srcW = srcSlc.getSceneRasterWidth();
        final int srcH = srcSlc.getSceneRasterHeight();

        // Source SLC: a plain (non-geocoded) product, safe to bulk-read; float halves the memory
        // footprint versus double (amplitude precision is not the bottleneck — the range/azimuth
        // solve below only needs simulatedUnwrappedPhase, which stays float64).
        final float[] srcIArr = new float[srcW * srcH];
        srcIQ[0].readPixels(0, 0, srcW, srcH, srcIArr);
        final float[] srcQArr = new float[srcW * srcH];
        srcIQ[1].readPixels(0, 0, srcW, srcH, srcQArr);

        // Burst boundaries — needed for BOTH the per-burst breakdown AND the correct azimuth line
        // mapping below (bursts overlap in TIME but not in line-index space, so a naive continuous
        // time-to-line conversion silently gives the wrong global line for any burst after the
        // first; see the day-offset comment at its use site).
        final Sentinel1Utils su = new Sentinel1Utils(srcSlc);
        final Sentinel1Utils.SubSwathInfo ss = su.getSubSwath()[0];
        final int numOfBursts = ss.numOfBursts;
        final int linesPerBurst = ss.linesPerBurst;
        final double dayOffsetSec = Math.floor(ss.burstFirstLineTime[0] / Constants.secondsInDay)
                * Constants.secondsInDay;

        final List<TopsCandidate> candidates = new ArrayList<>();
        final GeoPos geoPos = new GeoPos();
        final PixelPos pixelPos = new PixelPos();

        // Bound JAI's tile cache ONCE (rather than flushing it after every sample): flushing
        // discards shared upstream nodes too (DEM tiles, orbit interpolation tables) that legitimately
        // get reused across nearby samples, forcing them to be recomputed/re-fetched from scratch
        // every time — measured to turn an ~90 s scan into one that had not finished after 80+
        // minutes. A bounded capacity lets JAI's own LRU eviction reclaim memory from tiles that
        // are genuinely no longer needed while still reusing what is.
        final long previousTileCacheCapacity = JAI.getDefaultInstance().getTileCache().getMemoryCapacity();
        JAI.getDefaultInstance().getTileCache().setMemoryCapacity(768L * 1024 * 1024);
        try {

        // The GSLC-side bands are read via a STRIDE of small windows spread evenly across the FULL
        // raster height, rather than either (a) one whole-raster call — a single
        // readPixels(0,0,w,h,...) forces JAI to materialise the entire geocoded image as ONE
        // contiguous tile on top of the geocoding operator's own working set, exceeding the test
        // heap even though the resulting array itself would easily fit — or (b) scanning EVERY
        // contiguous row-block top-down, which was tried first and measured two ways: an early
        // break out of the whole scan only ever sampled a narrow along-track band at one end of
        // the scene (cannot prove both-burst coverage), while scanning every block in full (with a
        // per-block candidate quota to bound memory) forces JAI to compute literally the entire
        // 23M-pixel x 5-band raster regardless of how few candidates are kept from each block —
        // measured at over 80 minutes wall-clock with no sign of finishing. Striding a handful of
        // SMALL windows spread evenly from top to bottom touches only a fraction of the raster
        // while still genuinely spanning the full along-track (both-burst) extent, which the
        // per-burst floor assertion below then verifies.
        final int numSamples = 16;
        final int sampleRows = Math.max(1, Math.min(h, 60));
        final double[] simPhaseBlock = new double[w * sampleRows];
        final double[] elevBlock = new double[w * sampleRows];
        final double[] carrierBlock = new double[w * sampleRows];
        final double[] gslcIBlock = new double[w * sampleRows];
        final double[] gslcQBlock = new double[w * sampleRows];

        // Cap total candidates (a robust multiple of MIN_CANDIDATES, not a bare threshold), spread
        // evenly across the sampled windows via a labelled break out of just that window's pixel
        // loop — same rationale as the row-block quota above, just applied per SAMPLE instead of
        // per contiguous block.
        final int candidateCap = MIN_CANDIDATES * 10;
        final int perSampleQuota = Math.max(1, (candidateCap + numSamples - 1) / numSamples);
        final int maxY0 = Math.max(0, h - sampleRows);

        for (int s = 0; s < numSamples; s++) {
            final int y0 = (numSamples <= 1) ? 0 : (int) Math.round((double) s * maxY0 / (numSamples - 1));
            final int bh = Math.min(sampleRows, h - y0);
            simPhaseBand.readPixels(0, y0, w, bh, simPhaseBlock);
            elevBand.readPixels(0, y0, w, bh, elevBlock);
            carrierBand.readPixels(0, y0, w, bh, carrierBlock);
            gslcIQ[0].readPixels(0, y0, w, bh, gslcIBlock);
            gslcIQ[1].readPixels(0, y0, w, bh, gslcQBlock);

            int blockCandidates = 0;
            blockScan:
            for (int ly = 0; ly < bh; ly++) {
                final int y = y0 + ly;
                for (int x = 0; x < w; x++) {
                    final int lidx = ly * w + x;
                    final double simPhase = simPhaseBlock[lidx];
                    if (simPhase == 0.0 || !Double.isFinite(simPhase)) {
                        continue;
                    }
                    final double elevation = elevBlock[lidx];
                    if (Double.isNaN(elevation)) {
                        continue;
                    }
                    final double carrierPhase = carrierBlock[lidx];
                    if (carrierPhase == 0.0) {
                        // Nodata/unfilled diagnostic band, or a burst-overlap pixel whose selection
                        // is ambiguous between two disjoint-Doppler bursts — drop either way.
                        continue;
                    }

                    final double R = simPhase * wavelength / (4.0 * Math.PI);
                    final double rg = (R - nearEdgeSlantRange) / rangeSpacing;
                    final double frac = rg - Math.round(rg);
                    if (Math.abs(frac) >= RG_FRAC_TOL) {
                        continue;
                    }

                    pixelPos.setLocation(x + 0.5, y + 0.5);
                    geoCoding.getGeoPos(pixelPos, geoPos);
                    final Point xyz = Ellipsoid.ell2xyz(Math.toRadians(geoPos.lat), Math.toRadians(geoPos.lon),
                            elevation);
                    final double tAzimuth = orbit.xyz2t(xyz, slcImage).y;

                    // BURST-AWARE azimuth line mapping: jlinda's SLCImage.ta2line() is a NAIVE
                    // continuous (azitime - tAzi1) / lineTimeInterval mapping that assumes no burst
                    // overlaps, so it gives the wrong global line index for any burst after the
                    // first. Reproduce GSLCGeocodingOp's OWN mapping instead: select the burst via
                    // its own selectBurst(), then compute the line local to THAT burst's own
                    // burstFirstLineTime and add burst*linesPerBurst. jlinda's azimuth time is
                    // SECONDS-OF-DAY while Sentinel1Utils' burst times are absolute seconds since
                    // the MJD epoch — dayOffsetSec (same single pass, same UTC day) puts them on
                    // the same axis.
                    // Bistatic term exactly as the production solve applies it (full term when the
                    // IPF bulk correction is absent, near-range-referenced residual otherwise) —
                    // it feeds burst selection AND the line index, same as in getPosition.
                    double bistaticSec = 0.0;
                    if (!bulkBistaticApplied) {
                        bistaticSec = R / Constants.lightSpeed;
                    } else if (bistaticRefRange > 0.0) {
                        bistaticSec = (R - bistaticRefRange) / Constants.lightSpeed;
                    }
                    final double zeroDopplerTimeSec = dayOffsetSec + tAzimuth + bistaticSec;
                    final int burst = GSLCGeocodingOp.selectBurst(zeroDopplerTimeSec, ss);
                    if (burst < 0) {
                        continue;
                    }
                    final double lineWithinBurst = (zeroDopplerTimeSec - ss.burstFirstLineTime[burst])
                            / ss.azimuthTimeInterval;
                    final double az = burst * ss.linesPerBurst + lineWithinBurst;

                    // AZIMUTH must ALSO be near-integer, not just range: the TOPS azimuth deramp
                    // phase is steep (that is why deramping-before-interpolation is needed at all),
                    // so at a non-integer azimuth position BISINC is blending several distinct
                    // source rows — the result is a genuine interpolated value, not a stand-in for
                    // "the raw sample at round(az)". Comparing it against SLC[round(az), round(rg)]
                    // without this filter compares two different quantities regardless of carrier
                    // sign, which is exactly what drove the very first version of this test to a
                    // noise-floor concentration (~8e-4) for every sign/conjugation combination.
                    final double fracAz = az - Math.round(az);
                    if (Math.abs(fracAz) >= AZ_FRAC_TOL) {
                        continue;
                    }

                    candidates.add(new TopsCandidate(x, rg, frac, az, carrierPhase,
                            gslcIBlock[lidx], gslcQBlock[lidx]));
                    blockCandidates++;
                    if (blockCandidates >= perSampleQuota) {
                        break blockScan;
                    }
                }
            }
            }
        } finally {
            JAI.getDefaultInstance().getTileCache().setMemoryCapacity(previousTileCacheCapacity);
        }

        assumeTrue("too few TOPS faithful-phase candidate pixels (" + candidates.size() + "/" +
                        MIN_CANDIDATES + " needed) — sparse DEM/geocoding coverage for this fixture",
                candidates.size() >= MIN_CANDIDATES);

        // Pass 1: scan the constant azimuth offset, keep the one maximising concentration.
        double bestConc = -1.0;
        double bestAzOffset = AZ_OFFSET_MIN;
        for (double azOff = AZ_OFFSET_MIN; azOff <= AZ_OFFSET_MAX + 1.0e-9; azOff += AZ_OFFSET_STEP) {
            double sumReal = 0.0, sumImag = 0.0;
            int count = 0;
            for (final TopsCandidate c : candidates) {
                final double[] ph = evaluateTopsPhasor(c, azOff, srcW, srcH, srcIArr, srcQArr);
                if (ph == null) {
                    continue;
                }
                sumReal += ph[0];
                sumImag += ph[1];
                count++;
            }
            if (count == 0) {
                continue;
            }
            final double conc = Math.sqrt(sumReal * sumReal + sumImag * sumImag) / count;
            if (conc > bestConc) {
                bestConc = conc;
                bestAzOffset = azOff;
            }
        }

        // Pass 2: at the winning azimuth offset, bin per-pixel phasors by column (trend gate) and
        // by burst (breakdown reported unconditionally by the caller).
        final double[] colBinSumReal = new double[NUM_COLUMN_BINS];
        final double[] colBinSumImag = new double[NUM_COLUMN_BINS];
        final int[] colBinCount = new int[NUM_COLUMN_BINS];
        final double[] burstSumReal = new double[numOfBursts];
        final double[] burstSumImag = new double[numOfBursts];
        final int[] burstCount = new int[numOfBursts];
        for (final TopsCandidate c : candidates) {
            final double[] ph = evaluateTopsPhasor(c, bestAzOffset, srcW, srcH, srcIArr, srcQArr);
            if (ph == null) {
                continue;
            }
            final int colBin = clamp(c.x * NUM_COLUMN_BINS / w, 0, NUM_COLUMN_BINS - 1);
            colBinSumReal[colBin] += ph[0];
            colBinSumImag[colBin] += ph[1];
            colBinCount[colBin]++;

            final int burst = clamp((int) (c.az / linesPerBurst), 0, numOfBursts - 1);
            burstSumReal[burst] += ph[0];
            burstSumImag[burst] += ph[1];
            burstCount[burst]++;
        }
        double minAngle = Double.POSITIVE_INFINITY, maxAngle = Double.NEGATIVE_INFINITY;
        for (int b = 0; b < NUM_COLUMN_BINS; b++) {
            if (colBinCount[b] == 0) {
                continue;
            }
            final double angle = Math.atan2(colBinSumImag[b], colBinSumReal[b]);
            minAngle = Math.min(minAngle, angle);
            maxAngle = Math.max(maxAngle, angle);
        }
        final double columnBinTrend = (maxAngle >= minAngle) ? (maxAngle - minAngle) : 0.0;

        final double[] burstConcentration = new double[numOfBursts];
        for (int b = 0; b < numOfBursts; b++) {
            burstConcentration[b] = burstCount[b] == 0 ? Double.NaN :
                    Math.sqrt(burstSumReal[b] * burstSumReal[b] + burstSumImag[b] * burstSumImag[b])
                            / burstCount[b];
        }

        return new TopsFaithfulStats(bestConc < 0 ? 0.0 : bestConc, bestAzOffset, candidates.size(),
                columnBinTrend, burstConcentration, burstCount);
    }

    /**
     * {@code GSLC(P)*exp(-j*azimuthCarrierPhase(P))*conj(SLC[az,rg])},
     * normalised to unit modulus (a phase-only concentration statistic, not amplitude-weighted).
     * <p>
     * Sign derivation: {@code performDerampDemod} bakes {@code deramped = raw * exp(+j*phi_d)}
     * (verified against its source at GSLCGeocodingOp.java ~line 2712), and the carrier-free TOPS
     * output IS that deramped value (no reramp applied) — so recovering {@code raw} from
     * {@code GSLC(P)} needs the INVERSE, {@code exp(-j*phi_d)}, where
     * {@code phi_d = azimuthCarrierPhase(P)}. Empirically confirmed on this fixture (see the
     * one-shot 12-combination sign scan in the task report): {@code exp(-j*phi_d)} scores
     * concentration 0.94-0.96, every other combination scores at the noise floor (~0.1-0.18).
     * <p>
     * No sub-pixel range-carrier correction is applied: the resampler interpolates the baseband
     * deramped i/q, so at near-integer read positions the GSLC pixel carries the source sample's
     * own phase directly. (The former {@code exp(+j*carrierStep*frac)} term modelled the
     * pre-flatten/restore convention that has been removed from GSLCGeocodingOp.)
     */
    private static double[] evaluateTopsPhasor(final TopsCandidate c, final double azOff,
                                                final int srcW, final int srcH,
                                                final float[] srcIArr, final float[] srcQArr) {
        final int azR = (int) Math.round(c.az + azOff);
        final int rgR = (int) Math.round(c.rg);
        if (azR < 0 || azR >= srcH || rgR < 0 || rgR >= srcW) {
            return null;
        }
        final int srcIdx = azR * srcW + rgR;
        final double srcI = srcIArr[srcIdx];
        final double srcQ = srcQArr[srcIdx];

        final double gI = c.gI;
        final double gQ = c.gQ;

        // Re-apply the (inverse) deramp model the carrier-free TOPS output removed:
        // g' = GSLC(P) * exp(-j * azimuthCarrierPhase(P))
        final double cosCarrier = Math.cos(c.carrierPhase);
        final double sinCarrier = Math.sin(c.carrierPhase);
        final double gI2 = gI * cosCarrier + gQ * sinCarrier;
        final double gQ2 = gQ * cosCarrier - gI * sinCarrier;

        // g' * conj(SLC): (gI2 + j*gQ2) * (srcI - j*srcQ)
        final double re = gI2 * srcI + gQ2 * srcQ;
        final double im = gQ2 * srcI - gI2 * srcQ;

        final double mag = Math.hypot(re, im);
        if (mag < 1.0e-12) {
            return null; // degenerate (near-zero amplitude) pixel: phase undefined, drop it
        }
        return new double[]{re / mag, im / mag};
    }

    /** Mirrors {@code GSLCGeocodingOp}'s own I/Q pairing: the first REAL band immediately
     *  followed by an IMAGINARY band. */
    private static Band[] findComplexPair(final Product p) {
        final Band[] bands = p.getBands();
        for (int i = 0; i < bands.length - 1; i++) {
            final Band b = bands[i];
            if (b.getUnit() != null && b.getUnit().equals(Unit.REAL)
                    && bands[i + 1].getUnit() != null && bands[i + 1].getUnit().equals(Unit.IMAGINARY)) {
                return new Band[]{b, bands[i + 1]};
            }
        }
        throw new IllegalStateException("no complex (I/Q) band pair found in product " + p.getName());
    }

    private static int clamp(final int v, final int lo, final int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * Production wiring of the burst-boundary lock: {@code refBurstValidTimes} parameter → lock →
     * pixel-loop pairable masking → genuine nodata in the output, plus the effective-partition
     * stamp round-trip on a real product. No unit test can catch deletion of the mask call or a
     * broken stamp attribute — this one would.
     * <p>
     * Recipe: GSLC A of the fixture unlocked; its own stamp, with the last burst's lastValid
     * reduced by 1.5 s, becomes GSLC B's reference table (a self-lock everywhere except a
     * truncated pairable window). B must (a) mask the late-time (northern) strip A still covers,
     * (b) be pixel-identical to A mid-scene (self-lock boundaries = own midpoints), and (c) stamp
     * the TRUNCATED window as its own effective partition.
     */
    @Test
    public void testBurstLockMaskingAndEffectiveStampWiring() throws Exception {
        assumeTrue(mFile + " not found", mFile.exists());
        // Seconds off the late end (~730 azimuth lines). MARGIN NOTE: assertion (b) needs the cut
        // band to stay clear of row h/2 — on this fixture the band's lowest map row sits ~990 m
        // (~130 rows, ~0.16 s) above mid-scene, so do not raise this past ~1.6 s without moving
        // the identity row south.
        final double windowCut = 1.5;

        final Product srcB = TestUtils.readSourceProduct(mFile);
        Product gslcB = null;
        try {
            // Unlocked leg A is the shared GSLC — a self-lock B only needs A's stamp and
            // A's pixels for the relative assertions, and the shared product's extra
            // diagnostic bands do not touch the I/Q samples. B is built with the SAME
            // resampling so assertion (b)'s pixel-identity comparison stays exact.
            final Product gslcA = sharedGslc();

            final String stampA = AbstractMetadata.getAbstractedMetadata(gslcA)
                    .getAttributeString("gslc_burst_valid_times", null);
            assertTrue("GSLC must stamp gslc_burst_valid_times", stampA != null);
            final String[] rows = stampA.split(",");
            final String[] lastF = rows[rows.length - 1].split(":");
            final int lvIdx = lastF.length - 1;
            final double lvLast = Double.parseDouble(lastF[lvIdx]);
            lastF[lvIdx] = Double.toString(lvLast - windowCut);
            rows[rows.length - 1] = String.join(":", lastF);
            final String refTable = String.join(",", rows);

            final GSLCGeocodingOp opB = (GSLCGeocodingOp) spi.createOperator();
            opB.setSourceProduct(srcB);
            opB.setParameter("demName", "Copernicus 30m Global DEM");
            opB.setParameter("imgResamplingMethod", "BISINC_5_POINT_INTERPOLATION");
            opB.setParameter("nodataValueAtSea", false);
            opB.setParameter("refBurstValidTimes", refTable);
            gslcB = opB.getTargetProduct();

            final Band iA = findComplexPair(gslcA)[0];
            final Band iB = findComplexPair(gslcB)[0];
            final int w = gslcA.getSceneRasterWidth();
            final int h = gslcA.getSceneRasterHeight();
            assertTrue("grids must match", w == gslcB.getSceneRasterWidth()
                    && h == gslcB.getSceneRasterHeight());

            // (a) northern (late-time) strip: first row band where A has data must be masked in B
            final float[] rowBuf = new float[w * 8];
            int rTop = -1;
            for (int r = 0; r + 8 < h / 3; r += 32) {
                iA.readPixels(0, r, w, 8, rowBuf);
                if (countNonZero(rowBuf) > 1000) {
                    rTop = r;
                    break;
                }
            }
            // assume (not assert): an empty unlocked GSLC means the DEM was unavailable in this
            // environment, same skip semantics as the sibling tests
            assumeTrue("no valid northern rows in the unlocked GSLC — DEM unavailable?", rTop >= 0);
            final int validA = countNonZero(rowBuf);
            iB.readPixels(0, rTop, w, 8, rowBuf);
            final int validB = countNonZero(rowBuf);
            System.out.printf("burst-lock masking: rows %d-%d valid px A=%d B=%d%n",
                    rTop, rTop + 8, validA, validB);
            assertTrue("late-time strip must be masked in the locked GSLC (A=" + validA
                    + ", B=" + validB + ")", validB < validA / 20);

            // (b) mid-scene identity: self-lock boundaries = own midpoints
            final float[] midA = new float[w];
            final float[] midB = new float[w];
            iA.readPixels(0, h / 2, w, 1, midA);
            iB.readPixels(0, h / 2, w, 1, midB);
            int nMid = 0;
            for (int k = 0; k < w; k++) {
                assertTrue("mid-scene pixel differs at col " + k,
                        Math.abs(midA[k] - midB[k]) <= 1e-6 * Math.max(1.0, Math.abs(midA[k])));
                if (midA[k] != 0) nMid++;
            }
            assertTrue("mid-scene row unexpectedly empty", nMid > 1000);

            // (c) effective-partition stamp: B's own stamp must carry the truncated window
            final String stampB = AbstractMetadata.getAbstractedMetadata(gslcB)
                    .getAttributeString("gslc_burst_valid_times", null);
            assertTrue(stampB != null);
            final String[] rowsB = stampB.split(",");
            final String[] lastB = rowsB[rowsB.length - 1].split(":");
            final double lvLastB = Double.parseDouble(lastB[lastB.length - 1]);
            assertTrue("locked GSLC must stamp the truncated pairable window as its effective "
                            + "extent (expected ~" + (lvLast - windowCut) + ", got " + lvLastB + ")",
                    Math.abs(lvLastB - (lvLast - windowCut)) < 1e-6);
        } finally {
            // shared gslcA/sharedSrc are disposed once in disposeSharedProducts()
            if (gslcB != null) gslcB.dispose();
            srcB.dispose();
        }
    }

    private static int countNonZero(final float[] v) {
        int n = 0;
        for (final float x : v) {
            if (x != 0) n++;
        }
        return n;
    }
}
