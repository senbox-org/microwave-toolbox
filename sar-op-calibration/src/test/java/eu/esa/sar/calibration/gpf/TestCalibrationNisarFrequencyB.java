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
import eu.esa.sar.commons.test.TestData;
import eu.esa.sar.io.nisar.NisarProductReaderPlugIn;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Beta0 of NISAR frequency B uses the incidence angle at B's own pixel: on the dual-frequency RSLC,
 * B pixel 6000 of line 4560 is at 46.446 degrees, where frequency A's grid read at that pixel number
 * gives about 37.7 degrees. Skipped without the test data.
 */
public class TestCalibrationNisarFrequencyB {

    private static final File RSLC_A_B = new File(TestData.inputSAR + "NISAR",
            "NISAR_L1_PR_RSLC_036_083_D_080_2005_SHSH_A_20261122T231741_20261122T231746_P05036_N_P_J_001.h5");

    private Product source, target;

    @After
    public void tearDown() {
        if (target != null) target.dispose();
        if (source != null) source.dispose();
    }

    @Test
    @STTM("SNAP-4384")
    public void frequencyBBeta0UsesTheIncidenceAngleAtItsOwnPixel() throws Exception {
        assumeTrue(RSLC_A_B + " not found", RSLC_A_B.exists());
        source = new NisarProductReaderPlugIn().createReaderInstance().readProductNodes(RSLC_A_B, null);

        final CalibrationOp op = new CalibrationOp();
        op.setSourceProduct(source);
        op.setParameter("sourceBandNames", new String[]{"i_HH_S", "q_HH_S"});
        op.setParameter("outputSigmaBand", true);
        op.setParameter("outputBetaBand", true);
        target = op.getTargetProduct();

        final Band sigma = target.getBand("Sigma0_HH_S");
        final Band beta = target.getBand("Beta0_HH_S");
        assertNotNull(Arrays.toString(target.getBandNames()), sigma);
        assertNotNull(Arrays.toString(target.getBandNames()), beta);

        final int x = 6000, y = 4560, w = 8;
        final float[] s = new float[w], b = new float[w];
        sigma.readPixels(x, y, w, 1, s);
        beta.readPixels(x, y, w, 1, b);
        int compared = 0;
        for (int i = 0; i < w; i++) {
            if (b[i] > 0 && s[i] > 0) {
                assertEquals("incidence angle at B pixel " + (x + i), 46.446,
                        Math.toDegrees(Math.asin(s[i] / b[i])), 0.05);
                compared++;
            }
        }
        assertTrue("no valid samples", compared > 0);
    }
}
