/*
 * Copyright (C) 2015 by Array Systems Computing Inc. http://www.array.ca
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
package eu.esa.sar.io.ceos.jers;

import eu.esa.sar.commons.test.ReaderTest;
import eu.esa.sar.commons.test.SARTests;
import org.esa.snap.engine_utilities.gpf.TestProcessor;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assume.assumeTrue;

/**
 * Test JERS CEOS Product Reader.
 *
 * @author lveci
 */
public class TestJERSProductReader extends ReaderTest {

    public final static String inputJERS = SARTests.inputPathProperty + SARTests.sep + "SAR" + SARTests.sep  + "JERS" + SARTests.sep ;
    public final static File[] rootPathsJERS = SARTests.loadFilePath(inputJERS);

    @Before
    public void setUp() {
        // If the file does not exist: the test will be ignored
        for (File file : rootPathsJERS) {
            assumeTrue(file + " not found", file.exists());
        }
    }

    private static final String[] exemptions = new String[] {"startTime is null"};

    public TestJERSProductReader() {
        super(new JERSProductReaderPlugIn());
    }

    /**
     * Open all files in a folder recursively
     *
     * @throws Exception anything
     */
    @Test
    public void testOpenAll() throws Exception {
        TestProcessor testProcessor = SARTests.createTestProcessor();
        testProcessor.recurseReadFolder(this, rootPathsJERS, readerPlugIn, reader, null, exemptions);
    }
}
