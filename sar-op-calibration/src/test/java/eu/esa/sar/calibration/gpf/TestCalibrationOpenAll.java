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
package eu.esa.sar.calibration.gpf;

import com.bc.ceres.test.LongTestRunner;
import eu.esa.sar.commons.test.ProcessorTest;
import eu.esa.sar.commons.test.SARTests;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.engine_utilities.gpf.TestProcessor;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assume.assumeTrue;

/**
 * Unit test for Calibration Operator.
 */
@RunWith(LongTestRunner.class)
public class TestCalibrationOpenAll extends ProcessorTest {

    private final static OperatorSpi spi = new CalibrationOp.Spi();
    private TestProcessor testProcessor;

    private final String[] productTypeExemptions = {"_BP", "XCA", "RAW", "WVW", "WVI", "WVS", "WSS", "OCN", "DOR", "GeoTIFF", "SCS_U"};
    private final String[] exceptionExemptions = {"not supported", "numbands is zero",
            "calibration has already been applied",
            "The product has already been calibrated",
            "Cannot apply calibration to coregistered product",
            "WV is not a valid acquisition mode from: IW,EW,SM"
    };

    @Before
    public void setUp() {
        testProcessor = SARTests.createTestProcessor();

        // If any of the file does not exist: the test will be ignored
        assumeTrue(TestData.inputASAR_WSM + "not found", TestData.inputASAR_WSM.exists());
        assumeTrue(TestData.inputASAR_IMS + "not found", TestData.inputASAR_IMS.exists());
        assumeTrue(TestData.inputERS_IMP + "not found", TestData.inputERS_IMP.exists());
        assumeTrue(TestData.inputERS_IMS + "not found", TestData.inputERS_IMS.exists());
        assumeTrue(TestData.inputS1_GRD + "not found", TestData.inputS1_GRD.exists());
        assumeTrue(TestData.inputS1_StripmapSLC + "not found", TestData.inputS1_StripmapSLC.exists());
    }

    @Test
    public void testProcessAllASAR() throws Exception {
        testProcessor.testProcessAllInPath(spi, SARTests.rootPathsASAR, "ENVISAT", productTypeExemptions, null);
    }

    @Test
    public void testProcessAllERS() throws Exception {
        testProcessor.testProcessAllInPath(spi, SARTests.rootPathsERS, "ERS CEOS", productTypeExemptions, null);
    }

    @Test
    public void testProcessAllALOS() throws Exception {
        testProcessor.testProcessAllInPath(spi, SARTests.rootPathsALOS, "ALOS PALSAR CEOS", productTypeExemptions, null);
    }

    @Test
    @Ignore("Disable for now. Problem with GeoTiff reader")
    public void testProcessAllALOS2() throws Exception {
        testProcessor.testProcessAllInPath(spi, SARTests.rootPathsALOS2, "ALOS-2", productTypeExemptions, exceptionExemptions);
    }

    @Test
    @Ignore("Disable for now. Not all Cosmo products are supported")
    public void testProcessAllCosmo() throws Exception {
        testProcessor.testProcessAllInPath(spi, SARTests.rootPathsCosmoSkymed, "CosmoSkymed", productTypeExemptions, exceptionExemptions);
    }

    @Test
    public void testProcessAllTSX() throws Exception {
        testProcessor.testProcessAllInPath(spi, SARTests.rootPathsTerraSarX, "TerraSarX", productTypeExemptions, exceptionExemptions);
    }

    @Test
    public void testProcessAllSentinel1() throws Exception {
        testProcessor.testProcessAllInPath(spi, SARTests.rootPathsSentinel1, "SENTINEL-1", productTypeExemptions, exceptionExemptions);
    }

    @Test
    public void testProcessAllK5() throws Exception {
        testProcessor.testProcessAllInPath(spi, SARTests.rootPathsK5, "Kompsat5", productTypeExemptions, exceptionExemptions);
    }

    @Test
    @Ignore
    public void testProcessAllJERS() throws Exception {
        testProcessor.testProcessAllInPath(spi, SARTests.rootPathsJERS, "JERS", productTypeExemptions, exceptionExemptions);
    }

    @Test
    @Ignore
    public void testProcessAllIceye() throws Exception {
        testProcessor.testProcessAllInPath(spi, SARTests.rootPathsIceye, "ICEYE", productTypeExemptions, exceptionExemptions);
    }
}
