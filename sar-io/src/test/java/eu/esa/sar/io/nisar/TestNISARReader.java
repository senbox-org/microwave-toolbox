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
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"i_ifg_HH", "q_ifg_HH", "Intensity_ifg_HH", "Phase_HH", "coherenceMagnitude_HH", "alongTrackOffset_HH", "slantRangeOffset_HH", "correlationSurfacePeak_HH"});
        validator.validateBandData();
    }

    @Test
    public void testOpening_L1_ROFF_H5() throws Exception {
        Product prod = testReader(input_L1_ROFF_H5.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"L1_alongTrackOffsetVariance_HH", "L2_alongTrackOffset_HH", "L1_slantRangeOffset_HH", "L1_slantRangeOffsetVariance_HH", "L2_snr_HH", "L3_slantRangeOffsetVariance_HH", "L2_crossOffsetVariance_HH", "L2_slantRangeOffset_HH", "L2_slantRangeOffsetVariance_HH", "L1_crossOffsetVariance_HH", "L2_alongTrackOffsetVariance_HH", "L1_correlationSurfacePeak_HH", "L1_alongTrackOffset_HH", "L1_snr_HH", "L3_alongTrackOffset_HH", "L3_alongTrackOffsetVariance_HH", "L3_snr_HH", "L2_correlationSurfacePeak_HH", "L3_correlationSurfacePeak_HH", "L3_crossOffsetVariance_HH", "L3_slantRangeOffset_HH"});
        validator.validateBandData();
    }


    @Test
    public void testOpening_L1_RUNW_H5() throws Exception {
        Product prod = testReader(input_L1_RUNW_H5.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"coherenceMagnitude_HH", "connectedComponents_HH", "ionospherePhaseScreen_HH",
                "ionospherePhaseScreenUncertainty_HH", "unwrappedPhase_HH", "alongTrackOffset_HH", "slantRangeOffset_HH", "correlationSurfacePeak_HH"});
        validator.validateBandData();
    }

    @Test
    public void testOpening_L1_RSLC_H5() throws Exception {
        Product prod = testReader(input_L1_RSLC_H5.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"i_HH","q_HH","Intensity_HH", "Phase_HH"});
        validator.validateBandData();
    }

    @Test
    public void testOpening_L2_GOFF_H5() throws Exception {
        Product prod = testReader(input_L2_GOFF_H5.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"L1_alongTrackOffset_HH", "L1_alongTrackOffsetVariance_HH", "L1_slantRangeOffset_HH", "L1_slantRangeOffsetVariance_HH", "L1_correlationSurfacePeak_HH", "L1_crossOffsetVariance_HH", "L1_snr_HH",
                "L2_alongTrackOffset_HH", "L2_alongTrackOffsetVariance_HH", "L2_slantRangeOffset_HH", "L2_slantRangeOffsetVariance_HH", "L2_correlationSurfacePeak_HH", "L2_crossOffsetVariance_HH", "L2_snr_HH",
                "L3_alongTrackOffset_HH", "L3_alongTrackOffsetVariance_HH", "L3_slantRangeOffset_HH", "L3_slantRangeOffsetVariance_HH", "L3_correlationSurfacePeak_HH", "L3_crossOffsetVariance_HH", "L3_snr_HH"});
        validator.validateBandData();
    }

    @Test
    public void testOpening_L2_GUNW_H5() throws Exception {
        Product prod = testReader(input_L2_GUNW_H5.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"coherenceMagnitude_HH", "connectedComponents_HH", "ionospherePhaseScreen_HH", "ionospherePhaseScreenUncertainty_HH", "unwrappedPhase_HH", "wrappedInterferogram_HH", "alongTrackOffset_HH", "slantRangeOffset_HH", "correlationSurfacePeak_HH"});
        validator.validateBandData();
    }

    @Test
    public void testOpening_L3_SME2_H5() throws Exception {
        Product prod = testReader(input_L3_SME2_H5.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"Waterbody_fraction","landcover"});
        validator.validateBandData();
    }

    File RSLC = new File("E:\\out\\NISAR_L1_PR_RSLC_009_162_A_024_4005_DHDH_A_20260108T101627_20260108T101702_X05009_N_F_J_001.h5");
    File GCOV = new File("E:\\out\\NISAR_L2_PR_GCOV_009_162_A_024_4005_DHDH_A_20260108T101627_20260108T101702_X05009_N_F_J_001.h5");
    File GSLC = new File("E:\\out\\NISAR_L2_PR_GSLC_009_162_A_023_4005_DHDH_A_20260108T101553_20260108T101628_X05009_N_F_J_001.h5");

    @Test
    public void testOpening_L1_RSLC_H52() throws Exception {
        assumeTrue(RSLC + " not found", RSLC.exists());
        Product prod = testReader(RSLC.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"i_HH","q_HH","Intensity_HH", "Phase_HH",
                "i_HV", "q_HV", "Intensity_HV", "Phase_HV",
                "i_HH_S","q_HH_S","Intensity_HH_S", "Phase_HH_S",
                "i_HV_S", "q_HV_S", "Intensity_HV_S", "Phase_HV_S"});
        validator.validateBandData();
    }

    @Test
    public void testOpening_L2_GCOV_H52() throws Exception {
        assumeTrue(GCOV + " not found", GCOV.exists());
        Product prod = testReader(GCOV.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"HHHH","HVHV","HHHH_S","HVHV_S"});
        validator.validateBandData();
    }

    @Test
    @Ignore("GSLC file is truncated")
    public void testOpening_L2_GSLC_H52() throws Exception {
        Product prod = testReader(GSLC.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"i_HH","q_HH","Intensity_HH"});
        validator.validateBandData();
    }
}
