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
package org.csa.rstb.polarimetric.gpf;

import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.csa.rstb.polarimetric.gpf.PolTestAssertions.STACK_DATES;
import static org.csa.rstb.polarimetric.gpf.PolTestAssertions.assertSameBands;
import static org.csa.rstb.polarimetric.gpf.PolTestAssertions.assertStackBandsPerDate;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

/**
 * Unit test for PolarimetricSpeckleFilterOp.
 */
public class TestPolarimetricSpeckleFilterOp {

    static {
        TestUtils.initTestEnvironment();
    }

    private final static OperatorSpi spi = new PolarimetricSpeckleFilterOp.Spi();

    // Per-class cache of loaded source products to avoid repeated disk reads.
    // PolarimetricSpeckleFilterOp does not mutate its source product (it produces a separate
    // target product), so sharing a single read-only Product across tests is safe.
    private final static Map<String, Product> PRODUCT_CACHE = new HashMap<>();

    private static Product loadCached(final File inputFile) throws Exception {
        final String key = inputFile.getAbsolutePath();
        Product product = PRODUCT_CACHE.get(key);
        if (product == null) {
            product = TestUtils.readSourceProduct(inputFile);
            PRODUCT_CACHE.put(key, product);
        }
        return product;
    }

    @AfterClass
    public static void disposeCachedProducts() {
        for (Product p : PRODUCT_CACHE.values()) {
            if (p != null) {
                p.dispose();
            }
        }
        PRODUCT_CACHE.clear();
    }

    @Test
    public void testSpiCreatesOperator() {
        final PolarimetricSpeckleFilterOp op = (PolarimetricSpeckleFilterOp) spi.createOperator();
        assertNotNull(op);
    }

    @Test
    public void testOperatorMetadata() {
        final OperatorMetadata md = PolarimetricSpeckleFilterOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("Polarimetric-Speckle-Filter", md.alias());
    }

    private final static String inputPathQuad = TestData.inputSAR + "/QuadPol/QuadPol_subset_0_of_RS2-SLC-PDS_00058900.dim";
    private final static String inputQuadFullStack = TestData.inputSAR + "/QuadPolStack/RS2-Quad_Pol_Stack.dim";
    private final static String inputC3Stack = TestData.inputSAR + "/QuadPolStack/RS2-C3-Stack.dim";
    private final static String inputT3Stack = TestData.inputSAR + "/QuadPolStack/RS2-T3-Stack.dim";

    private final static String expectedBoxCar = TestUtils.TESTDATA_ROOT + "/expected/QuadPol/QuadPol_subset_0_of_RS2-SLC-PDS_00058900_BoxCar.dim";
    private final static String expectedRefinedLee = TestUtils.TESTDATA_ROOT + "/expected/QuadPol/QuadPol_subset_0_of_RS2-SLC-PDS_00058900_RefinedLee.dim";
    private final static String expectedIDAN = TestUtils.TESTDATA_ROOT + "/expected/QuadPol/QuadPol_subset_0_of_RS2-SLC-PDS_00058900_IDAN.dim";

    @Before
    public void setUp() {
        // If any of the file does not exist: the test will be ignored
        assumeTrue(inputPathQuad + " not found", new File(inputPathQuad).exists());
        assumeTrue(inputQuadFullStack + " not found", new File(inputQuadFullStack).exists());
        assumeTrue(inputC3Stack + " not found", new File(inputC3Stack).exists());
        assumeTrue(inputT3Stack + " not found", new File(inputT3Stack).exists());

        assumeTrue(expectedBoxCar + " not found", new File(expectedBoxCar).exists());
        assumeTrue(expectedRefinedLee + " not found", new File(expectedRefinedLee).exists());
        assumeTrue(expectedIDAN + " not found", new File(expectedIDAN).exists());
    }

    private Product runFilter(final PolarimetricSpeckleFilterOp op,
                              final String filterName, final String path) throws Exception {
        final File inputFile = new File(path);
        final Product sourceProduct = loadCached(inputFile);

        assertNotNull(op);
        op.setSourceProduct(sourceProduct);
        op.SetFilter(filterName);

        // get targetProduct: execute initialize()
        final Product targetProduct = op.getTargetProduct();
        TestUtils.verifyProduct(targetProduct, false, false);
        return targetProduct;
    }

    /**
     * Perform Box Car filtering of a Radarsat-2 product and compares it with processed product known to be correct
     *
     * @throws Exception general exception
     */
    @Test
    public void testBoxCarFilter() throws Exception {

        final PolarimetricSpeckleFilterOp op = (PolarimetricSpeckleFilterOp) spi.createOperator();
        final Product targetProduct = runFilter(op, PolarimetricSpeckleFilterOp.BOXCAR_SPECKLE_FILTER, inputPathQuad);
        TestUtils.compareProducts(targetProduct, expectedBoxCar, null);
    }

    /**
     * Perform Refined Lee filtering of a Radarsat-2 product and compares it with processed product known to be correct
     *
     * @throws Exception general exception
     */
    @Test
    public void testRefinedLeeFilter() throws Exception {

        final PolarimetricSpeckleFilterOp op = (PolarimetricSpeckleFilterOp) spi.createOperator();
        final Product targetProduct = runFilter(op, PolarimetricSpeckleFilterOp.REFINED_LEE_FILTER, inputPathQuad);
        TestUtils.compareProducts(targetProduct, expectedRefinedLee, null);
    }

    /**
     * Perform IDAN filtering of a Radarsat-2 product and compares it with processed product known to be correct
     *
     * @throws Exception general exception
     */
    @Test
    public void testIDANFilter() throws Exception {

        final PolarimetricSpeckleFilterOp op = (PolarimetricSpeckleFilterOp) spi.createOperator();
        final Product targetProduct = runFilter(op, PolarimetricSpeckleFilterOp.IDAN_FILTER, inputPathQuad);
        TestUtils.compareProducts(targetProduct, expectedIDAN, null);
    }

    // Stack
    // Every acquisition date must get the same filtered matrix bands as a single-date product of the same matrix
    // type, and since RS2-T3-Stack is the T3 of RS2-Quad_Pol_Stack, filtering either must give the same values.

    private Product filter(final String filterName, final Product source) {
        final PolarimetricSpeckleFilterOp op = (PolarimetricSpeckleFilterOp) spi.createOperator();
        op.setSourceProduct(source);
        op.SetFilter(filterName);
        return op.getTargetProduct();
    }

    private void assertStackFilter(final String filterName) throws Exception {
        final Product quad = loadCached(new File(inputPathQuad));
        final PolarimetricMatricesOp toC3 = new PolarimetricMatricesOp();
        toC3.setSourceProduct(quad);
        toC3.SetMatrixType(PolarimetricMatricesOp.C3);

        final Product singleT3 = filter(filterName, quad);
        final Product singleC3 = filter(filterName, toC3.getTargetProduct());

        final Product fromQuad = runFilter((PolarimetricSpeckleFilterOp) spi.createOperator(), filterName, inputQuadFullStack);
        final Product fromT3 = runFilter((PolarimetricSpeckleFilterOp) spi.createOperator(), filterName, inputT3Stack);
        assertStackBandsPerDate(singleT3, fromQuad, STACK_DATES);
        assertStackBandsPerDate(singleT3, fromT3, STACK_DATES);
        assertStackBandsPerDate(singleC3, runFilter((PolarimetricSpeckleFilterOp) spi.createOperator(), filterName, inputC3Stack), STACK_DATES);

        // The T3 stack is stored as float32: 0.1% relative, or 1% of the band's mean magnitude near zero.
        // The 2-pixel image border (half the default 5x5 window) is excluded: Box Car averages the window clipped
        // to the image for quad-pol input but replicates edge pixels for C3/T3 input, so border values differ.
        assertSameBands(filterName + " quad vs T3 stack", fromQuad, fromT3, 1e-3, 1e-2, 0.0, 2);
    }

    @Test
    public void testBoxCarStack() throws Exception {
        assertStackFilter(PolarimetricSpeckleFilterOp.BOXCAR_SPECKLE_FILTER);
    }

    @Test
    public void testRefinedLeeStack() throws Exception {
        assertStackFilter(PolarimetricSpeckleFilterOp.REFINED_LEE_FILTER);
    }

    @Test
    public void testIDANStack() throws Exception {
        assertStackFilter(PolarimetricSpeckleFilterOp.IDAN_FILTER);
    }
}
