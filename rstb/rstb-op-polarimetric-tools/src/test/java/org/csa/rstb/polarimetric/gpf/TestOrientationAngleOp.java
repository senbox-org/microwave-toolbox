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
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.csa.rstb.polarimetric.gpf.PolTestAssertions.STACK_DATES;
import static org.csa.rstb.polarimetric.gpf.PolTestAssertions.assertClose;
import static org.csa.rstb.polarimetric.gpf.PolTestAssertions.assertSameBands;
import static org.csa.rstb.polarimetric.gpf.PolTestAssertions.assertStackBandsPerDate;
import static org.csa.rstb.polarimetric.gpf.PolTestAssertions.readBand;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

/**
 * Unit test for OrientationAngleOp.
 */
public class TestOrientationAngleOp {


    static {
        TestUtils.initTestEnvironment();
    }

    private final static OperatorSpi spi = new OrientationAngleCorrectionOp.Spi();

    @Test
    public void testSpiCreatesOperator() {
        final OrientationAngleCorrectionOp op = (OrientationAngleCorrectionOp) spi.createOperator();
        assertNotNull(op);
    }

    @Test
    public void testOperatorMetadata() {
        final OperatorMetadata md = OrientationAngleCorrectionOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("Orientation-Angle-Correction", md.alias());
    }

    private final static String inputPathQuad = TestData.inputSAR + "/QuadPol/QuadPol_subset_0_of_RS2-SLC-PDS_00058900.dim";
    private final static String inputQuadFullStack = TestData.inputSAR + "/QuadPolStack/RS2-Quad_Pol_Stack.dim";
    private final static String inputC3Stack = TestData.inputSAR + "/QuadPolStack/RS2-C3-Stack.dim";
    private final static String inputT3Stack = TestData.inputSAR + "/QuadPolStack/RS2-T3-Stack.dim";


    @Before
    public void setUp() {
        // If any of the file does not exist: the test will be ignored
        assumeTrue(inputPathQuad + " not found", new File(inputPathQuad).exists());
        assumeTrue(inputQuadFullStack + " not found", new File(inputQuadFullStack).exists());
        assumeTrue(inputC3Stack + " not found", new File(inputC3Stack).exists());
        assumeTrue(inputT3Stack + " not found", new File(inputT3Stack).exists());
    }


    private Product runOrientation(final OrientationAngleCorrectionOp op, final String path) throws Exception {
        final File inputFile = new File(path);
        final Product sourceProduct = TestUtils.readSourceProduct(inputFile);

        assertNotNull(op);
        op.setSourceProduct(sourceProduct);

        // get targetProduct: execute initialize()
        final Product targetProduct = op.getTargetProduct();
        TestUtils.verifyProduct(targetProduct, false, false);
        return targetProduct;
    }

    private static Product runOrientation(final Product sourceProduct) {
        final OrientationAngleCorrectionOp op = (OrientationAngleCorrectionOp) spi.createOperator();
        op.setSourceProduct(sourceProduct);
        return op.getTargetProduct();
    }

    private static Product toMatrix(final Product sourceProduct, final String matrix) {
        final PolarimetricMatricesOp op = new PolarimetricMatricesOp();
        op.setSourceProduct(sourceProduct);
        op.SetMatrixType(matrix);
        return op.getTargetProduct();
    }

    /**
     * De-orientation is a real rotation of the coherency matrix about the radar line of sight:
     * it must leave T11 and the total power (trace of T3) unchanged and, by construction of the
     * estimated orientation angle, cancel the real part of T23.
     */
    @Test
    public void testOrientationAngle() throws Exception {

        final Product target = runOrientation((OrientationAngleCorrectionOp) spi.createOperator(), inputPathQuad);
        final Product t3 = toMatrix(TestUtils.readSourceProduct(new File(inputPathQuad)), PolarimetricMatricesOp.T3);

        final double[] t11In = readBand(t3, "T11");
        final double[] spanIn = trace(t3);
        assertClose("T11 preserved", t11In, readBand(target, "T11"), 1e-4, 1e-6);
        assertClose("trace preserved", spanIn, trace(target), 1e-4, 1e-6);

        final double[] t23Re = readBand(target, "T23_real");
        final double[] t22 = readBand(target, "T22");
        final double[] t33 = readBand(target, "T33");
        for (int i = 0; i < t23Re.length; i++) {
            assertEquals("Re(T23) after de-orientation at index " + i, 0.0, t23Re[i], 1e-4 * (t22[i] + t33[i]) + 1e-9);
        }
    }

    private static double[] trace(final Product t3) throws Exception {
        final double[] t11 = readBand(t3, "T11");
        final double[] t22 = readBand(t3, "T22");
        final double[] t33 = readBand(t3, "T33");
        final double[] tr = new double[t11.length];
        for (int i = 0; i < tr.length; i++) {
            tr[i] = t11[i] + t22[i] + t33[i];
        }
        return tr;
    }

    /**
     * Stacks get one corrected matrix per acquisition date, and since RS2-T3-Stack is the T3 of
     * RS2-Quad_Pol_Stack, correcting either must give the same values.
     */
    @Test
    public void testOrientationAngleStacks() throws Exception {

        final Product quad = TestUtils.readSourceProduct(new File(inputPathQuad));
        final Product singleT3 = runOrientation(quad);
        final Product singleC3 = runOrientation(toMatrix(quad, PolarimetricMatricesOp.C3));

        final Product fromQuad = runOrientation((OrientationAngleCorrectionOp) spi.createOperator(), inputQuadFullStack);
        final Product fromT3 = runOrientation((OrientationAngleCorrectionOp) spi.createOperator(), inputT3Stack);
        assertStackBandsPerDate(singleT3, fromQuad, STACK_DATES);
        assertStackBandsPerDate(singleT3, fromT3, STACK_DATES);
        assertStackBandsPerDate(singleC3, runOrientation((OrientationAngleCorrectionOp) spi.createOperator(), inputC3Stack), STACK_DATES);

        // The T3 stack is stored as float32: 0.1% relative, or 1% of the band's mean magnitude near zero.
        // Re(T23) is ~0 by construction after de-orientation, so give every band an absolute floor of
        // 1e-6 of the mean total power.
        final double[] span = readBand(fromQuad, "T11_" + STACK_DATES[0]);
        double meanSpan = 0;
        for (double v : span) {
            meanSpan += v;
        }
        meanSpan /= span.length;
        assertSameBands("orientation quad vs T3 stack", fromQuad, fromT3, 1e-3, 1e-2, 1e-6 * meanSpan, 0);
    }
}
