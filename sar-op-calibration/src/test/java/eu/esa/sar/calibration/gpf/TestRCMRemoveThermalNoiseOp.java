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
 * Unit tests for {@link RCMRemoveThermalNoiseOp}.
 * These tests cover operator metadata and sanity checks that do not require a
 * full RCM input product. End-to-end processing is exercised by integration
 * tests that provide real product data.
 */
public class TestRCMRemoveThermalNoiseOp extends ProcessorTest {

    private final static OperatorSpi spi = new RCMRemoveThermalNoiseOp.Spi();

    @Test
    public void testSpiCreatesOperator() {
        final RCMRemoveThermalNoiseOp op = (RCMRemoveThermalNoiseOp) spi.createOperator();
        assertNotNull(op);
    }

    @Test
    public void testOperatorMetadata() {
        final OperatorMetadata md = RCMRemoveThermalNoiseOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull("RCMRemoveThermalNoiseOp must declare @OperatorMetadata", md);
        assertEquals("RCM-Thermal-Noise-Removal", md.alias());
        assertEquals("Radar/Radiometric", md.category());
    }

    @Test
    public void testInitializeWithoutSourceProductThrows() {
        final RCMRemoveThermalNoiseOp op = (RCMRemoveThermalNoiseOp) spi.createOperator();
        try {
            op.getTargetProduct();
            fail("Expected OperatorException when no source product is set");
        } catch (OperatorException expected) {
            // success
        } catch (RuntimeException expected) {
            // Operator framework may wrap the failure in a different RuntimeException
        }
    }

    @Test
    public void testInitializeWithNonRcmProductThrows() {
        final Product product = TestUtils.createProduct("GRD", 10, 10);
        final RCMRemoveThermalNoiseOp op = (RCMRemoveThermalNoiseOp) spi.createOperator();
        op.setSourceProduct(product);
        try {
            op.getTargetProduct();
            fail("Expected failure when source product is not an RCM product");
        } catch (OperatorException expected) {
            // success
        } catch (RuntimeException expected) {
            // framework may wrap in other runtime exceptions
        }
    }
}
