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
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;

import static org.junit.Assume.assumeTrue;

/**
 * Test Product Reader.
 *
 * @author lveci
 */
public class TestBiomassProductReader extends ReaderTest {

    private static String pathPrefix = TestData.inputSAR;
    public final static File input_L1B_DGM = new File(pathPrefix+"BIOMASS/L1A_and_L1B/BIO_S1_DGM__1S_20170101T060309_20170101T060330_I_G03_M03_C03_T010_F001_01_D0NNQP/bio_s1_dgm__1s_20170101t060309_20170101t060330_i_g03_m03_c03_t010_f001_01_d0nnqp.xml");

    public final static File input_L1B_DGM_ZIP = new File(pathPrefix+"BIOMASS/L1A_and_L1B/BIO_S1_DGM__1S_20170101T060309_20170101T060330_I_G03_M03_C03_T010_F001_01_D0NNQP.zip");
    public final static File input_L1B_DGM_Folder = new File(pathPrefix+"BIOMASS/L1A_and_L1B/BIO_S1_DGM__1S_20170101T060309_20170101T060330_I_G03_M03_C03_T010_F001_01_D0NNQP");

    public final static File input_L1A_SCS = new File(pathPrefix+"BIOMASS/L1A_and_L1B/BIO_S1_SCS__1S_20170101T060309_20170101T060330_I_G03_M03_C03_T010_F001_01_D0NNPU/bio_s1_scs__1s_20170101t060309_20170101t060330_i_g03_m03_c03_t010_f001_01_d0nnpu.xml");

    public final static File input_L1C_IntPhase_SCS = new File(pathPrefix+"BIOMASS/L1C_INT_phase/BIO_S2_STA__1S_20170125T163833_20170125T163900_T_G03_M03_C03_T000_F001_01_CYW6AO/bio_s2_sta__1s_20170125t163833_20170125t163900_t_g03_m03_c03_t000_f001_01_cyw6ao.xml");

    final static ProductValidator.Options productOptions = new ProductValidator.Options();

    @Before
    public void setUp() {
        // If the file does not exist: the test will be ignored
        assumeTrue(input_L1B_DGM + " not found", input_L1B_DGM.exists());
        assumeTrue(input_L1B_DGM_ZIP + " not found", input_L1B_DGM_ZIP.exists());
        assumeTrue(input_L1A_SCS + " not found", input_L1A_SCS.exists());
    }

    public TestBiomassProductReader() {
        super(new BiomassProductReaderPlugIn());
    }


    @Test
    public void testOpeningFileDGM() throws Exception {
        try(Product prod = testReader(input_L1B_DGM.toPath())) {

            final ProductValidator validator = new ProductValidator(prod, productOptions);
            validator.validateProduct();
            validator.validateMetadata();
            validator.validateBands(new String[]{"Amplitude_S1_HH", "Intensity_S1_HH", "Amplitude_S1_HV", "Intensity_S1_HV", "Amplitude_S1_VH", "Intensity_S1_VH", "Amplitude_S1_VV", "Intensity_S1_VV"});
        }
    }

    @Test
    @Ignore
    public void testOpeningZipDGM() throws Exception {
        try(Product prod = testReader(input_L1B_DGM_ZIP.toPath())) {

            final ProductValidator validator = new ProductValidator(prod);
            validator.validateProduct();
            validator.validateMetadata();
            validator.validateBands(new String[]{"Amplitude_S1_HH", "Intensity_S1_HH", "Amplitude_S1_HV", "Intensity_S1_HV", "Amplitude_S1_VH", "Intensity_S1_VH", "Amplitude_S1_VV", "Intensity_S1_VV"});
        }
    }

    @Test
    public void testOpeningFolderDGM() throws Exception {
        try(Product prod = testReader(input_L1B_DGM_Folder.toPath())) {

            final ProductValidator validator = new ProductValidator(prod);
            validator.validateProduct();
            validator.validateMetadata();
            validator.validateBands(new String[]{"Amplitude_S1_HH", "Intensity_S1_HH", "Amplitude_S1_HV", "Intensity_S1_HV", "Amplitude_S1_VH", "Intensity_S1_VH", "Amplitude_S1_VV", "Intensity_S1_VV"});
        }
    }

    @Test
    public void testOpeningFile_L1A_SCS() throws Exception {
        try(Product prod = testReader(input_L1A_SCS.toPath())) {

            final ProductValidator validator = new ProductValidator(prod, productOptions);
            validator.validateProduct();
            validator.validateMetadata();
            validator.validateBands(new String[]{"Amplitude_S1_HH", "Phase_S1_HH", "i_S1_HH", "q_S1_HH", "Intensity_S1_HH", "Amplitude_S1_HV", "Phase_S1_HV", "i_S1_HV", "q_S1_HV", "Intensity_S1_HV", "Amplitude_S1_VH", "Phase_S1_VH", "i_S1_VH", "q_S1_VH", "Intensity_S1_VH", "Amplitude_S1_VV", "Phase_S1_VV", "i_S1_VV", "q_S1_VV", "Intensity_S1_VV"});
        }
    }

    @Test
    public void testOpeningFile_L1C_SCS() throws Exception {
        try(Product prod = testReader(input_L1C_IntPhase_SCS.toPath())) {

            final ProductValidator validator = new ProductValidator(prod, productOptions);
            validator.validateProduct();
            validator.validateMetadata();
            validator.validateBands(new String[]{"Amplitude_S2_HH", "Phase_S2_HH", "i_S2_HH", "q_S2_HH", "Intensity_S2_HH", "Amplitude_S2_VV", "Phase_S2_VV", "i_S2_VV", "q_S2_VV", "Intensity_S2_VV", "Amplitude_S2_XX", "Phase_S2_XX", "i_S2_XX", "q_S2_XX", "Intensity_S2_XX"});
        }
    }
}
