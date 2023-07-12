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
package org.csa.rstb.io.radarsat1;

import eu.esa.sar.commons.test.ProductValidator;
import eu.esa.sar.commons.test.ReaderTest;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Product;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assume.assumeTrue;

/**
 * Test Radarsat 1 CEOS Product Reader.
 *
 * @author lveci
 */
public class TestRadarsatProductReader extends ReaderTest  {

    private static final File zipFile = new File(TestData.inputSAR + "RS1/RS1_m0700850_S7_20121103_232202_HH_SGF.zip");
    private static final File folder = new File(TestData.inputSAR + "RS1/RS1_m0700850_S7_20121103_232202_HH_SGF");
    private static final File metaFile = new File(TestData.inputSAR + "RS1/RS1_m0700850_S7_20121103_232202_HH_SGF/RS1_m0700850_S7_20121103_232202_HH_SGF.vol");

    @Before
    public void setUp() {
        // If the file does not exist: the test will be ignored
        assumeTrue(zipFile + " not found", zipFile.exists());
        assumeTrue(folder + " not found", folder.exists());
        assumeTrue(metaFile + " not found", metaFile.exists());
    }

    public TestRadarsatProductReader() {
        super(new RadarsatProductReaderPlugIn());
    }

    @Test
    public void testOpeningZip() throws Exception {
        Product prod = testReader(zipFile.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"Amplitude_HH","Intensity_HH"});
    }

//    @Test
//    public void testOpeningFolder() throws Exception {
//        testReader(folder);
//    }

    @Test
    public void testOpeningVolFile() throws Exception {
        Product prod = testReader(metaFile.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"Amplitude_HH","Intensity_HH"});
    }
}
