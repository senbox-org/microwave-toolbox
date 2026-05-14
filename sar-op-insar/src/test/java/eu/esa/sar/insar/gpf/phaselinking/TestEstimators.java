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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestEstimators {

    /**
     * Build T = gamma * u u^H + (1 - gamma) * I, where u is a unit-norm complex
     * phasor with known per-epoch phases.
     */
    private static double[][][] buildKnownT(final int n, final double[] truePhases, final double gamma) {
        final double[] ur = new double[n];
        final double[] ui = new double[n];
        final double inv = 1.0 / Math.sqrt(n);
        for (int k = 0; k < n; k++) {
            ur[k] = Math.cos(truePhases[k]) * inv;
            ui[k] = Math.sin(truePhases[k]) * inv;
        }
        final double[][] tRe = new double[n][n];
        final double[][] tIm = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                final double rr = ur[i] * ur[j] + ui[i] * ui[j];
                final double ii = ui[i] * ur[j] - ur[i] * ui[j];
                tRe[i][j] = gamma * n * rr + ((i == j) ? (1.0 - gamma) : 0.0);
                tIm[i][j] = gamma * n * ii;
            }
        }
        // For a unit-trace coherence matrix (diag=1), re-normalise:
        for (int i = 0; i < n; i++) {
            final double diag = tRe[i][i];
            if (diag <= 0.0) continue;
            // Already normalised by construction with u being unit-norm: each diag entry should be gamma + (1-gamma) = 1 modulo the n scaling. Fix:
        }
        return new double[][][]{tRe, tIm};
    }

    @Test
    public void evd_recovers_phase_on_clean_rank_one_T() {
        final int n = 8;
        final double[] truePhases = new double[n];
        for (int k = 0; k < n; k++) truePhases[k] = 0.3 * k;  // ramp

        // Build clean rank-1 T = u u^H, normalised so diag = 1
        final double[] ur = new double[n];
        final double[] ui = new double[n];
        final double inv = 1.0 / Math.sqrt(n);
        for (int k = 0; k < n; k++) {
            ur[k] = Math.cos(truePhases[k]) * inv;
            ui[k] = Math.sin(truePhases[k]) * inv;
        }
        final double[][] tRe = new double[n][n];
        final double[][] tIm = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                tRe[i][j] = n * (ur[i] * ur[j] + ui[i] * ui[j]);
                tIm[i][j] = n * (ui[i] * ur[j] - ur[i] * ui[j]);
            }
        }

        final double[] phi = new double[n];
        new EVDEstimator().estimate(n, tRe, tIm, 0, phi);

        // phi[k] should equal truePhases[k] - truePhases[0] modulo 2pi
        for (int k = 0; k < n; k++) {
            final double expected = wrap(truePhases[k] - truePhases[0]);
            assertEquals("phi[" + k + "]", expected, wrap(phi[k]), 1.0e-6);
        }
        assertEquals("reference epoch phase", 0.0, phi[0], 1.0e-12);
    }

    @Test
    public void emi_recovers_phase_on_clean_rank_one_T() {
        final int n = 6;
        final double[] truePhases = {0.0, 0.2, -0.5, 1.1, -0.3, 0.7};

        final double[] ur = new double[n];
        final double[] ui = new double[n];
        final double inv = 1.0 / Math.sqrt(n);
        for (int k = 0; k < n; k++) {
            ur[k] = Math.cos(truePhases[k]) * inv;
            ui[k] = Math.sin(truePhases[k]) * inv;
        }
        final double[][] tRe = new double[n][n];
        final double[][] tIm = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                tRe[i][j] = n * (ur[i] * ur[j] + ui[i] * ui[j]);
                tIm[i][j] = n * (ui[i] * ur[j] - ur[i] * ui[j]);
            }
        }

        final double[] phi = new double[n];
        new EMIEstimator().estimate(n, tRe, tIm, 0, phi);

        for (int k = 0; k < n; k++) {
            final double expected = wrap(truePhases[k] - truePhases[0]);
            assertEquals("phi[" + k + "]", expected, wrap(phi[k]), 1.0e-3);
        }
        assertEquals(0.0, phi[0], 1.0e-12);
    }

    @Test
    public void temporal_coherence_one_on_rank_one_T_and_correct_phases() {
        final int n = 5;
        final double[] truePhases = {0.0, 0.4, 0.8, 1.2, 1.6};
        final double[] ur = new double[n];
        final double[] ui = new double[n];
        final double inv = 1.0 / Math.sqrt(n);
        for (int k = 0; k < n; k++) {
            ur[k] = Math.cos(truePhases[k]) * inv;
            ui[k] = Math.sin(truePhases[k]) * inv;
        }
        final double[][] tRe = new double[n][n];
        final double[][] tIm = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                tRe[i][j] = n * (ur[i] * ur[j] + ui[i] * ui[j]);
                tIm[i][j] = n * (ui[i] * ur[j] - ur[i] * ui[j]);
            }
        }
        final double[] phi = new double[n];
        for (int k = 0; k < n; k++) phi[k] = truePhases[k] - truePhases[0];

        final double gamma = TemporalCoherence.compute(n, tRe, tIm, phi);
        assertEquals(1.0, gamma, 1.0e-8);
    }

    @Test
    public void temporal_coherence_low_on_wrong_phases() {
        // Anti-correlated phase guesses on a rank-1 T must drive gamma well below 1.
        final int n = 8;
        final double[] truePhases = new double[n];
        for (int k = 0; k < n; k++) truePhases[k] = 0.3 * k;
        final double[] ur = new double[n];
        final double[] ui = new double[n];
        final double inv = 1.0 / Math.sqrt(n);
        for (int k = 0; k < n; k++) {
            ur[k] = Math.cos(truePhases[k]) * inv;
            ui[k] = Math.sin(truePhases[k]) * inv;
        }
        final double[][] tRe = new double[n][n];
        final double[][] tIm = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                tRe[i][j] = n * (ur[i] * ur[j] + ui[i] * ui[j]);
                tIm[i][j] = n * (ui[i] * ur[j] - ur[i] * ui[j]);
            }
        }
        // Wrong: use negated phases
        final double[] phi = new double[n];
        for (int k = 0; k < n; k++) phi[k] = -(truePhases[k] - truePhases[0]);
        final double gamma = TemporalCoherence.compute(n, tRe, tIm, phi);
        assertTrue("gamma should be small with anti-correlated phase: " + gamma, gamma < 0.6);
    }

    private static double wrap(double a) {
        while (a > Math.PI) a -= 2 * Math.PI;
        while (a <= -Math.PI) a += 2 * Math.PI;
        return a;
    }
}
