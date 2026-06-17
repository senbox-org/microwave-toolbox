package eu.esa.sar.sar.gpf.geometric;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.test.ProcessorTest;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.GPF;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Layer 4: side-by-side GSLC vs traditional (radar-geometry CSLC) InSAR on an S1 IW TOPS pair.
 * File-gated on a Napoli/Campi Flegrei pair — SKIPPED until that data is provided. Emits mean
 * coherence + a coherence histogram for each pipeline and writes both interferograms to E:/out
 * for fringe inspection. The coherence-ratio assertion is a low measure-not-gate floor, mirroring
 * the ENVISAT ASAR comparison test — the goal is to quantify the gap, not block on it.
 *
 * This harness is the reusable core for the follow-on Approach-3 standalone validation tool.
 *
 * Set MASTER/SLAVE to the Napoli IW SLC pair (same track/subswath overlap) to enable.
 */
public class GSLCVsCSLCTopsComparisonTest extends ProcessorTest {

    private static final File MASTER = new File("E:/TestData/s1tbx/SAR/S1/Napoli/master_IW_SLC.zip");
    private static final File SLAVE  = new File("E:/TestData/s1tbx/SAR/S1/Napoli/slave_IW_SLC.zip");
    private static final String SUBSWATH = "IW1";
    private static final String POL = "VV";
    private static final int FIRST_BURST = 1, LAST_BURST = 3;
    private static final double COH_WIN_M = 100.0;     // same physical window on both pipelines
    private static final double MIN_RATIO = 0.10;       // measure-not-gate floor

    @Test
    public void testGslcVsTraditionalTops() throws Exception {
        assumeTrue("Napoli pair not found", MASTER.isFile() && SLAVE.isFile());
        GPF.getDefaultInstance().getOperatorSpiRegistry().loadOperatorSpis();

        try (final Product m = TestUtils.readSourceProduct(MASTER);
             final Product s = TestUtils.readSourceProduct(SLAVE)) {

            final Product tradIfg = traditionalPipeline(m, s);
            final double tradCoh = meanCoherence(tradIfg);
            printHistogram("traditional", tradIfg);
            writeOut(tradIfg, "trad_tops_ifg");

            final Product gslcIfg = gslcPipeline(m, s);
            final double gslcCoh = meanCoherence(gslcIfg);
            printHistogram("gslc", gslcIfg);
            writeOut(gslcIfg, "gslc_tops_ifg");

            System.out.printf("TOPS comparison: tradCoh=%.4f  gslcCoh=%.4f  ratio=%.3f%n",
                    tradCoh, gslcCoh, gslcCoh / Math.max(tradCoh, 1e-9));
            assertTrue("GSLC coherence ratio below floor",
                    gslcCoh >= MIN_RATIO * tradCoh);
        }
    }

    private static Product applyOrbitSplit(Product src) {
        final Product orb = GPF.createProduct("Apply-Orbit-File", new HashMap<>(), src);
        final Map<String, Object> p = new HashMap<>();
        p.put("subswath", SUBSWATH);
        p.put("selectedPolarisations", POL);
        p.put("firstBurstIndex", FIRST_BURST);
        p.put("lastBurstIndex", LAST_BURST);
        return GPF.createProduct("TOPSAR-Split", p, orb);
    }

    private Product traditionalPipeline(Product m, Product s) {
        final Product mSplit = applyOrbitSplit(m);
        final Product sSplit = applyOrbitSplit(s);
        final Map<String, Product> bgSrc = new LinkedHashMap<>();
        bgSrc.put("sourceProduct", mSplit);
        bgSrc.put("sourceProduct.1", sSplit);
        final Product bg = GPF.createProduct("Back-Geocoding", new HashMap<>(), bgSrc);
        final Product esd = GPF.createProduct("Enhanced-Spectral-Diversity", new HashMap<>(), bg);
        final Map<String, Object> ifgP = new HashMap<>();
        ifgP.put("includeCoherence", true);
        ifgP.put("cohWinSizeMeters", COH_WIN_M);
        final Product ifg = GPF.createProduct("Interferogram", ifgP, esd);
        return GPF.createProduct("TOPSAR-Deburst", new HashMap<>(), ifg);
    }

    private Product gslcPipeline(Product m, Product s) {
        final Product mSplit = applyOrbitSplit(m);
        final Product sSplit = applyOrbitSplit(s);
        final Map<String, Object> gp = new HashMap<>();
        gp.put("demName", "SRTM 3Sec");
        gp.put("imgResamplingMethod", "BICUBIC_INTERPOLATION");
        gp.put("nodataValueAtSea", false);
        final Product mGslc = GPF.createProduct("GSLC-Terrain-Correction", gp, mSplit);

        final Map<String, Product> stackSrc = new LinkedHashMap<>();
        stackSrc.put("sourceProduct.1", mGslc);
        stackSrc.put("sourceProduct.2", sSplit);   // CreateStack auto-geocodes the slave to mGslc's grid
        final Map<String, Object> stackP = new HashMap<>();
        stackP.put("extent", "Master");
        final Product stack = GPF.createProduct("CreateStack", stackP, stackSrc);

        final Map<String, Object> ifgP = new HashMap<>();
        ifgP.put("includeCoherence", true);
        ifgP.put("cohWinSizeMeters", COH_WIN_M);
        return GPF.createProduct("Interferogram", ifgP, stack);
    }

    private static Band bandByUnit(Product p, String unit) {
        for (final Band b : p.getBands()) if (unit.equals(b.getUnit())) return b;
        return null;
    }

    /**
     * Mean coherence over the imaged area only — masked to pixels where the interferogram real
     * band is nonzero. A geocoded product (GSLC) has a wide nodata border where coherence is
     * written 0.0 (not NaN); averaging that in would unfairly penalize GSLC against the
     * radar-geometry traditional pipeline. Masking by valid interferogram pixels makes the two
     * coherence numbers comparable over the same imaged extent.
     */
    private static double meanCoherence(Product p) throws Exception {
        final Band c = bandByUnit(p, Unit.COHERENCE);
        final Band r = bandByUnit(p, Unit.REAL);
        if (c == null) return Double.NaN;
        final int w = c.getRasterWidth(), h = c.getRasterHeight();
        final float[] rc = new float[w], rr = new float[w];
        double sum = 0; long n = 0;
        for (int y = 0; y < h; y++) {
            c.readPixels(0, y, w, 1, rc, ProgressMonitor.NULL);
            if (r != null) r.readPixels(0, y, w, 1, rr, ProgressMonitor.NULL);
            for (int x = 0; x < w; x++) {
                if (Float.isNaN(rc[x])) continue;
                if (r != null && (Float.isNaN(rr[x]) || rr[x] == 0f)) continue;
                sum += rc[x]; n++;
            }
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    private static void printHistogram(String tag, Product p) throws Exception {
        final Band c = bandByUnit(p, Unit.COHERENCE);
        final Band r = bandByUnit(p, Unit.REAL);
        if (c == null) { System.out.println(tag + ": no coherence band"); return; }
        final int[] bins = new int[10];
        final int w = c.getRasterWidth(), h = c.getRasterHeight();
        final float[] rc = new float[w], rr = new float[w];
        for (int y = 0; y < h; y++) {
            c.readPixels(0, y, w, 1, rc, ProgressMonitor.NULL);
            if (r != null) r.readPixels(0, y, w, 1, rr, ProgressMonitor.NULL);
            for (int x = 0; x < w; x++) {
                if (Float.isNaN(rc[x])) continue;
                if (r != null && (Float.isNaN(rr[x]) || rr[x] == 0f)) continue;
                int bin = (int) Math.floor(Math.max(0, Math.min(0.9999, rc[x])) * 10);
                bins[bin]++;
            }
        }
        System.out.println(tag + " coherence histogram (valid pixels, 0.0..1.0 in 0.1 bins):");
        for (int i = 0; i < 10; i++) System.out.printf("  [%.1f-%.1f) %d%n", i / 10.0, (i + 1) / 10.0, bins[i]);
    }

    private static void writeOut(Product p, String name) throws Exception {
        final File dir = new File("E:/out");
        if (dir.isDirectory() || dir.mkdirs()) {
            final File out = new File(dir, name + ".dim");
            ProductIO.writeProduct(p, out, "BEAM-DIMAP", false, ProgressMonitor.NULL);
            System.out.println("  -> " + out.getAbsolutePath());
        }
    }
}
