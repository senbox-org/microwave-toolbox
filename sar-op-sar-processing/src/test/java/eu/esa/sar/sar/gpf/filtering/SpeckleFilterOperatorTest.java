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
package eu.esa.sar.sar.gpf.filtering;

import com.bc.ceres.annotation.STTM;
import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.test.ProcessorTest;
import eu.esa.sar.commons.test.SARTests;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.TestProcessor;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

/**
 * Unit test for SpeckleFilterOperator.
 */
public class SpeckleFilterOperatorTest extends ProcessorTest {

    private final static File inputFile = TestData.inputASAR_WSM;

    @Before
    public void setUp() throws Exception {
        try {
            // If any of the file does not exist: the test will be ignored
            assumeTrue("Input file" + inputFile + " does not exist - Skipping test", inputFile.exists());
        } catch (Exception e) {
            TestUtils.skipTest(this, e.getMessage());
            throw e;
        }
    }

    private final OperatorSpi spi = new SpeckleFilterOp.Spi();
    private final static TestProcessor testProcessor = SARTests.createTestProcessor();

    private static final String[] productTypeExemptions = {"_BP", "XCA", "WVW", "WVI", "WVS", "WSS", "DOR_VOR_AX","OCN", "ETAD"};
    private static final String[] exceptionExemptions = {"first be deburst","has no bands"};

    /**
     * Tests Mean speckle filter with a 4-by-4 test product.
     *
     * @throws Exception The exception.
     */
    @Test
    @STTM("SRM-147")
    public void testMeanFilter() throws Exception {
        final Product sourceProduct = createTestProduct(4, 4);

        final SpeckleFilterOp op = (SpeckleFilterOp) spi.createOperator();
        assertNotNull(op);
        op.setSourceProduct(sourceProduct);
        op.SetFilter("Mean");

        // get targetProduct gets initialize to be executed
        final Product targetProduct = op.getTargetProduct();
        TestUtils.verifyProduct(targetProduct, true, true);

        final Band band = targetProduct.getBandAt(0);
        assertNotNull(band);

        // readPixels gets computeTiles to be executed
        final float[] floatValues = new float[16];
        band.readPixels(0, 0, 4, 4, floatValues, ProgressMonitor.NULL);

        // compare with expected outputs:
        final float[] expectedValues = {3.5f, 4.0f, 5.0f, 5.5f, 5.5f, 6.0f, 7.0f, 7.5f, 9.5f, 10.0f, 11.0f, 11.5f,
                11.5f, 12.0f, 13.0f, 13.5f
        };
        assertArrayEquals(Arrays.toString(floatValues), expectedValues, floatValues, 0.0001f);
    }

    /**
     * Tests Mean speckle filter with a 4-by-4 test product.
     *
     * @throws Exception The exception.
     */
    @Test
    public void testBoxCarFilter() throws Exception {
        final Product sourceProduct = createTestProduct(4, 4);

        final SpeckleFilterOp op = (SpeckleFilterOp) spi.createOperator();
        assertNotNull(op);
        op.setSourceProduct(sourceProduct);
        op.SetFilter("Boxcar");

        // get targetProduct gets initialize to be executed
        final Product targetProduct = op.getTargetProduct();
        TestUtils.verifyProduct(targetProduct, true, true);

        final Band band = targetProduct.getBandAt(0);
        assertNotNull(band);

        // readPixels gets computeTiles to be executed
        final float[] floatValues = new float[16];
        band.readPixels(0, 0, 4, 4, floatValues, ProgressMonitor.NULL);

        // compare with expected outputs:
        final float[] expectedValues = {3.5f, 4.0f, 5.0f, 5.5f, 5.5f, 6.0f, 7.0f, 7.5f, 9.5f, 10.0f, 11.0f, 11.5f,
                11.5f, 12.0f, 13.0f, 13.5f
        };
        assertArrayEquals(Arrays.toString(floatValues), expectedValues, floatValues, 0.0001f);
    }

    /**
     * Tests Median speckle filter with a 4-by-4 test product.
     *
     * @throws Exception anything
     */
    @Test
    public void testMedianFilter() throws Exception {
        final Product sourceProduct = createTestProduct(4, 4);

        final SpeckleFilterOp op = (SpeckleFilterOp) spi.createOperator();
        assertNotNull(op);
        op.setSourceProduct(sourceProduct);
        op.SetFilter("Median");

        // get targetProduct gets initialize to be executed
        final Product targetProduct = op.getTargetProduct();
        TestUtils.verifyProduct(targetProduct, true, true);

        final Band band = targetProduct.getBandAt(0);
        assertNotNull(band);

        // readPixels gets computeTiles to be executed
        final float[] floatValues = new float[16];
        band.readPixels(0, 0, 4, 4, floatValues, ProgressMonitor.NULL);

        // compare with expected outputs
        final float[] expectedValues = {5.0f, 5.0f, 6.0f, 7.0f, 6.0f, 6.0f, 7.0f, 8.0f, 10.0f, 10.0f, 11.0f,
                12.0f, 13.0f, 13.0f, 14.0f, 15.0f};
        assertArrayEquals(Arrays.toString(floatValues), expectedValues, floatValues, 0.0001f);
    }

    /**
     * Tests Frost speckle filter with a 4-by-4 test product.
     *
     * @throws Exception anything
     */
    @Test
    public void testFrostFilter() throws Exception {
        final Product sourceProduct = createTestProduct(4, 4);

        final SpeckleFilterOp op = (SpeckleFilterOp) spi.createOperator();
        assertNotNull(op);
        op.setSourceProduct(sourceProduct);
        op.SetFilter("Frost");

        // get targetProduct gets initialize to be executed
        final Product targetProduct = op.getTargetProduct();
        TestUtils.verifyProduct(targetProduct, true, true);

        final Band band = targetProduct.getBandAt(0);
        assertNotNull(band);

        // readPixels gets computeTiles to be executed
        final float[] floatValues = new float[16];
        band.readPixels(0, 0, 4, 4, floatValues, ProgressMonitor.NULL);

        // compare with expected outputs
        final float[] expectedValues = {2.8108406f, 3.7109244f, 4.8278255f, 5.3469553f, 5.4066334f, 6.0f, 7.0f,
                7.5449896f, 9.473422f, 10.0f, 11.0f, 11.517614f, 11.532819f, 12.026602f, 13.022581f, 13.539467f};
        assertArrayEquals(Arrays.toString(floatValues), expectedValues, floatValues, 0.0001f);
    }

    /**
     * Tests Gamma speckle filter with a 4-by-4 test product.
     *
     * @throws Exception anything
     */
    @Test
    public void testGammaFilter() throws Exception {
        final Product sourceProduct = createTestProduct(4, 4);

        final SpeckleFilterOp op = (SpeckleFilterOp) spi.createOperator();
        assertNotNull(op);
        op.setSourceProduct(sourceProduct);
        op.SetFilter("Gamma Map");

        // get targetProduct gets initialize to be executed
        final Product targetProduct = op.getTargetProduct();
        TestUtils.verifyProduct(targetProduct, true, true);

        final Band band = targetProduct.getBandAt(0);
        assertNotNull(band);

        // readPixels gets computeTiles to be executed
        final float[] floatValues = new float[16];
        band.readPixels(0, 0, 4, 4, floatValues, ProgressMonitor.NULL);

        // compare with expected outputs
        final float[] expectedValues = {3.5f, 4.0f, 5.0f, 5.5f, 5.5f, 6.0f, 7.0f, 7.5f, 9.5f, 10.0f, 11.0f, 11.5f,
                11.5f, 12.0f, 13.0f, 13.5f};
        assertArrayEquals(Arrays.toString(floatValues), expectedValues, floatValues, 0.0001f);
    }

    /**
     * Tests Lee speckle filter with a 4-by-4 test product.
     *
     * @throws Exception anything
     */
    @Test
    public void testLeeFilter() throws Exception {
        final Product sourceProduct = createTestProduct(4, 4);

        final SpeckleFilterOp op = (SpeckleFilterOp) spi.createOperator();
        assertNotNull(op);
        op.setSourceProduct(sourceProduct);
        op.SetFilter("Lee");

        // get targetProduct gets initialize to be executed
        final Product targetProduct = op.getTargetProduct();
        TestUtils.verifyProduct(targetProduct, true, true);

        final Band band = targetProduct.getBandAt(0);
        assertNotNull(band);

        // readPixels gets computeTiles to be executed
        final float[] floatValues = new float[16];
        band.readPixels(0, 0, 4, 4, floatValues, ProgressMonitor.NULL);

        // compare with expected outputs
        final float[] expectedValues = {3.5f, 4.0f, 5.0f, 5.5f, 5.5f, 6.0f, 7.0f, 7.5f, 9.5f, 10.0f, 11.0f, 11.5f,
                11.5f, 12.0f, 13.0f, 13.5f};
        assertArrayEquals(Arrays.toString(floatValues), expectedValues, floatValues, 0.0001f);
    }

    /**
     * Tests IDAN filter with a 4-by-4 test product.
     *
     * @throws Exception anything
     */
    @Test
    public void testIDANFilter() throws Exception {
        final Product sourceProduct = createTestProduct(4, 4);

        final SpeckleFilterOp op = (SpeckleFilterOp) spi.createOperator();
        assertNotNull(op);
        op.setSourceProduct(sourceProduct);
        op.SetFilter("IDAN");

        // get targetProduct gets initialize to be executed
        final Product targetProduct = op.getTargetProduct();
        TestUtils.verifyProduct(targetProduct, true, true);

        final Band band = targetProduct.getBandAt(0);
        assertNotNull(band);

        // readPixels gets computeTiles to be executed
        final float[] floatValues = new float[16];
        band.readPixels(0, 0, 4, 4, floatValues, ProgressMonitor.NULL);

        // compare with expected outputs
        final float[] expectedValues = {4.5f, 4.5f, 5.0f, 6.5f, 5.0f, 5.0f, 6.5f, 7.0f, 8.0f, 8.0f, 9.0f, 9.0f, 9.5f, 9.5f, 10.5f, 10.5f};
        assertArrayEquals(Arrays.toString(floatValues), expectedValues, floatValues, 0.0001f);
    }

    /**
     * Tests refined Lee speckle filter with a 7-by-7 test product.
     *
     * @throws Exception anything
     */
    @Test
    public void testRefinedLeeFilter() throws Exception {
        final Product sourceProduct = createRefinedLeeTestProduct();

        final SpeckleFilterOp op = (SpeckleFilterOp) spi.createOperator();
        assertNotNull(op);
        op.setSourceProduct(sourceProduct);
        op.SetFilter("Refined Lee");

        // get targetProduct gets initialize to be executed
        final Product targetProduct = op.getTargetProduct();
        TestUtils.verifyProduct(targetProduct, true, true);

        final Band band = targetProduct.getBandAt(0);
        assertNotNull(band);

        // readPixels gets computeTiles to be executed
        final float[] floatValues = new float[49];
        band.readPixels(0, 0, 7, 7, floatValues, ProgressMonitor.NULL);

        // compare with expected outputs
        final float[] expectedValues = {
                117.125f, 115.6f, 108.98584f, 115.59759f, 111.30918f, 67.772064f, 77.42791f,
                120.4f, 115.62569f, 107.23333f, 99.46982f, 102.80414f, 83.69684f, 79.332756f,
                117.458336f, 115.96667f, 106.94328f, 122.64238f, 95.61863f, 80.35871f, 83.98103f,
                118.21429f, 116.314285f, 108.13215f, 115.28595f, 97.82989f, 76.39517f, 85.19781f,
                118.5f, 116.052475f, 105.69444f, 118.22289f, 103.49728f, 76.28018f, 81.13954f,
                120.008255f, 114.69612f, 106.46667f, 106.02027f, 98.75016f, 78.02842f, 85.9f,
                121.4375f, 119.85731f, 107.083336f, 106.53835f, 97.693275f, 88.09068f, 83.8125f
        };
        assertArrayEquals(Arrays.toString(floatValues), expectedValues, floatValues, 0.0001f);
    }

    /**
     * Tests None filter with a 4-by-4 test product.
     *
     * @throws Exception anything
     */
    @Test
    public void testNoneFilter() throws Exception {
        final Product sourceProduct = createTestProduct(4, 4);

        final SpeckleFilterOp op = (SpeckleFilterOp) spi.createOperator();
        assertNotNull(op);
        op.setSourceProduct(sourceProduct);
        op.SetFilter("None");

        // get targetProduct gets initialize to be executed
        final Product targetProduct = op.getTargetProduct();
        TestUtils.verifyProduct(targetProduct, true, true);

        final Band band = targetProduct.getBandAt(0);
        assertNotNull(band);

        // readPixels gets computeTiles to be executed
        final float[] floatValues = new float[16];
        band.readPixels(0, 0, 4, 4, floatValues, ProgressMonitor.NULL);

        // compare with expected outputs
        final float[] expectedValues = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f, 9.0f, 10.0f,
                11.0f, 12.0f, 13.0f, 14.0f, 15.0f, 16.0f};
        assertArrayEquals(Arrays.toString(floatValues), expectedValues, floatValues, 0.0001f);
    }

    /**
     * Creates a 4-by-4 test product as shown below for speckle filter tests:
     * 1  2  3  4
     * 5  6  7  8
     * 9 10 11 12
     * 13 14 15 16
     *
     * @param w width
     * @param h height
     * @return the new test product
     */
    private static Product createTestProduct(int w, int h) {
        final Product testProduct = TestUtils.createProduct("type", w, h);
        final Band band1 = new Band("band1", ProductData.TYPE_INT32, w, h);
        testProduct.addBand(band1);
        final Band band2 = new Band("band2", ProductData.TYPE_INT32, w + 5, h + 5); // different size from product
        testProduct.addBand(band2);

        final int[] intValues = new int[w * h];
        for (int i = 0; i < w * h; i++) {
            intValues[i] = i + 1;
        }
        band1.setData(ProductData.createInstance(intValues));
        band1.setUnit(Unit.AMPLITUDE);
        return testProduct;
    }

    private static Product createRefinedLeeTestProduct() {
        int w = 7;
        int h = 7;
        final Product testProduct = TestUtils.createProduct("type", w, h);
        final Band band1 = testProduct.addBand("band1", ProductData.TYPE_INT32);
        final int[] intValues = {99, 105, 124, 138, 128, 34, 62,
                105, 91, 140, 98, 114, 63, 31,
                107, 94, 128, 138, 96, 61, 82,
                137, 129, 136, 105, 100, 55, 85,
                144, 145, 113, 132, 119, 39, 50,
                102, 97, 102, 110, 103, 34, 53,
                107, 146, 115, 123, 101, 76, 56};

        band1.setData(ProductData.createInstance(intValues));
        band1.setUnit(Unit.AMPLITUDE);
        return testProduct;
    }

    /**
     * Processes a product and compares it to processed product known to be correct
     *
     * @throws Exception general exception
     */
    @Test
    public void testProcessing() throws Exception {
        try(final Product sourceProduct = TestUtils.readSourceProduct(inputFile)) {

            final SpeckleFilterOp op = (SpeckleFilterOp) spi.createOperator();
            assertNotNull(op);
            op.setSourceProduct(sourceProduct);

            // get targetProduct: execute initialize()
            final Product targetProduct = op.getTargetProduct();
            TestUtils.verifyProduct(targetProduct, true, true, true);

            final float[] expected = new float[]{658.8125f, 649.8499755859375f, 650.0417f};
            TestUtils.comparePixels(targetProduct, targetProduct.getBandAt(0).getName(), expected);
        }
    }

    @Test
    public void testProcessAllASAR() throws Exception {
        testProcessor.testProcessAllInPath(spi, SARTests.rootPathsASAR, productTypeExemptions, null);
    }

    @Test
    public void testProcessAllERS() throws Exception {
        testProcessor.testProcessAllInPath(spi, SARTests.rootPathsERS, null, null);
    }

    @Test
    public void testProcessAllALOS() throws Exception {
        testProcessor.testProcessAllInPath(spi, SARTests.rootPathsALOS, "ALOS PALSAR CEOS", null, null);
    }

    @Test
    public void testProcessAllRadarsat2() throws Exception {
        testProcessor.testProcessAllInPath(spi, SARTests.rootPathsRadarsat2, null, null);
    }

    @Test
    public void testProcessAllTerraSARX() throws Exception {
        testProcessor.testProcessAllInPath(spi, SARTests.rootPathsTerraSarX, null, null);
    }

    @Test
    public void testProcessAllCosmo() throws Exception {
        testProcessor.testProcessAllInPath(spi, SARTests.rootPathsCosmoSkymed, null, null);
    }

    @Test
    public void testProcessAllSentinel1() throws Exception {
        testProcessor.testProcessAllInPath(spi, SARTests.rootPathsSentinel1, productTypeExemptions, exceptionExemptions);
    }
}
