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
package eu.esa.sar.io.nisar;

import eu.esa.sar.commons.test.ProductValidator;
import eu.esa.sar.commons.test.ReaderTest;
import eu.esa.sar.commons.test.SARTests;
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
public class TestNISARReader extends ReaderTest {

    public final static File input_L1_RIFG_H5 = new File(TestData.inputSAR + "NISAR/NISAR_L1_PR_RIFG_001_005_A_219_220_4020_SH_20081012T060910_20081012T060926_20081127T060959_20081127T061015_P01101_M_F_J_001.h5");
    public final static File input_L1_ROFF_H5 = new File(TestData.inputSAR + "NISAR/NISAR_L1_PR_ROFF_001_005_A_219_220_4020_SH_20081012T060910_20081012T060926_20081127T060959_20081127T061015_P01101_M_F_J_001.h5");
    public final static File input_L1_RUNW_H5 = new File(TestData.inputSAR + "NISAR/NISAR_L1_PR_RUNW_001_005_A_219_220_4020_SH_20081012T060910_20081012T060926_20081127T060959_20081127T061015_P01101_M_F_J_001.h5");
    public final static File input_L1_RSLC_H5 = new File(TestData.inputSAR + "NISAR/NISAR_L1_PR_RSLC_001_005_A_219_2005_DHDH_A_20081012T060910_20081012T060926_P01101_F_N_J_001.h5");

    public final static File input_L2_GCOV_H5 = new File(TestData.inputSAR + "NISAR/NISAR_L2_PR_GCOV_001_005_A_219_4020_SHNA_A_20081012T060910_20081012T060926_P01101_F_N_J_001.h5");
    public final static File input_L2_GOFF_H5 = new File(TestData.inputSAR + "NISAR/NISAR_L2_PR_GOFF_001_005_A_219_220_4020_SH_20081012T060910_20081012T060926_20081127T060959_20081127T061015_P01101_M_F_J_001.h5");
    public final static File input_L2_GUNW_H5 = new File(TestData.inputSAR + "NISAR/NISAR_L2_PR_GUNW_001_005_A_219_220_4020_SH_20081012T060910_20081012T060926_20081127T060959_20081127T061015_P01101_M_F_J_001.h5");
    public final static File input_L2_GSLC_H5 = new File(TestData.inputSAR + "NISAR/NISAR_L2_PR_GSLC_001_005_A_219_2005_DHDH_A_20081127T060959_20081127T061015_P01101_F_N_J_001.h5");

    public final static File input_L3_SME2_H5 = new File(TestData.inputSAR + "NISAR/NISAR_L3_PR_SME2_001_008_D_070_4000_QPNA_A_20190829T180759_20190829T180809_P01101_M_P_J_001.h5");


    public final static String inputNISAR = SARTests.inputPathProperty + "SAR/NISAR/";
    public final static File[] rootPathNISAR = SARTests.loadFilePath(inputNISAR);

    private final String[] exceptionExemptions = {"not supported"};

    @Before
    public void setUp() {
        // If the file does not exist: the test will be ignored
        assumeTrue(input_L1_RIFG_H5 + " not found", input_L1_RIFG_H5.exists());
        assumeTrue(input_L1_ROFF_H5 + " not found", input_L1_ROFF_H5.exists());
        assumeTrue(input_L1_RUNW_H5 + " not found", input_L1_RUNW_H5.exists());
        assumeTrue(input_L1_RSLC_H5 + " not found", input_L1_RSLC_H5.exists());

        assumeTrue(input_L2_GCOV_H5 + " not found", input_L2_GCOV_H5.exists());
        assumeTrue(input_L2_GOFF_H5 + " not found", input_L2_GOFF_H5.exists());
        assumeTrue(input_L2_GUNW_H5 + " not found", input_L2_GUNW_H5.exists());
        assumeTrue(input_L2_GSLC_H5 + " not found", input_L2_GSLC_H5.exists());

        assumeTrue(input_L3_SME2_H5 + " not found", input_L3_SME2_H5.exists());
    }

    public TestNISARReader() {
        super(new NisarProductReaderPlugIn());
    }

    @Test
    public void testOpening_L1_RIFG_H5() throws Exception {
        Product prod = testReader(input_L1_RIFG_H5.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        //validator.validateProduct();
        //validator.validateMetadata();
        //validator.validateBands(new String[] {"correlationSurfacePeak_HH", "i_ifg_HH", "q_ifg_HH", "slantRangeOffset_HH", "coherenceMagnitude_HH", "alongTrackOffset_HH"});
    }

    @Test
    public void testOpening_L1_ROFF_H5() throws Exception {
        Product prod = testReader(input_L1_ROFF_H5.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        //validator.validateProduct();
        //validator.validateMetadata();
        //validator.validateBands(new String[] {"L1_alongTrackOffsetVariance_HH", "L2_alongTrackOffset_HH", "L1_slantRangeOffset_HH", "L1_slantRangeOffsetVariance_HH", "L2_snr_HH", "L3_slantRangeOffsetVariance_HH", "L2_crossOffsetVariance_HH", "L2_slantRangeOffset_HH", "L2_slantRangeOffsetVariance_HH", "L1_crossOffsetVariance_HH", "L2_alongTrackOffsetVariance_HH", "L1_correlationSurfacePeak_HH", "L1_alongTrackOffset_HH", "L1_snr_HH", "L3_alongTrackOffset_HH", "L3_alongTrackOffsetVariance_HH", "L3_snr_HH", "L2_correlationSurfacePeak_HH", "L3_correlationSurfacePeak_HH", "L3_crossOffsetVariance_HH", "L3_slantRangeOffset_HH"});
    }


    @Test
    public void testOpening_L1_RUNW_H5() throws Exception {
        Product prod = testReader(input_L1_RUNW_H5.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        //validator.validateProduct();
        //validator.validateMetadata();
        //validator.validateBands(new String[] {"i","q","Intensity"});
    }

    @Test
    public void testOpening_L1_RSLC_H5() throws Exception {
        Product prod = testReader(input_L1_RSLC_H5.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        //validator.validateMetadata();
        //validator.validateBands(new String[] {"i","q","Intensity"});
    }

    @Test
    public void testOpening_L2_GOFF_H5() throws Exception {
        Product prod = testReader(input_L2_GOFF_H5.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        //validator.validateProduct();
        //validator.validateMetadata();
        //validator.validateBands(new String[] {"i","q","Intensity"});
    }

    @Test
    public void testOpening_L2_GUNW_H5() throws Exception {
        Product prod = testReader(input_L2_GUNW_H5.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        //validator.validateMetadata();
        //validator.validateBands(new String[] {"i","q","Intensity"});
    }

    @Test
    public void testOpening_L3_SME2_H5() throws Exception {
        Product prod = testReader(input_L3_SME2_H5.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        //validator.validateProduct();
        //validator.validateMetadata();
        //validator.validateBands(new String[] {"i","q","Intensity"});
    }

    @Test
    @Ignore("unknown super block version=3")
    public void testOpening_L2_GCOV_H5() throws Exception {
        Product prod = testReader(input_L2_GCOV_H5.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        //validator.validateProduct();
        //validator.validateMetadata();
        //validator.validateBands(new String[] {"Amplitude","Intensity"});
    }

    @Test
    @Ignore("unknown super block version=3")
    public void testOpening_L2_GSLC_H5() throws Exception {
        Product prod = testReader(input_L2_GSLC_H5.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        //validator.validateProduct();
        //validator.validateMetadata();
        //validator.validateBands(new String[] {"i","q","Intensity"});
    }


    /**
     * Open all files in a folder recursively
     *
     * @throws Exception anything
     */
//    @Test
//    public void testOpenAll() throws Exception {
//        TestProcessor testProcessor = SARTests.createTestProcessor();
//        testProcessor.recurseReadFolder(this, rootPathNISAR, readerPlugIn, reader, null, exceptionExemptions);
//    }
}
