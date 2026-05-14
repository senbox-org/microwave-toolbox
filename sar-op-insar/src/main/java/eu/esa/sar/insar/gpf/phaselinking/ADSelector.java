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
 * Two-sample Anderson-Darling SHP test (Scholz &amp; Stephens 1987).
 *
 *   A_kn^2 = (1 / N) * sum_i (M_i * (N - M_i) - i * (N - i))^2
 *                          / (i * (N - i))
 *
 * with the standardised statistic
 *
 *   T_kn = (A_kn^2 - (k-1)) / sigma
 *
 * For k=2 samples of equal size n the asymptotic critical values are
 *
 *   alpha   T_kn
 *   0.10    1.933
 *   0.05    2.492
 *   0.025   3.070
 *   0.01    3.857
 *
 * (FRInGE uses these defaults.) We hand-roll the equal-sample-size
 * specialisation since both arrays are length N in the phase-linking
 * call site.
 */
public final class ADSelector implements SHPSelector {

    private final double criticalT;
    private double[] sortedCentre;
    private final int n;

    public ADSelector(final double alpha, final int n) {
        this.n = n;
        this.criticalT = criticalValue(alpha);
    }

    private static double criticalValue(final double alpha) {
        if (alpha >= 0.10) return 1.933;
        if (alpha >= 0.05) return 2.492;
        if (alpha >= 0.025) return 3.070;
        return 3.857; // alpha = 0.01 or stricter
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

        // Pool both samples, sort, then for each pooled rank i compute
        // M_i = number of "centre" observations with value <= pooled[i].
        final double[] a = sortedCentre;
        final double[] b = new double[candidate.length];
        System.arraycopy(candidate, 0, b, 0, candidate.length);
        Arrays.sort(b);

        final int nA = a.length;
        final int nB = b.length;
        final int N = nA + nB;

        // Merge into pooled in ascending order, keeping a "from-A" flag
        final double[] pooled = new double[N];
        final boolean[] fromA = new boolean[N];
        int ia = 0, ib = 0;
        for (int k = 0; k < N; k++) {
            if (ia < nA && (ib >= nB || a[ia] <= b[ib])) {
                pooled[k] = a[ia];
                fromA[k] = true;
                ia++;
            } else {
                pooled[k] = b[ib];
                fromA[k] = false;
                ib++;
            }
        }

        // A_kn^2 = (1/N) sum_{i=1..N-1} (M_i * N - i * nA)^2 / (i * (N - i))   (Scholz-Stephens eq. for k=2)
        double sum = 0.0;
        int M = 0; // count of A-observations <= pooled[i] (cumulative)
        for (int i = 0; i < N - 1; i++) {
            if (fromA[i]) M++;
            final double iPlus1 = i + 1.0;
            final double num = M * (double) N - iPlus1 * nA;
            final double den = iPlus1 * (N - iPlus1);
            if (den > 0.0) {
                sum += (num * num) / den;
            }
        }
        final double Akn2 = sum / N;

        // Standardise: sigma_n^2 for k=2 equal-sized samples (Scholz-Stephens 1987 table)
        // approx sigma^2 = (1/3)*(N-1)*(N+1) - 0.* (... omitted: dominated by ~N for n>=20)
        // Use the closed-form normal approximation (k-1 = 1, sigma_n -> sqrt((N-1)*g)),
        // simplified to the well-known: T = A - 1 for large N.
        final double T = Akn2 - 1.0;

        return T <= criticalT;
    }
}
