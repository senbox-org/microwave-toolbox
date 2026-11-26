/*
 * Copyright (C) 2026 Skywatch. https://www.skywatch.com
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

import com.bc.ceres.test.LongTestRunner;
import eu.esa.sar.commons.test.ProductValidator;
import eu.esa.sar.commons.test.ReaderTest;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Product;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

import static org.junit.Assume.assumeTrue;

/**
 * Radarsat-2 reader tests on large zipped SGX/SGF products (7-22 s each).
 * Run with -Denable.long.tests=true.
 */
@RunWith(LongTestRunner.class)
public class TestRadarsat2ProductReaderLongTest extends ReaderTest {

    private static final File zipQP_SGX = new File(TestData.inputSAR +"RS2/RS2_OK76385_PK678075_DK606764_FQ15_20080506_142542_HH_VV_HV_VH_SGX.zip");
    private static final File zipDP_SGF = new File(TestData.inputSAR +"RS2/RS2_OK76385_PK678077_DK606766_S7_20081111_141314_HH_HV_SGF.zip");
    private static final File zipDP_SGX = new File(TestData.inputSAR +"RS2/RS2_OK76385_PK678083_DK606772_S7_20081111_141314_HH_HV_SGX.zip");

    public TestRadarsat2ProductReaderLongTest() {
        super(new Radarsat2ProductReaderPlugIn());
    }

    @Test
    public void testOpeningQP_SGX_Zip() throws Exception {
        assumeTrue(zipQP_SGX + " not found", zipQP_SGX.exists());
        Product prod = testReader(zipQP_SGX.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {
                "Amplitude_HH", "Intensity_HH", "Amplitude_HV", "Intensity_HV", "Amplitude_VH", "Intensity_VH", "Amplitude_VV", "Intensity_VV"});
    }

    @Test
    public void testOpeningDP_SGFZip() throws Exception {
        assumeTrue(zipDP_SGF + " not found", zipDP_SGF.exists());
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
        assumeTrue(zipDP_SGX + " not found", zipDP_SGX.exists());
        Product prod = testReader(zipDP_SGX.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {
                "Amplitude_HH","Intensity_HH",
                "Amplitude_HV","Intensity_HV"});
    }
}
