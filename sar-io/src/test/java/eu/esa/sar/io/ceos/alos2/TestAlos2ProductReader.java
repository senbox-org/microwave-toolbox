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
package eu.esa.sar.io.ceos.alos2;

import eu.esa.sar.commons.test.ReaderTest;
import eu.esa.sar.commons.test.SARTests;
import org.esa.snap.engine_utilities.gpf.TestProcessor;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assume.assumeTrue;

/**
 * Test ALOS 2 CEOS Product Reader.
 *
 * @author lveci
 */
public class TestAlos2ProductReader extends ReaderTest {

    private String[] exceptionExemptions = {"geocoding is null", "not supported"};

    private final static String inputALOS2 = SARTests.inputPathProperty + SARTests.sep + "SAR" + SARTests.sep  + "ALOS2" + SARTests.sep ;
    private final static File[] rootPathsALOS2 = SARTests.loadFilePath(inputALOS2);

    @Before
    public void setUp() {
        // If the file does not exist: the test will be ignored
        for (File file : rootPathsALOS2) {
            assumeTrue(file + " not found", file.exists());
        }
    }

    public TestAlos2ProductReader() {
        super(new Alos2ProductReaderPlugIn());
    }

    /**
     * Open all files in a folder recursively
     *
     * @throws Exception anything
     */
    @Test
    public void testOpenAll() throws Exception {
        TestProcessor testProcessor = SARTests.createTestProcessor();
        testProcessor.recurseReadFolder(this, rootPathsALOS2, readerPlugIn, reader, null, exceptionExemptions);
    }
}
