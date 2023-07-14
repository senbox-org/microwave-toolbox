/*
 * Copyright (C) 2018 Skywatch. https://www.skywatch.com
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
package org.csa.rstb.io.rcm;

import eu.esa.sar.commons.test.ProductValidator;
import eu.esa.sar.commons.test.ReaderTest;
import eu.esa.sar.commons.test.SARTests;
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
public class TestEODMSRCMProductReader extends ReaderTest {

    private final static File eodmsGRDZip = new File(TestData.inputSAR +"RCM/EODMS/GRD/RCM1_OK1024567_PK1025865_1_SC50MA_20191205_225938_HH_HV_GRD.zip");
    private final static File eodmsGRDFolder = new File(TestData.inputSAR +"RCM/EODMS/GRD/RCM1_OK1024567_PK1025865_1_SC50MA_20191205_225938_HH_HV_GRD");
    private final static File eodmsGRDManifest = new File(TestData.inputSAR +"RCM/EODMS/GRD/RCM1_OK1024567_PK1025865_1_SC50MA_20191205_225938_HH_HV_GRD/manifest.safe");

    private final static File eodmsGRCZip = new File(TestData.inputSAR +"RCM/EODMS/GCD/RCM2_OK1028884_PK1029284_2_16M12_20191214_111155_HH_HV_GCD.zip");
    private final static File eodmsGRCFolder = new File(TestData.inputSAR +"RCM/EODMS/GCD/RCM2_OK1028884_PK1029284_2_16M12_20191214_111155_HH_HV_GCD");
    private final static File eodmsGRCManifest = new File(TestData.inputSAR +"RCM/EODMS/GCD/RCM2_OK1028884_PK1029284_2_16M12_20191214_111155_HH_HV_GCD/manifest.safe");

    private final static File eodmsScanSAR_CX_SLCZip = new File(TestData.inputSAR +"RCM/EODMS/SLC/RCM1_OK1683816_PK1836418_1_SC30MCPB_20211026_112824_CH_CV_SLC.zip");

    private final static File eodmsQP_SLCZip = new File(TestData.inputSAR +"RCM/EODMS/SLC/RCM1_OK1925486_PK1946630_1_QP8_20220103_110328_HH_VV_HV_VH_SLC.zip");

    private final static File inputQP_SLC = new File(TestData.inputSAR + "RCM/OpenData/Quebec City/RCM3_OK1050546_PK1050547_1_QP8_20191229_110339_HH_VV_HV_VH_SLC");

    private final static File inputCP_SLC = new File(TestData.inputSAR + "RCM/OpenData/Winnipeg/RCM1_OK1050595_PK1051816_1_3MCP24_20200219_123901_CH_CV_SLC");
    private final static File inputCP_GCC = new File(TestData.inputSAR + "RCM/OpenData/Winnipeg/RCM1_OK1052791_PK1052794_1_3MCP24_20200219_123901_CH_CV_GCC");
    private final static File inputCP_GRC = new File(TestData.inputSAR + "RCM/OpenData/Winnipeg/RCM1_OK1052791_PK1052795_1_3MCP24_20200219_123901_CH_CV_GRC");
    private final static File inputCP_GRD = new File(TestData.inputSAR + "RCM/OpenData/Winnipeg/RCM1_OK1052791_PK1052796_1_3MCP24_20200219_123901_CH_CV_GCD");

    public TestEODMSRCMProductReader() {
        super(new RCMProductReaderPlugIn());
    }

    @Before
    public void setUp() {
        // If any of the file does not exist: the test will be ignored
        assumeTrue(eodmsGRDZip + " not found", eodmsGRDZip.exists());
        assumeTrue(eodmsGRDFolder + " not found", eodmsGRDFolder.exists());
        assumeTrue(eodmsGRDManifest + " not found", eodmsGRDManifest.exists());

        assumeTrue(eodmsGRCFolder + " not found", eodmsGRCFolder.exists());
        assumeTrue(eodmsGRCManifest + " not found", eodmsGRCManifest.exists());

        //assumeTrue(eodmsScanSAR_CX_SLCZip + " not found", eodmsScanSAR_CX_SLCZip.exists());
        assumeTrue(eodmsQP_SLCZip + " not found", eodmsQP_SLCZip.exists());

        assumeTrue(inputQP_SLC + " not found", inputQP_SLC.exists());
        assumeTrue(inputCP_SLC + " not found", inputCP_SLC.exists());
        assumeTrue(inputCP_GCC + " not found", inputCP_GCC.exists());
        assumeTrue(inputCP_GRC + " not found", inputCP_GRC.exists());
        assumeTrue(inputCP_GRD + " not found", inputCP_GRD.exists());
    }

    @Test
    public void testOpeningGRDManifest() throws Exception {
        Product prod = testReader(eodmsGRDManifest.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"Amplitude_HH","Intensity_HH", "Amplitude_HV","Intensity_HV"});
    }

    @Test
    public void testOpeningGRDFolder() throws Exception {
        Product prod = testReader(eodmsGRDFolder.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"Amplitude_HH","Intensity_HH", "Amplitude_HV","Intensity_HV"});
    }

    @Test
    public void testOpeningGRCManifest() throws Exception {
        Product prod = testReader(eodmsGRCManifest.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"Amplitude_HH","Intensity_HH", "Amplitude_HV","Intensity_HV"});
    }

    @Test
    public void testOpeningGRCFolder() throws Exception {
        Product prod = testReader(eodmsGRCFolder.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"Amplitude_HH","Intensity_HH", "Amplitude_HV","Intensity_HV"});
    }

    @Test
    public void testOpeningGRCZip() throws Exception {
        Product prod = testReader(eodmsGRCZip.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"Amplitude_HH","Intensity_HH", "Amplitude_HV","Intensity_HV"});
    }

//    @Test
//    public void testOpeningScanSAR_CX_SLCZip() throws Exception {
//        Product prod = testReader(eodmsScanSAR_CX_SLCZip.toPath());
//
//        final ProductValidator validator = new ProductValidator(prod);
//        validator.validateProduct();
//        validator.validateMetadata();
//        validator.validateBands(new String[] {"i_RCH","q_RCH","Intensity_RCH","i_RCV","q_RCV","Intensity_RCV"});
//    }

    @Test
    public void testOpeningQP_SLCZip() throws Exception {
        Product prod = testReader(eodmsQP_SLCZip.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"i_HH","q_HH","Intensity_HH", "i_HV","q_HV","Intensity_HV","i_VV","q_VV","Intensity_VV", "i_VH","q_VH","Intensity_VH"});
    }

    @Test
    public void testOpeningQP_SLC() throws Exception {
        Product prod = testReader(inputQP_SLC.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"i_HH","q_HH","Intensity_HH", "i_HV","q_HV","Intensity_HV","i_VV","q_VV","Intensity_VV", "i_VH","q_VH","Intensity_VH"});
    }

    @Test
    public void testOpeningCP_SLC() throws Exception {
        Product prod = testReader(inputCP_SLC.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"i_RCH","q_RCH","Intensity_RCH","i_RCV","q_RCV","Intensity_RCV"});
    }

    @Test
    public void testOpeningCP_GCC() throws Exception {
        Product prod = testReader(inputCP_GCC.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"Amplitude_RCH","Intensity_RCH","Amplitude_RCV","Intensity_RCV"});
    }

    @Test
    public void testOpeningCP_GRC() throws Exception {
        Product prod = testReader(inputCP_GRC.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"i_RCH","q_RCH","Intensity_RCH","i_RCV","q_RCV","Intensity_RCV"});
    }

    @Test
    public void testOpeningCP_GRD() throws Exception {
        Product prod = testReader(inputCP_GRD.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"Amplitude_RCH","Intensity_RCH", "Amplitude_RCV","Intensity_RCV"});
    }
}
