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
package eu.esa.sar.io.cosmo;

import eu.esa.sar.commons.test.ProductValidator;
import eu.esa.sar.commons.test.ReaderTest;
import eu.esa.sar.commons.test.SARTests;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.engine_utilities.gpf.TestProcessor;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assume.assumeTrue;

/**
 * Test Product Reader.
 *
 * @author lveci
 */
public class TestCosmoSkymedReader extends ReaderTest {

    public final static File inputSCS_H5 = new File(TestData.inputSAR + "Cosmo/level1B/hdf5/EL20100624_102783_1129476.6.2/CSKS2_SCS_B_S2_01_VV_RA_SF_20100623045532_20100623045540.h5");
    public final static File inputDGM_H5 = new File(TestData.inputSAR + "Cosmo/level1B/hdf5/EL20141029_928699_3776081.6.2/CSKS4_DGM_B_WR_03_VV_RA_SF_20141001061215_20141001061230.h5");

    public final static File inputSM_SCS_H5 = new File(TestData.inputSAR + "Cosmo/STRIPMAP/HH_Level_1A_hdf5/CSG_SSAR1_SCS_B_0101_STR_012_HH_RD_F_20200921215026_20200921215032_1_F_09S_Z19_N00.h5");
    public final static File inputSM_DGM_H5 = new File(TestData.inputSAR + "Cosmo/STRIPMAP/HH_Level_1B_hdf5/CSG_SSAR1_DGM_B_0101_STR_012_HH_RD_F_20200921215026_20200921215032_1_F_09S_Z19_N00.h5");

    public final static File inputSC_SCS_H5 = new File(TestData.inputSAR + "Cosmo/SCANSAR-1/HH_Level_1A_hdf5/CSG_SSAR1_SCS_B_0101_SC1_001_HH_RA_F_20200923102555_20200923102610_1_F_41N_Z18_N00.h5");
    public final static File inputSC_DGM_H5 = new File(TestData.inputSAR + "Cosmo/SCANSAR-1/HH_Level_1B_hdf5/CSG_SSAR1_DGM_B_0301_SC1_001_HH_RA_F_20200923102555_20200923102610_1_F_41N_Z18_N00.h5");

    public final static String inputCosmo = SARTests.inputPathProperty + SARTests.sep + "SAR" + SARTests.sep  + "Cosmo" + SARTests.sep ;
    public final static File[] rootPathsCosmoSkymed = SARTests.loadFilePath(inputCosmo);

    private String[] exceptionExemptions = {"not supported"};

    @Before
    public void setUp() {
        // If the file does not exist: the test will be ignored
        assumeTrue(inputSCS_H5 + " not found", inputSCS_H5.exists());
        assumeTrue(inputDGM_H5 + " not found", inputDGM_H5.exists());

        assumeTrue(inputSM_SCS_H5 + " not found", inputSM_SCS_H5.exists());
        assumeTrue(inputSM_DGM_H5 + " not found", inputSM_DGM_H5.exists());

        assumeTrue(inputSC_SCS_H5 + " not found", inputSC_SCS_H5.exists());
        assumeTrue(inputSC_DGM_H5 + " not found", inputSC_DGM_H5.exists());
    }

    public TestCosmoSkymedReader() {
        super(new CosmoSkymedReaderPlugIn());
    }

    @Test
    public void testOpeningSCS_H5() throws Exception {
        Product prod = testReader(inputSCS_H5.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"i","q","Intensity"});
    }

    @Test
    public void testOpeningDGM_H5() throws Exception {
        Product prod = testReader(inputDGM_H5.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"Amplitude","Intensity"});
    }


    @Test
    public void testOpeningSM_SCS_H5() throws Exception {
        Product prod = testReader(inputSM_SCS_H5.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"i","q","Intensity"});
    }

    @Test
    public void testOpeningSM_DGM_H5() throws Exception {
        Product prod = testReader(inputSM_DGM_H5.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"Amplitude","Intensity"});
    }

    @Test
    public void testOpeningSC_SCS_H5() throws Exception {
        Product prod = testReader(inputSC_SCS_H5.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"i","q","Intensity"});
    }

    @Test
    public void testOpeningSC_DGM_H5() throws Exception {
        Product prod = testReader(inputSC_DGM_H5.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"Amplitude","Intensity"});
    }

    /**
     * Open all files in a folder recursively
     *
     * @throws Exception anything
     */
    @Test
    public void testOpenAll() throws Exception {
        TestProcessor testProcessor = SARTests.createTestProcessor();
        testProcessor.recurseReadFolder(this, rootPathsCosmoSkymed, readerPlugIn, reader, null, exceptionExemptions);
    }
}
