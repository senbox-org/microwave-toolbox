/*
 * Copyright (C) 2024 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.sentinel1.gpf;

import com.bc.ceres.annotation.STTM;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

public class TestSliceAssembly {

    private OperatorSpi spi;
    private SliceAssemblyOp sliceAssemblyOp;

    private final File S1_IW_GRD1 = new File(TestData.inputSAR + "S1/GRD/Hawaii_slices/S1A_IW_GRDH_1SDV_20180514T043029_20180514T043054_021896_025D31_BBDA.zip");
    private final File S1_IW_GRD2 = new File(TestData.inputSAR + "S1/GRD/Hawaii_slices/S1A_IW_GRDH_1SDV_20180514T043054_20180514T043119_021896_025D31_27FE.zip");

    @Before
    public void setUp() {
        spi = new SliceAssemblyOp.Spi();
        sliceAssemblyOp = new SliceAssemblyOp();

        assumeTrue(S1_IW_GRD1 + " not found", S1_IW_GRD1.exists());
        assumeTrue(S1_IW_GRD2 + " not found", S1_IW_GRD2.exists());
    }


    @Test
    public void testCreateOperator() {
        SliceAssemblyOp op = (SliceAssemblyOp) spi.createOperator();
        assertNotNull(op);
    }

    @Test
    @STTM("SNAP-3858")
    public void testDetermineSliceProducts() throws Exception {
        try(final Product product1 = TestUtils.readSourceProduct(S1_IW_GRD1)) {
            try(final Product product2 = TestUtils.readSourceProduct(S1_IW_GRD2)) {
                // Set the source products
                sliceAssemblyOp.setSourceProducts(product1, product2);
                sliceAssemblyOp.setTestProducts(new Product[] {product1, product2});

                // Call the method
                Product[] sortedProducts = sliceAssemblyOp.determineSliceProducts();

                // Verify the order of the products
                assertArrayEquals(new Product[]{product1, product2}, sortedProducts);
            }
        }

    }

    @Test
    public void testDetermineSliceProductsWithSingleProduct() throws IOException {
        try(final Product product1 = TestUtils.readSourceProduct(S1_IW_GRD1)) {
            // Set a single source product
            sliceAssemblyOp.setSourceProducts(product1);
            sliceAssemblyOp.setTestProducts(new Product[] {product1});

            // Verify that an exception is thrown
            assertThrows(Exception.class, () -> sliceAssemblyOp.determineSliceProducts());
        }
    }
}