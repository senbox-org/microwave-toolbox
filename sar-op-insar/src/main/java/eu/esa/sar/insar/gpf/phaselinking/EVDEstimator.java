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
 * EVD phase estimator (Ferretti et al., SqueeSAR, IEEE TGRS 2011).
 *
 * The estimated complex phasor vector u is the dominant eigenvector
 * (largest eigenvalue) of the Hermitian coherence matrix T_hat:
 *
 *   u = argmax_v (v^H T_hat v)  s.t.  ||v|| = 1
 *
 * The per-epoch phase is phi_n = arg(u_n) - arg(u_ref).
 */
public final class EVDEstimator implements PhaseEstimator {

    @Override
    public void estimate(final int n, final double[][] tRe, final double[][] tIm,
                         final int refIdx, final double[] phi) {

        final double[][] vr = new double[n][n];
        final double[][] vi = new double[n][n];
        final double[] lambda = new double[n];

        HermitianEigSolver.decompose(n, tRe, tIm, vr, vi, lambda);

        // dominant eigenvector is column 0 after descending sort
        final double refRe = vr[refIdx][0];
        final double refIm = vi[refIdx][0];
        final double refArg = Math.atan2(refIm, refRe);
        for (int k = 0; k < n; k++) {
            phi[k] = Math.atan2(vi[k][0], vr[k][0]) - refArg;
        }
        phi[refIdx] = 0.0;
    }
}
