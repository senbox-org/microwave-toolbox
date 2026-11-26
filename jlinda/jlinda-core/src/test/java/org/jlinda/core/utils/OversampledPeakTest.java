package org.jlinda.core.utils;

import org.jblas.ComplexDoubleMatrix;
import org.jblas.DoubleMatrix;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * SarUtils.oversampledPeak must find the same maximum as taking the argmax of the full
 * SarUtils.oversample(...).getReal() grid, which is what the cross-correlation code did before.
 */
public class OversampledPeakTest {

    /** A correlation-like surface: one smooth peak at a sub-pixel position plus noise. */
    private static DoubleMatrix correlationChip(int rows, int cols, double peakRow, double peakCol,
                                                double width, double noise, Random random) {
        final DoubleMatrix chip = new DoubleMatrix(rows, cols);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                final double d2 = (r - peakRow) * (r - peakRow) + (c - peakCol) * (c - peakCol);
                chip.put(r, c, 0.9 * Math.exp(-d2 / (2 * width * width)) + noise * random.nextGaussian());
            }
        }
        return chip;
    }

    private static void assertSamePeak(DoubleMatrix chip, int factorRow, int factorCol) {
        final DoubleMatrix full = SarUtils.oversample(new ComplexDoubleMatrix(chip), factorRow, factorCol).getReal();
        final int index = full.argmax();

        final double[] peak = SarUtils.oversampledPeak(chip, factorRow, factorCol);

        assertEquals("row", full.indexRows(index), (int) peak[0]);
        assertEquals("column", full.indexColumns(index), (int) peak[1]);
        assertEquals("value", full.get(index), peak[2], 1e-9);
    }

    @Test
    public void matchesFullOversamplingOnRandomPeaks() {
        final Random random = new Random(42);
        for (int trial = 0; trial < 40; trial++) {
            final double peakRow = 6 + 4 * random.nextDouble();
            final double peakCol = 6 + 4 * random.nextDouble();
            final double width = 1.0 + 2.0 * random.nextDouble();
            assertSamePeak(correlationChip(16, 16, peakRow, peakCol, width, 0.02, random), 32, 32);
        }
    }

    @Test
    public void matchesWithDefaultEsdSettings() {
        // ESD defaults: 2 x fineWinAcc 16 = 32x32 chip, oversampling 128 (the 4096x4096 case)
        final Random random = new Random(7);
        assertSamePeak(correlationChip(32, 32, 15.37, 16.81, 2.0, 0.01, random), 128, 128);
    }

    @Test
    public void matchesWhenPeakIsAtTheChipEdge() {
        final Random random = new Random(3);
        assertSamePeak(correlationChip(16, 16, 0.2, 15.6, 1.5, 0.01, random), 16, 16);
        assertSamePeak(correlationChip(16, 16, 15.4, 0.3, 1.5, 0.01, random), 16, 16);
    }

    @Test
    public void matchesForUnequalFactorsAndShapes() {
        final Random random = new Random(11);
        assertSamePeak(correlationChip(16, 8, 7.3, 3.6, 1.5, 0.01, random), 16, 8);
        assertSamePeak(correlationChip(8, 16, 3.3, 8.6, 1.5, 0.01, random), 4, 32);
    }

    @Test
    public void factorOneInOneDimensionIsAnIdentityThere() {
        final Random random = new Random(5);
        assertSamePeak(correlationChip(16, 16, 7.6, 8.2, 2.0, 0.01, random), 1, 16);
        assertSamePeak(correlationChip(16, 16, 7.6, 8.2, 2.0, 0.01, random), 16, 1);
    }

    @Test
    public void returnsNullForNonFiniteChip() {
        final DoubleMatrix chip = correlationChip(8, 8, 4, 4, 1.5, 0.0, new Random(1));
        chip.put(2, 3, Double.NaN);
        assertNull(SarUtils.oversampledPeak(chip, 8, 8));
        // the full oversampled grid is all NaN too, so argmax finds nothing
        assertEquals(-1, SarUtils.oversample(new ComplexDoubleMatrix(chip), 8, 8).getReal().argmax());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPowerOfTwoSize() {
        SarUtils.oversampledPeak(new DoubleMatrix(12, 16), 8, 8);
    }
}
