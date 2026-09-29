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
package eu.esa.sar.fex.gpf.oceantools;

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Unit tests for {@link ObjectDiscriminationOp}.
 */
public class TestObjectDiscriminationOp {

    @Test
    public void testSpiCreatesOperator() {
        final ObjectDiscriminationOp op = (ObjectDiscriminationOp) new ObjectDiscriminationOp.Spi().createOperator();
        assertNotNull(op);
    }

    @Test
    public void testOperatorMetadata() {
        final OperatorMetadata md = ObjectDiscriminationOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("Object-Discrimination", md.alias());
    }

    @Test
    public void testInitializeCopiesBandsToTargetProduct() {
        final Product srcProduct = createSourceProduct();

        final ObjectDiscriminationOp op = new ObjectDiscriminationOp();
        op.setSourceProduct(srcProduct);

        final Product target = op.getTargetProduct();
        assertNotNull(target);
        assertNotNull(target.getBand("Sigma0_VV"));
        // Target report file path is written to target metadata.
        final MetadataElement absTgt = AbstractMetadata.getAbstractedMetadata(target);
        final String reportPath = absTgt.getAttributeString(AbstractMetadata.target_report_file);
        assertNotNull(reportPath);
    }

    private Product createSourceProduct() {
        final Product srcProduct = TestUtils.createProduct("GRD", 10, 10);
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(srcProduct);
        absRoot.setAttributeDouble(AbstractMetadata.range_spacing, 10.0);
        absRoot.setAttributeDouble(AbstractMetadata.azimuth_spacing, 10.0);

        final Band band = TestUtils.createBand(srcProduct, "Sigma0_VV", 10, 10);
        band.setUnit(Unit.INTENSITY);
        return srcProduct;
    }
}
