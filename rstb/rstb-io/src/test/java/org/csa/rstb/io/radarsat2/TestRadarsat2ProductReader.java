/*
 * Copyright (C) 2024 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package org.csa.rstb.io.radarsat2;

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
public class TestRadarsat2ProductReader extends ReaderTest {

    private static final File folderSLC = new File(TestData.inputSAR +"RS2/RS2_OK76385_PK678063_DK606752_FQ2_20080415_143807_HH_VV_HV_VH_SLC");
    private static final File metadataSLC = new File(TestData.inputSAR +"RS2/RS2_OK76385_PK678063_DK606752_FQ2_20080415_143807_HH_VV_HV_VH_SLC/product.xml");
    private static final File slc2 = new File(TestData.inputSAR +"RS2/RS2_OK2084_PK24911_DK25857_FQ14_20080802_225909_HH_VV_HV_VH_SLC/product.xml");
    private static final File inputRS2_SQuadFile = TestData.inputRS2_SQuad;

    private static final File zipQP_SGX = new File(TestData.inputSAR +"RS2/RS2_OK76385_PK678075_DK606764_FQ15_20080506_142542_HH_VV_HV_VH_SGX.zip");
    private static final File zipDP_SGF = new File(TestData.inputSAR +"RS2/RS2_OK76385_PK678077_DK606766_S7_20081111_141314_HH_HV_SGF.zip");
    private static final File zipDP_SGX = new File(TestData.inputSAR +"RS2/RS2_OK76385_PK678083_DK606772_S7_20081111_141314_HH_HV_SGX.zip");
    private static final File zipDP_SSG = new File(TestData.inputSAR +"RS2/RS2_OK76397_PK678155_DK606835_S7_20081111_141314_HH_HV_SSG.zip");

    private final static ProductValidator.Expected expectedSLC = new ProductValidator.Expected();

    @Before
    public void setUp() {
        // If any of the file does not exist: the test will be ignored
        assumeTrue(inputRS2_SQuadFile + " not found", inputRS2_SQuadFile.exists());
        assumeTrue(folderSLC + " not found", folderSLC.exists());
        assumeTrue(metadataSLC + " not found", metadataSLC.exists());
        assumeTrue(zipQP_SGX + " not found", zipQP_SGX.exists());
        assumeTrue(zipDP_SGF + " not found", zipDP_SGF.exists());
        assumeTrue(zipDP_SGX + " not found", zipDP_SGX.exists());
        assumeTrue(zipDP_SSG + " not found", zipDP_SSG.exists());

        expectedSLC.isSAR = true;
        expectedSLC.isComplex = true;
        expectedSLC.productType = "SLC";
    }

    public TestRadarsat2ProductReader() {
        super(new Radarsat2ProductReaderPlugIn());
    }

    @Test
    public void testOpeningFolder() throws Exception {
        Product prod = testReader(folderSLC.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.setExpected(expectedSLC);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {
                "i_HH","q_HH","Intensity_HH",
                "i_HV","q_HV","Intensity_HV",
                "i_VV","q_VV","Intensity_VV",
                "i_VH","q_VH","Intensity_VH"});
    }

    @Test
    public void testOpeningMetadataFile() throws Exception {
        Product prod = testReader(metadataSLC.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.setExpected(expectedSLC);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {
                "i_HH","q_HH","Intensity_HH",
                "i_HV","q_HV","Intensity_HV",
                "i_VV","q_VV","Intensity_VV",
                "i_VH","q_VH","Intensity_VH"});
    }

    @Test
    public void testOpeningQP() throws Exception {
        Product prod = testReader(inputRS2_SQuadFile.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.setExpected(expectedSLC);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {
                "i_HH","q_HH","Intensity_HH",
                "i_HV","q_HV","Intensity_HV",
                "i_VV","q_VV","Intensity_VV",
                "i_VH","q_VH","Intensity_VH"});
    }

    @Test
    public void testOpeningSLC2() throws Exception {
        Product prod = testReader(slc2.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.setExpected(expectedSLC);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {
                "i_HH","q_HH","Intensity_HH",
                "i_HV","q_HV","Intensity_HV",
                "i_VV","q_VV","Intensity_VV",
                "i_VH","q_VH","Intensity_VH"});
    }

    @Test
    public void testOpeningQP_SGX_Zip() throws Exception {
        Product prod = testReader(zipQP_SGX.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {
                "Amplitude_HH", "Intensity_HH", "Amplitude_HV", "Intensity_HV", "Amplitude_VH", "Intensity_VH", "Amplitude_VV", "Intensity_VV"});
    }

    @Test
    public void testOpeningDP_SGFZip() throws Exception {
        Product prod = testReader(zipDP_SGF.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {
                "Amplitude_HH","Intensity_HH",
                "Amplitude_HV","Intensity_HV"});
    }

    @Test
    public void testOpeningDP_SGXZip() throws Exception {
        Product prod = testReader(zipDP_SGX.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {
                "Amplitude_HH","Intensity_HH",
                "Amplitude_HV","Intensity_HV"});
    }

    @Test
    public void testOpeningDP_SSGZip() throws Exception {
        Product prod = testReader(zipDP_SSG.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {
                "Amplitude_HH","Intensity_HH",
                "Amplitude_HV","Intensity_HV"});
    }
}
