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
package eu.esa.sar.sar.gpf.filtering.SpeckleFilters;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.gpf.Tile;
import org.junit.Test;

import java.awt.Rectangle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the default methods of the {@link SpeckleFilter} interface.
 * Uses an inline stub that implements only the abstract methods, so tests can
 * exercise the interface's default helpers directly.
 */
public class TestSpeckleFilter {

    private static final SpeckleFilter FILTER = new SpeckleFilter() {
        @Override
        public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) { }

        @Override
        public double[][] performFiltering(int x0, int y0, int w, int h, String[] srcBandNames) {
            return new double[0][0];
        }
    };

    // ---------- getSourceTileRectangle ----------

    @Test
    public void testGetSourceTileRectangleWithInteriorTile() {
        final Rectangle r = FILTER.getSourceTileRectangle(
                10, 10, 8, 8, /*half*/ 2, 2, /*image*/ 100, 100);

        assertEquals(8, r.x);
        assertEquals(8, r.y);
        assertEquals(12, r.width);
        assertEquals(12, r.height);
    }

    @Test
    public void testGetSourceTileRectangleClampsToImageOriginAndExtent() {
        final Rectangle r = FILTER.getSourceTileRectangle(
                0, 0, 5, 5, /*half*/ 3, 3, /*image*/ 4, 4);

        assertEquals("left clamp to 0", 0, r.x);
        assertEquals("top clamp to 0", 0, r.y);
        assertEquals("right clamp to image width", 4, r.width);
        assertEquals("bottom clamp to image height", 4, r.height);
    }

    @Test
    public void testGetSourceTileRectangleClampsRightBottom() {
        // Tile abuts right/bottom edge — half-windows would extend past the image.
        final Rectangle r = FILTER.getSourceTileRectangle(
                8, 8, 4, 4, /*half*/ 3, 3, /*image*/ 10, 10);

        // sx0 = max(0, 8-3)=5
        // sw  = min(8+4+3,10) - 5 = 10-5 = 5
        assertEquals(5, r.x);
        assertEquals(5, r.y);
        assertEquals(5, r.width);
        assertEquals(5, r.height);
    }

    // ---------- getMeanValue / getVarianceValue (simpler overloads) ----------

    @Test
    public void testGetMeanValueOfSimpleArray() {
        final double[] values = { 1.0, 2.0, 3.0, 4.0, 5.0 };
        assertEquals(3.0, FILTER.getMeanValue(values), 1e-9);
    }

    @Test
    public void testGetVarianceValueUsesSampleFormulaN_minus_1() {
        // sample variance of {1,2,3,4,5} = 2.5 (sum of squared deviations 10, divided by n-1=4)
        final double[] values = { 1.0, 2.0, 3.0, 4.0, 5.0 };
        final double mean = FILTER.getMeanValue(values);
        assertEquals(2.5, FILTER.getVarianceValue(values, mean), 1e-9);
    }

    @Test
    public void testGetVarianceValueSingleElementReturnsZero() {
        final double[] values = { 42.0 };
        // With length < 2 the method returns zero.
        assertEquals(0.0, FILTER.getVarianceValue(values, 42.0), 1e-9);
    }

    @Test
    public void testGetVarianceValueConstantArrayIsZero() {
        final double[] values = { 5.0, 5.0, 5.0, 5.0 };
        assertEquals(0.0, FILTER.getVarianceValue(values, 5.0), 1e-12);
    }

    // ---------- getMeanValue / getVarianceValue (no-data-aware overloads) ----------

    @Test
    public void testGetMeanValueWithNoDataSkipsPlaceholder() {
        final double[] values = { 1.0, -9999.0, 3.0, -9999.0, 5.0 };
        final double mean = FILTER.getMeanValue(values, /*numSamples*/ 3, /*noData*/ -9999.0);
        assertEquals(3.0, mean, 1e-9);
    }

    @Test
    public void testGetVarianceValueWithNoDataSkipsPlaceholder() {
        final double[] values = { 1.0, -9999.0, 3.0, -9999.0, 5.0 };
        final int numSamples = 3;
        final double mean = FILTER.getMeanValue(values, numSamples, -9999.0);
        final double var = FILTER.getVarianceValue(values, numSamples, mean, -9999.0);
        // variance of {1,3,5} with mean 3 = (4+0+4)/(3-1) = 4.0
        assertEquals(4.0, var, 1e-9);
    }

    @Test
    public void testGetVarianceValueWithSingleValidSampleReturnsZero() {
        final double[] values = { -9999.0, 7.0, -9999.0 };
        final double var = FILTER.getVarianceValue(values, 1, 7.0, -9999.0);
        assertEquals(0.0, var, 1e-12);
    }

    // ---------- computeMMSEWeight ----------

    @Test
    public void testComputeMMSEWeightOnConstantArrayIsZero() {
        final double[] constant = { 3.0, 3.0, 3.0, 3.0 };
        // var(constant) == 0, function returns 0 as the MMSE weight.
        assertEquals(0.0, FILTER.computeMMSEWeight(constant, 0.1), 1e-12);
    }

    @Test
    public void testComputeMMSEWeightWithinUnitInterval() {
        final double[] values = { 1.0, 2.0, 3.0, 4.0, 5.0 };
        final double weight = FILTER.computeMMSEWeight(values, /*sigmaVSqr*/ 0.1);
        assertTrue("MMSE weight should be in [0,1], got " + weight,
                weight >= 0.0 && weight <= 1.0);
    }

    @Test
    public void testComputeMMSEWeightClipsNegativeVarXToZero() {
        // Choose sigmaVSqr large enough that varX = varY - mean^2 * sigmaVSqr becomes negative.
        final double[] values = { 1.0, 1.0001, 0.9999 }; // tiny varY, non-zero mean
        final double weight = FILTER.computeMMSEWeight(values, /*sigmaVSqr*/ 100.0);
        assertEquals("clipping should yield exactly zero", 0.0, weight, 1e-12);
    }
}
