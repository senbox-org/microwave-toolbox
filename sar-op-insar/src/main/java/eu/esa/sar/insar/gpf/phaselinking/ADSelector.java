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

import java.util.Arrays;

/**
 * Two-sample Anderson-Darling SHP test (Scholz &amp; Stephens, JASA 1987,
 * "K-Sample Anderson-Darling Tests"); the same statistic implemented by
 * {@code scipy.stats.anderson_ksamp} and used by FRInGE.
 *
 * For k=2 samples the rank statistic (no-ties form, eq. 6) is
 *
 *   A2_akN = ((N-1) / N^2) * (1/nA + 1/nB) * sum_{j=1..N-1}
 *               (N * M_Aj - j * nA)^2 / (j * (N - j))
 *
 * where {@code M_Aj} is the number of sample-A observations among the j
 * smallest pooled values. It is standardised to the {@code T_m} (m=k-1=1)
 * distribution by its exact mean (k-1) and variance (eqs. 3-5):
 *
 *   T = (A2_akN - (k-1)) / sigma_N
 *
 * and the null (same distribution) is rejected when {@code T > criticalT}.
 * The m=1 upper-tail percentage points (Table 1 / scipy's b0+b1+b2 fit) are
 *
 *   alpha   T
 *   0.10    1.226
 *   0.05    1.961
 *   0.025   2.718
 *   0.01    3.752
 *
 * sigma_N and the rank-sum prefactor depend only on the sample sizes, which
 * are fixed (both arrays are the stack length N) for every phase-linking
 * call, so they are precomputed once in the constructor.
 */
public final class ADSelector implements SHPSelector {

    private final double criticalT;
    /** (N-1)/N^2 * (1/nA + 1/nB), the prefactor on the rank sum; nA = nB = n, N = 2n. */
    private final double normFactor;
    /** Standard deviation of A2_akN under H0 (Scholz-Stephens variance, k=2). */
    private final double sigma;

    private double[] sortedCentre;

    public ADSelector(final double alpha, final int n) {
        this.criticalT = criticalValue(alpha);

        final int nA = n, nB = n;
        final int N = nA + nB;
        this.normFactor = (N - 1.0) / ((double) N * N) * (1.0 / nA + 1.0 / nB);
        this.sigma = (N > 3) ? standardDeviation(N, nA, nB) : 1.0;
    }

    /** m = k-1 = 1 upper-tail percentage points of the Scholz-Stephens T_m distribution. */
    private static double criticalValue(final double alpha) {
        if (alpha >= 0.10) return 1.226;
        if (alpha >= 0.05) return 1.961;
        if (alpha >= 0.025) return 2.718;
        return 3.752; // alpha = 0.01 or stricter
    }

    /** sqrt of the H0 variance of A2_akN (Scholz-Stephens 1987, eqs. 3-5), specialised to k=2. */
    private static double standardDeviation(final int N, final int nA, final int nB) {
        final int k = 2;
        final double H = 1.0 / nA + 1.0 / nB;
        double h = 0.0;
        for (int j = 1; j < N; j++) {
            h += 1.0 / j;
        }
        double g = 0.0;
        for (int i = 1; i <= N - 2; i++) {
            for (int j = i + 1; j <= N - 1; j++) {
                g += 1.0 / ((double) (N - i) * j);
            }
        }
        final double a = (4 * g - 6) * (k - 1) + (10 - 6 * g) * H;
        final double b = (2 * g - 4) * k * k + 8 * h * k + (2 * g - 14 * h - 4) * H - 8 * h + 4 * g - 6;
        final double c = (6 * h + 2 * g - 2) * k * k + (4 * h - 4 * g + 6) * k + (2 * h - 6) * H + 4 * h;
        final double d = (2 * h + 6) * k * k - 4 * h * k;
        final double var = (a * Math.pow(N, 3) + b * (double) N * N + c * N + d)
                / ((N - 1.0) * (N - 2.0) * (N - 3.0));
        return (var > 0.0) ? Math.sqrt(var) : 1.0;
    }

    @Override
    public void prepareCentre(final double[] centre) {
        if (sortedCentre == null || sortedCentre.length != centre.length) {
            sortedCentre = new double[centre.length];
        }
        System.arraycopy(centre, 0, sortedCentre, 0, centre.length);
        Arrays.sort(sortedCentre);
    }

    @Override
    public boolean accept(final double[] centre, final double[] candidate) {
        if (sortedCentre == null) prepareCentre(centre);

        final double[] a = sortedCentre;
        final double[] b = new double[candidate.length];
        System.arraycopy(candidate, 0, b, 0, candidate.length);
        Arrays.sort(b);

        final int nA = a.length;
        final int nB = b.length;
        final int N = nA + nB;

        // Merge-walk the pooled sample, accumulating the rank sum
        //   S = sum_{j=1..N-1} (N * M_Aj - j * nA)^2 / (j * (N - j))
        // where M_Aj = #{A observations among the j smallest pooled values}.
        int ia = 0, ib = 0;
        int M = 0;        // cumulative A-count
        double S = 0.0;
        for (int j = 1; j < N; j++) {   // pooled positions 1..N-1
            if (ia < nA && (ib >= nB || a[ia] <= b[ib])) {
                M++;
                ia++;
            } else {
                ib++;
            }
            final double num = (double) N * M - (double) j * nA;
            final double den = (double) j * (N - j);
            S += (num * num) / den;
        }

        final double A2akN = normFactor * S;
        final double T = (A2akN - 1.0) / sigma;   // m = k-1 = 1

        return T <= criticalT;
    }
}
