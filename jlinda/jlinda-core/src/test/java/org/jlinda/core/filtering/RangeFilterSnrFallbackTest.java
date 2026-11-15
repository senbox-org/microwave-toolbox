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
package org.jlinda.core.filtering;

import org.jblas.ComplexDoubleMatrix;
import org.jblas.DoubleMatrix;
import org.jlinda.core.SLCImage;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;

/**
 * Adaptive range filtering: a line whose spectral-peak SNR is below the threshold must reuse the shift of the last
 * line that passed. The port declared {@code lastShift} inside the line loop and set it to the current shift, so
 * the fallback never took effect and noise-only lines were filtered with the shift of a random noise peak.
 */
public class RangeFilterSnrFallbackTest {

    private static final int ROWS = 40;
    private static final int COLS = 64;
    private static final int SIGNAL_ROWS = 20;
    private static final int FRINGE_BIN = 4; // interferogram spectral peak (shift) in the signal rows

    @Test
    public void testLowSnrLinesReuseLastGoodShift() throws Exception {
        final Random rnd = new Random(42);
        final ComplexDoubleMatrix ref = new ComplexDoubleMatrix(ROWS, COLS);
        final ComplexDoubleMatrix sec = new ComplexDoubleMatrix(ROWS, COLS);
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                // ifg = ref * conj(sec): a pure fringe in the first rows, unit-amplitude white phase noise after
                final double phase = r < SIGNAL_ROWS
                        ? 2 * Math.PI * FRINGE_BIN * c / COLS
                        : 2 * Math.PI * rnd.nextDouble();
                ref.put(r, c, new org.jblas.ComplexDouble(Math.cos(phase), Math.sin(phase)));
                sec.put(r, c, new org.jblas.ComplexDouble(1, 0));
            }
        }

        final RangeFilter rf = new RangeFilter();
        rf.setAlphaHamming(0.75);
        rf.setOvsFactor(1);
        rf.setNlMean(5);
        rf.setSNRthreshold(20);
        rf.setMetadata(new SLCImage());
        rf.setMetadata1(new SLCImage());
        rf.setData(ref);
        rf.setData1(sec);
        rf.defineParameters();
        rf.defineFilter();

        final DoubleMatrix filter = rf.getFilter();
        final double[] goodRow = filter.getRow(10).toArray();       // walking window rows 8..12: pure fringe
        final double[] noiseRow = filter.getRow(30).toArray();      // walking window rows 28..32: pure noise
        final double[] unfiltered = DoubleMatrix.ones(COLS).toArray();

        assertFalse("signal line must be filtered", java.util.Arrays.equals(unfiltered, goodRow));
        assertArrayEquals("low-SNR line must reuse the last good shift", goodRow, noiseRow, 1e-12);
    }
}
