/*
 * Copyright (C) 2023 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.iogdal.sentinel1;

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
public class TestSentinel1GDALOpenAll extends ReaderTest {

    public final static File inputS1_COGGRD_ZIP = new File(TestData.inputSAR+"S1/COG/S1A_EW_GRDM_1SSH_20220225T025010_20220225T025115_042063_0502C8_6675_COG.SAFE.zip");

    private final String[] productTypeExemptions = {"RAW","OCN"};

    private final static String inputS1 = SARTests.inputPathProperty + "/SAR/S1/";
    private final static File[] rootPathsSentinel1 = SARTests.loadFilePath(inputS1);

    final static ProductValidator.Options productOptions = new ProductValidator.Options();

    @Before
    public void setUp() {
        // If the file does not exist: the test will be ignored
        assumeTrue(inputS1_COGGRD_ZIP + " not found", inputS1_COGGRD_ZIP.exists());

        productOptions.verifyBands = false;
    }

    public TestSentinel1GDALOpenAll() {
        super(new Sentinel1GDALProductReaderPlugIn());
    }

    /**
     * Open all files in a folder recursively
     *
     * @throws Exception anything
     */
    @Test
    public void testOpenAll() throws Exception {
        TestProcessor testProcessor = SARTests.createTestProcessor();
        testProcessor.recurseReadFolder(this, rootPathsSentinel1, readerPlugIn, reader, productTypeExemptions, null);
    }
}
