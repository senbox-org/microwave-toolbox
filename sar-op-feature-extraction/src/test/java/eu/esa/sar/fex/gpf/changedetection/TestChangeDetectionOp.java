/*
 * Copyright (C) 2025 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.fex.gpf.changedetection;

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class TestChangeDetectionOp {


    @Test()
    public void test_log_ratio() throws Exception {
        Product srcProduct = createProduct();

        ChangeDetectionOp op = new ChangeDetectionOp();
        op.setSourceProduct(srcProduct);
        op.setParameter("outputRatio", true);
        op.setParameter("outputLogRatio", true);

        // get targetProduct: execute initialize()
        final Product targetProduct = op.getTargetProduct();
        TestUtils.verifyProduct(targetProduct, true, true, true);

        final Band band1 = targetProduct.getBandAt(0);
        assertNotNull(band1);
        assertEquals("ratio", band1.getName());
        assertEquals("ratio", band1.getUnit());

        final Band band2 = targetProduct.getBandAt(1);
        assertNotNull(band2);
        assertEquals("log_ratio", band2.getName());
        assertEquals("log_ratio", band2.getUnit());
    }

    private Product createProduct() {
        Product srcProduct = TestUtils.createProduct("GRD", 10, 10);
        Band band1 = TestUtils.createBand(srcProduct, "band1", 10, 10);
        band1.setUnit(Unit.AMPLITUDE);
        Band band2 = TestUtils.createBand(srcProduct, "band2", 10, 10);
        band2.setUnit(Unit.AMPLITUDE);

        return srcProduct;
    }
}
