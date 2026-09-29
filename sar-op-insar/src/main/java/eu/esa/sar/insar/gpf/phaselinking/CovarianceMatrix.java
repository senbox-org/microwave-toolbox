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
 * Per-pixel sample covariance accumulator and normaliser to the coherence
 * matrix T_hat used by the phase-linking estimators.
 *
 *   C_hat[i,j] = (1/N_shp) * sum_{q in Omega(p)} s_i(q) * conj(s_j(q))
 *   T_hat[i,j] = C_hat[i,j] / sqrt(C_hat[i,i] * C_hat[j,j])
 *
 * Real / imaginary components are kept in separate 2-D arrays so the same
 * memory can be handed straight to {@link HermitianEigSolver}.
 */
public final class CovarianceMatrix {

    private final int n;
    private final double[][] cRe;
    private final double[][] cIm;

    public CovarianceMatrix(final int n) {
        this.n = n;
        this.cRe = new double[n][n];
        this.cIm = new double[n][n];
    }

    public int size() {
        return n;
    }

    public void reset() {
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                cRe[i][j] = 0.0;
                cIm[i][j] = 0.0;
            }
        }
    }

    /**
     * Accumulate one SHP sample. {@code slcRe} / {@code slcIm} are length-N
     * vectors giving the complex SLC value at the sample for each epoch.
     */
    public void accumulate(final double[] slcRe, final double[] slcIm) {
        // C += s s^H
        for (int i = 0; i < n; i++) {
            final double sir = slcRe[i];
            final double sii = slcIm[i];
            // diagonal: s_i * conj(s_i) = |s_i|^2
            cRe[i][i] += sir * sir + sii * sii;
            // off-diagonal: only fill upper triangle, mirror at finalize()
            for (int j = i + 1; j < n; j++) {
                final double sjr = slcRe[j];
                final double sji = slcIm[j];
                // s_i * conj(s_j) = (sir + i sii)(sjr - i sji)
                //                 = (sir*sjr + sii*sji) + i (sii*sjr - sir*sji)
                cRe[i][j] += sir * sjr + sii * sji;
                cIm[i][j] += sii * sjr - sir * sji;
            }
        }
    }

    /**
     * Divide by sample count and normalise to the coherence matrix T_hat.
     * Writes the lower triangle by Hermitian symmetry.
     *
     * @param nSamples number of SHP samples accumulated (must be >= 1)
     * @param tRe      n x n output real part of T_hat (Hermitian)
     * @param tIm      n x n output imaginary part of T_hat (Hermitian)
     */
    public void finalizeT(final int nSamples, final double[][] tRe, final double[][] tIm) {
        finalizeT(nSamples, tRe, tIm, false);
    }

    /**
     * As {@link #finalizeT(int, double[][], double[][])} but optionally removes the positive bias of
     * the sample coherence magnitude. The sample coherence |T_hat_ij| from L looks is biased high
     * (E[|gamma_hat|^2] ~ gamma^2 + (1-gamma^2)/L; the bias is severe at low coherence / few looks).
     * The first-order magnitude-squared-coherence correction subtracts the 1/L noise floor and
     * rescales:
     *
     *   |gamma|^2_corrected = max(0, (L * |gamma_hat|^2 - 1) / (L - 1))
     *
     * which is first-order unbiased for gamma^2. Only the off-diagonal magnitudes are shrunk; the
     * phase arg(T_hat) (which carries the interferometric signal) is preserved, and the diagonal
     * stays 1.
     *
     * Caveats: (1) per-element magnitude shrinkage is not guaranteed to preserve positive-
     * semidefiniteness - the estimators only require a Hermitian matrix, so this is acceptable, but
     * PSD-preserving debiasing needs a regularized/tapered approach. (2) The correction is
     * beneficial for EVD (it down-weights noisy low-coherence pairs) but can DEGRADE EMI at low
     * coherence / few looks, because EMI inverts the magnitude matrix and the correction adds
     * estimation variance there. Recommended with the EVD estimator.
     *
     * @param biasCorrect apply the magnitude bias correction when true
     */
    public void finalizeT(final int nSamples, final double[][] tRe, final double[][] tIm,
                          final boolean biasCorrect) {
        final double inv = 1.0 / nSamples;
        // diagonal magnitudes for normalisation
        final double[] diag = new double[n];
        for (int i = 0; i < n; i++) {
            diag[i] = Math.sqrt(cRe[i][i] * inv);
            tRe[i][i] = 1.0;
            tIm[i][i] = 0.0;
        }
        final boolean correct = biasCorrect && nSamples > 1;
        final double L = nSamples;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                final double dij = diag[i] * diag[j];
                double re, im;
                if (dij <= 0.0) {
                    re = 0.0;
                    im = 0.0;
                } else {
                    re = (cRe[i][j] * inv) / dij;
                    im = (cIm[i][j] * inv) / dij;
                    if (correct) {
                        final double m2 = re * re + im * im;
                        final double m2c = (L * m2 - 1.0) / (L - 1.0);
                        final double scale = (m2 > 0.0 && m2c > 0.0) ? Math.sqrt(m2c / m2) : 0.0;
                        re *= scale;
                        im *= scale;
                    }
                }
                tRe[i][j] = re;
                tIm[i][j] = im;
                tRe[j][i] = re;
                tIm[j][i] = -im;
            }
        }
    }
}
