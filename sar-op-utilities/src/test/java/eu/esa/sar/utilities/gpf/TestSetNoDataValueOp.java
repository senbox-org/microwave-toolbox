/*
 * Copyright (C) 2023 SkyWatch Space Applications Inc. https://www.skywatch.com
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
package eu.esa.sar.utilities.gpf;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Unit test for SetNoDataValueOp Operator.
 */
public class TestSetNoDataValueOp {

    private final static OperatorSpi spi = new SetNoDataValueOp.Spi();

    @Test
    public void testSetNoData() throws Exception {

        final Product sourceProduct = TestUtils.createProduct("type", 10, 10);
        TestUtils.createBand(sourceProduct, "band", 10, 10);

        final SetNoDataValueOp op = (SetNoDataValueOp) spi.createOperator();
        assertNotNull(op);
        op.setSourceProduct(sourceProduct);
        op.setParameter("noDataValue", 5.0);

        // get targetProduct: execute initialize()
        final Product targetProduct = op.getTargetProduct();
        TestUtils.verifyProduct(targetProduct, true, true, true);

        final Band band = targetProduct.getBandAt(0);
        assertNotNull(band);
        assertEquals(5.0, band.getNoDataValue(), 0.0f);

        // readPixels gets computeTiles to be executed
        final float[] floatValues = new float[4];
        band.readPixels(0, 0, 2, 2, floatValues, ProgressMonitor.NULL);

        // compare with expected outputs:
        final float[] expected = new float[] { 1.0f, 2.0f, 11.0f, 12.0f };
        assertArrayEquals(Arrays.toString(floatValues), expected, floatValues, 0.0001f);
    }
}
