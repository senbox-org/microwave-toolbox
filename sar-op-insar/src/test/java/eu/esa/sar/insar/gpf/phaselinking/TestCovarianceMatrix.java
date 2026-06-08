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

public class TestCovarianceMatrix {

    @Test
    public void single_sample_gives_unit_coherence_matrix() {
        final int n = 4;
        final CovarianceMatrix C = new CovarianceMatrix(n);
        final double[] sR = {1.0, 0.5, -0.3, 0.8};
        final double[] sI = {0.0, 0.4, 0.7, -0.2};
        C.accumulate(sR, sI);

        final double[][] tRe = new double[n][n];
        final double[][] tIm = new double[n][n];
        C.finalizeT(1, tRe, tIm);

        for (int i = 0; i < n; i++) {
            assertEquals(1.0, tRe[i][i], 1.0e-12);
            assertEquals(0.0, tIm[i][i], 1.0e-12);
        }
        // Off-diagonals have magnitude 1 (rank-1 single sample)
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                final double mag = Math.hypot(tRe[i][j], tIm[i][j]);
                assertEquals("|T[" + i + "," + j + "]|", 1.0, mag, 1.0e-9);
            }
        }
    }

    @Test
    public void hermitian_symmetry_holds() {
        final int n = 5;
        final CovarianceMatrix C = new CovarianceMatrix(n);
        final java.util.Random rng = new java.util.Random(7);
        for (int s = 0; s < 50; s++) {
            final double[] sR = new double[n];
            final double[] sI = new double[n];
            for (int k = 0; k < n; k++) {
                sR[k] = rng.nextGaussian();
                sI[k] = rng.nextGaussian();
            }
            C.accumulate(sR, sI);
        }
        final double[][] tRe = new double[n][n];
        final double[][] tIm = new double[n][n];
        C.finalizeT(50, tRe, tIm);

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                assertEquals("Re symmetry " + i + "," + j, tRe[i][j], tRe[j][i], 1.0e-12);
                assertEquals("Im skew " + i + "," + j, tIm[i][j], -tIm[j][i], 1.0e-12);
            }
        }
    }

    @Test
    public void bias_correction_applies_msc_formula_and_preserves_phase() {
        final int n = 4;
        final int L = 12;
        final CovarianceMatrix C = new CovarianceMatrix(n);
        final java.util.Random rng = new java.util.Random(5);
        for (int s = 0; s < L; s++) {
            final double[] sR = new double[n];
            final double[] sI = new double[n];
            for (int k = 0; k < n; k++) {
                sR[k] = rng.nextGaussian();
                sI[k] = rng.nextGaussian();
            }
            C.accumulate(sR, sI);
        }
        final double[][] rawRe = new double[n][n];
        final double[][] rawIm = new double[n][n];
        C.finalizeT(L, rawRe, rawIm, false);
        final double[][] corRe = new double[n][n];
        final double[][] corIm = new double[n][n];
        C.finalizeT(L, corRe, corIm, true);

        for (int i = 0; i < n; i++) {
            assertEquals("diagonal stays 1", 1.0, corRe[i][i], 1.0e-12);
            assertEquals(0.0, corIm[i][i], 1.0e-12);
            for (int j = i + 1; j < n; j++) {
                final double mRaw = Math.hypot(rawRe[i][j], rawIm[i][j]);
                final double expected = Math.sqrt(Math.max(0.0, (L * mRaw * mRaw - 1.0) / (L - 1.0)));
                final double mCor = Math.hypot(corRe[i][j], corIm[i][j]);
                assertEquals("MSC-corrected magnitude (" + i + "," + j + ")", expected, mCor, 1.0e-9);
                assertTrue("correction must not increase magnitude", mCor <= mRaw + 1.0e-12);
                if (mCor > 1.0e-6) {
                    assertEquals("phase preserved (" + i + "," + j + ")",
                            Math.atan2(rawIm[i][j], rawRe[i][j]),
                            Math.atan2(corIm[i][j], corRe[i][j]), 1.0e-9);
                }
                assertEquals("Hermitian Re", corRe[i][j], corRe[j][i], 1.0e-12);
                assertEquals("Hermitian Im", corIm[i][j], -corIm[j][i], 1.0e-12);
            }
        }
    }

    @Test
    public void reset_clears_accumulator() {
        final int n = 3;
        final CovarianceMatrix C = new CovarianceMatrix(n);
        C.accumulate(new double[]{1, 1, 1}, new double[]{0, 0, 0});
        C.reset();
        C.accumulate(new double[]{2, 0, 0}, new double[]{0, 0, 0});

        final double[][] tRe = new double[n][n];
        final double[][] tIm = new double[n][n];
        C.finalizeT(1, tRe, tIm);

        assertEquals(1.0, tRe[0][0], 1.0e-12);
        // After reset, the only-nonzero-first-epoch sample produces a degenerate T whose
        // off-diagonals involving epoch 0 are 0/0 -> set to 0.
        assertEquals(0.0, tRe[0][1], 1.0e-12);
        assertEquals(0.0, tIm[0][1], 1.0e-12);
    }
}
