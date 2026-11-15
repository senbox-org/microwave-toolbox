/*
 * Copyright (C) 2026 by Michalis Papadimitriou
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
package eu.esa.sar.calibration.gpf.calibrators.tornadovm;

import eu.esa.sar.calibration.gpf.CalibrationOp;
import eu.esa.sar.calibration.gpf.calibrators.Sentinel1Calibrator;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.esa.snap.runtime.Config;
import org.junit.After;
import org.junit.Test;

import java.awt.Rectangle;
import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Compares the TornadoVM tile loop with the Java tile loop of the Sentinel-1 calibrator on a real
 * product. The product is {@code TestData.inputS1_GRD}, or the one given with
 * {@code -Ds1tbx.tornadovm.test.product=<path>}.
 */
public class TestSentinel1CalibrationTornadoVM {

    /** Largest accepted relative difference; the kernel computes in single precision. */
    private static final double TOLERANCE = 1e-5;

    @After
    public void resetPreference() {
        Config.instance().preferences().putBoolean(Sentinel1Calibrator.USE_TORNADOVM_PREFERENCE, false);
    }

    private static File testProduct() {
        final String path = System.getProperty("s1tbx.tornadovm.test.product");
        return path != null ? new File(path) : TestData.inputS1_GRD;
    }

    private static float[] calibrate(final File file, final boolean useTornadoVM, final String bandName,
                                     final Rectangle region) throws Exception {
        Config.instance().preferences().putBoolean(Sentinel1Calibrator.USE_TORNADOVM_PREFERENCE, useTornadoVM);
        try (Product source = TestUtils.readSourceProduct(file)) {
            final CalibrationOp op = (CalibrationOp) new CalibrationOp.Spi().createOperator();
            op.setSourceProduct(source);
            final Product target = op.getTargetProduct();
            final Band band = target.getBand(bandName);
            final Rectangle clipped = region.intersection(new Rectangle(band.getRasterWidth(), band.getRasterHeight()));
            return band.getSourceImage().getData(clipped)
                    .getSamples(clipped.x, clipped.y, clipped.width, clipped.height, 0, (float[]) null);
        }
    }

    @Test
    public void testSigma0MatchesJava() throws Exception {
        final File file = testProduct();
        assumeTrue(file + " not found", file.exists());

        final Rectangle region;
        try (Product source = TestUtils.readSourceProduct(file)) {
            final int w = source.getSceneRasterWidth();
            final int h = source.getSceneRasterHeight();
            region = new Rectangle(w / 2 - 1024, h / 2 - 1024, 2048, 2048);
        }

        final float[] java = calibrate(file, false, "Sigma0_VV", region);
        final long tilesBefore = TornadoVmSentinel1TileAccelerator.tilesCalibrated();
        final float[] tornado = calibrate(file, true, "Sigma0_VV", region);
        assertTrue("the TornadoVM path did not run",
                TornadoVmSentinel1TileAccelerator.tilesCalibrated() > tilesBefore);

        assertEquals(java.length, tornado.length);
        double maxRelative = 0;
        for (int i = 0; i < java.length; i++) {
            final double a = java[i];
            final double b = tornado[i];
            if (Double.isNaN(a) || Double.isNaN(b)) {
                assertEquals("NaN mismatch at " + i, Double.isNaN(a), Double.isNaN(b));
                continue;
            }
            final double scale = Math.max(Math.abs(a), Math.abs(b));
            if (scale > 0) {
                maxRelative = Math.max(maxRelative, Math.abs(a - b) / scale);
            }
        }
        System.out.println("Sigma0_VV, TornadoVM vs Java: max relative difference " + maxRelative +
                " over " + java.length + " pixels");
        assertTrue("max relative difference " + maxRelative, maxRelative <= TOLERANCE);
    }
}
