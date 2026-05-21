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
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Placemark;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductNodeGroup;
import org.esa.snap.core.gpf.GPF;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assume.assumeTrue;

/**
 * Step-by-step diagnostic for the GSLC pipeline on the user's ENVISAT ASAR pair.
 * <p>
 * The tests in this class are <em>not</em> pass/fail in the traditional sense — they
 * print measurements that tell us where the chain is correct and where it falls apart.
 * Run them, read the numbers, decide the next fix.
 * <ul>
 *     <li>{@link #testStep2_PerPixelPhaseConsistency} — does {@code arg(master · conj(slave))}
 *         equal the simulated-phase difference at individual pixels?  If yes, the per-pixel
 *         chain is correct and the noise comes from spatial averaging / sub-pixel drift.
 *         If no, there's a sign / convention bug.</li>
 *     <li>{@link #testStep4_BiasSpatialVariation} — fit a 1st-order polynomial through the
 *         cross-correlation GCP shifts; report the linear coefficients and the residual.
 *         Tells us whether scalar bias suffices or whether polynomial / LUT bias is
 *         needed for the full scene.</li>
 * </ul>
 */
public class GSLCDiagnosticTest extends ProcessorTest {

    private static final File MASTER_FILE = new File(
            "E:/out/ASA_IMS_1PNUPA20031203_061259_000000162022_00120_09192_0099_Orb.dim");
    private static final File SLAVE_FILE  = new File(
            "E:/out/ASA_IMS_1PXPDE20040211_061300_000000142024_00120_10194_0013_Orb.dim");

    /** geoRegion box for the subset diagnostic (~8 km × 8 km, mid-scene). */
    private static final double CENTRE_LAT = 29.13;
    private static final double CENTRE_LON = 58.45;
    private static final double HALF_SPAN_DEG = 0.038;

    // ---------------------------------------------------------------------------
    // STEP 2 — per-pixel phase consistency
    // ---------------------------------------------------------------------------

    @Test
    public void testStep2_PerPixelPhaseConsistency() throws Exception {
        assumeTrue(MASTER_FILE + " not found", MASTER_FILE.exists());
        assumeTrue(SLAVE_FILE + " not found", SLAVE_FILE.exists());
        GPF.getDefaultInstance().getOperatorSpiRegistry().loadOperatorSpis();

        System.out.println("\n=== STEP 2: per-pixel GSLC phase consistency ===");

        // Build master + slave GSLC on an 8 km × 8 km subset, with simulatedUnwrappedPhase
        // enabled on BOTH so we can compare actual interferogram phase to the
        // orbit-+-DEM-predicted differential phase.
        try (final Product masterFull = TestUtils.readSourceProduct(MASTER_FILE);
             final Product slaveFull  = TestUtils.readSourceProduct(SLAVE_FILE)) {

            final String wkt = wktBox(CENTRE_LON, CENTRE_LAT, HALF_SPAN_DEG);
            final Product masterSlc = subsetByGeoRegion(masterFull, wkt);
            final Product slaveSlc  = subsetByGeoRegion(slaveFull,  wkt);
            System.out.printf("master subset: %d x %d%n",
                    masterSlc.getSceneRasterWidth(), masterSlc.getSceneRasterHeight());
            System.out.printf("slave  subset: %d x %d%n",
                    slaveSlc.getSceneRasterWidth(),  slaveSlc.getSceneRasterHeight());

            // Estimate scalar bias so the slave is at least roughly aligned.
            final double[] bias = estimateScalarBias(masterSlc, slaveSlc);
            System.out.printf("scalar bias: Δrange=%+.4f px  Δazimuth=%+.4f px%n", bias[0], bias[1]);

            final Product masterGslc = runGslc(masterSlc, "BICUBIC_INTERPOLATION", 0.0, 0.0);
            final Product slaveGslc  = runGslc(slaveSlc,  "BICUBIC_INTERPOLATION", bias[0], bias[1]);

            // Pick high-amplitude pixels for stable scatterers (rocks/buildings).
            final Band iM = findBand(masterGslc, Unit.REAL);
            final Band qM = findBand(masterGslc, Unit.IMAGINARY);
            final Band iS = findBand(slaveGslc,  Unit.REAL);
            final Band qS = findBand(slaveGslc,  Unit.IMAGINARY);
            // IMPORTANT: use simulatedPhase (already wrapped to [-π, π]) rather than
            // simulatedUnwrappedPhase. The unwrapped band stores 4π·R/λ ~ 1.9e8 radians for
            // ASAR; when stored as float32 it has ~22 rad of precision noise, so wrapping
            // it back gives uniform random values — useless for this comparison.
            // The wrapped phase fits in [-π, π] and survives float32 round-trip cleanly.
            final Band simM = masterGslc.getBand("simulatedPhase");
            final Band simS = slaveGslc.getBand("simulatedPhase");
            org.junit.Assert.assertNotNull("simulatedPhase missing on master", simM);
            org.junit.Assert.assertNotNull("simulatedPhase missing on slave",  simS);

            final int w = masterGslc.getSceneRasterWidth();
            final int h = masterGslc.getSceneRasterHeight();
            // Use a sparse grid of well-spaced pixels to avoid clustering.
            final List<int[]> picks = pickHighAmplitudePixels(iM, qM, w, h, 80);
            System.out.printf("Selected %d high-amplitude diagnostic pixels.%n%n", picks.size());

            final List<Double> diffs = new ArrayList<>();
            int valid = 0;
            System.out.println("    i,    j |  |a_M|  arg_M |  |a_S|  arg_S |  ifg_act  ifg_exp   diff");
            System.out.println("----------+--------------+--------------+----------------------------");
            for (final int[] p : picks) {
                final int i = p[0], j = p[1];
                final float[] one = new float[1];
                iM.readPixels(i, j, 1, 1, one); final double miR = one[0];
                qM.readPixels(i, j, 1, 1, one); final double miQ = one[0];
                iS.readPixels(i, j, 1, 1, one); final double siR = one[0];
                qS.readPixels(i, j, 1, 1, one); final double siQ = one[0];
                simM.readPixels(i, j, 1, 1, one); final double sM = one[0];
                simS.readPixels(i, j, 1, 1, one); final double sS = one[0];

                final double aM = Math.hypot(miR, miQ);
                final double aS = Math.hypot(siR, siQ);
                if (aM < 5.0 || aS < 5.0) continue;
                if (Double.isNaN(sM) || Double.isNaN(sS)) continue;

                final double argM = Math.atan2(miQ, miR);
                final double argS = Math.atan2(siQ, siR);
                final double ifgI = miR * siR + miQ * siQ;     // master · conj(slave)
                final double ifgQ = miQ * siR - miR * siQ;
                final double ifgActual = Math.atan2(ifgQ, ifgI);
                // Expected ifg phase: master · conj(slave) = exp(j(φ_M − φ_S)) where
                // φ_M = −simM (since simulatedUnwrappedPhase stores +4π·R/λ and the SLC
                // has −4π·R/λ in its phase), φ_S = −simS. So φ_M − φ_S = simS − simM.
                final double ifgExpected = wrap(sS - sM);
                final double diff = wrap(ifgActual - ifgExpected);

                if (valid < 25) {
                    System.out.printf("%5d,%5d | %6.1f %+6.3f | %6.1f %+6.3f | %+7.3f %+7.3f %+7.3f%n",
                            i, j, aM, argM, aS, argS, ifgActual, ifgExpected, diff);
                }
                diffs.add(diff);
                valid++;
            }
            System.out.println();
            if (diffs.isEmpty()) {
                System.out.println("NO valid bright pixels — diagnostic failed to pick samples.");
                return;
            }

            // Phase-circular statistics: mean and concentration of (actual − expected)
            // around the unit circle.
            double sumCos = 0.0, sumSin = 0.0;
            for (final double d : diffs) {
                sumCos += Math.cos(d);
                sumSin += Math.sin(d);
            }
            final double meanDir = Math.atan2(sumSin, sumCos);
            final double meanRes = Math.hypot(sumCos, sumSin) / diffs.size();  // 1.0 = perfect
            // Linear stats just to give a feel
            Collections.sort(diffs);
            final double median = diffs.get(diffs.size() / 2);
            final double q1 = diffs.get(diffs.size() / 4);
            final double q3 = diffs.get((3 * diffs.size()) / 4);

            System.out.printf("DIAGNOSTIC SUMMARY (N=%d bright pixels):%n", diffs.size());
            System.out.printf("  circular mean direction  = %+.4f rad  (= %+.1f deg)%n",
                    meanDir, Math.toDegrees(meanDir));
            System.out.printf("  circular concentration R = %.4f   (1.0 = perfect, 0.0 = uniform random)%n",
                    meanRes);
            System.out.printf("  median diff             = %+.4f rad%n", median);
            System.out.printf("  IQR (q3 − q1)            = %.4f rad%n", q3 - q1);
            System.out.println();
            System.out.println("Interpretation:");
            System.out.println("  R > 0.7 and IQR < 1 rad  →  chain is correct per-pixel; scattering phase is the residual");
            System.out.println("  R ≈ 0 and IQR ≈ π        →  per-pixel phase is random → fundamental bug somewhere upstream");
            System.out.println("  intermediate            →  partial correlation; mixed bag of correct + corrupted pixels");
        }
    }

    // ---------------------------------------------------------------------------
    // STEP 4 — bias spatial variation
    // ---------------------------------------------------------------------------

    @Test
    public void testStep4_BiasSpatialVariation() throws Exception {
        assumeTrue(MASTER_FILE + " not found", MASTER_FILE.exists());
        assumeTrue(SLAVE_FILE + " not found", SLAVE_FILE.exists());
        GPF.getDefaultInstance().getOperatorSpiRegistry().loadOperatorSpis();

        System.out.println("\n=== STEP 4: CC-shift spatial variation across the full pair ===");

        // Use a LARGE subset (~50 km × 50 km mid-scene). Full-scene CC is too slow
        // for a regression test; this captures the spatial drift if it exists.
        final String wkt = wktBox(CENTRE_LON, CENTRE_LAT, 0.25);
        try (final Product masterFull = TestUtils.readSourceProduct(MASTER_FILE);
             final Product slaveFull  = TestUtils.readSourceProduct(SLAVE_FILE)) {

            final Product masterSlc = subsetByGeoRegion(masterFull, wkt);
            final Product slaveSlc  = subsetByGeoRegion(slaveFull,  wkt);
            final int w = masterSlc.getSceneRasterWidth();
            final int h = masterSlc.getSceneRasterHeight();
            System.out.printf("master subset: %d x %d%n", w, h);
            System.out.printf("slave  subset: %d x %d%n", slaveSlc.getSceneRasterWidth(),
                    slaveSlc.getSceneRasterHeight());

            // Stack + CC
            final Map<String, Product> stackSources = new HashMap<>();
            stackSources.put("sourceProduct.1", masterSlc);
            stackSources.put("sourceProduct.2", slaveSlc);
            final Map<String, Object> stackParams = new HashMap<>();
            stackParams.put("extent", "Master");
            stackParams.put("initialOffsetMethod", "Orbit");
            stackParams.put("resamplingType", "NONE");
            final Product stack = GPF.createProduct("CreateStack", stackParams, stackSources);

            final Map<String, Object> ccParams = new HashMap<>();
            ccParams.put("numGCPtoGenerate", 2000);
            ccParams.put("coarseRegistrationWindowWidth", "128");
            ccParams.put("coarseRegistrationWindowHeight", "128");
            ccParams.put("applyFineRegistration", true);
            ccParams.put("inSAROptimized", true);
            ccParams.put("coherenceThreshold", 0.6);
            final Product cc = GPF.createProduct("Cross-Correlation", ccParams, stack);

            // Read the slave band to trigger CC matching (populates GCPs).
            Band masterBand = null, slaveBand = null;
            for (final Band b : cc.getBands()) {
                if (b.getUnit() != null && b.getUnit().equals(Unit.REAL)) {
                    if (masterBand == null) masterBand = b;
                    else if (slaveBand == null) { slaveBand = b; break; }
                }
            }
            org.junit.Assert.assertNotNull(masterBand);
            org.junit.Assert.assertNotNull(slaveBand);
            final int sbh = slaveBand.getRasterHeight();
            final int sbw = slaveBand.getRasterWidth();
            final float[] row = new float[sbw];
            for (int y = 0; y < sbh; y += 64) {
                slaveBand.readPixels(0, y, sbw, 1, row, ProgressMonitor.NULL);
            }

            final ProductNodeGroup<Placemark> mGcp = GCPManager.instance().getGcpGroup(masterBand);
            final ProductNodeGroup<Placemark> sGcp = GCPManager.instance().getGcpGroup(slaveBand);
            final List<double[]> data = new ArrayList<>(); // {mx, my, dx, dy}
            for (final Placemark mp : mGcp.toArray(new Placemark[0])) {
                final Placemark sp = sGcp.get(mp.getName());
                if (sp == null) continue;
                data.add(new double[]{
                        mp.getPixelPos().x, mp.getPixelPos().y,
                        sp.getPixelPos().x - mp.getPixelPos().x,
                        sp.getPixelPos().y - mp.getPixelPos().y
                });
            }
            System.out.printf("matched %d GCPs (of %d generated)%n%n",
                    data.size(), mGcp.getNodeCount());
            if (data.isEmpty()) {
                System.out.println("NO matched GCPs — cross-correlation found no coherent patches.");
                return;
            }

            // Basic stats
            statsSection("Δrange (px)",    data, 2);
            statsSection("Δazimuth (px)", data, 3);

            // 1st-order least-squares fit:  dx = a0 + a1·mx + a2·my, same for dy
            final double[] coefDx = fitLinear(data, 2);
            final double[] coefDy = fitLinear(data, 3);

            System.out.printf("Linear fit  Δrange   = %+9.6f %+9.6e·rg %+9.6e·az    (rmse=%6.4f px)%n",
                    coefDx[0], coefDx[1], coefDx[2], coefDx[3]);
            System.out.printf("Linear fit  Δazimuth = %+9.6f %+9.6e·rg %+9.6e·az    (rmse=%6.4f px)%n",
                    coefDy[0], coefDy[1], coefDy[2], coefDy[3]);

            // Predicted shift gradient across the *full* scene (use master FULL dims).
            final int W = masterFull.getSceneRasterWidth();
            final int H = masterFull.getSceneRasterHeight();
            final double gradDxRg = coefDx[1] * W;
            final double gradDxAz = coefDx[2] * H;
            final double gradDyRg = coefDy[1] * W;
            final double gradDyAz = coefDy[2] * H;
            System.out.println();
            System.out.printf("Predicted shift change across the FULL scene (%d × %d):%n", W, H);
            System.out.printf("  Δrange  drifts by %+8.3f px along range,   %+8.3f px along azimuth%n",
                    gradDxRg, gradDxAz);
            System.out.printf("  Δazimuth drifts by %+8.3f px along range,   %+8.3f px along azimuth%n",
                    gradDyRg, gradDyAz);
            System.out.println();
            System.out.println("Interpretation:");
            System.out.println("  drift  < 0.2 px AND rmse < 0.1 px →  scalar bias is sufficient");
            System.out.println("  drift  > 1.0 px OR  rmse > 0.3 px →  polynomial bias is essential");
            System.out.println("  rmse   ~ comparable to drift     →  linear fit is good; higher-order won't help much");
            System.out.println("  rmse  >> drift                   →  noisy CC; bias estimation itself is unreliable");
        }
    }

    // ---------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------

    /**
     * 1st-order least-squares fit: {@code z = a0 + a1·x + a2·y}, returns
     * {@code [a0, a1, a2, rmse]}. {@code data[i] = {x, y, ..., z, ...}} where
     * {@code zIndex} picks the column.
     */
    private static double[] fitLinear(final List<double[]> data, final int zIndex) {
        final int n = data.size();
        // Solve normal equations: A^T A coef = A^T z, where A = [1, x, y]
        double sX=0, sY=0, sZ=0, sXX=0, sXY=0, sYY=0, sXZ=0, sYZ=0;
        for (final double[] d : data) {
            final double x = d[0], y = d[1], z = d[zIndex];
            sX += x;  sY += y;  sZ += z;
            sXX += x*x;  sXY += x*y;  sYY += y*y;
            sXZ += x*z;  sYZ += y*z;
        }
        // 3x3 system
        final double[][] A = {
            { n,   sX,  sY  },
            { sX,  sXX, sXY },
            { sY,  sXY, sYY }
        };
        final double[] b = { sZ, sXZ, sYZ };
        final double[] c = solve3x3(A, b);

        double sse = 0.0;
        for (final double[] d : data) {
            final double pred = c[0] + c[1]*d[0] + c[2]*d[1];
            final double r = d[zIndex] - pred;
            sse += r * r;
        }
        final double rmse = Math.sqrt(sse / n);
        return new double[]{ c[0], c[1], c[2], rmse };
    }

    /** Solve a 3x3 linear system by direct elimination. */
    private static double[] solve3x3(final double[][] A, final double[] b) {
        final double[][] M = new double[3][4];
        for (int i = 0; i < 3; i++) {
            System.arraycopy(A[i], 0, M[i], 0, 3);
            M[i][3] = b[i];
        }
        // Forward elimination with partial pivoting
        for (int k = 0; k < 3; k++) {
            int piv = k;
            for (int i = k + 1; i < 3; i++) {
                if (Math.abs(M[i][k]) > Math.abs(M[piv][k])) piv = i;
            }
            if (piv != k) {
                final double[] tmp = M[k]; M[k] = M[piv]; M[piv] = tmp;
            }
            for (int i = k + 1; i < 3; i++) {
                final double f = M[i][k] / M[k][k];
                for (int j = k; j < 4; j++) M[i][j] -= f * M[k][j];
            }
        }
        final double[] x = new double[3];
        for (int i = 2; i >= 0; i--) {
            double s = M[i][3];
            for (int j = i + 1; j < 3; j++) s -= M[i][j] * x[j];
            x[i] = s / M[i][i];
        }
        return x;
    }

    private static void statsSection(final String label, final List<double[]> data, final int col) {
        final List<Double> v = new ArrayList<>(data.size());
        for (final double[] d : data) v.add(d[col]);
        Collections.sort(v);
        double s = 0, ss = 0;
        for (final double x : v) { s += x; ss += x * x; }
        final double mean = s / v.size();
        final double sd = Math.sqrt(ss / v.size() - mean * mean);
        System.out.printf("%-14s  N=%d  mean=%+.4f  std=%6.4f  min=%+.4f  max=%+.4f  median=%+.4f%n",
                label, v.size(), mean, sd, v.get(0), v.get(v.size() - 1), v.get(v.size() / 2));
    }

    /** Random sparse sampling — biased toward high-amplitude (likely-stable) pixels. */
    private static List<int[]> pickHighAmplitudePixels(final Band iBand, final Band qBand,
                                                        final int w, final int h,
                                                        final int target) throws Exception {
        final Random rnd = new Random(424242);
        final List<int[]> picks = new ArrayList<>();
        // Stride sampling: take a checkerboard, score by intensity, keep top quantile.
        final int stride = Math.max(1, (int) Math.sqrt((long) w * h / (10 * target)));
        final List<double[]> candidates = new ArrayList<>(); // {i, j, intensity}
        final float[] one = new float[1];
        for (int j = 5; j < h - 5; j += stride) {
            for (int i = 5; i < w - 5; i += stride) {
                iBand.readPixels(i, j, 1, 1, one); final double a = one[0];
                qBand.readPixels(i, j, 1, 1, one); final double b = one[0];
                final double mag = Math.hypot(a, b);
                if (mag > 1.0) candidates.add(new double[]{i, j, mag});
            }
        }
        // Sort by intensity descending; take top `target`.
        candidates.sort((x, y) -> Double.compare(y[2], x[2]));
        for (int k = 0; k < Math.min(target, candidates.size()); k++) {
            picks.add(new int[]{(int) candidates.get(k)[0], (int) candidates.get(k)[1]});
        }
        return picks;
    }

    private static double wrap(final double phase) {
        double p = phase;
        while (p >  Math.PI) p -= 2 * Math.PI;
        while (p < -Math.PI) p += 2 * Math.PI;
        return p;
    }

    private static Band findBand(final Product p, final String unit) {
        for (final Band b : p.getBands()) {
            if (b.getUnit() != null && b.getUnit().equals(unit)) return b;
        }
        return null;
    }

    private static String wktBox(final double centreLon, final double centreLat, final double halfSpan) {
        final double l = centreLon - halfSpan, r = centreLon + halfSpan;
        final double s = centreLat - halfSpan, n = centreLat + halfSpan;
        return String.format("POLYGON((%f %f, %f %f, %f %f, %f %f, %f %f))",
                l, s, r, s, r, n, l, n, l, s);
    }

    private static Product subsetByGeoRegion(final Product source, final String wkt) {
        final Map<String, Object> params = new HashMap<>();
        params.put("geoRegion", wkt);
        params.put("copyMetadata", true);
        return GPF.createProduct("Subset", params, source);
    }

    private static Product runGslc(final Product source, final String resampler,
                                    final double rangeOffsetPx, final double azimuthOffsetPx) {
        final Map<String, Object> params = new HashMap<>();
        params.put("demName", "Copernicus 30m Global DEM");
        params.put("imgResamplingMethod", resampler);
        params.put("outputFlattened", false);
        params.put("alignToStandardGrid", true);
        params.put("standardGridOriginX", 0.0);
        params.put("standardGridOriginY", 0.0);
        params.put("nodataValueAtSea", false);
        params.put("rangeOffsetPixels", rangeOffsetPx);
        params.put("azimuthOffsetPixels", azimuthOffsetPx);
        params.put("saveSimulatedUnwrappedPhase", true);
        params.put("saveSimulatedPhase", true);
        return GPF.createProduct("GSLC-Terrain-Correction", params, source);
    }

    /** Re-implementation of the scalar-bias estimator used by CreateStackOp. Same logic. */
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
        final int h = slaveBand.getRasterHeight();
        final int w = slaveBand.getRasterWidth();
        final float[] row = new float[w];
        for (int y = 0; y < h; y += 64) {
            slaveBand.readPixels(0, y, w, 1, row, ProgressMonitor.NULL);
        }
        final ProductNodeGroup<Placemark> mGcp = GCPManager.instance().getGcpGroup(masterBand);
        final ProductNodeGroup<Placemark> sGcp = GCPManager.instance().getGcpGroup(slaveBand);
        final List<Double> dxs = new ArrayList<>(), dys = new ArrayList<>();
        for (final Placemark mp : mGcp.toArray(new Placemark[0])) {
            final Placemark sp = sGcp.get(mp.getName());
            if (sp == null) continue;
            dxs.add((double)(sp.getPixelPos().x - mp.getPixelPos().x));
            dys.add((double)(sp.getPixelPos().y - mp.getPixelPos().y));
        }
        Collections.sort(dxs);
        Collections.sort(dys);
        final int n = dxs.size();
        if (n == 0) return new double[]{0, 0};
        final double dx = (n % 2 == 0) ? 0.5 * (dxs.get(n/2 - 1) + dxs.get(n/2)) : dxs.get(n/2);
        final double dy = (n % 2 == 0) ? 0.5 * (dys.get(n/2 - 1) + dys.get(n/2)) : dys.get(n/2);
        return new double[]{ dx, dy };
    }
}
