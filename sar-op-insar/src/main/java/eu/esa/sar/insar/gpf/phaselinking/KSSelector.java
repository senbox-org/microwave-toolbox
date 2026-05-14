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
 * Two-sample Kolmogorov-Smirnov SHP test.
 *
 * Statistic D = max_x |F1(x) - F2(x)|. The null (same distribution) is
 * rejected when
 *
 *   D &gt; c(alpha) * sqrt((n1 + n2) / (n1 * n2))
 *
 * with c(0.05) approx 1.358, c(0.01) approx 1.628 (Smirnov 1948
 * asymptotic critical values).
 *
 * For phase-linking n1 = n2 = N (the stack length), so the critical
 * value collapses to {@code c * sqrt(2/N)} and is precomputed in
 * {@link #setAlpha(double, int)}.
 *
 * Implementation is hand-rolled because commons-math3
 * {@code KolmogorovSmirnovTest} exact path is far too slow for the
 * ~10^7 calls per tile that phase-linking requires.
 */
public final class KSSelector implements SHPSelector {

    private final double alpha;
    private final int n;
    private final double criticalD;

    private double[] sortedCentre;

    public KSSelector(final double alpha, final int n) {
        this.alpha = alpha;
        this.n = n;
        this.criticalD = criticalValue(alpha, n);
    }

    public double getCriticalD() {
        return criticalD;
    }

    public void setAlpha(final double newAlpha, final int newN) {
        throw new UnsupportedOperationException("Construct a new KSSelector instead.");
    }

    private static double criticalValue(final double alpha, final int n) {
        // Smirnov asymptotic two-sample critical values c(alpha) for
        // alpha = 0.10, 0.05, 0.025, 0.01, 0.005, 0.001.
        // c(alpha) = sqrt(-0.5 * ln(alpha / 2)).
        final double c = Math.sqrt(-0.5 * Math.log(alpha / 2.0));
        return c * Math.sqrt(2.0 / n);
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
        if (sortedCentre == null) {
            prepareCentre(centre);
        }
        final double[] a = sortedCentre;
        final double[] b = new double[candidate.length];
        System.arraycopy(candidate, 0, b, 0, candidate.length);
        Arrays.sort(b);

        // Merge-walk to compute the supremum of |F_a - F_b|.
        final int na = a.length;
        final int nb = b.length;
        final double invNa = 1.0 / na;
        final double invNb = 1.0 / nb;
        int ia = 0, ib = 0;
        double fa = 0.0, fb = 0.0;
        double d = 0.0;
        while (ia < na && ib < nb) {
            if (a[ia] <= b[ib]) {
                fa += invNa;
                ia++;
            } else {
                fb += invNb;
                ib++;
            }
            final double cur = Math.abs(fa - fb);
            if (cur > d) d = cur;
        }
        // tail catch-up
        while (ia < na) { fa += invNa; ia++; final double cur = Math.abs(fa - fb); if (cur > d) d = cur; }
        while (ib < nb) { fb += invNb; ib++; final double cur = Math.abs(fa - fb); if (cur > d) d = cur; }

        return d <= criticalD;
    }
}
