package eu.esa.sar.insar.gpf;

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.CrsGeoCoding;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * GSLC-mode interferogram formation from a stack with <em>several</em> secondaries.
 * <p>
 * {@code InterferogramOp.initializeGSLC()} used to size the pair list with
 * {@code Math.min(refIBands.size(), secIBands.size())}. A GSLC stack is one reference + N
 * secondaries, so that evaluated to 1 and secondaries 2..N were silently dropped from the output —
 * data loss with no warning. These tests pin the corrected behaviour: N secondaries produce N
 * interferograms, each carrying its <em>own</em> phase, and pairing follows polarisation rather
 * than band order.
 */
public class TestGslcMultiSecondary {

    private static final int SIZE = 256;

    /** Distinct phase plane per secondary, so a mispaired band is detectable in the output. */
    private static double plane(final int which, final int x, final int y) {
        switch (which) {
            case 1:  return 0.010 * x;
            case 2:  return 0.020 * y;
            default: return 0.005 * (x + y);
        }
    }

    private static Product newStack() throws Exception {
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
        return p;
    }

    private static void addBand(final Product p, final String name, final String unit, final float[] data) {
        final Band b = new Band(name, ProductData.TYPE_FLOAT32, SIZE, SIZE);
        b.setUnit(unit);
        b.setRasterData(ProductData.createInstance(data));
        p.addBand(b);
    }

    /** Unit-amplitude reference: 1 + 0j everywhere. */
    private static void addReference(final Product p, final String tag) {
        final float[] one = new float[SIZE * SIZE];
        final float[] zero = new float[SIZE * SIZE];
        for (int k = 0; k < one.length; k++) one[k] = 1f;
        addBand(p, "i_" + tag, Unit.REAL, one);
        addBand(p, "q_" + tag, Unit.IMAGINARY, zero);
    }

    /** Secondary = exp(-j*plane), so ref*conj(sec) = exp(+j*plane). */
    private static void addSecondary(final Product p, final String tag, final int which) {
        final float[] si = new float[SIZE * SIZE];
        final float[] sq = new float[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                final int k = y * SIZE + x;
                final double ph = plane(which, x, y);
                si[k] = (float) Math.cos(ph);
                sq[k] = (float) -Math.sin(ph);
            }
        }
        addBand(p, "i_" + tag, Unit.REAL, si);
        addBand(p, "q_" + tag, Unit.IMAGINARY, sq);
    }

    private static Product runIfg(final Product src, final boolean coherence) {
        final InterferogramOp op = new InterferogramOp();
        op.setSourceProduct(src);
        op.setParameter("subtractFlatEarthPhase", false);
        op.setParameter("subtractTopographicPhase", false);
        op.setParameter("subtractResidualRamp", false);
        op.setParameter("includeCoherence", coherence);
        return op.getTargetProduct();
    }

    private static List<String> namesStartingWith(final Product p, final String prefix) {
        final List<String> out = new ArrayList<>();
        for (final Band b : p.getBands()) {
            if (b.getName().startsWith(prefix)) out.add(b.getName());
        }
        return out;
    }

    /** Mean phase of a band pair over an interior block, and the expected plane's mean. */
    private static double meanPhase(final Product tgt, final String iName) throws Exception {
        final Band bi = tgt.getBand(iName);
        final Band bq = tgt.getBand('q' + iName.substring(1));
        assertNotNull("missing " + iName, bi);
        assertNotNull("missing q partner of " + iName, bq);
        final int x0 = 32, y0 = 32, n = 64;
        final float[] iv = new float[n * n];
        final float[] qv = new float[n * n];
        bi.readPixels(x0, y0, n, n, iv);
        bq.readPixels(x0, y0, n, n, qv);
        double sr = 0, si = 0;
        for (int k = 0; k < n * n; k++) {
            final double m = Math.hypot(iv[k], qv[k]);
            if (m <= 0) continue;
            sr += iv[k] / m;
            si += qv[k] / m;
        }
        return Math.atan2(si, sr);
    }

    private static double expectedMeanPhase(final int which) {
        final int x0 = 32, y0 = 32, n = 64;
        double sr = 0, si = 0;
        for (int y = y0; y < y0 + n; y++) {
            for (int x = x0; x < x0 + n; x++) {
                final double ph = plane(which, x, y);
                sr += Math.cos(ph);
                si += Math.sin(ph);
            }
        }
        return Math.atan2(si, sr);
    }

    /**
     * The headline case: 1 reference + 3 secondaries must yield 3 interferograms and 3 coherence
     * bands, each ifg carrying the phase plane of its own secondary.
     */
    @Test
    public void testThreeSecondariesYieldThreeInterferograms() throws Exception {
        final Product src = newStack();
        addReference(src, "ref_23Jun2026");
        addSecondary(src, "sec1_24Jun2026", 1);
        addSecondary(src, "sec2_30Jun2026", 2);
        addSecondary(src, "sec3_06Jul2026", 3);

        final Product tgt = runIfg(src, true);

        final List<String> ifgI = namesStartingWith(tgt, "i_ifg");
        final List<String> ifgQ = namesStartingWith(tgt, "q_ifg");
        final List<String> coh = namesStartingWith(tgt, "coh");
        assertEquals("expected one i_ifg band per secondary: " + ifgI, 3, ifgI.size());
        assertEquals("expected one q_ifg band per secondary: " + ifgQ, 3, ifgQ.size());
        assertEquals("expected one coherence band per secondary: " + coh, 3, coh.size());

        // Band names must be distinct and carry the secondary date, so pairs are identifiable.
        assertEquals("ifg band names must be unique: " + ifgI, 3, new java.util.HashSet<>(ifgI).size());
        for (final String d : new String[]{"24Jun2026", "30Jun2026", "06Jul2026"}) {
            boolean found = false;
            for (final String n : ifgI) found |= n.contains(d);
            assertTrue("no ifg band carries secondary date " + d + ": " + ifgI, found);
        }

        // Each interferogram must carry its own plane, not the first secondary's.
        for (int which = 1; which <= 3; which++) {
            final String date = which == 1 ? "24Jun2026" : which == 2 ? "30Jun2026" : "06Jul2026";
            String name = null;
            for (final String n : ifgI) if (n.contains(date)) name = n;
            assertNotNull("no ifg for " + date, name);
            final double got = meanPhase(tgt, name);
            final double want = expectedMeanPhase(which);
            final double d = Math.abs(Math.atan2(Math.sin(got - want), Math.cos(got - want)));
            assertTrue(String.format("ifg %s carries the wrong phase: got %.4f want %.4f rad",
                    name, got, want), d < 0.02);
        }
    }

    /**
     * A multi-polarisation stack must pair by polarisation, not by band order. The secondaries are
     * added in the opposite order to the references, so positional pairing would cross VV with VH
     * and produce the wrong phase in each band.
     */
    @Test
    public void testMultiPolPairsByPolarisationNotBandOrder() throws Exception {
        final Product src = newStack();
        addReference(src, "ref_IW1_VV_23Jun2026");
        addReference(src, "ref_IW1_VH_23Jun2026");
        // deliberately reversed relative to the references
        addSecondary(src, "sec1_IW1_VH_30Jun2026", 2);
        addSecondary(src, "sec1_IW1_VV_30Jun2026", 1);

        final Product tgt = runIfg(src, false);

        final List<String> ifgI = namesStartingWith(tgt, "i_ifg");
        assertEquals("expected one ifg per polarisation: " + ifgI, 2, ifgI.size());

        String vv = null, vh = null;
        for (final String n : ifgI) {
            if (n.contains("VV")) vv = n;
            if (n.contains("VH")) vh = n;
        }
        assertNotNull("no VV ifg in " + ifgI, vv);
        assertNotNull("no VH ifg in " + ifgI, vh);

        // VV secondary was built with plane 1, VH with plane 2.
        final double dVV = Math.abs(meanPhase(tgt, vv) - expectedMeanPhase(1));
        final double dVH = Math.abs(meanPhase(tgt, vh) - expectedMeanPhase(2));
        assertTrue("VV ifg paired with the wrong polarisation (off by " + dVV + " rad)", dVV < 0.02);
        assertTrue("VH ifg paired with the wrong polarisation (off by " + dVH + " rad)", dVH < 0.02);
    }

    /**
     * A secondary whose polarisation matches no reference must fail loudly. Silently truncating the
     * stack or pairing mismatched polarisations is worse than an error.
     */
    @Test
    public void testUnmatchablePolarisationFailsLoudly() throws Exception {
        final Product src = newStack();
        addReference(src, "ref_IW1_VV_23Jun2026");
        addReference(src, "ref_IW1_VH_23Jun2026");
        addSecondary(src, "sec1_IW1_HH_30Jun2026", 1);

        try {
            runIfg(src, false);
            fail("expected an OperatorException for a secondary with no matching reference polarisation");
        } catch (OperatorException e) {
            assertTrue("unhelpful message: " + e.getMessage(),
                    e.getMessage().contains("sec1_IW1_HH_30Jun2026"));
        }
    }

    /** The single-secondary case must keep working exactly as before. */
    @Test
    public void testSingleSecondaryUnchanged() throws Exception {
        final Product src = newStack();
        addReference(src, "ref_23Jun2026");
        addSecondary(src, "sec1_30Jun2026", 1);

        final Product tgt = runIfg(src, true);
        assertEquals(1, namesStartingWith(tgt, "i_ifg").size());
        assertEquals(1, namesStartingWith(tgt, "coh").size());
        final double d = Math.abs(meanPhase(tgt, namesStartingWith(tgt, "i_ifg").get(0))
                - expectedMeanPhase(1));
        assertTrue("single-pair phase wrong by " + d + " rad", d < 0.02);
    }
    /**
     * Coherence must ignore the (0,0) geocoding fill.
     * <p>
     * A geocoded product is mostly fill around its edges. Counting a fill sample in the reference
     * power sum but not in the cross/secondary sums drove the ratio toward zero, so valid pixels
     * within half a window of a fill boundary read systematically LOW — up to 25% on a perfectly
     * coherent pair — which is indistinguishable from real decorrelation. And a non-zero coherence
     * was written at pixels where the interferogram itself is no-data, so the two masks disagreed.
     */
    @Test
    public void testCoherenceIgnoresGeocodingFill() throws Exception {
        final int FILL_FROM = 160;
        final Product src = newStack();
        addReference(src, "ref_23Jun2026");

        // secondary identical to the reference (=> true coherence 1) but fill for x >= FILL_FROM
        final float[] si = new float[SIZE * SIZE];
        final float[] sq = new float[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                if (x < FILL_FROM) { si[y * SIZE + x] = 1f; sq[y * SIZE + x] = 0f; }
            }
        }
        addBand(src, "i_sec1_30Jun2026", Unit.REAL, si);
        addBand(src, "q_sec1_30Jun2026", Unit.IMAGINARY, sq);

        final Product tgt = runIfg(src, true);
        final Band coh = tgt.getBand(namesStartingWith(tgt, "coh").get(0));
        assertNotNull("coherence band missing", coh);
        final float[] c = new float[SIZE];
        coh.readPixels(0, SIZE / 2, SIZE, 1, c);

        // valid pixels right up to the boundary must still read ~1, not a decaying ramp
        for (int x = FILL_FROM - 12; x < FILL_FROM; x++) {
            assertTrue("coherence depressed by the nearby fill at x=" + x + ": " + c[x],
                    c[x] > 0.95f);
        }
        // deep inside the fill there is no valid sample pair => no-data
        for (int x = FILL_FROM + 12; x < SIZE; x++) {
            assertEquals("coherence must be no-data inside the fill at x=" + x, 0.0f, c[x], 1e-6f);
        }
    }
}
