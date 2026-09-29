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
package eu.esa.sar.sentinel1.gpf.etadcorrectors;

import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ETADUtils tests. The ETAD SAFE-zip fixture is loaded once per class via
 * {@link #setUpClass()} (extraction + NetCDF parsing is expensive), and the
 * shared {@link Product} is reused across tests. Each test that needs an
 * {@link ETADUtils} instance constructs its own from the shared product so
 * mutations to {@code inputProducts} stay isolated to that test.
 *
 * <p>If the fixture is not present on the machine, the per-test {@link #setUp()}
 * turns it into a JUnit "skipped". {@code assumeTrue} inside {@code @BeforeClass}
 * is not reliable across JUnit 4 versions, hence the two-step pattern.</p>
 */
public class ETADUtilsTest {

    protected final static File etadInSAR1 = new File(TestData.inputSAR +"S1/ETAD/IW/InSAR/S1B_IW_ETA__AXDV_20200815T173048_20200815T173116_022937_02B897_E56D.SAFE.zip");

    private static Product sourceProduct;

    @BeforeClass
    public static void setUpClass() throws Exception {
        // Bail out cheaply if the fixture isn't available; per-test @Before will
        // turn this into a JUnit "skipped" via assumeTrue.
        if (!etadInSAR1.exists()) {
            return;
        }

        sourceProduct = TestUtils.readSourceProduct(etadInSAR1);
    }

    @AfterClass
    public static void tearDownClass() {
        if (sourceProduct != null) {
            sourceProduct.dispose();
            sourceProduct = null;
        }
    }

    @Before
    public void setUp() {
        // If the fixture file does not exist: the test will be ignored.
        assumeTrue(etadInSAR1 + " not found", etadInSAR1.exists());
        assertNotNull("ETAD source product fixture was not initialised", sourceProduct);
    }

    @Test
    public void testTOPSCorrectorInSAR() throws Exception {
        ETADUtils etadUtils = new ETADUtils(sourceProduct);
    }

    // Correctly parses a valid date-time string with 'T' separator
    @Test
    public void test_parses_valid_datetime_with_T_separator() {
        MetadataElement elem = mock(MetadataElement.class);
        when(elem.getAttributeString("testTag", AbstractMetadata.NO_METADATA_STRING)).thenReturn("2023-10-01T12:34:56");

        ProductData.UTC result = ETADUtils.getTime(elem, "testTag");

        assertNotNull(result);
        assertEquals("01-OCT-2023 12:34:56.000000", result.format());
    }

    // Handles a date-time string with no 'T' separator
    @Test
    public void test_handles_datetime_without_T_separator() {
        MetadataElement elem = mock(MetadataElement.class);
        when(elem.getAttributeString("testTag", AbstractMetadata.NO_METADATA_STRING)).thenReturn("2023-10-01 12:34:56");

        ProductData.UTC result = ETADUtils.getTime(elem, "testTag");

        assertNotNull(result);
        assertEquals("01-OCT-2023 12:34:56.000000", result.format());
    }

    // Returns correct band name for single-digit bIndex
    @Test
    public void test_single_digit_bIndex() throws Exception {
        ETADUtils etadUtils = new ETADUtils(sourceProduct);

        String result = etadUtils.createBandName("S1A", 5, "VV");
        assertEquals("S1A_Burst0005_VV", result);
    }

    // Handles negative bIndex values
    @Test
    public void test_negative_bIndex() throws Exception {
        ETADUtils etadUtils = new ETADUtils(sourceProduct);

        String result = etadUtils.createBandName("S1A", -1, "VV");
        assertEquals("S1A_Burst000-1_VV", result);
    }

    // Returns correct product index when azimuthTime is within the range of a product
    @Test
    public void test_returns_correct_product_index_within_range() throws Exception {
        ETADUtils etadUtils = new ETADUtils(sourceProduct);
        ETADUtils.InputProduct product1 = new ETADUtils.InputProduct();
        product1.startTime = 10.0;
        product1.stopTime = 20.0;
        product1.pIndex = 1;

        ETADUtils.InputProduct product2 = new ETADUtils.InputProduct();
        product2.startTime = 21.0;
        product2.stopTime = 30.0;
        product2.pIndex = 2;

        etadUtils.inputProducts = new ETADUtils.InputProduct[]{product1, product2};

        int result = etadUtils.getProductIndex(15.0);

        assertEquals(1, result);
    }

    // Handles empty inputProducts array without errors
    @Test
    public void test_handles_empty_input_products_array() throws Exception {
        ETADUtils etadUtils = new ETADUtils(sourceProduct);
        etadUtils.inputProducts = new ETADUtils.InputProduct[]{};

        int result = etadUtils.getProductIndex(15.0);

        assertEquals(-1, result);
    }

    // Returns correct index when productID contains the sensing start/stop time substring
    @Test
    public void test_correct_index_when_productID_contains_sensing_time() throws Exception {
        ETADUtils etadUtils = new ETADUtils(sourceProduct);
        ETADUtils.InputProduct inputProduct1 = new ETADUtils.InputProduct();
        inputProduct1.productID = "S1A_IW_GRDH_1SDV_20200101T120000_20200101T120023_030000_037E0D_1234";
        inputProduct1.pIndex = 0;
        ETADUtils.InputProduct inputProduct2 = new ETADUtils.InputProduct();
        inputProduct2.productID = "S1A_IW_GRDH_1SDV_20200101T120024_20200101T120047_030001_037E0D_5678";
        inputProduct2.pIndex = 1;
        etadUtils.inputProducts = new ETADUtils.InputProduct[]{inputProduct1, inputProduct2};

        String productName = "S1A_IW_GRDH_1SDV_20200101T120000_20200101T120023_030000_037E0D";
        int result = etadUtils.getProductIndex(productName);

        assertEquals(0, result);
    }
}
