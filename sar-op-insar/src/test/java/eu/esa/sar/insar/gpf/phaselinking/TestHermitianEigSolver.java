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

public class TestHermitianEigSolver {

    @Test
    public void diagonal_real_matrix_eigenvalues_recovered_in_descending_order() {
        final int n = 4;
        final double[][] ar = new double[n][n];
        final double[][] ai = new double[n][n];
        final double[] expected = {2.0, 5.0, 1.0, 3.0};
        for (int i = 0; i < n; i++) ar[i][i] = expected[i];

        final double[][] vr = new double[n][n];
        final double[][] vi = new double[n][n];
        final double[] lambda = new double[n];

        HermitianEigSolver.decompose(n, ar, ai, vr, vi, lambda);

        final double[] sortedDesc = expected.clone();
        java.util.Arrays.sort(sortedDesc);
        for (int i = 0; i < n; i++) {
            assertEquals("eigenvalue " + i + " (sorted descending)", sortedDesc[n - 1 - i], lambda[i], 1.0e-9);
        }
    }

    @Test
    public void rank_one_hermitian_eigenvector_recovered() {
        // Build T = u u^H for u = (1, exp(i*pi/4), exp(i*pi/2)) / sqrt(3)
        final int n = 3;
        final double[] ur1 = {1.0, Math.cos(Math.PI / 4), 0.0};
        final double[] ui1 = {0.0, Math.sin(Math.PI / 4), 1.0};
        final double norm = Math.sqrt(3.0);
        for (int i = 0; i < n; i++) {
            ur1[i] /= norm;
            ui1[i] /= norm;
        }
        final double[][] tRe = new double[n][n];
        final double[][] tIm = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                // u_i * conj(u_j)
                tRe[i][j] = ur1[i] * ur1[j] + ui1[i] * ui1[j];
                tIm[i][j] = ui1[i] * ur1[j] - ur1[i] * ui1[j];
            }
        }

        final double[][] vr = new double[n][n];
        final double[][] vi = new double[n][n];
        final double[] lambda = new double[n];
        HermitianEigSolver.decompose(n, tRe, tIm, vr, vi, lambda);

        // Dominant eigenvalue should be 1, others near 0
        assertEquals(1.0, lambda[0], 1.0e-8);
        assertTrue(Math.abs(lambda[1]) < 1.0e-8);
        assertTrue(Math.abs(lambda[2]) < 1.0e-8);

        // Dominant eigenvector should match u up to a global phase. Check that
        // the arg() differences (u_k - u_0) match those of v[k][0] - v[0][0].
        final double[] uArg = new double[n];
        final double[] vArg = new double[n];
        for (int k = 0; k < n; k++) {
            uArg[k] = Math.atan2(ui1[k], ur1[k]);
            vArg[k] = Math.atan2(vi[k][0], vr[k][0]);
        }
        for (int k = 1; k < n; k++) {
            final double du = wrap(uArg[k] - uArg[0]);
            final double dv = wrap(vArg[k] - vArg[0]);
            assertEquals("phase difference for k=" + k, du, dv, 1.0e-6);
        }
    }

    @Test
    public void invert_round_trips_to_identity() {
        // Construct a 3x3 positive-definite Hermitian
        final int n = 3;
        final double[][] tRe = {{2.0, 0.5, 0.1},
                {0.5, 1.5, 0.2},
                {0.1, 0.2, 1.0}};
        final double[][] tIm = {{0.0, 0.3, 0.05},
                {-0.3, 0.0, 0.1},
                {-0.05, -0.1, 0.0}};

        final double[][] invRe = new double[n][n];
        final double[][] invIm = new double[n][n];
        HermitianEigSolver.invert(n, tRe, tIm, invRe, invIm, 1.0e-12);

        // T * T^{-1} should be ~ I
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double re = 0.0, im = 0.0;
                for (int k = 0; k < n; k++) {
                    re += tRe[i][k] * invRe[k][j] - tIm[i][k] * invIm[k][j];
                    im += tRe[i][k] * invIm[k][j] + tIm[i][k] * invRe[k][j];
                }
                final double expected = (i == j) ? 1.0 : 0.0;
                assertEquals("T*Tinv real (" + i + "," + j + ")", expected, re, 1.0e-6);
                assertEquals("T*Tinv imag (" + i + "," + j + ")", 0.0, im, 1.0e-6);
            }
        }
    }

    private static double wrap(double a) {
        while (a > Math.PI) a -= 2 * Math.PI;
        while (a <= -Math.PI) a += 2 * Math.PI;
        return a;
    }
}
