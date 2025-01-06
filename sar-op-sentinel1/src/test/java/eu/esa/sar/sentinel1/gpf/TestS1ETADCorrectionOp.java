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
package eu.esa.sar.sentinel1.gpf;

import com.bc.ceres.annotation.STTM;
import eu.esa.sar.cloud.opendata.DataSpaces;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.TiePointGrid;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.util.io.FileUtils;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

@STTM("SNAP-3815")
public class TestS1ETADCorrectionOp {

    private final File S1_IW_SLC = new File(TestData.inputSAR + "S1/ETAD/IW/S1B_IW_SLC__1SDV_20200815T173048_20200815T173116_022937_02B897_F7CF_Orb_IW1.dim");
    private final File S1_IW_ETAD = new File(TestData.inputSAR + "S1/ETAD/IW/S1B_IW_ETA__AXDV_20200815T173048_20200815T173116_022937_02B897_E56D.SAFE/manifest.safe");
    private final File S1_SM_SLC = new File(TestData.inputSAR + "S1/ETAD/SM/subset_0_of_S1B_S4_SLC__1SDV_20200827T014634_20200827T014658_023102_02BDCF_DB58.dim");
    private final File S1_SM_ETAD = new File(TestData.inputSAR + "S1/ETAD/SM/S1B_S4_ETA__AXDV_20200827T014634_20200827T014658_023102_02BDCF_82DD.SAFE/manifest.safe");

    private final static OperatorSpi spi = new S1ETADCorrectionOp.Spi();

    @Before
    public void setUp() {
        // If any of the file does not exist: the test will be ignored
        assumeTrue(S1_IW_SLC + " not found", S1_IW_SLC.exists());
        assumeTrue(S1_IW_ETAD + " not found", S1_IW_ETAD.exists());
        assumeTrue(S1_SM_SLC + " not found", S1_SM_SLC.exists());
        assumeTrue(S1_SM_ETAD + " not found", S1_SM_ETAD.exists());
    }

    @Test
    @STTM("SNAP-3910")
    public void testTOPSCorrectorInSAR() throws Exception {
        try(final Product sourceProduct = TestUtils.readSourceProduct(S1_IW_SLC)) {

            final S1ETADCorrectionOp op = (S1ETADCorrectionOp) spi.createOperator();
            assertNotNull(op);
            op.setSourceProduct(sourceProduct);
            op.setParameter("etadFile", new File(S1_IW_ETAD.getPath()));
            op.setParameter("outputPhaseCorrections", true);
            op.setParameter("resamplingImage", false);

            // get targetProduct: execute initialize()
            final Product targetProduct = op.getTargetProduct();
            TestUtils.verifyProduct(targetProduct, true, true, true);

            final float[] expected = new float[] {-602.80945f, -608.42346f, -614.2902f};
            final TiePointGrid phaseTPG = targetProduct.getTiePointGrid("etadPhaseCorrection_IW1_3");
            final float[] points = phaseTPG.getTiePoints();
            for (int i = 0; i < expected.length; ++i) {
                if ((Math.abs(expected[i] - points[i]) > 0.0001)) {
                    String msg = "actual:";
                    for (int j = 0; j < expected.length; ++j) {
                        msg += points[j] + ", ";
                    }
                    TestUtils.log.info(msg);
                    msg = "expected:";
                    for (float anExpected : expected) {
                        msg += anExpected + ", ";
                    }
                    TestUtils.log.info(msg);
                    throw new IOException("Mismatch [" + i + "] " + points[i] + " is not " + expected[i] + " for " +
                            targetProduct.getName() + " TPG: " + "etadPhaseCorrection_IW1_3");
                }
            }

            int numOfETADTpgs = 0;
            for (TiePointGrid tpg : targetProduct.getTiePointGrids()) {
                if (tpg.getName().startsWith("etad")) {
                    numOfETADTpgs++;
                }
            }
            Assert.assertEquals(3, numOfETADTpgs, 0);
        }
    }

    @Test
    public void testSMCorrectorInSAR() throws Exception {
        try(final Product sourceProduct = TestUtils.readSourceProduct(S1_SM_SLC)) {

            final S1ETADCorrectionOp op = (S1ETADCorrectionOp) spi.createOperator();
            assertNotNull(op);
            op.setSourceProduct(sourceProduct);
            op.setParameter("etadFile", new File(S1_SM_ETAD.getPath()));
            op.setParameter("outputPhaseCorrections", true);
            op.setParameter("resamplingImage", false);

            // get targetProduct: execute initialize()
            final Product targetProduct = op.getTargetProduct();
            TestUtils.verifyProduct(targetProduct, true, true, true);

            final float[] expected = new float[] {-677.40881f, -677.41156f, -677.41425f};
            TestUtils.comparePixels(targetProduct, "etadPhaseCorrection", expected);
        }
    }
}
