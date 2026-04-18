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
package eu.esa.sar.fex.gpf.forest;

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Unit tests for {@link ForestAreaDetectionOp}.
 */
public class TestForestAreaDetectionOp {

    @Test
    public void testSpiCreatesOperator() {
        final ForestAreaDetectionOp op = (ForestAreaDetectionOp) new ForestAreaDetectionOp.Spi().createOperator();
        assertNotNull(op);
    }

    @Test
    public void testOperatorMetadata() {
        final OperatorMetadata md = ForestAreaDetectionOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("Forest-Area-Detection", md.alias());
    }

    @Test
    public void testConstantsAreStable() {
        assertEquals("forest_mask", ForestAreaDetectionOp.FOREST_MASK_NAME);
        assertEquals("ratio", ForestAreaDetectionOp.RATIO_BAND_NAME);
    }

    @Test
    public void testInitializeAddsRatioBandAndForestMask() {
        final Product srcProduct = TestUtils.createProduct("GRD", 10, 10);
        final Band vv = TestUtils.createBand(srcProduct, "Sigma0_VV", 10, 10);
        vv.setUnit(Unit.INTENSITY);
        final Band hv = TestUtils.createBand(srcProduct, "Sigma0_HV", 10, 10);
        hv.setUnit(Unit.INTENSITY);

        final ForestAreaDetectionOp op = new ForestAreaDetectionOp();
        op.setSourceProduct(srcProduct);
        op.setParameter("nominatorBandName", "Sigma0_VV");
        op.setParameter("denominatorBandName", "Sigma0_HV");

        final Product target = op.getTargetProduct();
        assertNotNull(target);
        assertNotNull(target.getBand(ForestAreaDetectionOp.RATIO_BAND_NAME));
        assertNotNull(target.getMaskGroup().get(ForestAreaDetectionOp.FOREST_MASK_NAME));
    }
}
