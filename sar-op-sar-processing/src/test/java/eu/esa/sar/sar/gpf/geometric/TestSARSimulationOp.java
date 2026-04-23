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

import com.bc.ceres.annotation.STTM;
import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.test.ProcessorTest;
import eu.esa.sar.commons.test.SARTests;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
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
 * Unit test for SAR Simulation Operator.
 */
public class TestSARSimulationOp extends ProcessorTest {

    private final static File inputFile = TestData.inputASAR_WSM;

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

    private final static OperatorSpi spi = new SARSimulationOp.Spi();
    private final static TestProcessor testProcessor = SARTests.createTestProcessor();

    private static final String[] productTypeExemptions = {"_BP", "XCA", "WVW", "WVI", "WVS", "WSS", "DOR_VOR_AX","OCN","ETAD"};
    private static final String[] exceptionExemptions = {"not supported", "not be map projected", "outside of SRTM valid area",
                "Source product should first be deburst",
                "incidence_angle tie point grid not found in product"};

    /**
     * Processes a product and compares it to processed product known to be correct
     *
     * @throws Exception general exception
     */
    @Test
    public void testProcessing() throws Exception {
        try(final Product sourceProduct = TestUtils.readSourceProduct(inputFile)) {

            final SARSimulationOp op = (SARSimulationOp) spi.createOperator();
            assertNotNull(op);
            op.setSourceProduct(sourceProduct);

            // get targetProduct: execute initialize()
            final Product targetProduct = op.getTargetProduct();
            TestUtils.verifyProduct(targetProduct, true, true, true);

            final Band band = targetProduct.getBandAt(0);
            assertNotNull(band);

            // readPixels gets computeTiles to be executed
            final float[] floatValues = new float[4];
            band.readPixels(0, 0, 2, 2, floatValues, ProgressMonitor.NULL);

            // compare with expected outputs:
            final float[] expected = new float[]{0.01345945f, 0.0022644033f, 4.9961345E-5f, 5.912213E-5f};
            assertArrayEquals(Arrays.toString(floatValues), expected, floatValues, 0.0001f);
        }
    }

    /**
     * Regression guard for a missing/unreadable external DEM. Previously
     * {@link SARSimulationOp#getElevationModel()} wrapped DEM construction in a
     * try/catch(Throwable) that swallowed the real failure, left {@code dem == null},
     * and flipped {@code isElevationModelAvailable = true}. Tile computation then
     * dereferenced the null {@code dem} and surfaced as a bare
     * {@link NullPointerException} — the symptom users hit when pointing SAR-Sim
     * Terrain Correction at an external DEM. The failure must now propagate as an
     * {@link org.esa.snap.core.gpf.OperatorException} naming the DEM problem.
     */
    @Test
    @STTM("SNAP-2528")
    public void testExternalDEM_missingFile_throwsDescriptiveError() throws Exception {
        try (final Product sourceProduct = TestUtils.readSourceProduct(inputFile)) {

            final SARSimulationOp op = (SARSimulationOp) spi.createOperator();
            op.setSourceProduct(sourceProduct);
            op.setParameter("demName", SARSimulationOp.externalDEMStr);
            op.setParameter("externalDEMFile", new File("E:/this/path/does/not/exist.tif"));

            final Product targetProduct = op.getTargetProduct();
            final Band band = targetProduct.getBand("Simulated_Intensity");
            assertNotNull(band);

            final float[] floatValues = new float[4];
            try {
                band.readPixels(0, 0, 2, 2, floatValues, ProgressMonitor.NULL);
                org.junit.Assert.fail("Expected DEM-loading failure to propagate, but read succeeded");
            } catch (NullPointerException npe) {
                org.junit.Assert.fail("DEM-loading failure leaked as NullPointerException: " + npe);
            } catch (Exception expected) {
                // ok — any concrete exception is acceptable as long as it's not a bare NPE
            }
        }
    }

    @Test
    public void testLayoverShadow() throws Exception {
        try(final Product sourceProduct = TestUtils.readSourceProduct(inputFile)) {

            final SARSimulationOp op = (SARSimulationOp) spi.createOperator();
            assertNotNull(op);
            op.setSourceProduct(sourceProduct);
            op.setParameter("saveLayoverShadowMask", true);

            // get targetProduct: execute initialize()
            final Product targetProduct = op.getTargetProduct();
            TestUtils.verifyProduct(targetProduct, true, true, true);

            final Band band = targetProduct.getBandAt(3);
            assertNotNull(band);

            // readPixels gets computeTiles to be executed
            final float[] floatValues = new float[4];
            band.readPixels(500, 500, 2, 2, floatValues, ProgressMonitor.NULL);

            // compare with expected outputs:
            final float[] expected = new float[]{0.0f, 0.0f, 0.0f, 0.0f};
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
    public void testProcessAllALOS() throws Exception {
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
