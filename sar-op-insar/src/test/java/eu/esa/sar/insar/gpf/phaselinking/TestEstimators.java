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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestEstimators {

    // ---------------------------------------------------------------------
    // Deterministic recovery on exact (noise-free) coherence matrices
    // ---------------------------------------------------------------------

    @Test
    public void evd_recovers_phase_on_clean_rank_one_T() {
        final int n = 8;
        final double[] truePhases = new double[n];
        for (int k = 0; k < n; k++) truePhases[k] = 0.3 * k;  // ramp

        final double[][][] T = rankOneT(n, truePhases);
        final double[] phi = new double[n];
        new EVDEstimator().estimate(n, T[0], T[1], 0, phi);

        for (int k = 0; k < n; k++) {
            final double expected = wrap(truePhases[k] - truePhases[0]);
            assertEquals("phi[" + k + "]", expected, wrap(phi[k]), 1.0e-6);
        }
        assertEquals("reference epoch phase", 0.0, phi[0], 1.0e-12);
    }

    /**
     * Spec test plan 8.2: on the exact coherence matrix
     * {@code T = gamma * u u^H + (1-gamma) I} the dominant eigenvector is u for any gamma > 0,
     * so EVD must recover the phase ramp essentially exactly at every coherence level.
     */
    @Test
    public void evd_recovers_phase_at_varying_coherence() {
        final int n = 10;
        final double[] truePhases = new double[n];
        for (int k = 0; k < n; k++) truePhases[k] = 0.25 * (k - n / 2);
        final int ref = n / 2;

        for (final double gamma : new double[]{0.5, 0.7, 0.9}) {
            final double[][][] T = constCoherenceT(n, truePhases, gamma);
            final double[] phi = new double[n];
            new EVDEstimator().estimate(n, T[0], T[1], ref, phi);
            for (int k = 0; k < n; k++) {
                final double expected = wrap(truePhases[k] - truePhases[ref]);
                assertEquals("gamma=" + gamma + " phi[" + k + "]", expected, wrap(phi[k]), 1.0e-6);
            }
        }
    }

    /**
     * EMI must likewise recover the phase ramp exactly on the exact constant-coherence matrix
     * (which is full rank for gamma &lt; 1, so Gamma^{-1} exists). This is the canonical
     * Ansari/De Zan/Bamler estimator: smallest-eigenvalue eigenvector of Gamma^{-1} (Hadamard) T.
     */
    @Test
    public void emi_recovers_phase_at_varying_coherence() {
        final int n = 10;
        final double[] truePhases = {0.0, 0.2, -0.5, 1.1, -0.3, 0.7, -0.9, 0.4, 1.3, -0.1};
        final int ref = n / 2;

        for (final double gamma : new double[]{0.5, 0.7, 0.9}) {
            final double[][][] T = constCoherenceT(n, truePhases, gamma);
            final double[] phi = new double[n];
            new EMIEstimator().estimate(n, T[0], T[1], ref, phi);
            for (int k = 0; k < n; k++) {
                final double expected = wrap(truePhases[k] - truePhases[ref]);
                assertEquals("gamma=" + gamma + " phi[" + k + "]", expected, wrap(phi[k]), 1.0e-6);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Monte-Carlo behaviour on sampled (noisy) coherence matrices
    // ---------------------------------------------------------------------

    /**
     * Spec test plan 8.2 (statistical part): with finite looks the EVD phase error has the
     * expected variance behaviour - it shrinks as the number of looks grows and as coherence
     * rises. (The spec's closed form 1/(2 N sqrt(gamma)) is not used as a hard bound: it carries
     * no dependence on the number of looks, whereas the actual estimator variance scales as ~1/L.)
     */
    @Test
    public void evd_phase_error_shrinks_with_more_looks() {
        final int n = 20;
        final double[] truePhases = new double[n];
        for (int k = 0; k < n; k++) truePhases[k] = 0.1 * (k - n / 2);
        final int ref = n / 2;
        final double gamma = 0.7;
        final double[][][] C = constCoherenceT(n, truePhases, gamma);

        final double rmsFewLooks = monteCarloRms(new EVDEstimator(), n, C, truePhases, ref, 64, 150, 11L);
        final double rmsManyLooks = monteCarloRms(new EVDEstimator(), n, C, truePhases, ref, 256, 150, 11L);

        assertTrue("RMS should drop with more looks: 64->" + rmsFewLooks + " 256->" + rmsManyLooks,
                rmsManyLooks < 0.75 * rmsFewLooks);
        assertTrue("RMS at 256 looks should be small: " + rmsManyLooks, rmsManyLooks < 0.1);
    }

    /**
     * Spec claim / motivation for shipping EMI: under decaying temporal coherence (long baselines
     * almost decorrelated) the inverse-magnitude weighting of EMI down-weights the noisiest pairs,
     * giving a lower phase RMSE than EVD's equal weighting. (On a flat constant-coherence matrix
     * the two are equivalent; the advantage only appears when coherence varies across pairs.)
     */
    @Test
    public void emi_beats_evd_at_low_coherence() {
        final int n = 18;
        final double[] truePhases = new double[n];
        for (int k = 0; k < n; k++) truePhases[k] = 0.05 * (k - n / 2);   // gentle, no wrap
        final int ref = n / 2;
        final double rho = 0.7;     // coherence rho^|i-j|: near-decorrelated at long baselines
        final double[][][] C = decayCoherenceT(n, truePhases, rho);

        final int looks = 150, trials = 150;
        final double rmsEvd = monteCarloRms(new EVDEstimator(), n, C, truePhases, ref, looks, trials, 23L);
        final double rmsEmi = monteCarloRms(new EMIEstimator(), n, C, truePhases, ref, looks, trials, 23L);

        assertTrue("EMI RMSE (" + rmsEmi + ") should be below EVD RMSE (" + rmsEvd + ") at low coherence",
                rmsEmi < rmsEvd);
    }

    /**
     * Spec test plan 8.2 (near-optimality): MLE-style phase linking should sit close to the
     * Cramer-Rao lower bound. On a constant-coherence stack both EVD and EMI track the CRB: the
     * empirical RMS lands just under the unbiased bound (EVD/EMI are mildly biased toward the
     * reference, which trims variance) and well within a small factor of it - it can neither fall
     * far below the bound nor blow up above it. The bound itself is the Guarnieri-Tebaldini
     * Fisher information F = 2L (Gamma o Gamma^{-1} - I), reduced by removing the reference epoch.
     */
    @Test
    public void estimators_track_cramer_rao_bound() {
        final int n = 12;
        final double[] truePhases = new double[n];
        for (int k = 0; k < n; k++) truePhases[k] = 0.1 * (k - n / 2);
        final int ref = n / 2;
        final int looks = 200, trials = 300;

        for (final double gamma : new double[]{0.5, 0.7, 0.9}) {
            final double[][][] C = constCoherenceT(n, truePhases, gamma);
            final double[][] gMag = magnitude(n, C[0], C[1]);
            final double crb = crbPooled(crbStd(n, gMag, looks, ref), ref);

            for (final PhaseEstimator est : new PhaseEstimator[]{new EVDEstimator(), new EMIEstimator()}) {
                final double rms = monteCarloRms(est, n, C, truePhases, ref, looks, trials, 101L);
                final double ratio = rms / crb;
                assertTrue(est.getClass().getSimpleName() + " gamma=" + gamma + " RMS=" + rms +
                                " CRB=" + crb + " ratio=" + ratio + " should be near-optimal [0.75, 1.6]",
                        ratio >= 0.75 && ratio <= 1.6);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Temporal (goodness-of-fit) coherence
    // ---------------------------------------------------------------------

    @Test
    public void temporal_coherence_one_on_rank_one_T_and_correct_phases() {
        final int n = 5;
        final double[] truePhases = {0.0, 0.4, 0.8, 1.2, 1.6};
        final double[][][] T = rankOneT(n, truePhases);
        final double[] phi = new double[n];
        for (int k = 0; k < n; k++) phi[k] = truePhases[k] - truePhases[0];

        final double gamma = TemporalCoherence.compute(n, T[0], T[1], phi);
        assertEquals(1.0, gamma, 1.0e-8);
    }

    @Test
    public void temporal_coherence_low_on_wrong_phases() {
        // Anti-correlated phase guesses on a rank-1 T must drive gamma well below 1.
        final int n = 8;
        final double[] truePhases = new double[n];
        for (int k = 0; k < n; k++) truePhases[k] = 0.3 * k;
        final double[][][] T = rankOneT(n, truePhases);
        final double[] phi = new double[n];
        for (int k = 0; k < n; k++) phi[k] = -(truePhases[k] - truePhases[0]);
        final double gamma = TemporalCoherence.compute(n, T[0], T[1], phi);
        assertTrue("gamma should be small with anti-correlated phase: " + gamma, gamma < 0.6);
    }

    /**
     * Spec test plan 8.3: for pure noise (no temporal correlation) the goodness-of-fit coherence
     * is far below the value of a real DS (~1). EVD over-fits the dominant eigenvector so it does
     * not collapse all the way to 0, but it stays comfortably below the 0.6 DS-acceptance gate.
     */
    @Test
    public void temporal_coherence_small_for_random_noise() {
        final int n = 30;
        final double[] zeroPhase = new double[n];                 // C = I: no common signal
        final double[][][] C = constCoherenceT(n, zeroPhase, 0.0);

        final Random rng = new Random(31L);
        final int trials = 50;
        double sum = 0.0, max = 0.0;
        for (int t = 0; t < trials; t++) {
            final double[][] tRe = new double[n][n];
            final double[][] tIm = new double[n][n];
            sampleCoherence(n, C[0], C[1], 30, rng, tRe, tIm);
            final double[] phi = new double[n];
            new EVDEstimator().estimate(n, tRe, tIm, n / 2, phi);
            final double g = TemporalCoherence.compute(n, tRe, tIm, phi);
            sum += g;
            max = Math.max(max, g);
        }
        final double mean = sum / trials;
        assertTrue("mean noise gamma_T should be well below the DS gate: " + mean, mean < 0.55);
        assertTrue("even the worst noise gamma_T should stay below 1: " + max, max < 0.7);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** Clean rank-1 coherence matrix T = n * (u u^H) with u = exp(j phi) / sqrt(n). */
    private static double[][][] rankOneT(final int n, final double[] phases) {
        final double[] ur = new double[n];
        final double[] ui = new double[n];
        final double inv = 1.0 / Math.sqrt(n);
        for (int k = 0; k < n; k++) {
            ur[k] = Math.cos(phases[k]) * inv;
            ui[k] = Math.sin(phases[k]) * inv;
        }
        final double[][] tRe = new double[n][n];
        final double[][] tIm = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                tRe[i][j] = n * (ur[i] * ur[j] + ui[i] * ui[j]);
                tIm[i][j] = n * (ui[i] * ur[j] - ur[i] * ui[j]);
            }
        }
        return new double[][][]{tRe, tIm};
    }

    /**
     * Constant-coherence model T = gamma * u u^H + (1-gamma) I with unit diagonal:
     * off-diagonal magnitude gamma, phase phi_i - phi_j. Valid PSD coherence matrix for
     * gamma in [0, 1). gamma = 0 yields the identity (pure noise).
     */
    private static double[][][] constCoherenceT(final int n, final double[] phases, final double gamma) {
        final double[][] tRe = new double[n][n];
        final double[][] tIm = new double[n][n];
        for (int i = 0; i < n; i++) {
            tRe[i][i] = 1.0;
            for (int j = i + 1; j < n; j++) {
                final double d = phases[i] - phases[j];
                tRe[i][j] = gamma * Math.cos(d);
                tIm[i][j] = gamma * Math.sin(d);
                tRe[j][i] = tRe[i][j];
                tIm[j][i] = -tIm[i][j];
            }
        }
        return new double[][][]{tRe, tIm};
    }

    /**
     * Decaying-coherence model T_ij = rho^|i-j| * exp(j (phi_i - phi_j)), unit diagonal.
     * This is a diagonal-unitary congruence of the (PSD) AR(1) correlation matrix, hence a valid
     * PSD coherence matrix, with off-diagonal magnitude falling off with temporal baseline.
     */
    private static double[][][] decayCoherenceT(final int n, final double[] phases, final double rho) {
        final double[][] tRe = new double[n][n];
        final double[][] tIm = new double[n][n];
        for (int i = 0; i < n; i++) {
            tRe[i][i] = 1.0;
            for (int j = i + 1; j < n; j++) {
                final double mag = Math.pow(rho, Math.abs(i - j));
                final double d = phases[i] - phases[j];
                tRe[i][j] = mag * Math.cos(d);
                tIm[i][j] = mag * Math.sin(d);
                tRe[j][i] = tRe[i][j];
                tIm[j][i] = -tIm[i][j];
            }
        }
        return new double[][][]{tRe, tIm};
    }

    /**
     * Draw {@code looks} i.i.d. complex-circular-Gaussian samples with covariance C (= the supplied
     * unit-diagonal coherence matrix) and form their sample coherence matrix via the production
     * {@link CovarianceMatrix}. Sampling uses C = V diag(lambda) V^H -> s = V diag(sqrt(lambda)) z.
     */
    private static void sampleCoherence(final int n, final double[][] cRe, final double[][] cIm,
                                        final int looks, final Random rng,
                                        final double[][] tReOut, final double[][] tImOut) {
        final double[][] vr = new double[n][n];
        final double[][] vi = new double[n][n];
        final double[] lambda = new double[n];
        HermitianEigSolver.decompose(n, cRe, cIm, vr, vi, lambda);
        final double[] sq = new double[n];
        for (int k = 0; k < n; k++) sq[k] = Math.sqrt(Math.max(lambda[k], 0.0));

        final double invSqrt2 = 1.0 / Math.sqrt(2.0);
        final CovarianceMatrix acc = new CovarianceMatrix(n);
        final double[] sR = new double[n];
        final double[] sI = new double[n];
        final double[] zr = new double[n];
        final double[] zi = new double[n];
        for (int l = 0; l < looks; l++) {
            for (int k = 0; k < n; k++) {
                zr[k] = rng.nextGaussian() * invSqrt2;
                zi[k] = rng.nextGaussian() * invSqrt2;
            }
            // s_i = sum_k V[i][k] * sqrt(lambda_k) * z_k
            for (int i = 0; i < n; i++) {
                double accR = 0.0, accI = 0.0;
                for (int k = 0; k < n; k++) {
                    final double ar = vr[i][k] * sq[k];
                    final double ai = vi[i][k] * sq[k];
                    accR += ar * zr[k] - ai * zi[k];
                    accI += ar * zi[k] + ai * zr[k];
                }
                sR[i] = accR;
                sI[i] = accI;
            }
            acc.accumulate(sR, sI);
        }
        acc.finalizeT(looks, tReOut, tImOut);
    }

    /** Mean per-epoch (non-reference) phase RMSE of an estimator over many sampled covariances. */
    private static double monteCarloRms(final PhaseEstimator estimator, final int n, final double[][][] C,
                                        final double[] truePhases, final int ref,
                                        final int looks, final int trials, final long seed) {
        final Random rng = new Random(seed);
        final double[][] tRe = new double[n][n];
        final double[][] tIm = new double[n][n];
        final double[] phi = new double[n];
        double sumSq = 0.0;
        long count = 0;
        for (int t = 0; t < trials; t++) {
            sampleCoherence(n, C[0], C[1], looks, rng, tRe, tIm);
            estimator.estimate(n, tRe, tIm, ref, phi);
            for (int k = 0; k < n; k++) {
                if (k == ref) continue;
                final double err = wrap(phi[k] - wrap(truePhases[k] - truePhases[ref]));
                sumSq += err * err;
                count++;
            }
        }
        return Math.sqrt(sumSq / count);
    }

    /** Elementwise magnitude |T| of a complex matrix given as separate real/imag parts. */
    private static double[][] magnitude(final int n, final double[][] re, final double[][] im) {
        final double[][] g = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                g[i][j] = Math.sqrt(re[i][j] * re[i][j] + im[i][j] * im[i][j]);
            }
        }
        return g;
    }

    /**
     * Per-epoch Cramer-Rao lower bound on the phase relative to {@code ref}, in radians, for a
     * coherence-magnitude matrix {@code gMag} estimated from {@code looks} samples. Fisher
     * information F = 2L (Gamma o Gamma^{-1} - I) is singular (global-phase null space), so the CRB
     * on the phase differences comes from inverting the (n-1)x(n-1) submatrix with the reference
     * row/column removed. Returns NaN at the reference index.
     */
    private static double[] crbStd(final int n, final double[][] gMag, final int looks, final int ref) {
        final double[][] zero = new double[n][n];
        final double[][] gInvRe = new double[n][n];
        final double[][] gInvIm = new double[n][n];
        HermitianEigSolver.invert(n, gMag, zero, gInvRe, gInvIm, 1.0e-12);

        final double[][] f = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                f[i][j] = 2.0 * looks * (gMag[i][j] * gInvRe[i][j] - (i == j ? 1.0 : 0.0));
            }
        }

        // Reduced FIM: drop the reference row/column, then invert (it is positive definite).
        final int m = n - 1;
        final int[] keep = new int[m];
        for (int i = 0, p = 0; i < n; i++) if (i != ref) keep[p++] = i;
        final double[][] fSub = new double[m][m];
        for (int a = 0; a < m; a++) {
            for (int b = 0; b < m; b++) {
                fSub[a][b] = f[keep[a]][keep[b]];
            }
        }
        final double[][] zeroM = new double[m][m];
        final double[][] covRe = new double[m][m];
        final double[][] covIm = new double[m][m];
        HermitianEigSolver.invert(m, fSub, zeroM, covRe, covIm, 1.0e-12);

        final double[] std = new double[n];
        java.util.Arrays.fill(std, Double.NaN);
        for (int a = 0; a < m; a++) std[keep[a]] = Math.sqrt(Math.max(covRe[a][a], 0.0));
        return std;
    }

    /** RMS over non-reference epochs of the per-epoch CRB std (matches monteCarloRms aggregation). */
    private static double crbPooled(final double[] crb, final int ref) {
        double sumSq = 0.0;
        int count = 0;
        for (int k = 0; k < crb.length; k++) {
            if (k == ref || Double.isNaN(crb[k])) continue;
            sumSq += crb[k] * crb[k];
            count++;
        }
        return Math.sqrt(sumSq / count);
    }

    private static double wrap(double a) {
        while (a > Math.PI) a -= 2 * Math.PI;
        while (a <= -Math.PI) a += 2 * Math.PI;
        return a;
    }
}
