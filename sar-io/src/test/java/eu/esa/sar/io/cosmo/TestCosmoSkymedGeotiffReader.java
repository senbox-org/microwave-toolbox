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

import com.bc.ceres.annotation.STTM;
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
public class TestCosmoSkymedGeotiffReader extends ReaderTest {

    public final static File inputSM_GeoTiff_1B_tif = new File(TestData.inputSAR + "Cosmo/STRIPMAP/HH_Level_1B_TIFF/CSG_SSAR1_DGM_B_0101_STR_012_HH_RD_F_20200921215026_20200921215032_1_F_09S_Z19_N00.IMG.tif");
    public final static File inputSM_GeoTiff_1B_xml = new File(TestData.inputSAR + "Cosmo/STRIPMAP/HH_Level_1B_TIFF/CSG_SSAR1_DGM_B_0101_STR_012_HH_RD_F_20200921215026_20200921215032_1_F_09S_Z19_N00.attribs.xml");
    public final static File inputSM_GeoTiff_1C_tif = new File(TestData.inputSAR + "Cosmo/STRIPMAP/HH_Level_1C_GeoTIFF/CSG_SSAR1_GEC_B_0101_STR_012_HH_RD_F_20200921215026_20200921215032_1_F_09S_Z19_N00.IMG.tif");

    public final static File inputSC_GeoTiff_DGM_tif = new File(TestData.inputSAR + "Cosmo/SCANSAR-1/HH_Level_1B_TIFF/CSG_SSAR1_DGM_B_0301_SC1_001_HH_RA_F_20200923102555_20200923102610_1_F_41N_Z18_N00.IMG.tif");
    public final static File inputSC_GeoTiff_GEC_tif = new File(TestData.inputSAR + "Cosmo/SCANSAR-1/HH_Level_1C_GeoTIFF/CSG_SSAR1_GEC_B_0301_SC1_001_HH_RA_F_20200923102555_20200923102610_1_F_41N_Z18_N00.IMG.tif");


    private final static String inputCosmo = SARTests.inputPathProperty + "SAR/Cosmo/";
    private final static File[] rootPathsCosmoSkymed = SARTests.loadFilePath(inputCosmo);

    private String[] exceptionExemptions = {"not supported"};

    @Before
    public void setUp() {
        // If the file does not exist: the test will be ignored
        assumeTrue(inputSM_GeoTiff_1B_tif + " not found", inputSM_GeoTiff_1B_tif.exists());
        assumeTrue(inputSM_GeoTiff_1B_xml + " not found", inputSM_GeoTiff_1B_xml.exists());
        assumeTrue(inputSM_GeoTiff_1C_tif + " not found", inputSM_GeoTiff_1C_tif.exists());

        assumeTrue(inputSC_GeoTiff_DGM_tif + " not found", inputSC_GeoTiff_DGM_tif.exists());
        assumeTrue(inputSC_GeoTiff_GEC_tif + " not found", inputSC_GeoTiff_GEC_tif.exists());
    }

    public TestCosmoSkymedGeotiffReader() {
        super(new CosmoSkymedReaderPlugIn());
    }

    @Test
    @STTM("SNAP-2602")
    public void testOpeningSM_GeoTiff_1B_tif() throws Exception {
        Product prod = testReader(inputSM_GeoTiff_1B_tif.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"Amplitude_HH","Intensity_HH"});
    }

    @Test
    @STTM("SNAP-2602")
    public void testOpeningSM_GeoTiff_1B_xml() throws Exception {
        Product prod = testReader(inputSM_GeoTiff_1B_xml.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"Amplitude_HH","Intensity_HH"});
    }

    @Test
    @STTM("SNAP-2602")
    public void testOpeningSM_GeoTiff_1C_tif() throws Exception {
        Product prod = testReader(inputSM_GeoTiff_1C_tif.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"Amplitude_HH","Intensity_HH"});
    }


    @Test
    @STTM("SNAP-2602")
    public void testOpeningSC_GeoTiff_DGM_tif() throws Exception {
        Product prod = testReader(inputSC_GeoTiff_DGM_tif.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"Amplitude_HH","Intensity_HH"});
    }

    @Test
    @STTM("SNAP-2602")
    public void testOpeningSC_GeoTiff_GEC_tif() throws Exception {
        Product prod = testReader(inputSC_GeoTiff_GEC_tif.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"Amplitude_HH","Intensity_HH"});
    }

    /**
     * Open all files in a folder recursively
     *
     * @throws Exception anything
     */
    @Test
    @STTM("SNAP-2602")
    public void testOpenAll() throws Exception {
        TestProcessor testProcessor = SARTests.createTestProcessor();
        testProcessor.recurseReadFolder(this, rootPathsCosmoSkymed, readerPlugIn, reader, null, exceptionExemptions);
    }
}
