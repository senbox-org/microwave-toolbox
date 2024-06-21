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
import eu.esa.sar.commons.test.ProcessorTest;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

/**
 * Unit test for RemodulateOp Operator.
 */
public class TestRemodulateOp extends ProcessorTest {

    private final static File inputFile = TestData.inputStackIMS;

    private final static OperatorSpi spi = new RemodulateOp.Spi();

    @Before
    public void setUp() throws Exception {
        try {
            // If the file does not exist: the test will be ignored
            assumeTrue(inputFile + " not found", inputFile.exists());
        } catch (Exception e) {
            TestUtils.skipTest(this, e.getMessage());
            throw e;
        }
    }

    @Test
    public void testIMS() throws Exception {
        final float[] expected = new float[] { 38.999535f, 2.8585785E-4f, 5.197046E-4f, -45.999943f };
        process(inputFile, expected);
    }

    /**
     * Processes a product and compares it to processed product known to be correct
     *
     * @param inputFile    the path to the input product
     * @throws Exception general exception
     */
    private void process(final File inputFile, final float[] expected) throws Exception {

        try(final Product sourceProduct = TestUtils.readSourceProduct(inputFile)) {

            final float[] origValues = new float[4];
            sourceProduct.getBandAt(2).readPixels(500, 500, 2, 2, origValues, ProgressMonitor.NULL);

            final DemodulateOp dmodOp = (DemodulateOp) new DemodulateOp.Spi().createOperator();
            assertNotNull(dmodOp);
            dmodOp.setSourceProduct(sourceProduct);

            // get targetProduct: execute initialize()
            final Product dmodProduct = dmodOp.getTargetProduct();
            TestUtils.verifyProduct(dmodProduct, true, true, true);

            final Band dmodSlvBand = dmodProduct.getBandAt(2);
            assertNotNull(dmodSlvBand);

            final float[] dmodExpected = new float[]{-23.118227f, -9.201061f, 52.344772f, 42.925545f};
            final float[] dmodValues = new float[4];
            dmodSlvBand.readPixels(500, 500, 2, 2, dmodValues, ProgressMonitor.NULL);

            // compare with expected outputs:
            assertArrayEquals(Arrays.toString(dmodValues), dmodExpected, dmodValues, 0.0001f);

            final RemodulateOp op = (RemodulateOp) spi.createOperator();
            assertNotNull(op);
            op.setSourceProduct(dmodProduct);

            // get targetProduct: execute initialize()
            final Product targetProduct = op.getTargetProduct();
            TestUtils.verifyProduct(targetProduct, true, true, true);

            final Band band = targetProduct.getBandAt(2);
            assertNotNull(band);

            // readPixels gets computeTiles to be executed
            final float[] floatValues = new float[4];
            band.readPixels(500, 500, 2, 2, floatValues, ProgressMonitor.NULL);

            // compare with expected outputs:
            assertArrayEquals(Arrays.toString(floatValues), expected, floatValues, 0.0001f);
        }
    }
}
