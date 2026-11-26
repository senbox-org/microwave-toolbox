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

import com.bc.ceres.annotation.STTM;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.*;

/**
 * The sub-pixel peak finder on synthetic band-limited point targets with a known position.
 */
public class TestCornerReflectorPeak {

    private static final int SIZE = 96;
    private static final double BANDWIDTH = 0.8;

    /** A complex point target on a phase ramp, plus complex white noise. Like an SLC it is oversampled: 80 % bandwidth. */
    private static float[][] pointTarget(double row, double col, double carrierX, double carrierY,
                                         double noise, long seed) {
        final float[] re = new float[SIZE * SIZE], im = new float[SIZE * SIZE];
        final Random random = new Random(seed);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                final double a = 10.0 * sinc(BANDWIDTH * (x - col)) * sinc(BANDWIDTH * (y - row));
                final double phase = carrierX * x + carrierY * y;
                re[y * SIZE + x] = (float) (a * Math.cos(phase) + noise * random.nextGaussian());
                im[y * SIZE + x] = (float) (a * Math.sin(phase) + noise * random.nextGaussian());
            }
        }
        return new float[][]{re, im};
    }

    private static double sinc(double x) {
        return x == 0 ? 1 : Math.sin(Math.PI * x) / (Math.PI * x);
    }

    @Test
    @STTM("SNAP-4384")
    public void findsSubPixelPositionAtBaseband() {
        final float[][] s = pointTarget(40.3, 52.7, 0, 0, 0.01, 1);
        final CornerReflectorPeak.Peak peak = CornerReflectorPeak.find(s[0], s[1], SIZE, SIZE, 42, 50, 16);
        assertNotNull(peak);
        assertEquals(40.3, peak.row, 0.02);
        assertEquals(52.7, peak.col, 0.02);
    }

    @Test
    @STTM("SNAP-4384")
    public void findsSubPixelPositionWithCarrier() {
        // A GSLC keeps the SLC carrier, so the spectrum is not centred on zero frequency.
        final float[][] s = pointTarget(47.81, 33.24, 1.1, -0.7, 0.01, 2);
        final CornerReflectorPeak.Peak peak = CornerReflectorPeak.find(s[0], s[1], SIZE, SIZE, 45, 36, 16);
        assertNotNull(peak);
        assertEquals(47.81, peak.row, 0.02);
        assertEquals(33.24, peak.col, 0.02);
    }

    @Test
    @STTM("SNAP-4384")
    public void reportsSignalToClutterRatio() {
        final float[][] s = pointTarget(48, 48, 0, 0, 0.1, 3);
        final CornerReflectorPeak.Peak peak = CornerReflectorPeak.find(s[0], s[1], SIZE, SIZE, 48, 48, 16);
        assertNotNull(peak);
        // peak power 100, clutter power 2 * 0.1^2 = 0.02 -> 37 dB
        assertEquals(37.0, peak.scrDb, 1.5);
    }

    @Test
    @STTM("SNAP-4384")
    public void noTargetInPureClutter() {
        final float[][] s = pointTarget(48, 48, 0, 0, 1.0, 4);
        for (int i = 0; i < s[0].length; i++) {   // remove the target, keep the clutter
            s[0][i] = (float) new Random(i).nextGaussian();
            s[1][i] = (float) new Random(i + 7919L).nextGaussian();
        }
        assertNull(CornerReflectorPeak.find(s[0], s[1], SIZE, SIZE, 48, 48, 16));
    }

    @Test
    @STTM("SNAP-4384")
    public void rectangularWindowIgnoresABrighterTargetOutsideItsColumns() {
        // a pixel grid much coarser across than along (frequency B: 40 m x 5 m) needs a narrow window
        final float[][] s = pointTarget(48.3, 40.6, 0.9, 0, 0.01, 6);
        final float[][] bright = pointTarget(46, 52, 0.9, 0, 0, 7);
        for (int i = 0; i < s[0].length; i++) {
            s[0][i] += 3 * bright[0][i];
            s[1][i] += 3 * bright[1][i];
        }
        final CornerReflectorPeak.Peak peak = CornerReflectorPeak.find(s[0], s[1], SIZE, SIZE, 48, 40, 16, 4);
        assertNotNull(peak);
        assertEquals(48.3, peak.row, 0.05);
        assertEquals(40.6, peak.col, 0.05);
    }

    @Test
    @STTM("SNAP-4384")
    public void windowOutsideTheImageGivesNoPeak() {
        final float[][] s = pointTarget(48, 48, 0, 0, 0.01, 5);
        assertNull(CornerReflectorPeak.find(s[0], s[1], SIZE, SIZE, 3, 48, 16));
    }
}
