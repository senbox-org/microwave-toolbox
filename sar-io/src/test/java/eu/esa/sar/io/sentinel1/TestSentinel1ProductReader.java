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
package eu.esa.sar.io.sentinel1;

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
public class TestSentinel1ProductReader extends ReaderTest {

    public final static File inputS1_AnnotGRD = new File(TestData.inputSAR+"S1/S1A_IW_GRDH_1ADV_20140819T224528_20140819T224546_002015_001F3B_979A.SAFE");
    public final static File inputS1_AnnotGRDZip = new File(TestData.inputSAR+"S1/S1A_IW_GRDH_1ADV_20140819T224528_20140819T224546_002015_001F3B_979A.zip");
    public final static File inputS1_meta1GRD = new File(TestData.inputSAR+"S1/bandless1/manifest.safe");
    public final static File inputS1_meta2GRD = new File(TestData.inputSAR+"S1/bandless2/manifest.safe");

    public final static File inputGRDFolder = new File(TestData.inputSAR + "S1/AWS/S1A_IW_GRDH_1SDV_20180719T002854_20180719T002919_022856_027A78_042A");

    public final static File inputS1_IW_SLC_ZIP = new File(TestData.inputSAR+"S1/SLC/Etna-DLR/S1A_IW_SLC__1SDV_20140809T165546_20140809T165613_001866_001C20_088B.zip");

    private final String[] productTypeExemptions = {"RAW","OCN"};

    private final static String inputS1 = SARTests.inputPathProperty + "/SAR/S1/";
    private final static File[] rootPathsSentinel1 = SARTests.loadFilePath(inputS1);

    private final static File inputS1_GRDFile = TestData.inputS1_GRD;

    final static ProductValidator.Options productOptions = new ProductValidator.Options();

    @Before
    public void setUp() {
        // If the file does not exist: the test will be ignored
        assumeTrue(inputS1_GRDFile + " not found", inputS1_GRDFile.exists());
        assumeTrue(inputS1_AnnotGRD + " not found", inputS1_AnnotGRD.exists());
        assumeTrue(inputS1_meta1GRD + " not found", inputS1_meta1GRD.exists());
        assumeTrue(inputS1_meta2GRD + " not found", inputS1_meta2GRD.exists());
        assumeTrue(inputS1_AnnotGRDZip + " not found", inputS1_AnnotGRDZip.exists());
        assumeTrue(inputGRDFolder + " not found", inputGRDFolder.exists());

        for (File file : rootPathsSentinel1) {
            assumeTrue(file + " not found", file.exists());
        }

        productOptions.verifyBands = false;
    }

    public TestSentinel1ProductReader() {
        super(new Sentinel1ProductReaderPlugIn());
    }

    /**
     * Open all files in a folder recursively
     *
     * @throws Exception anything
     */
    @Test
    public void testOpenAll() throws Exception {
        TestProcessor testProcessor = SARTests.createTestProcessor();
        testProcessor.recurseReadFolder(this, rootPathsSentinel1, readerPlugIn, reader, productTypeExemptions, null);
    }

//    @Test
//    public void testOpeningFile() throws Exception {
//        Product prod = testReader(inputS1_AnnotGRD.toPath().resolve("manifest.safe"));
//
//        final ProductValidator validator = new ProductValidator(prod, productOptions);
//        validator.validateProduct();
//        validator.validateMetadata();
//        validator.validateBands(new String[] {});
//    }

    @Test
    public void testOpeningBandlessMetadataFile1() throws Exception {
        try(Product prod = testReader(inputS1_meta1GRD.toPath())) {

            final ProductValidator validator = new ProductValidator(prod, productOptions);
            validator.validateProduct();
            validator.validateMetadata();
            validator.validateBands(new String[]{});
        }
    }

    @Test
    public void testOpeningBandlessMetadataFile2() throws Exception {
        try(Product prod = testReader(inputS1_meta2GRD.toPath())) {

            final ProductValidator validator = new ProductValidator(prod, productOptions);
            validator.validateProduct();
            validator.validateMetadata();
            validator.validateBands(new String[]{});
        }
    }

//    @Test
//    public void testOpeningAnnotFolder() throws Exception {
//        Product prod = testReader(inputS1_AnnotGRD.toPath());
//
//        final ProductValidator validator = new ProductValidator(prod, productOptions);
//        validator.validateProduct();
//        validator.validateMetadata();
//        validator.validateBands(new String[] {});
//    }

//    @Test
//    public void testOpeningZip() throws Exception {
//        Product prod = testReader(inputS1_GRDFile.toPath());
//
//        final ProductValidator validator = new ProductValidator(prod);
//        validator.validateProduct();
//        validator.validateMetadata();
//        validator.validateBands(new String[] {"Amplitude_VV","Intensity_VV","Amplitude_VH","Intensity_VH"});
//    }
//
//    @Test
//    public void testOpeningAnnotationProduct() throws Exception {
//        Product prod = testReader(inputS1_AnnotGRDZip.toPath());
//
//        final ProductValidator validator = new ProductValidator(prod, productOptions);
//        validator.validateProduct();
//        validator.validateMetadata();
//        validator.validateBands(new String[] {});
//    }

//    @Test
//    public void testOpeningFolder() throws Exception {
//        Product prod = testReader(inputGRDFolder.toPath());
//
//        final ProductValidator validator = new ProductValidator(prod);
//        validator.validateProduct();
//        validator.validateMetadata();
//        validator.validateBands(new String[] {"Amplitude_VV","Intensity_VV","Amplitude_VH","Intensity_VH"});
//    }

    @Test
    public void testOpeningIW_SLC_Zip() throws Exception {
        try(Product prod = testReader(inputS1_IW_SLC_ZIP.toPath())) {

            final ProductValidator validator = new ProductValidator(prod);
            validator.validateProduct();
            validator.validateMetadata();
            validator.validateBands(new String[]{
                    "i_IW1_VH", "q_IW1_VH", "Intensity_IW1_VH",
                    "i_IW1_VV", "q_IW1_VV", "Intensity_IW1_VV",
                    "i_IW2_VH", "q_IW2_VH", "Intensity_IW2_VH",
                    "i_IW2_VV", "q_IW2_VV", "Intensity_IW2_VV",
                    "i_IW3_VH", "q_IW3_VH", "Intensity_IW3_VH",
                    "i_IW3_VV", "q_IW3_VV", "Intensity_IW3_VV"});
        }
    }
}
