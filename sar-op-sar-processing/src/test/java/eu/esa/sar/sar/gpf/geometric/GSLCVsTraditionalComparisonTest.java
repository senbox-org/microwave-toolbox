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
import eu.esa.sar.commons.test.ProcessorTest;
import eu.esa.sar.insar.gpf.coregistration.GCPManager;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Placemark;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductNodeGroup;
import org.esa.snap.core.gpf.GPF;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import java.awt.Rectangle;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Side-by-side comparison of the GSLC InSAR pipeline against the traditional
 * DEM-Assisted-Coregistration pipeline on the user's ENVISAT ASAR pair. Measures
 * mean coherence of each pipeline's interferogram and prints a table.
 * <p>
 * Iterates over a small grid of GSLC parameter combinations and reports which
 * one comes closest to the traditional pipeline's coherence — the "minimize the
 * error" sweep. File-existence gated.
 * <p>
 * Pipelines:
 * <pre>
 *  Traditional:  Apply-Orbit-File (done) → DEM-Assisted-Coregistration → Interferogram
 *  GSLC (A):     Apply-Orbit-File (done) → GSLC(master) → CreateStack(masterGSLC, slaveSLC) → Interferogram
 *                (slave is auto-geocoded against masterGSLC's grid by CreateStack)
 *  GSLC (B):     Apply-Orbit-File (done) → GSLC(master) → GSLC(slave) → CreateStack → Interferogram
 * </pre>
 */
public class GSLCVsTraditionalComparisonTest extends ProcessorTest {

    private static final File MASTER_FILE = new File(
            "E:/out/ASA_IMS_1PNUPA20031203_061259_000000162022_00120_09192_0099_Orb.dim");
    private static final File SLAVE_FILE  = new File(
            "E:/out/ASA_IMS_1PXPDE20040211_061300_000000142024_00120_10194_0013_Orb.dim");

    /**
     * Minimum coherence-ratio (best gslc / traditional) the test asserts. Set low because
     * the traditional pipeline (Cross-Correlation + Warp) does empirical sub-pixel
     * coregistration that pure geometric GSLC has no analog for. The point of this test
     * is to measure the gap, not to gate on it.
     */
    private static final double MIN_GSLC_TO_TRAD_COH_RATIO = 0.10;

    /**
     * Coherence-estimation window size in metres. Same physical extent on both pipelines
     * so that GSLC (~7.8 m pixel) and traditional (~4 m × ~8 m pixel) average over the
     * same ground area and the coherence numbers are directly comparable.
     */
    private static final double COHERENCE_WINDOW_M = 100.0;

    @Test
    public void testCompareGSLCAndTraditional() throws Exception {
        runComparison(0.04, "subset_8km");
    }

    /**
     * Full-scene variant — no subset. Slow (~30-60 min) and memory-hungry; needs
     * {@code -Xmx20g} or more on the JVM. Run with:
     * <pre>
     *   mvn -pl sar-op-sar-processing -Dtest='GSLCVsTraditionalComparisonTest#testFullResolution' \
     *       -Dsurefire.jvm.args="-Xmx20g -enableassertions -Dfile.encoding=UTF-8 \
     *           --add-exports java.desktop/sun.awt=ALL-UNNAMED \
     *           --add-exports java.desktop/sun.awt.image=ALL-UNNAMED \
     *           --add-opens java.desktop/sun.awt.shell=ALL-UNNAMED \
     *           --add-opens java.base/java.net=ALL-UNNAMED \
     *           --add-opens java.desktop/javax.swing=ALL-UNNAMED \
     *           --add-opens java.base/java.security=ALL-UNNAMED \
     *           --add-exports java.desktop/com.sun.imageio.plugins.jpeg=ALL-UNNAMED" \
     *       test
     * </pre>
     */
    @org.junit.Test
    public void testFullResolution() throws Exception {
        runComparison(-1.0 /* no subset */, "full");
    }

    /**
     * Side-by-side GSLC interferogram with {@code outputFlattened=false} vs {@code true}.
     * The flattened path bakes the topographic/ellipsoidal phase removal into the GSLC
     * itself, so the residual should still produce fringes (just from differential phase
     * — atmosphere, displacement, decorrelation) instead of being dominated by the
     * geometric phase ramp. User reported the {@code outputFlattened=true} interferogram
     * comes out as pure noise; this test reproduces and quantifies the difference.
     */
    @org.junit.Test
    public void testOutputFlattenedComparison() throws Exception {
        runFlattenedComparison(0.04, "flattened_subset_8km");
    }

    private void runFlattenedComparison(final double halfSpanDeg, final String tag) throws Exception {
        assumeTrue("Test files not available locally", MASTER_FILE.isFile() && SLAVE_FILE.isFile());

        final Product masterFull = ProductIO.readProduct(MASTER_FILE);
        final Product slaveFull = ProductIO.readProduct(SLAVE_FILE);
        assertNotNull(masterFull);
        assertNotNull(slaveFull);

        final Product masterSLC;
        final Product slaveSLC;
        if (halfSpanDeg > 0) {
            final double centreLat = 29.13;
            final double centreLon = 58.45;
            final String wktBox = String.format(
                    "POLYGON((%f %f, %f %f, %f %f, %f %f, %f %f))",
                    centreLon - halfSpanDeg, centreLat - halfSpanDeg,
                    centreLon + halfSpanDeg, centreLat - halfSpanDeg,
                    centreLon + halfSpanDeg, centreLat + halfSpanDeg,
                    centreLon - halfSpanDeg, centreLat + halfSpanDeg,
                    centreLon - halfSpanDeg, centreLat - halfSpanDeg);
            masterSLC = subsetByGeoRegion(masterFull, wktBox);
            slaveSLC  = subsetByGeoRegion(slaveFull,  wktBox);
        } else {
            masterSLC = masterFull;
            slaveSLC = slaveFull;
        }

        // Estimate the scalar bias once and reuse — we want the SAME alignment for both
        // flattened/non-flattened so any phase difference comes from the flattening itself.
        System.out.println("--- Bias estimation ---");
        final double[] bias = estimateBiasViaCC(masterSLC, slaveSLC);
        final double dRg = bias[0], dAz = bias[1];
        System.out.printf("scalar bias: Δrange=%+.4f px  Δazimuth=%+.4f px%n%n", dRg, dAz);

        for (final boolean flat : new boolean[]{false, true}) {
            System.out.printf("--- GSLC pipeline (outputFlattened=%s) ---%n", flat);
            final long t0 = System.currentTimeMillis();
            final Product ifg = runGslcPipeline(masterSLC, slaveSLC,
                    "BICUBIC_INTERPOLATION", false, dRg, dAz, flat);
            final double coh = computeMeanCoherence(ifg, "GSLC(flat=" + flat + ")");
            final double[] fqi = measureFringeQuality(ifg, 10, 10);
            final long ms = System.currentTimeMillis() - t0;
            System.out.printf("outputFlattened=%-5s: meanCoh=%.4f  FQI_rg=%.3f  FQI_az=%.3f  mean_FQI=%.3f  time=%.1fs%n",
                    flat, coh, fqi[0], fqi[1], 0.5 * (fqi[0] + fqi[1]), ms / 1000.0);

            // Write to disk for visual inspection
            final File outDir = new File("E:/out");
            if (outDir.isDirectory() || outDir.mkdirs()) {
                final File out = new File(outDir, "gslc_ifg_" + tag + "_flat" + flat + ".dim");
                ProductIO.writeProduct(ifg, out, "BEAM-DIMAP", false, ProgressMonitor.NULL);
                System.out.printf("  → %s%n", out.getAbsolutePath());
            }
            System.out.println();
        }
        System.out.println("Interpretation:");
        System.out.println("  Both should have visible fringes (mean_FQI > 0.5). If outputFlattened=true");
        System.out.println("  collapses to noise, the flattening math (topo+earth phase removal during");
        System.out.println("  geocoding) is broken, OR the phase being removed doesn't match what's actually");
        System.out.println("  in the carrier (sign error, range convention, etc.).");
    }

    private static double[] estimateBiasViaCC(final Product masterSLC, final Product slaveSLC) throws Exception {
        final Map<String, Product> stackSources = new LinkedHashMap<>();
        stackSources.put("sourceProduct.1", masterSLC);
        stackSources.put("sourceProduct.2", slaveSLC);
        final Map<String, Object> stackParams = new HashMap<>();
        stackParams.put("extent", "Master");
        stackParams.put("initialOffsetMethod", "Orbit");
        stackParams.put("resamplingType", "NONE");
        stackParams.put("autoCoregisterGSLC", false);
        final Product stack = GPF.createProduct("CreateStack", stackParams, stackSources);

        final Map<String, Object> ccParams = new HashMap<>();
        ccParams.put("numGCPtoGenerate", 500);
        ccParams.put("coarseRegistrationWindowWidth", "128");
        ccParams.put("coarseRegistrationWindowHeight", "128");
        ccParams.put("applyFineRegistration", true);
        ccParams.put("inSAROptimized", true);
        ccParams.put("coherenceThreshold", 0.6);
        final Product cc = GPF.createProduct("Cross-Correlation", ccParams, stack);

        Band masterBand = null, slaveBand = null;
        for (final Band b : cc.getBands()) {
            if (b.getUnit() != null && b.getUnit().equals(Unit.REAL)) {
                if (masterBand == null) masterBand = b;
                else if (slaveBand == null) { slaveBand = b; break; }
            }
        }
        // Drive computeTile to populate GCPs
        final int w = slaveBand.getRasterWidth(), h = slaveBand.getRasterHeight();
        final float[] row = new float[w];
        for (int y = 0; y < h; y += 256) {
            slaveBand.readPixels(0, y, w, 1, row, ProgressMonitor.NULL);
        }
        final ProductNodeGroup<Placemark> mGcp = GCPManager.instance().getGcpGroup(masterBand);
        final ProductNodeGroup<Placemark> sGcp = GCPManager.instance().getGcpGroup(slaveBand);
        final List<Double> dxs = new ArrayList<>(), dys = new ArrayList<>();
        for (final Placemark mp : mGcp.toArray(new Placemark[0])) {
            final Placemark sp = sGcp.get(mp.getName());
            if (sp == null) continue;
            dxs.add((double) (sp.getPixelPos().x - mp.getPixelPos().x));
            dys.add((double) (sp.getPixelPos().y - mp.getPixelPos().y));
        }
        Collections.sort(dxs);
        Collections.sort(dys);
        final int n = dxs.size();
        final double medDx = (n % 2 == 0) ? 0.5 * (dxs.get(n / 2 - 1) + dxs.get(n / 2)) : dxs.get(n / 2);
        final double medDy = (n % 2 == 0) ? 0.5 * (dys.get(n / 2 - 1) + dys.get(n / 2)) : dys.get(n / 2);
        return new double[]{medDx, medDy};
    }

    /**
     * @param halfSpanDeg subset half-span in degrees (~latitude), or negative for
     *                    no-subset (full scene).
     * @param tag         label used in the output filenames.
     */
    private void runComparison(final double halfSpanDeg, final String tag) throws Exception {
        assumeTrue(MASTER_FILE + " not found", MASTER_FILE.exists());
        assumeTrue(SLAVE_FILE + " not found", SLAVE_FILE.exists());

        GPF.getDefaultInstance().getOperatorSpiRegistry().loadOperatorSpis();

        try (final Product masterFull = TestUtils.readSourceProduct(MASTER_FILE);
             final Product slaveFull  = TestUtils.readSourceProduct(SLAVE_FILE)) {

            System.out.println("\n=== GSLCVsTraditionalComparisonTest [" + tag + "] ===");
            System.out.printf("master FULL: %s (%dx%d)%n",
                    masterFull.getName(), masterFull.getSceneRasterWidth(), masterFull.getSceneRasterHeight());
            System.out.printf("slave  FULL: %s (%dx%d)%n",
                    slaveFull.getName(), slaveFull.getSceneRasterWidth(), slaveFull.getSceneRasterHeight());

            // ----------- Optionally subset the SLCs to a common geographic patch ------
            // Negative halfSpanDeg means "no subset — use the full scenes".
            final Product masterSLC;
            final Product slaveSLC;
            if (halfSpanDeg > 0) {
                final double centreLat = 29.13;
                final double centreLon = 58.45;
                final String wktBox = String.format(
                        "POLYGON((%f %f, %f %f, %f %f, %f %f, %f %f))",
                        centreLon - halfSpanDeg, centreLat - halfSpanDeg,
                        centreLon + halfSpanDeg, centreLat - halfSpanDeg,
                        centreLon + halfSpanDeg, centreLat + halfSpanDeg,
                        centreLon - halfSpanDeg, centreLat + halfSpanDeg,
                        centreLon - halfSpanDeg, centreLat - halfSpanDeg);
                System.out.printf("subset geoRegion: %s%n", wktBox);
                masterSLC = subsetByGeoRegion(masterFull, wktBox);
                slaveSLC  = subsetByGeoRegion(slaveFull,  wktBox);
                System.out.printf("master SUBSET: %dx%d%n",
                        masterSLC.getSceneRasterWidth(), masterSLC.getSceneRasterHeight());
                System.out.printf("slave  SUBSET: %dx%d%n%n",
                        slaveSLC.getSceneRasterWidth(),  slaveSLC.getSceneRasterHeight());
            } else {
                System.out.println("(no subset — running on full SLCs)");
                masterSLC = masterFull;
                slaveSLC  = slaveFull;
            }

            // ----------------------------- Traditional pipeline ---------------------
            System.out.println("--- Traditional pipeline (CreateStack + Cross-Correlation + Warp + Interferogram) ---");
            final long t0Trad = System.currentTimeMillis();
            final Product tradIfg = runTraditionalPipeline(masterSLC, slaveSLC);
            final double tradMeanCoh = computeMeanCoherence(tradIfg, "traditional");
            final long tradMs = System.currentTimeMillis() - t0Trad;
            System.out.printf("traditional: meanCoh=%.4f   time=%.1fs%n%n",
                    tradMeanCoh, tradMs / 1000.0);

            // ----------------------------- Scalar bias from cross-correlation -------
            // The traditional pipeline above already ran cross-correlation on the same
            // master+slave pair; re-running here just to extract the median GCP offset
            // would double the cost, so we re-do a lightweight CC pass with fewer GCPs.
            System.out.println("--- Estimating scalar (Δrange, Δazimuth) bias from cross-correlation ---");
            final long t0Bias = System.currentTimeMillis();
            final double[] bias = estimateScalarBias(masterSLC, slaveSLC);
            final double dRangePixels   = bias[0];
            final double dAzimuthPixels = bias[1];
            System.out.printf("scalar bias: Δrange=%+.4f px  Δazimuth=%+.4f px  time=%.1fs%n%n",
                    dRangePixels, dAzimuthPixels, (System.currentTimeMillis() - t0Bias) / 1000.0);

            // ----------------------------- GSLC pipeline (single config) ------------
            // We've established that BICUBIC + BIAS is the best configuration (sweep
            // results in prior runs). For iterative TDD on the algorithm we run only
            // that single config — the sweep adds 5× runtime for ~1% coherence
            // difference between resamplers.
            System.out.println("--- GSLC pipeline (BICUBIC + BIAS + deramp) ---");
            final long t0Gslc = System.currentTimeMillis();
            final Product bestGslcIfg = runGslcPipeline(masterSLC, slaveSLC,
                    "BICUBIC_INTERPOLATION", false,
                    dRangePixels, dAzimuthPixels);
            final double bestCoh = computeMeanCoherence(bestGslcIfg, "GSLC");
            final long gslcMs = System.currentTimeMillis() - t0Gslc;
            System.out.printf("GSLC: meanCoh=%.4f  ratio=%.3f  time=%.1fs%n%n",
                    bestCoh, bestCoh / Math.max(tradMeanCoh, 1e-9), gslcMs / 1000.0);

            assertTrue(String.format(
                    "GSLC coherence (%.4f) should reach at least %.0f%% of the traditional " +
                            "pipeline's coherence (%.4f).",
                    bestCoh, MIN_GSLC_TO_TRAD_COH_RATIO * 100, tradMeanCoh),
                    bestCoh >= tradMeanCoh * MIN_GSLC_TO_TRAD_COH_RATIO);

            // Fringe quality: multilook the wrapped interferogram and measure the
            // circular concentration of the lag-1 phase gradient. Values close to 1
            // mean the phase varies smoothly between adjacent multilooked pixels —
            // visible fringes. Values close to 0 mean random phase = no fringes.
            System.out.println("--- Fringe quality (post-multilook 10×10) ---");
            final double[] fqiGslc = measureFringeQuality(bestGslcIfg, 10, 10);
            final double[] fqiTrad = measureFringeQuality(tradIfg, 10, 10);
            System.out.printf("GSLC        :  FQI_rg=%.3f   FQI_az=%.3f   mean=%.3f%n",
                    fqiGslc[0], fqiGslc[1], 0.5 * (fqiGslc[0] + fqiGslc[1]));
            System.out.printf("Traditional :  FQI_rg=%.3f   FQI_az=%.3f   mean=%.3f%n",
                    fqiTrad[0], fqiTrad[1], 0.5 * (fqiTrad[0] + fqiTrad[1]));
            System.out.println("Interpretation:");
            System.out.println("  FQI > 0.7 → strong, visually clear fringes");
            System.out.println("  FQI 0.3-0.7 → faint but real fringes (visible with multilook/filter)");
            System.out.println("  FQI < 0.2 → noise dominates, no fringes");
            System.out.println();

            // Visualization: write the best GSLC interferogram + the traditional one to
            // disk so they can be opened in SNAP and the fringes inspected visually.
            final File outDir = new File("E:/out");
            if (outDir.isDirectory() || outDir.mkdirs()) {
                final File gslcOut = new File(outDir, "gslc_ifg_" + tag + ".dim");
                final File tradOut = new File(outDir, "trad_ifg_" + tag + ".dim");
                ProductIO.writeProduct(bestGslcIfg, gslcOut, "BEAM-DIMAP", false, ProgressMonitor.NULL);
                ProductIO.writeProduct(tradIfg, tradOut, "BEAM-DIMAP", false, ProgressMonitor.NULL);
                System.out.printf("%nWrote interferograms for visual inspection:%n  %s%n  %s%n",
                        gslcOut.getAbsolutePath(), tradOut.getAbsolutePath());
            }
        }
    }

    /**
     * Quantify how "fringey" an interferogram is, in both range and azimuth directions.
     * Box-multilooks the i_ifg/q_ifg bands {@code mlAz × mlRg}, then computes the circular
     * concentration {@code R = |Σ exp(jdφ)| / N} of the lag-1 phase gradient between
     * adjacent multilooked pixels. Returns {@code [R_range, R_azimuth]} ∈ [0, 1]:
     * <ul>
     *     <li>1.0 → phase is locally linear (perfect fringes)</li>
     *     <li>0.0 → phase difference is uniformly random (no fringes)</li>
     * </ul>
     * This works on the raw wrapped phase — no need to unwrap.
     */
    private static double[] measureFringeQuality(final Product ifg, final int mlAz, final int mlRg) throws Exception {
        // Locate the i_ifg / q_ifg complex pair.
        Band iBand = null, qBand = null;
        for (final Band b : ifg.getBands()) {
            final String u = b.getUnit();
            if (u == null) continue;
            if (u.equals(Unit.REAL)      && iBand == null && b.getName().startsWith("i_ifg")) iBand = b;
            if (u.equals(Unit.IMAGINARY) && qBand == null && b.getName().startsWith("q_ifg")) qBand = b;
        }
        if (iBand == null || qBand == null) {
            System.out.println("  (interferogram bands i_ifg/q_ifg not found — FQI skipped)");
            return new double[]{0.0, 0.0};
        }
        final int W = iBand.getRasterWidth();
        final int H = iBand.getRasterHeight();

        // Multilook into a coarser grid by complex boxcar averaging.
        final int mlW = W / mlRg;
        final int mlH = H / mlAz;
        if (mlW < 2 || mlH < 2) return new double[]{0.0, 0.0};

        final double[][] mlI = new double[mlH][mlW];
        final double[][] mlQ = new double[mlH][mlW];
        final float[] rowI = new float[W];
        final float[] rowQ = new float[W];
        for (int y = 0; y < mlH * mlAz; y++) {
            iBand.readPixels(0, y, W, 1, rowI, ProgressMonitor.NULL);
            qBand.readPixels(0, y, W, 1, rowQ, ProgressMonitor.NULL);
            final int my = y / mlAz;
            for (int x = 0; x < mlW * mlRg; x++) {
                final int mx = x / mlRg;
                final float i = rowI[x];
                final float q = rowQ[x];
                if (!Float.isNaN(i) && !Float.isNaN(q)) {
                    mlI[my][mx] += i;
                    mlQ[my][mx] += q;
                }
            }
        }

        // Lag-1 gradient consistency, range direction (x).
        double cR = 0, sR = 0; int nR = 0;
        for (int my = 0; my < mlH; my++) {
            for (int mx = 0; mx + 1 < mlW; mx++) {
                final double iA = mlI[my][mx],     qA = mlQ[my][mx];
                final double iB = mlI[my][mx + 1], qB = mlQ[my][mx + 1];
                // exp(j·dφ) = A · conj(B) / |A·B|
                final double dI = iA * iB + qA * qB;
                final double dQ = qA * iB - iA * qB;
                final double mag = Math.hypot(dI, dQ);
                if (mag < 1e-12) continue;
                cR += dI / mag;
                sR += dQ / mag;
                nR++;
            }
        }
        final double rangeFqi = nR > 0 ? Math.hypot(cR, sR) / nR : 0.0;

        // Lag-1 gradient consistency, azimuth direction (y).
        double cA = 0, sA = 0; int nA = 0;
        for (int my = 0; my + 1 < mlH; my++) {
            for (int mx = 0; mx < mlW; mx++) {
                final double iA = mlI[my][mx],     qA = mlQ[my][mx];
                final double iB = mlI[my + 1][mx], qB = mlQ[my + 1][mx];
                final double dI = iA * iB + qA * qB;
                final double dQ = qA * iB - iA * qB;
                final double mag = Math.hypot(dI, dQ);
                if (mag < 1e-12) continue;
                cA += dI / mag;
                sA += dQ / mag;
                nA++;
            }
        }
        final double azFqi = nA > 0 ? Math.hypot(cA, sA) / nA : 0.0;

        return new double[]{rangeFqi, azFqi};
    }

    // ---------------------------------------------------------------------------

    /**
     * Crop a product to a lat/lon polygon (WKT) using {@code SubsetOp}. Both inputs in
     * the test pair are cropped with the SAME polygon so they cover the same ground
     * area — much faster than running the full scenes and keeps the comparison fair.
     */
    private static Product subsetByGeoRegion(final Product source, final String wkt) throws Exception {
        final Map<String, Object> params = new HashMap<>();
        params.put("geoRegion", wkt);
        params.put("copyMetadata", true);
        final Product subset = GPF.createProduct("Subset", params, source);
        assertNotNull("Subset must not be null for " + source.getName(), subset);
        return subset;
    }

    /**
     * Estimate a constant (Δrange, Δazimuth) bias in slant-range pixels between master
     * and slave by running CreateStack + Cross-Correlation and taking the median offset
     * of the GCPs found. Returns {@code {dRangePixels, dAzimuthPixels}}: the value that,
     * when added to the slave's geometric pixel coordinates, would align it with the
     * cross-correlation solution. Captures everything the geometric model doesn't
     * (clock offsets, sub-cm orbit residuals, etc.) in two scalars.
     */
    private static double[] estimateScalarBias(final Product master, final Product slave) throws Exception {
        final Map<String, Product> stackSources = new HashMap<>();
        stackSources.put("sourceProduct.1", master);
        stackSources.put("sourceProduct.2", slave);
        final Map<String, Object> stackParams = new HashMap<>();
        stackParams.put("extent", "Master");
        stackParams.put("initialOffsetMethod", "Orbit");
        stackParams.put("resamplingType", "NONE");
        final Product stack = GPF.createProduct("CreateStack", stackParams, stackSources);

        final Map<String, Object> ccParams = new HashMap<>();
        ccParams.put("numGCPtoGenerate", 500);
        ccParams.put("coarseRegistrationWindowWidth", "128");
        ccParams.put("coarseRegistrationWindowHeight", "128");
        ccParams.put("applyFineRegistration", true);
        ccParams.put("inSAROptimized", true);
        ccParams.put("coherenceThreshold", 0.6);
        final Product cc = GPF.createProduct("Cross-Correlation", ccParams, stack);

        // Force tile evaluation across the secondary band so Cross-Correlation actually
        // runs its matching loops — the GCPs are populated as a side effect of computeTile.
        Band masterBand = null;
        Band slaveBand = null;
        for (final Band b : cc.getBands()) {
            if (b.getUnit() != null && b.getUnit().equals(Unit.REAL)) {
                if (masterBand == null) masterBand = b;
                else if (slaveBand == null) { slaveBand = b; break; }
            }
        }
        assertNotNull("master band missing in CC output", masterBand);
        assertNotNull("slave band missing in CC output",  slaveBand);

        final int w = slaveBand.getRasterWidth();
        final int h = slaveBand.getRasterHeight();
        final float[] row = new float[w];
        for (int y = 0; y < h; y += 64) {
            slaveBand.readPixels(0, y, w, 1, row, ProgressMonitor.NULL);
        }

        final ProductNodeGroup<Placemark> masterGcps = GCPManager.instance().getGcpGroup(masterBand);
        final ProductNodeGroup<Placemark> slaveGcps  = GCPManager.instance().getGcpGroup(slaveBand);
        final List<Double> dxs = new ArrayList<>();
        final List<Double> dys = new ArrayList<>();
        for (final Placemark mp : masterGcps.toArray(new Placemark[0])) {
            final Placemark sp = slaveGcps.get(mp.getName());
            if (sp == null) continue;
            final PixelPos mPP = mp.getPixelPos();
            final PixelPos sPP = sp.getPixelPos();
            dxs.add(sPP.x - mPP.x);
            dys.add(sPP.y - mPP.y);
        }
        System.out.printf("    Cross-Correlation matched %d/%d GCPs%n",
                dxs.size(), masterGcps.getNodeCount());

        return new double[]{ median(dxs), median(dys) };
    }

    private static double median(final List<Double> values) {
        if (values.isEmpty()) return 0.0;
        Collections.sort(values);
        final int n = values.size();
        return (n % 2 == 0)
                ? 0.5 * (values.get(n / 2 - 1) + values.get(n / 2))
                : values.get(n / 2);
    }

    private static Product runTraditionalPipeline(final Product master, final Product slave) throws Exception {
        // Classical SLC coregistration: CreateStack → Cross-Correlation → Warp.
        //
        // 1) CreateStack: stack master + slave with no resampling, orbit-based initial
        //    integer-pixel offset (this is the SLC orbit path, not the geocoded path).
        final Map<String, Product> stackSources = new HashMap<>();
        stackSources.put("sourceProduct.1", master);
        stackSources.put("sourceProduct.2", slave);
        final Map<String, Object> stackParams = new HashMap<>();
        stackParams.put("extent", "Master");
        stackParams.put("initialOffsetMethod", "Orbit");
        stackParams.put("resamplingType", "NONE");
        final Product stack = GPF.createProduct("CreateStack", stackParams, stackSources);
        assertNotNull("CreateStack (slc) must not be null", stack);

        // 2) Cross-Correlation: find GCP shifts by correlating amplitude patches between
        //    master and slave. inSAROptimized=true and applyFineRegistration=true so the
        //    sub-pixel refinement uses coherence-based estimator (proper for InSAR).
        final Map<String, Object> ccParams = new HashMap<>();
        ccParams.put("numGCPtoGenerate", 2000);
        ccParams.put("coarseRegistrationWindowWidth", "128");
        ccParams.put("coarseRegistrationWindowHeight", "128");
        ccParams.put("rowInterpFactor", "2");
        ccParams.put("columnInterpFactor", "2");
        ccParams.put("maxIteration", 10);
        ccParams.put("gcpTolerance", 0.5);
        ccParams.put("applyFineRegistration", true);
        ccParams.put("inSAROptimized", true);
        ccParams.put("fineRegistrationWindowWidth", "32");
        ccParams.put("fineRegistrationWindowHeight", "32");
        ccParams.put("fineRegistrationWindowAccAzimuth", "16");
        ccParams.put("fineRegistrationWindowAccRange", "16");
        ccParams.put("fineRegistrationOversampling", "16");
        ccParams.put("coherenceWindowSize", 3);
        ccParams.put("coherenceThreshold", 0.6);
        final Product ccStack = GPF.createProduct("Cross-Correlation", ccParams, stack);
        assertNotNull("Cross-Correlation must not be null", ccStack);

        // 3) Warp: resample slave's complex bands to master's grid using the GCPs found
        //    by Cross-Correlation. CC6P (truncated sinc-cosine, 6-point) is the SNAP
        //    default and the right kernel for complex SLC interpolation.
        final Map<String, Object> warpParams = new HashMap<>();
        warpParams.put("rmsThreshold", "0.05");
        warpParams.put("warpPolynomialOrder", 2);
        warpParams.put("interpolationMethod", "Truncated sinc (6 points)"); // alias for CC6P
        final Product warpedStack = GPF.createProduct("Warp", warpParams, ccStack);
        assertNotNull("Warp must not be null", warpedStack);

        // 4) Interferogram in slant range. Disable flat-earth removal so we measure raw
        //    (R_M − R_S) coherence — fair comparison with the GSLC path. Coherence
        //    window in metres so both pipelines average over the same physical area.
        final Map<String, Object> ifgParams = new HashMap<>();
        ifgParams.put("subtractFlatEarthPhase", false);
        ifgParams.put("subtractTopographicPhase", false);
        ifgParams.put("includeCoherence", true);
        ifgParams.put("squarePixel", true);
        ifgParams.put("cohWinSizeMeters", COHERENCE_WINDOW_M);
        final Product ifg = GPF.createProduct("Interferogram", ifgParams, warpedStack);
        assertNotNull("Traditional Interferogram must not be null", ifg);
        return ifg;
    }

    private static Product runGslcPipeline(final Product master, final Product slave,
                                            final String imgResampler, final boolean applySET,
                                            final double slaveRangeOffsetPixels,
                                            final double slaveAzimuthOffsetPixels)
            throws Exception {
        return runGslcPipeline(master, slave, imgResampler, applySET,
                slaveRangeOffsetPixels, slaveAzimuthOffsetPixels, false /*outputFlattened*/);
    }

    private static Product runGslcPipeline(final Product master, final Product slave,
                                            final String imgResampler, final boolean applySET,
                                            final double slaveRangeOffsetPixels,
                                            final double slaveAzimuthOffsetPixels,
                                            final boolean outputFlattened)
            throws Exception {
        // Master GSLC — never gets a bias; it defines the grid.
        final Map<String, Object> gslcMasterParams = new HashMap<>();
        gslcMasterParams.put("demName", "Copernicus 30m Global DEM");
        gslcMasterParams.put("imgResamplingMethod", imgResampler);
        gslcMasterParams.put("outputFlattened", outputFlattened);
        gslcMasterParams.put("alignToStandardGrid", true);
        gslcMasterParams.put("standardGridOriginX", 0.0);
        gslcMasterParams.put("standardGridOriginY", 0.0);
        gslcMasterParams.put("nodataValueAtSea", false);
        gslcMasterParams.put("applySolidEarthTide", applySET);
        final Product masterGSLC = GPF.createProduct("GSLC-Terrain-Correction", gslcMasterParams, master);
        assertNotNull("master GSLC must not be null", masterGSLC);

        // Slave GSLC — same params as master, plus the scalar bias correction applied
        // via the new rangeOffsetPixels / azimuthOffsetPixels operator parameters
        // (0.0 when withBias=false at the call site).
        final Map<String, Object> gslcSlaveParams = new HashMap<>(gslcMasterParams);
        gslcSlaveParams.put("rangeOffsetPixels", slaveRangeOffsetPixels);
        gslcSlaveParams.put("azimuthOffsetPixels", slaveAzimuthOffsetPixels);
        final Product slaveGSLC = GPF.createProduct("GSLC-Terrain-Correction", gslcSlaveParams, slave);
        assertNotNull("slave GSLC must not be null", slaveGSLC);

        // CreateStack(master_GSLC, slave_GSLC) — both already geocoded, the
        // geocoded-offset path resolves the integer-pixel alignment.
        final Map<String, Product> stackSources = new HashMap<>();
        stackSources.put("sourceProduct.1", masterGSLC);
        stackSources.put("sourceProduct.2", slaveGSLC);
        final Map<String, Object> stackParams = new HashMap<>();
        stackParams.put("extent", "Master");
        stackParams.put("initialOffsetMethod", "Orbit");
        stackParams.put("resamplingType", "NONE");
        final Product stack = GPF.createProduct("CreateStack", stackParams, stackSources);
        assertNotNull("CreateStack must not be null", stack);

        // Interferogram. The GSLC branch inside InterferogramOp auto-detects
        // is_terrain_corrected=1 and does the right thing (raw conj product + coherence).
        // Same metres-based coherence window as traditional so the comparison is fair.
        final Map<String, Object> ifgParams = new HashMap<>();
        ifgParams.put("subtractFlatEarthPhase", false);
        ifgParams.put("subtractTopographicPhase", false);
        ifgParams.put("includeCoherence", true);
        ifgParams.put("cohWinSizeMeters", COHERENCE_WINDOW_M);
        final Product ifg = GPF.createProduct("Interferogram", ifgParams, stack);
        assertNotNull("GSLC Interferogram must not be null", ifg);
        return ifg;
    }

    private static double computeMeanCoherence(final Product interferogram, final String label) throws Exception {
        Band cohBand = null;
        for (final Band b : interferogram.getBands()) {
            if (b.getUnit() != null && b.getUnit().contains(Unit.COHERENCE)) {
                cohBand = b;
                break;
            }
        }
        assertNotNull("coherence band missing in " + label, cohBand);

        final int w = interferogram.getSceneRasterWidth();
        final int h = interferogram.getSceneRasterHeight();
        // Sample to keep memory bounded: 4-megapixel grid max
        final int strideX = Math.max(1, (int) Math.ceil(Math.sqrt((double) w * h / 4_000_000)));
        final int strideY = strideX;
        final int sw = (w + strideX - 1) / strideX;
        final int sh = (h + strideY - 1) / strideY;

        double sum = 0.0;
        long valid = 0;
        final float[] row = new float[w];
        for (int y = 0; y < h; y += strideY) {
            cohBand.readPixels(0, y, w, 1, row, ProgressMonitor.NULL);
            for (int x = 0; x < w; x += strideX) {
                final float c = row[x];
                if (!Float.isNaN(c) && c > 0.0f && c <= 1.0001f) {
                    sum += c;
                    valid++;
                }
            }
        }
        System.out.printf("  [%s] sampled %d valid pixels (%dx%d at stride %d)%n",
                label, valid, sw, sh, strideX);
        return valid > 0 ? sum / valid : 0.0;
    }
}
