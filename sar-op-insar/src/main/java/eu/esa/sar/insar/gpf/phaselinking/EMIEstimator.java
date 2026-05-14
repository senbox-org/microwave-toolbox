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
 * EMI phase estimator (Ansari, De Zan, Bamler, IEEE TGRS 56(7) 2018).
 *
 * Lower bias than EVD at low coherence; same O(N^3) cost.
 *
 * Solve the smallest-eigenvalue eigenvector of the weighted matrix
 *
 *   W = |T_hat|^{-1} (elementwise Hadamard product) T_hat
 *
 * where |T_hat| is the matrix of moduli of T_hat. W is not Hermitian, so
 * we solve it via inverse-power iteration on T_hat itself: start from a
 * random unit vector v, set v &lt;- T_hat^{-1} (|T_hat| .* v_prev), normalise.
 *
 * This recovers the same fixed-point as the smallest-eigenvalue eigenvector
 * of W (Ansari 2018, eqs. 22-25). T_hat^{-1} is computed once via the
 * Hermitian eigen-decomposition (positive-(semi-)definite pseudo-inverse).
 */
public final class EMIEstimator implements PhaseEstimator {

    private static final int MAX_ITER = 30;
    private static final double TOL = 1.0e-8;
    private static final double EIG_FLOOR = 1.0e-9;

    @Override
    public void estimate(final int n, final double[][] tRe, final double[][] tIm,
                         final int refIdx, final double[] phi) {

        // Invert T_hat (pseudo-inverse via Hermitian eigendecomp).
        final double[][] invRe = new double[n][n];
        final double[][] invIm = new double[n][n];
        HermitianEigSolver.invert(n, tRe, tIm, invRe, invIm, EIG_FLOOR);

        // Precompute |T_hat|
        final double[][] absT = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                absT[i][j] = Math.sqrt(tRe[i][j] * tRe[i][j] + tIm[i][j] * tIm[i][j]);
            }
        }

        // Initial guess: dominant EVD eigenvector to get a warm start.
        final double[] vr = new double[n];
        final double[] vi = new double[n];
        {
            final double[][] evr = new double[n][n];
            final double[][] evi = new double[n][n];
            final double[] lambda = new double[n];
            HermitianEigSolver.decompose(n, tRe, tIm, evr, evi, lambda);
            for (int k = 0; k < n; k++) {
                vr[k] = evr[k][0];
                vi[k] = evi[k][0];
            }
        }

        final double[] wr = new double[n];
        final double[] wi = new double[n];

        for (int iter = 0; iter < MAX_ITER; iter++) {

            // y_i = sum_j |T|_ij * v_j   (real-weight scaling of complex v)
            final double[] yr = new double[n];
            final double[] yi = new double[n];
            for (int i = 0; i < n; i++) {
                double accR = 0.0, accI = 0.0;
                for (int j = 0; j < n; j++) {
                    accR += absT[i][j] * vr[j];
                    accI += absT[i][j] * vi[j];
                }
                yr[i] = accR;
                yi[i] = accI;
            }

            // w = T_hat^{-1} * y   (complex matrix-vector)
            for (int i = 0; i < n; i++) {
                double accR = 0.0, accI = 0.0;
                for (int j = 0; j < n; j++) {
                    // (invRe + i invIm)(yr + i yi)
                    accR += invRe[i][j] * yr[j] - invIm[i][j] * yi[j];
                    accI += invRe[i][j] * yi[j] + invIm[i][j] * yr[j];
                }
                wr[i] = accR;
                wi[i] = accI;
            }

            // Normalise w to unit length
            double norm2 = 0.0;
            for (int i = 0; i < n; i++) {
                norm2 += wr[i] * wr[i] + wi[i] * wi[i];
            }
            final double invNorm = (norm2 > 0.0) ? 1.0 / Math.sqrt(norm2) : 1.0;
            for (int i = 0; i < n; i++) {
                wr[i] *= invNorm;
                wi[i] *= invNorm;
            }

            // Phase-align to v to compare convergence (the eigenvector is defined up to a global phase).
            double dotR = 0.0, dotI = 0.0;
            for (int i = 0; i < n; i++) {
                dotR += vr[i] * wr[i] + vi[i] * wi[i];
                dotI += vr[i] * wi[i] - vi[i] * wr[i];
            }
            final double dotNorm = Math.sqrt(dotR * dotR + dotI * dotI);
            final double pr = (dotNorm > 0.0) ? dotR / dotNorm : 1.0;
            final double pi = (dotNorm > 0.0) ? -dotI / dotNorm : 0.0;
            for (int i = 0; i < n; i++) {
                final double r = wr[i] * pr - wi[i] * pi;
                final double im = wr[i] * pi + wi[i] * pr;
                wr[i] = r;
                wi[i] = im;
            }

            double diff = 0.0;
            for (int i = 0; i < n; i++) {
                final double dr = wr[i] - vr[i];
                final double di = wi[i] - vi[i];
                diff += dr * dr + di * di;
            }

            System.arraycopy(wr, 0, vr, 0, n);
            System.arraycopy(wi, 0, vi, 0, n);

            if (diff < TOL) break;
        }

        final double refArg = Math.atan2(vi[refIdx], vr[refIdx]);
        for (int k = 0; k < n; k++) {
            phi[k] = Math.atan2(vi[k], vr[k]) - refArg;
        }
        phi[refIdx] = 0.0;
    }
}
