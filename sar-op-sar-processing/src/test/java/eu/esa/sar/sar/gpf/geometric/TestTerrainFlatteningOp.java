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
import eu.esa.sar.calibration.gpf.CalibrationOp;
import eu.esa.sar.commons.test.ProcessorTest;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertEquals;
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

    private final static OperatorSpi spi = new TerrainFlatteningOp.Spi();

    // Relative pixel tolerance: 1% is 0.04 dB. It has to pin the radiometric level, which the
    // Beta0 (1/sin(theta), x2.4) and SRGR ratio (x1.24) fixes moved by far more than that; the
    // old 0.05 absolute tolerance was 11% (0.5 dB) of these values.
    // There is an unresolved state leak in snap-engine: when TestRangeDopplerOp / TestSARSimulationOp
    // run earlier in the same surefire fork, TerrainFlattening's pixel output at (200, 200) smooths out
    // by 5-13%. The mutator is buried somewhere in snap-engine (GeoTiffProductReader.closeResources,
    // JAI tile cache, or the SRTM ElevationModel cache lifecycle). The surefire configuration in
    // sar-op-sar-processing/pom.xml forks this class into its own JVM so the leak can't reach it.
    private static final double RELATIVE_TOLERANCE = 0.01;

    /**
     * Forces a full TerrainFlattening pass on inputFile1 before any timed test runs,
     * so the DEM tile cache is hot. Without this, the first test that calls
     * dem.getElevation() races SNAP's tile loader and intermittently sees NaN cells
     * (see PIXEL_TOLERANCE comment).
     */
    @BeforeClass
    public static void prewarmDem() throws Exception {
        if (!inputFile1.exists()) {
            return; // per-test @Before will skip via assumeTrue
        }
        final Product src = loadCached(inputFile1);
        final CalibrationOp cal = new CalibrationOp();
        cal.setSourceProduct(src);
        cal.setParameter("outputBetaBand", true);
        cal.setParameter("createBetaBand", true);

        final TerrainFlatteningOp op = (TerrainFlatteningOp) spi.createOperator();
        op.setSourceProduct(cal.getTargetProduct());
        final Product target = op.getTargetProduct();
        // Reading a pixel block forces computeTile, which triggers DEM tile loads.
        final Band band = target.getBandAt(0);
        final float[] sink = new float[16];
        band.readPixels(200, 200, 4, 4, sink, ProgressMonitor.NULL);
        target.dispose();
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

    private static void assertPixelsClose(final Product targetProduct, final String bandName,
                                          final int x, final int y, final float[] expected) throws IOException {
        final Band band = targetProduct.getBand(bandName);
        if (band == null) {
            throw new IOException(bandName + " not found");
        }
        final float[] actual = new float[expected.length];
        band.readPixels(x, y, expected.length, 1, actual, ProgressMonitor.NULL);
        final String msg = "expected=" + Arrays.toString(expected) + " actual=" + Arrays.toString(actual);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(msg, expected[i], actual[i], RELATIVE_TOLERANCE * Math.abs(expected[i]));
        }
    }

    /*
     * The ERS IMP regression values were re-recorded for two fixes. Both are pinned elsewhere, by
     * closed-form tests:
     *  - ERSCalibrator wrote sigma0 into the Beta0 band Terrain-Flattening reads; Beta0 is now
     *    sigma0 / sin(theta), x2.40 here (TestBaseCalibrator, TestCalibrationIceye).
     *  - computeSRGRRatio ignored ground_range_origin. This fixture is a subset with
     *    ground_range_origin = 68900 m, so the slant/ground range ratio at x = 200 is 1.238 larger
     *    (TestTerrainFlatteningSRGRRatio, TestTerrainFlatteningSubsetLevel).
     * Gamma0 and Sigma0 carry both factors, x2.97 against the old values; the simulated image carries
     * only the second, /1.238.
     */

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

        final float[] expected = new float[]{0.43853235f, 0.45097739f, 0.3625649f, 0.45141622f};
        assertPixelsClose(targetProduct, targetProduct.getBandAt(0).getName(), 200, 200, expected);
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

        final float[] expected = new float[]{2.1695888f, 2.178224f, 2.2015066f, 2.1685026f};
        assertPixelsClose(targetProduct, targetProduct.getBandAt(1).getName(), 200, 200, expected);
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

        final float[] expected = new float[]{0.3985106f, 0.4098169f, 0.32947162f, 0.41020992f};
        assertPixelsClose(targetProduct, targetProduct.getBandAt(1).getName(), 200, 200, expected);
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
