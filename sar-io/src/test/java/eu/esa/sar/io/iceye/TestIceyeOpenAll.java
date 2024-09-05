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
package eu.esa.sar.io.iceye;

import com.bc.ceres.test.LongTestRunner;
import eu.esa.sar.commons.test.ReaderTest;
import eu.esa.sar.commons.test.SARTests;
import org.esa.snap.engine_utilities.gpf.TestProcessor;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;


/**
 * @author Ahmad Hamouda
 */
@RunWith(LongTestRunner.class)
public class TestIceyeOpenAll extends ReaderTest {

    private final static String inputIceyeFolder = SARTests.inputPathProperty + "SAR/Iceye/";
    private final static File[] iceyeSLCFiles = SARTests.loadFilePath(inputIceyeFolder + "SLC");
    private final static File[] iceyeGRDFiles = SARTests.loadFilePath(inputIceyeFolder + "GRD");

    private String[] exceptionExemptions = {"not supported"};

    public TestIceyeOpenAll() {
        super(new IceyeProductReaderPlugIn());
    }

    /**
     * Open all files in a folder recursively
     *
     * @throws Exception anything
     */
    @Test
    public void testOpenAll() {
        TestProcessor testProcessor = new TestProcessor(100, 100, 100, 100, 100, true, false);

        File[] folderPaths = new File[] {new File(inputIceyeFolder)};
        try {
            testProcessor.recurseReadFolder(this, folderPaths, readerPlugIn, reader, null, exceptionExemptions);
        } catch (Exception e) {
            Assert.fail();
        }
    }

    /**
     * Open all files in a folder recursively
     *
     * @throws Exception anything
     */
    @Test
    public void testTestServerOpenAllSLC() throws Exception {
        TestProcessor testProcessor = SARTests.createTestProcessor();
        testProcessor.recurseReadFolder(this, iceyeSLCFiles, readerPlugIn, null, null, null);
    }

    /**
     * Open all files in a folder recursively
     *
     * @throws Exception anything
     */
    @Test
    public void testTestServerOpenAllGRD() throws Exception {
        TestProcessor testProcessor = SARTests.createTestProcessor();
        testProcessor.recurseReadFolder(this, iceyeGRDFiles, readerPlugIn, null, null, null);
    }
}
