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
package eu.esa.sar.insar.gpf.coregistration;

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Synthetic in-memory check of the Stack-Averaging statistics. Three dates with sample values
 * 1, 2 and 3 at every pixel give closed-form results:
 * <ul>
 *   <li>mean = 2</li>
 *   <li>population standard deviation = sqrt(((1-2)^2 + 0 + (3-2)^2) / 3) = sqrt(2/3) = 0.816497</li>
 *   <li>coefficient of variation = std / mean = 0.408248</li>
 * </ul>
 * The coefficient of variation used to be computed on the squared samples
 * (std(x^2)/mean(x^2) = 3.29983/4.66667 = 0.707107).
 */
public class TestStackAveragingStatistics {

    private static final int W = 4, H = 3;
    private static final double[] SAMPLES = {1.0, 2.0, 3.0};

    private static Product newStack() {
        final Product p = new Product("stack", "SLC", W, H);
        AbstractMetadata.addAbstractedMetadataHeader(p.getMetadataRoot());
        final String[] names = {"Amplitude_VV_ref_01Jan2020", "Amplitude_VV_sec_13Jan2020", "Amplitude_VV_sec_25Jan2020"};
        for (int k = 0; k < names.length; k++) {
            final float[] data = new float[W * H];
            java.util.Arrays.fill(data, (float) SAMPLES[k]);
            final Band b = new Band(names[k], ProductData.TYPE_FLOAT32, W, H);
            b.setUnit(Unit.AMPLITUDE);
            b.setRasterData(ProductData.createInstance(data));
            p.addBand(b);
        }
        return p;
    }

    private static double run(final String statistic) throws Exception {
        final StackAveragingOp op = new StackAveragingOp();
        op.setSourceProduct(newStack());
        op.setParameter("statistic", statistic);
        final Product target = op.getTargetProduct();
        final Band band = target.getBand("Amplitude_VV");
        assertNotNull("averaged band missing", band);
        final float[] out = new float[W * H];
        band.readPixels(0, 0, W, H, out);
        return out[W + 1];
    }

    /**
     * Same stack with 0 as no-data and the third date (3) missing at pixel (1,1), as outside a Secondary's
     * footprint. The statistics must use the two valid dates (1, 2), not average the 0 in.
     */
    private static float[] runWithGap(final String statistic, final int minValidDates) throws Exception {
        final Product src = newStack();
        for (Band b : src.getBands()) {
            b.setNoDataValue(0.0);
            b.setNoDataValueUsed(true);
        }
        src.getBand("Amplitude_VV_sec_25Jan2020").getRasterData().setElemFloatAt(W + 1, 0f);
        final StackAveragingOp op = new StackAveragingOp();
        op.setSourceProduct(src);
        op.setParameter("statistic", statistic);
        op.setParameter("minValidDates", minValidDates);
        final Band band = op.getTargetProduct().getBand("Amplitude_VV");
        final float[] out = new float[W * H];
        band.readPixels(0, 0, W, H, out);
        return out;
    }

    @Test
    public void testNoDataDatesAreLeftOut() throws Exception {
        assertEquals("mean of 1, 2", 1.5, runWithGap("Mean Average", 1)[W + 1], 1e-6);
        assertEquals("mean elsewhere unchanged", 2.0, runWithGap("Mean Average", 1)[0], 1e-6);
        assertEquals("min ignores the 0", 1.0, runWithGap("Minimum", 1)[W + 1], 1e-6);
        assertEquals("max of 1, 2", 2.0, runWithGap("Maximum", 1)[W + 1], 1e-6);
        assertEquals("population std of 1, 2", 0.5, runWithGap("Standard Deviation", 1)[W + 1], 1e-6);
        assertEquals("cv of 1, 2", 0.5 / 1.5, runWithGap("Coefficient of Variation", 1)[W + 1], 1e-6);
    }

    /**
     * Stack of three dates with geophysical values 1, 2, 3, where date {@code gapDate} holds {@code gapRaw} at
     * pixel (1,1). Each band gets its own data type, scaling and no-data value.
     */
    private static Product stackWithGap(final int dataType, final double scaling, final double[] noData,
                                        final int gapDate, final double gapRaw) {
        final Product p = new Product("stack", "SLC", W, H);
        AbstractMetadata.addAbstractedMetadataHeader(p.getMetadataRoot());
        final String[] names = {"Amplitude_VV_ref_01Jan2020", "Amplitude_VV_sec_13Jan2020", "Amplitude_VV_sec_25Jan2020"};
        for (int k = 0; k < names.length; k++) {
            final ProductData data = ProductData.createInstance(dataType, W * H);
            for (int i = 0; i < W * H; i++) {
                data.setElemDoubleAt(i, SAMPLES[k] / scaling);
            }
            if (k == gapDate) {
                data.setElemDoubleAt(W + 1, gapRaw);
            }
            final Band b = new Band(names[k], dataType, W, H);
            b.setUnit(Unit.AMPLITUDE);
            b.setScalingFactor(scaling);
            b.setRasterData(data);
            b.setNoDataValue(noData[k]);
            b.setNoDataValueUsed(true);
            p.addBand(b);
        }
        return p;
    }

    private static float[] run(final Product src, final String statistic, final int minValidDates) throws Exception {
        final StackAveragingOp op = new StackAveragingOp();
        op.setSourceProduct(src);
        op.setParameter("statistic", statistic);
        op.setParameter("minValidDates", minValidDates);
        final float[] out = new float[W * H];
        op.getTargetProduct().getBand("Amplitude_VV").readPixels(0, 0, W, H, out);
        return out;
    }

    /** Scaled int16 (raw -9999, geophysical -999.9): the no-data must be compared as the float the band yields. */
    @Test
    public void testScaledIntegerNoDataIsLeftOut() throws Exception {
        final Product src = stackWithGap(ProductData.TYPE_INT16, 0.1, new double[]{-9999, -9999, -9999}, 2, -9999);
        final float[] out = run(src, "Mean Average", 1);
        assertEquals(1.5, out[W + 1], 1e-5);
        assertEquals(2.0, out[0], 1e-5);
    }

    @Test
    public void testUnscaledIntegerNoDataIsLeftOut() throws Exception {
        final Product src = stackWithGap(ProductData.TYPE_INT16, 1.0, new double[]{-9999, -9999, -9999}, 2, -9999);
        assertEquals(1.5, run(src, "Mean Average", 1)[W + 1], 1e-6);
    }

    @Test
    public void testNaNNoDataIsLeftOut() throws Exception {
        final double nan = Double.NaN;
        final Product src = stackWithGap(ProductData.TYPE_FLOAT32, 1.0, new double[]{nan, nan, nan}, 2, nan);
        assertEquals(1.5, run(src, "Mean Average", 1)[W + 1], 1e-6);
    }

    /** Each date is checked against its own no-data value: -1 is no-data only for the third date. */
    @Test
    public void testEachBandUsesItsOwnNoData() throws Exception {
        final Product src = stackWithGap(ProductData.TYPE_FLOAT32, 1.0, new double[]{0, 0, -1}, 2, -1);
        assertEquals(1.5, run(src, "Mean Average", 1)[W + 1], 1e-6);
        assertEquals(1.0, run(src, "Minimum", 1)[W + 1], 1e-6);
    }

    /** An infinite no-data value must not break the expression ("Infinity" is not a literal). */
    @Test
    public void testInfiniteNoData() throws Exception {
        final double inf = Double.POSITIVE_INFINITY;
        final Product src = stackWithGap(ProductData.TYPE_FLOAT32, 1.0, new double[]{inf, inf, inf}, 2, inf);
        assertEquals(1.5, run(src, "Mean Average", 1)[W + 1], 1e-6);
    }

    /** A single valid date has no spread: Standard Deviation needs at least two, whatever minValidDates says. */
    @Test
    public void testStdNeedsTwoValidDates() throws Exception {
        final Product src = stackWithGap(ProductData.TYPE_FLOAT32, 1.0, new double[]{0, 0, 0}, 2, 0);
        src.getBand("Amplitude_VV_sec_13Jan2020").getRasterData().setElemDoubleAt(W + 1, 0);
        final float[] out = run(src, "Standard Deviation", 1);
        assertEquals("one valid date gives no-data (NaN)", Float.NaN, out[W + 1], 0.0f);
        assertEquals(Math.sqrt(2.0 / 3.0), out[0], 1e-6);
    }

    @Test
    public void testMinMaxHonourMinValidDates() throws Exception {
        final Product src = stackWithGap(ProductData.TYPE_FLOAT32, 1.0, new double[]{0, 0, 0}, 2, 0);
        assertEquals(Float.NaN, run(src, "Minimum", 3)[W + 1], 0.0f);
        assertEquals(Float.NaN, run(src, "Maximum", 3)[W + 1], 0.0f);
        assertEquals(3.0, run(src, "Maximum", 3)[0], 1e-6);
    }

    /** Fewer valid dates than required gives the output's no-data value. */
    @Test
    public void testTooFewValidDatesGiveNoData() throws Exception {
        final float[] out = runWithGap("Mean Average", 3);
        assertEquals("pixel with 2 of 3 dates", Float.NaN, out[W + 1], 0.0f);
        assertEquals("pixel with all dates", 2.0, out[0], 1e-6);
    }

    @Test
    public void testMean() throws Exception {
        assertEquals(2.0, run("Mean Average"), 1e-6);
    }

    @Test
    public void testPopulationStandardDeviation() throws Exception {
        assertEquals(Math.sqrt(2.0 / 3.0), run("Standard Deviation"), 1e-6);
    }

    @Test
    public void testCoefficientOfVariationIsStdOverMeanOfSamples() throws Exception {
        assertEquals(Math.sqrt(2.0 / 3.0) / 2.0, run("Coefficient of Variation"), 1e-6);
    }

    /** The output no-data is NaN, so a real statistic of 0 (no spread on a constant pixel) stays valid. */
    @Test
    public void testZeroStdIsNotNoData() throws Exception {
        final Product src = stackWithGap(ProductData.TYPE_FLOAT32, 1.0, new double[]{0, 0, 0}, -1, 0);
        for (Band b : src.getBands()) {
            for (int i = 0; i < W * H; i++) {
                b.getRasterData().setElemDoubleAt(i, 5.0);
            }
        }
        final StackAveragingOp op = new StackAveragingOp();
        op.setSourceProduct(src);
        op.setParameter("statistic", "Standard Deviation");
        final Band band = op.getTargetProduct().getBand("Amplitude_VV");
        assertTrue(Double.isNaN(band.getNoDataValue()));
        final float[] out = new float[W * H];
        band.readPixels(0, 0, W, H, out);
        assertEquals(0.0, out[W + 1], 0.0);
        assertTrue("0 is a valid standard deviation", band.isPixelValid(1, 1));
    }

    /**
     * An undated band (an elevation band added to the stack) is copied unchanged. Its group name used to be its own
     * name, so the statistic became a virtual band referring to itself, and dispose() then removed the band from
     * the source product.
     */
    @Test
    public void testUndatedBandIsCopiedAndKeptInTheSource() throws Exception {
        final Product src = newStack();
        final Band elevation = new Band("elevation", ProductData.TYPE_FLOAT32, W, H);
        final float[] heights = new float[W * H];
        java.util.Arrays.fill(heights, 123f);
        elevation.setRasterData(ProductData.createInstance(heights));
        src.addBand(elevation);
        final int sourceBands = src.getNumBands();

        final StackAveragingOp op = new StackAveragingOp();
        op.setSourceProduct(src);
        final Product target = op.getTargetProduct();
        final float[] out = new float[W * H];
        target.getBand("elevation").readPixels(0, 0, W, H, out);
        assertEquals(123f, out[W + 1], 0.0f);
        target.getBand("Amplitude_VV").readPixels(0, 0, W, H, out);
        assertEquals(2.0, out[W + 1], 1e-6);

        op.dispose();
        assertSame("the source keeps its own band", elevation, src.getBand("elevation"));
        assertEquals("only the operator's bands are removed", sourceBands, src.getNumBands());
    }

    /** Groups are matched by name, not by prefix: Amplitude_VV must not take in the Amplitude_VV_db dates. */
    @Test
    public void testGroupsWithACommonPrefixStaySeparate() throws Exception {
        final Product src = newStack();
        final String[] dbNames = {"Amplitude_VV_db_ref_01Jan2020", "Amplitude_VV_db_sec_13Jan2020"};
        for (String name : dbNames) {
            final float[] data = new float[W * H];
            java.util.Arrays.fill(data, 100f);
            final Band b = new Band(name, ProductData.TYPE_FLOAT32, W, H);
            b.setRasterData(ProductData.createInstance(data));
            src.addBand(b);
        }
        final StackAveragingOp op = new StackAveragingOp();
        op.setSourceProduct(src);
        final Product target = op.getTargetProduct();
        final float[] out = new float[W * H];
        target.getBand("Amplitude_VV").readPixels(0, 0, W, H, out);
        assertEquals("mean of 1, 2, 3 only", 2.0, out[W + 1], 1e-6);
        target.getBand("Amplitude_VV_db").readPixels(0, 0, W, H, out);
        assertEquals(100.0, out[W + 1], 1e-6);
    }

    @Test
    public void testStackBandNames() {
        assertTrue(StackAveragingOp.isStackBand("Sigma0_VV_mst_01Jan2020"));
        assertTrue(StackAveragingOp.isStackBand("Amplitude_VV_sec_13Jan2020"));
        assertTrue(StackAveragingOp.isStackBand("Sigma0_VV_01Jan2020"));
        assertFalse(StackAveragingOp.isStackBand("elevation"));
        assertFalse(StackAveragingOp.isStackBand("incident_angle"));
    }
}
