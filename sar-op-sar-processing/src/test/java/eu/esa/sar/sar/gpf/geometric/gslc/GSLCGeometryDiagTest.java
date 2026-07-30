package eu.esa.sar.sar.gpf.geometric.gslc;

import org.esa.snap.core.datamodel.GeoCoding;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.gpf.GPF;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * TEMPORARY diagnostic harness (not a regression test) for the GSLC zero-coherence
 * investigation. Runs {@code GSLC-Terrain-Correction} on a pre-built pair of TOPSAR-Split
 * fixtures with {@code -Dgslc.diagGeometry=true} so the per-pixel backward-geocoding solution
 * (DEM height, slant range, source range/azimuth index, burst) is emitted as extra bands and
 * the two independently geocoded legs can be compared pixel-by-pixel.
 * <p>
 * Skipped unless the fixture directory exists. Point it elsewhere with
 * {@code -Dgslc.diagDir=/path/to/dir} containing {@code m.dim} and {@code s.dim}.
 * <p>
 * <b>Why this is {@code @Ignore}d and NOT a {@code LongTestRunner} test.</b> Measured at <b>780–1058 s</b>
 * (13–17.6 min) — ~98% of all GSLC test time. Two independent reasons it must never run automatically:
 * <ol>
 *   <li>Several cases are fixture <em>producers</em>: they run the full geocode + CreateStack +
 *       Interferogram chain on real S1 SLCs and write {@code .dim} products ({@code mgcf.dim},
 *       {@code sgcf.dim}, {@code gslc_ifg_cf.dim}, {@code gslc_ifg_cfr.dim},
 *       {@code gslc_stack_auto.dim}). That is a pipeline harness, not a regression suite.</li>
 *   <li>Its gate is {@code System.getProperty("gslc.diagDir", "E:/Output/gslcdiag")} — a hardcoded
 *       default that <em>exists on the investigation machine</em>. So unlike the sibling harnesses,
 *       whose {@code TestData.inputSAR} gate leaves them skipping under surefire, this one's
 *       "fixture gating" affords no protection at all: all 7 cases execute. {@code LongTestRunner}
 *       would not help either, because builds here are run with
 *       {@code -Denable.long.tests=true}.</li>
 * </ol>
 * Run it deliberately by commenting out the {@code @Ignore}:
 * <pre>
 *   mvn test -pl sar-op-sar-processing -Dtest=GSLCGeometryDiagTest
 * </pre>
 * Note the fixture dependency: {@code GSLCCarrierResidualTest} (which stays in the normal suite)
 * consumes {@code gslc_ifg_cf.dim} and {@code mgcf.dim} produced here. Those files already exist on
 * disk; that test is file-gated and skips cleanly where they do not.
 */
@Ignore("Internal test harness - fixture producer, 13+ min; see class javadoc")
public class GSLCGeometryDiagTest {

    private static final File DIR = new File(System.getProperty("gslc.diagDir", "E:/Output/gslcdiag"));

    /**
     * Two products of DIFFERENT Sentinel-1 platforms, geocoded independently with default
     * parameters, must land on one shared lattice: identical pixel size, and origins separated by
     * a whole number of pixels.
     * <p>
     * Before the spacing was quantised this failed on the real S1A/S1D Venezuela pair — the
     * derived steps differed by 1.8e-10 deg, so each origin snapped to its own lattice and left a
     * 0.219 px (3.06 m) fractional offset that CreateStack's integer-offset path silently rounded
     * away, destroying all interferometric coherence.
     * <p>
     * Only {@code initialize()} runs here (the target grid is built there), so no pixels are
     * computed and the test costs seconds rather than the ~20 min a full geocode takes.
     */
    @Test
    public void testDifferentPlatformsLandOnOneLattice() throws Exception {
        final File m = new File(DIR, "m.dim");   // Sentinel-1A
        final File s = new File(DIR, "s.dim");   // Sentinel-1D
        assumeTrue("fixtures not present: " + m + " / " + s, m.exists() && s.exists());

        final Product srcA = ProductIO.readProduct(m);
        final Product srcB = ProductIO.readProduct(s);
        try {
            final Product ga = GPF.createProduct("GSLC-Terrain-Correction", defaultParams(), srcA);
            final Product gb = GPF.createProduct("GSLC-Terrain-Correction", defaultParams(), srcB);

            final GeoCoding gcA = ga.getSceneGeoCoding();
            final GeoCoding gcB = gb.getSceneGeoCoding();
            assertNotNull("reference geocoding missing", gcA);
            assertNotNull("secondary geocoding missing", gcB);

            final double[] a = originAndStep(gcA);
            final double[] b = originAndStep(gcB);

            assertEquals("pixel size (lon) must be bit-identical across platforms",
                    0, Double.compare(a[2], b[2]));
            assertEquals("pixel size (lat) must be bit-identical across platforms",
                    0, Double.compare(a[3], b[3]));

            final double offX = (b[0] - a[0]) / a[2];
            final double offY = (a[1] - b[1]) / a[3];
            final double fracX = Math.abs(offX - Math.rint(offX));
            final double fracY = Math.abs(offY - Math.rint(offY));
            System.out.printf("GSLC-LATTICE step=%.17e  offset=(%.6f, %.6f) px  frac=(%.2e, %.2e)%n",
                    a[2], offX, offY, fracX, fracY);

            assertTrue("origin offset in x must be a whole number of pixels, was " + offX,
                    fracX < 1e-6);
            assertTrue("origin offset in y must be a whole number of pixels, was " + offY,
                    fracY < 1e-6);
        } finally {
            srcA.dispose();
            srcB.dispose();
        }
    }

    /**
     * Rectangular cells must survive the whole grid chain: explicit X != Y spacing on the GSLC,
     * then CreateStack's auto-coregister grid-lock must reproduce the SAME rectangular lattice on
     * the slave (a collapsed single spacing would trip the lattice guard). Init-only — no pixels
     * computed, runs in seconds.
     */
    @Test
    public void testRectangularGridLocksThroughStack() throws Exception {
        final File m = new File(DIR, "m.dim");
        final File s = new File(DIR, "s.dim");
        assumeTrue("fixtures not present", m.exists() && s.exists());

        final Product srcA = ProductIO.readProduct(m);
        final Product srcB = ProductIO.readProduct(s);
        try {
            final Map<String, Object> p = new HashMap<>();
            p.put("demName", "Copernicus 30m Global DEM");
            p.put("outputFlattened", false);
            p.put("pixelSpacingInMeter", 14.0);
            p.put("pixelSpacingInMeterY", 28.0);
            final Product rectGslc = GPF.createProduct("GSLC-Terrain-Correction", p, srcA);

            final double[] g = originAndStep(rectGslc.getSceneGeoCoding());
            final double ratio = g[3] / g[2];
            System.out.printf("RECT-GRID master steps: lon=%.10e lat=%.10e ratio=%.6f%n", g[2], g[3], ratio);
            assertEquals("lat step must be exactly 2x the lon step (28 m vs 14 m)",
                    2.0, ratio, 1e-9);

            // Auto-coregister path: the slave must be locked onto the SAME rectangular lattice.
            // The lattice guard inside CreateStack throws for complex stacks if it is not.
            final Map<String, Object> cs = new HashMap<>();
            cs.put("extent", "Master");
            cs.put("resamplingType", "NONE");
            cs.put("autoCoregisterGSLC", true);
            cs.put("skipBiasEstimation", true);
            final Product stack = GPF.createProduct("CreateStack", cs, new Product[]{rectGslc, srcB});
            assertNotNull(stack.getSceneGeoCoding());
            final double[] st = originAndStep(stack.getSceneGeoCoding());
            assertEquals("stack keeps the rectangular lattice", 2.0, st[3] / st[2], 1e-9);
            // relative tolerance: the stack's geocoding is a copy whose derived step can differ
            // in the last ulp; the actual alignment invariant (integral offsets) is enforced by
            // the lattice guard inside CreateStack, which would have thrown above.
            assertEquals("stack lon step = master lon step", 1.0, st[2] / g[2], 1e-12);
            System.out.println("RECT-GRID stack accepted on the rectangular lattice (lattice guard passed).");
            stack.dispose();
            rectGslc.dispose();
        } finally {
            srcA.dispose();
            srcB.dispose();
        }
    }

    /** @return {originLon, originLat, stepLon, stepLat} taken from pixel centres. */
    private static double[] originAndStep(final GeoCoding gc) {
        final GeoPos g00 = gc.getGeoPos(new PixelPos(0.5, 0.5), null);
        final GeoPos g10 = gc.getGeoPos(new PixelPos(1.5, 0.5), null);
        final GeoPos g01 = gc.getGeoPos(new PixelPos(0.5, 1.5), null);
        return new double[]{g00.lon, g00.lat, g10.lon - g00.lon, g00.lat - g01.lat};
    }

    private static Map<String, Object> defaultParams() {
        final Map<String, Object> p = new HashMap<>();
        p.put("demName", "Copernicus 30m Global DEM");
        p.put("outputFlattened", false);
        return p;   // pixel spacing intentionally left to the operator to derive
    }

    /**
     * End-to-end validation of the carrier-free default: geocode both fixture legs with default
     * parameters (azimuth carrier NOT restored) plus diag bands, then CreateStack + Interferogram.
     * The azimuth-carrier residual measured against the classical control (a per-burst quadratic
     * of ~141/161 rad excursion with the carrier restored) must be gone from
     * {@code gslc_ifg_cf.dim}.
     */
    @Test
    public void geocodeCarrierFreeLegsAndBuildIfg() throws Exception {
        final File m = new File(DIR, "m.dim");
        final File s = new File(DIR, "s.dim");
        assumeTrue("fixtures not present", m.exists() && s.exists());

        System.setProperty("gslc.diagGeometry", "true");
        geocode(m, new File(DIR, "mgcf.dim"));
        geocode(s, new File(DIR, "sgcf.dim"));
        System.clearProperty("gslc.diagGeometry");

        final Product pa = ProductIO.readProduct(new File(DIR, "mgcf.dim"));
        final Product pb = ProductIO.readProduct(new File(DIR, "sgcf.dim"));
        try {
            final Map<String, Object> cs = new HashMap<>();
            cs.put("extent", "Master");
            cs.put("resamplingType", "NONE");
            cs.put("autoCoregisterGSLC", false);
            cs.put("skipBiasEstimation", true);
            final Product stack = GPF.createProduct("CreateStack", cs, new Product[]{pa, pb});

            final Map<String, Object> ig = new HashMap<>();
            ig.put("subtractFlatEarthPhase", true);
            ig.put("subtractTopographicPhase", true);
            ig.put("demName", "Copernicus 30m Global DEM");
            ig.put("includeCoherence", true);
            ig.put("cohWinAz", 10);
            ig.put("cohWinRg", 10);
            final Product ifg = GPF.createProduct("Interferogram", ig, stack);

            final File out = new File(DIR, "gslc_ifg_cf.dim");
            ProductIO.writeProduct(ifg, out, "BEAM-DIMAP", false);
            System.out.println("GSLC-IFG-CF wrote " + out + " bands=" + String.join(",", ifg.getBandNames()));
            ifg.dispose();
            stack.dispose();
        } finally {
            pa.dispose();
            pb.dispose();
        }
    }

    /**
     * Carrier-free legs + {@code subtractResidualRamp=true}: the interferogram should come out
     * with the annotation-mismatch ramp removed by the operator itself — matching classical with
     * no external deramp step. Requires mgcf/sgcf from {@link #geocodeCarrierFreeLegsAndBuildIfg}.
     */
    @Test
    public void buildGslcInterferogramRampRemoved() throws Exception {
        final File a = new File(DIR, "mgcf.dim");
        final File b = new File(DIR, "sgcf.dim");
        assumeTrue("carrier-free fixtures not present", a.exists() && b.exists());

        final Product pa = ProductIO.readProduct(a);
        final Product pb = ProductIO.readProduct(b);
        try {
            final Map<String, Object> cs = new HashMap<>();
            cs.put("extent", "Master");
            cs.put("resamplingType", "NONE");
            cs.put("autoCoregisterGSLC", false);
            cs.put("skipBiasEstimation", true);
            final Product stack = GPF.createProduct("CreateStack", cs, new Product[]{pa, pb});

            final Map<String, Object> ig = new HashMap<>();
            ig.put("subtractFlatEarthPhase", true);
            ig.put("subtractTopographicPhase", true);
            ig.put("demName", "Copernicus 30m Global DEM");
            ig.put("includeCoherence", true);
            ig.put("cohWinAz", 10);
            ig.put("cohWinRg", 10);
            ig.put("subtractResidualRamp", true);
            final Product ifg = GPF.createProduct("Interferogram", ig, stack);

            final File out = new File(DIR, "gslc_ifg_cfr.dim");
            ProductIO.writeProduct(ifg, out, "BEAM-DIMAP", false);
            System.out.println("GSLC-IFG-CFR wrote " + out);
            ifg.dispose();
            stack.dispose();
        } finally {
            pa.dispose();
            pb.dispose();
        }
    }

    /**
     * The AUTO-COREGISTER path — the pipeline the user actually runs: CreateStack(master GSLC,
     * slave SLC) with autoCoregisterGSLC=true and bias estimation enabled. CreateStack geocodes
     * the slave internally (grid-locked to the master), estimates the bias (Back-Geocoding + ESD
     * for TOPS), possibly rebuilds the slave GSLC with the bias baked in, and swaps the bands.
     * <p>
     * This exercises the placeholder/swap/pass-through interactions and the bias estimator on a
     * genuinely low-coherence (7-day tropical) pair — neither of which the manual both-GSLC
     * fixture path touches. Output: {@code gslc_ifg_auto.dim} for comparison against
     * {@code classical_ifg} and the manual-path {@code gslc_ifg}.
     */
    @Test
    public void buildGslcInterferogramAutoPathForFixture() throws Exception {
        final File a = new File(DIR, "mg.dim");   // master GSLC (S1A)
        final File b = new File(DIR, "s.dim");    // slave SLC (S1D) — auto-geocoded by CreateStack
        assumeTrue("fixtures not present", a.exists() && b.exists());

        final Product pa = ProductIO.readProduct(a);
        final Product pb = ProductIO.readProduct(b);
        try {
            final Map<String, Object> cs = new HashMap<>();
            cs.put("extent", "Master");
            cs.put("resamplingType", "NONE");
            cs.put("autoCoregisterGSLC", true);
            cs.put("skipBiasEstimation", false);
            cs.put("initialOffsetMethod", "Orbit");
            final Product stack = GPF.createProduct("CreateStack", cs, new Product[]{pa, pb});

            // Materialize the stack before the interferogram — chaining Interferogram directly
            // onto an in-memory CreateStack→GSLC graph recomputes GSLC tiles pathologically
            // (observed: 6 CPU-hours, 0 rows written, on a 2-burst fixture). Users hit the same
            // wall, which is why the documented workflow writes the stack to disk.
            final File stackOut = new File(DIR, "gslc_stack_auto.dim");
            ProductIO.writeProduct(stack, stackOut, "BEAM-DIMAP", false);
            stack.dispose();
            System.out.println("GSLC-IFG-AUTO stack written: " + stackOut);
            final Product stackFromDisk = ProductIO.readProduct(stackOut);

            final Map<String, Object> ig = new HashMap<>();
            ig.put("subtractFlatEarthPhase", true);
            ig.put("subtractTopographicPhase", true);
            ig.put("demName", "Copernicus 30m Global DEM");
            ig.put("includeCoherence", true);
            ig.put("cohWinAz", 10);
            ig.put("cohWinRg", 10);
            final Product ifg = GPF.createProduct("Interferogram", ig, stackFromDisk);

            final File out = new File(DIR, "gslc_ifg_auto.dim");
            ProductIO.writeProduct(ifg, out, "BEAM-DIMAP", false);
            System.out.println("GSLC-IFG-AUTO wrote " + out + " bands=" + String.join(",", ifg.getBandNames()));
            ifg.dispose();
            stackFromDisk.dispose();
        } finally {
            pa.dispose();
            pb.dispose();
        }
    }

    /**
     * Build the GSLC interferogram for the fixture pair so the reference phase that
     * {@code InterferogramOp} actually removes can be compared against the true flat-earth +
     * topographic surface (measured independently against the classical interferogram).
     */
    @Test
    public void buildGslcInterferogramForFixture() throws Exception {
        final File a = new File(DIR, "mg.dim");
        final File b = new File(DIR, "sg_lock.dim");
        assumeTrue("GSLC fixtures not present", a.exists() && b.exists());

        final Product pa = ProductIO.readProduct(a);
        final Product pb = ProductIO.readProduct(b);
        try {
            final Map<String, Object> cs = new HashMap<>();
            cs.put("extent", "Master");
            cs.put("resamplingType", "NONE");
            cs.put("autoCoregisterGSLC", false);   // both legs are already GSLC on one lattice
            cs.put("skipBiasEstimation", true);
            final Product stack = GPF.createProduct("CreateStack", cs, new Product[]{pa, pb});

            final Map<String, Object> ig = new HashMap<>();
            ig.put("subtractFlatEarthPhase", true);
            ig.put("subtractTopographicPhase", true);
            ig.put("demName", "Copernicus 30m Global DEM");
            ig.put("includeCoherence", true);
            ig.put("cohWinAz", 10);
            ig.put("cohWinRg", 10);
            final Product ifg = GPF.createProduct("Interferogram", ig, stack);

            final File out = new File(DIR, "gslc_ifg.dim");
            ProductIO.writeProduct(ifg, out, "BEAM-DIMAP", false);
            System.out.println("GSLC-IFG wrote " + out + " bands=" + String.join(",", ifg.getBandNames()));
            ifg.dispose();
            stack.dispose();
        } finally {
            pa.dispose();
            pb.dispose();
        }
    }

    @Test
    public void dumpGeometryForBothLegs() throws Exception {
        final File m = new File(DIR, "m.dim");
        final File s = new File(DIR, "s.dim");
        assumeTrue("fixtures not present: " + m + " / " + s, m.exists() && s.exists());

        System.setProperty("gslc.diagGeometry", "true");

        geocode(m, new File(DIR, "mgd.dim"));
        geocode(s, new File(DIR, "sgd.dim"));
    }

    private static void geocode(final File in, final File out) throws Exception {
        final Product src = ProductIO.readProduct(in);
        try {
            final Map<String, Object> p = new HashMap<>();
            p.put("demName", "Copernicus 30m Global DEM");
            p.put("demResamplingMethod", "BILINEAR_INTERPOLATION");
            p.put("imgResamplingMethod", "BISINC_5_POINT_INTERPOLATION");
            p.put("outputFlattened", false);
            p.put("nodataValueAtSea", true);
            p.put("saveDEM", true);
            p.put("saveLatLon", true);
            p.put("saveSimulatedUnwrappedPhase", true);

            final Product tgt = GPF.createProduct("GSLC-Terrain-Correction", p, src);
            final long t0 = System.currentTimeMillis();
            ProductIO.writeProduct(tgt, out, "BEAM-DIMAP", false);
            System.out.println("GSLC-DIAG wrote " + out.getName() + " (" + tgt.getSceneRasterWidth() + "x"
                    + tgt.getSceneRasterHeight() + ") in " + (System.currentTimeMillis() - t0) / 1000 + " s");
            tgt.dispose();
        } finally {
            src.dispose();
        }
    }
}
