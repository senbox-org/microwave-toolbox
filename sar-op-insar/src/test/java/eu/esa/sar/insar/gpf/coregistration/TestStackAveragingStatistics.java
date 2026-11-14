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
import static org.junit.Assert.assertNotNull;

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
}
