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

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static org.csa.rstb.polarimetric.gpf.PolTestAssertions.STACK_DATES;
import static org.csa.rstb.polarimetric.gpf.PolTestAssertions.assertSameBands;
import static org.csa.rstb.polarimetric.gpf.PolTestAssertions.assertStackBandsPerDate;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

/**
 * Unit test for PolarimetricDecompositionOp.
 */
public class TestPolarimetricDecompositionOp {

    static {
        TestUtils.initTestEnvironment();
    }

    private final static OperatorSpi spi = new PolarimetricDecompositionOp.Spi();

    // Cache of source products, keyed by absolute path, to avoid re-reading the same product
    // across many tests. Safe because PolarimetricDecompositionOp only reads from the source
    // product (it constructs a new target product) and the helper does not mutate the source.
    private static final Map<String, Product> PRODUCT_CACHE = new ConcurrentHashMap<>();

    private static Product loadCached(final File file) throws Exception {
        final String key = file.getAbsolutePath();
        Product p = PRODUCT_CACHE.get(key);
        if (p == null) {
            p = TestUtils.readSourceProduct(file);
            PRODUCT_CACHE.put(key, p);
        }
        return p;
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
        final PolarimetricDecompositionOp op = (PolarimetricDecompositionOp) spi.createOperator();
        assertNotNull(op);
    }

    @Test
    public void testOperatorMetadata() {
        final OperatorMetadata md = PolarimetricDecompositionOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("Polarimetric-Decomposition", md.alias());
    }

    private final static String inputPathQuad = TestData.inputSAR + "/QuadPol/QuadPol_subset_0_of_RS2-SLC-PDS_00058900.dim";
    private final static String inputQuadFullStack = TestData.inputSAR + "/QuadPolStack/RS2-Quad_Pol_Stack.dim";
    private final static String inputC3Stack = TestData.inputSAR + "/QuadPolStack/RS2-C3-Stack.dim";
    private final static String inputT3Stack = TestData.inputSAR + "/QuadPolStack/RS2-T3-Stack.dim";

    private final static String expectedSinclair = TestUtils.TESTDATA_ROOT + "/expected/QuadPol/QuadPol_subset_0_of_RS2-SLC-PDS_00058900_Sinclair.dim";
    private final static String expectedPauli = TestUtils.TESTDATA_ROOT + "/expected/QuadPol/QuadPol_subset_0_of_RS2-SLC-PDS_00058900_Pauli.dim";
    private final static String expectedFreeman = TestUtils.TESTDATA_ROOT + "/expected/QuadPol/QuadPol_subset_0_of_RS2-SLC-PDS_00058900_FreemanDurden.dim";
    private final static String expectedYamaguchi = TestUtils.TESTDATA_ROOT + "/expected/QuadPol/QuadPol_subset_0_of_RS2-SLC-PDS_00058900_Yamaguchi.dim";
    private final static String expectedVanZyl = TestUtils.TESTDATA_ROOT + "/expected/QuadPol/QuadPol_subset_0_of_RS2-SLC-PDS_00058900_VanZyl.dim";
    private final static String expectedCloude = TestUtils.TESTDATA_ROOT + "/expected/QuadPol/QuadPol_subset_0_of_RS2-SLC-PDS_00058900_Cloude.dim";
    private final static String expectedHaAlpha = TestUtils.TESTDATA_ROOT + "/expected/QuadPol/QuadPol_subset_0_of_RS2-SLC-PDS_00058900_HaAlpha.dim";
    private final static String expectedTouzi = TestUtils.TESTDATA_ROOT + "/expected/QuadPol/QuadPol_subset_0_of_RS2-SLC-PDS_00058900_Touzi.dim";


    @Before
    public void setUp() {
        // If any of the file does not exist: the test will be ignored
        assumeTrue(inputPathQuad + " not found", new File(inputPathQuad).exists());
        assumeTrue(inputQuadFullStack + " not found", new File(inputQuadFullStack).exists());
        assumeTrue(inputC3Stack + " not found", new File(inputC3Stack).exists());
        assumeTrue(inputT3Stack + " not found", new File(inputT3Stack).exists());

        assumeTrue(expectedSinclair + " not found", new File(expectedSinclair).exists());
        assumeTrue(expectedPauli + " not found", new File(expectedPauli).exists());
        assumeTrue(expectedFreeman + " not found", new File(expectedFreeman).exists());
        assumeTrue(expectedYamaguchi + " not found", new File(expectedYamaguchi).exists());
        assumeTrue(expectedVanZyl + " not found", new File(expectedVanZyl).exists());
        assumeTrue(expectedCloude + " not found", new File(expectedCloude).exists());
        assumeTrue(expectedHaAlpha + " not found", new File(expectedHaAlpha).exists());
        assumeTrue(expectedTouzi + " not found", new File(expectedTouzi).exists());
    }

    private Product runDecomposition(final PolarimetricDecompositionOp op,
                                     final String decompositionName, final Product sourceProduct) throws Exception {
        assertNotNull(op);
        op.setSourceProduct(sourceProduct);
        op.SetDecomposition(decompositionName);

        // get targetProduct: execute initialize()
        final Product targetProduct = op.getTargetProduct();
        TestUtils.verifyProduct(targetProduct, false, false);
        return targetProduct;
    }

    private Product runDecomposition(final PolarimetricDecompositionOp op,
                                     final String decompositionName, final String path) throws Exception {
        final File inputFile = new File(path);
        final Product sourceProduct = loadCached(inputFile);

        assertNotNull(op);
        op.setSourceProduct(sourceProduct);
        op.SetDecomposition(decompositionName);

        // get targetProduct: execute initialize()
        final Product targetProduct = op.getTargetProduct();
        TestUtils.verifyProduct(targetProduct, false, false);
        return targetProduct;
    }

    /**
     * Perform Sinclair decomposition of a Radarsat-2 product and compares it with processed product known to be correct
     *
     * @throws Exception general exception
     */
    @Test
    public void testSinclairDecomposition() throws Exception {
        final PolarimetricDecompositionOp op = (PolarimetricDecompositionOp) spi.createOperator();
        final Product targetProduct = runDecomposition(op,
                PolarimetricDecompositionOp.SINCLAIR_DECOMPOSITION, inputPathQuad);
        TestUtils.compareProducts(targetProduct, expectedSinclair, null);
    }

    /**
     * Perform Pauli decomposition of a Radarsat-2 product and compares it with processed product known to be correct
     *
     * @throws Exception general exception
     */
    @Test
    public void testPauliDecomposition() throws Exception {

        final PolarimetricDecompositionOp op = (PolarimetricDecompositionOp) spi.createOperator();
        final Product targetProduct = runDecomposition(op,
                PolarimetricDecompositionOp.PAULI_DECOMPOSITION, inputPathQuad);
        TestUtils.compareProducts(targetProduct, expectedPauli, null);
    }

    /**
     * Perform Freeman-Durden decomposition of a Radarsat-2 product and compares it with processed product known to be correct
     *
     * @throws Exception general exception
     */
    @Test
    public void testFreemanDecomposition() throws Exception {

        final PolarimetricDecompositionOp op = (PolarimetricDecompositionOp) spi.createOperator();
        final Product targetProduct = runDecomposition(op,
                PolarimetricDecompositionOp.FREEMAN_DURDEN_DECOMPOSITION, inputPathQuad);
        TestUtils.compareProducts(targetProduct, expectedFreeman, null);
    }

    @Test
    public void testCloudeDecomposition() throws Exception {

        final PolarimetricDecompositionOp op = (PolarimetricDecompositionOp) spi.createOperator();
        final Product targetProduct = runDecomposition(op,
                PolarimetricDecompositionOp.CLOUDE_DECOMPOSITION, inputPathQuad);
        TestUtils.compareProducts(targetProduct, expectedCloude, null);
    }

    /**
     * Perform H-A-Alpha decomposition of a Radarsat-2 product and compares it with processed product known to be correct
     *
     * @throws Exception general exception
     */
    @Test
    public void testHAAlphaDecomposition() throws Exception {

        final PolarimetricDecompositionOp op = (PolarimetricDecompositionOp) spi.createOperator();
        op.setHAAlphaParameters(true, true, true, true);
        final Product targetProduct = runDecomposition(op,
                PolarimetricDecompositionOp.H_A_ALPHA_DECOMPOSITION, inputPathQuad);
        TestUtils.compareProducts(targetProduct, expectedHaAlpha, null);
    }

    @Test
    public void testTouziDecomposition() throws Exception {

        final PolarimetricDecompositionOp op = (PolarimetricDecompositionOp) spi.createOperator();
        op.setTouziParameters(true, true, true, true);
        final Product targetProduct = runDecomposition(op,
                PolarimetricDecompositionOp.TOUZI_DECOMPOSITION, inputPathQuad);
        TestUtils.compareProducts(targetProduct, expectedTouzi, null);
    }

    @Test
    public void testVanZylDecomposition() throws Exception {

        final Product sourceProduct = createTestC3Product(10, 10);
        final PolarimetricDecompositionOp op = (PolarimetricDecompositionOp) spi.createOperator();
        final Product targetProduct = runDecomposition(op,
                PolarimetricDecompositionOp.VANZYL_DECOMPOSITION, sourceProduct);

        final Band band = targetProduct.getBandAt(0);
        assertNotNull(band);

        // readPixels gets computeTiles to be executed
        final float[] floatValues = new float[100];
        band.readPixels(0, 0, 10, 10, floatValues, ProgressMonitor.NULL);

        // compare with expected outputs. Pixels whose windowed Re(C13) < 0 are double-bounce dominated:
        // the larger co-pol eigenvalue goes to VanZyl_dbl_r there (Phase 8 fix of the surface/double assignment).
        final float[] expectedValues = {0.3352742f, 0.46784493f, 0.44862446f, 1.8797586f, 2.0523696f, 1.9618585f,
                1.8952637f, 2.012029f, 1.8125277f, 1.6801618f, 0.49740696f, 0.5315601f,
                0.50541216f, 1.750782f, 1.882242f, 1.7481928f, 1.6830088f, 1.8180666f,
                1.6689452f, 1.551639f, 0.5812638f, 0.6154853f, 0.57704693f, 1.5910583f,
                1.7832367f, 1.6704513f, 1.658481f, 1.8705754f, 1.8566637f, 0.5053506f,
                2.1665678f, 1.9491425f, 1.8479842f, 1.6762727f, 1.667684f, 0.5707106f,
                1.7248362f, 1.8723432f, 0.52650046f, 0.5568196f, 2.0709965f, 1.894194f,
                1.8943801f, 1.7663236f, 1.8422289f, 0.61678004f, 1.9813709f, 0.61086273f,
                0.5711422f, 0.58777225f, 2.0496798f, 2.0325975f, 1.9745373f, 1.8982384f,
                1.9100286f, 0.64840996f, 0.57401884f, 0.58385557f, 0.5057045f, 0.49801853f,
                2.045479f, 2.248908f, 2.1853502f, 2.0920095f, 2.092788f, 1.959654f,
                1.9413339f, 1.8742491f, 1.8487113f, 1.8858297f, 1.9967551f, 2.2065165f,
                2.1372297f, 2.308347f, 2.373253f, 2.1216695f, 2.1952348f, 2.075229f,
                1.877449f, 1.8810016f, 1.9210458f, 2.2627745f, 2.1290746f, 2.3591058f,
                2.520218f, 2.131389f, 2.0682943f, 1.9947968f, 1.7783233f, 1.745349f,
                1.9053699f, 2.3205738f, 2.056333f, 2.3210106f, 2.4694893f, 2.1678257f,
                2.0172732f, 2.071455f, 1.9232721f, 1.8198577f};
        assertArrayEquals(Arrays.toString(floatValues), expectedValues, floatValues, 0.0001f);
    }

    @Test
    public void testYamaguchiDecomposition() throws Exception {

        final PolarimetricDecompositionOp op = (PolarimetricDecompositionOp) spi.createOperator();
        final Product targetProduct = runDecomposition(op,
                PolarimetricDecompositionOp.YAMAGUCHI_DECOMPOSITION, inputPathQuad);
        TestUtils.compareProducts(targetProduct, expectedYamaguchi, null);
    }

    // Quad Pol Stack
    // Every acquisition date must get the same set of decomposition bands as a single-date decomposition, and
    // since RS2-C3-Stack / RS2-T3-Stack are the C3 / T3 of RS2-Quad_Pol_Stack, all three inputs must give the
    // same values.

    private void assertStackDecomposition(final String decomposition,
                                          final Consumer<PolarimetricDecompositionOp> configure) throws Exception {
        final PolarimetricDecompositionOp singleOp = (PolarimetricDecompositionOp) spi.createOperator();
        configure.accept(singleOp);
        final Product single = runDecomposition(singleOp, decomposition, inputPathQuad);

        Product fromQuad = null;
        for (String stackPath : new String[]{inputQuadFullStack, inputC3Stack, inputT3Stack}) {
            final PolarimetricDecompositionOp op = (PolarimetricDecompositionOp) spi.createOperator();
            configure.accept(op);
            final Product stack = runDecomposition(op, decomposition, stackPath);
            assertStackBandsPerDate(single, stack, STACK_DATES);
            if (fromQuad == null) {
                fromQuad = stack;
            } else {
                // C3/T3 stacks are stored as float32: allow 0.1% relative, or 1% of the band's mean magnitude
                // for values near zero (e.g. Touzi phases)
                assertSameBands(decomposition + " from " + new File(stackPath).getName(), fromQuad, stack, 1e-3, 1e-2);
            }
        }
    }

    @Test
    public void testSinclairStack() throws Exception {
        assertStackDecomposition(PolarimetricDecompositionOp.SINCLAIR_DECOMPOSITION, op -> {});
    }

    @Test
    public void testPauliStack() throws Exception {
        assertStackDecomposition(PolarimetricDecompositionOp.PAULI_DECOMPOSITION, op -> {});
    }

    @Test
    public void testFreemanStack() throws Exception {
        assertStackDecomposition(PolarimetricDecompositionOp.FREEMAN_DURDEN_DECOMPOSITION, op -> {});
    }

    @Test
    public void testCloudeStack() throws Exception {
        assertStackDecomposition(PolarimetricDecompositionOp.CLOUDE_DECOMPOSITION, op -> {});
    }

    @Test
    public void testHAAlphaStack() throws Exception {
        assertStackDecomposition(PolarimetricDecompositionOp.H_A_ALPHA_DECOMPOSITION,
                op -> op.setHAAlphaParameters(true, true, true, true));
    }

    @Test
    public void testTouziStack() throws Exception {
        assertStackDecomposition(PolarimetricDecompositionOp.TOUZI_DECOMPOSITION,
                op -> op.setTouziParameters(true, true, true, true));
    }

    @Test
    public void testVanZylStack() throws Exception {
        assertStackDecomposition(PolarimetricDecompositionOp.VANZYL_DECOMPOSITION, op -> {});
    }

    @Test
    public void testYamaguchiStack() throws Exception {
        assertStackDecomposition(PolarimetricDecompositionOp.YAMAGUCHI_DECOMPOSITION, op -> {});
    }

    private Product createTestC3Product(final int w, final int h) {

        final Product testProduct = new Product("name", "SLC", w, h);
        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(testProduct.getMetadataRoot());
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.radar_frequency, 5404.999242769673);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SAMPLE_TYPE, "COMPLEX");
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.MISSION, "RS2");

        final Band[] bands = new Band[9];
        bands[0] = testProduct.addBand("C11", ProductData.TYPE_FLOAT32);
        bands[0].setUnit(Unit.INTENSITY);
        bands[1] = testProduct.addBand("C12_real", ProductData.TYPE_FLOAT32);
        bands[1].setUnit(Unit.REAL);
        bands[2] = testProduct.addBand("C12_imag", ProductData.TYPE_FLOAT32);
        bands[2].setUnit(Unit.IMAGINARY);
        bands[3] = testProduct.addBand("C13_real", ProductData.TYPE_FLOAT32);
        bands[3].setUnit(Unit.REAL);
        bands[4] = testProduct.addBand("C13_imag", ProductData.TYPE_FLOAT32);
        bands[4].setUnit(Unit.IMAGINARY);
        bands[5] = testProduct.addBand("C22", ProductData.TYPE_FLOAT32);
        bands[5].setUnit(Unit.INTENSITY);
        bands[6] = testProduct.addBand("C23_real", ProductData.TYPE_FLOAT32);
        bands[6].setUnit(Unit.REAL);
        bands[7] = testProduct.addBand("C23_imag", ProductData.TYPE_FLOAT32);
        bands[7].setUnit(Unit.IMAGINARY);
        bands[8] = testProduct.addBand("C33", ProductData.TYPE_FLOAT32);
        bands[8].setUnit(Unit.INTENSITY);

        final double[] bandSTDs = new double[]{2.55, 0.2664, 0.1631, 0.8338, 0.5407, 0.0862, 0.1209, 0.0979, 0.6073};

        long seed = 1234;
        Random random = new Random(seed);
        for (int i = 0; i < bands.length; ++i) {
            final float[] values = new float[w * h];
            if (bands[i].getUnit().equals(Unit.INTENSITY)) {
                for (int j = 0; j < w * h; j++) {
                    values[j] = (float)(Math.abs(random.nextGaussian() * bandSTDs[i]));
                }
            } else {
                for (int j = 0; j < w * h; j++) {
                    values[j] = (float)(random.nextGaussian() * bandSTDs[i]);
                }
            }
            bands[i].setData(ProductData.createInstance(values));
        }

        return testProduct;
    }
}
