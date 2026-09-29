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

/**
 * Pepe-Lanari "goodness-of-fit" temporal coherence:
 *
 *   gamma_T = (2 / (N (N-1))) * | sum_{i &lt; j}
 *               exp(j (arg(T_hat[i,j]) - (phi_i - phi_j))) |
 *
 * Range: 0 (random) .. 1 (perfect rank-1 T_hat).
 *
 * The signal-flow convention here matches the sample-covariance build
 * order: T_hat[i,j] = E[s_i * conj(s_j)], so arg(T_hat[i,j]) = phi_i - phi_j
 * for a noise-free rank-1 model. Subtracting the model prediction
 * (phi_i - phi_j) zeroes the residual when the estimator is correct.
 *
 * Note: the Phase-Linking-Op-Spec.md draft writes the residual with
 * `(phi_j - phi_i)` and drops the `arg()` around T_hat; both are
 * corrected here.
 */
public final class TemporalCoherence {

    private TemporalCoherence() {
    }

    /**
     * @param n   stack size
     * @param tRe n x n real part of the Hermitian coherence matrix T_hat
     * @param tIm n x n imaginary part of T_hat
     * @param phi length-n estimated per-epoch phases
     * @return temporal coherence in [0, 1]
     */
    public static double compute(final int n,
                                 final double[][] tRe, final double[][] tIm,
                                 final double[] phi) {

        if (n < 2) return 1.0;

        double sumRe = 0.0;
        double sumIm = 0.0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                final double argTij = Math.atan2(tIm[i][j], tRe[i][j]);
                final double residual = argTij - (phi[i] - phi[j]);
                sumRe += Math.cos(residual);
                sumIm += Math.sin(residual);
            }
        }
        final double pairs = 0.5 * n * (n - 1);
        return Math.sqrt(sumRe * sumRe + sumIm * sumIm) / pairs;
    }
}
