package eu.esa.sar.sar.gpf.geometric;

import eu.esa.sar.commons.test.ProcessorTest;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.GPF;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Layer 2 functional tests on the locally available S1A IW dual-pol SLC scene.
 * File-gated: skip (not fail) when the scene is absent. Uses the embedded orbit
 * (no Apply-Orbit-File) and {@code nodataValueAtSea=false} per existing GSLC-test convention.
 */
public class GSLCTopsFunctionalTest extends ProcessorTest {

    private static final java.io.File SLC = TestData.inputS1_SLC;

    private static Product split(Product src, String subswath) {
        final Map<String, Object> p = new HashMap<>();
        p.put("subswath", subswath);
        p.put("selectedPolarisations", "VV,VH");
        p.put("firstBurstIndex", 1);
        p.put("lastBurstIndex", 3);
        return GPF.createProduct("TOPSAR-Split", p, src);
    }

    private static Product gslcVV(Product split) {
        final Map<String, Object> p = new HashMap<>();
        p.put("demName", "SRTM 3Sec");
        p.put("imgResamplingMethod", "BILINEAR_INTERPOLATION");
        p.put("nodataValueAtSea", false);
        return GPF.createProduct("GSLC-Terrain-Correction", p, split);
    }

    private static Product gslc(Product split) {
        return gslcVV(split);
    }

    @Test
    public void testSplitToGslc_DualPolGeocoded() throws Exception {
        assumeTrue(SLC + " not found", SLC.exists());
        try (final Product src = TestUtils.readSourceProduct(SLC)) {
            final Product splitP = split(src, "IW1");
            final Product g = gslc(splitP);

            // Geocoded: map geocoding present and is_terrain_corrected stamped.
            assertNotNull("scene geocoding", g.getSceneGeoCoding());
            assertEquals("is_terrain_corrected",
                    1, AbstractMetadata.getAbstractedMetadata(g).getAttributeInt("is_terrain_corrected"));

            // Both polarizations present as complex band pairs.
            assertHasComplexPair(g, "VV");
            assertHasComplexPair(g, "VH");
            TestUtils.verifyProduct(g, true, true, true);
        }
    }

    @Test
    public void testDualPol_SameGrid() throws Exception {
        assumeTrue(SLC + " not found", SLC.exists());
        try (final Product src = TestUtils.readSourceProduct(SLC)) {
            final Product g = gslc(split(src, "IW1"));
            final Band iVV = findBand(g, "i", "VV");
            final Band iVH = findBand(g, "i", "VH");
            assertNotNull(iVV);
            assertNotNull(iVH);
            // Same raster grid for both polarizations.
            assertEquals(iVV.getRasterWidth(), iVH.getRasterWidth());
            assertEquals(iVV.getRasterHeight(), iVH.getRasterHeight());
        }
    }

    @Test
    public void testThreeSubswaths_EachGeocodes() throws Exception {
        assumeTrue(SLC + " not found", SLC.exists());
        try (final Product src = TestUtils.readSourceProduct(SLC)) {
            for (final String sw : new String[]{"IW1", "IW2", "IW3"}) {
                final Product g = gslc(split(src, sw));
                assertNotNull(sw + " geocoding", g.getSceneGeoCoding());
                assertTrue(sw + " has bands", g.getNumBands() > 0);
            }
        }
    }

    private static Product splitOneBurst(Product src, String subswath) {
        final Map<String, Object> p = new HashMap<>();
        p.put("subswath", subswath);
        p.put("selectedPolarisations", "VV");
        p.put("firstBurstIndex", 1);
        p.put("lastBurstIndex", 1);
        return GPF.createProduct("TOPSAR-Split", p, src);
    }

    /**
     * Zero-baseline sanity: two GSLCs of the same scene/grid go through CreateStack to form a
     * self-pair. Because the two grids are bit-identical (same scene, standard grid, zero offset)
     * the master and slave legs must come out pixel-identical — that is the chain self-consistency
     * property. From identity it follows that the interferometric phase is ~0 (proving the TOPS
     * deramp/reramp sign convention has no phase bias) and coherence is ~1. We measure the
     * master/slave difference directly so the test pinpoints any decorrelation source rather than
     * relying on the coherence estimator's absolute calibration. One burst, VV, for speed.
     */
    @Test
    public void testSelfPairIdentityInterferogram() throws Exception {
        assumeTrue(SLC + " not found", SLC.exists());
        try (final Product src = TestUtils.readSourceProduct(SLC)) {
            final Product splitP = splitOneBurst(src, "IW1");
            final Product g1 = gslcVV(splitP);
            final Product g2 = gslcVV(splitP);

            final Map<String, Product> stackSrc = new LinkedHashMap<>();
            stackSrc.put("sourceProduct.1", g1);
            stackSrc.put("sourceProduct.2", g2);
            final Map<String, Object> stackP = new HashMap<>();
            stackP.put("extent", "Master");
            stackP.put("resamplingType", "NONE");
            final Product stack = GPF.createProduct("CreateStack", stackP, stackSrc);

            final double maxAbsDiff = maxAbsComplexDiff(stack);
            System.out.printf("self-pair: max|master-slave| = %.6g%n", maxAbsDiff);

            final int stackTC = AbstractMetadata.getAbstractedMetadata(stack)
                    .getAttributeInt(AbstractMetadata.is_terrain_corrected, -1);
            System.out.printf("self-pair: stack is_terrain_corrected = %d%n", stackTC);

            final Map<String, Object> ifgP = new HashMap<>();
            ifgP.put("subtractFlatEarthPhase", false);
            ifgP.put("includeCoherence", true);
            ifgP.put("cohWinAz", 3);
            ifgP.put("cohWinRg", 3);
            final Product ifg = GPF.createProduct("Interferogram", ifgP, stack);

            final double meanAbsPhase = meanAbs(ifg, org.esa.snap.engine_utilities.datamodel.Unit.PHASE);
            final double meanCoh = meanCoherenceValid(ifg);
            System.out.printf("self-pair: mean|phase|=%.4f rad  meanCoh(valid)=%.4f%n", meanAbsPhase, meanCoh);

            // The defining property: a zero-baseline self-pair has identical legs.
            assertEquals("self-pair master and slave legs must be pixel-identical",
                    0.0, maxAbsDiff, 1e-6);
            assertTrue("self-pair phase should be ~0 (got " + meanAbsPhase + ")", meanAbsPhase < 0.05);
            // The stack must stay flagged geocoded so InterferogramOp uses its GSLC coherence
            // path (no flat-earth subtraction). On identical legs that path yields coherence 1.0.
            assertEquals("CreateStack must propagate is_terrain_corrected for a GSLC pair", 1, stackTC);
            assertTrue("self-pair coherence should be ~1 (got " + meanCoh + ")", meanCoh > 0.98);
        }
    }

    /**
     * Max |legA - legB| over the two complex legs of a CreateStack self-pair output, paired
     * tag-agnostically: each {@code i_*} band is matched to its {@code q_*} partner by name, and
     * the two resulting complex legs are differenced. Prints all band names/units for diagnostics.
     */
    private static double maxAbsComplexDiff(Product stack) throws java.io.IOException {
        System.out.println("stack bands:");
        for (final Band b : stack.getBands()) System.out.println("  " + b.getName() + " [" + b.getUnit() + "]");

        final java.util.List<Band> realBands = new java.util.ArrayList<>();
        for (final Band b : stack.getBands()) {
            if (org.esa.snap.engine_utilities.datamodel.Unit.REAL.equals(b.getUnit())
                    && b.getName().startsWith("i")) {
                realBands.add(b);
            }
        }
        assertEquals("expected exactly two complex legs (master, slave)", 2, realBands.size());

        final Band ai = realBands.get(0);
        final Band bi = realBands.get(1);
        final Band aq = stack.getBand("q" + ai.getName().substring(1));
        final Band bq = stack.getBand("q" + bi.getName().substring(1));
        assertNotNull("imag partner of " + ai.getName(), aq);
        assertNotNull("imag partner of " + bi.getName(), bq);

        final int w = ai.getRasterWidth(), h = ai.getRasterHeight();
        final float[] rai = new float[w], raq = new float[w], rbi = new float[w], rbq = new float[w];
        double max = 0;
        for (int y = 0; y < h; y++) {
            ai.readPixels(0, y, w, 1, rai, com.bc.ceres.core.ProgressMonitor.NULL);
            aq.readPixels(0, y, w, 1, raq, com.bc.ceres.core.ProgressMonitor.NULL);
            bi.readPixels(0, y, w, 1, rbi, com.bc.ceres.core.ProgressMonitor.NULL);
            bq.readPixels(0, y, w, 1, rbq, com.bc.ceres.core.ProgressMonitor.NULL);
            for (int x = 0; x < w; x++) {
                if (Float.isNaN(rai[x]) || Float.isNaN(rbi[x])) continue;
                max = Math.max(max, Math.abs(rai[x] - rbi[x]));
                max = Math.max(max, Math.abs(raq[x] - rbq[x]));
            }
        }
        return max;
    }

    private static void assertHasComplexPair(Product p, String pol) {
        assertNotNull("i_*_" + pol + " band", findBand(p, "i", pol));
        assertNotNull("q_*_" + pol + " band", findBand(p, "q", pol));
    }

    private static Band findBand(Product p, String prefix, String pol) {
        for (final Band b : p.getBands()) {
            final String n = b.getName();
            if (n.startsWith(prefix + "_") && n.contains(pol)) return b;
        }
        return null;
    }

    private static double meanAbs(Product p, String unit) throws java.io.IOException {
        return meanOf(p, unit, true);
    }

    /**
     * Mean coherence over the imaged area only. A geocoded product has a large nodata border
     * (map grid wider than the swath); InterferogramOp writes coherence 0.0 (not NaN) there, so a
     * naive whole-band mean is dragged down by fill. We mask to pixels where the interferogram's
     * real band is nonzero (i.e. both legs had valid imaged data).
     */
    private static double meanCoherenceValid(Product p) throws java.io.IOException {
        Band coh = null, real = null;
        for (final Band b : p.getBands()) {
            if (org.esa.snap.engine_utilities.datamodel.Unit.COHERENCE.equals(b.getUnit()) && coh == null) coh = b;
            if (org.esa.snap.engine_utilities.datamodel.Unit.REAL.equals(b.getUnit()) && real == null) real = b;
        }
        assertNotNull("coherence band", coh);
        assertNotNull("interferogram real band", real);
        final int w = coh.getRasterWidth(), h = coh.getRasterHeight();
        final float[] rc = new float[w], rr = new float[w];
        double sum = 0; long n = 0;
        for (int y = 0; y < h; y++) {
            coh.readPixels(0, y, w, 1, rc, com.bc.ceres.core.ProgressMonitor.NULL);
            real.readPixels(0, y, w, 1, rr, com.bc.ceres.core.ProgressMonitor.NULL);
            for (int x = 0; x < w; x++) {
                if (Float.isNaN(rc[x]) || Float.isNaN(rr[x]) || rr[x] == 0f) continue;
                sum += rc[x]; n++;
            }
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    private static double meanOf(Product p, String unit, boolean abs) throws java.io.IOException {
        Band band = null;
        for (final Band b : p.getBands()) {
            if (unit.equals(b.getUnit())) { band = b; break; }
        }
        assertNotNull("band with unit " + unit, band);
        final int w = band.getRasterWidth(), h = band.getRasterHeight();
        final float[] row = new float[w];
        double sum = 0; long n = 0;
        for (int y = 0; y < h; y++) {
            band.readPixels(0, y, w, 1, row, com.bc.ceres.core.ProgressMonitor.NULL);
            for (final float v : row) {
                if (Float.isNaN(v)) continue;
                sum += abs ? Math.abs(v) : v; n++;
            }
        }
        return n == 0 ? Double.NaN : sum / n;
    }
}
