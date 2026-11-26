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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.csa.rstb.polarimetric.gpf.PolTestAssertions.STACK_DATES;
import static org.csa.rstb.polarimetric.gpf.PolTestAssertions.assertClose;
import static org.csa.rstb.polarimetric.gpf.PolTestAssertions.assertStackBandsPerDate;
import static org.csa.rstb.polarimetric.gpf.PolTestAssertions.readBand;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

/**
 * Unit test for PolarimetricMatricesOp
 */
public class TestPolarimetricMatricesOp {

    static {
        TestUtils.initTestEnvironment();
    }

    private final static OperatorSpi spi = new PolarimetricMatricesOp.Spi();

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
    public static void disposeProducts() {
        for (Product p : PRODUCT_CACHE.values()) {
            if (p != null) {
                p.dispose();
            }
        }
        PRODUCT_CACHE.clear();
    }

    @Test
    public void testSpiCreatesOperator() {
        final PolarimetricMatricesOp op = (PolarimetricMatricesOp) spi.createOperator();
        assertNotNull(op);
    }

    @Test
    public void testOperatorMetadata() {
        final OperatorMetadata md = PolarimetricMatricesOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("Polarimetric-Matrices", md.alias());
    }

    private final static String quadInputPath = TestData.inputSAR + "/QuadPol/QuadPol_subset_0_of_RS2-SLC-PDS_00058900.dim";
    private final static String inputQuadFullStack = TestData.inputSAR + "/QuadPolStack/RS2-Quad_Pol_Stack.dim";

    private final static String expectedPathC3 = TestUtils.TESTDATA_ROOT + "/expected/QuadPol/QuadPol_subset_0_of_RS2-SLC-PDS_00058900_C3.dim";
    private final static String expectedPathT3 = TestUtils.TESTDATA_ROOT + "/expected/QuadPol/QuadPol_subset_0_of_RS2-SLC-PDS_00058900_T3.dim";

    @Before
    public void setUp() {
        // If any of the file does not exist: the test will be ignored
        assumeTrue(quadInputPath + " not found", new File(quadInputPath).exists());
        assumeTrue(inputQuadFullStack + " not found", new File(inputQuadFullStack).exists());

        assumeTrue(expectedPathC3 + " not found", new File(expectedPathC3).exists());
        assumeTrue(expectedPathT3 + " not found", new File(expectedPathT3).exists());
    }

    private Product runMatrix(final PolarimetricMatricesOp op,
                              final String decompositionName, final String path) throws Exception {
        final File inputFile = new File(path);
        final Product sourceProduct = loadCached(inputFile);

        assertNotNull(op);
        op.setSourceProduct(sourceProduct);
        op.SetMatrixType(decompositionName);

        // get targetProduct: execute initialize()
        final Product targetProduct = op.getTargetProduct();
        TestUtils.verifyProduct(targetProduct, false, false);
        return targetProduct;
    }

    /**
     * Compute covariance matrix C3 from a Radarsat-2 product and compares it to processed product known to be correct
     *
     * @throws Exception general exception
     */
    @Test
    public void testComputeC3() throws Exception {

        final PolarimetricMatricesOp op = (PolarimetricMatricesOp) spi.createOperator();
        final Product targetProduct = runMatrix(op, PolarimetricMatricesOp.C3, quadInputPath);
        TestUtils.compareProducts(targetProduct, expectedPathC3, null);
    }

    /**
     * Compute coherency matrix T3 from a Radarsat-2 product and compares it to processed product known to be correct
     *
     * @throws Exception general exception
     */
    @Test
    public void testComputeT3() throws Exception {

        final PolarimetricMatricesOp op = (PolarimetricMatricesOp) spi.createOperator();
        final Product targetProduct = runMatrix(op, PolarimetricMatricesOp.T3, quadInputPath);
        TestUtils.compareProducts(targetProduct, expectedPathT3, null);
    }

    /** Complex scattering element S_pol = i_pol + j q_pol of every pixel of the quad-pol source. */
    private static double[][] scattering(final Product source, final String pol) throws Exception {
        return new double[][]{readBand(source, "i_" + pol), readBand(source, "q_" + pol)};
    }

    private static double[] intensity(final double[][] s) {
        final double[] out = new double[s[0].length];
        for (int i = 0; i < out.length; i++) {
            out[i] = s[0][i] * s[0][i] + s[1][i] * s[1][i];
        }
        return out;
    }

    /**
     * C4 is the per-pixel outer product of the lexicographic vector [S_hh, S_hv, S_vh, S_vv]:
     * the diagonal holds the channel intensities and C14 = S_hh * conj(S_vv).
     */
    @Test
    public void testComputeC4() throws Exception {

        final PolarimetricMatricesOp op = (PolarimetricMatricesOp) spi.createOperator();
        final Product targetProduct = runMatrix(op, PolarimetricMatricesOp.C4, quadInputPath);
        final Product source = loadCached(new File(quadInputPath));

        final double[][] hh = scattering(source, "HH");
        final double[][] hv = scattering(source, "HV");
        final double[][] vh = scattering(source, "VH");
        final double[][] vv = scattering(source, "VV");

        assertClose("C11", intensity(hh), readBand(targetProduct, "C11"), 1e-5, 1e-6);
        assertClose("C22", intensity(hv), readBand(targetProduct, "C22"), 1e-5, 1e-6);
        assertClose("C33", intensity(vh), readBand(targetProduct, "C33"), 1e-5, 1e-6);
        assertClose("C44", intensity(vv), readBand(targetProduct, "C44"), 1e-5, 1e-6);

        final int n = hh[0].length;
        final double[] c14Re = new double[n];
        final double[] c14Im = new double[n];
        for (int i = 0; i < n; i++) {
            c14Re[i] = hh[0][i] * vv[0][i] + hh[1][i] * vv[1][i];
            c14Im[i] = hh[1][i] * vv[0][i] - hh[0][i] * vv[1][i];
        }
        // floor relative to the channel power so near-zero cross products are not over-constrained
        final double floor = 1e-3 * mean(intensity(hh));
        assertClose("C14_real", c14Re, readBand(targetProduct, "C14_real"), 1e-4, floor);
        assertClose("C14_imag", c14Im, readBand(targetProduct, "C14_imag"), 1e-4, floor);
    }

    /**
     * T4 is a unitary transform of C4, so its trace equals the total power, and in the Pauli basis
     * T11 = |S_hh + S_vv|^2 / 2 and T22 = |S_hh - S_vv|^2 / 2.
     */
    @Test
    public void testComputeT4() throws Exception {

        final PolarimetricMatricesOp op = (PolarimetricMatricesOp) spi.createOperator();
        final Product targetProduct = runMatrix(op, PolarimetricMatricesOp.T4, quadInputPath);
        final Product source = loadCached(new File(quadInputPath));

        final double[][] hh = scattering(source, "HH");
        final double[][] hv = scattering(source, "HV");
        final double[][] vh = scattering(source, "VH");
        final double[][] vv = scattering(source, "VV");

        final int n = hh[0].length;
        final double[] span = new double[n];
        final double[] t11 = new double[n];
        final double[] t22 = new double[n];
        final double[] iHH = intensity(hh), iHV = intensity(hv), iVH = intensity(vh), iVV = intensity(vv);
        for (int i = 0; i < n; i++) {
            span[i] = iHH[i] + iHV[i] + iVH[i] + iVV[i];
            final double sr = hh[0][i] + vv[0][i], si = hh[1][i] + vv[1][i];
            final double dr = hh[0][i] - vv[0][i], di = hh[1][i] - vv[1][i];
            t11[i] = (sr * sr + si * si) / 2;
            t22[i] = (dr * dr + di * di) / 2;
        }

        final double[] trace = new double[n];
        for (String d : new String[]{"T11", "T22", "T33", "T44"}) {
            final double[] v = readBand(targetProduct, d);
            for (int i = 0; i < n; i++) {
                trace[i] += v[i];
            }
        }
        assertClose("trace(T4) = span", span, trace, 1e-5, 1e-6);
        assertClose("T11", t11, readBand(targetProduct, "T11"), 1e-5, 1e-6);
        assertClose("T22", t22, readBand(targetProduct, "T22"), 1e-5, 1e-6);
    }

    private static double mean(final double[] v) {
        double s = 0;
        for (double d : v) {
            s += d;
        }
        return s / v.length;
    }

    /**
     * Matrices of a stack of Quad Pol Radarsat-2 products: one complete matrix per acquisition date, each built
     * from that date's own scattering elements (catches date / polarisation mispairing in the stack handling).
     */
    @Test
    public void testQuadPolStack() throws Exception {

        for (String matrix : new String[]{PolarimetricMatricesOp.T3, PolarimetricMatricesOp.C3,
                PolarimetricMatricesOp.T4, PolarimetricMatricesOp.C4}) {
            final Product single = runMatrix((PolarimetricMatricesOp) spi.createOperator(), matrix, quadInputPath);
            final Product stack = runMatrix((PolarimetricMatricesOp) spi.createOperator(), matrix, inputQuadFullStack);
            assertStackBandsPerDate(single, stack, STACK_DATES);
        }

        final Product source = loadCached(new File(inputQuadFullStack));
        final Product c3 = runMatrix((PolarimetricMatricesOp) spi.createOperator(), PolarimetricMatricesOp.C3, inputQuadFullStack);
        final Product t3 = runMatrix((PolarimetricMatricesOp) spi.createOperator(), PolarimetricMatricesOp.T3, inputQuadFullStack);
        for (String date : STACK_DATES) {
            final double[][] hh = stackScattering(source, "HH", date);
            final double[][] vv = stackScattering(source, "VV", date);
            final double[] t11 = new double[hh[0].length];
            for (int i = 0; i < t11.length; i++) {
                final double sr = hh[0][i] + vv[0][i], si = hh[1][i] + vv[1][i];
                t11[i] = (sr * sr + si * si) / 2;
            }
            assertClose("C11_" + date, intensity(hh), readBand(c3, "C11_" + date), 1e-5, 1e-6);
            assertClose("C33_" + date, intensity(vv), readBand(c3, "C33_" + date), 1e-5, 1e-6);
            assertClose("T11_" + date, t11, readBand(t3, "T11_" + date), 1e-5, 1e-6);
        }
    }

    /** S_pol of one acquisition of a coregistered stack (band names i_POL_ref_DATE / i_POL_secN_DATE). */
    private static double[][] stackScattering(final Product stack, final String pol, final String date) throws Exception {
        for (String name : stack.getBandNames()) {
            if (name.startsWith("i_" + pol + '_') && name.endsWith('_' + date)) {
                return new double[][]{readBand(stack, name), readBand(stack, 'q' + name.substring(1))};
            }
        }
        throw new AssertionError("no i_" + pol + " band for " + date + " in " + java.util.Arrays.toString(stack.getBandNames()));
    }
}
