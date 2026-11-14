/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc.
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
package org.jlinda.core.coregistration;

import org.jblas.DoubleMatrix;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Checks the 4-point cubic convolution kernel against Keys (1981), "Cubic convolution interpolation for digital
 * image processing", IEEE Trans. ASSP 29(6), 1153-1160, eq. (15) with a = -1/2:
 * <pre>
 *   u(s) = (a+2)|s|^3 - (a+3)|s|^2 + 1          0 &lt;= |s| &lt; 1
 *   u(s) = a|s|^3 - 5a|s|^2 + 8a|s| - 4a         1 &lt;= |s| &lt; 2
 *   u(s) = 0                                     2 &lt;= |s|
 * </pre>
 * With a = -1/2 the weights sum to 1 and a linear ramp is reproduced exactly.
 */
public class LUTTest {

    private static final double EPS = 1e-12;

    @Test
    public void testCc4ClosedFormValues() {
        final LUT lut = new LUT(LUT.CC4P);
        final double[] y = lut.cc4(new double[]{0.0, 0.5, -0.5, 1.0, 1.5, -1.5, 2.0, 2.5, 0.25, -1.25});

        assertEquals(1.0, y[0], EPS);
        assertEquals(0.5625, y[1], EPS);      // 1.5*0.125 - 2.5*0.25 + 1
        assertEquals(0.5625, y[2], EPS);      // symmetric: u(-s) = u(s)
        assertEquals(0.0, y[3], EPS);
        assertEquals(-0.0625, y[4], EPS);     // -0.5*(3.375 - 11.25 + 12 - 4)
        assertEquals(-0.0625, y[5], EPS);
        assertEquals(0.0, y[6], EPS);
        assertEquals(0.0, y[7], EPS);
        assertEquals(0.8671875, y[8], EPS);   // 1.5/64 - 2.5/16 + 1
        assertEquals(-0.0703125, y[9], EPS);  // -0.5*(1.953125 - 7.8125 + 10 - 4)
    }

    @Test
    public void testCc4RawWeightsSumToOneAndReproduceRamp() {
        final LUT lut = new LUT(LUT.CC4P);
        // target at fractional position f between samples 0 and 1; samples at -1, 0, 1, 2
        for (int i = 0; i <= 100; i++) {
            final double f = i / 100.0;
            final double[] w = lut.cc4(new double[]{-1 - f, -f, 1 - f, 2 - f});
            double sum = 0, ramp = 0;
            for (int k = 0; k < 4; k++) {
                assertFalse("NaN weight at f=" + f, Double.isNaN(w[k]));
                sum += w[k];
                ramp += w[k] * (k - 1);
            }
            assertEquals("weights must sum to 1 at f=" + f, 1.0, sum, EPS);
            assertEquals("linear ramp must be reproduced at f=" + f, f, ramp, EPS);
        }
    }

    @Test
    public void testCc4LookupTablesReproduceRamp() {
        final LUT lut = new LUT(LUT.CC4P);
        lut.constructLUT();
        checkTable(lut.getKernel());

        final SimpleLUT simpleLut = new SimpleLUT(SimpleLUT.CC4P);
        simpleLut.constructLUT();
        checkTable(simpleLut.getKernel());
    }

    private static void checkTable(final DoubleMatrix kernel) {
        final int rows = kernel.getRows();
        assertEquals(LUT.getInterval() + 1, rows);
        assertEquals(4, kernel.getColumns());
        for (int i = 0; i < rows; i++) {
            final double f = i / (double) LUT.getInterval();
            double sum = 0, ramp = 0;
            for (int k = 0; k < 4; k++) {
                final double w = kernel.get(i, k);
                assertFalse("NaN weight in row " + i, Double.isNaN(w));
                sum += w;
                ramp += w * (k - 1);
            }
            assertEquals(1.0, sum, 1e-9);
            assertEquals("linear ramp must be reproduced in row " + i, f, ramp, 1e-9);
        }
    }
}
