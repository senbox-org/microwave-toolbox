/*
 * Copyright (C) 2021 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.io.spacety;

import com.bc.ceres.test.LongTestRunner;
import eu.esa.sar.commons.test.ProductValidator;
import eu.esa.sar.commons.test.ReaderTest;
import eu.esa.sar.commons.test.SARTests;
import org.esa.snap.core.datamodel.Product;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

import static org.junit.Assume.assumeTrue;

/**
 * Test Product Reader.
 *
 * @author lveci
 */
@RunWith(LongTestRunner.class)
public class TestSpacetyZips extends ReaderTest {

    final static File slc_sp1_zip = new File(SARTests.inputPathProperty +
            "/SAR/Spacety/SLC/BC1_SP_SLC_1SSV_20210405T201755_000480_0001E0.zip");

    final static File slc_sm1_zip = new File(SARTests.inputPathProperty +
            "/SAR/Spacety/SLC/BC1_SM_SLC_1SSV_20210324T214340_000394_00018A.zip");
    final static File slc_sm2_zip = new File(SARTests.inputPathProperty +
            "/SAR/Spacety/SLC/BC1_SM_SLC_1SSV_20210327T145357_000411_00019B.zip");

    final static File l2_sm_zip = new File(SARTests.inputPathProperty +
            "/SAR/Spacety/Level2/BC1_SM_ORG_2SSV_20210606T100859_000963_0003C3.zip");
    final static File l2_sp_zip = new File(SARTests.inputPathProperty +
            "/SAR/Spacety/Level2/BC1_SP_ORG_2SSV_20210521T082626_000804_000324.zip");

    final static File slc_ns1_zip = new File(SARTests.inputPathProperty +
            "/SAR/Spacety/SLC/BC1_NS_SLC_1SSV_20210326T012419_000395_00018B.zip");

    @Before
    public void setUp() {
        // If the file does not exist: the test will be ignored
        assumeTrue(slc_sp1_zip + " not found", slc_sp1_zip.exists());

        assumeTrue(l2_sm_zip + " not found", l2_sm_zip.exists());
        assumeTrue(l2_sp_zip + " not found", l2_sp_zip.exists());

        assumeTrue(slc_ns1_zip + " not found", slc_ns1_zip.exists());
    }

    public TestSpacetyZips() {
        super(new SpacetyProductReaderPlugIn());
    }

    @Test
    public void testOpeningZip_SP1() throws Exception {
        try(Product prod = testReader(slc_sp1_zip.toPath())) {

            final ProductValidator validator = new ProductValidator(prod);
            validator.validateProduct();
            validator.validateMetadata();
            validator.validateBands(new String[]{"i_VV", "q_VV", "Intensity_VV"});
        }
    }

    @Test
    public void testOpeningZip_SM1() throws Exception {
        try(Product prod = testReader(slc_sm1_zip.toPath())) {

            final ProductValidator validator = new ProductValidator(prod);
            validator.validateProduct();
            validator.validateMetadata();
            validator.validateBands(new String[]{"i_VV", "q_VV", "Intensity_VV"});
        }
    }

    @Test
    public void testOpeningZip_SM2() throws Exception {
        try(Product prod = testReader(slc_sm2_zip.toPath())) {

            final ProductValidator validator = new ProductValidator(prod);
            validator.validateProduct();
            validator.validateMetadata();
            validator.validateBands(new String[]{"i_VV", "q_VV", "Intensity_VV"});
        }
    }

    @Test
    public void testOpeningZip_NS1() throws Exception {
        try(Product prod = testReader(slc_ns1_zip.toPath())) {

            final ProductValidator validator = new ProductValidator(prod);
            validator.validateProduct();
            validator.validateMetadata();
            validator.validateBands(new String[]{"i_VV_NS1", "q_VV_NS1", "Intensity_VV_NS1", "i_VV_NS2", "q_VV_NS2", "Intensity_VV_NS2", "i_VV_NS3", "q_VV_NS3", "Intensity_VV_NS3", "i_VV_NS4", "q_VV_NS4", "Intensity_VV_NS4"});
        }
    }
}
