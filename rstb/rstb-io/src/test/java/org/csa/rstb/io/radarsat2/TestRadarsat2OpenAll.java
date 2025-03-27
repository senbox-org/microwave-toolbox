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
package org.csa.rstb.io.radarsat2;

import com.bc.ceres.test.LongTestRunner;
import eu.esa.sar.commons.test.ProductValidator;
import eu.esa.sar.commons.test.ReaderTest;
import eu.esa.sar.commons.test.SARTests;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.engine_utilities.gpf.TestProcessor;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

import static org.junit.Assume.assumeTrue;

/**
 * Test Product Reader.
 *
 * @author lveci
 */
@RunWith(LongTestRunner.class)
public class TestRadarsat2OpenAll extends ReaderTest {

    public final static String inputRS2 = TestData.inputSAR + "RS2/";
    public final static File[] rootPathsRadarsat2 = SARTests.loadFilePath(inputRS2);

    private final static ProductValidator.Expected expectedSLC = new ProductValidator.Expected();

    @Before
    public void setUp() {
        // If any of the file does not exist: the test will be ignored
        for (File file : rootPathsRadarsat2) {
            assumeTrue(file + " not found", file.exists());
        }

        expectedSLC.isSAR = true;
        expectedSLC.isComplex = true;
        expectedSLC.productType = "SLC";
    }

    public TestRadarsat2OpenAll() {
        super(new Radarsat2ProductReaderPlugIn());
    }

    @Test
    public void testOpenAll() throws Exception {
        TestProcessor testProcessor = SARTests.createTestProcessor();
        testProcessor.recurseReadFolder(this, rootPathsRadarsat2, readerPlugIn, reader, null, null);
    }
}
