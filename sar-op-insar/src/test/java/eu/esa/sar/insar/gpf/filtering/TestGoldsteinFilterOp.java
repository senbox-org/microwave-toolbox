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
package eu.esa.sar.insar.gpf.filtering;

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link GoldsteinFilterOp}.
 * <p>
 * The no-data tests below use a 200x200 scene on purpose: the sliding window steps by
 * FFTSize/4 = 16 and the last block origin the loop can reach is 128 (128+64 = 192), so
 * rows/columns 192..199 are the trailing strip that the block loop used to miss. Since the
 * i/q no-data value of an interferogram is 0, unwritten pixels are indistinguishable from
 * no-data, which is what made the coherence mask look like it "broke" the no-data area.
 */
public class TestGoldsteinFilterOp {

    private static final int W = 200;
    private static final int H = 200;
    private static final double THRESHOLD = 0.2;

    /** How the source no-data region (if any) is filled. */
    private enum Fill { NONE, ZERO, PARTIAL, NAN, SENTINEL }

    @Test
    public void testSpiCreatesOperator() {
        final GoldsteinFilterOp op = (GoldsteinFilterOp) new GoldsteinFilterOp.Spi().createOperator();
        assertNotNull(op);
    }

    @Test
    public void testOperatorMetadata() {
        final OperatorMetadata md = GoldsteinFilterOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("GoldsteinPhaseFiltering", md.alias());
    }

    /**
     * Every pixel with valid source data must come out of the filter as valid data. The trailing
     * strip at the right/bottom scene edge used to be left at the buffer's initial 0, which is the
     * no-data value of an interferogram, so valid data silently turned into a no-data border.
     */
    @Test
    public void testValidDataIsNeverOutputAsNoData() throws Exception {
        final Scene scene = new Scene(Fill.NONE, 0.0, 0.9f, 0.9f);
        final Scene out = scene.filter(false);

        int lost = 0;
        for (int k = 0; k < W * H; k++) {
            if (out.i[k] == 0.0f && out.q[k] == 0.0f) lost++;
        }
        assertEquals("valid pixels output as no-data", 0, lost);
    }

    /**
     * The reported bug: with the coherence mask on, the no-data area filled with garbage. The mask
     * pass must not write into pixels the filter left as no-data, and must not treat the coherence
     * band's own no-data (0) as "low coherence" and restore raw, unfiltered samples there.
     */
    @Test
    public void testCoherenceMaskDoesNotRestoreRawSamplesWhereCoherenceIsNoData() throws Exception {
        // coherence is valid over the scene except in the trailing strip, where it is its no-data (0)
        final Scene scene = new Scene(Fill.NONE, 0.0, 0.9f, 0.0f);
        final Scene out = scene.filter(true);

        int raw = 0;
        for (int y = H - 8; y < H; y++) {
            for (int x = 0; x < W; x++) {
                final int k = y * W + x;
                if (out.i[k] == scene.i[k] && out.q[k] == scene.q[k]) raw++;
            }
        }
        assertEquals("raw unfiltered samples restored where coherence is no-data", 0, raw);
    }

    /**
     * A pixel that is no-data in either the i or the q band must stay no-data. The coherence mask
     * pass restored source samples with no validity check at all, so a half-no-data pixel came back
     * as valid data.
     */
    @Test
    public void testCoherenceMaskKeepsNoDataPixelsNoData() throws Exception {
        final Scene scene = new Scene(Fill.PARTIAL, 0.0, 0.1f, 0.1f);   // coherence below threshold
        final Scene out = scene.filter(true);

        int resurrected = 0;
        for (int k = 0; k < W * H; k++) {
            if (scene.noData[k] && (out.i[k] != 0.0f || out.q[k] != 0.0f)) resurrected++;
        }
        assertEquals("no-data pixels turned into valid data by the coherence mask", 0, resurrected);
    }

    /** Same invariant with the mask off, so the two paths agree on what no-data means. */
    @Test
    public void testNoDataPixelsStayNoDataWithoutCoherenceMask() throws Exception {
        final Scene scene = new Scene(Fill.ZERO, 0.0, 0.9f, 0.9f);
        final Scene out = scene.filter(false);

        int resurrected = 0;
        for (int k = 0; k < W * H; k++) {
            if (scene.noData[k] && (out.i[k] != 0.0f || out.q[k] != 0.0f)) resurrected++;
        }
        assertEquals("no-data pixels turned into valid data", 0, resurrected);
    }

    /**
     * NaN samples must be rejected as invalid. A value comparison against the no-data value never
     * matches NaN (NaN != NaN), so NaN used to enter the FFT and smear a block-sized hole of NaN
     * across the surrounding valid data.
     */
    @Test
    public void testNaNSamplesDoNotContaminateValidData() throws Exception {
        final Scene scene = new Scene(Fill.NAN, 0.0, 0.9f, 0.9f);
        final Scene out = scene.filter(false);

        int contaminated = 0;
        for (int k = 0; k < W * H; k++) {
            if (!scene.noData[k] && (Float.isNaN(out.i[k]) || Float.isNaN(out.q[k]))) contaminated++;
        }
        assertEquals("valid pixels contaminated by NaN source samples", 0, contaminated);
    }

    /** A no-data value other than 0 must be written to the target, not a bare 0. */
    @Test
    public void testNonZeroNoDataValueIsWrittenToTarget() throws Exception {
        final Scene scene = new Scene(Fill.SENTINEL, -9999.0, 0.9f, 0.9f);
        final Scene out = scene.filter(false);

        int wrong = 0;
        for (int k = 0; k < W * H; k++) {
            if (scene.noData[k] && out.i[k] != -9999.0f) wrong++;
        }
        assertEquals("no-data pixels not written with the band no-data value", 0, wrong);
    }

    /**
     * A tile thinner than the FFT block cannot be filtered at all. Such a tile must be reported as
     * no-data, not left holding the tile buffer's zeros, which would read as valid samples whenever
     * the no-data value is not 0.
     */
    @Test
    public void testTilesTooSmallToFilterAreReportedAsNoData() throws Exception {
        final Scene scene = new Scene(Fill.NONE, -9999.0, 0.9f, 0.9f);
        // 150x150 tiles over a 200x200 scene leave 50-pixel edge strips, below the 64 FFT size
        final Scene out = scene.filter(false, 150);

        int wrong = 0;
        for (int y = 150; y < H; y++) {
            for (int x = 150; x < W; x++) {
                if (out.i[y * W + x] != -9999.0f) wrong++;
            }
        }
        assertEquals("unfilterable tile not reported as no-data", 0, wrong);
    }

    /** Valid pixels below the coherence threshold still get their unfiltered samples back. */
    @Test
    public void testCoherenceMaskRestoresUnfilteredSamplesOnValidLowCoherencePixels() throws Exception {
        final Scene scene = new Scene(Fill.NONE, 0.0, 0.1f, 0.1f);   // everything below threshold
        final Scene out = scene.filter(true);

        int restored = 0;
        for (int y = 8; y < H - 8; y++) {          // interior, away from the edge strips
            for (int x = 8; x < W - 8; x++) {
                final int k = y * W + x;
                if (out.i[k] == scene.i[k] && out.q[k] == scene.q[k]) restored++;
            }
        }
        assertTrue("coherence mask no longer restores unfiltered samples: " + restored,
                restored > (H - 16) * (W - 16) * 9 / 10);
    }

    /**
     * A synthetic interferogram-like scene: valid complex data everywhere, optionally with a
     * no-data block in the middle, plus a coherence band whose value in the trailing edge strip
     * can differ from the rest of the scene.
     */
    private static final class Scene {
        private final float[] i = new float[W * H];
        private final float[] q = new float[W * H];
        private final float[] coh = new float[W * H];
        private final boolean[] noData = new boolean[W * H];
        private final double noDataValue;

        private Scene(final float[] i, final float[] q, final double noDataValue) {
            System.arraycopy(i, 0, this.i, 0, i.length);
            System.arraycopy(q, 0, this.q, 0, q.length);
            this.noDataValue = noDataValue;
        }

        private Scene(final Fill fill, final double noDataValue, final float cohValue,
                      final float cohEdgeValue) {
            this.noDataValue = noDataValue;
            final Random rng = new Random(7);
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    final int k = y * W + x;
                    final boolean inHole = fill != Fill.NONE && x >= 64 && x < 128 && y >= 64 && y < 128;
                    if (inHole) {
                        noData[k] = true;
                        switch (fill) {
                            case ZERO:
                                i[k] = 0f;
                                q[k] = 0f;
                                break;
                            case PARTIAL:                       // no-data in i only
                                i[k] = 0f;
                                q[k] = (float) (100.0 * rng.nextGaussian());
                                break;
                            case NAN:
                                i[k] = Float.NaN;
                                q[k] = Float.NaN;
                                break;
                            case SENTINEL:
                                i[k] = (float) noDataValue;
                                q[k] = (float) noDataValue;
                                break;
                            default:
                                break;
                        }
                    } else {
                        final double phase = 0.05 * x + 0.02 * y + rng.nextGaussian() * 0.3;
                        i[k] = (float) (100.0 * Math.cos(phase));
                        q[k] = (float) (100.0 * Math.sin(phase));
                    }
                    coh[k] = (y >= H - 8 || x >= W - 8) ? cohEdgeValue : cohValue;
                }
            }
        }

        /** Run the operator and return its i/q output. */
        private Scene filter(final boolean useCoherenceMask) throws Exception {
            return filter(useCoherenceMask, W);
        }

        /** Run the operator over the given square tile size and return its i/q output. */
        private Scene filter(final boolean useCoherenceMask, final int tileSize) throws Exception {
            final Product src = toProduct();
            src.setPreferredTileSize(tileSize, tileSize);
            final GoldsteinFilterOp op = (GoldsteinFilterOp) new GoldsteinFilterOp.Spi().createOperator();
            op.setSourceProduct(src);
            op.setParameter("useCoherenceMask", useCoherenceMask);
            op.setParameter("coherenceThreshold", THRESHOLD);
            final Product tgt = op.getTargetProduct();

            final float[] outI = new float[W * H];
            final float[] outQ = new float[W * H];
            tgt.getBand("i_ifg").readPixels(0, 0, W, H, outI);
            tgt.getBand("q_ifg").readPixels(0, 0, W, H, outQ);
            return new Scene(outI, outQ, noDataValue);
        }

        private Product toProduct() throws Exception {
            final Product product = TestUtils.createProduct("SLC", W, H);
            final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
            absRoot.setAttributeInt(AbstractMetadata.coregistered_stack, 1);
            absRoot.setAttributeString(AbstractMetadata.SAMPLE_TYPE, "COMPLEX");
            absRoot.setAttributeString(AbstractMetadata.MISSION, "TestMission");
            absRoot.setAttributeDouble("radar_frequency", 5405.0);
            product.setPreferredTileSize(W, H);

            addBand(product, "i_ifg", Unit.REAL, i, noDataValue);
            addBand(product, "q_ifg", Unit.IMAGINARY, q, noDataValue);
            addBand(product, "coh_ifg", Unit.COHERENCE, coh, 0.0);
            return product;
        }

        private static void addBand(final Product product, final String name, final String unit,
                                    final float[] data, final double noDataValue) {
            final Band band = new Band(name, ProductData.TYPE_FLOAT32, W, H);
            band.setUnit(unit);
            band.setNoDataValueUsed(true);
            band.setNoDataValue(noDataValue);
            band.setData(ProductData.createInstance(data));
            product.addBand(band);
        }
    }
}
