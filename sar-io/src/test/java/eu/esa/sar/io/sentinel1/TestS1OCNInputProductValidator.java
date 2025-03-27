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

import eu.esa.sar.commons.test.ReaderTest;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assume.assumeTrue;

/**
 * Validates input products using commonly used verifications
 */
public class TestS1OCNInputProductValidator extends ReaderTest {

    public final static File inputS1_IW_OCN = new File(TestData.inputSAR+"S1/OCN/S1A_IW_OCN__2SDV_20170317T221705_20170317T221730_015738_019E85_FACA.zip");
    public final static File inputS1_WV_OCN = new File(TestData.inputSAR+"S1/OCN/S1A_WV_OCN__2SSV_20150630T131602_20150630T133818_006603_008CD6_19BF.zip");
    public final static File inputS1_WV_SLC = new File(TestData.inputSAR+"S1/OCN/S1A_WV_SLC__1SSV_20240818T071513_20240818T071530_055264_06BCBE_F4BF.SAFE.zip");

    public TestS1OCNInputProductValidator() {
        super(new Sentinel1ProductReaderPlugIn());
    }

    @Before
    public void setUp() {
        // If any of the file does not exist: the test will be ignored
        assumeTrue(inputS1_IW_OCN + " not found", inputS1_IW_OCN.exists());
        assumeTrue(inputS1_WV_OCN + " not found", inputS1_WV_OCN.exists());
        assumeTrue(inputS1_WV_SLC + " not found", inputS1_WV_SLC.exists());
    }

    @Test
    public void TestSentinel1_IW_OCNProduct() throws Exception {
        try(final Product sourceProduct = testReader(inputS1_IW_OCN.toPath())) {
            if (sourceProduct != null) {
                final InputProductValidator validator = new InputProductValidator(sourceProduct);

                validator.checkIfSentinel1Product();
                validator.checkProductType(new String[]{"OCN"});
                validator.checkIfTOPSARBurstProduct(false);
                validator.checkAcquisitionMode(new String[]{"IW"});
            }
        }
    }

    @Test
    public void TestSentinel1_WV_OCNProduct() throws Exception {
        final Product sourceProduct = testReader(inputS1_WV_OCN.toPath());
        if(sourceProduct != null) {
            final InputProductValidator validator = new InputProductValidator(sourceProduct);

            validator.checkIfSentinel1Product();
            validator.checkProductType(new String[]{"OCN"});
            validator.checkIfTOPSARBurstProduct(false);
            validator.checkAcquisitionMode(new String[]{"WV"});
        }
    }

    @Test
    public void TestSentinel1_WV_SLCProduct() throws Exception {
        final Product sourceProduct = testReader(inputS1_WV_SLC.toPath());
        if(sourceProduct != null) {
            final InputProductValidator validator = new InputProductValidator(sourceProduct);

            validator.checkIfSentinel1Product();
            validator.checkProductType(new String[]{"SLC"});
            validator.checkIfTOPSARBurstProduct(false);
            validator.checkAcquisitionMode(new String[]{"WV"});
        }
    }
}


