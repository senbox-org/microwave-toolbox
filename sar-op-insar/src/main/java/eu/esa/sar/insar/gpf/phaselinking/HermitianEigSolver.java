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
 * Complex Hermitian eigenvalue decomposition by Jacobi rotation.
 *
 * Adapted from org.csa.rstb.polarimetric.gpf.decompositions.EigenDecomposition
 * to keep sar-op-insar free of a dependency on rstb-op-polarimetric-tools.
 *
 * Eigenvalues are returned in descending order; eigenvectors are returned as
 * columns of (Vr + i Vi) such that A * V[:,k] = lambda[k] * V[:,k].
 */
public final class HermitianEigSolver {

    private HermitianEigSolver() {
    }

    /**
     * Decompose an n x n Hermitian matrix A = Ar + i Ai.
     *
     * @param n           matrix dimension
     * @param ar          n x n real part of A (input, not modified)
     * @param ai          n x n imaginary part of A (input, not modified)
     * @param eigenVectRe n x n real part of eigenvector matrix (output, k-th eigenvector is column k)
     * @param eigenVectIm n x n imaginary part of eigenvector matrix (output)
     * @param eigenVal    length-n eigenvalues in descending order (output)
     */
    public static void decompose(final int n,
                                 final double[][] ar, final double[][] ai,
                                 final double[][] eigenVectRe, final double[][] eigenVectIm,
                                 final double[] eigenVal) {

        final double[][] a_r = new double[n][n];
        final double[][] a_i = new double[n][n];
        final double[][] vr = new double[n][n];
        final double[][] vi = new double[n][n];
        final double[] d = new double[n];
        final double[] z = new double[n];
        final double[] w = new double[2];
        final double[] s = new double[2];
        final double[] c = new double[2];
        final double[] titi = new double[2];
        final double[] gc = new double[2];
        final double[] hc = new double[2];
        double sm, tresh, x, toto, e, f, g, h, r, d1, d2;
        int p, q, ii, i, j, k;
        final int n2 = n * n;

        for (i = 0; i < n; i++) {
            for (j = 0; j < n; j++) {
                a_r[i][j] = ar[i][j];
                a_i[i][j] = ai[i][j];
                vr[i][j] = 0.0;
                vi[i][j] = 0.0;
            }
            vr[i][i] = 1.0;
            vi[i][i] = 0.0;

            d[i] = a_r[i][i];
            z[i] = 0.0;
        }

        final int iiMax = 1000 * n2;
        for (ii = 1; ii < iiMax; ii++) {

            sm = 0.0;
            for (p = 0; p < n - 1; p++) {
                for (q = p + 1; q < n; q++) {
                    sm += 2.0 * Math.sqrt(a_r[p][q] * a_r[p][q] + a_i[p][q] * a_i[p][q]);
                }
            }
            sm /= (n2 - n);

            if (sm < 1.E-16) {
                break;
            }

            tresh = 1.E-17;
            if (ii < 4) {
                tresh = (long) 0.2 * sm / n2;
            }

            x = -1.E-15;
            p = 0;
            q = 0;
            for (i = 0; i < n - 1; i++) {
                for (j = i + 1; j < n; j++) {
                    toto = Math.sqrt(a_r[i][j] * a_r[i][j] + a_i[i][j] * a_i[i][j]);
                    if (x < toto) {
                        x = toto;
                        p = i;
                        q = j;
                    }
                }
            }
            toto = Math.sqrt(a_r[p][q] * a_r[p][q] + a_i[p][q] * a_i[p][q]);
            if (toto > tresh) {
                e = d[p] - d[q];
                w[0] = a_r[p][q];
                w[1] = a_i[p][q];
                g = Math.sqrt(w[0] * w[0] + w[1] * w[1]);
                g = g * g;
                f = Math.sqrt(e * e + 4.0 * g);
                d1 = e + f;
                d2 = e - f;
                if (Math.abs(d2) > Math.abs(d1)) {
                    d1 = d2;
                }
                r = Math.abs(d1) / Math.sqrt(d1 * d1 + 4.0 * g);
                s[0] = r;
                s[1] = 0.0;
                titi[0] = 2.0 * r / d1;
                titi[1] = 0.0;
                c[0] = titi[0] * w[0] - titi[1] * w[1];
                c[1] = titi[0] * w[1] + titi[1] * w[0];
                r = Math.sqrt(s[0] * s[0] + s[1] * s[1]);
                r = r * r;
                h = (d1 / 2.0 + 2.0 * g / d1) * r;
                d[p] = d[p] - h;
                z[p] = z[p] - h;
                d[q] = d[q] + h;
                z[q] = z[q] + h;
                a_r[p][q] = 0.0;
                a_i[p][q] = 0.0;

                for (j = 0; j < p; j++) {
                    gc[0] = a_r[j][p];
                    gc[1] = a_i[j][p];
                    hc[0] = a_r[j][q];
                    hc[1] = a_i[j][q];
                    a_r[j][p] = c[0] * gc[0] - c[1] * gc[1] - s[0] * hc[0] - s[1] * hc[1];
                    a_i[j][p] = c[0] * gc[1] + c[1] * gc[0] - s[0] * hc[1] + s[1] * hc[0];
                    a_r[j][q] = s[0] * gc[0] - s[1] * gc[1] + c[0] * hc[0] + c[1] * hc[1];
                    a_i[j][q] = s[0] * gc[1] + s[1] * gc[0] + c[0] * hc[1] - c[1] * hc[0];
                }
                for (j = p + 1; j < q; j++) {
                    gc[0] = a_r[p][j];
                    gc[1] = a_i[p][j];
                    hc[0] = a_r[j][q];
                    hc[1] = a_i[j][q];
                    a_r[p][j] = c[0] * gc[0] + c[1] * gc[1] - s[0] * hc[0] - s[1] * hc[1];
                    a_i[p][j] = c[0] * gc[1] - c[1] * gc[0] + s[0] * hc[1] - s[1] * hc[0];
                    a_r[j][q] = s[0] * gc[0] + s[1] * gc[1] + c[0] * hc[0] + c[1] * hc[1];
                    a_i[j][q] = -s[0] * gc[1] + s[1] * gc[0] + c[0] * hc[1] - c[1] * hc[0];
                }
                for (j = q + 1; j < n; j++) {
                    gc[0] = a_r[p][j];
                    gc[1] = a_i[p][j];
                    hc[0] = a_r[q][j];
                    hc[1] = a_i[q][j];
                    a_r[p][j] = c[0] * gc[0] + c[1] * gc[1] - s[0] * hc[0] + s[1] * hc[1];
                    a_i[p][j] = c[0] * gc[1] - c[1] * gc[0] - s[0] * hc[1] - s[1] * hc[0];
                    a_r[q][j] = s[0] * gc[0] + s[1] * gc[1] + c[0] * hc[0] - c[1] * hc[1];
                    a_i[q][j] = s[0] * gc[1] - s[1] * gc[0] + c[0] * hc[1] + c[1] * hc[0];
                }
                for (j = 0; j < n; j++) {
                    gc[0] = vr[j][p];
                    gc[1] = vi[j][p];
                    hc[0] = vr[j][q];
                    hc[1] = vi[j][q];
                    vr[j][p] = c[0] * gc[0] - c[1] * gc[1] - s[0] * hc[0] - s[1] * hc[1];
                    vi[j][p] = c[0] * gc[1] + c[1] * gc[0] - s[0] * hc[1] + s[1] * hc[0];
                    vr[j][q] = s[0] * gc[0] - s[1] * gc[1] + c[0] * hc[0] + c[1] * hc[1];
                    vi[j][q] = s[0] * gc[1] + s[1] * gc[0] + c[0] * hc[1] - c[1] * hc[0];
                }
            }
        }

        // Recompute eigenvalues from Rayleigh quotients (numerically stable than diag of rotated A)
        for (k = 0; k < n; k++) {
            d[k] = 0.0;
            for (i = 0; i < n; i++) {
                for (j = 0; j < n; j++) {
                    d[k] += vr[i][k] * (ar[i][j] * vr[j][k] - ai[i][j] * vi[j][k]);
                    d[k] += vi[i][k] * (ar[i][j] * vi[j][k] + ai[i][j] * vr[j][k]);
                }
            }
        }

        // Sort eigenpairs in descending order of eigenvalue
        double tmp_r, tmp_i;
        for (i = 0; i < n; i++) {
            for (j = i + 1; j < n; j++) {
                if (d[j] > d[i]) {
                    x = d[i];
                    d[i] = d[j];
                    d[j] = x;
                    for (k = 0; k < n; k++) {
                        tmp_r = vr[k][i];
                        tmp_i = vi[k][i];
                        vr[k][i] = vr[k][j];
                        vi[k][i] = vi[k][j];
                        vr[k][j] = tmp_r;
                        vi[k][j] = tmp_i;
                    }
                }
            }
        }

        for (i = 0; i < n; i++) {
            eigenVal[i] = d[i];
            for (j = 0; j < n; j++) {
                eigenVectRe[i][j] = vr[i][j];
                eigenVectIm[i][j] = vi[i][j];
            }
        }
    }

    /**
     * Inverse of a Hermitian positive-(semi-)definite matrix via its eigen-decomposition:
     *   A^{-1} = sum_k (1/lambda_k) u_k u_k^H
     *
     * Eigenvalues below {@code eps} are treated as zero (pseudo-inverse).
     */
    public static void invert(final int n, final double[][] ar, final double[][] ai,
                              final double[][] invRe, final double[][] invIm,
                              final double eps) {
        final double[][] vr = new double[n][n];
        final double[][] vi = new double[n][n];
        final double[] lambda = new double[n];
        decompose(n, ar, ai, vr, vi, lambda);

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                invRe[i][j] = 0.0;
                invIm[i][j] = 0.0;
            }
        }

        for (int k = 0; k < n; k++) {
            if (Math.abs(lambda[k]) < eps) continue;
            final double w = 1.0 / lambda[k];
            // outer product u_k u_k^H = (vr+i vi)_k (vr-i vi)_k^T
            for (int i = 0; i < n; i++) {
                final double ur = vr[i][k];
                final double ui = vi[i][k];
                for (int j = 0; j < n; j++) {
                    final double vrj = vr[j][k];
                    final double vij = vi[j][k];
                    // (ur + i ui) * (vrj - i vij) = ur*vrj + ui*vij + i(ui*vrj - ur*vij)
                    invRe[i][j] += w * (ur * vrj + ui * vij);
                    invIm[i][j] += w * (ui * vrj - ur * vij);
                }
            }
        }
    }
}
