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
package org.csa.rstb.polarimetric.gpf;

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
 * Unit test for CPRVIOp.
 */
public class TestCPRVIOp {


    static {
        TestUtils.initTestEnvironment();
    }

    private final static OperatorSpi spi = new CPRVIOp.Spi();

    private final static String inputPath = TestData.inputSAR + "/RCM/CP/subset_0_of_RCM1_OK76385_PK678063_2_16MCP2_20080415_143807_CH_CV_SLC.dim";


    @Before
    public void setUp() {
        // If any of the file does not exist: the test will be ignored
        assumeTrue(inputPath + "not found", new File(inputPath).exists());
    }


    private Product runCPRVIOp(final CPRVIOp op, final String path) throws Exception {
        final File inputFile = new File(path);
        final Product sourceProduct = TestUtils.readSourceProduct(inputFile);

        assertNotNull(op);
        op.setSourceProduct(sourceProduct);

        // get targetProduct: execute initialize()
        final Product targetProduct = op.getTargetProduct();
        TestUtils.verifyProduct(targetProduct, false, false);
        return targetProduct;
    }

    @Test
    @STTM("SNAP-3710")
    public void testCPRVIOp() throws Exception {

        runCPRVIOp((CPRVIOp) spi.createOperator(), inputPath);
    }
}
