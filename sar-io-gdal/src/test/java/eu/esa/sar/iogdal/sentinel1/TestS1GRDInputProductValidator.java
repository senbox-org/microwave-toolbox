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
package eu.esa.sar.iogdal.sentinel1;

import eu.esa.sar.commons.test.ProductValidator;
import eu.esa.sar.commons.test.ReaderTest;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Product;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import static org.junit.Assume.assumeTrue;

/**
 * Validates input products using commonly used verifications
 */
@Ignore("Handled by S1 Reader")
public class TestS1GRDInputProductValidator extends ReaderTest {

    public TestS1GRDInputProductValidator() {
        super(new Sentinel1GDALProductReaderPlugIn());
    }

    @Before
    public void setUp() {
        // If any of the file does not exist: the test will be ignored
        assumeTrue(TestData.inputS1_GRD + " not found", TestData.inputS1_GRD.exists());
    }

    @Test
    public void TestSentinel1GRDProduct() throws Exception {
        try(final Product prod = testReader(TestData.inputS1_GRD.toPath())) {

            final ProductValidator validator = new ProductValidator(prod);
            validator.validateProduct();
            validator.validateMetadata();
            validator.validateBands(new String[]{"Amplitude_VH", "Intensity_VH", "Amplitude_VV", "Intensity_VV"});
        }
    }
}


