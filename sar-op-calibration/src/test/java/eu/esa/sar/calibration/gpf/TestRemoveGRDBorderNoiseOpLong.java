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
package eu.esa.sar.calibration.gpf;

import com.bc.ceres.core.ProgressMonitor;
import com.bc.ceres.test.LongTestRunner;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Border-noise removal on real Sentinel-1 GRD products. Metrics follow the failure modes reported for the
 * previous operator by Ali et al. (2018) and Stasolla and Neyt (2018): residual dark stripes, holes in the
 * mask (granular masking) and over-masking.
 */
@RunWith(LongTestRunner.class)
public class TestRemoveGRDBorderNoiseOpLong {

    /**
     * Stripmap GRDM, IPF 2.32: before the IPF 2.90 border fix, so Auto runs the Bidirectional detection (stripmap from
     * IPF 2.90 on is passed through like IW/EW).
     */
    private static final File SM = new File(TestData.inputSAR +
            "S1/S1A_S1_GRDM_1SDV_20140607T172812_20140607T172836_000947_000EBD_7543.zip");
    /** IW GRDH, IPF 3.71: border pixels already zeroed by the processor. */
    private static final File IW_RECENT = new File(TestData.inputSAR +
            "S1/GRD/S1A_IW_GRDH_1SDV_20240508T062559_20240508T062624_053776_0688DB_1A13.SAFE.zip");

    @Test
    public void smUsesBidirectionalAndPassesMetrics() throws Exception {
        assumeTrue(SM.exists());
        final Product src = ProductIO.readProduct(SM);
        try {
            final RemoveGRDBorderNoiseOp op = new RemoveGRDBorderNoiseOp();
            op.setSourceProduct(src);
            final Product trg = op.getTargetProduct();
            final int[][] cuts = op.getCuts();
            final Band band = trg.getBand("Amplitude_VV");
            final int w = band.getRasterWidth(), h = band.getRasterHeight();
            System.out.println("SM cuts: left max " + max(cuts[0]) + ", right max " + max(cuts[1]) +
                    ", top max " + max(cuts[2]) + ", bottom max " + max(cuts[3]));
            assertTrue("cuts within the search limit", Math.max(max(cuts[0]), max(cuts[1])) <= 2000);

            final double stripe = stripeRatio(src.getBand("Amplitude_VV"), cuts[0]);
            System.out.println("SM residual stripe ratio " + stripe);
            assertTrue("residual dark stripe, ratio " + stripe, stripe > 0.5);

            final double maskedFraction = maskedFraction(src.getBand("Amplitude_VV"), band, w, h);
            System.out.println("SM masked fraction " + maskedFraction);
            assertTrue("over-masking " + maskedFraction, maskedFraction < 0.05);

            assertBorderMasked(band, cuts, w, h);
        } finally {
            src.dispose();
        }
    }

    @Test
    public void recentIwIsPassThrough() throws Exception {
        assumeTrue(IW_RECENT.exists());
        final Product src = ProductIO.readProduct(IW_RECENT);
        try {
            final RemoveGRDBorderNoiseOp op = new RemoveGRDBorderNoiseOp();
            op.setSourceProduct(src);
            final Product trg = op.getTargetProduct();
            final Band s = src.getBand("Amplitude_VV");
            final Band t = trg.getBand("Amplitude_VV");
            final int w = s.getRasterWidth(), h = s.getRasterHeight();
            for (int[] corner : new int[][]{{0, 0}, {w - 512, 0}, {0, h - 512}, {w - 512, h - 512}}) {
                final float[] a = new float[512 * 512];
                final float[] b = new float[512 * 512];
                s.readPixels(corner[0], corner[1], 512, 512, a, ProgressMonitor.NULL);
                t.readPixels(corner[0], corner[1], 512, 512, b, ProgressMonitor.NULL);
                assertArrayEquals(a, b, 0f);
            }
        } finally {
            src.dispose();
        }
    }

    /**
     * IPF 3.71 IW: the processor zero-fills the border, which gives the true border width per line. With detection
     * forced on, the cut must cover the zeros without masking much valid data (the regulariser's moving maximum
     * may add up to the border's variation within its window plus the buffer).
     */
    @Test
    public void bidirectionalOnRecentIwFollowsTheZeroFilledBorder() throws Exception {
        assumeTrue(IW_RECENT.exists());
        final Product src = ProductIO.readProduct(IW_RECENT);
        try {
            final RemoveGRDBorderNoiseOp op = new RemoveGRDBorderNoiseOp();
            op.setSourceProduct(src);
            op.setParameter("processRecentProducts", true);
            op.getTargetProduct();
            final long t0 = System.currentTimeMillis();
            final int[][] cuts = op.getCuts();
            System.out.println("IW prepass ms " + (System.currentTimeMillis() - t0));
            final Band s = src.getBand("Amplitude_VV");
            final int w = s.getRasterWidth(), h = s.getRasterHeight();
            final float[] row = new float[w];
            int lines = 0, covered = 0;
            final int[] excess = new int[h / 50 + 1];
            for (int y = 0; y < h; y += 50) {
                s.readPixels(0, y, w, 1, row, ProgressMonitor.NULL);
                int zeros = 0;
                while (zeros < w && row[w - 1 - zeros] == 0) {
                    zeros++;
                }
                if (zeros == 0 || zeros >= 2000) {
                    continue;
                }
                if (cuts[1][y] >= zeros) {
                    covered++;
                }
                excess[lines++] = cuts[1][y] - zeros;
            }
            final int[] e = Arrays.copyOf(excess, lines);
            Arrays.sort(e);
            System.out.println("IW right border: lines " + lines + ", covered " + covered + ", median excess " +
                    e[lines / 2] + ", 90% excess " + e[(int) (lines * 0.9)]);
            assertTrue("coverage " + covered + "/" + lines, covered >= 0.99 * lines);
            assertTrue("median excess " + e[lines / 2], e[lines / 2] <= 12);
        } finally {
            src.dispose();
        }
    }

    private static final File S1C = new File(TestData.inputSAR +
            "S1/GRD/S1C_IW_GRDH_1SDV_20250117T181329_20250117T181357_000624_000495_FC84.SAFE.zip");
    private static final File EW_COG = new File(TestData.inputSAR +
            "S1/COG/S1A_EW_GRDM_1SSH_20220225T025010_20220225T025115_042063_0502C8_6675_COG.SAFE.zip");

    /**
     * Zero-filled (IPF 3.x) products give the true border on all four edges. With detection forced on, the cuts
     * must cover the zeros and mask very little valid data, including next to the first/last lines (corners).
     */
    @Test
    public void allFourEdgesFollowTheZeroFilledBorder() throws Exception {
        for (File f : new File[]{S1C, EW_COG}) {
            assumeTrue(f.exists());
            final Product src = ProductIO.readProduct(f);
            try {
                final RemoveGRDBorderNoiseOp op = new RemoveGRDBorderNoiseOp();
                op.setSourceProduct(src);
                op.setParameter("processRecentProducts", true);
                final Product trg = op.getTargetProduct();
                final int[][] cuts = op.getCuts();
                final String bandName = src.getBand("Amplitude_VV") != null ? "Amplitude_VV" : "Amplitude_HH";
                final Band s = src.getBand(bandName);
                final Band t = trg.getBand(bandName);
                final int w = s.getRasterWidth(), h = s.getRasterHeight();
                // read whole blocks of lines (one tile row at a time) and sample every 7th line inside them;
                // line-by-line reads of a full-width product recompute each tile for every line
                final int block = 512;
                final float[] a = new float[w * block];
                final float[] b = new float[w * block];
                long valid = 0, masked = 0, edgeValid = 0, edgeMasked = 0;
                for (int y0 = 0; y0 < h; y0 += block) {
                    final int bh = Math.min(block, h - y0);
                    s.readPixels(0, y0, w, bh, a, ProgressMonitor.NULL);
                    t.readPixels(0, y0, w, bh, b, ProgressMonitor.NULL);
                    for (int dy = 0; dy < bh; dy += 7) {
                        final int y = y0 + dy;
                        final boolean nearFirstOrLast = y < 1500 || y >= h - 1500;
                        for (int x = 0; x < w; x++) {
                            final int i = dy * w + x;
                            if (a[i] != 0) {
                                valid++;
                                if (nearFirstOrLast) edgeValid++;
                                if (b[i] == 0) {
                                    masked++;
                                    if (nearFirstOrLast) edgeMasked++;
                                }
                            }
                        }
                    }
                }
                final double fraction = (double) masked / valid;
                final double edgeFraction = (double) edgeMasked / edgeValid;
                System.out.println(f.getName() + ": masked valid " + fraction + ", near first/last lines " + edgeFraction +
                        ", cuts max L/R/T/B " + max(cuts[0]) + "/" + max(cuts[1]) + "/" + max(cuts[2]) + "/" + max(cuts[3]));
                assertTrue(f.getName() + " masked valid " + fraction, fraction < 0.01);
                assertTrue(f.getName() + " masked valid near first/last lines " + edgeFraction, edgeFraction < 0.02);
            } finally {
                src.dispose();
            }
        }
    }

    private static int max(final int[] a) {
        return Arrays.stream(a).max().orElse(0);
    }

    /**
     * Median over lines (every 50th) of the mean amplitude of the 10 pixels just inside the left cut divided by
     * the mean of [cut+200, cut+400). An unremoved low-value zone gives about 0.05.
     */
    private static double stripeRatio(final Band src, final int[] left) throws Exception {
        final int w = src.getRasterWidth();
        final float[] row = new float[w];
        final double[] ratios = new double[src.getRasterHeight() / 50 + 1];
        int n = 0;
        for (int y = 0; y < src.getRasterHeight(); y += 50) {
            src.readPixels(0, y, w, 1, row, ProgressMonitor.NULL);
            final int c = left[y];
            if (c + 400 >= w) {
                continue;
            }
            final double ref = mean(row, c + 200, c + 400);
            if (ref > 0) {
                ratios[n++] = mean(row, c, c + 10) / ref;
            }
        }
        final double[] r = Arrays.copyOf(ratios, n);
        Arrays.sort(r);
        return r[n / 2];
    }

    private static double mean(final float[] a, final int from, final int to) {
        double s = 0;
        for (int i = from; i < to; i++) {
            s += a[i];
        }
        return s / (to - from);
    }

    /** Fraction of valid (non-zero) source pixels that the operator masked, on every 10th line. */
    private static double maskedFraction(final Band src, final Band trg, final int w, final int h) throws Exception {
        final float[] a = new float[w];
        final float[] b = new float[w];
        long valid = 0, masked = 0;
        for (int y = 0; y < h; y += 10) {
            src.readPixels(0, y, w, 1, a, ProgressMonitor.NULL);
            trg.readPixels(0, y, w, 1, b, ProgressMonitor.NULL);
            for (int x = 0; x < w; x++) {
                if (a[x] != 0) {
                    valid++;
                    if (b[x] == 0) {
                        masked++;
                    }
                }
            }
        }
        return (double) masked / valid;
    }

    /** Every pixel in the border region defined by the cuts is masked (no holes along the border). */
    private static void assertBorderMasked(final Band trg, final int[][] cuts, final int w, final int h) throws Exception {
        final float[] row = new float[w];
        for (int y = 0; y < h; y += 97) {
            trg.readPixels(0, y, w, 1, row, ProgressMonitor.NULL);
            for (int x = 0; x < w; x++) {
                final boolean border = x < cuts[0][y] || x >= w - cuts[1][y] || y < cuts[2][x] || y >= h - cuts[3][x];
                if (border) {
                    assertEquals("x=" + x + " y=" + y, 0f, row[x], 0f);
                }
            }
        }
    }
}
