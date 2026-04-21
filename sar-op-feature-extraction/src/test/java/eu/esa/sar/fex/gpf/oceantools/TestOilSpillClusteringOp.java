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
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Unit tests for {@link OilSpillClusteringOp}.
 */
public class TestOilSpillClusteringOp {

    @Test
    public void testSpiCreatesOperator() {
        final OilSpillClusteringOp op = (OilSpillClusteringOp) new OilSpillClusteringOp.Spi().createOperator();
        assertNotNull(op);
    }

    @Test
    public void testOperatorMetadata() {
        final OperatorMetadata md = OilSpillClusteringOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("Oil-Spill-Clustering", md.alias());
    }

    @Test
    public void testInitializeCreatesTargetAndMasks() {
        final Product srcProduct = createSourceProduct();

        final OilSpillClusteringOp op = new OilSpillClusteringOp();
        op.setSourceProduct(srcProduct);

        final Product target = op.getTargetProduct();
        assertNotNull(target);
        // The input oil-spill mask band must be carried over.
        assertNotNull(target.getBand("band1" + OilSpillDetectionOp.OILSPILLMASK_NAME));
        // addBitmasks is invoked during init and registers a detection mask.
        assertNotNull(target.getMaskGroup().get(
                "band1" + OilSpillDetectionOp.OILSPILLMASK_NAME + "_detection"));
    }

    private Product createSourceProduct() {
        final Product srcProduct = TestUtils.createProduct("GRD", 10, 10);
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(srcProduct);
        absRoot.setAttributeDouble(AbstractMetadata.range_spacing, 10.0);
        absRoot.setAttributeDouble(AbstractMetadata.azimuth_spacing, 10.0);

        // OilSpillDetectionOp.OILSPILLMASK_NAME is the naming suffix the clustering expects.
        final Band mask = new Band("band1" + OilSpillDetectionOp.OILSPILLMASK_NAME,
                ProductData.TYPE_INT8, 10, 10);
        srcProduct.addBand(mask);
        return srcProduct;
    }
}
