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

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.test.ProcessorTest;
import eu.esa.sar.commons.test.SARTests;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.GeoCoding;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.dataop.dem.ElevationModel;
import org.esa.snap.core.dataop.dem.ElevationModelDescriptor;
import org.esa.snap.core.dataop.dem.ElevationModelRegistry;
import org.esa.snap.core.dataop.resamp.ResamplingFactory;
import org.esa.snap.core.gpf.OperatorSpi;
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
 * Unit test for Range Doppler.
 */
public class TestRangeDopplerOp extends ProcessorTest {

    private final static File inputFile1 = TestData.inputASAR_WSM;
    private final static File inputFile2 = TestData.inputASAR_IMM;
    private final static File inputFile3 = TestData.inputASAR_IMS;
    private final static File inputFile4 = TestData.inputASAR_APM;

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

    private final static OperatorSpi spi = new RangeDopplerGeocodingOp.Spi();
    private final static TestProcessor testProcessor = SARTests.createTestProcessor();

    private static final String[] productTypeExemptions = {"_BP", "XCA", "WVW", "WVI", "WVS", "WSS", "DOR_VOR_AX","OCN","ETAD"};
    private static final String[] exceptionExemptions = {"not supported", "not be map projected", "outside of SRTM valid area",
                                "Source product should first be deburst","has no bands","numbands is zero"};

    /**
     * Processes a WSM product and compares it to processed product known to be correct
     *
     * @throws Exception general exception
     */
    @Test
    public void testProcessWSM() throws Exception {
        try(final Product sourceProduct = TestUtils.readSourceProduct(inputFile1)) {

            final RangeDopplerGeocodingOp op = (RangeDopplerGeocodingOp) spi.createOperator();
            assertNotNull(op);
            op.setSourceProduct(sourceProduct);
            op.setApplyRadiometricCalibration(true);
            op.setParameter("saveLayoverShadowMask", true);
            String[] bandNames = {"Amplitude"};
            op.setSourceBandNames(bandNames);

            // get targetProduct: execute initialize()
            final Product targetProduct = op.getTargetProduct();
            TestUtils.verifyProduct(targetProduct, true, true, true);

            final Band band = targetProduct.getBandAt(0);
            assertNotNull(band);

            // readPixels gets computeTiles to be executed
            final float[] floatValues = new float[4];
            band.readPixels(200, 200, 2, 2, floatValues, ProgressMonitor.NULL);

            // compare with expected outputs:
            final float[] expected = new float[]{0.12189214f, 0.12721543f, 0.13359734f, 0.12150828f};
            assertArrayEquals(Arrays.toString(floatValues), expected, floatValues, 0.0001f);
        }
    }

    @Test
    public void testGetLocalDEM() throws Exception {

        final ProductReader reader = ProductIO.getProductReaderForInput(inputFile2);
        try(final Product sourceProduct = reader.readProductNodes(inputFile2, null)) {

            final ElevationModelRegistry elevationModelRegistry = ElevationModelRegistry.getInstance();
            final ElevationModelDescriptor demDescriptor = elevationModelRegistry.getDescriptor("SRTM 3Sec");
            final ElevationModel dem = demDescriptor.createDem(ResamplingFactory.createResampling(ResamplingFactory.BILINEAR_INTERPOLATION_NAME));
            final GeoCoding targetGeoCoding = sourceProduct.getSceneGeoCoding();

            final int width = sourceProduct.getSceneRasterWidth();
            final int height = sourceProduct.getSceneRasterHeight();

            final GeoPos geoPos = new GeoPos();
            final PixelPos pixPos = new PixelPos();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixPos.setLocation(x, y);
                    targetGeoCoding.getGeoPos(pixPos, geoPos);
                    dem.getElevation(geoPos);
                }
            }
        }
    }

    /**
     * Processes a IMS product and compares it to processed product known to be correct
     *
     * @throws Exception general exception
     */
    @Test
    public void testProcessIMS() throws Exception {
        try(final Product sourceProduct = TestUtils.readSourceProduct(inputFile3)) {

            final RangeDopplerGeocodingOp op = (RangeDopplerGeocodingOp) spi.createOperator();
            assertNotNull(op);
            op.setSourceProduct(sourceProduct);
            op.setApplyRadiometricCalibration(true);
            String[] bandNames = {"i", "q"};
            op.setSourceBandNames(bandNames);

            // get targetProduct: execute initialize()
            final Product targetProduct = op.getTargetProduct();
            TestUtils.verifyProduct(targetProduct, true, true, true);

            final Band band = targetProduct.getBandAt(0);
            assertNotNull(band);

            // readPixels gets computeTiles to be executed
            final float[] floatValues = new float[4];
            band.readPixels(0, 0, 2, 2, floatValues, ProgressMonitor.NULL);

            // compare with expected outputs:
            final float[] expected = new float[]{0.050986305f, 0.15979816f, 0.017083498f, 0.10548973f};
            assertArrayEquals(Arrays.toString(floatValues), expected, floatValues, 0.0001f);
        }
    }

    /**
     * Processes a APM product and compares it to processed product known to be correct
     *
     * @throws Exception general exception
     */
    @Test
    public void testProcessAPM() throws Exception {
        try(final Product sourceProduct = TestUtils.readSourceProduct(inputFile4)) {

            final RangeDopplerGeocodingOp op = (RangeDopplerGeocodingOp) spi.createOperator();
            assertNotNull(op);
            op.setSourceProduct(sourceProduct);
            op.setApplyRadiometricCalibration(true);
            String[] bandNames = {sourceProduct.getBandAt(0).getName()};
            op.setSourceBandNames(bandNames);

            // get targetProduct: execute initialize()
            final Product targetProduct = op.getTargetProduct();
            TestUtils.verifyProduct(targetProduct, true, true);

            final Band band = targetProduct.getBandAt(0);
            assertNotNull(band);

            // readPixels gets computeTiles to be executed
            final float[] floatValues = new float[4];
            band.readPixels(1000, 1000, 2, 2, floatValues, ProgressMonitor.NULL);

            // compare with expected outputs:
            final float[] expected = new float[]{0.2688405f, 0.2265824f, 0.18008466f, 0.17219248f};
            assertArrayEquals(Arrays.toString(floatValues), expected, floatValues, 0.0001f);
        }
    }

    @Test
    public void testProcessAllASAR() throws Exception {
        testProcessor.testProcessAllInPath(spi, SARTests.rootPathsASAR, productTypeExemptions, exceptionExemptions);
    }

    @Test
    public void testProcessAllERS() throws Exception {
        testProcessor.testProcessAllInPath(spi, SARTests.rootPathsERS, productTypeExemptions, exceptionExemptions);
    }

    @Test
    public void testProcessAllALOS() throws Exception
    {
        testProcessor.testProcessAllInPath(spi, SARTests.rootPathsALOS, "ALOS PALSAR CEOS", null, exceptionExemptions);
    }

    @Test
    public void testProcessAllRadarsat2() throws Exception {
        testProcessor.testProcessAllInPath(spi, SARTests.rootPathsRadarsat2, null, exceptionExemptions);
    }

    @Test
    public void testProcessAllTerraSARX() throws Exception {
        testProcessor.testProcessAllInPath(spi, SARTests.rootPathsTerraSarX, null, exceptionExemptions);
    }

    @Test
    public void testProcessAllCosmo() throws Exception {
        testProcessor.testProcessAllInPath(spi, SARTests.rootPathsCosmoSkymed, null, exceptionExemptions);
    }

    @Test
    public void testProcessAllSentinel1() throws Exception {
        testProcessor.testProcessAllInPath(spi, SARTests.rootPathsSentinel1, productTypeExemptions, exceptionExemptions);
    }
}
