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
package eu.esa.sar.sar.gpf.geometric;


import eu.esa.sar.calibration.gpf.CalibrationOp;
import eu.esa.sar.commons.test.ProcessorTest;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

/**
 * Unit test for Range Doppler.
 */
public class TestTerrainFlatteningOp extends ProcessorTest {

    private final static File inputFile1 = TestData.inputERS_IMP;
    private final static File inputFile2 = TestData.inputASAR_IMS;
    private final static File inputFile3 = TestData.inputASAR_APM;
    private final static File inputFile4 = TestData.inputASAR_APM;

    private static final Map<String, Product> PRODUCT_CACHE = new ConcurrentHashMap<>();

    private static Product loadCached(final File file) throws Exception {
        final String key = file.getAbsolutePath();
        Product p = PRODUCT_CACHE.get(key);
        if (p == null) {
            p = TestUtils.readSourceProduct(file);
            PRODUCT_CACHE.put(key, p);
        }
        return p;
    }

    @AfterClass
    public static void tearDownClass() {
        for (Product p : PRODUCT_CACHE.values()) {
            if (p != null) {
                p.dispose();
            }
        }
        PRODUCT_CACHE.clear();
    }

    @Before
    public void setUp() throws Exception {
        try {
            // If any of the file does not exist: the test will be ignored
            assumeTrue(inputFile1 + " not found", inputFile1.exists());
            assumeTrue(inputFile2 + " not found", inputFile2.exists());
            assumeTrue(inputFile3 + " not found", inputFile3.exists());
            assumeTrue(inputFile4 + " not found", inputFile4.exists());
        } catch (Exception e) {
            TestUtils.skipTest(this, e.getMessage());
            throw e;
        }
    }

    private final static OperatorSpi spi = new TerrainFlatteningOp.Spi();

    /**
     * Processes a IMP product and compares it to processed product known to be correct
     *
     * @throws Exception general exception
     */
    @Test
    public void testProcessIMP() throws Exception {
        final Product sourceProduct = loadCached(inputFile1);

        final CalibrationOp calOp = new CalibrationOp();
        calOp.setSourceProduct(sourceProduct);
        calOp.setParameter("outputBetaBand", true);
        calOp.setParameter("createBetaBand", true);

        final TerrainFlatteningOp op = (TerrainFlatteningOp) spi.createOperator();
        assertNotNull(op);
        op.setSourceProduct(calOp.getTargetProduct());

        // get targetProduct: execute initialize()
        final Product targetProduct = op.getTargetProduct();
        TestUtils.verifyProduct(targetProduct, true, true, true);

        final float[] expected = new float[]{0.14750221f, 0.15169495f, 0.12196117f, 0.15185618f};
        TestUtils.comparePixels(targetProduct, targetProduct.getBandAt(0).getName(), 200, 200, expected);
    }

    @Test
    public void testProcess_SimulatedImage() throws Exception {
        final Product sourceProduct = loadCached(inputFile1);

        final CalibrationOp calOp = new CalibrationOp();
        calOp.setSourceProduct(sourceProduct);
        calOp.setParameter("outputBetaBand", true);
        calOp.setParameter("createBetaBand", true);

        final TerrainFlatteningOp op = (TerrainFlatteningOp) spi.createOperator();
        assertNotNull(op);
        op.setSourceProduct(calOp.getTargetProduct());
        op.setParameter("outputSimulatedImage", true);

        // get targetProduct: execute initialize()
        final Product targetProduct = op.getTargetProduct();
        TestUtils.verifyProduct(targetProduct, true, true, true);

        final float[] expected = new float[]{2.685696f, 2.6963534f, 2.7251422f, 2.6842563f};
        TestUtils.comparePixels(targetProduct, targetProduct.getBandAt(1).getName(), 200, 200, expected);
    }

    @Test
    public void testProcess_SigmaNaught() throws Exception {
        final Product sourceProduct = loadCached(inputFile1);

        final CalibrationOp calOp = new CalibrationOp();
        calOp.setSourceProduct(sourceProduct);
        calOp.setParameter("outputBetaBand", true);
        calOp.setParameter("createBetaBand", true);

        final TerrainFlatteningOp op = (TerrainFlatteningOp) spi.createOperator();
        assertNotNull(op);
        op.setSourceProduct(calOp.getTargetProduct());
        op.setParameter("outputSigma0", true);

        // get targetProduct: execute initialize()
        final Product targetProduct = op.getTargetProduct();
        TestUtils.verifyProduct(targetProduct, true, true, true);

        final float[] expected = new float[]{0.13404073f, 0.13784982f, 0.110829115f, 0.13799441f};
        TestUtils.comparePixels(targetProduct, targetProduct.getBandAt(1).getName(), 200, 200, expected);
    }

    /**
     * Processes a IMS product and compares it to processed product known to be correct
     *
     * @throws Exception general exception
     */
    @Test
    public void testProcessIMS() throws Exception {
        final Product sourceProduct = loadCached(inputFile2);

        final CalibrationOp calOp = new CalibrationOp();
        calOp.setSourceProduct(sourceProduct);
        calOp.setParameter("outputBetaBand", true);

        final TerrainFlatteningOp op = (TerrainFlatteningOp) spi.createOperator();
        assertNotNull(op);
        op.setSourceProduct(calOp.getTargetProduct());

        // get targetProduct: execute initialize()
        final Product targetProduct = op.getTargetProduct();
        TestUtils.verifyProduct(targetProduct, false, false);
    }

    /**
     * Processes a APM product and compares it to processed product known to be correct
     *
     * @throws Exception general exception
     */
    @Test
    public void testProcessAPM() throws Exception {
        final Product sourceProduct = loadCached(inputFile3);

        final CalibrationOp calOp = new CalibrationOp();
        calOp.setSourceProduct(sourceProduct);
        calOp.setParameter("outputBetaBand", true);

        final TerrainFlatteningOp op = (TerrainFlatteningOp) spi.createOperator();
        assertNotNull(op);
        op.setSourceProduct(calOp.getTargetProduct());

        // get targetProduct: execute initialize()
        final Product targetProduct = op.getTargetProduct();
        TestUtils.verifyProduct(targetProduct, false, false);
    }

    /**
     * Processes a APM product and compares it to processed product known to be correct
     *
     * @throws Exception general exception
     */
    @Test
    public void testProcessCalibratedAPM() throws Exception {
        final Product sourceProduct = loadCached(inputFile4);

        final CalibrationOp calOp = new CalibrationOp();
        calOp.setSourceProduct(sourceProduct);
        calOp.setParameter("outputBetaBand", true);

        final TerrainFlatteningOp op = (TerrainFlatteningOp) spi.createOperator();
        assertNotNull(op);
        op.setSourceProduct(calOp.getTargetProduct());

        // get targetProduct: execute initialize()
        final Product targetProduct = op.getTargetProduct();
        TestUtils.verifyProduct(targetProduct, false, false);
    }
}
