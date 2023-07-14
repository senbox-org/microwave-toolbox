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
package eu.esa.sar.io.seasat;

import eu.esa.sar.commons.test.MetadataValidator;
import eu.esa.sar.commons.test.ProductValidator;
import eu.esa.sar.commons.test.ReaderTest;
import eu.esa.sar.commons.test.SARTests;
import org.esa.snap.core.datamodel.Product;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assume.assumeTrue;

/**
 * Test Product Reader.
 *
 * @author lveci
 */
public class TestSeaSatProductReader extends ReaderTest {

    private final static File metadataFile = new File(SARTests.inputPathProperty + "SAR/Seasat/SS_00263_STD_F0886_tif/SS_00263_STD_F0886.xml");
    private final static File zipFile = new File(SARTests.inputPathProperty + "SAR/Seasat/SS_00263_STD_F0886_tif.zip");

    final static MetadataValidator.Options options = new MetadataValidator.Options();

    @Before
    public void setUp() {
        // If the file does not exist: the test will be ignored
        assumeTrue(metadataFile + " not found", metadataFile.exists());

        options.validateSRGR = false;
    }

    public TestSeaSatProductReader() {
        super(new SeaSatProductReaderPlugIn());
    }

    @Test
    public void testOpenMetadata() throws Exception {
        Product prod = testReader(metadataFile.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata(options);
        validator.validateBands(new String[] {"Amplitude_HH","Intensity_HH"});
    }

    @Test
    public void testOpenZip() throws Exception {
        Product prod = testReader(zipFile.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata(options);
        validator.validateBands(new String[] {"Amplitude_HH","Intensity_HH"});
    }
}
