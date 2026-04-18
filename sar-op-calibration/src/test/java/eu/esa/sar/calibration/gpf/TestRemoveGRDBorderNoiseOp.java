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

import eu.esa.sar.commons.test.ProcessorTest;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link RemoveGRDBorderNoiseOp}.
 * Covers operator metadata and defensive-path behavior that does not require a
 * full Sentinel-1 GRD input product.
 */
public class TestRemoveGRDBorderNoiseOp extends ProcessorTest {

    private final static OperatorSpi spi = new RemoveGRDBorderNoiseOp.Spi();

    @Test
    public void testSpiCreatesOperator() {
        final RemoveGRDBorderNoiseOp op = (RemoveGRDBorderNoiseOp) spi.createOperator();
        assertNotNull(op);
    }

    @Test
    public void testOperatorMetadata() {
        final OperatorMetadata md = RemoveGRDBorderNoiseOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull("RemoveGRDBorderNoiseOp must declare @OperatorMetadata", md);
        assertEquals("Remove-GRD-Border-Noise", md.alias());
        assertEquals("Radar/Sentinel-1 TOPS", md.category());
    }

    @Test
    public void testInitializeWithoutSourceProductThrows() {
        final RemoveGRDBorderNoiseOp op = (RemoveGRDBorderNoiseOp) spi.createOperator();
        try {
            op.getTargetProduct();
            fail("Expected OperatorException when no source product is set");
        } catch (OperatorException expected) {
            // success
        } catch (RuntimeException expected) {
            // framework may wrap in other runtime exceptions
        }
    }

    @Test
    public void testInitializeRejectsNonSentinel1Product() {
        final Product product = TestUtils.createProduct("GRD", 10, 10);
        final RemoveGRDBorderNoiseOp op = (RemoveGRDBorderNoiseOp) spi.createOperator();
        op.setSourceProduct(product);
        try {
            op.getTargetProduct();
            fail("Expected failure when product is not a Sentinel-1 GRD");
        } catch (OperatorException expected) {
            // success
        } catch (RuntimeException expected) {
            // framework may wrap in other runtime exceptions
        }
    }
}
