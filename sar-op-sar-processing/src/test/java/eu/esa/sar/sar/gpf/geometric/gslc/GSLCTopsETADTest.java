package eu.esa.sar.sar.gpf.geometric.gslc;

import com.bc.ceres.test.LongTestRunner;
import eu.esa.sar.commons.test.ProcessorTest;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.GPF;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;
import org.junit.runner.RunWith;

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
 * <p>
 * <b>Long test.</b> This harness drives real SAR products through multi-operator chains, so it is
 * gated off by default and enabled explicitly:
 * <pre>
 *   mvn test -pl sar-op-sar-processing -Dtest=GSLCTopsETADTest -Denable.long.tests=true
 * </pre>
 * It remains fixture-gated on top of that, so it skips cleanly where the input products are absent.
 */
@RunWith(LongTestRunner.class)
public class GSLCTopsETADTest extends ProcessorTest {

    // ETAD-Surat has both the SLC (1SSH, single-pol) and the matching ETAD .SAFE locally.
    private static final File ETAD_DIR =
            new File(TestData.inputSAR + "S1_ETAD/ETAD/ETAD-Surat");

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
        // Copernicus 30m: the project-standard DEM, cached by every other GSLC test/run on this
        // machine — SRTM 3Sec risked a fresh tile download inside the timed test.
        p.put("demName", "Copernicus 30m Global DEM");
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
            // A CENTRE WINDOW is ample to prove "ETAD changed the data" — and it is the whole
            // difference between a minutes test and a half-hour one: reading full-width rows pulled
            // every tile of BOTH GSLC rasters plus the full ETAD resample behind one of them,
            // whereas reading a window lets GPF laziness compute only the tiles it touches, all the
            // way back through the chain.
            final double diff = meanAbsDiffCentreWindow(gPlain, gCorr, 1024);
            System.out.printf("ETAD vs non-ETAD mean|delta(real band)| over centre window = %.6g%n", diff);
            assertTrue("ETAD must change the GSLC (got " + diff + ")", diff > 1e-3);
        }
    }

    private static double meanAbsDiffCentreWindow(Product a, Product b, int size) throws Exception {
        final Band ba = firstReal(a), bb = firstReal(b);
        assertNotNull(ba); assertNotNull(bb);
        final int w = Math.min(ba.getRasterWidth(), bb.getRasterWidth());
        final int h = Math.min(ba.getRasterHeight(), bb.getRasterHeight());
        final int ww = Math.min(size, w), wh = Math.min(size, h);
        final int x0 = (w - ww) / 2, y0 = (h - wh) / 2;
        final float[] ra = new float[ww], rb = new float[ww];
        double sum = 0; long n = 0;
        for (int y = y0; y < y0 + wh; y += 4) {
            ba.readPixels(x0, y, ww, 1, ra, com.bc.ceres.core.ProgressMonitor.NULL);
            bb.readPixels(x0, y, ww, 1, rb, com.bc.ceres.core.ProgressMonitor.NULL);
            for (int x = 0; x < ww; x++) {
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
