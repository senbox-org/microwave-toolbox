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
 * Welch t-test on log-amplitude (MiaplPy "fast mode" SHP selector).
 *
 * Under Rayleigh-distributed amplitudes A, ln(A) is approximately Normal
 * with mean and variance set by the underlying RCS - so a Welch t-test on
 * ln(A) is a cheap surrogate for the full KS/AD machinery.
 *
 * Two-sided critical value at alpha = 0.05, large df: 1.960.
 * At alpha = 0.01: 2.576. (Normal-approximation critical values.)
 */
public final class TLogSelector implements SHPSelector {

    private final double critical;
    private double centreMean;
    private double centreVar;
    private int centreN;

    public TLogSelector(final double alpha) {
        this.critical = (alpha <= 0.01) ? 2.576 : (alpha <= 0.025) ? 2.326 : 1.960;
    }

    @Override
    public void prepareCentre(final double[] centre) {
        centreN = centre.length;
        double m = 0.0;
        int valid = 0;
        for (double a : centre) {
            if (a > 0.0) {
                m += Math.log(a);
                valid++;
            }
        }
        m /= Math.max(1, valid);
        double v = 0.0;
        for (double a : centre) {
            if (a > 0.0) {
                final double d = Math.log(a) - m;
                v += d * d;
            }
        }
        centreMean = m;
        centreVar = v / Math.max(1, valid - 1);
        centreN = valid;
    }

    @Override
    public boolean accept(final double[] centre, final double[] candidate) {
        if (centreN <= 1) prepareCentre(centre);

        double m = 0.0;
        int valid = 0;
        for (double a : candidate) {
            if (a > 0.0) {
                m += Math.log(a);
                valid++;
            }
        }
        if (valid <= 1) return false;
        m /= valid;
        double v = 0.0;
        for (double a : candidate) {
            if (a > 0.0) {
                final double d = Math.log(a) - m;
                v += d * d;
            }
        }
        v /= (valid - 1);

        final double se = Math.sqrt(centreVar / centreN + v / valid);
        if (!(se > 0.0)) return true; // degenerate -> don't reject
        final double t = Math.abs((centreMean - m) / se);

        return t <= critical;
    }
}
