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
}
