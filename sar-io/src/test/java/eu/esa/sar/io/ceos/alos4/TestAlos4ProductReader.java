/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.io.ceos.alos4;

import eu.esa.sar.commons.test.ProductValidator;
import eu.esa.sar.commons.test.ReaderTest;
import eu.esa.sar.commons.test.SARTests;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.dataio.DecodeQualification;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.engine_utilities.gpf.TestProcessor;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assume.assumeTrue;

/**
 * Test ALOS 4 CEOS Product Reader.
 *
 * @author lveci
 */
public class TestAlos4ProductReader extends ReaderTest {

    private final static File slc = new File("E:\\EO\\ALOS4\\ALOS40182900250502UWDPRD0107_1.1__-\\ALOS40182900250502UWDPRD0107_1.1__-\\VOL-ALOS40182900250502UWDPRD0107-1.1__-");

    private String[] exceptionExemptions = {"geocoding is null", "not supported"};

    private final static String inputALOS4 = TestData.inputSAR + "ALOS4/";
    private final static File[] rootPathsALOS4 = SARTests.loadFilePath(inputALOS4);

    @Before
    public void setUp() {
        // If the file does not exist: the test will be ignored
        assumeTrue(slc + " not found", slc.exists());
    }

    public TestAlos4ProductReader() {
        super(new Alos4ProductReaderPlugIn());
    }

    @Test
    public void testOpeningSLC() throws Exception {

        final DecodeQualification canRead = readerPlugIn.getDecodeQualification(slc);
        Assert.assertTrue(canRead == DecodeQualification.INTENDED);

        final Product product = reader.readProductNodes(slc, null);
        Assert.assertTrue(product != null);

        final ProductValidator validator = new ProductValidator(product);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"i_HH","q_HH","Intensity_HH"});
    }

    /**
     * Open all files in a folder recursively
     *
     * @throws Exception anything
     */
    @Test
    public void testOpenAll() throws Exception {
        TestProcessor testProcessor = SARTests.createTestProcessor();
        testProcessor.recurseReadFolder(this, rootPathsALOS4, readerPlugIn, reader, null, exceptionExemptions);
    }
}
