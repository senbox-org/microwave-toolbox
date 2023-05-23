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
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Unit test for PolarimetricDecompositionOp.
 */
public class TestPolarimetricDecompositionOp {

    static {
        TestUtils.initTestEnvironment();
    }

    private final static OperatorSpi spi = new PolarimetricDecompositionOp.Spi();

    private final static String inputPathQuad = TestData.inputSAR + "/QuadPol/QuadPol_subset_0_of_RS2-SLC-PDS_00058900.dim";
    private final static String inputQuadFullStack = TestData.inputSAR + "/QuadPolStack/RS2-Quad_Pol_Stack.dim";
    private final static String inputC3Stack = TestData.inputSAR + "/QuadPolStack/RS2-C3-Stack.dim";
    private final static String inputT3Stack = TestData.inputSAR + "/QuadPolStack/RS2-T3-Stack.dim";

    private final static String expectedSinclair = TestData.input + "/expected/QuadPol/QuadPol_subset_0_of_RS2-SLC-PDS_00058900_Sinclair.dim";
    private final static String expectedPauli = TestData.input + "/expected/QuadPol/QuadPol_subset_0_of_RS2-SLC-PDS_00058900_Pauli.dim";
    private final static String expectedFreeman = TestData.input + "/expected/QuadPol/QuadPol_subset_0_of_RS2-SLC-PDS_00058900_FreemanDurden.dim";
    private final static String expectedYamaguchi = TestData.input + "/expected/QuadPol/QuadPol_subset_0_of_RS2-SLC-PDS_00058900_Yamaguchi.dim";
    private final static String expectedVanZyl = TestData.input + "/expected/QuadPol/QuadPol_subset_0_of_RS2-SLC-PDS_00058900_VanZyl.dim";
    private final static String expectedCloude = TestData.input + "/expected/QuadPol/QuadPol_subset_0_of_RS2-SLC-PDS_00058900_Cloude.dim";
    private final static String expectedHaAlpha = TestData.input + "/expected/QuadPol/QuadPol_subset_0_of_RS2-SLC-PDS_00058900_HaAlpha.dim";
    private final static String expectedTouzi = TestData.input + "/expected/QuadPol/QuadPol_subset_0_of_RS2-SLC-PDS_00058900_Touzi.dim";


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
        final Product sourceProduct = TestUtils.readSourceProduct(inputFile);

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
        if (targetProduct != null)
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
        if (targetProduct != null)
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
        if (targetProduct != null)
            TestUtils.compareProducts(targetProduct, expectedFreeman, null);
    }

    @Test
    public void testCloudeDecomposition() throws Exception {

        final PolarimetricDecompositionOp op = (PolarimetricDecompositionOp) spi.createOperator();
        final Product targetProduct = runDecomposition(op,
                PolarimetricDecompositionOp.CLOUDE_DECOMPOSITION, inputPathQuad);
        if (targetProduct != null)
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
        if (targetProduct != null)
            TestUtils.compareProducts(targetProduct, expectedHaAlpha, null);
    }

    public void testTouziDecomposition() throws Exception {

        final PolarimetricDecompositionOp op = (PolarimetricDecompositionOp) spi.createOperator();
        op.setTouziParameters(true, true, true, true);
        final Product targetProduct = runDecomposition(op,
                PolarimetricDecompositionOp.TOUZI_DECOMPOSITION, inputPathQuad);
        if (targetProduct != null)
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

        // compare with expected outputs:
        final float[] expectedValues = {0.3352742f, 0.46784493f, 0.44862446f, 0.40875262f, 0.3036448f, 0.38027877f,
                0.25553876f, 0.27621624f, 0.3889112f, 0.39162698f, 0.49740696f, 0.5315601f, 0.50541216f, 0.4528281f,
                0.37095192f, 0.4760012f, 0.3552928f, 0.4424209f, 0.51719195f, 0.55584365f, 0.5812638f, 0.6154853f,
                0.57704693f, 0.53179526f, 0.41812962f, 0.5363548f, 0.41915277f, 0.4925977f, 0.5248552f, 0.5053506f,
                0.62536377f, 0.7389454f, 0.6492092f, 0.5890301f, 0.4909914f, 0.5707106f, 0.44424757f, 0.50344014f,
                0.52650046f, 0.5568196f, 0.6524977f, 0.7001816f, 0.64607877f, 0.5434106f, 0.5132085f, 0.61678004f,
                0.57415015f, 0.61086273f, 0.5711422f, 0.58777225f, 0.5232767f, 0.6036279f, 0.58228236f, 0.5193582f,
                0.5382116f, 0.64840996f, 0.57401884f, 0.58385557f, 0.5057045f, 0.49801853f, 0.4830593f, 0.5798051f,
                0.57090926f, 0.53432566f, 0.5362130f, 0.6116159f, 0.53054947f, 0.55362135f, 0.5388208f, 0.47885248f,
                0.41896993f, 0.48561466f, 0.49204364f, 0.48023382f, 0.4964855f, 0.5486218f, 0.5057795f, 0.5838198f,
                0.58138514f, 0.47988653f, 0.35988957f, 0.4122757f, 0.43132278f, 0.43501678f, 0.46842563f, 0.52232397f,
                0.4944064f, 0.6037089f, 0.57674855f, 0.44855425f, 0.22622389f, 0.37596372f, 0.35784683f, 0.4225033f,
                0.43441418f, 0.4428584f, 0.34741288f, 0.4936852f, 0.4295814f, 0.28043285f};
        assertTrue(Arrays.equals(expectedValues, floatValues));
    }

    @Test
    public void testYamaguchiDecomposition() throws Exception {

        final PolarimetricDecompositionOp op = (PolarimetricDecompositionOp) spi.createOperator();
        final Product targetProduct = runDecomposition(op,
                PolarimetricDecompositionOp.YAMAGUCHI_DECOMPOSITION, inputPathQuad);
        if (targetProduct != null)
            TestUtils.compareProducts(targetProduct, expectedYamaguchi, null);
    }

    // Quad Pol Stack

    @Test
    public void testSinclairStack() throws Exception {

        runDecomposition((PolarimetricDecompositionOp) spi.createOperator(),
                PolarimetricDecompositionOp.SINCLAIR_DECOMPOSITION, inputQuadFullStack);
        runDecomposition((PolarimetricDecompositionOp) spi.createOperator(),
                PolarimetricDecompositionOp.SINCLAIR_DECOMPOSITION, inputC3Stack);
        runDecomposition((PolarimetricDecompositionOp) spi.createOperator(),
                PolarimetricDecompositionOp.SINCLAIR_DECOMPOSITION, inputT3Stack);
    }

    @Test
    public void testPauliStack() throws Exception {

        runDecomposition((PolarimetricDecompositionOp) spi.createOperator(),
                PolarimetricDecompositionOp.PAULI_DECOMPOSITION, inputQuadFullStack);
        runDecomposition((PolarimetricDecompositionOp) spi.createOperator(),
                PolarimetricDecompositionOp.PAULI_DECOMPOSITION, inputC3Stack);
        runDecomposition((PolarimetricDecompositionOp) spi.createOperator(),
                PolarimetricDecompositionOp.PAULI_DECOMPOSITION, inputT3Stack);
    }

    @Test
    public void testFreemanStack() throws Exception {

        runDecomposition((PolarimetricDecompositionOp) spi.createOperator(),
                PolarimetricDecompositionOp.FREEMAN_DURDEN_DECOMPOSITION, inputQuadFullStack);
        runDecomposition((PolarimetricDecompositionOp) spi.createOperator(),
                PolarimetricDecompositionOp.FREEMAN_DURDEN_DECOMPOSITION, inputC3Stack);
        runDecomposition((PolarimetricDecompositionOp) spi.createOperator(),
                PolarimetricDecompositionOp.FREEMAN_DURDEN_DECOMPOSITION, inputT3Stack);
    }

    @Test
    public void testCloudeStack() throws Exception {

        runDecomposition((PolarimetricDecompositionOp) spi.createOperator(),
                PolarimetricDecompositionOp.CLOUDE_DECOMPOSITION, inputQuadFullStack);
        runDecomposition((PolarimetricDecompositionOp) spi.createOperator(),
                PolarimetricDecompositionOp.CLOUDE_DECOMPOSITION, inputC3Stack);
        runDecomposition((PolarimetricDecompositionOp) spi.createOperator(),
                PolarimetricDecompositionOp.CLOUDE_DECOMPOSITION, inputT3Stack);
    }

    @Test
    public void testHAAlphaStack() throws Exception {

        PolarimetricDecompositionOp op;

        op = (PolarimetricDecompositionOp) spi.createOperator();
        op.setHAAlphaParameters(true, true, true, true);
        runDecomposition(op, PolarimetricDecompositionOp.H_A_ALPHA_DECOMPOSITION, inputQuadFullStack);

        op = (PolarimetricDecompositionOp) spi.createOperator();
        op.setHAAlphaParameters(true, true, true, true);
        runDecomposition(op, PolarimetricDecompositionOp.H_A_ALPHA_DECOMPOSITION, inputC3Stack);

        op = (PolarimetricDecompositionOp) spi.createOperator();
        op.setHAAlphaParameters(true, true, true, true);
        runDecomposition(op, PolarimetricDecompositionOp.H_A_ALPHA_DECOMPOSITION, inputT3Stack);
    }

    @Test
    public void testTouziStack() throws Exception {

        PolarimetricDecompositionOp op;

        op = (PolarimetricDecompositionOp) spi.createOperator();
        op.setTouziParameters(true, true, true, true);
        runDecomposition(op, PolarimetricDecompositionOp.TOUZI_DECOMPOSITION, inputQuadFullStack);

        op = (PolarimetricDecompositionOp) spi.createOperator();
        op.setTouziParameters(true, true, true, true);
        runDecomposition(op, PolarimetricDecompositionOp.TOUZI_DECOMPOSITION, inputC3Stack);

        op = (PolarimetricDecompositionOp) spi.createOperator();
        op.setTouziParameters(true, true, true, true);
        runDecomposition(op, PolarimetricDecompositionOp.TOUZI_DECOMPOSITION, inputT3Stack);
    }

    @Test
    public void testVanZylStack() throws Exception {

        runDecomposition((PolarimetricDecompositionOp) spi.createOperator(),
                PolarimetricDecompositionOp.VANZYL_DECOMPOSITION, inputQuadFullStack);
        runDecomposition((PolarimetricDecompositionOp) spi.createOperator(),
                PolarimetricDecompositionOp.VANZYL_DECOMPOSITION, inputC3Stack);
        runDecomposition((PolarimetricDecompositionOp) spi.createOperator(),
                PolarimetricDecompositionOp.VANZYL_DECOMPOSITION, inputT3Stack);
    }

    public void testYamaguchiStack() throws Exception {

        runDecomposition((PolarimetricDecompositionOp) spi.createOperator(),
                PolarimetricDecompositionOp.YAMAGUCHI_DECOMPOSITION, inputQuadFullStack);
        runDecomposition((PolarimetricDecompositionOp) spi.createOperator(),
                PolarimetricDecompositionOp.YAMAGUCHI_DECOMPOSITION, inputC3Stack);
        runDecomposition((PolarimetricDecompositionOp) spi.createOperator(),
                PolarimetricDecompositionOp.YAMAGUCHI_DECOMPOSITION, inputT3Stack);
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
