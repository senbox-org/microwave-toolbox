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
package eu.esa.sar.calibration.gpf.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Per-line border-noise cut estimation for Sentinel-1 GRD. Each strip is oriented with index 0 at the image
 * edge and samples running inward; a cut of n means samples [0, n) are border noise.
 * <ul>
 *     <li>{@link #esaCut}: ESA MPC-0243 v2.2 section 5.5, a contiguous run from the edge</li>
 *     <li>{@link #jumpCut} and {@link #regularise}: Ali et al. 2018, IEEE J-STARS 11(3), Algorithm 3
 *     (bidirectional all-samples), with relative instead of absolute DN thresholds</li>
 * </ul>
 */
public final class BorderCutDetector {

    private BorderCutDetector() {
    }

    /** Number of contiguous edge samples whose denoised amplitude sqrt(max(a^2 - eta, 0)) is below the threshold. */
    public static int esaCut(final double[] amplitude, final double[] noisePower, final double trimThreshold) {
        int n = 0;
        while (n < amplitude.length) {
            final double a = amplitude[n];
            final double d = Math.sqrt(Math.max(a * a - noisePower[n], 0.0));
            if (!(d < trimThreshold || Double.isNaN(a))) {
                break;
            }
            n++;
        }
        return n;
    }

    /**
     * Edge of the low-value border zone of one line, after Ali et al. Alg. 3 (lines 5-9), with the absolute
     * raw-DN thresholds (jump 10, valid edge 50, tuned for IW GRDH) replaced by relative ones:
     * <ul>
     *     <li>a border is only present when the edge level (median of the first 10 samples) is below
     *     {@code validEdgeFraction} of the scene level: no-value samples are near zero, while dark surfaces such
     *     as calm water touching the image edge are well above it;</li>
     *     <li>the border ends where the 3-sample mean first exceeds the smaller of {@code riseFraction} of the line's
     *     interior level and four times the edge level, so the cut stops at the first surface behind the border
     *     (e.g. water) rather than running on to the brightest one.</li>
     * </ul>
     *
     * @param sceneLevel typical amplitude of the scene (e.g. median co-polarised amplitude)
     * @return number of border samples: 0 when the line starts with valid data, the strip length when no rise is found
     */
    public static int jumpCut(final double[] a, final double riseFraction, final double validEdgeFraction,
                              final double sceneLevel) {
        return jumpCut(a, riseFraction, validEdgeFraction, sceneLevel, true);
    }

    /**
     * As {@link #jumpCut(double[], double, double, double)}, but a strip without a rise is only masked when
     * {@code deepBorderAllowed}. After thermal noise removal the noise floor no longer keeps dark surfaces above
     * the border level, so a strip that is dark throughout may be water rather than a border deeper than the strip.
     *
     * @return number of border samples; when no rise is found, the strip length if {@code deepBorderAllowed}, else 0
     */
    public static int jumpCut(final double[] a, final double riseFraction, final double validEdgeFraction,
                              final double sceneLevel, final boolean deepBorderAllowed) {
        if (a.length < 12 || !(sceneLevel > 0)) {
            return 0;
        }
        final double edgeLevel = median(a, 0, 10);
        if (!(edgeLevel < validEdgeFraction * sceneLevel)) {
            return 0;
        }
        final double edge = Math.max(edgeLevel, 1e-6 * sceneLevel);
        // at least twice the edge level, so a strip that is border throughout is not cut at its own level
        final double rise = Math.max(2 * edge, Math.min(riseFraction * interiorLevel(a), 4 * edge));
        for (int i = 0; i + 3 <= a.length; i++) {
            if (mean3(a, i) > rise) {
                return i + 1;
            }
        }
        return deepBorderAllowed ? a.length : 0;    // the border is deeper than the strip: mask all of it
    }

    /** Median of a[from, to) ignoring NaN (upper median), 0 when all are NaN; one primitive copy and sort. */
    private static double median(final double[] a, final int from, final int to) {
        final double[] v = Arrays.copyOfRange(a, from, to);
        Arrays.sort(v);                                 // NaN sorts last
        int n = v.length;
        while (n > 0 && Double.isNaN(v[n - 1])) {
            n--;
        }
        return n == 0 ? 0 : v[n / 2];
    }

    private static double mean3(final double[] a, final int i) {
        double s = 0;
        for (int k = i; k < i + 3; k++) {
            s += Double.isNaN(a[k]) ? 0 : a[k];
        }
        return s / 3;
    }

    /** Median of the inner half of the strip. */
    private static double interiorLevel(final double[] a) {
        return median(a, a.length / 2, a.length);
    }

    /**
     * Regularises the cut series along the edge: a median filter removes isolated outliers (Ali et al. Alg. 3), then a
     * moving maximum over {@code maxWindow} lines makes the cut cover the border's local variation. The moving maximum
     * replaces Ali's 99.8th percentile over at most three segments, which flattened gradually widening borders to their
     * largest value and spread isolated false cuts over whole segments. Finally +1, and 0 when at most 2 (Ali Alg. 3),
     * else plus a buffer. For {@link #jumpCut} cuts, where 1 means a rise at the first sample.
     */
    public static int[] regularise(final int[] cuts, final int medianWindow, final int maxWindow, final int buffer) {
        return regularise(cuts, medianWindow, maxWindow, buffer, true);
    }

    /**
     * As {@link #regularise(int[], int, int, int)}; with {@code dropTinyCuts} false only a zero cut stays 0, so the
     * genuine 1-pixel cuts of {@link #esaCut} are kept.
     */
    public static int[] regularise(final int[] cuts, final int medianWindow, final int maxWindow, final int buffer,
                                   final boolean dropTinyCuts) {
        final int n = cuts.length;
        if (n == 0) {
            return new int[0];
        }
        final int[] med = medianFilter(cuts, medianWindow);
        final int[] max = movingMax(med, maxWindow);
        final int minCut = dropTinyCuts ? 2 : 1;
        final int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = max[i] < minCut ? 0 : max[i] + 1 + buffer;
        }
        return out;
    }

    /** Centred moving maximum (monotonic deque, O(n)). */
    private static int[] movingMax(final int[] v, final int window) {
        final int half = window / 2;
        final int n = v.length;
        final int[] out = new int[n];
        final int[] deque = new int[n];
        int head = 0, tail = 0;
        int next = 0;    // next index to add
        for (int i = 0; i < n; i++) {
            final int hi = Math.min(n - 1, i + half);
            while (next <= hi) {
                while (tail > head && v[deque[tail - 1]] <= v[next]) {
                    tail--;
                }
                deque[tail++] = next++;
            }
            while (deque[head] < i - half) {
                head++;
            }
            out[i] = v[deque[head]];
        }
        return out;
    }

    private static int[] medianFilter(final int[] v, final int window) {
        final int half = window / 2;
        final int[] out = new int[v.length];
        final int[] buf = new int[2 * half + 1];
        for (int i = 0; i < v.length; i++) {
            int m = 0;
            for (int j = Math.max(0, i - half); j <= Math.min(v.length - 1, i + half); j++) {
                buf[m++] = v[j];
            }
            Arrays.sort(buf, 0, m);
            out[i] = buf[m / 2];
        }
        return out;
    }
}
