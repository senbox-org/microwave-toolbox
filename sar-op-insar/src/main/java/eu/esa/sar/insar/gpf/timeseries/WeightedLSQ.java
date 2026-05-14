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
package eu.esa.sar.insar.gpf.timeseries;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.DecompositionSolver;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;
import org.apache.commons.math3.linear.SingularValueDecomposition;

/**
 * Coherence-weighted least-squares solver for SBAS inversion (Berardino
 * et al. 2002).
 *
 * Solves
 *
 *   x = (A^T W A + lambda I)^{-1} A^T W phi
 *
 * with W = diag(w_k) per-pair weights. Tikhonov regularization activates
 * automatically when cond(A^T W A) exceeds {@code condThreshold}, using
 * lambda = regWeight * trace(A^T W A) / (N - 1).
 *
 * Returns no-data flag when the active sub-network is rank deficient (i.e.
 * the pixel is unreachable from the reference under the available
 * coherence threshold).
 */
public final class WeightedLSQ {

    private final double[][] A;
    private final int M;
    private final int K;
    private final double regWeight;
    private final double condThreshold;

    /**
     * @param A             M x K design matrix (K = N - 1 typically)
     * @param regWeight     Tikhonov weight scale (default 1e-3)
     * @param condThreshold cond(AtWA) above which Tikhonov is applied (default 1e6)
     */
    public WeightedLSQ(final double[][] A, final double regWeight, final double condThreshold) {
        this.A = A;
        this.M = A.length;
        this.K = (M > 0) ? A[0].length : 0;
        this.regWeight = regWeight;
        this.condThreshold = condThreshold;
    }

    public static final class Result {
        public boolean ok;
        public double[] x;           // length K
        public double[] residuals;   // length M (phi - A x)
        public boolean regularized;
    }

    /**
     * Solve for one pixel.
     *
     * @param phi length-M observations
     * @param w   length-M non-negative weights (zero = drop equation)
     * @param out reusable result holder (its fields are overwritten)
     */
    public void solve(final double[] phi, final double[] w, final Result out) {
        // Normal equations: G = A^T W A,  b = A^T W phi
        final double[][] G = new double[K][K];
        final double[] b = new double[K];
        int active = 0;
        for (int k = 0; k < M; k++) {
            final double wk = w[k];
            if (wk <= 0.0) continue;
            active++;
            final double[] Ak = A[k];
            for (int i = 0; i < K; i++) {
                final double ai = Ak[i];
                if (ai == 0.0) continue;
                b[i] += wk * ai * phi[k];
                for (int j = 0; j < K; j++) {
                    G[i][j] += wk * ai * Ak[j];
                }
            }
        }

        if (active < K) {
            out.ok = false;
            return;
        }

        // Inspect singular values for condition number
        final RealMatrix Gm = new Array2DRowRealMatrix(G, false);
        final SingularValueDecomposition svd = new SingularValueDecomposition(Gm);
        final double[] sv = svd.getSingularValues();
        final double sMax = sv[0];
        final double sMin = sv[sv.length - 1];
        final double cond = (sMin > 0.0) ? (sMax / sMin) : Double.POSITIVE_INFINITY;

        final boolean ill = !(cond < condThreshold);
        double lambda = 0.0;
        if (ill) {
            double trace = 0.0;
            for (int i = 0; i < K; i++) trace += G[i][i];
            lambda = regWeight * trace / K;
            for (int i = 0; i < K; i++) G[i][i] += lambda;
        }

        final RealMatrix Greg = ill ? new Array2DRowRealMatrix(G, false) : Gm;
        final DecompositionSolver solver = new SingularValueDecomposition(Greg).getSolver();
        if (!solver.isNonSingular()) {
            out.ok = false;
            return;
        }
        final RealVector bv = new ArrayRealVector(b, false);
        final RealVector xv = solver.solve(bv);

        if (out.x == null || out.x.length != K) out.x = new double[K];
        for (int i = 0; i < K; i++) out.x[i] = xv.getEntry(i);

        if (out.residuals == null || out.residuals.length != M) out.residuals = new double[M];
        for (int k = 0; k < M; k++) {
            if (w[k] <= 0.0) {
                out.residuals[k] = Double.NaN;
                continue;
            }
            double pred = 0.0;
            final double[] Ak = A[k];
            for (int i = 0; i < K; i++) pred += Ak[i] * out.x[i];
            out.residuals[k] = phi[k] - pred;
        }
        out.regularized = ill;
        out.ok = true;
    }

    /** Pseudo-inverse via SVD (used for unit tests). */
    public static double[][] pinv(final double[][] M) {
        final RealMatrix Mm = MatrixUtils.createRealMatrix(M);
        final SingularValueDecomposition svd = new SingularValueDecomposition(Mm);
        final RealMatrix pinv = svd.getSolver().getInverse();
        return pinv.getData();
    }
}
