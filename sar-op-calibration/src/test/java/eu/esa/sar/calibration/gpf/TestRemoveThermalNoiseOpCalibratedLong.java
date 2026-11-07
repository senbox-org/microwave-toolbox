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
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Calibrated (Sigma0) path of {@link Sentinel1RemoveThermalNoiseOp} on a real IW GRD: removing then re-introducing
 * the noise restores the input, and the noise band shows the noise level in both directions.
 */
@RunWith(LongTestRunner.class)
public class TestRemoveThermalNoiseOpCalibratedLong {

    private static final File GRD = new File(TestData.inputSAR +
            "S1/GRD/S1A_IW_GRDH_1SDV_20240508T062559_20240508T062624_053776_0688DB_1A13.SAFE.zip");

    @Test
    public void removeThenReintroduceRestoresSigma0AndKeepsTheNoiseBand() throws Exception {
        assumeTrue(GRD.exists());
        final Product src = ProductIO.readProduct(GRD);
        try {
            final CalibrationOp cal = new CalibrationOp();
            cal.setSourceProduct(src);
            cal.setParameter("outputSigmaBand", true);
            cal.setParameter("selectedPolarisations", new String[]{"VH"});
            final Product sigma = cal.getTargetProduct();

            final Sentinel1RemoveThermalNoiseOp remove = new Sentinel1RemoveThermalNoiseOp();
            remove.setSourceProduct(sigma);
            remove.setParameter("clipNegativeValues", false);
            remove.setParameter("outputNoise", true);
            final Product removed = remove.getTargetProduct();

            final Sentinel1RemoveThermalNoiseOp restore = new Sentinel1RemoveThermalNoiseOp();
            restore.setSourceProduct(removed);
            restore.setParameter("removeThermalNoise", false);
            restore.setParameter("reIntroduceThermalNoise", true);
            restore.setParameter("outputNoise", true);
            final Product restored = restore.getTargetProduct();

            final int x = 5000, y = sigma.getSceneRasterHeight() / 2, n = 64;
            final float[] original = read(sigma.getBand("Sigma0_VH"), x, y, n);
            final float[] roundTrip = read(restored.getBand("Sigma0_VH"), x, y, n);
            final float[] neszRemove = read(removed.getBand("NESZ_VH"), x, y, n);
            final float[] neszRestore = read(restored.getBand("NESZ_VH"), x, y, n);
            final double noData = restored.getBand("NESZ_VH").getNoDataValue();
            for (int i = 0; i < n; i++) {
                assertEquals("sigma0 " + i, original[i], roundTrip[i], 1e-4 * Math.max(1e-6, Math.abs(original[i])) + 1e-9);
                assertTrue("NESZ no-data at " + i, neszRestore[i] != noData && !Float.isNaN(neszRestore[i]));
                assertEquals("NESZ " + i, neszRemove[i], neszRestore[i], 1e-3);
            }
        } finally {
            src.dispose();
        }
    }

    private static float[] read(final Band band, final int x, final int y, final int n) throws Exception {
        final float[] v = new float[n];
        band.readPixels(x, y, n, 1, v, ProgressMonitor.NULL);
        return v;
    }
}
