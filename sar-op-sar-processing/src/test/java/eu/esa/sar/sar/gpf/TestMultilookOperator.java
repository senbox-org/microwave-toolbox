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
package eu.esa.sar.sar.gpf;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.test.ProcessorTest;
import eu.esa.sar.commons.test.SARTests;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
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
 * Unit test for MultilookOperator.
 */
public class TestMultilookOperator extends ProcessorTest {

    private final static File inputFile = TestData.inputASAR_WSM;

    @Before
    public void setUp() throws Exception {
        try {
            // If the file does not exist: the test will be ignored
            assumeTrue("Input file" + inputFile + " does not exist - Skipping test", inputFile.exists());
        } catch (Exception e) {
            TestUtils.skipTest(this, e.getMessage());
            throw e;
        }
    }

    private final static OperatorSpi spi = new MultilookOp.Spi();
    private final static TestProcessor testProcessor = SARTests.createTestProcessor();

    private static final String[] productTypeExemptions = {"-","_BP", "XCA", "WVW", "WVI", "WVS", "WSS", "DOR_VOR_AX","OCN"};
    private static final String[] exceptionExemptions = {"not supported", "not intended", "not be map projected",
            "first be deburst","has no bands"};

    /**
     * Tests multi-look operator with a 4x16 "DETECTED" test product.
     *
     * @throws Exception general exception
     */
    @Test
    public void testMultilookOfRealImage() throws Exception {

        final Product sourceProduct = createTestProduct(16, 4);

        final MultilookOp op = (MultilookOp) spi.createOperator();
        assertNotNull(op);
        op.setSourceProduct(sourceProduct);
        op.setNumRangeLooks(4);
        MultilookOp.DerivedParams param = new MultilookOp.DerivedParams();
        param.nRgLooks = 4;
        op.getDerivedParameters(sourceProduct, param);
        op.setNumAzimuthLooks(param.nAzLooks);

        // get targetProduct: execute initialize()
        final Product targetProduct = op.getTargetProduct();
        TestUtils.verifyProduct(targetProduct, true, true);

        final Band band = targetProduct.getBandAt(0);
        assertNotNull(band);

        // readPixels: execute computeTiles()
        final float[] floatValues = new float[8];
        band.readPixels(0, 0, 4, 2, floatValues, ProgressMonitor.NULL);

        // compare with expected outputs:
        final float[] expectedValues = {11.0f, 15.0f, 19.0f, 23.0f, 43.0f, 47.0f, 51.0f, 55.0f};
        assertArrayEquals(Arrays.toString(floatValues), expectedValues, floatValues, 0.0001f);

        // compare updated metadata
        final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(targetProduct);

        TestUtils.attributeEquals(abs, AbstractMetadata.azimuth_looks, 2.0);
        TestUtils.attributeEquals(abs, AbstractMetadata.range_looks, 4.0);
        TestUtils.attributeEquals(abs, AbstractMetadata.azimuth_spacing, 4.0);
        TestUtils.attributeEquals(abs, AbstractMetadata.range_spacing, 2.0);
        TestUtils.attributeEquals(abs, AbstractMetadata.line_time_interval, 0.02);
        TestUtils.attributeEquals(abs, AbstractMetadata.first_line_time, "10-MAY-2008 20:32:46.890683");
    }

    /**
     * Processes a product and compares it to processed product known to be correct
     *
     * @throws Exception general exception
     */
    @Test
    public void testProcessing() throws Exception {
        try(final Product sourceProduct = TestUtils.readSourceProduct(inputFile)) {

            final MultilookOp op = (MultilookOp) spi.createOperator();
            assertNotNull(op);
            op.setSourceProduct(sourceProduct);

            // get targetProduct: execute initialize()
            final Product targetProduct = op.getTargetProduct();
            TestUtils.verifyProduct(targetProduct, true, true, true);

            final float[] expected = new float[]{668.0f, 564.0f, 574.0f};
            TestUtils.comparePixels(targetProduct, targetProduct.getBandAt(0).getName(), expected);
        }
    }

    /**
     * Creates a 4-by-16 test product as shown below:
     * 1  2  3  4  5  6  7  8  9 10 11 12 13 14 15 16
     * 17 18 19 20 21 22 23 24 25 26 27 28 29 30 31 32
     * 33 34 35 36 37 38 39 40 41 42 43 44 45 46 47 48
     * 49 50 51 52 53 54 55 56 57 58 59 60 61 62 63 64
     *
     * @param w width
     * @param h height
     * @return the created product
     */
    private static Product createTestProduct(final int w, final int h) {

        final Product testProduct = TestUtils.createProduct("ASA_APG_1P", w, h);

        // create a Band: band1
        final Band band1 = testProduct.addBand("band1", ProductData.TYPE_INT32);
        band1.setUnit(Unit.AMPLITUDE);
        final int[] intValues = new int[w * h];
        for (int i = 0; i < w * h; i++) {
            intValues[i] = i + 1;
        }
        band1.setData(ProductData.createInstance(intValues));

        // create abstracted metadata
        final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(testProduct);

        AbstractMetadata.setAttribute(abs, AbstractMetadata.SAMPLE_TYPE, "DETECTED");
        AbstractMetadata.setAttribute(abs, AbstractMetadata.MISSION, "ENVISAT");
        AbstractMetadata.setAttribute(abs, AbstractMetadata.srgr_flag, 0);
        AbstractMetadata.setAttribute(abs, AbstractMetadata.radar_frequency, 4.5f);
        AbstractMetadata.setAttribute(abs, AbstractMetadata.range_spacing, 0.5F);
        AbstractMetadata.setAttribute(abs, AbstractMetadata.azimuth_spacing, 2.0F);
        AbstractMetadata.setAttribute(abs, AbstractMetadata.azimuth_looks, 1);
        AbstractMetadata.setAttribute(abs, AbstractMetadata.range_looks, 1);
        AbstractMetadata.setAttribute(abs, AbstractMetadata.line_time_interval, 0.01F);
        AbstractMetadata.setAttribute(abs, AbstractMetadata.slant_range_to_first_pixel, 881619);
        AbstractMetadata.setAttribute(abs, AbstractMetadata.first_line_time,
                AbstractMetadata.parseUTC("10-MAY-2008 20:32:46.885684"));

        final float[] incidence_angle = new float[64];
        Arrays.fill(incidence_angle, 30.0f);
        testProduct.addTiePointGrid(new TiePointGrid(OperatorUtils.TPG_INCIDENT_ANGLE, 16, 4, 0, 0, 1, 1, incidence_angle));

        return testProduct;
    }

    @Test
    public void testProcessAllASAR() throws Exception {
        testProcessor.testProcessAllInPath(spi, SARTests.rootPathsASAR, "ENVISAT", productTypeExemptions, exceptionExemptions);
    }

    @Test
    public void testProcessAllERS() throws Exception {
        testProcessor.testProcessAllInPath(spi, SARTests.rootPathsERS, "ERS CEOS", productTypeExemptions, exceptionExemptions);
    }

    @Test
    public void testProcessAllALOS() throws Exception {
        testProcessor.testProcessAllInPath(spi, SARTests.rootPathsALOS, "ALOS PALSAR CEOS", productTypeExemptions, exceptionExemptions);
    }

    @Test
    public void testProcessAllRadarsat2() throws Exception {
        testProcessor.testProcessAllInPath(spi, SARTests.rootPathsRadarsat2, "RADARSAT-2", productTypeExemptions, exceptionExemptions);
    }

    @Test
    public void testProcessAllTerraSARX() throws Exception {
        testProcessor.testProcessAllInPath(spi, SARTests.rootPathsTerraSarX, "TerraSarX", productTypeExemptions, exceptionExemptions);
    }

    @Test
    public void testProcessAllCosmo() throws Exception {
        testProcessor.testProcessAllInPath(spi, SARTests.rootPathsCosmoSkymed, "CosmoSkymed", productTypeExemptions, exceptionExemptions);
    }

    @Test
    public void testProcessAllSentinel1() throws Exception {
        testProcessor.testProcessAllInPath(spi, SARTests.rootPathsSentinel1, "SENTINEL-1", productTypeExemptions, exceptionExemptions);
    }
}
