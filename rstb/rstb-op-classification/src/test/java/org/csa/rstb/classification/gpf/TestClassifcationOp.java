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
package org.csa.rstb.classification.gpf;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.test.ProcessorTest;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.IndexCoding;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Unit test for PolarimetricClassification.
 */
public class TestClassifcationOp extends ProcessorTest {

    private static File inputQuadFile;
    private static File inputQuadFullStackFile;
    private static File inputC3StackFile;
    private static File inputT3StackFile;

    @Before
    public void setUp() throws Exception {
        try {
            // If any of the file does not exist: the test will be ignored
            inputQuadFile = TestData.inputQuad;
            assumeTrue(inputQuadFile + " not found", inputQuadFile.exists());

            inputQuadFullStackFile = TestData.inputQuadFullStack;
            assumeTrue(inputQuadFullStackFile + " not found", inputQuadFullStackFile.exists());

            inputC3StackFile = TestData.inputC3Stack;
            assumeTrue(inputC3StackFile + " not found", inputC3StackFile.exists());

            inputT3StackFile = TestData.inputT3Stack;
            assumeTrue(inputT3StackFile + " not found", inputT3StackFile.exists());
        } catch (Exception e) {
            TestUtils.skipTest(this, e.getMessage());
            throw e;
        }
    }

    private final static OperatorSpi spi = new PolarimetricClassificationOp.Spi();

    private Product runClassification(final PolarimetricClassificationOp op, final String classifier,
                                      final File inputFile) throws Exception {
        final Product sourceProduct = TestUtils.readSourceProduct(inputFile);

        assertNotNull(op);
        op.setSourceProduct(sourceProduct);
        op.SetClassification(classifier);

        // get targetProduct: execute initialize()
        final Product targetProduct = op.getTargetProduct();
        TestUtils.verifyProduct(targetProduct, true, true, true);
        return targetProduct;
    }

    private static final String[] STACK_DATES = {"13Jun2012", "07Jul2012", "17Sep2012"};

    /**
     * The class map of a real quad-pol scene must only contain codes declared by the band's index coding
     * (0 = no data, 1..numClasses), must be mostly valid and must use more than one class.
     */
    private static void assertValidClassMap(final Product target) throws Exception {
        assertEquals(1, target.getNumBands());
        final Band band = target.getBandAt(0);
        final IndexCoding coding = band.getIndexCoding();
        assertNotNull("class band has an index coding", coding);
        final int numClasses = coding.getIndexNames().length - 1; // minus "no data"
        assertTrue("number of classes " + numClasses, numClasses >= 2);

        final int w = target.getSceneRasterWidth();
        final int h = target.getSceneRasterHeight();
        final int[] classes = new int[w * h];
        band.readPixels(0, 0, w, h, classes, ProgressMonitor.NULL);

        final Set<Integer> used = new HashSet<>();
        int noData = 0;
        for (int c : classes) {
            assertTrue("class " + c + " outside 0.." + numClasses, c >= 0 && c <= numClasses);
            if (c == 0) {
                noData++;
            } else {
                used.add(c);
            }
        }
        assertTrue("no-data pixels " + noData + " of " + classes.length, noData < classes.length / 2);
        assertTrue("classes used " + used, used.size() >= 2);
    }

    /** One class band per acquisition date, named like the single-date band with the date suffix. */
    private static void assertClassBandPerDate(final Product single, final Product stack) {
        final String name = single.getBandAt(0).getName();
        for (String date : STACK_DATES) {
            assertTrue("stack is missing " + name + '_' + date + " in " + Arrays.toString(stack.getBandNames()),
                    stack.containsBand(name + '_' + date));
        }
        assertEquals(Arrays.toString(stack.getBandNames()), STACK_DATES.length, stack.getNumBands());
    }

    /**
     * Single-date classification is checked on the real quad-pol scene. Stacks must get one class band per date,
     * and since RS2-T3-Stack is the T3 of RS2-Quad_Pol_Stack, both must give the same class map (up to 1% of
     * pixels, which float32 storage of the T3 stack can push across a class boundary).
     */
    private void assertClassifier(final String classifier, final boolean withStacks) throws Exception {
        final Product single = runClassification((PolarimetricClassificationOp) spi.createOperator(),
                classifier, inputQuadFile);
        assertValidClassMap(single);
        if (!withStacks) {
            return;
        }
        Product fromQuad = null;
        for (File stack : new File[]{inputQuadFullStackFile, inputC3StackFile, inputT3StackFile}) {
            final Product result = runClassification((PolarimetricClassificationOp) spi.createOperator(), classifier, stack);
            assertClassBandPerDate(single, result);
            if (fromQuad == null) {
                fromQuad = result;
            } else if (stack == inputT3StackFile) {
                assertSameClassMaps(fromQuad, result);
            }
        }
    }

    private static void assertSameClassMaps(final Product expected, final Product actual) throws Exception {
        final int w = expected.getSceneRasterWidth();
        final int h = expected.getSceneRasterHeight();
        for (String date : STACK_DATES) {
            final String name = expected.getBandAt(0).getName().replaceAll("_[0-9]{2}[A-Za-z]{3}[0-9]{4}$", "") + '_' + date;
            final int[] e = new int[w * h];
            final int[] a = new int[w * h];
            expected.getBand(name).readPixels(0, 0, w, h, e, ProgressMonitor.NULL);
            actual.getBand(name).readPixels(0, 0, w, h, a, ProgressMonitor.NULL);
            int differ = 0, valid = 0;
            for (int i = 0; i < e.length; i++) {
                if (e[i] != 0) {
                    valid++;
                }
                if (e[i] != a[i]) {
                    differ++;
                }
            }
            assertTrue(name + ": classified pixels " + valid, valid > e.length / 2);
            assertTrue(name + ": " + differ + " of " + e.length + " pixels differ", differ <= e.length / 100);
        }
    }

    @Test
    public void testCloudePottierClassifier() throws Exception {
        assertClassifier(PolarimetricClassificationOp.UNSUPERVISED_CLOUDE_POTTIER_CLASSIFICATION, true);
    }

    @Test
    public void testWishartClassifier() throws Exception {
        assertClassifier(PolarimetricClassificationOp.UNSUPERVISED_HALPHA_WISHART_CLASSIFICATION, true);
    }

    @Test
    public void testTerrainClassifier() throws Exception {

        assertClassifier(PolarimetricClassificationOp.UNSUPERVISED_FREEMAN_DURDEN_CLASSIFICATION, false);
//        runClassification((PolarimetricClassificationOp)spi.createOperator(),
//                PolarimetricClassificationOp.UNSUPERVISED_TERRAIN_CLASSIFICATION, inputQuadFullStackFile);
//        runClassification((PolarimetricClassificationOp)spi.createOperator(),
//                PolarimetricClassificationOp.UNSUPERVISED_TERRAIN_CLASSIFICATION, inputC3StackFile);
//        runClassification((PolarimetricClassificationOp)spi.createOperator(),
//                PolarimetricClassificationOp.UNSUPERVISED_TERRAIN_CLASSIFICATION, inputT3StackFile);
    }
}
