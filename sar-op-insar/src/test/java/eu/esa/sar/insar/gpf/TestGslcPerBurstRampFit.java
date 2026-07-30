/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc.
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
package eu.esa.sar.insar.gpf;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The per-burst residual-ramp fitter is a pure function from labelled gradient samples to a
 * segmented model in AZIMUTH TIME — the physical axis of the TOPS deramp-annotation error. The
 * critical property under test beyond parameter recovery: iso-eta lines are tilted in map space,
 * so each burst's azimuth rate leaks into the map-x gradient as {@code rate * dEtaDx}; the fitter
 * must separate that leakage from the genuine shared range gradient (a map-row model provably
 * cannot — it was measured leaving ~19 rad of per-burst x-ramp misfit across a swath).
 */
public class TestGslcPerBurstRampFit {

    private static final double N = 1000.0;   // must match InterferogramOp.GSLC_RAMP_NORM

    // three bursts with S1-like overlap, seconds of day; eta centres = interval midpoints
    private static final double[] START = {80000.0, 80002.7, 80005.4};
    private static final double[] END = {80003.0, 80005.7, 80008.4};
    private static final double[] ETA_K = {80001.5, 80004.2, 80006.9};

    // truth: shared range terms + per-burst azimuth rate polynomials (rad/s), S1-like magnitudes
    private static final double A_TRUE = 25.0;      // rad per N px
    private static final double C2_TRUE = -0.10;
    private static final double[] B_TRUE = {-55.0, -45.0, -65.0};
    private static final double[] Q_TRUE = {2.0, -1.5, 3.0};

    // iso-eta tilt: ~1.96e-3 s per map row, ~0.2 of that per map column
    private static final double DETA_DY = 1.96e-3;
    private static final double DETA_DX = 3.9e-4;

    private static double truthRate(final int k, final double eta) {
        return B_TRUE[k] + 2.0 * Q_TRUE[k] * (eta - ETA_K[k]);
    }

    private static List<double[]> synthSamples(final Random rng, final boolean starveBurst1) {
        final List<double[]> samples = new ArrayList<>();
        for (int k = 0; k < 3; k++) {
            final int rows = (k == 1 && starveBurst1) ? 0 : 8;
            for (int r = 0; r < rows; r++) {
                final double eta = ETA_K[k] - 1.0 + r * (2.0 / Math.max(rows - 1, 1));
                for (double x = 2000; x < 38000; x += 6000) {
                    final double rate = truthRate(k, eta);
                    final double fx = A_TRUE / N + 2.0 * C2_TRUE * x / (N * N) + rate * DETA_DX
                            + 2e-5 * rng.nextGaussian();
                    final double fy = rate * DETA_DY + 2e-5 * rng.nextGaussian();
                    samples.add(new double[]{x, eta, fx, fy, 0.5, k, DETA_DX, DETA_DY});
                }
            }
            if (rows > 0) {
                // one gross outlier per burst: the per-burst trim must reject it rather than let
                // it drag the small 2-parameter fit (a global trim would instead reject honest
                // samples of strongly-deviating bursts, the very bug class this fitter replaces)
                samples.add(new double[]{20000, ETA_K[k], 0.5, -0.5, 0.5, k, DETA_DX, DETA_DY});
            }
        }
        return samples;
    }

    @Test
    public void testRecoversPerBurstRateAndSharedRangeTerms() {
        final InterferogramOp.GslcPerBurstRamp ramp =
                InterferogramOp.fitGslcPerBurstRamp(synthSamples(new Random(42), false), START, END);

        // the tilt leakage (rate*dEtaDx ~ -0.02 rad/px, burst-dependent) must NOT end up in aN
        assertEquals(A_TRUE, ramp.aN, 0.05);
        assertEquals(C2_TRUE, ramp.c2N, 0.05);
        for (int k = 0; k < 3; k++) {
            for (double eta = ETA_K[k] - 0.9; eta <= ETA_K[k] + 0.9; eta += 0.45) {
                assertEquals("rate burst " + k + " at eta=" + eta,
                        truthRate(k, eta), ramp.rateAt(eta, k), 0.05);
            }
        }
    }

    @Test
    public void testBurstSeamIsADiscontinuity() {
        final InterferogramOp.GslcPerBurstRamp ramp =
                InterferogramOp.fitGslcPerBurstRamp(synthSamples(new Random(1), false), START, END);
        // adjacent bursts predict different rates at the same azimuth time: the model must NOT be
        // continuous across the seam (the annotation error is per burst)
        final double seamEta = 0.5 * (START[1] + END[0]);
        assertTrue("seam must separate burst models",
                Math.abs(ramp.rateAt(seamEta, 0) - ramp.rateAt(seamEta, 1)) > 1.0);
    }

    @Test
    public void testStarvedBurstInheritsNeighbours() {
        final InterferogramOp.GslcPerBurstRamp ramp =
                InterferogramOp.fitGslcPerBurstRamp(synthSamples(new Random(7), true), START, END);
        // burst 1 had no samples: its rate at centre must be finite and bracketed by its
        // neighbours' centre rates (linear inheritance over burst index)
        final double r = ramp.rateAt(ETA_K[1], 1);
        final double lo = Math.min(ramp.rateAt(ETA_K[0], 0), ramp.rateAt(ETA_K[2], 2));
        final double hi = Math.max(ramp.rateAt(ETA_K[0], 0), ramp.rateAt(ETA_K[2], 2));
        assertTrue(Double.isFinite(r));
        assertTrue("inherited rate outside neighbour bracket", r >= lo - 1e-6 && r <= hi + 1e-6);
    }

    @Test
    public void testPhaseIsQuadraticInEtaWithinBurst() {
        final InterferogramOp.GslcPerBurstRamp ramp =
                InterferogramOp.fitGslcPerBurstRamp(synthSamples(new Random(3), false), START, END);
        // phaseAt must integrate rateAt: finite-difference the phase and compare with the rate
        final int k = 2;
        final double eta = ETA_K[k] + 0.4, x = 15000, h = 1e-3;
        final double numRate = (ramp.phaseAt(x, eta + h, k) - ramp.phaseAt(x, eta - h, k)) / (2 * h);
        assertEquals(ramp.rateAt(eta, k), numRate, 1e-6);
    }

    @Test
    public void testBurstOfSodMidpointRule() {
        final InterferogramOp.GslcPerBurstRamp ramp =
                InterferogramOp.fitGslcPerBurstRamp(synthSamples(new Random(3), false), START, END);
        assertEquals(0, ramp.burstOfSod(80001.0));
        // overlap of bursts 0/1 spans 80002.7..80003.0; midpoint 80002.85 decides
        assertEquals(0, ramp.burstOfSod(80002.80));
        assertEquals(1, ramp.burstOfSod(80002.90));
        assertEquals(2, ramp.burstOfSod(80008.0));
        assertEquals(2, ramp.burstOfSod(80100.0));   // beyond the last burst clamps to it
    }
}
