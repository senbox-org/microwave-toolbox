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
package eu.esa.sar.calibration.gpf;

import com.bc.ceres.annotation.STTM;
import eu.esa.sar.commons.test.ProcessorTest;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Calibration tests for COSMO-SkyMed. Guards against regressions where the polarisation
 * filter in BaseCalibrator silently drops all bands when band names don't end in a
 * recognisable pol suffix (e.g. older CSK readers wrote "i_1"/"Amplitude_1").
 */
public class TestCalibrationCSK extends ProcessorTest {

    private final static OperatorSpi spi = new CalibrationOp.Spi();

    private final static File inputCSK_SCS = new File(TestData.inputSAR
            + "Cosmo/STRIPMAP/HH_Level_1A_hdf5/CSG_SSAR1_SCS_B_0101_STR_012_HH_RD_F_20200921215026_20200921215032_1_F_09S_Z19_N00.h5");
    private final static File inputCSK_DGM = new File(TestData.inputSAR
            + "Cosmo/STRIPMAP/HH_Level_1B_hdf5/CSG_SSAR1_DGM_B_0101_STR_012_HH_RD_F_20200921215026_20200921215032_1_F_09S_Z19_N00.h5");

    @Before
    public void setUp() {
        assumeTrue(inputCSK_SCS + " not found", inputCSK_SCS.exists());
        assumeTrue(inputCSK_DGM + " not found", inputCSK_DGM.exists());
    }

    @Test
    @STTM("SNAP-4161")
    public void testCSK_noPolarizationSelected() throws Exception {
        try (Product srcProduct = TestUtils.readSourceProduct(inputCSK_SCS)) {
            final CalibrationOp op = (CalibrationOp) spi.createOperator();
            op.setSourceProduct(srcProduct);

            final Product tgt = op.getTargetProduct();
            assertNotNull(tgt);
            assertTrue(tgt.getNumBands() > 0);
        }
    }

    @Test
    @STTM("SNAP-4161")
    public void testCSK_withPolarizationSelected() throws Exception {
        try (Product srcProduct = TestUtils.readSourceProduct(inputCSK_SCS)) {
            final CalibrationOp op = (CalibrationOp) spi.createOperator();
            op.setSourceProduct(srcProduct);
            op.setParameter("selectedPolarisations", new String[]{"HH"});

            final Product tgt = op.getTargetProduct();
            assertNotNull(tgt);
            assertTrue("target empty when pol=HH selected on CSK", tgt.getNumBands() > 0);
        }
    }

    /**
     * Regression guard: simulates an older CSK reader output where bands had a numeric
     * suffix ("i_1"/"q_1") instead of "_HH". BaseCalibrator's previous
     * substring-after-last-underscore pol extraction returned "1", which never matched
     * the user's selected polarisation — so every band was filtered out and the target
     * product came back empty. The pol filter must fall back to abstracted metadata.
     */
    @Test
    @STTM("SNAP-4161")
    public void testCSK_bandsWithCounterSuffix_polFilterShouldNotDropAll() throws Exception {
        try (Product srcProduct = TestUtils.readSourceProduct(inputCSK_SCS)) {
            for (Band b : srcProduct.getBands()) {
                final String n = b.getName();
                if (n.startsWith("i_HH")) b.setName("i_1");
                else if (n.startsWith("q_HH")) b.setName("q_1");
                else if (n.startsWith("Amplitude_HH")) b.setName("Amplitude_1");
                else if (n.startsWith("Intensity_HH")) b.setName("Intensity_1");
            }

            final CalibrationOp op = (CalibrationOp) spi.createOperator();
            op.setSourceProduct(srcProduct);
            op.setParameter("selectedPolarisations", new String[]{"HH"});

            final Product tgt = op.getTargetProduct();
            assertNotNull(tgt);
            assertTrue("pol filter wrongly dropped all bands", tgt.getNumBands() > 0);
        }
    }
}
