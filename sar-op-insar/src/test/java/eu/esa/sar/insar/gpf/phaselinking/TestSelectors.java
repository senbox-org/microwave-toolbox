/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. https://www.skywatch.com
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */
package eu.esa.sar.insar.gpf.phaselinking;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertTrue;

public class TestSelectors {

    private static double[] rayleighSeries(final Random rng, final double sigma, final int n) {
        final double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            final double u1 = rng.nextDouble();
            // Inverse CDF of Rayleigh(sigma): R = sigma * sqrt(-2 * ln(1 - u))
            out[i] = sigma * Math.sqrt(-2.0 * Math.log(Math.max(1.0e-12, 1.0 - u1)));
        }
        return out;
    }

    @Test
    public void ks_accepts_same_distribution_majority_of_the_time() {
        final int n = 30;
        final int trials = 200;
        int accept = 0;
        final Random rng = new Random(42);
        final KSSelector ks = new KSSelector(0.05, n);
        for (int t = 0; t < trials; t++) {
            final double[] centre = rayleighSeries(rng, 1.0, n);
            final double[] candidate = rayleighSeries(rng, 1.0, n);
            ks.prepareCentre(centre);
            if (ks.accept(centre, candidate)) accept++;
        }
        // At alpha=0.05 we expect ~95% acceptance under H0
        assertTrue("KS H0 acceptance rate too low: " + accept + "/" + trials,
                accept >= (int) (0.85 * trials));
    }

    @Test
    public void ks_rejects_clearly_different_distributions() {
        final int n = 30;
        final int trials = 200;
        int reject = 0;
        final Random rng = new Random(43);
        final KSSelector ks = new KSSelector(0.05, n);
        for (int t = 0; t < trials; t++) {
            final double[] centre = rayleighSeries(rng, 1.0, n);
            final double[] candidate = rayleighSeries(rng, 5.0, n);   // very different scale
            ks.prepareCentre(centre);
            if (!ks.accept(centre, candidate)) reject++;
        }
        // Power against a 5x scale difference should be > 95%
        assertTrue("KS power too low: " + reject + "/" + trials, reject >= (int) (0.90 * trials));
    }

    @Test
    public void tlog_rejects_clearly_different_distributions() {
        final int n = 30;
        final int trials = 200;
        int reject = 0;
        final Random rng = new Random(44);
        final TLogSelector tlog = new TLogSelector(0.05);
        for (int t = 0; t < trials; t++) {
            final double[] centre = rayleighSeries(rng, 1.0, n);
            final double[] candidate = rayleighSeries(rng, 5.0, n);
            tlog.prepareCentre(centre);
            if (!tlog.accept(centre, candidate)) reject++;
        }
        assertTrue("TLog power too low: " + reject + "/" + trials, reject >= (int) (0.90 * trials));
    }

    @Test
    public void ad_accepts_same_distribution_majority_of_the_time() {
        // Calibration: with the proper Scholz-Stephens standardisation the two-sample AD test at
        // alpha=0.05 must accept ~95% of same-distribution pairs (not over-reject).
        final int n = 30;
        final int trials = 400;
        int accept = 0;
        final Random rng = new Random(46);
        final ADSelector ad = new ADSelector(0.05, n);
        for (int t = 0; t < trials; t++) {
            final double[] centre = rayleighSeries(rng, 1.0, n);
            final double[] candidate = rayleighSeries(rng, 1.0, n);
            ad.prepareCentre(centre);
            if (ad.accept(centre, candidate)) accept++;
        }
        assertTrue("AD H0 acceptance rate too low (over-rejecting): " + accept + "/" + trials,
                accept >= (int) (0.88 * trials));
    }

    @Test
    public void ad_rejects_clearly_different_distributions() {
        final int n = 30;
        final int trials = 200;
        int reject = 0;
        final Random rng = new Random(45);
        final ADSelector ad = new ADSelector(0.05, n);
        for (int t = 0; t < trials; t++) {
            final double[] centre = rayleighSeries(rng, 1.0, n);
            final double[] candidate = rayleighSeries(rng, 5.0, n);
            ad.prepareCentre(centre);
            if (!ad.accept(centre, candidate)) reject++;
        }
        assertTrue("AD power too low: " + reject + "/" + trials, reject >= (int) (0.80 * trials));
    }
}
