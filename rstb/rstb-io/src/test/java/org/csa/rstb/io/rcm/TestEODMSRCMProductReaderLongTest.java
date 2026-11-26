/*
 * Copyright (C) 2026 Skywatch. https://www.skywatch.com
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
package org.csa.rstb.io.rcm;

import com.bc.ceres.annotation.STTM;
import com.bc.ceres.test.LongTestRunner;
import eu.esa.sar.commons.test.ProductValidator;
import eu.esa.sar.commons.test.ReaderTest;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

/**
 * RCM reader tests on large EODMS / Open Data products (8-37 s each).
 * Run with -Denable.long.tests=true.
 */
@RunWith(LongTestRunner.class)
public class TestEODMSRCMProductReaderLongTest extends ReaderTest {

    private final static File eodmsScanSAR_CX_SLCZip = new File(TestData.inputSAR +"RCM/EODMS/SLC/RCM1_OK1683816_PK1836418_1_SC30MCPB_20211026_112824_CH_CV_SLC.zip");

    private final static File eodmsQP_SLCZip = new File(TestData.inputSAR +"RCM/EODMS/SLC/RCM1_OK1925486_PK1946630_1_QP8_20220103_110328_HH_VV_HV_VH_SLC.zip");

    private final static File inputCP_SLC = new File(TestData.inputSAR + "RCM/OpenData/Winnipeg/RCM1_OK1050595_PK1051816_1_3MCP24_20200219_123901_CH_CV_SLC");
    private final static File inputCP_GCC = new File(TestData.inputSAR + "RCM/OpenData/Winnipeg/RCM1_OK1052791_PK1052794_1_3MCP24_20200219_123901_CH_CV_GCC");
    private final static File inputCP_GRC = new File(TestData.inputSAR + "RCM/OpenData/Winnipeg/RCM1_OK1052791_PK1052795_1_3MCP24_20200219_123901_CH_CV_GRC");

    public TestEODMSRCMProductReaderLongTest() {
        super(new RCMProductReaderPlugIn());
    }

    /**
     * ScanSAR SLC products deliver one image per burst; the reader does not support them yet (docs/roadmap.md) and
     * must say so instead of failing on a duplicate band name. When burst support is added, validate the bands here.
     */
    @Test
    public void testScanSAR_CX_SLCZipIsRefusedClearly() throws Exception {
        assumeTrue(eodmsScanSAR_CX_SLCZip + " not found", eodmsScanSAR_CX_SLCZip.exists());
        try {
            testReader(eodmsScanSAR_CX_SLCZip.toPath());
            fail("burst-mode ScanSAR SLC must be refused");
        } catch (Exception e) {
            String msg = "";
            for (Throwable t = e; t != null; t = t.getCause()) {
                msg += t.getMessage() + " | ";
            }
            assertTrue(msg, msg.contains("burst-mode (ScanSAR) SLC products are not supported"));
        }
    }

    @Test
    public void testOpeningQP_SLCZip() throws Exception {
        assumeTrue(eodmsQP_SLCZip + " not found", eodmsQP_SLCZip.exists());
        Product prod = testReader(eodmsQP_SLCZip.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"i_HH","q_HH","Intensity_HH", "i_HV","q_HV","Intensity_HV","i_VV","q_VV","Intensity_VV", "i_VH","q_VH","Intensity_VH"});
    }

    @Test
    public void testOpeningCP_SLC() throws Exception {
        assumeTrue(inputCP_SLC + " not found", inputCP_SLC.exists());
        Product prod = testReader(inputCP_SLC.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"i_RCH","q_RCH","Intensity_RCH","i_RCV","q_RCV","Intensity_RCV"});
    }

    @Test
    public void testOpeningCP_GCC() throws Exception {
        assumeTrue(inputCP_GCC + " not found", inputCP_GCC.exists());
        Product prod = testReader(inputCP_GCC.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"Amplitude_RCH","Intensity_RCH","Amplitude_RCV","Intensity_RCV"});
    }

    @Test
    @STTM("SNAP-3547")
    public void testOpeningCP_GRC() throws Exception {
        assumeTrue(inputCP_GRC + " not found", inputCP_GRC.exists());
        Product prod = testReader(inputCP_GRC.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"i_RCH","q_RCH","Intensity_RCH","i_RCV","q_RCV","Intensity_RCV"});

        MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(prod);
        String mode = absRoot.getAttributeString(AbstractMetadata.ACQUISITION_MODE);
        assertEquals("Stripmap", mode);
    }
}
