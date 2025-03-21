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
package eu.esa.sar.calibration.gpf;

import com.bc.ceres.annotation.STTM;
import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.test.SARTests;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.TestProcessor;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

/**
 * Unit test for RemoveThermalNoise Operator.
 */
public class TestRemoveThermalNoiseOp {

    private final static File inputFile1 = TestData.inputS1_GRD;
    private final static File inputFile2 = TestData.inputS1_StripmapSLC;

    @Before
    public void setUp() {
        // If any of the file does not exist: the test will be ignored
//        assumeTrue(inputFile1 + "not found", inputFile1.exists());
//        assumeTrue(inputFile2 + "not found", inputFile2.exists());
    }

    static {
        TestUtils.initTestEnvironment();
    }

    private final static OperatorSpi spi = new Sentinel1RemoveThermalNoiseOp.Spi();

    private String[] productTypeExemptions = {"OCN"};
    private String[] exceptionExemptions = {"not supported", "numbands is zero",
            "not a valid mission for Sentinel1 product",
            "WV is not a valid acquisition mode from: IW,EW,SM"};

    @Test
    public void testProcessingS1_GRD() throws Exception {
        try (final Product sourceProduct = TestUtils.readSourceProduct(inputFile1)) {
            try (final Product targetProduct = process(sourceProduct)) {

                final Band band = targetProduct.getBand("Intensity_VV");
                assertNotNull(band);

                final float[] floatValues = new float[8];
                band.readPixels(0, 0, 4, 2, floatValues, ProgressMonitor.NULL);

                assertEquals(1024.0, floatValues[0], 0.0001);
                assertEquals(1024.0, floatValues[1], 0.0001);
                assertEquals(1444.0, floatValues[2], 0.0001);
            }
        }
    }

    @Test
    public void testProcessingS1_StripmapSLC() throws Exception {
        try (final Product sourceProduct = TestUtils.readSourceProduct(inputFile2)) {
            try (final Product targetProduct = process(sourceProduct)) {

                final Band band = targetProduct.getBand("Intensity_VV");
                assertNotNull(band);

                final float[] floatValues = new float[8];
                band.readPixels(0, 0, 4, 2, floatValues, ProgressMonitor.NULL);

                assertEquals(629.0, floatValues[0], 0.0001);
                assertEquals(2362.0, floatValues[1], 0.0001);
                assertEquals(6065.0, floatValues[2], 0.0001);
            }
        }
    }

    @Test
    @STTM("SNAP-3862")
    public void testProcessingS1_TOPS_SLC() throws Exception {
        try (final Product sourceProduct = TestUtils.readSourceProduct(new File(
                "src/test/resources/S1A_IW_SLC__1SDV_20181115T125000_20181115T125015_024599_02B387_2799_split.dim"))) {

            sourceProduct.removeTiePointGrid(sourceProduct.getTiePointGrid("latitude"));
            sourceProduct.removeTiePointGrid(sourceProduct.getTiePointGrid("longitude"));
            sourceProduct.removeTiePointGrid(sourceProduct.getTiePointGrid("incident_angle"));
            sourceProduct.removeTiePointGrid(sourceProduct.getTiePointGrid("elevation_angle"));
            sourceProduct.removeTiePointGrid(sourceProduct.getTiePointGrid("slant_range_time"));
            sourceProduct.removeBand(sourceProduct.getBand("i_IW1_VH"));
            sourceProduct.removeBand(sourceProduct.getBand("q_IW1_VH"));
            sourceProduct.removeBand(sourceProduct.getBand("Intensity_IW1_VH"));

            final int w = sourceProduct.getSceneRasterWidth();
            final int h = sourceProduct.getSceneRasterHeight();
            final TiePointGrid latGrid = new TiePointGrid(OperatorUtils.TPG_LATITUDE, 2, 2, 0.5f, 0.5f,
                    w, h, new float[]{9.0802f, 9.235484f, 9.41406f, 9.569095f});
            final TiePointGrid lonGrid = new TiePointGrid(OperatorUtils.TPG_LONGITUDE, 2, 2, 0.5f, 0.5f,
                    w, h, new float[]{79.81333f, 80.58029f, 79.74476f, 80.51244f});
            final TiePointGrid incGrid = new TiePointGrid(OperatorUtils.TPG_INCIDENT_ANGLE, 2, 2, 0.5f, 0.5f,
                    w, h, new float[]{30.814592f, 36.57208f, 30.816704f, 36.573723f});
            final TiePointGrid eleGrid = new TiePointGrid(OperatorUtils.TPG_ELEVATION_ANGLE, 2, 2, 0.5f, 0.5f,
                    w, h, new float[]{27.498474f, 32.483097f, 27.500357f, 32.48455f});
            final TiePointGrid srtGrid = new TiePointGrid(OperatorUtils.TPG_SLANT_RANGE_TIME, 2, 2, 0.5f, 0.5f,
                    w, h, new float[]{5330301.5f, 5649082.0f, 5330301.5f, 5649082.0f});
            final TiePointGeoCoding tpGeoCoding = new TiePointGeoCoding(latGrid, lonGrid);

            sourceProduct.addTiePointGrid(latGrid);
            sourceProduct.addTiePointGrid(lonGrid);
            sourceProduct.addTiePointGrid(incGrid);
            sourceProduct.addTiePointGrid(eleGrid);
            sourceProduct.addTiePointGrid(srtGrid);
            sourceProduct.setSceneGeoCoding(tpGeoCoding);

            TestUtils.createBand(sourceProduct, "i_IW1_VH", ProductData.TYPE_INT16, Unit.REAL, w, h, true);
            TestUtils.createBand(sourceProduct, "q_IW1_VH", ProductData.TYPE_INT16, Unit.IMAGINARY, w, h, true);

            try (final Product targetProduct = process(sourceProduct)) {
                final Band band = targetProduct.getBand("Intensity_IW1_VH");
                assertNotNull(band);

                final float[] floatValues = new float[8];
                band.readPixels(0, 0, 4, 2, floatValues, ProgressMonitor.NULL);

                assertEquals(0.0, floatValues[0], 0.0001);
                assertEquals(0.0, floatValues[1], 0.0001);
                assertEquals(0.0, floatValues[2], 0.0001);
            }
        }
    }

    /**
     * Processes a product and compares it to processed product known to be correct
     *
     * @param sourceProduct the path to the input product
     * @throws Exception general exception
     */
    private static Product process(final Product sourceProduct) throws Exception {

        final Sentinel1RemoveThermalNoiseOp op = (Sentinel1RemoveThermalNoiseOp) spi.createOperator();
        assertNotNull(op);
        op.setSourceProduct(sourceProduct);

        // get targetProduct: execute initialize()
        final Product targetProduct = op.getTargetProduct();
        TestUtils.verifyProduct(targetProduct, true, true, true);
        return targetProduct;
    }

    @Test
    public void testProcessAllSentinel1() throws Exception {
        TestProcessor testProcessor = SARTests.createTestProcessor();
        testProcessor.testProcessAllInPath(spi, SARTests.rootPathsSentinel1, "SENTINEL-1", productTypeExemptions, exceptionExemptions);
    }
}
