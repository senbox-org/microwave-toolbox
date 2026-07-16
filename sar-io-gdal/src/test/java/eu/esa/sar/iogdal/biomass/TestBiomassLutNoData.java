/*
 * Copyright (C) 2025 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.iogdal.biomass;

import org.esa.snap.core.datamodel.TiePointGrid;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Regression test for the BIOMASS radiometric-LUT no-data handling.
 *
 * <p>The gammaNought / sigmaNought LUTs in the annotation NetCDF carry the product no-data
 * value ({@code -9999}) at grid nodes outside the valid swath. A {@link TiePointGrid} has no
 * concept of no-data, so if that fill reaches the grid, bilinear interpolation blends it into
 * the neighbouring pixels and yields physically-impossible <b>negative</b> gamma-nought along
 * the swath boundary. This is the source of the negative minima seen in SNAP but not in the
 * reference PFD v1.6.1 Sec.4.3.2 Python processor (which masks the fill before interpolating).
 *
 * <p>{@link BiomassProductDirectory#fillNoDataNodes} neutralises the fill; this test both
 * reproduces the defect (raw grid interpolates negative) and verifies the fix (sanitised grid
 * never does). It needs no product data.
 */
public class TestBiomassLutNoData {

    private static final double NO_DATA = -9999.0;

    // A 4x4 sub-sampled LUT of unit backscatter, with one fill node at (col=3, row=0) — as would
    // occur where the RGC grid extends just past the valid swath corner.
    private static float[] lutWithCornerFill() {
        final float[] lut = new float[16];
        java.util.Arrays.fill(lut, 1.0f);
        lut[0 * 4 + 3] = (float) NO_DATA;   // top-right corner node is fill
        return lut;
    }

    // Build the grid exactly as BiomassProductDirectory.addTiePointGrids does: offset 0.5,
    // subSampling = sceneSize / (gridSize - 1). Here scene is 12x12, grid is 4x4 -> subSampling 4.
    private static TiePointGrid grid(final float[] data) {
        final int gridW = 4, gridH = 4, sceneW = 12, sceneH = 12;
        final double subX = (double) sceneW / (gridW - 1);
        final double subY = (double) sceneH / (gridH - 1);
        return new TiePointGrid("gammaNought", gridW, gridH, 0.5f, 0.5f, subX, subY, data);
    }

    /** The finding: with the raw fill left in place, interpolation produces negative gamma0. */
    @Test
    public void rawFillProducesNegativeGamma0() {
        final TiePointGrid raw = grid(lutWithCornerFill());

        // Pixel (10,0) sits half-way between the valid node col2 and the fill node col3 on row 0.
        final double v = raw.getPixelDouble(10, 0);
        assertTrue("raw LUT must interpolate to a large negative value near the fill node, was " + v,
                v < -1000.0);

        // Confirm at least one negative exists over the scene (the reported symptom).
        assertTrue("raw grid should contain negative interpolated values", scanMin(raw) < 0.0);
    }

    /** The fix: after neutralising the fill, no pixel interpolates negative. */
    @Test
    public void sanitisedFillNeverNegative() {
        final float[] filled = BiomassProductDirectory.fillNoDataNodes(lutWithCornerFill(), 4, 4, NO_DATA);

        // The fill node must be gone and replaced by the mean of its valid neighbours (both 1.0).
        assertEquals(1.0f, filled[0 * 4 + 3], 1.0e-6f);
        for (float f : filled) {
            assertTrue("no node may retain the fill value", f != (float) NO_DATA);
        }

        final TiePointGrid fixed = grid(filled);
        assertTrue("sanitised grid must never interpolate negative, min was " + scanMin(fixed),
                scanMin(fixed) >= 0.0);
    }

    /**
     * The same fill defect corrupts the incidence-angle grid, which drives the
     * K/sin(theta) / K/cos(theta) calibration fallback. Raw fill drags the interpolated
     * angle out of the physical [0,90] range; the sanitised grid stays within it.
     */
    @Test
    public void incidenceAngleFillIsSanitised() {
        // Typical near-to-far incidence (~25-40 deg) with one fill node at the top-right corner.
        final float[] inc = {
                25f, 30f, 35f, (float) NO_DATA,
                25f, 30f, 35f, 40f,
                25f, 30f, 35f, 40f,
                25f, 30f, 35f, 40f
        };

        // Raw: the fill node blends in and pushes the interpolated angle far below 0 deg.
        assertTrue("raw fill must drive incidence angle out of [0,90], min was " + scanMin(grid(inc)),
                scanMin(grid(inc)) < 0.0);

        // Fixed: nearest-valid fill keeps every interpolated angle physical.
        final float[] filled = BiomassProductDirectory.fillNoDataNodes(inc, 4, 4, NO_DATA);
        for (float f : filled) {
            assertTrue("no fill may remain in the incidence grid", f != (float) NO_DATA);
        }
        final TiePointGrid fixed = grid(filled);
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (int y = 0; y < 12; y++) {
            for (int x = 0; x < 12; x++) {
                final double v = fixed.getPixelDouble(x, y);
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        assertTrue("sanitised incidence angle must stay within [0,90], was [" + min + ", " + max + "]",
                min >= 0.0 && max <= 90.0);
    }

    /** fillNoDataNodes is a no-op (identity copy) when the LUT has no fill. */
    @Test
    public void noFillIsUnchanged() {
        final float[] src = {1f, 2f, 3f, 4f};
        final float[] out = BiomassProductDirectory.fillNoDataNodes(src, 2, 2, NO_DATA);
        assertTrue("must return a distinct copy", out != src);
        for (int i = 0; i < src.length; i++) {
            assertEquals(src[i], out[i], 0.0f);
        }
    }

    /** Fill nodes with no immediately-valid neighbour are still resolved by iterative diffusion. */
    @Test
    public void interiorFillIsDiffused() {
        // 3x3 grid, all valid except the whole middle column -> the centre node has no valid
        // left/right neighbour on the first pass and must be filled across iterations.
        final float[] src = {
                1f, (float) NO_DATA, 1f,
                1f, (float) NO_DATA, 1f,
                1f, (float) NO_DATA, 1f
        };
        final float[] out = BiomassProductDirectory.fillNoDataNodes(src, 3, 3, NO_DATA);
        for (float f : out) {
            assertTrue("all fill must be resolved, found " + f, f != (float) NO_DATA);
            assertTrue("filled values must be finite and non-negative, found " + f, f >= 0.0f);
        }
    }

    private static double scanMin(final TiePointGrid tpg) {
        double min = Double.POSITIVE_INFINITY;
        for (int y = 0; y < 12; y++) {
            for (int x = 0; x < 12; x++) {
                min = Math.min(min, tpg.getPixelDouble(x, y));
            }
        }
        return min;
    }
}
