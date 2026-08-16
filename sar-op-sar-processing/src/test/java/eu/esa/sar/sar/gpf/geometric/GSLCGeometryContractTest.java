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
import org.esa.snap.core.gpf.common.SubsetOp;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.jlinda.core.Ellipsoid;
import org.jlinda.core.Orbit;
import org.jlinda.core.Point;
import org.jlinda.core.SLCImage;
import org.junit.Test;

import java.awt.Rectangle;
import java.io.File;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Pins the exact invariant {@code InterferogramOp}'s GSLC-removal path relies on: the slant
 * range GSLC bakes into its {@code simulatedUnwrappedPhase} restore carrier must equal, at the
 * same ground/map pixel, the range that jlinda's {@link SLCImage}/{@link Orbit}/{@link Ellipsoid}
 * machinery independently computes from the SAME source SLC metadata (orbit state vectors +
 * DEM height). This is exactly the pairing {@code InterferogramOp.setupGSLCReferencePhase()} and
 * {@code computeGslcReferencePhase()} depend on: any systematic mismatch between GSLC's own
 * backward-geocoding solve and jlinda's forward solve shows up there as a spurious phase ramp.
 * <p>
 * A CONSTANT offset between the two models (up to a few metres — e.g. a bistatic one-way/two-way
 * convention difference) is harmless: it is a single scalar absorbed identically at every pixel,
 * so it cancels in {@code master - conj(slave)}. What must NOT happen is DRIFT — the offset
 * varying across the scene — because that turns into a smooth spurious fringe pattern. So this
 * test asserts the drift (max-min of per-column and per-row binned mean residuals) stays below
 * 1 cm (worth ~2 rad of ramp for a typical C-band wavelength), while only sanity-bounding the
 * mean residual itself.
 */
public class GSLCGeometryContractTest extends ProcessorTest {

    private final static OperatorSpi spi = new GSLCGeocodingOp.Spi();

    private final static File ers1File = new File(
            "E:/Output/ers/ERS-1_SAR_SLC-ORBIT_21159_DATE__1-AUG-1995_21_16_39_Orb.dim");
    private final static File ers2File = new File(
            "E:/Output/ers/ERS-2_SAR_SLC-ORBIT_1486_DATE__2-AUG-1995_21_16_42_Orb.dim");
    private final static File capellaFile = TestData.inputCapella_StripmapSLC;
    private final static File s1SMFile = TestData.inputS1_StripmapSLC;

    private static final int SUBSET_SIZE = 2048;
    private static final int MIN_VALID_RASTER_PIXELS = 1000;
    private static final int GRID_N = 12;
    private static final int NUM_BINS = 8;
    private static final int MIN_VALID_GRID_SAMPLES = 60;

    // ---- gated fixtures --------------------------------------------------------------------

    @Test
    public void testGeometryContract_ERS1() throws Exception {
        // Present on this machine — must genuinely pass, not just skip.
        runFixture(ers1File, false);
    }

    @Test
    public void testGeometryContract_ERS2() throws Exception {
        runFixture(ers2File, false);
    }

    @Test
    public void testGeometryContract_Capella() throws Exception {
        runFixture(capellaFile, true);
    }

    @Test
    public void testGeometryContract_S1StripmapSLC() throws Exception {
        runFixture(s1SMFile, true);
    }

    // ---- shared fixture driver ---------------------------------------------------------------

    /**
     * @param allowDemUnavailableSkip if true, a near-empty geocoded raster (DEM tiles not
     *                                installed in this environment) is treated as a skip rather
     *                                than a failure — used for the two TestData fixtures whose
     *                                DEM coverage is not guaranteed everywhere this suite runs.
     */
    private void runFixture(final File file, final boolean allowDemUnavailableSkip) throws Exception {
        assumeTrue(file + " not found", file.exists());

        final Product srcFull = TestUtils.readSourceProduct(file);
        try {
            final Product src = subsetAroundCentre(srcFull, SUBSET_SIZE, SUBSET_SIZE);
            try {
                final MetadataElement srcAbs = AbstractMetadata.getAbstractedMetadata(src);

                final GSLCGeocodingOp op = (GSLCGeocodingOp) spi.createOperator();
                op.setSourceProduct(src);
                op.setParameter("demName", "Copernicus 30m Global DEM");
                op.setParameter("gridSpacing", "NATIVE_ANISOTROPIC");
                op.setParameter("nodataValueAtSea", false);
                op.setParameter("saveSimulatedUnwrappedPhase", true);
                op.setParameter("saveDEM", true);
                final Product gslc = op.getTargetProduct();
                try {
                    final int nValidRaster = countValidSimPhasePixels(gslc, MIN_VALID_RASTER_PIXELS);
                    if (allowDemUnavailableSkip) {
                        assumeTrue("fixture geocoded empty (DEM unavailable?) — skipping",
                                nValidRaster >= MIN_VALID_RASTER_PIXELS);
                    } else {
                        assertTrue("expected a geocoded raster with valid simulatedUnwrappedPhase " +
                                        "pixels for " + file.getName() + "; got " + nValidRaster,
                                nValidRaster >= MIN_VALID_RASTER_PIXELS);
                    }

                    final double[] r = measureGeometryDrift(gslc, srcAbs);
                    System.out.printf(
                            "GSLCGeometryContractTest[%s]: meanResidual=%.4f m, columnDrift=%.6f m, rowDrift=%.6f m%n",
                            file.getName(), r[0], r[1], r[2]);

                    assertTrue("mean R_jlinda-R_snap residual should be sanity-bounded (a constant " +
                                    "offset up to a few metres is allowed, e.g. bistatic convention); got " + r[0],
                            Math.abs(r[0]) < 5.0);
                    assertTrue("R drift across scene must stay below 1 cm (2 rad); got columnDrift=" +
                                    r[1] + " rowDrift=" + r[2],
                            Math.abs(r[1]) < 0.01 && Math.abs(r[2]) < 0.01);
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

    // ---- deliverable helper (reused by Task 1.3 and Phase 3) ---------------------------------

    /**
     * Measures how well {@code GSLCGeocodingOp}'s backward-geocoded slant range (recovered from
     * the {@code simulatedUnwrappedPhase} band as {@code R_snap = simPhase * lambda / (4*pi)})
     * agrees with jlinda's independently-solved forward range at the same map pixel.
     * <p>
     * Samples a {@value #GRID_N}x{@value #GRID_N} grid of pixels spread uniformly over the
     * geocoded raster (a geocoded product's valid footprint is a rotated shape inside its
     * bounding box, so some grid points legitimately fall on no-data fill and are skipped).
     * For every valid sample it computes {@code R_jlinda} via {@code Ellipsoid.ell2xyz(lat, lon,
     * demHeight)} + {@code Orbit.xyz2t(...)} against the SOURCE SLC's own orbit/timing metadata,
     * and accumulates {@code R_jlinda - R_snap}.
     *
     * @param gslcWithSimPhaseAndDem a GSLC product produced with {@code saveSimulatedUnwrappedPhase=true}
     *                               and {@code saveDEM=true}
     * @param srcAbsRoot             the SOURCE SLC's abstracted metadata (pre-geocoding), used to build
     *                               the jlinda {@link SLCImage}/{@link Orbit} pair
     * @return {@code {meanResidualMeters, columnDriftMeters, rowDriftMeters}} where
     * {@code meanResidual = mean(R_jlinda - R_snap)}, {@code columnDrift} and {@code rowDrift} are
     * the max-min of per-column-bin / per-row-bin mean residuals ({@value #NUM_BINS} bins each,
     * binned by pixel position across the raster width/height)
     */
    static double[] measureGeometryDrift(final Product gslcWithSimPhaseAndDem,
                                          final MetadataElement srcAbsRoot) throws Exception {
        final Band simPhaseBand = gslcWithSimPhaseAndDem.getBand("simulatedUnwrappedPhase");
        final Band elevBand = gslcWithSimPhaseAndDem.getBand("elevation");
        if (simPhaseBand == null || elevBand == null) {
            throw new IllegalStateException("GSLC product must contain simulatedUnwrappedPhase and " +
                    "elevation bands (saveSimulatedUnwrappedPhase=true, saveDEM=true)");
        }

        double freq = AbstractMetadata.getAttributeDouble(srcAbsRoot, AbstractMetadata.radar_frequency);
        if (freq < 1.0e8) { // metadata sometimes stores MHz
            freq *= 1.0e6;
        }
        final double wavelength = Constants.lightSpeed / freq;

        final SLCImage slcImage = new SLCImage(srcAbsRoot, null);
        final Orbit orbit = new Orbit(srcAbsRoot, 3);

        final int w = gslcWithSimPhaseAndDem.getSceneRasterWidth();
        final int h = gslcWithSimPhaseAndDem.getSceneRasterHeight();
        final GeoCoding geoCoding = gslcWithSimPhaseAndDem.getSceneGeoCoding();
        if (geoCoding == null) {
            throw new IllegalStateException("GSLC product has no scene geo-coding");
        }

        // A geocoded raster's valid footprint is a ROTATED rectangle (the source SLC's swath,
        // rotated by heading) inscribed inside its axis-aligned bounding box (the target raster
        // extent). A naive 12x12 grid spanning the full bounding box therefore lands a chunk of
        // its corner samples on the fill triangles outside that rotated rectangle — worse the
        // steeper the heading. To sample "within the valid region" as intended, progressively
        // shrink the grid's extent toward the raster centre until enough samples land on real
        // data (or give up and report the best attempt for the assumeTrue-skip message).
        GridSample best = null;
        double bestInset = GRID_INSET_FRACTIONS[0];
        for (final double inset : GRID_INSET_FRACTIONS) {
            final GridSample sample = sampleGrid(simPhaseBand, elevBand, geoCoding, slcImage, orbit,
                    wavelength, w, h, inset);
            if (best == null || sample.nValid > best.nValid) {
                best = sample;
                bestInset = inset;
            }
            if (sample.nValid >= MIN_VALID_GRID_SAMPLES) {
                best = sample;
                bestInset = inset;
                break;
            }
        }

        assumeTrue("too few valid geometry-drift grid samples (" + best.nValid + "/" + (GRID_N * GRID_N) +
                        ", need >= " + MIN_VALID_GRID_SAMPLES + ") — sparse DEM/geocoding coverage for this fixture",
                best.nValid >= MIN_VALID_GRID_SAMPLES);

        // Visibility for a future regression: if a fixture ever needs the largest inset to reach
        // MIN_VALID_GRID_SAMPLES, the drift check below is only covering the central portion of
        // the scene (e.g. inset=0.35 leaves just the central ~30% of each axis) rather than the
        // full extent — this line makes that silently-narrowed coverage visible in test output.
        System.out.printf("GEOMETRY-CONTRACT: inset %.2f, %d valid samples%n", bestInset, best.nValid);

        final double meanResidual = best.residualSum / best.nValid;
        final double columnDrift = binSpread(best.colBinSum, best.colBinCount);
        final double rowDrift = binSpread(best.rowBinSum, best.rowBinCount);

        return new double[]{meanResidual, columnDrift, rowDrift};
    }

    /** Fractions of width/height trimmed from EACH side before laying out the 12x12 grid;
     *  tried in order until a sample achieves {@link #MIN_VALID_GRID_SAMPLES}. */
    private static final double[] GRID_INSET_FRACTIONS = {0.0, 0.1, 0.2, 0.3, 0.35};

    private static final class GridSample {
        int nValid;
        double residualSum;
        final double[] colBinSum = new double[NUM_BINS];
        final int[] colBinCount = new int[NUM_BINS];
        final double[] rowBinSum = new double[NUM_BINS];
        final int[] rowBinCount = new int[NUM_BINS];
    }

    private static GridSample sampleGrid(final Band simPhaseBand, final Band elevBand,
                                          final GeoCoding geoCoding, final SLCImage slcImage,
                                          final Orbit orbit, final double wavelength,
                                          final int w, final int h, final double inset) throws Exception {
        final GridSample s = new GridSample();
        final double[] simPhaseBuf = new double[1];
        final float[] elevBuf = new float[1];
        final GeoPos geoPos = new GeoPos();
        final PixelPos pixelPos = new PixelPos();

        final double xLo = inset * w, xHi = (1.0 - inset) * w;
        final double yLo = inset * h, yHi = (1.0 - inset) * h;

        for (int j = 0; j < GRID_N; j++) {
            final int y = clamp((int) Math.round(yLo + (j + 0.5) * (yHi - yLo) / GRID_N), 0, h - 1);
            for (int i = 0; i < GRID_N; i++) {
                final int x = clamp((int) Math.round(xLo + (i + 0.5) * (xHi - xLo) / GRID_N), 0, w - 1);

                simPhaseBand.readPixels(x, y, 1, 1, simPhaseBuf);
                final double simPhase = simPhaseBuf[0];
                if (simPhase == 0.0 || !Double.isFinite(simPhase)) {
                    continue;
                }

                elevBand.readPixels(x, y, 1, 1, elevBuf);
                final double elevation = elevBuf[0];
                if (Double.isNaN(elevation)) {
                    continue;
                }

                pixelPos.setLocation(x + 0.5, y + 0.5);
                geoCoding.getGeoPos(pixelPos, geoPos);

                final double rSnap = simPhase * wavelength / (4.0 * Math.PI);

                final Point xyz = Ellipsoid.ell2xyz(Math.toRadians(geoPos.lat), Math.toRadians(geoPos.lon),
                        elevation);
                final double rJlinda = orbit.xyz2t(xyz, slcImage).x * Constants.lightSpeed;

                final double residual = rJlinda - rSnap;
                s.residualSum += residual;
                s.nValid++;

                final int colBin = clamp(x * NUM_BINS / w, 0, NUM_BINS - 1);
                s.colBinSum[colBin] += residual;
                s.colBinCount[colBin]++;

                final int rowBin = clamp(y * NUM_BINS / h, 0, NUM_BINS - 1);
                s.rowBinSum[rowBin] += residual;
                s.rowBinCount[rowBin]++;
            }
        }
        return s;
    }

    private static double binSpread(final double[] binSum, final int[] binCount) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (int b = 0; b < binSum.length; b++) {
            if (binCount[b] == 0) {
                continue;
            }
            final double mean = binSum[b] / binCount[b];
            min = Math.min(min, mean);
            max = Math.max(max, mean);
        }
        return (max >= min) ? (max - min) : 0.0;
    }

    private static int clamp(final int v, final int lo, final int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ---- other helpers -------------------------------------------------------------------------

    /** Crop a source SLC to a {@code width}x{@code height} pixel block centred on the scene.
     *  Package-visible so sibling GSLC test classes (e.g. GSLCFaithfulPhaseTest) can reuse the
     *  exact same fixture-cropping logic. */
    static Product subsetAroundCentre(final Product src, final int width, final int height) throws Exception {
        final int w = src.getSceneRasterWidth();
        final int h = src.getSceneRasterHeight();
        final int subW = Math.min(width, w);
        final int subH = Math.min(height, h);
        final int x = Math.max(0, (w - subW) / 2);
        final int y = Math.max(0, (h - subH) / 2);

        final SubsetOp subsetOp = new SubsetOp();
        subsetOp.setSourceProduct(src);
        subsetOp.setRegion(new Rectangle(x, y, subW, subH));
        subsetOp.setCopyMetadata(true);
        final Product subset = subsetOp.getTargetProduct();
        subset.setName(src.getName() + "_centreSubset");
        return subset;
    }

    /**
     * Counts non-zero, finite {@code simulatedUnwrappedPhase} pixels across the whole raster,
     * stopping early once {@code stopAt} is reached (this is only used as a coarse "did this
     * geocode to something" gate, not for precision). Package-visible for reuse by sibling GSLC
     * test classes (e.g. GSLCFaithfulPhaseTest).
     */
    static int countValidSimPhasePixels(final Product gslc, final int stopAt) throws Exception {
        final Band simPhaseBand = gslc.getBand("simulatedUnwrappedPhase");
        if (simPhaseBand == null) {
            return 0;
        }
        final int w = simPhaseBand.getRasterWidth();
        final int h = simPhaseBand.getRasterHeight();
        final double[] row = new double[w];
        int count = 0;
        for (int y = 0; y < h && count < stopAt; y++) {
            simPhaseBand.readPixels(0, y, w, 1, row);
            for (final double v : row) {
                if (v != 0.0 && Double.isFinite(v)) {
                    count++;
                }
            }
        }
        return count;
    }
}
