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
import org.esa.snap.core.dataio.ProductSubsetDef;
import org.esa.snap.core.datamodel.Product;
import org.junit.After;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Complex (compound) samples read with sub-sampling, which the reader serves from 512-line strips:
 * a read spanning several strips must fill every line. Skipped without the test data.
 */
public class TestNisarComplexReads {

    private static final File RSLC_B_ONLY = new File(TestData.inputSAR + "NISAR",
            "NISAR_L1_PR_RSLC_036_079_D_076_0005_NASV_A_20261122T163605_20261122T163610_P05036_N_P_J_001.h5");

    private Product full, decimated;

    @After
    public void tearDown() {
        if (full != null) full.dispose();
        if (decimated != null) decimated.dispose();
    }

    @Test
    @STTM("SNAP-4384")
    public void subSampledComplexReadFillsLinesAcrossStrips() throws Exception {
        assumeTrue(RSLC_B_ONLY + " not found", RSLC_B_ONLY.exists());
        final int step = 3, x0 = 3000, w = 16, h = 400;   // 1200 source lines: three strips
        full = new NisarProductReaderPlugIn().createReaderInstance().readProductNodes(RSLC_B_ONLY, null);
        final ProductSubsetDef def = new ProductSubsetDef();
        def.setSubSampling(1, step);
        decimated = new NisarProductReaderPlugIn().createReaderInstance().readProductNodes(RSLC_B_ONLY, def);

        for (String band : new String[]{"i_VV_S", "q_VV_S"}) {
            final float[] got = new float[w * h];
            decimated.getBand(band).readPixels(x0, 0, w, h, got);
            final float[] column = new float[(h - 1) * step + 1];
            full.getBand(band).readPixels(x0 + 5, 0, 1, column.length, column);
            int nonZero = 0;
            for (int y = 0; y < h; y++) {
                assertEquals(band + " line " + y, column[y * step], got[y * w + 5], 0f);
                if (got[y * w + 5] != 0f) nonZero++;
            }
            assertTrue(band + " has data", nonZero > h / 2);
        }
    }
}
