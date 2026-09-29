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
 * Unit tests for {@link SaocomRemoveThermalNoiseOp}.
 * These tests cover operator metadata and sanity checks that do not require a
 * full SAOCOM input product. End-to-end processing is exercised by integration
 * tests that provide real product data.
 */
public class TestSaocomRemoveThermalNoiseOp extends ProcessorTest {

    private final static OperatorSpi spi = new SaocomRemoveThermalNoiseOp.Spi();

    @Test
    public void testSpiCreatesOperator() {
        final SaocomRemoveThermalNoiseOp op = (SaocomRemoveThermalNoiseOp) spi.createOperator();
        assertNotNull(op);
    }

    @Test
    public void testOperatorMetadata() {
        final OperatorMetadata md = SaocomRemoveThermalNoiseOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull("SaocomRemoveThermalNoiseOp must declare @OperatorMetadata", md);
        assertEquals("SAOCOM-Thermal-Noise-Removal", md.alias());
        assertEquals("Radar/Radiometric", md.category());
    }

    @Test
    public void testInitializeWithoutSourceProductThrows() {
        final SaocomRemoveThermalNoiseOp op = (SaocomRemoveThermalNoiseOp) spi.createOperator();
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
    public void testInitializeWithNonSaocomProductThrows() {
        final Product product = TestUtils.createProduct("GRD", 10, 10);
        final SaocomRemoveThermalNoiseOp op = (SaocomRemoveThermalNoiseOp) spi.createOperator();
        op.setSourceProduct(product);
        try {
            op.getTargetProduct();
            fail("Expected failure when source product is not a SAOCOM product");
        } catch (OperatorException expected) {
            // success
        } catch (RuntimeException expected) {
            // framework may wrap in other runtime exceptions
        }
    }
}
