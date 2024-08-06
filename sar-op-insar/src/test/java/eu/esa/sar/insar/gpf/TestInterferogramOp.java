/*
 * Copyright (C) 20123 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.insar.gpf;

import com.bc.ceres.annotation.STTM;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

/**
 * Unit test for Calibration Operator.
 */
public class TestInterferogramOp {

    private final static File inputFile1 = TestData.inputStackS1;

    @Before
    public void setUp() {
        // If the file does not exist: the test will be ignored
        assumeTrue(inputFile1 + " not found", inputFile1.exists());
    }

    static {
        TestUtils.initTestEnvironment();
    }

    private final static OperatorSpi spi = new InterferogramOp.Spi();

    @Test
    @STTM("SNAP-3687")
    public void testProcessingInterferogram() throws Exception {
        final Product sourceProduct = TestUtils.readSourceProduct(inputFile1);

        final InterferogramOp op = (InterferogramOp) spi.createOperator();
        assertNotNull(op);
        op.setSourceProduct(sourceProduct);

        // get targetProduct: execute initialize()
        final Product targetProduct = op.getTargetProduct();
        final float[] expected = new float[] { -0.56316f,-3.09884f,-2.96232f };
        TestUtils.comparePixels(targetProduct, "Phase_ifg_IW1_VH_25Feb2018_09Mar2018", expected);
    }

    @Test
    @STTM("SNAP-3723")
    public void testProcessingInterferogramFlatEarthPhase() throws Exception {
        final Product sourceProduct = TestUtils.readSourceProduct(inputFile1);

        final InterferogramOp op = (InterferogramOp) spi.createOperator();
        assertNotNull(op);
        op.setSourceProduct(sourceProduct);
        op.setParameter("outputFlatEarthPhase", true);

        // get targetProduct: execute initialize()
        final Product targetProduct = op.getTargetProduct();
        assumeTrue(targetProduct.containsBand("fep_IW1_VH_25Feb2018_09Mar2018"));
    }

    @Test
    @STTM("SNAP-3780")
    public void testComputeETADPhaseWithHeightCompensation() {

        final InterferogramOp op = (InterferogramOp) spi.createOperator();
        assertNotNull(op);

        final int burstIndex0 = op.getBurstIndex(754, 1509);
        final int burstIndex1 = op.getBurstIndex(2263, 1509);
        final int burstIndex2 = op.getBurstIndex(3772, 1509);
        final int burstIndex3 = op.getBurstIndex(5281, 1509);
        final int burstIndex4 = op.getBurstIndex(6790, 1509);
        final int burstIndex5 = op.getBurstIndex(8299, 1509);
        final int burstIndex6 = op.getBurstIndex(9808, 1509);
        final int burstIndex7 = op.getBurstIndex(11317, 1509);
        final int burstIndex8 = op.getBurstIndex(12826, 1509);

        Assert.assertEquals(0, burstIndex0, 0);
        Assert.assertEquals(1, burstIndex1, 0);
        Assert.assertEquals(2, burstIndex2, 0);
        Assert.assertEquals(3, burstIndex3, 0);
        Assert.assertEquals(4, burstIndex4, 0);
        Assert.assertEquals(5, burstIndex5, 0);
        Assert.assertEquals(6, burstIndex6, 0);
        Assert.assertEquals(7, burstIndex7, 0);
        Assert.assertEquals(8, burstIndex8, 0);
    }
}
