/*
 * Copyright (C) 2025 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.sentinel1.gpf.util;

import com.bc.ceres.annotation.STTM;
import eu.esa.sar.commons.test.TestData;
import eu.esa.sar.sentinel1.gpf.ETADDeburstOp;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

@STTM("SNAP-4002")
public class TestETADDeburstOp {

    private final File S1_IW_ETAD = new File(TestData.inputSAR + "S1/ETAD/IW/S1B_IW_ETA__AXDV_20200815T173048_20200815T173116_022937_02B897_E56D.SAFE/manifest.safe");

    private final static OperatorSpi spi = new ETADDeburstOp.Spi();

    @Before
    public void setUp() {
        // If any of the file does not exist: the test will be ignored
        assumeTrue(S1_IW_ETAD + " not found", S1_IW_ETAD.exists());
    }

    @Test
    public void testETADDeburst() throws Exception {
        try(final Product sourceProduct = ProductIO.readProduct(S1_IW_ETAD)) {

            final ETADDeburstOp op = (ETADDeburstOp) spi.createOperator();
            assertNotNull(op);
            op.setSourceProduct(sourceProduct);
            op.setParameter("selectedSwath", "IW1");
            op.setParameter("selectedLayer", "sumOfCorrectionsAz");

            // get targetProduct: execute initialize()
            final Product targetProduct = op.getTargetProduct();
            TestUtils.verifyProduct(targetProduct, true, true, true);

            final float[] expected = new float[] {-0.0004380696f, -0.0004279505f, -0.0004174325f};
            TestUtils.comparePixels(targetProduct, "IW1_sumOfCorrectionsAz", expected);
        }
    }
}
