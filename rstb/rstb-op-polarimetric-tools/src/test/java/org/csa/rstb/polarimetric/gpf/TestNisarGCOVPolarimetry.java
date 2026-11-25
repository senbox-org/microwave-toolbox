/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. http://www.skywatch.com
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

import com.bc.ceres.annotation.STTM;
import eu.esa.sar.commons.test.TestData;
import eu.esa.sar.io.nisar.NisarProductReaderPlugIn;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.common.SubsetOp;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.junit.After;
import org.junit.Test;

import java.awt.Rectangle;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * The polarimetric operators on NISAR GCOV products, through the C3/C2 bands the GCOV reader adds (SNAP-4384).
 * <p>
 * The synthetic fixtures are not physical covariance matrices, so on them only check that each operator
 * accepts the product and computes. Value ranges are checked on a real quad-pol product when present.
 */
public class TestNisarGCOVPolarimetry {

    private static final String FIXTURE_DIR = "sar-io/src/test/resources/eu/esa/sar/io/nisar/gcov/";
    private static final String FIXTURES = "NISAR_L2_PR_GCOV_001_005_A_219_4020_";
    private static final String FIXTURE_SUFFIX = "_A_20081012T060910_20081012T060926_P01101_F_N_J_001.h5";
    private static final File QPDH = new File(TestData.inputSAR + "NISAR",
            "NISAR_L2_PR_GCOV_036_076_A_020_2005_QPDH_A_20261122T110437_20261122T110441_P05036_N_P_J_001.h5");
    // a valid region of frequency A in the QPDH product
    private static final int Q_X = 11200 - 32, Q_Y = 3072 - 32, Q_SIZE = 64;

    private final List<Product> opened = new ArrayList<>();

    @After
    public void tearDown() {
        opened.forEach(Product::dispose);
    }

    private Product open(final File file) throws Exception {
        assumeTrue(file + " not found", file.exists());
        final Product product = new NisarProductReaderPlugIn().createReaderInstance().readProductNodes(file, null);
        opened.add(product);
        return product;
    }

    /**
     * The GCOV reader's fixtures, in sar-io. Found from the module directory (Maven) or the repository root
     * (an IDE's default working directory); a missing fixture fails rather than skips.
     */
    private Product fixture(final String mode) throws Exception {
        final String name = FIXTURES + mode + FIXTURE_SUFFIX;
        for (String root : new String[]{System.getProperty("basedir", "."), ".", "../.."}) {
            for (String up : new String[]{"../../", ""}) {
                final File file = new File(new File(root, up + FIXTURE_DIR), name);
                if (file.exists()) {
                    return open(file);
                }
            }
        }
        fail("GCOV fixture not found: " + FIXTURE_DIR + name);
        return null;
    }

    private static PolarimetricDecompositionOp decomposition(final Product source, final String name) {
        final PolarimetricDecompositionOp op = new PolarimetricDecompositionOp();
        op.setSourceProduct(source);
        op.SetDecomposition(name);
        op.setParameter("windowSize", 3);
        if (name.equals(PolarimetricDecompositionOp.H_A_ALPHA_DECOMPOSITION)) {
            op.setParameter("outputHAAlpha", true);
        }
        return op;
    }

    /** Computes every band of the operator's target over the region and returns the values by band. */
    private static List<float[]> compute(final Operator op, final int x, final int y, final int w, final int h) throws Exception {
        final Product target = op.getTargetProduct();
        assertTrue("no output bands", target.getNumBands() > 0);
        final List<float[]> values = new ArrayList<>();
        for (Band band : target.getBands()) {
            final float[] v = new float[w * h];
            band.readPixels(x, y, w, h, v);
            values.add(v);
        }
        return values;
    }

    private static Band targetBand(final Operator op, final String nameStart) {
        for (Band b : op.getTargetProduct().getBands()) {
            if (b.getName().startsWith(nameStart)) {
                return b;
            }
        }
        fail("no band " + nameStart + " in " + Arrays.toString(op.getTargetProduct().getBandNames()));
        return null;
    }

    // ---- fixtures: the operators accept GCOV and compute ------------------------------------

    @Test
    @STTM("SNAP-4384")
    public void quadPolDecompositionsAcceptGcov() throws Exception {
        for (String mode : new String[]{"QPQP", "QPDH"}) {
            for (String name : new String[]{PolarimetricDecompositionOp.H_A_ALPHA_DECOMPOSITION,
                    PolarimetricDecompositionOp.FREEMAN_DURDEN_DECOMPOSITION, PolarimetricDecompositionOp.PAULI_DECOMPOSITION}) {
                compute(decomposition(fixture(mode), name), 0, 0, 8, 6);
            }
        }
    }

    @Test
    @STTM("SNAP-4384")
    public void quadPolGcovConvertsToT3() throws Exception {
        final PolarimetricMatricesOp op = new PolarimetricMatricesOp();
        op.setSourceProduct(fixture("QPDH"));
        op.setParameter("matrix", "T3");
        compute(op, 0, 0, 8, 6);
        assertNotNull(op.getTargetProduct().getBand("T11"));
    }

    @Test
    @STTM("SNAP-4384")
    public void dualPolGcovDecomposes() throws Exception {
        compute(decomposition(fixture("DHDH"), PolarimetricDecompositionOp.H_ALPHA_DECOMPOSITION), 0, 0, 8, 6);
    }

    @Test
    @STTM("SNAP-4384")
    public void compactPolGcovGivesStokesParameters() throws Exception {
        final CompactPolStokesParametersOp op = new CompactPolStokesParametersOp();
        op.setSourceProduct(fixture("CPCP"));
        compute(op, 0, 0, 8, 6);
    }

    // ---- real quad-pol product: values in range ---------------------------------------------

    /**
     * A small valid region of the real product. Some decompositions (Freeman-Durden) scan the whole
     * source for the span range first, which on a 17k x 17k product takes many minutes.
     */
    private Product realSubset() throws Exception {
        final SubsetOp subset = new SubsetOp();
        subset.setSourceProduct(open(QPDH));
        subset.setRegion(new Rectangle(Q_X, Q_Y, Q_SIZE, Q_SIZE));
        subset.setCopyMetadata(true);
        final Product product = subset.getTargetProduct();
        opened.add(product);
        return product;
    }

    @Test
    @STTM("SNAP-4384")
    public void quadPolHAAlphaIsInRangeOnRealData() throws Exception {
        final PolarimetricDecompositionOp op = decomposition(realSubset(), PolarimetricDecompositionOp.H_A_ALPHA_DECOMPOSITION);
        compute(op, 0, 0, Q_SIZE, Q_SIZE);
        assertRange(op, "Entropy", 0, 1);
        assertRange(op, "Anisotropy", 0, 1);
        assertRange(op, "Alpha", 0, 90);
    }

    @Test
    @STTM("SNAP-4384")
    public void quadPolFreemanDurdenIsPlausibleOnRealData() throws Exception {
        final PolarimetricDecompositionOp op = decomposition(realSubset(), PolarimetricDecompositionOp.FREEMAN_DURDEN_DECOMPOSITION);
        compute(op, 0, 0, Q_SIZE, Q_SIZE);
        // the components are written in dB
        for (Band b : op.getTargetProduct().getBands()) {
            assertEquals(Unit.INTENSITY_DB, b.getUnit());
            assertRange(op, b.getName(), -80, 40);
        }
    }

    private static void assertRange(final Operator op, final String nameStart, final double min, final double max) throws Exception {
        final Band band = targetBand(op, nameStart);
        final float[] v = new float[Q_SIZE * Q_SIZE];
        band.readPixels(0, 0, Q_SIZE, Q_SIZE, v);
        int finite = 0;
        for (float f : v) {
            if (Float.isFinite(f)) {
                finite++;
                assertTrue(band.getName() + " = " + f + " outside [" + min + ", " + max + "]",
                        f >= min - 1e-4 && f <= max + 1e-4);
            }
        }
        assertTrue(band.getName() + ": too few finite values (" + finite + ")", finite > v.length / 2);
    }
}
