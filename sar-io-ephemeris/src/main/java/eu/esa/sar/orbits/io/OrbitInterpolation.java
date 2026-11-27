/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.orbits.io;

import org.esa.snap.engine_utilities.datamodel.Orbits;

import java.util.List;

/**
 * Interpolation of orbit state vectors with an 8-point Lagrange polynomial through the positions
 * around the requested time (the scheme of {@code eu.esa.sar.commons.OrbitStateVectors} and of
 * jlinda's {@code Orbit}). The velocity returned is the derivative of the position returned, so the
 * two are consistent; the file's velocities are not used, which keeps the result independent of
 * how well they agree with the positions.
 * <p>
 * The vectors' epochs are taken to the microsecond, the resolution of orbit files and of
 * {@code ProductData.UTC}: an MJD double resolves only 0.08-0.16 us (0.6-1.2 mm along track), and
 * that jitter between the vectors would otherwise show up in the positions and their derivative.
 */
public final class OrbitInterpolation {

    /** state vectors used around the requested time */
    static final int NUM_VECTORS = 8;

    private static final double SECONDS_PER_DAY = 86400.0;

    private OrbitInterpolation() {
    }

    /**
     * @param osv    state vectors in strictly increasing time order (utcMJD in days, positions in m)
     * @param utcMJD the time to interpolate at, in days
     * @return the state vector at utcMJD: interpolated position, its derivative as velocity (m/s)
     */
    public static Orbits.OrbitVector interpolate(final List<Orbits.OrbitVector> osv, final double utcMJD) {
        final int n = osv.size();
        if (n == 0) {
            throw new IllegalArgumentException("no orbit state vectors to interpolate");
        }
        if (n == 1) {
            final Orbits.OrbitVector v = osv.get(0);
            return new Orbits.OrbitVector(utcMJD, v.xPos, v.yPos, v.zPos, v.xVel, v.yVel, v.zVel);
        }
        final int k = segmentIndex(osv, utcMJD);
        final int m = Math.min(NUM_VECTORS, n);
        final int start = Math.max(0, Math.min(k - m / 2 + 1, n - m));

        // seconds from the first vector, the vectors' epochs to the microsecond; then in units of
        // the segment's spacing from the segment's first vector
        final double t0 = osv.get(0).utcMJD;
        final double sk = epochSeconds(osv.get(k).utcMJD, t0);
        final double h = epochSeconds(osv.get(k + 1).utcMJD, t0) - sk;
        final double[] x = new double[m];
        final double[][] f = new double[3][m];
        for (int i = 0; i < m; i++) {
            final Orbits.OrbitVector v = osv.get(start + i);
            x[i] = (epochSeconds(v.utcMJD, t0) - sk) / h;
            f[0][i] = v.xPos;
            f[1][i] = v.yPos;
            f[2][i] = v.zPos;
        }

        final double u = ((utcMJD - t0) * SECONDS_PER_DAY - sk) / h;
        final double[] pos = new double[3];
        final double[] vel = new double[3];
        for (int c = 0; c < 3; c++) {
            final double[] a = dividedDifferences(x, f[c]);
            double p = a[m - 1], dp = 0.0;
            for (int i = m - 2; i >= 0; i--) {
                final double d = u - x[i];
                dp = dp * d + p;
                p = p * d + a[i];
            }
            pos[c] = p;
            vel[c] = dp / h;
        }
        return new Orbits.OrbitVector(utcMJD, pos[0], pos[1], pos[2], vel[0], vel[1], vel[2]);
    }

    /** seconds from t0 to a state vector's epoch, rounded to the microsecond */
    private static double epochSeconds(final double utcMJD, final double t0) {
        return Math.rint((utcMJD - t0) * SECONDS_PER_DAY * 1e6) / 1e6;
    }

    /** Newton coefficients of the polynomial through (x[i], f[i]) */
    private static double[] dividedDifferences(final double[] x, final double[] f) {
        final double[] a = f.clone();
        for (int level = 1; level < x.length; level++) {
            for (int j = x.length - 1; j >= level; j--) {
                a[j] = (a[j] - a[j - 1]) / (x[j] - x[j - level]);
            }
        }
        return a;
    }

    /** index k of the segment [k, k+1] holding utcMJD, clamped to the first and last segments */
    private static int segmentIndex(final List<Orbits.OrbitVector> osv, final double utcMJD) {
        int lo = 0, hi = osv.size() - 1;
        while (lo < hi) {
            final int mid = (lo + hi + 1) >>> 1;
            if (osv.get(mid).utcMJD <= utcMJD) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return Math.max(0, Math.min(lo, osv.size() - 2));
    }
}
