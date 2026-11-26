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
package eu.esa.sar.io.nisar;

import edu.emory.mathcs.jtransforms.fft.DoubleFFT_2D;

/**
 * Sub-pixel point-target peak finder for complex (SLC) imagery.
 * <p>
 * Coarse maximum of |s|^2 in a window around the expected position, then a chip centred on it is
 * demodulated by its own phase gradient (the SLC carrier), upsampled by FFT zero-padding and the
 * upsampled maximum refined with a 3-point parabola in each direction. Positions are array indices:
 * pixel i is centred on i.
 */
final class CornerReflectorPeak {

    static final class Peak {
        final double row, col;
        /** Peak power over the mean power of the window outside the target's row and column. */
        final double scrDb;

        Peak(double row, double col, double scrDb) {
            this.row = row;
            this.col = col;
            this.scrDb = scrDb;
        }

        @Override
        public String toString() {
            return String.format("(%.3f, %.3f) SCR %.1f dB", row, col, scrDb);
        }
    }

    static final double MIN_SCR_DB = 15.0;
    private static final int UPSAMPLE = 16;
    private static final int CROSS_HALF_WIDTH = 2;

    private CornerReflectorPeak() {
    }

    /**
     * @param re      real part, row-major, width x height
     * @param im      imaginary part
     * @param expRow  expected row of the target
     * @param expCol  expected column of the target
     * @param half    the search window and the upsampled chip are 2 * half pixels square
     * @return the peak, or null if the window leaves the image or no target stands out of the clutter
     */
    static Peak find(final float[] re, final float[] im, final int width, final int height,
                     final double expRow, final double expCol, final int half) {
        return find(re, im, width, height, expRow, expCol, half, half);
    }

    /**
     * As {@link #find(float[], float[], int, int, double, double, int)} with a window of 2 * halfRow rows
     * by 2 * halfCol columns, for pixels much larger in one direction than the other.
     */
    static Peak find(final float[] re, final float[] im, final int width, final int height,
                     final double expRow, final double expCol, final int halfRow, final int halfCol) {
        final int nr = 2 * halfRow, nc = 2 * halfCol;
        int r0 = (int) Math.round(expRow) - halfRow, c0 = (int) Math.round(expCol) - halfCol;
        if (!inside(r0, c0, nr, nc, width, height)) {
            return null;
        }

        // coarse maximum in the search window
        int pr = -1, pc = -1;
        double max = -1;
        for (int y = r0; y < r0 + nr; y++) {
            for (int x = c0; x < c0 + nc; x++) {
                final double p = power(re, im, y * width + x);
                if (p > max) {
                    max = p;
                    pr = y;
                    pc = x;
                }
            }
        }
        if (!(max > 0)) {
            return null;
        }

        // clutter: the window outside the target's row and column (its sidelobes)
        double clutter = 0;
        int count = 0;
        for (int y = r0; y < r0 + nr; y++) {
            for (int x = c0; x < c0 + nc; x++) {
                if (Math.abs(y - pr) > CROSS_HALF_WIDTH && Math.abs(x - pc) > CROSS_HALF_WIDTH) {
                    clutter += power(re, im, y * width + x);
                    count++;
                }
            }
        }
        clutter /= count;

        // chip centred on the coarse peak
        r0 = pr - halfRow;
        c0 = pc - halfCol;
        if (!inside(r0, c0, nr, nc, width, height)) {
            return null;
        }

        // carrier from the lag-one autocorrelation in each direction
        double axRe = 0, axIm = 0, ayRe = 0, ayIm = 0;
        for (int y = r0; y < r0 + nr; y++) {
            for (int x = c0; x < c0 + nc; x++) {
                final int i = y * width + x;
                if (x + 1 < c0 + nc) {   // s[x+1] * conj(s[x])
                    axRe += re[i + 1] * re[i] + im[i + 1] * im[i];
                    axIm += im[i + 1] * re[i] - re[i + 1] * im[i];
                }
                if (y + 1 < r0 + nr) {
                    final int j = i + width;
                    ayRe += re[j] * re[i] + im[j] * im[i];
                    ayIm += im[j] * re[i] - re[j] * im[i];
                }
            }
        }
        final double fx = Math.atan2(axIm, axRe), fy = Math.atan2(ayIm, ayRe);

        final double[][] chip = new double[nr][2 * nc];
        for (int y = 0; y < nr; y++) {
            for (int x = 0; x < nc; x++) {
                final int i = (r0 + y) * width + c0 + x;
                final double ph = -(fx * x + fy * y);
                final double c = Math.cos(ph), s = Math.sin(ph);
                chip[y][2 * x] = re[i] * c - im[i] * s;
                chip[y][2 * x + 1] = re[i] * s + im[i] * c;
            }
        }
        new DoubleFFT_2D(nr, nc).complexForward(chip);

        final int mr = nr * UPSAMPLE, mc = nc * UPSAMPLE;
        final double[][] big = new double[mr][2 * mc];
        for (int ky = 0; ky < nr; ky++) {
            for (int kx = 0; kx < nc; kx++) {
                final double wy = ky == halfRow ? 0.5 : 1, wx = kx == halfCol ? 0.5 : 1;
                for (int by : targets(ky, halfRow, nr, mr)) {
                    for (int bx : targets(kx, halfCol, nc, mc)) {
                        big[by][2 * bx] += wy * wx * chip[ky][2 * kx];
                        big[by][2 * bx + 1] += wy * wx * chip[ky][2 * kx + 1];
                    }
                }
            }
        }
        new DoubleFFT_2D(mr, mc).complexInverse(big, false);

        int uy = 0, ux = 0;
        double peak = -1;
        for (int y = 0; y < mr; y++) {
            for (int x = 0; x < mc; x++) {
                final double p = big[y][2 * x] * big[y][2 * x] + big[y][2 * x + 1] * big[y][2 * x + 1];
                if (p > peak) {
                    peak = p;
                    uy = y;
                    ux = x;
                }
            }
        }
        final double scale = (double) nr * nc;   // undo the zero-padding gain
        final double peakPower = peak / (scale * scale);
        final double scrDb = 10 * Math.log10(peakPower / clutter);
        if (scrDb < MIN_SCR_DB) {
            return null;
        }

        final double dy = parabola(upPower(big, (uy - 1 + mr) % mr, ux), peak, upPower(big, (uy + 1) % mr, ux));
        final double dx = parabola(upPower(big, uy, (ux - 1 + mc) % mc), peak, upPower(big, uy, (ux + 1) % mc));
        return new Peak(r0 + (uy + dy) / UPSAMPLE, c0 + (ux + dx) / UPSAMPLE, scrDb);
    }

    /** Where spectrum bin k of an n-point FFT goes in the m-point one; the Nyquist bin goes to both ends. */
    private static int[] targets(final int k, final int half, final int n, final int m) {
        if (k < half) return new int[]{k};
        if (k > half) return new int[]{m - n + k};
        return new int[]{k, m - n + k};
    }

    private static double upPower(final double[][] big, final int y, final int x) {
        return big[y][2 * x] * big[y][2 * x] + big[y][2 * x + 1] * big[y][2 * x + 1];
    }

    private static double parabola(final double left, final double centre, final double right) {
        final double d = left - 2 * centre + right;
        return d == 0 ? 0 : 0.5 * (left - right) / d;
    }

    private static double power(final float[] re, final float[] im, final int i) {
        return (double) re[i] * re[i] + (double) im[i] * im[i];
    }

    private static boolean inside(final int r0, final int c0, final int nr, final int nc, final int width, final int height) {
        return r0 >= 0 && c0 >= 0 && r0 + nr <= height && c0 + nc <= width;
    }
}
