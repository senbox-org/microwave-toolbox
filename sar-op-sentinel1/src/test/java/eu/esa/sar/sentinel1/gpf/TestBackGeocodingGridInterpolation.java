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
package eu.esa.sar.sentinel1.gpf;

import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.jlinda.core.Window;
import org.jlinda.core.delaunay.GridTriangleInterpolator;
import org.jlinda.core.delaunay.TriangleInterpolator;
import org.junit.Test;

import javax.media.jai.JAI;
import javax.media.jai.TileCache;

import java.awt.Rectangle;
import java.awt.image.RenderedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Compares the grid-cell interpolation of Back-Geocoding with the original Delaunay interpolation on a real
 * Sentinel-1 IW SLC pair (the snap-validation tops_insar Venezuela pair and its Copernicus 30 m DEM), and times
 * both. Skipped when the data are not on this machine. Inputs can be overridden with the system properties
 * backgeocoding.test.ref, .sec, .dem, .bursts ("first-last", reference product) and .maxTiles (tiles spread
 * evenly over the bursts).
 */
public class TestBackGeocodingGridInterpolation {

    private static final File REF = new File(System.getProperty("backgeocoding.test.ref",
            "E:/Data/Venezuela/S1A_IW_SLC__1SDV_20260623T225050_20260623T225120_065103_0834C8_BAD5.SAFE.zip"));
    private static final File SEC = new File(System.getProperty("backgeocoding.test.sec",
            "E:/Data/Venezuela/S1C_IW_SLC__1SDV_20260624T224958_20260624T225025_008254_010515_304A.SAFE.zip"));
    private static final File DEM = new File(System.getProperty("backgeocoding.test.dem",
            "E:/TestData/dem/copernicus30_venezuela_orbit106.tif"));
    private static final String BURSTS = System.getProperty("backgeocoding.test.bursts", "4-5");
    private static final int MAX_TILES = Integer.getInteger("backgeocoding.test.maxTiles", 40);

    private static final double INVALID = -9999.0;

    /** Accumulated grid-vs-Delaunay statistics over all tile interpolations. */
    private static final class Stats implements BackGeocodingOp.TileGridListener {
        int tiles, fallbackTiles, foldedTriangles, flips, invalidCells;
        long clean, layover, onlyDelaunay, onlyGrid, fallbackSamples;
        double maxClean, maxLayover, maxLatLonClean;
        final List<Double> layoverDiffs = new ArrayList<>();
        long nanosDelaunay, nanosGrid;
        int nodes;
        // samples outside folded triangles, binned by approximate ground distance to the nearest folded one
        static final double[] DIST_BINS_M = {30, 60, 120, 250, 500, Double.POSITIVE_INFINITY};
        static final int FOLD_SEARCH_LINES = 40, FOLD_SEARCH_PIXELS = 130;   // ~550 m
        final long[] binCount = new long[DIST_BINS_M.length];
        final long[] binOver1e3 = new long[DIST_BINS_M.length];
        final double[] binMax = new double[DIST_BINS_M.length];
        long cleanOver1e4;

        @Override
        public synchronized void onTileGrid(final double[][] referenceAz, final double[][] referenceRg,
                                            final TriangleInterpolator.ZData[] dataList, final Window tileWindow,
                                            final double rgAzRatio) {
            try {
                compareTile(referenceAz, referenceRg, dataList, tileWindow, rgAzRatio);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private void compareTile(final double[][] referenceAz, final double[][] referenceRg,
                                 final TriangleInterpolator.ZData[] dataList, final Window tileWindow,
                                 final double rgAzRatio) throws Exception {
            final int nx = (int) tileWindow.lines(), ny = (int) tileWindow.pixels();
            final double[][][] outD = newOutput(dataList.length, nx, ny);
            final double[][][] outG = newOutput(dataList.length, nx, ny);
            final GridTriangleInterpolator.Diagnostics diag = new GridTriangleInterpolator.Diagnostics();
            diag.regularCount = new int[nx][ny];
            diag.foldedCount = new int[nx][ny];

            long t0 = System.nanoTime();
            TriangleInterpolator.gridDataLinear(referenceAz, referenceRg, zList(dataList, outD), tileWindow,
                    rgAzRatio, 1, 1, INVALID, 0);
            long t1 = System.nanoTime();
            GridTriangleInterpolator.gridDataLinear(referenceAz, referenceRg, zList(dataList, outG), tileWindow,
                    rgAzRatio, 1, 1, INVALID, 0, diag);
            long t2 = System.nanoTime();
            nanosDelaunay += t1 - t0;
            nanosGrid += t2 - t1;
            nodes += referenceAz.length * referenceAz[0].length;

            tiles++;
            if (tiles % 10 == 0) {
                System.out.printf(Locale.ROOT, "  %d tile interpolations, Delaunay %.0f ms, grid %.0f ms so far%n",
                        tiles, nanosDelaunay / 1e6, nanosGrid / 1e6);
            }
            foldedTriangles += diag.foldedTriangles;
            flips += diag.flips;
            invalidCells += diag.invalidCells;
            if (diag.fallbackUsed) {
                fallbackTiles++;
                fallbackSamples += diag.fallbackSamples;
            }

            final double[][] foldDist = distanceToFold(referenceAz, referenceRg, dataList, tileWindow, rgAzRatio);
            for (int i = 0; i < nx; i++) {
                for (int j = 0; j < ny; j++) {
                    final boolean inD = outD[0][i][j] != INVALID && outD[1][i][j] != INVALID;
                    final boolean inG = outG[0][i][j] != INVALID && outG[1][i][j] != INVALID;
                    if (inD && !inG) {
                        onlyDelaunay++;
                    } else if (inG && !inD) {
                        onlyGrid++;
                    }
                    if (!inD || !inG) {
                        continue;
                    }
                    final double diff = Math.max(Math.abs(outD[0][i][j] - outG[0][i][j]),
                            Math.abs(outD[1][i][j] - outG[1][i][j]));
                    if (diag.foldedCount[i][j] > 0) {
                        layover++;
                        maxLayover = Math.max(maxLayover, diff);
                        layoverDiffs.add(diff);
                    } else {
                        clean++;
                        maxClean = Math.max(maxClean, diff);
                        if (diff > 1e-4) {
                            cleanOver1e4++;
                        }
                        int bin = 0;
                        while (bin < DIST_BINS_M.length - 1 && foldDist[i][j] >= DIST_BINS_M[bin]) {
                            bin++;
                        }
                        binCount[bin]++;
                        binMax[bin] = Math.max(binMax[bin], diff);
                        if (diff > 1e-3) {
                            binOver1e3[bin]++;
                        }
                        maxLatLonClean = Math.max(maxLatLonClean, Math.max(
                                Math.abs(outD[2][i][j] - outG[2][i][j]), Math.abs(outD[3][i][j] - outG[3][i][j])));
                    }
                }
            }
        }

        /**
         * Chamfer distance in approximate ground metres (13.9 m per line, 4 m per IW slant-range pixel) from
         * every sample to the nearest sample covered by a folded triangle.
         */
        /**
         * Distance from every tile sample to the nearest folded triangle (including sub-pixel ones), with the
         * folds located on a window extended by FOLD_SEARCH_LINES / FOLD_SEARCH_PIXELS so that folds just outside
         * the tile count too.
         */
        private static double[][] distanceToFold(final double[][] referenceAz, final double[][] referenceRg,
                                                 final TriangleInterpolator.ZData[] dataList, final Window tile,
                                                 final double rgAzRatio) throws Exception {
            final Window ext = new Window(tile.linelo - FOLD_SEARCH_LINES, tile.linehi + FOLD_SEARCH_LINES,
                    tile.pixlo - FOLD_SEARCH_PIXELS, tile.pixhi + FOLD_SEARCH_PIXELS);
            final int ex = (int) ext.lines(), ey = (int) ext.pixels();
            final GridTriangleInterpolator.Diagnostics diag = new GridTriangleInterpolator.Diagnostics();
            diag.foldedCount = new int[ex][ey];
            diag.foldedNear = new int[ex][ey];
            final TriangleInterpolator.ZData[] z = {new TriangleInterpolator.ZData(dataList[0].z_in, new double[ex][ey])};
            GridTriangleInterpolator.gridDataLinear(referenceAz, referenceRg, z, ext, rgAzRatio, 1, 1, INVALID, 0, diag);
            for (int i = 0; i < ex; i++) {
                for (int j = 0; j < ey; j++) {
                    diag.foldedCount[i][j] += diag.foldedNear[i][j];
                }
            }
            final double[][] extDist = chamferDistance(diag.foldedCount, ex, ey);
            final int nx = (int) tile.lines(), ny = (int) tile.pixels();
            final double[][] d = new double[nx][ny];
            for (int i = 0; i < nx; i++) {
                System.arraycopy(extDist[i + FOLD_SEARCH_LINES], FOLD_SEARCH_PIXELS, d[i], 0, ny);
            }
            return d;
        }

        private static double[][] chamferDistance(final int[][] foldedCount, final int nx, final int ny) {
            final double a = 13.9, b = 4.0, c = Math.hypot(a, b);
            final double[][] d = new double[nx][ny];
            for (int i = 0; i < nx; i++) {
                for (int j = 0; j < ny; j++) {
                    d[i][j] = foldedCount[i][j] > 0 ? 0 : Double.POSITIVE_INFINITY;
                }
            }
            for (int i = 0; i < nx; i++) {
                for (int j = 0; j < ny; j++) {
                    double v = d[i][j];
                    if (j > 0) v = Math.min(v, d[i][j - 1] + b);
                    if (i > 0) {
                        v = Math.min(v, d[i - 1][j] + a);
                        if (j > 0) v = Math.min(v, d[i - 1][j - 1] + c);
                        if (j < ny - 1) v = Math.min(v, d[i - 1][j + 1] + c);
                    }
                    d[i][j] = v;
                }
            }
            for (int i = nx - 1; i >= 0; i--) {
                for (int j = ny - 1; j >= 0; j--) {
                    double v = d[i][j];
                    if (j < ny - 1) v = Math.min(v, d[i][j + 1] + b);
                    if (i < nx - 1) {
                        v = Math.min(v, d[i + 1][j] + a);
                        if (j < ny - 1) v = Math.min(v, d[i + 1][j + 1] + c);
                        if (j > 0) v = Math.min(v, d[i + 1][j - 1] + c);
                    }
                    d[i][j] = v;
                }
            }
            return d;
        }

        private static double[][][] newOutput(final int nz, final int nx, final int ny) {
            final double[][][] out = new double[nz][nx][ny];
            for (double[][] plane : out) {
                for (double[] row : plane) {
                    Arrays.fill(row, INVALID);
                }
            }
            return out;
        }

        private static TriangleInterpolator.ZData[] zList(final TriangleInterpolator.ZData[] in, final double[][][] out) {
            final TriangleInterpolator.ZData[] z = new TriangleInterpolator.ZData[in.length];
            for (int d = 0; d < in.length; d++) {
                z[d] = new TriangleInterpolator.ZData(in[d].z_in, out[d]);
            }
            return z;
        }

        double layoverPercentile(final double p) {
            if (layoverDiffs.isEmpty()) {
                return 0;
            }
            final double[] sorted = layoverDiffs.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            return sorted[Math.min(sorted.length - 1, (int) (p * sorted.length))];
        }
    }

    @Test
    public void testGridMatchesDelaunayOnRealPair() throws Exception {
        assumeTrue(REF + " not found", REF.exists());
        assumeTrue(SEC + " not found", SEC.exists());
        assumeTrue(DEM + " not found", DEM.exists());

        // keep the external DEM tiles cached: with the small default cache they are decoded again for every tile
        final TileCache tileCache = JAI.getDefaultInstance().getTileCache();
        final long previousCapacity = tileCache.getMemoryCapacity();
        tileCache.setMemoryCapacity(Math.max(previousCapacity, Runtime.getRuntime().maxMemory() / 2));
        try {
            compareOnRealPair();
        } finally {
            tileCache.setMemoryCapacity(previousCapacity);
        }
    }

    private static void compareOnRealPair() throws Exception {
        final String[] bursts = BURSTS.split("-");
        final Product refSplit = split(REF, Integer.parseInt(bursts[0]), Integer.parseInt(bursts[1]));
        final Product secSplit = split(SEC, 1, 9999);

        // 1) grid vs Delaunay on the inputs of every tile interpolation
        final Stats stats = new Stats();
        BackGeocodingOp.tileGridListener = stats;
        try {
            computeTiles(refSplit, secSplit, "grid");
        } finally {
            BackGeocodingOp.tileGridListener = null;
        }

        System.out.printf(Locale.ROOT, "Back-Geocoding grid vs Delaunay, %s bursts %s: %d tile interpolations, " +
                        "%.0f DEM nodes per tile%n", REF.getName(), BURSTS, stats.tiles, (double) stats.nodes / stats.tiles);
        System.out.printf(Locale.ROOT, "  outside layover: %d samples, max |diff| az/rg %.3g px, lat/lon %.3g deg%n",
                stats.clean, stats.maxClean, stats.maxLatLonClean);
        System.out.printf(Locale.ROOT, "  outside layover: %d samples with |diff| > 1e-4 px%n", stats.cleanOver1e4);
        for (int b = 0; b < Stats.DIST_BINS_M.length; b++) {
            System.out.printf(Locale.ROOT, "    distance to fold < %5.0f m: %9d samples, %7d with |diff| > 1e-3 px, max %.3g px%n",
                    Stats.DIST_BINS_M[b], stats.binCount[b], stats.binOver1e3[b], stats.binMax[b]);
        }
        System.out.printf(Locale.ROOT, "  layover: %d samples, max |diff| %.3g px, p50 %.3g, p99 %.3g px; folded triangles %d, flips %d%n",
                stats.layover, stats.maxLayover, stats.layoverPercentile(0.5), stats.layoverPercentile(0.99),
                stats.foldedTriangles, stats.flips);
        System.out.printf(Locale.ROOT, "  coverage: only Delaunay %d, only grid %d; invalid cells %d, " +
                "fallback tiles %d (%d samples)%n", stats.onlyDelaunay, stats.onlyGrid, stats.invalidCells,
                stats.fallbackTiles, stats.fallbackSamples);
        System.out.printf(Locale.ROOT, "  gridDataLinear per tile: Delaunay %.1f ms, grid %.1f ms (x%.1f)%n",
                stats.nanosDelaunay / 1e6 / stats.tiles, stats.nanosGrid / 1e6 / stats.tiles,
                (double) stats.nanosDelaunay / stats.nanosGrid);

        // 2) end-to-end tile computation time with each method: single thread, after a warm-up pass,
        //    interleaved and best of two to damp interference from other load on the machine
        computeTiles(refSplit, secSplit, "delaunay");
        long delaunayMs = Long.MAX_VALUE, gridMs = Long.MAX_VALUE;
        for (int run = 0; run < 2; run++) {
            delaunayMs = Math.min(delaunayMs, computeTiles(refSplit, secSplit, "delaunay"));
            gridMs = Math.min(gridMs, computeTiles(refSplit, secSplit, "grid"));
        }
        System.out.printf(Locale.ROOT, "  Back-Geocoding tiles end to end (best of 2): Delaunay %d ms, grid %d ms%n",
                delaunayMs, gridMs);

        refSplit.dispose();
        secSplit.dispose();

        assertTrue(stats.tiles > 0);
        // Inside and next to layover the Delaunay version joins nodes of different slopes (see
        // GridTriangleInterpolator); farther than ~60 m from any folded triangle both must agree.
        for (int b = 2; b < Stats.DIST_BINS_M.length; b++) {
            assertTrue("max diff " + stats.binMax[b] + " px at < " + Stats.DIST_BINS_M[b] + " m from a fold",
                    stats.binMax[b] <= 1e-3);
        }
        assertEquals("samples covered by Delaunay but not by the grid", 0, stats.onlyDelaunay);
    }

    private static Product split(final File file, final int firstBurst, final int lastBurst) throws Exception {
        final Product product = ProductIO.readProduct(file);
        final TOPSARSplitOp op = new TOPSARSplitOp();
        op.setSourceProduct(product);
        op.setParameter("subswath", "IW1");
        op.setParameter("selectedPolarisations", new String[]{"VV"});
        op.setParameter("firstBurstIndex", firstBurst);
        op.setParameter("lastBurstIndex", lastBurst);
        return op.getTargetProduct();
    }

    /** Computes the target tiles of one secondary band; returns the wall time in ms. */
    private static long computeTiles(final Product ref, final Product sec, final String method) {
        final String previous = System.getProperty(BackGeocodingOp.INTERPOLATION_PROPERTY);
        System.setProperty(BackGeocodingOp.INTERPOLATION_PROPERTY, method);
        try {
            final BackGeocodingOp op = new BackGeocodingOp();
            op.setSourceProducts(ref, sec);
            op.setParameter("demName", "External DEM");
            op.setParameter("externalDEMFile", DEM);
            op.setParameter("externalDEMNoDataValue", 0.0);
            op.setParameter("demResamplingMethod", "BILINEAR_INTERPOLATION");
            final Product target = op.getTargetProduct();

            Band band = null;
            for (Band b : target.getBands()) {
                if (b.getName().startsWith("i_") && !b.getName().contains("_mst_")) {
                    band = b;
                }
            }
            assertTrue("no secondary band", band != null);

            final RenderedImage image = band.getSourceImage();
            final int numTiles = image.getNumXTiles() * image.getNumYTiles();
            final int stride = Math.max(1, (int) Math.ceil((double) numTiles / MAX_TILES));
            System.out.printf(Locale.ROOT, "%s: %d of %d tiles of %d x %d%n", method, (numTiles + stride - 1) / stride,
                    numTiles, image.getTileWidth(), image.getTileHeight());
            final Rectangle bounds = new Rectangle(image.getMinX(), image.getMinY(), image.getWidth(), image.getHeight());
            final long t0 = System.currentTimeMillis();
            for (int t = 0; t < numTiles; t += stride) {
                final int tx = t % image.getNumXTiles(), ty = t / image.getNumXTiles();
                image.getData(new Rectangle(image.getMinX() + tx * image.getTileWidth(),
                        image.getMinY() + ty * image.getTileHeight(), image.getTileWidth(), image.getTileHeight())
                        .intersection(bounds));
            }
            final long ms = System.currentTimeMillis() - t0;
            target.dispose();
            return ms;
        } finally {
            if (previous == null) {
                System.clearProperty(BackGeocodingOp.INTERPOLATION_PROPERTY);
            } else {
                System.setProperty(BackGeocodingOp.INTERPOLATION_PROPERTY, previous);
            }
        }
    }
}
