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
package eu.esa.sar.io.nisar;

import com.bc.ceres.annotation.STTM;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Every NISAR product type, as currently distributed by ASF (2026-11), opens with bands, data and
 * geocoding. Skipped for products not in the test data folder.
 */
@RunWith(Parameterized.class)
public class TestNisarCurrentProducts {

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> products() {
        return Arrays.asList(new Object[][]{
                {"RSLC", "NISAR_L1_PR_RSLC_036_079_D_076_0005_NASV_A_20261122T163605_20261122T163610_P05036_N_P_J_001.h5", true, null},
                {"RSLC-AB", "NISAR_L1_PR_RSLC_036_083_D_080_2005_SHSH_A_20261122T231741_20261122T231746_P05036_N_P_J_001.h5", true, null},
                {"RIFG", "NISAR_L1_PR_RIFG_035_056_A_045_036_7700_SH_20261109T020117_20261109T020123_20261121T020117_20261121T020124_P05036_N_P_J_001.h5", true, "no geocoding: the tie-point path supports UTM only, this product is polar stereographic (EPSG 3413)"},
                {"RUNW", "NISAR_L1_PR_RUNW_035_056_A_045_036_7700_SH_20261109T020117_20261109T020123_20261121T020117_20261121T020124_P05036_N_P_J_001.h5", true, "no geocoding: the tie-point path supports UTM only, this product is polar stereographic (EPSG 3413)"},
                {"ROFF", "NISAR_L1_PR_ROFF_033_044_D_123_036_4000_SH_20261015T064628_20261015T064656_20261120T064629_20261120T064650_P05036_N_P_J_001.h5", true, "no geocoding: the tie-point path supports UTM only, this product is polar stereographic (EPSG 3031)"},
                {"GSLC", "NISAR_L2_PR_GSLC_036_034_A_019_4005_DHDH_A_20261119T130824_20261119T130859_P05036_N_F_J_001.h5", true, null},
                {"GCOV-NASV", "NISAR_L2_PR_GCOV_036_078_D_050_0005_NASV_A_20261122T144055_20261122T144102_P05036_N_P_J_001.h5", true, null},
                {"GCOV-DHDH", "NISAR_L2_PR_GCOV_036_066_A_004_2005_DHDH_A_20261121T181649_20261121T181653_P05036_N_P_J_001.h5", true, null},
                {"GCOV-QPDH", "NISAR_L2_PR_GCOV_036_076_A_020_2005_QPDH_A_20261122T110437_20261122T110441_P05036_N_P_J_001.h5", true, null},
                {"GUNW", "NISAR_L2_PR_GUNW_035_064_D_097_036_4000_SH_20261109T154941_20261109T154944_20261121T154941_20261121T154944_P05036_N_P_J_001.h5", true, null},
                {"GOFF", "NISAR_L2_UR_GOFF_033_004_A_016_035_4000_SH_20261012T111038_20261012T111044_20261105T111039_20261105T111045_P05023_F_P_J_001.h5", true, null},
                {"SME2", "NISAR_L3_PR_SME2_033_137_D_116_0005_NASV_A_20261021T173144_20261021T173203_P05023_N_P_J_001.h5", false, "SME2 layout changed: layers are now under grids/algorithmCandidates/{DSG,PMI,TSR}"},
        });
    }

    private final File file;
    private final boolean sar;
    /** A known reader limitation for this product (SNAP-4384 follow-ups), or null. */
    private final String knownIssue;
    private Product product;

    public TestNisarCurrentProducts(final String type, final String name, final boolean sar, final String knownIssue) {
        this.file = new File(TestData.inputSAR + "NISAR", name);
        this.sar = sar;
        this.knownIssue = knownIssue;
    }

    @After
    public void tearDown() {
        if (product != null) {
            product.dispose();
        }
    }

    @Test
    @STTM("SNAP-4384")
    public void opensWithDataGeocodingAndRadarFrequency() throws Exception {
        assumeTrue(file + " not found", file.exists());
        assumeTrue("known issue: " + knownIssue, knownIssue == null);
        product = new NisarProductReaderPlugIn().createReaderInstance().readProductNodes(file, null);
        assertNotNull(product);
        assertTrue("no bands", product.getNumBands() > 0);
        assertNotNull("no geocoding", product.getSceneGeoCoding());

        // some data in the middle of the first band
        final Band band = product.getBandAt(0);
        final int w = Math.min(64, band.getRasterWidth()), h = Math.min(64, band.getRasterHeight());
        final float[] v = new float[w * h];
        band.readPixels((band.getRasterWidth() - w) / 2, (band.getRasterHeight() - h) / 2, w, h, v);

        assertEquals("is a SAR product (radar_frequency set)", sar, new InputProductValidator(product).isSARProduct());
    }
}
