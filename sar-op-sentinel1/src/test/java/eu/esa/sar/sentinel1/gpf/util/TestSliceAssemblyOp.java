/*
 * Copyright (C) 2014 by Array Systems Computing Inc. http://www.array.ca
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
package eu.esa.sar.sentinel1.gpf.util;

import eu.esa.sar.commons.test.ProcessorTest;
import eu.esa.sar.sentinel1.gpf.SliceAssemblyOp;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Unit test for MultilookOperator.
 */
public class TestSliceAssemblyOp extends ProcessorTest {

    private final static OperatorSpi spi = new SliceAssemblyOp.Spi();

    private final File slice1File = new File(TestData.inputSAR + "S1/GRD/Hawaii_slices/S1A_IW_GRDH_1SDV_20180514T043029_20180514T043054_021896_025D31_BBDA.zip");
    private final File slice2File = new File(TestData.inputSAR + "S1/GRD/Hawaii_slices/S1A_IW_GRDH_1SDV_20180514T043054_20180514T043119_021896_025D31_27FE.zip");

    private final File nonSliceFile = TestData.inputS1_GRD;

    @Before
    public void setUp() {
        // If any of the file does not exist: the test will be ignored
        assumeTrue(slice1File + " not found", slice1File.exists());
        assumeTrue(slice2File + " not found", slice2File.exists());
        assumeTrue(nonSliceFile + " not found", nonSliceFile.exists());
    }

    @Test
    public void testSingleProduct() throws Exception {

        try(final Product slice1Product = TestUtils.readSourceProduct(slice1File)) {

            final SliceAssemblyOp op = (SliceAssemblyOp) spi.createOperator();
            assertNotNull(op);
            op.setSourceProducts(slice1Product);

            try {
                // get targetProduct: execute initialize()
                final Product targetProduct = op.getTargetProduct();
                TestUtils.verifyProduct(targetProduct, false, false);
            } catch (Exception e) {
                String msg = e.getMessage();
                assertTrue(msg.equals("Slice assembly requires at least two consecutive slice products"));
            }
        }
    }

    @Test
    public void testNonSliceProduct() throws Exception {

        try(final Product slice1Product = TestUtils.readSourceProduct(slice1File)) {
            try(final Product nonSliceProduct = TestUtils.readSourceProduct(nonSliceFile)) {

                final SliceAssemblyOp op = (SliceAssemblyOp) spi.createOperator();
                assertNotNull(op);
                op.setSourceProducts(slice1Product, nonSliceProduct);

                try {
                    // get targetProduct: execute initialize()
                    final Product targetProduct = op.getTargetProduct();
                    TestUtils.verifyProduct(targetProduct, false, false);
                } catch (Exception e) {
                    String msg = e.getMessage();
                    assertTrue(msg.contains("is not a slice product"));
                }
            }
        }
    }

    @Test
    public void testOrder1_2() throws Exception {

        try(final Product slice1Product = TestUtils.readSourceProduct(slice1File)) {
            try (final Product slice2Product = TestUtils.readSourceProduct(slice2File)) {

                final SliceAssemblyOp op = (SliceAssemblyOp) spi.createOperator();
                assertNotNull(op);
                op.setSourceProducts(slice1Product, slice2Product);

                // get targetProduct: execute initialize()
                final Product targetProduct = op.getTargetProduct();
                TestUtils.verifyProduct(targetProduct, true, true, true);
            }
        }
    }

    @Test
    public void testOrder2_1() throws Exception {

        try(final Product slice1Product = TestUtils.readSourceProduct(slice1File)) {
            try (final Product slice2Product = TestUtils.readSourceProduct(slice2File)) {

                final SliceAssemblyOp op = (SliceAssemblyOp) spi.createOperator();
                assertNotNull(op);
                op.setSourceProducts(slice2Product, slice1Product);

                // get targetProduct: execute initialize()
                final Product targetProduct = op.getTargetProduct();
                TestUtils.verifyProduct(targetProduct, true, true, true);
            }
        }
    }

}
