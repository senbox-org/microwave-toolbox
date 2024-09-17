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
package org.csa.rstb.calibration;

import com.bc.ceres.test.LongTestRunner;
import eu.esa.sar.calibration.gpf.CalibrationOp;
import eu.esa.sar.commons.test.ProcessorTest;
import eu.esa.sar.commons.test.SARTests;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.engine_utilities.gpf.TestProcessor;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Before;
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

    private String[] productTypeExemptions = {"GeoTIFF"};

    @Before
    public void setUp() throws Exception {
        try {
            testProcessor = SARTests.createTestProcessor();

            // If any of the file does not exist: the test will be ignored
            assumeTrue(TestData.inputRS2_SQuad + "not found", TestData.inputRS2_SQuad.exists());
        } catch (Exception e) {
            TestUtils.skipTest(this, e.getMessage());
            throw e;
        }
    }

    //@Test
    //@Ignore
    public void testProcessAllRadarsat1() throws Exception {
        testProcessor.testProcessAllInPath(spi, SARTests.rootPathsRadarsat1, "RADARSAT-1", productTypeExemptions, null);
    }

    @Test
    public void testProcessAllRadarsat2() throws Exception {
        testProcessor.testProcessAllInPath(spi, SARTests.rootPathsRadarsat2, "RADARSAT-2", productTypeExemptions, null);
    }
}
