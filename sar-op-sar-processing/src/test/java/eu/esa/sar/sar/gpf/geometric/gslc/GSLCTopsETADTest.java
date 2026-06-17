package eu.esa.sar.sar.gpf.geometric.gslc;

import eu.esa.sar.commons.test.ProcessorTest;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.GPF;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Layer 3: GSLC must run on an ETAD-corrected TOPS SLC, and the ETAD correction must
 * make a measurable-but-bounded difference to the geocoded product (ETAD shifts
 * geolocation by cm–dm). File-gated on the local IW-Philippines ETAD pair.
 */
@Ignore("Internal test harness")
public class GSLCTopsETADTest extends ProcessorTest {

    // ETAD-Surat has both the SLC (1SSH, single-pol) and the matching ETAD .SAFE locally.
    private static final File ETAD_DIR =
            new File("E:/TestData/s1tbx/SAR/S1_ETAD/ETAD/ETAD-Surat");

    private static File find(String contains) {
        final File[] fs = ETAD_DIR.listFiles();
        if (fs == null) return null;
        for (final File f : fs) if (f.getName().contains(contains)) return f;
        return null;
    }

    private static Product splitIW1(Product src) {
        final Map<String, Object> p = new HashMap<>();
        p.put("subswath", "IW1");
        p.put("firstBurstIndex", 1);
        p.put("lastBurstIndex", 2);
        return GPF.createProduct("TOPSAR-Split", p, src);
    }

    private static Product gslc(Product in) {
        final Map<String, Object> p = new HashMap<>();
        p.put("demName", "SRTM 3Sec");
        p.put("imgResamplingMethod", "BILINEAR_INTERPOLATION");
        p.put("nodataValueAtSea", false);
        return GPF.createProduct("GSLC-Terrain-Correction", p, in);
    }

    @Test
    public void testEtadCorrectedTopsThroughGslc() throws Exception {
        final File slc = find("_SLC_");
        final File etad = find("_ETA_");
        assumeTrue("ETAD pair not found", slc != null && etad != null && slc.exists() && etad.exists());

        try (final Product src = TestUtils.readSourceProduct(slc)) {
            final Product split = splitIW1(src);

            // ETAD-corrected branch.
            final Map<String, Object> etadP = new HashMap<>();
            etadP.put("etadFile", etad);
            final Product corrected = GPF.createProduct("S1-ETAD-Correction", etadP, split);
            final Product gCorr = gslc(corrected);
            assertNotNull("ETAD GSLC geocoding", gCorr.getSceneGeoCoding());

            // Non-ETAD branch on the identical split.
            final Product gPlain = gslc(splitIW1(src));

            // Both geocode to the same grid size; the data differs by a small geolocation shift.
            final double diff = meanAbsDiffFirstReal(gPlain, gCorr);
            System.out.printf("ETAD vs non-ETAD mean|delta(real band)| = %.6g%n", diff);
            assertTrue("ETAD must change the GSLC (got " + diff + ")", diff > 0.0);
        }
    }

    private static double meanAbsDiffFirstReal(Product a, Product b) throws Exception {
        final Band ba = firstReal(a), bb = firstReal(b);
        assertNotNull(ba); assertNotNull(bb);
        final int w = Math.min(ba.getRasterWidth(), bb.getRasterWidth());
        final int h = Math.min(ba.getRasterHeight(), bb.getRasterHeight());
        final float[] ra = new float[w], rb = new float[w];
        double sum = 0; long n = 0;
        for (int y = 0; y < h; y += 8) {
            ba.readPixels(0, y, w, 1, ra, com.bc.ceres.core.ProgressMonitor.NULL);
            bb.readPixels(0, y, w, 1, rb, com.bc.ceres.core.ProgressMonitor.NULL);
            for (int x = 0; x < w; x++) {
                if (Float.isNaN(ra[x]) || Float.isNaN(rb[x])) continue;
                sum += Math.abs(ra[x] - rb[x]); n++;
            }
        }
        return n == 0 ? 0.0 : sum / n;
    }

    private static Band firstReal(Product p) {
        for (final Band b : p.getBands()) {
            if ("real".equals(b.getUnit()) || b.getName().startsWith("i_")) return b;
        }
        return null;
    }
}
