package org.jlinda.core.delaunay;

import org.jlinda.core.Window;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Compares {@link GridTriangleInterpolator} with the Delaunay-based {@link TriangleInterpolator} on regular
 * source grids mapped to an output grid by synthetic radar-like mappings.
 */
public class GridTriangleInterpolatorTest {

    private static final double INVALID = -9999.0;
    private static final Window TILE = new Window(1000, 1000 + 127, 5000, 5000 + 511);   // 128 lines x 512 pixels
    private static final double RG_AZ_RATIO = 2.33 / 13.9;

    /** DEM grid with line / pixel positions and four quantities to interpolate. */
    private static final class Grid {
        final int rows, cols;
        final double[][] line, pixel, height;
        final double[][][] z;   // secondary az, secondary rg, lat, lon

        Grid(int rows, int cols) {
            this.rows = rows;
            this.cols = cols;
            line = new double[rows][cols];
            pixel = new double[rows][cols];
            height = new double[rows][cols];
            z = new double[4][rows][cols];
        }
    }

    /**
     * A 30 m DEM seen by an IW1-like geometry: ~2.1 lines per DEM row, ~7.4 slant pixels per DEM column, a
     * ~12 degree heading rotation, and a slant-range shift of -0.35 px per metre of height (layover where the
     * fore-slope exceeds ~21 m per DEM column). The secondary positions are a smooth function of the reference
     * position plus a baseline term proportional to the height.
     */
    private static Grid radarLikeGrid(final double hillAmplitude, final double hillWavelength) {
        final int rows = 130, cols = 130;
        final Grid g = new Grid(rows, cols);
        final double l0 = TILE.linelo - 60, p0 = TILE.pixlo - 220;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                final double h = hillAmplitude * Math.sin(2 * Math.PI * c / hillWavelength)
                        * Math.cos(2 * Math.PI * r / (1.7 * hillWavelength)) + 0.02 * r * c;
                final double line = l0 + 2.1 * r + 0.45 * c + 1e-4 * r * r;
                final double pixel = p0 + 7.4 * c - 1.6 * r + 2e-4 * c * c - 0.35 * h;
                g.height[r][c] = h;
                g.line[r][c] = line;
                g.pixel[r][c] = pixel;
                g.z[0][r][c] = line + 0.37 + 2e-6 * line * pixel / 100 + 2e-5 * h;
                g.z[1][r][c] = pixel - 3.21 + 1e-7 * pixel * pixel + 1.1e-4 * h;
                g.z[2][r][c] = 10.0 - r / 3600.0;
                g.z[3][r][c] = -70.0 + c / 3600.0;
            }
        }
        return g;
    }

    private static double[][][] run(final Grid g, final boolean grid, final GridTriangleInterpolator.Diagnostics diag)
            throws Exception {
        final int nx = (int) TILE.lines(), ny = (int) TILE.pixels();
        final double[][][] out = new double[4][nx][ny];
        final TriangleInterpolator.ZData[] zList = new TriangleInterpolator.ZData[4];
        for (int d = 0; d < 4; d++) {
            for (double[] row : out[d]) {
                Arrays.fill(row, INVALID);
            }
            zList[d] = new TriangleInterpolator.ZData(g.z[d], out[d]);
        }
        if (grid) {
            GridTriangleInterpolator.gridDataLinear(g.line, g.pixel, zList, TILE, RG_AZ_RATIO, 1, 1, INVALID, 0, diag);
        } else {
            TriangleInterpolator.gridDataLinear(g.line, g.pixel, zList, TILE, RG_AZ_RATIO, 1, 1, INVALID, 0);
        }
        return out;
    }

    private static GridTriangleInterpolator.Diagnostics newDiag() {
        final GridTriangleInterpolator.Diagnostics diag = new GridTriangleInterpolator.Diagnostics();
        diag.regularCount = new int[(int) TILE.lines()][(int) TILE.pixels()];
        diag.foldedCount = new int[(int) TILE.lines()][(int) TILE.pixels()];
        return diag;
    }

    /** Statistics of grid minus Delaunay for the secondary az / rg, split by layover. */
    private static final class Comparison {
        double maxDiffClean, maxDiffLayover;
        int clean, layover, onlyDelaunay, onlyGrid;
    }

    private static Comparison compare(final double[][][] grid, final double[][][] delaunay,
                                      final GridTriangleInterpolator.Diagnostics diag) {
        final Comparison cmp = new Comparison();
        for (int i = 0; i < grid[0].length; i++) {
            for (int j = 0; j < grid[0][0].length; j++) {
                final boolean inGrid = grid[0][i][j] != INVALID;
                final boolean inDelaunay = delaunay[0][i][j] != INVALID;
                if (inGrid && !inDelaunay) {
                    cmp.onlyGrid++;
                } else if (!inGrid && inDelaunay) {
                    cmp.onlyDelaunay++;
                }
                if (!inGrid || !inDelaunay) {
                    continue;
                }
                final double diff = Math.max(Math.abs(grid[0][i][j] - delaunay[0][i][j]),
                        Math.abs(grid[1][i][j] - delaunay[1][i][j]));
                final boolean layover = diag.foldedCount[i][j] > 0;
                if (layover) {
                    cmp.layover++;
                    cmp.maxDiffLayover = Math.max(cmp.maxDiffLayover, diff);
                } else {
                    cmp.clean++;
                    cmp.maxDiffClean = Math.max(cmp.maxDiffClean, diff);
                }
            }
        }
        return cmp;
    }

    @Test
    public void testAffineMappingIsReproducedExactly() throws Exception {
        final Grid g = new Grid(100, 110);
        for (int r = 0; r < g.rows; r++) {
            for (int c = 0; c < g.cols; c++) {
                g.line[r][c] = TILE.linelo - 60 + 2.1 * r + 0.45 * c;
                g.pixel[r][c] = TILE.pixlo - 120 + 7.4 * c - 1.6 * r;
                for (int d = 0; d < 4; d++) {
                    g.z[d][r][c] = (d + 1) * g.line[r][c] - 0.5 * d * g.pixel[r][c] + d;
                }
            }
        }
        final double[][][] out = run(g, true, null);
        for (int i = 0; i < out[0].length; i++) {
            for (int j = 0; j < out[0][0].length; j++) {
                final double line = TILE.linelo + i, pixel = TILE.pixlo + j;
                for (int d = 0; d < 4; d++) {
                    assertEquals((d + 1) * line - 0.5 * d * pixel + d, out[d][i][j], 1e-8);
                }
            }
        }
    }

    @Test
    public void testSmoothMappingMatchesDelaunay() throws Exception {
        final Grid g = radarLikeGrid(40, 70);
        final GridTriangleInterpolator.Diagnostics diag = newDiag();
        final Comparison cmp = compare(run(g, true, diag), run(g, false, null), diag);
        report("smooth", cmp, diag);
        assertEquals(0, diag.foldedTriangles);
        assertTrue(!diag.fallbackUsed);
        assertEquals(0, cmp.onlyDelaunay);
        assertEquals(0, cmp.layover);
        assertEquals((int) (TILE.lines() * TILE.pixels()), cmp.clean);
        assertTrue("max diff " + cmp.maxDiffClean, cmp.maxDiffClean <= 1e-3);
    }

    /** Steep fore-slopes compress the cells in range until the grid edges are no longer Delaunay edges. */
    @Test
    public void testForeshorteningMatchesDelaunay() throws Exception {
        final Grid g = radarLikeGrid(170, 70);
        final GridTriangleInterpolator.Diagnostics diag = newDiag();
        final Comparison cmp = compare(run(g, true, diag), run(g, false, null), diag);
        report("foreshortening", cmp, diag);
        assertEquals(0, diag.foldedTriangles);
        assertTrue(diag.flips > 0);
        assertEquals(0, cmp.onlyDelaunay);
        assertTrue("max diff " + cmp.maxDiffClean, cmp.maxDiffClean <= 1e-6);
    }

    @Test
    public void testLayoverHasNoNewHoles() throws Exception {
        final Grid g = radarLikeGrid(400, 30);
        final GridTriangleInterpolator.Diagnostics diag = newDiag();
        final Comparison cmp = compare(run(g, true, diag), run(g, false, null), diag);
        report("layover", cmp, diag);
        assertTrue(diag.foldedTriangles > 0);
        assertTrue(cmp.layover > 0);
        assertEquals(0, cmp.onlyDelaunay);
        // Near and inside folds the Delaunay triangles join nodes of different slopes; the two results then
        // differ by at most the spread of the secondary positions between those slopes (here up to
        // 1.1e-4 px/m * 800 m of height).
        assertTrue("max diff " + cmp.maxDiffClean, cmp.maxDiffClean <= 0.1);
        assertTrue("max diff " + cmp.maxDiffLayover, cmp.maxDiffLayover <= 0.1);
    }

    @Test
    public void testInvalidNodesAreFilledLikeDelaunay() throws Exception {
        final Grid g = radarLikeGrid(40, 70);
        // a hole of no-data nodes inside the tile and a few isolated ones
        for (int r = 40; r < 46; r++) {
            for (int c = 30; c < 38; c++) {
                g.line[r][c] = INVALID;
                g.pixel[r][c] = INVALID;
            }
        }
        g.line[20][60] = INVALID;
        g.pixel[55][70] = INVALID;

        final GridTriangleInterpolator.Diagnostics diag = newDiag();
        final double[][][] grid = run(g, true, diag);
        final double[][][] delaunay = run(g, false, null);
        final Comparison cmp = compare(grid, delaunay, diag);
        report("invalid nodes", cmp, diag);
        assertTrue(diag.fallbackUsed);
        assertTrue(diag.fallbackSamples > 0);
        assertEquals(0, cmp.onlyDelaunay);
        assertTrue("max diff " + cmp.maxDiffClean, cmp.maxDiffClean <= 1e-3);
    }

    @Test
    public void testTileOutsideGridLeavesOutputUntouched() throws Exception {
        final Grid g = radarLikeGrid(40, 70);
        for (int r = 0; r < g.rows; r++) {
            for (int c = 0; c < g.cols; c++) {
                g.line[r][c] += 10000;
            }
        }
        final GridTriangleInterpolator.Diagnostics diag = newDiag();
        final double[][][] out = run(g, true, diag);
        for (double[] row : out[0]) {
            for (double val : row) {
                assertEquals(INVALID, val, 0);
            }
        }
        assertTrue(!diag.fallbackUsed);
    }

    /** Timing of the two interpolators on the same input; informational. */
    @Test
    public void testTiming() throws Exception {
        final Grid g = radarLikeGrid(40, 70);
        for (int k = 0; k < 20; k++) {
            run(g, true, null);
            run(g, false, null);
        }
        final int reps = 50;
        long t0 = System.nanoTime();
        for (int k = 0; k < reps; k++) {
            run(g, false, null);
        }
        long t1 = System.nanoTime();
        for (int k = 0; k < reps; k++) {
            run(g, true, null);
        }
        long t2 = System.nanoTime();
        System.out.printf("GridTriangleInterpolator timing (%d x %d nodes -> 128 x 512): Delaunay %.2f ms, grid %.2f ms%n",
                g.rows, g.cols, (t1 - t0) / 1e6 / reps, (t2 - t1) / 1e6 / reps);
    }

    private static void report(final String name, final Comparison cmp, final GridTriangleInterpolator.Diagnostics diag) {
        System.out.printf("%s: clean %d (max |diff| %.3g px), layover %d (max |diff| %.3g px), only Delaunay %d, " +
                        "only grid %d, folded triangles %d, flips %d, fallback samples %d%n",
                name, cmp.clean, cmp.maxDiffClean, cmp.layover, cmp.maxDiffLayover, cmp.onlyDelaunay, cmp.onlyGrid,
                diag.foldedTriangles, diag.flips, diag.fallbackSamples);
    }
}
