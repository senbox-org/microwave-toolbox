package eu.esa.sar.sar.gpf.geometric.gslc;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.test.ProcessorTest;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.GPF;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * GSLC TOPS coregistration on the fast Etna fixtures (orbit-applied IW1 burst-1-2 VV splits, built
 * once by {@code snap_tmp/build_etna_fixture.ps1}). Reading the small BEAM-DIMAP fixtures skips the
 * ~8 GB SLC-zip reads, the orbit download, and TOPSAR-Split, so these file-gated tests iterate in
 * minutes instead of ~hour. Two checks:
 *  - {@link #testAzimuthOffsetDegradesCoherenceWhenMisaligned} proves azimuthOffsetPixels is applied
 *    in GSLC's TOPS path (a deliberate misalignment must reduce coherence).
 *  - {@link #testTopsBiasEstimatorRunsWithoutRegression} proves CreateStack's TOPS bias estimator
 *    (Back-Geocoding + ESD) runs and does not regress coherence.
 */
@Ignore("Internal test harness")
public class GSLCTopsBiasIntegrationTest extends ProcessorTest {

    private static final File FIX_DIR = new File("E:/TestData/s1tbx/SAR/S1/SLC/Etna-DLR/fixtures");
    private static final File FIXTURE_MASTER = new File(FIX_DIR, "etna_master.dim");
    private static final File FIXTURE_SLAVE = new File(FIX_DIR, "etna_slave.dim");

    private static boolean fixturesPresent() {
        return FIXTURE_MASTER.isFile() && FIXTURE_SLAVE.isFile();
    }

    private static Product gslc(File fixture, Double azimuthOffsetPixels) {
        final Map<String, Object> g = new HashMap<>();
        g.put("demName", "Copernicus 30m Global DEM");
        g.put("imgResamplingMethod", "BISINC_5_POINT_INTERPOLATION"); // match classical Back-Geocoding (apples-to-apples)
        g.put("nodataValueAtSea", false);
        // outputFlattened=true removes the geometric (flat+topo) phase at the leg level so the GSLC
        // interferogram coherence is topo-compensated (no fringe cancellation in the window) — sound
        // coherence over rough terrain like Etna.
        g.put("outputFlattened", true);
        // SET/tropo isolation control: disabled here to test whether the autoCoregisterGSLC=false
        // (standard-grid) path alone explains the drop vs the grid-locked auto-coregister path.
        // g.put("applySolidEarthTide", true);
        // g.put("applyTroposphericCorrection", true);
        if (azimuthOffsetPixels != null) g.put("azimuthOffsetPixels", azimuthOffsetPixels);
        try {
            return GPF.createProduct("GSLC-Terrain-Correction", g, TestUtils.readSourceProduct(fixture));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Stack a (reused) master GSLC with a slave geocoded at a chosen azimuth offset; return the
     *  masked mean coherence. autoCoregisterGSLC=false: just overlay by the standard grid. */
    private static double gslcPairCoherence(Product mGslc, double slaveAzOffset) throws Exception {
        final Product sGslc = gslc(FIXTURE_SLAVE, slaveAzOffset);

        final Map<String, Product> ss = new LinkedHashMap<>();
        ss.put("sourceProduct.1", mGslc);
        ss.put("sourceProduct.2", sGslc);
        final Map<String, Object> sp = new HashMap<>();
        sp.put("extent", "Master");
        sp.put("autoCoregisterGSLC", false);
        final Product stack = GPF.createProduct("CreateStack", sp, ss);

        return interferogramCoherence(stack);
    }

    /** GSLC(master) + raw slave fixture (TOPS split) -> CreateStack auto-coregister (which runs the
     *  TOPS bias estimator unless skipBias) -> masked mean coherence. */
    private static double gslcAutoCoregCoherence(boolean skipBias) throws Exception {
        final Product mGslc = gslc(FIXTURE_MASTER, null);
        final Product sSplit = TestUtils.readSourceProduct(FIXTURE_SLAVE);

        final Map<String, Product> ss = new LinkedHashMap<>();
        ss.put("sourceProduct.1", mGslc);
        ss.put("sourceProduct.2", sSplit);
        final Map<String, Object> sp = new HashMap<>();
        sp.put("extent", "Master");
        sp.put("autoCoregisterGSLC", true);
        sp.put("skipBiasEstimation", skipBias);
        final Product stack = GPF.createProduct("CreateStack", sp, ss);

        return interferogramCoherence(stack);
    }

    private static double interferogramCoherence(Product stack) throws Exception {
        final Map<String, Object> ip = new HashMap<>();
        ip.put("includeCoherence", true);
        ip.put("cohWinAz", "10");
        ip.put("cohWinRg", "10");
        final Product ifg = GPF.createProduct("Interferogram", ip, stack);
        return meanCoherenceValid(ifg);
    }

    @Test
    public void testAzimuthOffsetDegradesCoherenceWhenMisaligned() throws Exception {
        assumeTrue("Etna fixtures not found — run snap_tmp/build_etna_fixture.ps1", fixturesPresent());
        GPF.getDefaultInstance().getOperatorSpiRegistry().loadOperatorSpis();

        final Product mGslc = gslc(FIXTURE_MASTER, null); // geocode master once, reuse for both arms
        final double cAligned = gslcPairCoherence(mGslc, 0.0);
        final double cShifted = gslcPairCoherence(mGslc, 3.0); // inject +3 px azimuth
        System.out.printf("azimuth-offset injection: aligned(0px)=%.4f  shifted(3px)=%.4f%n",
                cAligned, cShifted);

        // Direction is the proof: a 3-px azimuth misalignment must reduce coherence, confirming
        // azimuthOffsetPixels reaches the TOPS azimuth mapping (a silent no-op before the fix).
        assertTrue("a 3-px azimuth misalignment must reduce coherence (aligned=" + cAligned +
                ", shifted=" + cShifted + ")", cAligned > cShifted + 0.003);
    }

    @Test
    public void testTopsBiasEstimatorRunsWithoutRegression() throws Exception {
        assumeTrue("Etna fixtures not found — run snap_tmp/build_etna_fixture.ps1", fixturesPresent());
        GPF.getDefaultInstance().getOperatorSpiRegistry().loadOperatorSpis();

        final double cohNoBias = gslcAutoCoregCoherence(true);
        final double cohBias = gslcAutoCoregCoherence(false);
        System.out.printf("GSLC TOPS coherence: no-bias=%.4f  ESD-bias=%.4f%n", cohNoBias, cohBias);

        // On a clean pair the ESD residual is sub-0.1 px so coherence is ~unchanged; the estimator
        // must run (no TOPS refusal) and must not regress coherence.
        assertTrue("ESD bias must not regress coherence (bias=" + cohBias +
                " vs no-bias=" + cohNoBias + ")", cohBias >= cohNoBias - 0.01);
    }

    /** Interferogram coherence at a physical window size (metres). 300 m keeps BOTH geometries well
     *  above the coherence-estimator noise floor: GSLC (~14 m map px) gets ~440 looks (floor ~0.04),
     *  classical (~2.3x14 m radar px) gets ~thousands — so the comparison is not floor-limited
     *  (at 100 m GSLC sat at its ~50-look floor of ~0.125, which the earlier 0.135 reflected). */
    private static double cohMeters(Product stack, String meters) throws Exception {
        final Map<String, Object> ip = new HashMap<>();
        ip.put("includeCoherence", true);
        ip.put("cohWinSizeMeters", meters);
        ip.put("cohWinAz", "10");
        ip.put("cohWinRg", "10");
        final Product ifg = GPF.createProduct("Interferogram", ip, stack);
        return meanCoherenceValid(ifg);
    }

    /** Topo-compensated classical coherence: subtractFlatEarthPhase + subtractTopographicPhase
     *  derotate the secondary leg by the deterministic phase BEFORE the coherence sum (verified in
     *  InterferogramOp.computeTileStackForNormalProduct), so the window sums coherently over rough
     *  terrain — matched to GSLC's outputFlattened=true leg flattening. */
    private static double cohClassicalTopo(Product stack, String meters) throws Exception {
        final Map<String, Object> ip = new HashMap<>();
        ip.put("includeCoherence", true);
        ip.put("subtractFlatEarthPhase", true);
        ip.put("subtractTopographicPhase", true);
        ip.put("demName", "Copernicus 30m Global DEM");
        ip.put("cohWinSizeMeters", meters);
        ip.put("cohWinAz", "10");
        ip.put("cohWinRg", "10");
        final Product ifg = GPF.createProduct("Interferogram", ip, stack);
        return meanCoherenceValid(ifg);
    }

    /**
     * The real over-Etna GSLC-vs-classical coherence comparison, fast: both pipelines run on the
     * range-cropped Etna fixtures. Classical coherence is taken from Back-Geocoding -> Interferogram
     * (coherence is estimated on the coregistered burst stack, BEFORE deburst — so the deburst step
     * that rejects range-cropped splits is not needed). Same 100 m physical window on both.
     */
    @Test
    public void testGslcVsClassicalCoherenceOverEtna() throws Exception {
        assumeTrue("Etna fixtures not found — run snap_tmp/build_etna_fixture.ps1", fixturesPresent());
        GPF.getDefaultInstance().getOperatorSpiRegistry().loadOperatorSpis();

        // Classical: DEM-assisted Back-Geocoding coregistration, coherence pre-deburst.
        final Map<String, Object> bg = new HashMap<>();
        bg.put("demName", "Copernicus 30m Global DEM");
        bg.put("resamplingType", "BISINC_5_POINT_INTERPOLATION");
        final Product bgStack = GPF.createProduct("Back-Geocoding", bg,
                new Product[]{TestUtils.readSourceProduct(FIXTURE_MASTER), TestUtils.readSourceProduct(FIXTURE_SLAVE)});
        final double classical100 = cohClassicalTopo(bgStack, "100");
        final double classical300 = cohClassicalTopo(bgStack, "300");

        // GSLC: geocode master, CreateStack grid-locks + auto-coregisters the raw slave onto the
        // master grid (this grid-locked path gave the best coherence; standard-grid was worse).
        final Product mGslc = gslc(FIXTURE_MASTER, null);
        final Map<String, Product> ss = new LinkedHashMap<>();
        ss.put("sourceProduct.1", mGslc);
        ss.put("sourceProduct.2", TestUtils.readSourceProduct(FIXTURE_SLAVE));
        final Map<String, Object> sp = new HashMap<>();
        sp.put("extent", "Master");
        sp.put("autoCoregisterGSLC", true);
        sp.put("skipBiasEstimation", true); // ESD residual ~0.03 px (negligible); skip for speed
        final Product gslcStack = GPF.createProduct("CreateStack", sp, ss);
        final double gslc100 = cohMeters(gslcStack, "100");
        final double gslc300 = cohMeters(gslcStack, "300");

        System.out.printf("OVER-ETNA  100m: classical=%.4f GSLC=%.4f (ratio %.3f)  |  300m: classical=%.4f GSLC=%.4f (ratio %.3f)%n",
                classical100, gslc100, gslc100 / Math.max(classical100, 1e-9),
                classical300, gslc300, gslc300 / Math.max(classical300, 1e-9));
        assertTrue("both pipelines should yield finite coherence", classical100 > 0 && gslc100 > 0);
    }

    private static double meanCoherenceValid(Product p) throws Exception {
        Band coh = null, real = null;
        for (final Band b : p.getBands()) {
            if (Unit.COHERENCE.equals(b.getUnit()) && coh == null) coh = b;
            if (Unit.REAL.equals(b.getUnit()) && real == null) real = b;
        }
        if (coh == null) return Double.NaN;
        final int w = coh.getRasterWidth(), h = coh.getRasterHeight();
        final float[] rc = new float[w], rr = new float[w];
        double sum = 0; long n = 0;
        for (int y = 0; y < h; y++) {
            coh.readPixels(0, y, w, 1, rc, ProgressMonitor.NULL);
            if (real != null) real.readPixels(0, y, w, 1, rr, ProgressMonitor.NULL);
            for (int x = 0; x < w; x++) {
                if (Float.isNaN(rc[x])) continue;
                if (real != null && (Float.isNaN(rr[x]) || rr[x] == 0f)) continue;
                sum += rc[x]; n++;
            }
        }
        return n == 0 ? Double.NaN : sum / n;
    }
}
