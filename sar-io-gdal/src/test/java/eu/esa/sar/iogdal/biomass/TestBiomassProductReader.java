/*
 * Copyright (C) 2025 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.iogdal.biomass;

import eu.esa.sar.commons.test.ProductValidator;
import eu.esa.sar.commons.test.ReaderTest;
import eu.esa.sar.commons.test.TestData;
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
public class TestBiomassProductReader extends ReaderTest {

    //private static String pathPrefix = TestData.inputSAR;
    private static String pathPrefix = "E:/EO/";
    public final static File input_DGM = new File(pathPrefix+"BIOMASS/L1A_and_L1B/BIO_S1_DGM__1S_20170101T060309_20170101T060330_I_G03_M03_C03_T010_F001_01_D0NNQP/bio_s1_dgm__1s_20170101t060309_20170101t060330_i_g03_m03_c03_t010_f001_01_d0nnqp.xml");
    public final static File input_DGM_ZIP = new File(pathPrefix+"BIOMASS/L1A_and_L1B/BIO_S1_DGM__1S_20170101T060309_20170101T060330_I_G03_M03_C03_T010_F001_01_D0NNQP.zip");
    public final static File input_DGMFolder = new File(pathPrefix+"BIOMASS/L1A_and_L1B/BIO_S1_DGM__1S_20170101T060309_20170101T060330_I_G03_M03_C03_T010_F001_01_D0NNQP");


    final static ProductValidator.Options productOptions = new ProductValidator.Options();

    @Before
    public void setUp() {
        // If the file does not exist: the test will be ignored
        assumeTrue(input_DGM + " not found", input_DGM.exists());
        assumeTrue(input_DGM_ZIP + " not found", input_DGM_ZIP.exists());
    }

    public TestBiomassProductReader() {
        super(new BiomassProductReaderPlugIn());
    }


    @Test
    public void testOpeningFile() throws Exception {
        try(Product prod = testReader(input_DGM.toPath())) {

            final ProductValidator validator = new ProductValidator(prod, productOptions);
            validator.validateProduct();
            validator.validateMetadata();
            validator.validateBands(new String[]{"Amplitude_HH", "Intensity_HH"});
        }
    }

    @Test
    public void testOpeningZip() throws Exception {
        try(Product prod = testReader(input_DGM_ZIP.toPath())) {

            final ProductValidator validator = new ProductValidator(prod);
            validator.validateProduct();
            validator.validateMetadata();
            validator.validateBands(new String[]{"Amplitude_HH", "Intensity_HH"});
        }
    }

    @Test
    public void testOpeningFolder() throws Exception {
        try(Product prod = testReader(input_DGMFolder.toPath())) {

            final ProductValidator validator = new ProductValidator(prod);
            validator.validateProduct();
            validator.validateMetadata();
            validator.validateBands(new String[]{"Amplitude_HH", "Intensity_HH"});
        }
    }
}
