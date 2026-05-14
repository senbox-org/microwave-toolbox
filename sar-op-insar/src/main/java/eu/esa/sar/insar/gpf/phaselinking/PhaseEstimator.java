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
 * Per-pixel phase estimator over an N x N Hermitian coherence matrix.
 *
 * Implementations: {@link EVDEstimator} (largest eigenvector of T_hat,
 * Ferretti SqueeSAR 2011) and {@link EMIEstimator} (smallest eigenvector of
 * |T_hat|^{-1} (elementwise) * T_hat, Ansari/De Zan/Bamler 2018).
 *
 * Future v2: iterative MLE / CRLB refinement, sequential / ministack PL.
 */
public interface PhaseEstimator {

    /**
     * @param n      stack size
     * @param tRe    n x n real part of T_hat
     * @param tIm    n x n imaginary part of T_hat
     * @param refIdx index of the reference epoch (its output phase is forced to 0)
     * @param phi    length-n output per-epoch phases in radians, phi[refIdx]=0
     */
    void estimate(int n, double[][] tRe, double[][] tIm, int refIdx, double[] phi);
}
