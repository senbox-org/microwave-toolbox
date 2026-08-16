package eu.esa.sar.insar.gpf;

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.CrsGeoCoding;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * GSLC-mode residual-ramp removal ({@code subtractResidualRamp}) on a synthetic stack whose
 * interferogram is a pure phase plane — the artifact left by cross-acquisition GSLC
 * interferometry (annotation-vs-data deramp mismatch, measured ~0.09/0.03 rad/px on a real
 * S1A/S1D pair). With the option on, the output phase must be constant; with it off, the plane
 * must survive untouched.
 */
public class TestGslcResidualRamp {

    private static final int SIZE = 2048;
    private static final double FX = 0.09;   // rad/px, the real pair's measured ramp
    private static final double FY = 0.03;

    private static Product createSyntheticGslcStack() throws Exception {
        final Product p = new Product("gslcStack", "GSLC", SIZE, SIZE);
        final ProductData.UTC t0 = AbstractMetadata.parseUTC("23-JUN-2026 22:50:52.310630");
        final ProductData.UTC t1 = AbstractMetadata.parseUTC("23-JUN-2026 22:51:20.000000");
        p.setStartTime(t0);
        p.setEndTime(t1);
        final MetadataElement abs = AbstractMetadata.addAbstractedMetadataHeader(p.getMetadataRoot());
        abs.setAttributeUTC(AbstractMetadata.first_line_time, t0);
        abs.setAttributeUTC(AbstractMetadata.last_line_time, t1);
        abs.setAttributeInt(AbstractMetadata.is_terrain_corrected, 1);
        p.setSceneGeoCoding(new CrsGeoCoding(DefaultGeographicCRS.WGS84,
                SIZE, SIZE, -68.0, 10.0, 1.2566e-4, 1.2566e-4));

        final float[] one = new float[SIZE * SIZE];
        final float[] zero = new float[SIZE * SIZE];
        final float[] si = new float[SIZE * SIZE];
        final float[] sq = new float[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                final int k = y * SIZE + x;
                one[k] = 1f;
                final double ph = FX * x + FY * y;
                // sec = exp(-j*ph)  =>  ifg = ref * conj(sec) = exp(+j*ph)
                si[k] = (float) Math.cos(ph);
                sq[k] = (float) -Math.sin(ph);
            }
        }
        addBand(p, "i_ref_23Jun2026", Unit.REAL, one);
        addBand(p, "q_ref_23Jun2026", Unit.IMAGINARY, zero);
        addBand(p, "i_sec1_30Jun2026", Unit.REAL, si);
        addBand(p, "q_sec1_30Jun2026", Unit.IMAGINARY, sq);
        return p;
    }

    private static void addBand(final Product p, final String name, final String unit, final float[] data) {
        final Band b = new Band(name, ProductData.TYPE_FLOAT32, SIZE, SIZE);
        b.setUnit(unit);
        b.setRasterData(ProductData.createInstance(data));
        p.addBand(b);
    }

    private static double[] runAndMeasure(final boolean removeRamp) throws Exception {
        final Product src = createSyntheticGslcStack();
        final InterferogramOp op = new InterferogramOp();
        op.setSourceProduct(src);
        op.setParameter("subtractFlatEarthPhase", false);
        op.setParameter("subtractTopographicPhase", false);
        op.setParameter("includeCoherence", false);
        op.setParameter("subtractResidualRamp", removeRamp);
        final Product tgt = op.getTargetProduct();

        Band bi = null, bq = null;
        for (final Band b : tgt.getBands()) {
            if (b.getName().startsWith("i_ifg")) bi = b;
            if (b.getName().startsWith("q_ifg")) bq = b;
        }
        assertTrue("ifg bands missing", bi != null && bq != null);

        final int x0 = 256, y0 = 256, n = 1024;
        final float[] ivals = new float[n * n];
        final float[] qvals = new float[n * n];
        bi.readPixels(x0, y0, n, n, ivals);
        bq.readPixels(x0, y0, n, n, qvals);

        // concentration |mean(exp(j*phi))| and rms phase about the mean direction
        double sr = 0, si2 = 0;
        for (int k = 0; k < n * n; k++) {
            final double m = Math.hypot(ivals[k], qvals[k]);
            if (m <= 0) continue;
            sr += ivals[k] / m;
            si2 += qvals[k] / m;
        }
        final double conc = Math.hypot(sr, si2) / (n * n);
        final double mean = Math.atan2(si2, sr);
        double rms = 0;
        int cnt = 0;
        for (int k = 0; k < n * n; k++) {
            if (ivals[k] == 0 && qvals[k] == 0) continue;
            double d = Math.atan2(qvals[k], ivals[k]) - mean;
            while (d > Math.PI) d -= 2 * Math.PI;
            while (d < -Math.PI) d += 2 * Math.PI;
            rms += d * d;
            cnt++;
        }
        rms = Math.sqrt(rms / Math.max(cnt, 1));
        tgt.dispose();
        src.dispose();
        return new double[]{conc, rms};
    }

    @Test
    public void rampIsRemovedWhenEnabled() throws Exception {
        final double[] r = runAndMeasure(true);
        System.out.printf("RAMP-TEST enabled: concentration=%.4f rms=%.4f rad%n", r[0], r[1]);
        assertTrue("phase should be ~constant after ramp removal, concentration=" + r[0], r[0] > 0.98);
        assertTrue("rms residual too large: " + r[1], r[1] < 0.2);
    }

    @Test
    public void rampSurvivesWhenDisabled() throws Exception {
        final double[] r = runAndMeasure(false);
        System.out.printf("RAMP-TEST disabled: concentration=%.4f rms=%.4f rad%n", r[0], r[1]);
        assertTrue("with removal off the plane must remain (concentration ~0), got " + r[0], r[0] < 0.05);
    }

    // ------------------------------------------------------------------
    // Operator-level exercise of residualRamp2D through the FULL application path
    // (computeGslcReferencePhase threading, coherence-extended rect, GslcSurface2D.valueAt
    // at real tile coordinates) — not just the pure fitGslcSurface2D/valueAt unit tests
    // above. A larger scene is used so the estimator's real block grid produces >= 40
    // blocks (the fit's own minimum), matching the conditions under which the surface
    // actually engages in production.
    // ------------------------------------------------------------------

    private static final int BUMP_SIZE = 4096;
    private static final double BUMP_AMP = 8.0;   // rad, smooth 2-D bump on top of the plane

    private static Product createSyntheticGslcStackWithBump() throws Exception {
        final int n = BUMP_SIZE;
        final Product p = new Product("gslcStackBump", "GSLC", n, n);
        final ProductData.UTC t0 = AbstractMetadata.parseUTC("23-JUN-2026 22:50:52.310630");
        final ProductData.UTC t1 = AbstractMetadata.parseUTC("23-JUN-2026 22:51:20.000000");
        p.setStartTime(t0);
        p.setEndTime(t1);
        final MetadataElement abs = AbstractMetadata.addAbstractedMetadataHeader(p.getMetadataRoot());
        abs.setAttributeUTC(AbstractMetadata.first_line_time, t0);
        abs.setAttributeUTC(AbstractMetadata.last_line_time, t1);
        abs.setAttributeInt(AbstractMetadata.is_terrain_corrected, 1);
        p.setSceneGeoCoding(new CrsGeoCoding(DefaultGeographicCRS.WGS84,
                n, n, -68.0, 10.0, 1.2566e-4, 1.2566e-4));

        final float[] one = new float[n * n];
        final float[] zero = new float[n * n];
        final float[] si = new float[n * n];
        final float[] sq = new float[n * n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                final int k = y * n + x;
                one[k] = 1f;
                final double bump = BUMP_AMP * Math.sin(2 * Math.PI * x / n) * Math.cos(Math.PI * y / n);
                final double ph = FX * x + FY * y + bump;
                // sec = exp(-j*ph)  =>  ifg = ref * conj(sec) = exp(+j*ph)
                si[k] = (float) Math.cos(ph);
                sq[k] = (float) -Math.sin(ph);
            }
        }
        addBand(p, "i_ref_23Jun2026", Unit.REAL, one, n);
        addBand(p, "q_ref_23Jun2026", Unit.IMAGINARY, zero, n);
        addBand(p, "i_sec1_30Jun2026", Unit.REAL, si, n);
        addBand(p, "q_sec1_30Jun2026", Unit.IMAGINARY, sq, n);
        return p;
    }

    private static void addBand(final Product p, final String name, final String unit, final float[] data,
                                 final int size) {
        final Band b = new Band(name, ProductData.TYPE_FLOAT32, size, size);
        b.setUnit(unit);
        b.setRasterData(ProductData.createInstance(data));
        p.addBand(b);
    }

    @Test
    public void surface2DApplicationPathProducesNonzeroOutput() throws Exception {
        final Product src = createSyntheticGslcStackWithBump();
        final InterferogramOp op = new InterferogramOp();
        op.setSourceProduct(src);
        op.setParameter("subtractFlatEarthPhase", false);
        op.setParameter("subtractTopographicPhase", false);
        // includeCoherence left at its default (true): the coherence-extended rect
        // (cohRect, which can differ from targetRectangle at tile/scene edges) is exactly
        // the path implicated in the reported production failure.
        op.setParameter("subtractResidualRamp", true);
        op.setParameter("residualRamp2D", true);
        final Product tgt = op.getTargetProduct();

        Band bi = null, bq = null;
        for (final Band b : tgt.getBands()) {
            if (b.getName().startsWith("i_ifg")) bi = b;
            if (b.getName().startsWith("q_ifg")) bq = b;
        }
        assertTrue("ifg bands missing", bi != null && bq != null);

        final int n = BUMP_SIZE;
        final float[] ivals = new float[n * n];
        final float[] qvals = new float[n * n];
        bi.readPixels(0, 0, n, n, ivals);
        bq.readPixels(0, 0, n, n, qvals);

        long nonzero = 0;
        double sr = 0, si2 = 0;
        for (int k = 0; k < n * n; k++) {
            final double m = Math.hypot(ivals[k], qvals[k]);
            if (m <= 0) continue;
            nonzero++;
            sr += ivals[k] / m;
            si2 += qvals[k] / m;
        }
        final double conc = Math.hypot(sr, si2) / (n * n);
        System.out.printf("SURFACE2D-APPLICATION-TEST: nonzero=%d/%d concentration=%.4f%n",
                nonzero, (long) n * n, conc);
        tgt.dispose();
        src.dispose();

        // This is the assertion that catches the reported bug: GPF zero-fills a tile whose
        // computeTile throws, so an application-path exception shows up as "mostly zeros"
        // rather than a visible stack trace.
        assertTrue("output must not be (almost) all zeros — nonzero=" + nonzero + " of " + (n * n),
                nonzero > 0.9 * (long) n * n);
        // Measured 0.8816 on this fixture (10x10-node fit of an 8-rad sinusoidal bump from 63
        // blocks, no noise) — comfortably above "removal did nothing" (which would land near 0,
        // as in rampSurvivesWhenDisabled) but short of the near-1.0 seen for a pure plane
        // (rampIsRemovedWhenEnabled) because a coarse node grid cannot fully absorb an 8-rad
        // bump from this few samples. 0.8 gives margin while still requiring the application
        // path to have actually removed most of the injected phase, not just avoided crashing.
        assertTrue("residual concentration too low with ramp+surface removal enabled: " + conc,
                conc > 0.8);
    }

    // ------------------------------------------------------------------
    // Polynomial machinery at configurable degree (residualRampDegree):
    // motivated by 1995 ERS VMP tandem pairs whose annotation-phase surface
    // is smoothly CURVED — a quadratic left ~150 rad of arcs on the real pair.
    // ------------------------------------------------------------------

    /** Gradient samples of a known polynomial surface on a grid, as the estimator sees them. */
    private static java.util.List<double[]> gradientSamplesOf(final double[] coef, final int extent) {
        final java.util.List<double[]> s = new java.util.ArrayList<>();
        for (int y = 200; y < extent; y += extent / 9) {
            for (int x = 200; x < extent; x += extent / 9) {
                s.add(new double[]{x, y,
                        InterferogramOp.gslcRampFx(coef, x, y),
                        InterferogramOp.gslcRampFy(coef, x, y), 1.0, -1});
            }
        }
        return s;
    }

    @Test
    public void rampTermCountsPerDegree() {
        assertTrue(InterferogramOp.gslcRampTerms(2).length == 5);
        assertTrue(InterferogramOp.gslcRampTerms(3).length == 9);
        assertTrue(InterferogramOp.gslcRampTerms(4).length == 14);
    }

    @Test
    public void cubicSurfaceRecoveredAtDegree3ButNotDegree2() {
        // a curved (cubic) surface of the measured ERS scale: ~150 rad of beyond-quadratic arcs
        final double[] truth = new double[9];
        truth[0] = -60.0;  // x
        truth[1] = 15.0;   // y
        truth[2] = -0.5;   // x^2
        truth[4] = -0.8;   // y^2
        truth[5] = 0.9;    // x^3
        truth[8] = -1.1;   // y^3
        final int extent = 20000;
        final java.util.List<double[]> samples = gradientSamplesOf(truth, extent);

        final double[] c3 = InterferogramOp.fitGslcRamp(samples, 3);
        final double[] c2 = InterferogramOp.fitGslcRamp(samples, 2);
        double max3 = 0, max2 = 0;
        for (int y = 500; y < extent; y += 1500) {
            for (int x = 500; x < extent; x += 1500) {
                final double t = InterferogramOp.gslcRampPhase(truth, x, y);
                max3 = Math.max(max3, Math.abs(t - InterferogramOp.gslcRampPhase(c3, x, y)));
                // degree-2 fit misses only the cubic part; compare gradients-implied phase
                max2 = Math.max(max2, Math.abs(t - InterferogramOp.gslcRampPhase(c2, x, y)));
            }
        }
        System.out.printf("RAMP-DEGREE-TEST: max|err| degree3=%.3f rad, degree2=%.3f rad%n", max3, max2);
        assertTrue("degree-3 fit must reproduce a cubic surface (max err " + max3 + ")", max3 < 0.5);
        assertTrue("degree-2 fit must NOT reproduce a cubic surface (max err " + max2 + ")", max2 > 20.0);
    }

    @Test
    public void rangeProfileRecoversNonPolynomialShape() {
        // ERS-VMP-scale scenario: phase = S(R), smooth but beyond low-order polynomials
        // (measured −58 rad over 21 km with curvature and a mid-span shoulder).
        final double R0 = 830_000, R1 = 852_000;
        final java.util.function.DoubleUnaryOperator S = R -> {
            final double u = (R - R0) / (R1 - R0);
            return -58.0 * u + 8.0 * Math.sin(2.5 * Math.PI * u) * u;
        };
        final java.util.function.DoubleUnaryOperator Sprime = R -> {
            final double h = 1.0;
            return (S.applyAsDouble(R + h) - S.applyAsDouble(R - h)) / (2 * h);
        };
        // blocks spread over the span; map geometry: R ≈ R0 + 1.1*x, weak y-dependence
        final java.util.List<double[]> samples = new java.util.ArrayList<>();
        final java.util.Random rnd = new java.util.Random(5);
        for (int i = 0; i < 80; i++) {
            final double R = R0 + rnd.nextDouble() * (R1 - R0);
            final double dRdx = 1.1, dRdy = 0.12;
            final double sp = Sprime.applyAsDouble(R);
            samples.add(new double[]{R,
                    dRdx, dRdy,
                    sp * dRdx + 1e-4 * rnd.nextGaussian(),
                    sp * dRdy + 1e-4 * rnd.nextGaussian(), 1.0});
        }
        final InterferogramOp.GslcRangeProfile prof =
                InterferogramOp.fitGslcRangeProfile(samples, 12);
        assertTrue("profile fit must succeed", prof != null);
        double maxErr = 0;
        for (double R = R0 + 1500; R < R1 - 1500; R += 500) {
            final double truth = S.applyAsDouble(R) - S.applyAsDouble(prof.knotR[0]);
            maxErr = Math.max(maxErr, Math.abs(truth - prof.valueAt(R)));
        }
        System.out.printf("RANGE-PROFILE-TEST: max|err| = %.2f rad over %.0f rad excursion%n",
                maxErr, prof.excursion());
        assertTrue("profile must reproduce the curved shape (max err " + maxErr + " rad)",
                maxErr < 2.0);
        assertTrue("excursion must be the injected scale", prof.excursion() > 30.0);
    }

    @Test
    public void rangeProfileRejectsSparseBlocks() {
        final java.util.List<double[]> few = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            few.add(new double[]{830_000 + i * 1000, 1.1, 0.1, 0.01, 0.001, 1.0});
        }
        assertTrue(InterferogramOp.fitGslcRangeProfile(few, 12) == null);
    }

    @Test
    public void degree2FitMatchesHistoricalLayout() {
        // pure quadratic surface: degree-2 fit must recover the exact coefficients
        final double[] truth = {-69.1, 18.3, -0.276, -1.07, -0.020};
        final java.util.List<double[]> samples = gradientSamplesOf(truth, 19000);
        final double[] c = InterferogramOp.fitGslcRamp(samples, 2);
        for (int k = 0; k < 5; k++) {
            assertTrue("coef " + k + ": " + c[k] + " vs " + truth[k],
                    Math.abs(c[k] - truth[k]) < 1e-6 * Math.max(1.0, Math.abs(truth[k])));
        }
    }

    // ------------------------------------------------------------------
    // Bounded 2-D residual phase surface (residualRamp2D): the archive-data closure for
    // phase errors below any registration estimator's floor (see task-A-brief.md). Fitted
    // from block fringe-gradient residuals left after the polynomial ramp (and, when active,
    // the range profile) on a 10x10 bilinear node grid.
    // ------------------------------------------------------------------

    @Test
    public void surface2DRecoversSmoothField() {
        final int W = 12000, H = 8000;
        final java.util.function.DoubleBinaryOperator phi = (x, y) ->
                40.0 * Math.sin(2 * Math.PI * x / W) * Math.cos(Math.PI * y / H) + 25.0 * (y / H) * (y / H);
        final java.util.function.DoubleBinaryOperator dPhiDx = (x, y) ->
                40.0 * (2 * Math.PI / W) * Math.cos(2 * Math.PI * x / W) * Math.cos(Math.PI * y / H);
        final java.util.function.DoubleBinaryOperator dPhiDy = (x, y) ->
                -40.0 * (Math.PI / H) * Math.sin(2 * Math.PI * x / W) * Math.sin(Math.PI * y / H)
                        + 50.0 * y / ((double) H * H);

        // Fix round 2: fitGslcSurface2D now adaptively shrinks the node grid below 10x10 when
        // samples.size() < 4*NX*NY (see that method's javadoc) — a real fix for the production
        // oscillation bug, but it means THIS test (which targets full 10x10-resolution recovery)
        // needs >= 400 samples to stay at 10x10, not the original 8x9=72 (which would now shrink
        // to the 5x5 floor and fail this test's tighter, full-resolution tolerance below). 21x20
        // = 420 samples, still spanning the full scene like the original 8x9 grid.
        final java.util.Random rnd = new java.util.Random(7);
        final java.util.List<double[]> samples = new java.util.ArrayList<>();
        for (int j = 0; j < 24; j++) {
            final double y = j * (H - 1) / 23.0;
            for (int i = 0; i < 26; i++) {
                final double x = i * (W - 1) / 25.0;
                final double fx = dPhiDx.applyAsDouble(x, y) + 0.001 * rnd.nextGaussian();
                final double fy = dPhiDy.applyAsDouble(x, y) + 0.001 * rnd.nextGaussian();
                samples.add(new double[]{x, y, fx, fy, 1.0});
            }
        }

        final InterferogramOp.GslcSurface2D surf = InterferogramOp.fitGslcSurface2D(samples, W, H);
        assertTrue("surface fit must succeed", surf != null);

        // compare after removing each field's mean over a 7x7 comparison grid — the mean node
        // value is unobservable from gradients alone and is pinned to zero, not to phi's mean.
        // The grid is inset 20% from the scene edges: right at the domain boundary the outermost
        // node row/column is seen from only one side by the 8x9 sample grid, so comparing exactly
        // at x=0/x=W-1 tests boundary extrapolation rather than the smooth-field recovery this
        // test targets.
        final double[][] truth = new double[7][7];
        final double[][] fit = new double[7][7];
        double truthMean = 0, fitMean = 0;
        final double lo = 0.20, hi = 0.80;
        for (int k = 0; k < 7; k++) {
            final double x = (lo + k * (hi - lo) / 6.0) * (W - 1);
            for (int l = 0; l < 7; l++) {
                final double y = (lo + l * (hi - lo) / 6.0) * (H - 1);
                truth[k][l] = phi.applyAsDouble(x, y);
                fit[k][l] = surf.valueAt(x, y);
                truthMean += truth[k][l];
                fitMean += fit[k][l];
            }
        }
        truthMean /= 49.0;
        fitMean /= 49.0;
        double maxErr = 0;
        for (int k = 0; k < 7; k++) {
            for (int l = 0; l < 7; l++) {
                final double err = Math.abs((truth[k][l] - truthMean) - (fit[k][l] - fitMean));
                maxErr = Math.max(maxErr, err);
            }
        }
        System.out.printf("SURFACE2D-RECOVERY-TEST: max|err| (mean-removed) = %.3f rad%n", maxErr);
        assertTrue("surface must reproduce the smooth field up to a constant (max err " + maxErr + ")",
                maxErr < 3.0);
    }

    @Test
    public void surface2DHonorsRequestedNodeCount() {
        // The ERS north "arc" field oscillates at ~6+ cycles across the scene — beyond the
        // default 10-node grid's Nyquist (~4.5 cycles) BY DESIGN (deformation safety). The
        // expert residualRamp2DNodes parameter must let a dense grid resolve it: a 24-node
        // fit recovers the field, while the default adaptive fit provably cannot.
        final int W = 12000, H = 8000;
        final double CYC = 6.0;
        final java.util.function.DoubleBinaryOperator phi = (x, y) ->
                30.0 * Math.sin(2 * Math.PI * CYC * y / H);
        final java.util.function.DoubleBinaryOperator dPhiDy = (x, y) ->
                30.0 * (2 * Math.PI * CYC / H) * Math.cos(2 * Math.PI * CYC * y / H);

        // 120x120 = 14400 samples (~25 per node at 24x24): gradient-only node recovery of a
        // field oscillating at the cell scale needs REAL oversampling — measured (NumPy replica
        // of these exact normal equations): 52x52 samples leave 29 rad of error at 24 nodes,
        // 120x120 reach the bilinear representation floor (~12 rad for 6 cycles at 3.8
        // nodes/cycle). This is why the production sampling pass densifies with the requested
        // node count and why the explicit-request shrink rule demands 16 samples/node.
        final java.util.Random rnd = new java.util.Random(11);
        final java.util.List<double[]> samples = new java.util.ArrayList<>();
        for (int j = 0; j < 120; j++) {
            final double y = j * (H - 1) / 119.0;
            for (int i = 0; i < 120; i++) {
                final double x = i * (W - 1) / 119.0;
                final double fy = dPhiDy.applyAsDouble(x, y) + 0.001 * rnd.nextGaussian();
                samples.add(new double[]{x, y, 0.001 * rnd.nextGaussian(), fy, 1.0});
            }
        }

        final InterferogramOp.GslcSurface2D dense =
                InterferogramOp.fitGslcSurface2D(samples, W, H, 24);
        assertTrue("dense surface fit must succeed", dense != null);
        final InterferogramOp.GslcSurface2D coarse =
                InterferogramOp.fitGslcSurface2D(samples, W, H, 0);
        assertTrue("default surface fit must succeed", coarse != null);

        // mean-removed comparison on an inset grid, as surface2DRecoversSmoothField
        final double lo = 0.20, hi = 0.80;
        double denseMax = 0, coarseMax = 0;
        double truthMean = 0, denseMean = 0, coarseMean = 0;
        final int NG = 15;
        final double[][] vals = new double[NG * NG][3];
        int k = 0;
        for (int a = 0; a < NG; a++) {
            final double x = (lo + a * (hi - lo) / (NG - 1)) * (W - 1);
            for (int b = 0; b < NG; b++) {
                final double y = (lo + b * (hi - lo) / (NG - 1)) * (H - 1);
                vals[k][0] = phi.applyAsDouble(x, y);
                vals[k][1] = dense.valueAt(x, y);
                vals[k][2] = coarse.valueAt(x, y);
                truthMean += vals[k][0]; denseMean += vals[k][1]; coarseMean += vals[k][2];
                k++;
            }
        }
        truthMean /= k; denseMean /= k; coarseMean /= k;
        for (int i = 0; i < k; i++) {
            denseMax = Math.max(denseMax, Math.abs((vals[i][0] - truthMean) - (vals[i][1] - denseMean)));
            coarseMax = Math.max(coarseMax, Math.abs((vals[i][0] - truthMean) - (vals[i][2] - coarseMean)));
        }
        System.out.printf("SURFACE2D-NODES-TEST: dense max|err| %.2f rad, coarse max|err| %.2f rad%n",
                denseMax, coarseMax);
        // 15 rad = the bilinear representation floor for 6 cycles at 24 nodes (~12 rad measured)
        // plus margin; the field itself is 60 rad peak-to-peak, so this is a real recovery.
        assertTrue("24-node fit must recover the 6-cycle field to its representation floor " +
                "(max err " + denseMax + ")", denseMax < 15.0);
        assertTrue("default grid must NOT be able to represent 6 cycles (max err " + coarseMax +
                ") — if this fails the safety bound changed", coarseMax > 25.0);
    }

    @Test
    public void surface2DAbsorptionOfCompactLobeIsBounded() {
        // "10-km lobe in a 120-km scene": A=30 rad, sigma=W/12, injected far inside the scene.
        final int W = 120_000, H = 120_000;
        final double A = 30.0;
        final double sigma = W / 12.0;
        final double cx = W / 2.0, cy = H / 2.0;

        final java.util.Random rnd = new java.util.Random(11);
        final java.util.List<double[]> samples = new java.util.ArrayList<>();
        for (int j = 0; j < 20; j++) {
            final double y = 0.05 * H + j * (0.90 * H) / 19.0;
            for (int i = 0; i < 20; i++) {
                final double x = 0.05 * W + i * (0.90 * W) / 19.0;
                final double dx = x - cx, dy = y - cy;
                final double r2 = dx * dx + dy * dy;
                final double phi = A * Math.exp(-r2 / (2 * sigma * sigma));
                final double fx = -phi * dx / (sigma * sigma) + 1e-4 * rnd.nextGaussian();
                final double fy = -phi * dy / (sigma * sigma) + 1e-4 * rnd.nextGaussian();
                samples.add(new double[]{x, y, fx, fy, 1.0});
            }
        }

        final InterferogramOp.GslcSurface2D surf = InterferogramOp.fitGslcSurface2D(samples, W, H);
        assertTrue("surface fit must succeed", surf != null);

        final double absorbed = surf.valueAt(cx, cy) / A;
        System.out.println("=====================================================================");
        System.out.printf("SURFACE2D-LOBE-ABSORPTION-TEST: absorbed fraction = %.4f " +
                "(A=%.0f rad, sigma=%.0f, scene=%dx%d)%n", absorbed, A, sigma, W, H);
        System.out.println("=====================================================================");
        assertTrue("absorption fraction outside the pinned band [0.05, 0.60]: " + absorbed,
                absorbed >= 0.05 && absorbed <= 0.60);
    }

    @Test
    public void surface2DRejectsSparseSamples() {
        final java.util.List<double[]> few = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            // all clustered in one quadrant: neither the count (<40) nor the span (<0.5*W/H)
            // requirement is met.
            few.add(new double[]{100.0 + i * 20.0, 150.0 + i * 15.0, 0.01, 0.01, 1.0});
        }
        assertTrue(InterferogramOp.fitGslcSurface2D(few, 10_000, 8_000) == null);
    }

    // ------------------------------------------------------------------
    // Fix round 2 (production A/B evidence): a fixed 10x10 grid fitted from a production-scale
    // sample count (~58 real samples, dominant-fringe weight gate dropping the eastern third of
    // the scene) is underdetermined enough that per-block measurement noise drives node-to-node
    // oscillation ~3x the true field's own smoothness. That oscillation ADDS fringes to the
    // interferogram (measured gx/gy fringe-ratio going UP with the surface on, not down) instead
    // of removing the smooth arcs it was meant to absorb. Fixed by an adaptive node count
    // (shrinks from 10x10 towards a 5x5 floor until samples give >= 4x oversampling) plus a
    // coverage rule that clamps nodes with zero local sample support to their nearest
    // sample-backed neighbour instead of trusting unconstrained ridge/pin extrapolation.
    // ------------------------------------------------------------------

    @Test
    public void surface2DSparseFitDoesNotOscillate() {
        final int W = 5000, H = 25000;
        // Smooth "arcs" field: amplitude 65 rad, ~1.1 full periods across the scene diagonal from
        // (0.7W, 0.5H) — comparable curvature/amplitude scale to the production report's "smooth
        // arcs of ~130 rad total excursion".
        final java.util.function.DoubleBinaryOperator phi = (x, y) -> {
            final double rx = (x - 0.7 * W) / W, ry = (y - 0.5 * H) / H;
            final double r = Math.sqrt(rx * rx + ry * ry);
            return 65.0 * Math.cos(Math.PI * r * 2.2);
        };
        final double h0 = 1.0;
        final java.util.function.DoubleBinaryOperator dPhiDx = (x, y) ->
                (phi.applyAsDouble(x + h0, y) - phi.applyAsDouble(x - h0, y)) / (2 * h0);
        final java.util.function.DoubleBinaryOperator dPhiDy = (x, y) ->
                (phi.applyAsDouble(x, y + h0) - phi.applyAsDouble(x, y - h0)) / (2 * h0);

        // Production-like sampling: a 5x13 block grid (65 samples, close to the reported 58),
        // with samples only over the western 60% of the scene (the eastern third+ having no
        // coherent-fringe blocks) — "the valid samples cover only the western ~60% of the scene"
        // per the production A/B report.
        final int ncols = 5, nrows = 13;
        final java.util.List<double[]> cleanSamples = new java.util.ArrayList<>();
        for (int j = 0; j < nrows; j++) {
            final double y = j * (H - 1) / (double) (nrows - 1);
            for (int i = 0; i < ncols; i++) {
                final double x = i * (0.6 * (W - 1)) / (ncols - 1);
                cleanSamples.add(new double[]{x, y, dPhiDx.applyAsDouble(x, y), dPhiDy.applyAsDouble(x, y), 1.0});
            }
        }

        // --- (b) correctness sanity: noiseless samples must reproduce the true field reasonably
        // well at the sample locations. NOTE the bound is 15 rad, not the originally-proposed
        // 3 rad: verified numerically (a standalone NumPy replica, swept over node counts 5-10)
        // that <3 rad is not achievable for ANY node count at this amplitude (65 rad)/domain-size
        // combination with only 65 samples covering 60% of the scene — that is a genuine
        // representational limit of a bilinear grid this coarse, not a fixable defect (the best
        // achievable noiseless RMS across N=5..10 was ~5.3 rad, at N=10, which is exactly the
        // node count fix round 2 shrinks AWAY from for stability under noise; see task-A-report.md
        // fix-round-2 section for the full sweep). 15 rad still firmly separates "the fit is
        // reproducing the field's shape" from "the fit is nonsense" (a broken/oscillating fit
        // measured 40-60+ rad RMS at these same sample locations during development).
        final InterferogramOp.GslcSurface2D surfClean = InterferogramOp.fitGslcSurface2D(cleanSamples, W, H);
        assertTrue("surface fit must succeed on the production-like sample count", surfClean != null);
        double meanErr = 0;
        final double[] errs = new double[cleanSamples.size()];
        for (int k = 0; k < cleanSamples.size(); k++) {
            final double[] s = cleanSamples.get(k);
            errs[k] = surfClean.valueAt(s[0], s[1]) - phi.applyAsDouble(s[0], s[1]);
            meanErr += errs[k];
        }
        meanErr /= errs.length;
        double sumSq = 0;
        for (final double e : errs) sumSq += (e - meanErr) * (e - meanErr);
        final double rms = Math.sqrt(sumSq / errs.length);
        System.out.printf("SURFACE2D-SPARSE-TEST: noiseless RMS at sample locations = %.2f rad " +
                "(node grid %dx%d)%n", rms, surfClean.NX, surfClean.NY);
        assertTrue("noiseless fit should reproduce the field's shape, not be nonsense: RMS=" + rms,
                rms < 15.0);

        // --- (a) the oscillation regression: same sample layout, WITH per-block measurement
        // noise (0.04 rad/px — comparable to real per-block fringe-gradient estimation error on
        // lower-coherence archive scenes). This is the noise the fixed-10x10/no-coverage-rule
        // code (pre fix-round-2) turns into node-to-node oscillation far exceeding the true
        // field's own smoothness. Seed/noise chosen (see task-A-report.md fix-round-2 section for
        // the sweep) so that the PRE-fix code demonstrably fails the ratio bound below (measured
        // ratio 3.95, confirmed by temporarily reverting to that code during development) while
        // the fix comfortably clears it (measured 2.08).
        final java.util.Random rnd = new java.util.Random(1);
        final java.util.List<double[]> noisySamples = new java.util.ArrayList<>();
        for (final double[] s : cleanSamples) {
            noisySamples.add(new double[]{s[0], s[1],
                    s[2] + 0.04 * rnd.nextGaussian(), s[3] + 0.04 * rnd.nextGaussian(), 1.0});
        }
        final InterferogramOp.GslcSurface2D surfNoisy = InterferogramOp.fitGslcSurface2D(noisySamples, W, H);
        assertTrue("surface fit must succeed on the noisy production-like sample count", surfNoisy != null);

        final double fitAdjMax = maxAdjacentNodeDiff(surfNoisy.node, surfNoisy.NX, surfNoisy.NY);
        final double[][] trueNodes = new double[surfNoisy.NY][surfNoisy.NX];
        for (int j = 0; j < surfNoisy.NY; j++) {
            for (int i = 0; i < surfNoisy.NX; i++) {
                trueNodes[j][i] = phi.applyAsDouble(i * surfNoisy.dx, j * surfNoisy.dy);
            }
        }
        final double trueAdjMax = maxAdjacentNodeDiff(trueNodes, surfNoisy.NX, surfNoisy.NY);
        final double ratio = fitAdjMax / trueAdjMax;
        System.out.printf("SURFACE2D-SPARSE-TEST: adjacent-node-diff fit=%.2f true=%.2f ratio=%.2f " +
                "(node grid %dx%d)%n", fitAdjMax, trueAdjMax, ratio, surfNoisy.NX, surfNoisy.NY);
        assertTrue("fit must not oscillate far beyond the true field's own smoothness: ratio=" + ratio,
                ratio <= 3.0);
    }

    /** Max |difference| between any two grid-adjacent (4-neighbour) node values. */
    private static double maxAdjacentNodeDiff(final double[][] node, final int nx, final int ny) {
        double m = 0;
        for (int j = 0; j < ny; j++) {
            for (int i = 0; i < nx - 1; i++) {
                m = Math.max(m, Math.abs(node[j][i + 1] - node[j][i]));
            }
        }
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny - 1; j++) {
                m = Math.max(m, Math.abs(node[j + 1][i] - node[j][i]));
            }
        }
        return m;
    }

    // ------------------------------------------------------------------
    // Fix round 3 (production evidence): the estimation itself was open-loop and undershot.
    // A single weighted-LS-plus-trim pass against block gradients contaminated by junk
    // systematically underestimates the true residual — measured on the ERS acceptance pair,
    // one pass captured only 89% of the true x-gradient and 57% of the true y-gradient, though
    // a degree-2 polynomial can express the (linear) missing ramp exactly. Fixed by making the
    // whole estimation closed-loop: repeated MAD-trim-and-refit rounds against a progressively-
    // narrowing (never re-growing) working set of blocks — see fitGslcRampIterative's javadoc
    // for why a naive "subtract the current fit and refit on the SAME rows" trick is a
    // mathematical no-op for ordinary least squares, and why narrowing the row set instead is
    // what actually lets later rounds discriminate junk from clean more sharply.
    // ------------------------------------------------------------------

    @Test
    public void residualRampIterationConvergesOnUndershoot() {
        // True scene-global ramp: FX=0.09, FY=0.03 rad/px (the real S1A/S1D pair's measured
        // ramp, same convention as rampIsRemovedWhenEnabled), i.e. c = [90, 30, 0, 0, 0] in the
        // GSLC_RAMP_NORM=1000 unit convention.
        final double[] cTrue = {90.0, 30.0, 0.0, 0.0, 0.0};
        final double extent = 20000.0;
        final double trueMag = Math.hypot(
                InterferogramOp.gslcRampFx(cTrue, extent / 2, extent / 2),
                InterferogramOp.gslcRampFy(cTrue, extent / 2, extent / 2));

        // 70% clean samples (exact analytic gradient + tiny noise) + 38 junk samples (~35% of
        // the total): large-magnitude (1.5x the true gradient), RANDOM-direction gradients —
        // representing decorrelated/high-scatter blocks that pass the dominant-fringe weight
        // gate anyway. Fixed seed for determinism.
        final java.util.Random rnd = new java.util.Random(1);
        final java.util.List<double[]> samples = new java.util.ArrayList<>();
        final int nClean = 70;
        for (int i = 0; i < nClean; i++) {
            final double x = 200 + rnd.nextDouble() * (extent - 400);
            final double y = 200 + rnd.nextDouble() * (extent - 400);
            samples.add(new double[]{x, y,
                    InterferogramOp.gslcRampFx(cTrue, x, y) + 0.001 * rnd.nextGaussian(),
                    InterferogramOp.gslcRampFy(cTrue, x, y) + 0.001 * rnd.nextGaussian(), 1.0});
        }
        final int nJunk = 38;
        for (int i = 0; i < nJunk; i++) {
            final double x = 200 + rnd.nextDouble() * (extent - 400);
            final double y = 200 + rnd.nextDouble() * (extent - 400);
            final double ang = rnd.nextDouble() * 2 * Math.PI;
            final double mag = trueMag * 1.5 * (0.7 + 0.6 * rnd.nextDouble());
            samples.add(new double[]{x, y, mag * Math.cos(ang), mag * Math.sin(ang), 1.0});
        }

        final double[] cSingle = InterferogramOp.fitGslcRampOneRobustStep(samples, 2);
        final double singleFrac = Math.hypot(
                InterferogramOp.gslcRampFx(cSingle, extent / 2, extent / 2),
                InterferogramOp.gslcRampFy(cSingle, extent / 2, extent / 2)) / trueMag;

        final double[] cIter = InterferogramOp.fitGslcRampIterative(samples, 2, 4, 0.0005);
        final double iterFrac = Math.hypot(
                InterferogramOp.gslcRampFx(cIter, extent / 2, extent / 2),
                InterferogramOp.gslcRampFy(cIter, extent / 2, extent / 2)) / trueMag;

        System.out.printf("RAMP-ITERATION-TEST: single-pass recovered %.1f%% of true gradient, " +
                "iterated recovered %.1f%%%n", 100 * singleFrac, 100 * iterFrac);

        assertTrue("this test requires the single pass to demonstrably undershoot (>30% miss) " +
                "— got " + (100 * singleFrac) + "% recovered", singleFrac < 0.70);
        assertTrue("iterated fit must recover the true ramp (>95%) — got " + (100 * iterFrac) + "%",
                iterFrac > 0.95);
    }

    @Test
    public void residualRampTrimRejectsJunkBlocks() {
        final double[] cTrue = {90.0, 30.0, 0.0, 0.0, 0.0};
        final double extent = 20000.0;
        final java.util.Random rnd = new java.util.Random(11);
        final java.util.List<double[]> samples = new java.util.ArrayList<>();
        final java.util.List<double[]> cleanRows = new java.util.ArrayList<>();
        final java.util.List<double[]> junkRows = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) {
            final double x = 200 + rnd.nextDouble() * (extent - 400);
            final double y = 200 + rnd.nextDouble() * (extent - 400);
            final double[] row = {x, y,
                    InterferogramOp.gslcRampFx(cTrue, x, y) + 0.0005 * rnd.nextGaussian(),
                    InterferogramOp.gslcRampFy(cTrue, x, y) + 0.0005 * rnd.nextGaussian(), 1.0};
            samples.add(row);
            cleanRows.add(row);
        }
        for (int i = 0; i < 10; i++) {
            final double x = 200 + rnd.nextDouble() * (extent - 400);
            final double y = 200 + rnd.nextDouble() * (extent - 400);
            // grossly wrong, isolated junk blocks — no ambiguity about which points are junk,
            // so a correct MAD trim must reject every one of them.
            final double[] row = {x, y, 5.0, -5.0, 1.0};
            samples.add(row);
            junkRows.add(row);
        }

        final double[] c0 = InterferogramOp.fitGslcRamp(samples, 2);
        final java.util.List<double[]> kept = InterferogramOp.trimGslcRampOutliers(samples, c0);

        int cleanKept = 0, junkKept = 0;
        for (final double[] row : kept) {
            if (junkRows.contains(row)) junkKept++;
            else if (cleanRows.contains(row)) cleanKept++;
        }
        System.out.printf("RAMP-TRIM-TEST: kept %d/%d clean, %d/%d junk%n",
                cleanKept, cleanRows.size(), junkKept, junkRows.size());

        assertTrue("MAD trim must reject all grossly-wrong junk blocks, kept " + junkKept + "/10",
                junkKept == 0);
        assertTrue("MAD trim must retain nearly all clean blocks, kept only " + cleanKept + "/60",
                cleanKept >= 55);
    }

    // ------------------------------------------------------------------
    // Fix round 4: the FULL production estimation path (poly + profile + surface, dense
    // decoupled surface sampling pass included) must have DECAYING profile/surface increments
    // and a bounded cumulative surface. The ERS acceptance run (ers_v7d.log) showed the
    // round-3 interleaved accumulate-increments loop repeating a ~30 rad profile and a
    // ~150-230 rad surface increment EVERY iteration (cumulative 650 rad vs a ~160-200 rad
    // true field) while the poly's own sample RMS collapsed — a runaway feedback loop, not a
    // missing-subtraction bug (every stage did subtract the full accumulated model; verified).
    // See estimateGslcResidualModel's javadoc for the mechanism.
    // ------------------------------------------------------------------

    // ERS-like scene for the full-path iteration test (matches the production pair's scale,
    // block-survival pattern and contamination level).
    private static final int ERS_W = 4900;
    private static final int ERS_H = 26000;
    private static final double ERS_R_MIN = 831_200.0;                  // m slant, near edge
    private static final double ERS_DRDX = 31_600.0 / 4388.0;           // m per px (ers_v7d span)
    // per-cell survival probability of the ERS pair's dense-pass blocks (from the production
    // per-cell sample-count log: counts out of ~43 candidate blocks per 7x7 cell)
    private static final double[][] ERS_KEEP = {
            {0, 1, 4, 7, 10, 6, 0},
            {6, 15, 15, 15, 13, 0, 0},
            {4, 12, 12, 12, 2, 0, 0},
            {5, 15, 15, 13, 0, 0, 0},
            {0, 15, 15, 6, 0, 0, 0},
            {0, 10, 12, 5, 0, 0, 0},
            {0, 8, 11, 7, 0, 0, 0}};

    private static double ersKeepProb(final double x, final double y) {
        final int i = Math.min(6, Math.max(0, (int) (x / ((ERS_W - 1) / 7.0))));
        final int j = Math.min(6, Math.max(0, (int) (y / ((ERS_H - 1) / 7.0))));
        return ERS_KEEP[j][i] / 43.0;
    }

    private static double ersTrueSurf(final double x, final double y) {
        // smooth 2-D "arc" field, ~150-200 rad excursion over the scene (the archive-data
        // annotation-error field the surface stage exists to remove)
        return 80.0 * Math.cos(1.7 * Math.PI * y / ERS_H + 0.4) * Math.sin(1.3 * Math.PI * x / ERS_W + 0.3)
                + 40.0 * Math.sin(0.9 * Math.PI * y / ERS_H);
    }

    private static double ersTrueProfileS(final double r) {
        final double t = (r - ERS_R_MIN) / 31_600.0;
        return 15.0 * (1.0 - Math.cos(2.0 * Math.PI * t));              // 30 rad excursion
    }

    /** Analytic total (fx, fy) of ramp + profile + surface at (x, y). */
    private static double[] ersTrueGrad(final double[] cTrue, final double x, final double y) {
        final double e = 1.0;
        final double sx = (ersTrueSurf(x + e, y) - ersTrueSurf(x - e, y)) / (2 * e);
        final double sy = (ersTrueSurf(x, y + e) - ersTrueSurf(x, y - e)) / (2 * e);
        final double r = ERS_R_MIN + (x - 256.0) * ERS_DRDX;
        final double dSdR = (ersTrueProfileS(r + 50.0) - ersTrueProfileS(r - 50.0)) / 100.0;
        return new double[]{
                InterferogramOp.gslcRampFx(cTrue, x, y) + dSdR * ERS_DRDX + sx,
                InterferogramOp.gslcRampFy(cTrue, x, y) + sy};
    }

    /** Block-gradient samples over a step grid with the ERS coverage/contamination pattern. */
    private static java.util.List<double[]> ersBlocks(final double[] cTrue, final int step,
                                                      final int block, final double noise,
                                                      final double junkFrac,
                                                      final java.util.Random rnd) {
        final java.util.List<double[]> out = new java.util.ArrayList<>();
        for (int y0 = 64; y0 + block < ERS_H - 64; y0 += step) {
            for (int x0 = 64; x0 + block < ERS_W - 64; x0 += step) {
                final double xc = x0 + block / 2.0, yc = y0 + block / 2.0;
                if (rnd.nextDouble() >= ersKeepProb(xc, yc)) continue;
                final double[] g = ersTrueGrad(cTrue, xc, yc);
                final double fx, fy, wgt;
                if (rnd.nextDouble() < junkFrac) {
                    // decorrelated block that still passed the dominant-fringe weight gate
                    fx = g[0] + 0.15 * rnd.nextGaussian();
                    fy = g[1] + 0.15 * rnd.nextGaussian();
                    wgt = 0.05 + 0.10 * rnd.nextDouble();
                } else {
                    fx = g[0] + noise * rnd.nextGaussian();
                    fy = g[1] + noise * rnd.nextGaussian();
                    wgt = 0.2 + 0.7 * rnd.nextDouble();
                }
                out.add(new double[]{xc, yc, fx, fy, wgt});
            }
        }
        return out;
    }

    @Test
    public void residualRampIterationIncrementsDecay() {
        // True model: quadratic ramp (~the ERS pair's measured (-0.09, +0.013) rad/px centre
        // gradient) + 30 rad slant-range profile + a smooth ~150-200 rad 2-D surface, sampled
        // with the production block-survival map and realistic contamination.
        final double[] cTrue = {-88.0, 15.0, -1.0, -1.5, -0.25};
        final java.util.Random rnd = new java.util.Random(7);
        final java.util.List<double[]> polySamples = ersBlocks(cTrue, 490, 384, 0.005, 0.20, rnd);
        final java.util.List<double[]> surfSamples = ersBlocks(cTrue, 245, 160, 0.02, 0.25, rnd);

        final InterferogramOp.GslcProfileGeom geom = (x, y) ->
                new double[]{ERS_R_MIN + (x - 256.0) * ERS_DRDX, ERS_DRDX, 0.0};

        final java.util.List<double[]> iterLog = new java.util.ArrayList<>();
        final InterferogramOp.GslcResidualFit fit = InterferogramOp.estimateGslcResidualModel(
                polySamples, surfSamples, geom, true, ERS_W, ERS_H, 2, 4, "iteration-decay-test",
                iterLog);

        // true surface excursion over the scene
        double sMin = Double.POSITIVE_INFINITY, sMax = Double.NEGATIVE_INFINITY;
        for (int j = 0; j <= 80; j++) {
            for (int i = 0; i <= 80; i++) {
                final double v = ersTrueSurf(i * (ERS_W - 1) / 80.0, j * (ERS_H - 1) / 80.0);
                sMin = Math.min(sMin, v);
                sMax = Math.max(sMax, v);
            }
        }
        final double trueExc = sMax - sMin;

        final java.util.List<Double> surfIncs = new java.util.ArrayList<>();
        for (final double[] row : iterLog) {
            if (row[2] > 0) surfIncs.add(row[2]);
        }
        assertTrue("surface stage must engage (no surface increment was ever fitted)",
                !surfIncs.isEmpty() && fit.surface != null);
        final double inc1 = surfIncs.get(0);
        final double inc2 = surfIncs.size() > 1 ? surfIncs.get(1) : 0.0;
        final double cumExc = fit.surface.maxNode() - fit.surface.minNode();

        System.out.printf("ITERATION-DECAY-TEST: %d poly samples, %d surf samples; surface " +
                        "increments %s; cumulative surface excursion %.1f rad (true %.1f rad)%n",
                polySamples.size(), surfSamples.size(), surfIncs, cumExc, trueExc);

        // The defect signature (ers_v7d.log): increments that do NOT decay (iter 2 at ~67% of
        // iter 1) and a cumulative surface at 3-4x the true field. A correct estimator's second
        // surface increment is a small correction (or zero), and the accumulated surface stays
        // within the true field's own scale.
        assertTrue(String.format("surface increment 2 (%.1f rad) must be < 25%% of increment 1 " +
                "(%.1f rad) — non-decaying increments mean the estimation re-adds the same " +
                "residual field every iteration", inc2, inc1), inc2 < 0.25 * inc1);
        assertTrue(String.format("cumulative surface excursion (%.1f rad) must stay within 1.5x " +
                "of the true field's (%.1f rad) — over-correction injects an arc field into the " +
                "interferogram", cumExc, trueExc), cumExc <= 1.5 * trueExc);
        assertTrue(String.format("cumulative surface excursion (%.1f rad) must reach at least " +
                "35%% of the true field's (%.1f rad) — the surface must genuinely engage",
                cumExc, trueExc), cumExc >= 0.35 * trueExc);
    }
}
