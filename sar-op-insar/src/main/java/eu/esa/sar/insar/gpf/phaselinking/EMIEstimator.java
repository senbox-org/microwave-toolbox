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
 * Lower bias and variance than EVD at low coherence; same O(N^3) cost.
 *
 * The EMI estimate is the eigenvector of the SMALLEST eigenvalue of
 *
 *   M = Gamma^{-1} (Hadamard product) T_hat ,   Gamma = |T_hat|
 *
 * where {@code Gamma^{-1}} is the (real, symmetric) MATRIX inverse of the
 * coherence-magnitude matrix and the Hadamard product is taken with the
 * complex sample coherence T_hat. M is Hermitian, so its eigen-pairs come
 * straight from {@link HermitianEigSolver#decompose} (eigenvalues descending,
 * so the EMI eigenvector is the last column). The inverse-magnitude weighting
 * down-weights low-coherence (long-baseline) pairs, which is exactly where
 * EVD's equal weighting of noisy phases hurts.
 *
 * Gamma is positive-(semi-)definite; near-singular cases (e.g. a degenerate
 * rank-1 T_hat) fall back to a pseudo-inverse via the eigen-floor.
 */
public final class EMIEstimator implements PhaseEstimator {

    private static final double EIG_FLOOR = 1.0e-9;

    @Override
    public void estimate(final int n, final double[][] tRe, final double[][] tIm,
                         final int refIdx, final double[] phi) {

        // Gamma = |T_hat| (real, symmetric, unit diagonal).
        final double[][] gammaRe = new double[n][n];
        final double[][] gammaIm = new double[n][n];   // identically zero (Gamma is real)
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                gammaRe[i][j] = Math.sqrt(tRe[i][j] * tRe[i][j] + tIm[i][j] * tIm[i][j]);
            }
        }

        // Gamma^{-1} (real, symmetric; pseudo-inverse if near-singular).
        final double[][] invRe = new double[n][n];
        final double[][] invIm = new double[n][n];
        HermitianEigSolver.invert(n, gammaRe, gammaIm, invRe, invIm, EIG_FLOOR);

        // M = Gamma^{-1} (Hadamard) T_hat. Gamma^{-1} is real, so M_ij = invRe_ij * T_hat_ij.
        final double[][] mRe = new double[n][n];
        final double[][] mIm = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                mRe[i][j] = invRe[i][j] * tRe[i][j];
                mIm[i][j] = invRe[i][j] * tIm[i][j];
            }
        }

        // Smallest-eigenvalue eigenvector of the Hermitian M (last column, since
        // HermitianEigSolver returns eigenvalues in descending order).
        final double[][] vr = new double[n][n];
        final double[][] vi = new double[n][n];
        final double[] lambda = new double[n];
        HermitianEigSolver.decompose(n, mRe, mIm, vr, vi, lambda);
        final int c = n - 1;

        final double refArg = Math.atan2(vi[refIdx][c], vr[refIdx][c]);
        for (int k = 0; k < n; k++) {
            phi[k] = Math.atan2(vi[k][c], vr[k][c]) - refArg;
        }
        phi[refIdx] = 0.0;
    }
}
