package eu.esa.sar.sar.gpf.geometric;

import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.Product;
import org.junit.Test;

import java.io.File;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Data-driven Doppler-centroid estimation (Madsen lag-1 ACF) for the stripmap GSLC
 * azimuth deramp: the estimator that arbitrates against (and substitutes for) the
 * metadata Doppler polynomial whose convention is untrustworthy on archive products
 * (ERS CEOS units; legacy-VMP annotation quality).
 */
public class GSLCFdcEstimationTest {

    /** Pure fit: per-column lag-1 ACF accumulations -> smooth quadratic f_dc(col) profile. */
    @Test
    public void fitFdcProfileRecoversQuadraticCarrier() {
        final int W = 4000;
        final double PRF = 1679.902;
        // truth: f_dc(col) = -550 + 60*(col/W) + 120*(col/W)^2  (ERS-2-like)
        final double[] accRe = new double[W];
        final double[] accIm = new double[W];
        final Random rnd = new Random(3);
        for (int c = 0; c < W; c++) {
            final double x = c / (double) W;
            final double f = -550.0 + 60.0 * x + 120.0 * x * x;
            final double phi = 2.0 * Math.PI * f / PRF;
            // ACF accumulation of ~1500 noisy lag-1 products: concentration < 1, phase = phi
            final double mag = 1500.0 * (0.6 + 0.05 * rnd.nextDouble());
            final double jitter = 0.002 * rnd.nextGaussian();
            accRe[c] = mag * Math.cos(phi + jitter);
            accIm[c] = mag * Math.sin(phi + jitter);
        }
        final double[] fdc = GSLCGeocodingOp.fitFdcProfile(accRe, accIm, W, PRF);
        assertNotNull(fdc);
        assertEquals(W, fdc.length);
        for (int c = 0; c < W; c += 250) {
            final double x = c / (double) W;
            final double truth = -550.0 + 60.0 * x + 120.0 * x * x;
            assertEquals("f_dc at col " + c, truth, fdc[c], 5.0);
        }
    }

    /** Arbitration: annotation kept when it agrees with the data, replaced when it does not. */
    @Test
    public void chooseFdcTablePrefersDataOnDisagreement() {
        final int W = 1000;
        final double PRF = 1679.902;
        final double[] data = new double[W];
        final double[] agreeing = new double[W];
        final double[] wrong = new double[W];
        for (int c = 0; c < W; c++) {
            data[c] = -550.0 + 60.0 * c / W;
            agreeing[c] = data[c] + 8.0;    // within the 25 Hz gate
            wrong[c] = data[c] + 220.0;     // an ERS-2-VMP-scale annotation error
        }
        assertSame("agreeing annotation must be kept",
                agreeing, GSLCGeocodingOp.chooseFdcTable(agreeing, data, PRF));
        assertSame("disagreeing annotation must be replaced by the data estimate",
                data, GSLCGeocodingOp.chooseFdcTable(wrong, data, PRF));
        assertSame("missing annotation falls back to the data estimate",
                data, GSLCGeocodingOp.chooseFdcTable(null, data, PRF));
        assertSame("missing data estimate keeps the annotation",
                agreeing, GSLCGeocodingOp.chooseFdcTable(agreeing, null, PRF));
    }

    /**
     * File-gated: on the real ERS-2 VMP SLC the data estimator must reproduce the
     * python lag-1 ACF truth (2026-08-10): near/mid/far = -556.3 / -549.0 / -491.1 Hz.
     */
    @Test
    public void ersFdcEstimateMatchesMeasuredTruth() throws Exception {
        final File f = new File(
                "E:/Output/ers/ERS-2_SAR_SLC-ORBIT_1486_DATE__2-AUG-1995_21_16_42_Orb.dim");
        assumeTrue(f.exists());
        try (Product p = ProductIO.readProduct(f)) {
            final double PRF = 1679.902;
            final double[] fdc = GSLCGeocodingOp.estimateFdcFromData(p, PRF);
            assertNotNull("estimator must produce a profile on a real complex SLC", fdc);
            final int W = p.getSceneRasterWidth();
            assertEquals(-556.3, fdc[W / 32], 15.0);
            assertEquals(-549.0, fdc[W / 2], 15.0);
            assertEquals(-491.1, fdc[W - 1 - W / 32], 15.0);
            // smoothness: the fitted profile must not wiggle (it is a quadratic)
            double maxStep = 0;
            for (int c = 1; c < W; c++) {
                maxStep = Math.max(maxStep, Math.abs(fdc[c] - fdc[c - 1]));
            }
            assertTrue("profile must be smooth per column, got step " + maxStep,
                    maxStep < 0.5);
        }
    }
}
