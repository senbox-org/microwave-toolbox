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
package eu.esa.sar.fex.gpf.flood;

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link FloodDetectionOp}.
 */
public class TestFloodDetectionOp {

    @Test
    public void testSpiCreatesOperator() {
        final FloodDetectionOp op = (FloodDetectionOp) new FloodDetectionOp.Spi().createOperator();
        assertNotNull(op);
    }

    @Test
    public void testOperatorMetadata() {
        final OperatorMetadata md = FloodDetectionOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("Flood-Detection", md.alias());
    }

    @Test
    public void testMaskNameConstant() {
        assertEquals("_Flood", FloodDetectionOp.MASK_NAME);
    }

    @Test
    public void testInitializeAddsFloodMask() {
        final Product srcProduct = TestUtils.createProduct("GRD", 10, 10);
        final Band band = TestUtils.createBand(srcProduct, "Sigma0_VV", 10, 10);
        band.setUnit(Unit.INTENSITY);

        final FloodDetectionOp op = new FloodDetectionOp();
        op.setSourceProduct(srcProduct);

        final Product target = op.getTargetProduct();
        assertNotNull(target);
        assertNotNull("Source intensity band must be copied to target",
                target.getBand("Sigma0_VV"));
        assertTrue("Flood mask must be registered in the mask group",
                target.getMaskGroup().getNodeCount() >= 1);
    }
}
