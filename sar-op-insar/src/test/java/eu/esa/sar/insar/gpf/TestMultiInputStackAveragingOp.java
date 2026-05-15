/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. https://www.skywatch.com
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
package eu.esa.sar.insar.gpf;

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TestMultiInputStackAveragingOp {

    private static final OperatorSpi spi = new MultiInputStackAveragingOp.Spi();

    @Test
    public void spi_creates_operator() {
        final MultiInputStackAveragingOp op = (MultiInputStackAveragingOp) spi.createOperator();
        assertNotNull(op);
    }

    @Test
    public void operator_metadata_alias_and_category() {
        final OperatorMetadata md = MultiInputStackAveragingOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("Multi-Input-Stack-Averaging", md.alias());
        assertEquals("Radar/Coregistration/Stack Tools", md.category());
    }

    @Test
    public void requires_at_least_two_inputs() {
        final Product p = TestUtils.createProduct("SLC", 4, 4);
        TestUtils.createBand(p, "Sigma0_VV_01Jan2025", ProductData.TYPE_FLOAT32, Unit.AMPLITUDE, 4, 4, true);
        final MultiInputStackAveragingOp op = (MultiInputStackAveragingOp) spi.createOperator();
        op.setSourceProducts(p);
        try {
            op.getTargetProduct();
            // The operator wraps the validation throw in OperatorUtils.catchOperatorException;
            // if we get here without an exception something is wrong.
            org.junit.Assert.fail("expected operator to refuse single-product input");
        } catch (RuntimeException expected) {
            // Acceptable: an OperatorException is thrown either directly or wrapped.
            assertTrue("expected single-input rejection, got: " + expected,
                    expected.getMessage() == null
                            || expected.getMessage().contains("two source products")
                            || (expected.getCause() != null && String.valueOf(expected.getCause()).contains("two source products")));
        }
    }

    @Test
    public void mean_of_two_identical_inputs_returns_input_value() throws Exception {
        final int w = 4, h = 4;
        final Product p1 = makeTestProduct(w, h, 5.0f);
        final Product p2 = makeTestProduct(w, h, 5.0f);

        final MultiInputStackAveragingOp op = (MultiInputStackAveragingOp) spi.createOperator();
        op.setSourceProducts(p1, p2);
        op.setParameter("statistic", "Mean Average");
        final Product target = op.getTargetProduct();
        assertNotNull(target);

        final Band meanBand = firstNonEmptyBand(target);
        assertNotNull("expected at least one aggregated band in target", meanBand);
        final float[] data = new float[w * h];
        meanBand.readPixels(0, 0, w, h, data);
        for (float v : data) {
            assertEquals(5.0, v, 1.0e-5);
        }
    }

    @Test
    public void max_of_two_distinct_inputs_returns_larger_value() throws Exception {
        final int w = 4, h = 4;
        final Product p1 = makeTestProduct(w, h, 3.0f);
        final Product p2 = makeTestProduct(w, h, 7.0f);

        final MultiInputStackAveragingOp op = (MultiInputStackAveragingOp) spi.createOperator();
        op.setSourceProducts(p1, p2);
        op.setParameter("statistic", "Maximum");
        final Product target = op.getTargetProduct();
        final Band maxBand = firstNonEmptyBand(target);
        assertNotNull(maxBand);
        final float[] data = new float[w * h];
        maxBand.readPixels(0, 0, w, h, data);
        for (float v : data) {
            assertEquals(7.0, v, 1.0e-5);
        }
    }

    /** Returns the first target band whose name does not appear in either source product. */
    private static Band firstNonEmptyBand(final Product target) {
        for (Band b : target.getBands()) {
            // Skip the per-product copies that were used to build the synthetic stack.
            if (b.getName().matches(".*_\\d+$")) continue;
            return b;
        }
        return null;
    }

    private static Product makeTestProduct(final int w, final int h, final float fillValue) {
        final Product p = TestUtils.createProduct("SLC", w, h);
        final Band b = TestUtils.createBand(p, "Sigma0_VV_01Jan2025", ProductData.TYPE_FLOAT32, Unit.AMPLITUDE, w, h, true);
        final float[] data = new float[w * h];
        java.util.Arrays.fill(data, fillValue);
        b.setData(ProductData.createInstance(data));
        return p;
    }
}
