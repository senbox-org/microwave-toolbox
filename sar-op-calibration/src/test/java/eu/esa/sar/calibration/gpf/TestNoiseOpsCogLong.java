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

import com.bc.ceres.core.ProgressMonitor;
import com.bc.ceres.test.LongTestRunner;
import eu.esa.sar.calibration.gpf.support.S1IpfVersion;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * CDSE COG products: the manifest names the COGifier before the IPF. Both noise operators must use the IPF version.
 */
@RunWith(LongTestRunner.class)
public class TestNoiseOpsCogLong {

    private static final File COG = new File(TestData.inputSAR +
            "S1/COG/S1A_IW_GRDH_1SDV_20231030T181230_20231030T181255_050998_062611_D4AC_COG.SAFE.zip");

    @Test
    public void thermalNoiseRemovalKeepsValidPixels() throws Exception {
        assumeTrue(COG.exists());
        final Product src = ProductIO.readProduct(COG);
        try {
            assertTrue("IPF " + S1IpfVersion.get(src), S1IpfVersion.get(src) >= 2.90);
            final Sentinel1RemoveThermalNoiseOp op = new Sentinel1RemoveThermalNoiseOp();
            op.setSourceProduct(src);
            final Band band = op.getTargetProduct().getBand("Intensity_VV");
            final int w = band.getRasterWidth();
            final float[] row = new float[w];
            band.readPixels(0, band.getRasterHeight() / 2, w, 1, row, ProgressMonitor.NULL);
            int valid = 0, floor = 0;
            for (float v : row) {
                if (v != 0) {
                    valid++;
                    if (v == Sentinel1RemoveThermalNoiseOp.trgFloorValue) floor++;
                }
            }
            assertTrue("valid " + valid, valid > 1000);
            assertTrue("clipped " + floor + " of " + valid, floor < valid / 10);
        } finally {
            src.dispose();
        }
    }

    @Test
    public void borderNoiseRemovalPassesThroughRecentCog() throws Exception {
        assumeTrue(COG.exists());
        final Product src = ProductIO.readProduct(COG);
        try {
            final RemoveGRDBorderNoiseOp op = new RemoveGRDBorderNoiseOp();
            op.setSourceProduct(src);
            final Band s = src.getBand("Amplitude_VV");
            final Band t = op.getTargetProduct().getBand("Amplitude_VV");
            final float[] a = new float[512 * 512];
            final float[] b = new float[512 * 512];
            s.readPixels(0, 0, 512, 512, a, ProgressMonitor.NULL);
            t.readPixels(0, 0, 512, 512, b, ProgressMonitor.NULL);
            assertArrayEquals(a, b, 0f);
        } finally {
            src.dispose();
        }
    }
}
