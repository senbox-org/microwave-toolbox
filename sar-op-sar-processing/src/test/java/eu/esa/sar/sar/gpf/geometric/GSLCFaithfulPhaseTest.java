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

import eu.esa.sar.commons.test.ProcessorTest;
import eu.esa.sar.commons.test.TestData;
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
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Pins the "faithful phase" invariant: at any output pixel {@code P} of a GSLC product built
 * with {@code saveSimulatedUnwrappedPhase=true}, the range {@code R} baked into
 * {@code simulatedUnwrappedPhase} ({@code R = simPhase * lambda / (4*pi)}) must be the SAME range
 * that the source SLC's own complex sample was acquired at — i.e. GSLC's backward-geocoded pixel
 * value is a faithful (deramped-to-DC) copy of the source SLC pixel it was resampled from, not an
 * independently-modelled phase.
 * <p>
 * The test statistic is a coherence-style concentration between {@code GSLC(P)} and
 * {@code conj(SLC(read position))}. (An earlier revision additionally corrected each phasor by
 * {@code exp(+j*carrierStep*frac(rg))}: under the former pre-flatten/restore resampler convention
 * the output at a fractional read position carried that residual carrier. The resampler now
 * interpolates the baseband i/q faithfully — the GSLC pixel IS the interpolated source sample —
 * so no correction applies; keeping it would inject {@code 1756*frac} rad of artificial spread
 * on ERS and collapse the statistic.) Because
 * GSLC's own backward solve and this test's independent forward (jlinda) solve can differ by a
 * small constant (a bistatic one-way/two-way convention, or an annotation epoch constant in the
 * azimuth direction), a constant azimuth offset is scanned for and the best value kept — mirroring
 * what {@code ers_faithful.py}'s validated method did. A DRIFTING offset would instead show up as
 * a smooth phase ramp across the swath, which the per-column-bin trend check below catches.
 */
public class GSLCFaithfulPhaseTest extends ProcessorTest {

    private final static OperatorSpi spi = new GSLCGeocodingOp.Spi();

    private final static File ers1File = new File(
            "E:/Output/ers/ERS-1_SAR_SLC-ORBIT_21159_DATE__1-AUG-1995_21_16_39_Orb.dim");
    private final static File ers2File = new File(
            "E:/Output/ers/ERS-2_SAR_SLC-ORBIT_1486_DATE__2-AUG-1995_21_16_42_Orb.dim");
    private final static File capellaFile = TestData.inputCapella_StripmapSLC;
    private final static File s1SMFile = TestData.inputS1_StripmapSLC;

    private static final int SUBSET_SIZE = 2048;
    private static final int MIN_VALID_RASTER_PIXELS = 1000;

    private static final double RG_FRAC_TOL = 0.02;
    private static final int MIN_CANDIDATES = 200;
    private static final double AZ_OFFSET_MIN = 0.0;
    private static final double AZ_OFFSET_MAX = 8.0;
    private static final double AZ_OFFSET_STEP = 0.25;
    private static final int NUM_COLUMN_BINS = 6;

    // ---- gated fixtures --------------------------------------------------------------------

    @Test
    public void testFaithfulPhase_ERS1() throws Exception {
        // Present on this machine — must genuinely pass, not just skip.
        runFixture(ers1File, false);
    }

    @Test
    public void testFaithfulPhase_ERS2() throws Exception {
        runFixture(ers2File, false);
    }

    @Test
    public void testFaithfulPhase_Capella() throws Exception {
        runFixture(capellaFile, true);
    }

    @Test
    public void testFaithfulPhase_S1StripmapSLC() throws Exception {
        runFixture(s1SMFile, true);
    }

    // ---- shared fixture driver ---------------------------------------------------------------

    private void runFixture(final File file, final boolean allowDemUnavailableSkip) throws Exception {
        assumeTrue(file + " not found", file.exists());

        final Product srcFull = TestUtils.readSourceProduct(file);
        try {
            final Product src = GSLCGeometryContractTest.subsetAroundCentre(srcFull, SUBSET_SIZE, SUBSET_SIZE);
            try {
                final GSLCGeocodingOp op = (GSLCGeocodingOp) spi.createOperator();
                op.setSourceProduct(src);
                op.setParameter("demName", "Copernicus 30m Global DEM");
                op.setParameter("gridSpacing", "NATIVE_ANISOTROPIC");
                op.setParameter("nodataValueAtSea", false);
                op.setParameter("saveSimulatedUnwrappedPhase", true);
                op.setParameter("saveDEM", true);
                final Product gslc = op.getTargetProduct();
                try {
                    final int nValidRaster = GSLCGeometryContractTest.countValidSimPhasePixels(
                            gslc, MIN_VALID_RASTER_PIXELS);
                    if (allowDemUnavailableSkip) {
                        assumeTrue("fixture geocoded empty (DEM unavailable?) — skipping",
                                nValidRaster >= MIN_VALID_RASTER_PIXELS);
                    } else {
                        assertTrue("expected a geocoded raster with valid simulatedUnwrappedPhase " +
                                        "pixels for " + file.getName() + "; got " + nValidRaster,
                                nValidRaster >= MIN_VALID_RASTER_PIXELS);
                    }

                    final FaithfulStats stats = computeFaithfulStats(gslc, src, null, null);
                    System.out.printf(
                            "GSLCFaithfulPhaseTest[%s]: conc=%.4f, bestAzOffset=%.2f, nCandidates=%d, " +
                                    "columnBinTrend=%.4f rad%n",
                            file.getName(), stats.concentration, stats.bestAzOffset, stats.nCandidates,
                            stats.columnBinTrend);

                    assertTrue("faithful concentration " + stats.concentration, stats.concentration > 0.7);
                    assertTrue("per-leg phase trend across swath " + stats.columnBinTrend + " rad",
                            Math.abs(stats.columnBinTrend) < 0.5);
                } finally {
                    gslc.dispose();
                }
            } finally {
                src.dispose();
            }
        } finally {
            srcFull.dispose();
        }
    }

    // ---- deliverable helper (reused by Phase 3) -----------------------------------------------

    /**
     * The public, minimal-surface entry point Phase 3 reuses: the faithful-phase concentration
     * between a GSLC product and the source SLC it was geocoded from.
     *
     * @param gslc      a GSLC product built with {@code saveSimulatedUnwrappedPhase=true} and
     *                  {@code saveDEM=true}
     * @param srcSlc    the SOURCE SLC {@code gslc} was geocoded from (pre-geocoding, same metadata
     *                  used to build {@code gslc})
     * @param rangeField {@code null} for no correction (this task); otherwise a 3-element
     *                   {@code {a0, a1, a2}} affine field evaluated as {@code a0 + a1*rg + a2*az}
     *                   and ADDED to the range read position (e.g. an evalOffsetPoly-style
     *                   coregistration bias field, for later phases)
     * @param azField    same convention as {@code rangeField}, but added to the azimuth read
     *                   position
     * @return the concentration {@code |mean(GSLC(P) * conj(SLC(read position)) * carrierCorrection)|}
     * at the best-fitting constant azimuth offset
     */
    static double faithfulConcentration(final Product gslc, final Product srcSlc,
                                         final double[] rangeField, final double[] azField) throws Exception {
        return computeFaithfulStats(gslc, srcSlc, rangeField, azField).concentration;
    }

    static final class FaithfulStats {
        final double concentration;
        final double bestAzOffset;
        final int nCandidates;
        final double columnBinTrend;

        FaithfulStats(final double concentration, final double bestAzOffset, final int nCandidates,
                      final double columnBinTrend) {
            this.concentration = concentration;
            this.bestAzOffset = bestAzOffset;
            this.nCandidates = nCandidates;
            this.columnBinTrend = columnBinTrend;
        }
    }

    /**
     * Full faithful-phase statistic computation, used both by {@link #faithfulConcentration} and
     * by this test's own per-column-bin trend gate.
     * <p>
     * Method (ports {@code ers_faithful.py}'s corrected/validated approach):
     * <ol>
     *     <li>For every GSLC output pixel with a valid {@code simulatedUnwrappedPhase}, recover
     *     {@code R = simPhase*lambda/(4*pi)} and the range read position
     *     {@code rg = (R - nearEdgeSlantRange)/rangeSpacing} (+ {@code rangeField} if supplied).
     *     Keep only pixels where {@code |frac(rg)| < 0.02} — i.e. where the source SLC has an
     *     actual sample very close to the read position, so resampling is a non-issue.</li>
     *     <li>For each kept pixel, independently solve its zero-Doppler azimuth read position via
     *     jlinda ({@code SLCImage}/{@code Orbit}/{@code Ellipsoid}, using the DEM height GSLC
     *     itself geocoded against) (+ {@code azField} if supplied).</li>
     *     <li>Scan a constant azimuth offset over {@code [0, 8]} px in {@code 0.25} steps (this
     *     absorbs a systematic bistatic/annotation azimuth-epoch constant common to the whole
     *     scene) and keep the offset that maximises the concentration
     *     {@code |mean(GSLC(P) * conj(SLC[round(az+azOff), round(rg)]))|}. No sub-pixel
     *     range-carrier correction is applied: the resampler interpolates baseband i/q, so at
     *     {@code |frac(rg)| < 0.02} the GSLC pixel matches the source sample's phase directly.</li>
     *     <li>At the winning azimuth offset, also bin the per-pixel phasors into
     *     {@value #NUM_COLUMN_BINS} column bins across the swath and report the spread
     *     (max-min) of their circular-mean phase angles — a smooth ramp here (as opposed to a
     *     constant) is the signature of a genuine geometry defect, not just a benign constant
     *     offset.</li>
     * </ol>
     */
    static FaithfulStats computeFaithfulStats(final Product gslc, final Product srcSlc,
                                               final double[] rangeField, final double[] azField) throws Exception {
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

        final Band simPhaseBand = gslc.getBand("simulatedUnwrappedPhase");
        final Band elevBand = gslc.getBand("elevation");
        if (simPhaseBand == null || elevBand == null) {
            throw new IllegalStateException("GSLC product must contain simulatedUnwrappedPhase and " +
                    "elevation bands (saveSimulatedUnwrappedPhase=true, saveDEM=true)");
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

        // Bulk-read whole rasters once: far cheaper than per-pixel readPixels() calls given the
        // number of (pixel x azOff-scan-step) combinations evaluated below.
        final double[] simPhaseArr = new double[w * h];
        simPhaseBand.readPixels(0, 0, w, h, simPhaseArr);
        final double[] elevArr = new double[w * h];
        elevBand.readPixels(0, 0, w, h, elevArr);
        final double[] gslcIArr = new double[w * h];
        gslcIQ[0].readPixels(0, 0, w, h, gslcIArr);
        final double[] gslcQArr = new double[w * h];
        gslcIQ[1].readPixels(0, 0, w, h, gslcQArr);

        final double[] srcIArr = new double[srcW * srcH];
        srcIQ[0].readPixels(0, 0, srcW, srcH, srcIArr);
        final double[] srcQArr = new double[srcW * srcH];
        srcIQ[1].readPixels(0, 0, srcW, srcH, srcQArr);

        final List<Candidate> candidates = new ArrayList<>();
        final GeoPos geoPos = new GeoPos();
        final PixelPos pixelPos = new PixelPos();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                final int idx = y * w + x;
                final double simPhase = simPhaseArr[idx];
                if (simPhase == 0.0 || !Double.isFinite(simPhase)) {
                    continue;
                }
                final double elevation = elevArr[idx];
                if (Double.isNaN(elevation)) {
                    continue;
                }

                final double R = simPhase * wavelength / (4.0 * Math.PI);
                final double baseRg = (R - nearEdgeSlantRange) / rangeSpacing;

                // Cheap prefilter on the un-corrected range position before paying for the
                // per-pixel geocoding + jlinda zero-Doppler solve below. Exact for the null-field
                // path this task requires; when rangeField is supplied (future phases) the field
                // is small/smooth so this is still a good prefilter, re-checked exactly afterwards.
                if (rangeField == null) {
                    final double frac0 = baseRg - Math.round(baseRg);
                    if (Math.abs(frac0) >= RG_FRAC_TOL) {
                        continue;
                    }
                }

                pixelPos.setLocation(x + 0.5, y + 0.5);
                geoCoding.getGeoPos(pixelPos, geoPos);
                final Point xyz = Ellipsoid.ell2xyz(Math.toRadians(geoPos.lat), Math.toRadians(geoPos.lon),
                        elevation);
                final double tAzimuth = orbit.xyz2t(xyz, slcImage).y;
                double azBase = slcImage.ta2line(tAzimuth);

                double rg = baseRg;
                if (rangeField != null) {
                    rg += evalField(rangeField, baseRg, azBase);
                }
                final double frac = rg - Math.round(rg);
                if (Math.abs(frac) >= RG_FRAC_TOL) {
                    continue;
                }

                double az = azBase;
                if (azField != null) {
                    az += evalField(azField, rg, azBase);
                }

                candidates.add(new Candidate(x, idx, rg, frac, az));
            }
        }

        assumeTrue("too few faithful-phase candidate pixels (" + candidates.size() + "/" + MIN_CANDIDATES +
                        " needed) — sparse DEM/geocoding coverage for this fixture",
                candidates.size() >= MIN_CANDIDATES);

        // Pass 1: scan the constant azimuth offset, keep the one maximising concentration.
        double bestConc = -1.0;
        double bestAzOffset = AZ_OFFSET_MIN;
        for (double azOff = AZ_OFFSET_MIN; azOff <= AZ_OFFSET_MAX + 1.0e-9; azOff += AZ_OFFSET_STEP) {
            double sumReal = 0.0, sumImag = 0.0;
            int count = 0;
            for (final Candidate c : candidates) {
                final Phasor ph = evaluatePhasor(c, azOff, srcW, srcH, gslcIArr, gslcQArr, srcIArr,
                        srcQArr);
                if (ph == null) {
                    continue;
                }
                sumReal += ph.re;
                sumImag += ph.im;
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

        // Pass 2: at the winning azimuth offset, bin per-pixel phasors by column to measure trend.
        final double[] binSumReal = new double[NUM_COLUMN_BINS];
        final double[] binSumImag = new double[NUM_COLUMN_BINS];
        final int[] binCount = new int[NUM_COLUMN_BINS];
        for (final Candidate c : candidates) {
            final Phasor ph = evaluatePhasor(c, bestAzOffset, srcW, srcH, gslcIArr, gslcQArr, srcIArr,
                    srcQArr);
            if (ph == null) {
                continue;
            }
            final int bin = clamp(c.x * NUM_COLUMN_BINS / w, 0, NUM_COLUMN_BINS - 1);
            binSumReal[bin] += ph.re;
            binSumImag[bin] += ph.im;
            binCount[bin]++;
        }
        double minAngle = Double.POSITIVE_INFINITY, maxAngle = Double.NEGATIVE_INFINITY;
        for (int b = 0; b < NUM_COLUMN_BINS; b++) {
            if (binCount[b] == 0) {
                continue;
            }
            final double angle = Math.atan2(binSumImag[b], binSumReal[b]);
            minAngle = Math.min(minAngle, angle);
            maxAngle = Math.max(maxAngle, angle);
        }
        final double columnBinTrend = (maxAngle >= minAngle) ? (maxAngle - minAngle) : 0.0;

        return new FaithfulStats(bestConc < 0 ? 0.0 : bestConc, bestAzOffset, candidates.size(), columnBinTrend);
    }

    private static double evalField(final double[] field, final double rg, final double az) {
        if (field == null) {
            return 0.0;
        }
        return field[0] + field[1] * rg + field[2] * az;
    }

    private static final class Candidate {
        final int x;
        final int idx;
        final double rg;
        final double frac;
        final double az;

        Candidate(final int x, final int idx, final double rg, final double frac, final double az) {
            this.x = x;
            this.idx = idx;
            this.rg = rg;
            this.frac = frac;
            this.az = az;
        }
    }

    private static final class Phasor {
        final double re;
        final double im;

        Phasor(final double re, final double im) {
            this.re = re;
            this.im = im;
        }
    }

    private static Phasor evaluatePhasor(final Candidate c, final double azOff,
                                          final int srcW, final int srcH, final double[] gslcIArr,
                                          final double[] gslcQArr, final double[] srcIArr, final double[] srcQArr) {
        final int azR = (int) Math.round(c.az + azOff);
        final int rgR = (int) Math.round(c.rg);
        if (azR < 0 || azR >= srcH || rgR < 0 || rgR >= srcW) {
            return null;
        }
        final int srcIdx = azR * srcW + rgR;
        final double srcI = srcIArr[srcIdx];
        final double srcQ = srcQArr[srcIdx];
        final double gI = gslcIArr[c.idx];
        final double gQ = gslcQArr[c.idx];

        // GSLC(P) * conj(SLC): (gI + j*gQ) * (srcI - j*srcQ). No sub-pixel range-carrier
        // correction: the resampler interpolates baseband i/q, so the GSLC pixel carries the
        // source sample's own phase directly. (The former exp(+j*carrierStep*frac) term modelled
        // the pre-flatten/restore convention that has been removed from GSLCGeocodingOp.)
        final double re = gI * srcI + gQ * srcQ;
        final double im = gQ * srcI - gI * srcQ;

        // Normalise to unit modulus: this statistic must be a phase-only "concentration" (the
        // circular-statistics resultant length, in [0, 1] — 1 = perfectly phase-aligned, 0 =
        // random phase), NOT an amplitude-weighted sum. Raw SAR DN products are large, so an
        // un-normalised sum would trivially clear a "> 0.7" gate on amplitude alone regardless of
        // whether the phases actually agree.
        final double mag = Math.hypot(re, im);
        if (mag < 1.0e-12) {
            return null; // degenerate (near-zero amplitude) pixel: phase undefined, drop it
        }
        return new Phasor(re / mag, im / mag);
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
}
