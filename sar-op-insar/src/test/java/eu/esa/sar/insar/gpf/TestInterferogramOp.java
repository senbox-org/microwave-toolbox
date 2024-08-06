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
    private final static File inputFile2 = TestData.inputStackETADS1;

    @Before
    public void setUp() {
        // If the file does not exist: the test will be ignored
        assumeTrue(inputFile1 + " not found", inputFile1.exists());
        assumeTrue(inputFile2 + " not found", inputFile2.exists());
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
    public void testComputeETADPhaseWithHeightCompensation() throws Exception {
        final Product sourceProduct = TestUtils.readSourceProduct(inputFile2);

        final InterferogramOp op = (InterferogramOp) spi.createOperator();
        assertNotNull(op);
        op.setSourceProduct(sourceProduct);
        op.setParameter("subtractTopographicPhase", true);
        op.setParameter("demName", "Copernicus 30m Global DEM");
        op.setParameter("tileExtensionPercent", "40");

        // get targetProduct: execute initialize()
        final Product targetProduct = op.getTargetProduct();
        final float[] expected = new float[] { 1.77912f, 1.93397f, 2.41658f };
        TestUtils.comparePixels(targetProduct, "Phase_ifg_IW2_VV_15Aug2020_27Aug2020", 500, 754, expected);
    }

}
