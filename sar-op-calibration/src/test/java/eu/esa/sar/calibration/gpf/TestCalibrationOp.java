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
package eu.esa.sar.calibration.gpf;

import com.bc.ceres.annotation.STTM;
import eu.esa.sar.commons.test.ProcessorTest;
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
public class TestCalibrationOp extends ProcessorTest {

    private final static OperatorSpi spi = new CalibrationOp.Spi();

    @Before
    public void setUp() {
        // If any of the file does not exist: the test will be ignored
        assumeTrue(TestData.inputASAR_WSM + "not found", TestData.inputASAR_WSM.exists());
        assumeTrue(TestData.inputASAR_IMS + "not found", TestData.inputASAR_IMS.exists());
        assumeTrue(TestData.inputERS_IMP + "not found", TestData.inputERS_IMP.exists());
        assumeTrue(TestData.inputERS_IMS + "not found", TestData.inputERS_IMS.exists());
        assumeTrue(TestData.inputS1_GRD + "not found", TestData.inputS1_GRD.exists());
        assumeTrue(TestData.inputS1_StripmapSLC + "not found", TestData.inputS1_StripmapSLC.exists());
    }

    @Test
    public void testProcessingASAR_WSM_Sigma0() throws Exception {

        final float[] expected = new float[] {0.027908697724342346f, 0.019894488155841827f, 0.020605698227882385f};
        processFile(TestData.inputASAR_WSM, "sigma0_VV", expected);
    }

    @Test
    public void testProcessingASAR_WSM_beta0() throws Exception {

        final float[] expected = new float[] {0.05965894f, 0.04252025f, 0.044032857f};
        processFile(TestData.inputASAR_WSM, "beta0_VV", expected, true);
    }

    @Test
    public void testProcessingASAR_IMS() throws Exception {

        final float[] expected = new float[] {0.043132662773132324f, 3.3039296977221966E-4f, 0.06897620856761932f};
        processFile(TestData.inputASAR_IMS, "sigma0_VV", expected);
    }

    @Test
    public void testProcessingERS_IMP() throws Exception {

        final float[] expected = new float[] {0.19550558924674988f, 0.19353462755680084f, 0.1338571310043335f};
        processFile(TestData.inputERS_IMP, "sigma0_VV", expected);
    }

    @Test
    public void testProcessingERS_IMS() throws Exception {

        final float[] expected = new float[] {0.11003237217664719f, 0.09509188681840897f, 0.01090210024267435f};
        processFile(TestData.inputERS_IMS, "sigma0_VV", expected);
    }

    @Test
    public void testProcessingS1_GRD() throws Exception {

        final float[] expected = new float[] {2.3076418642631324E-7f,2.3079778088685998E-7f,3.255083242947876E-7f};
        processFile(TestData.inputS1_GRD, "sigma0_VV", expected);
    }

    @Test
    public void testProcessingS1_StripmapSLC() throws Exception {

        final float[] expected = new float[] {0.03781468f,0.14200227f,0.3646295f};
        processFile(TestData.inputS1_StripmapSLC, "sigma0_VV", expected);
    }

    @Test
    @STTM("SNAP-3672")
    public void testProcessingCapella_StripmapSLC() throws Exception {

        final float[] expected = new float[] {0.01825511f,0.04138892f,0.04425726f};
        processFile(TestData.inputCapella_StripmapSLC, "sigma0_HH", expected);
        System.out.println();
    }

    /**
     * Processes a product and compares it to processed product known to be correct
     *
     * @param inputFile the path to the input product
     * @param bandName the target band name to verify
     * @param expected expected values
     * @throws Exception general exception
     */
    private void processFile(final File inputFile, final String bandName, final float[] expected) throws Exception {
        processFile(inputFile, bandName, expected, false);
    }

    private void processFile(final File inputFile, final String bandName, final float[] expected,
                             boolean outputBeta0) throws Exception {

        try(final Product sourceProduct = TestUtils.readSourceProduct(inputFile)) {

            final CalibrationOp op = (CalibrationOp) spi.createOperator();
            assertNotNull(op);
            if(outputBeta0) {
                //op.setParameter("outputBetaBand", true);
                op.setParameter("createBetaBand", true);
            }
            op.setSourceProduct(sourceProduct);

            // get targetProduct: execute initialize()
            final Product targetProduct = op.getTargetProduct();
            TestUtils.verifyProduct(targetProduct, true, true, true);

            TestUtils.comparePixels(targetProduct, bandName, expected);
        }
    }
}
