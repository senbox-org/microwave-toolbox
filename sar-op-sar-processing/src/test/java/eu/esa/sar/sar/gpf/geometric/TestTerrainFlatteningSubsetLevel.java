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
package eu.esa.sar.sar.gpf.geometric;

import com.bc.ceres.annotation.STTM;
import com.bc.ceres.core.ProgressMonitor;
import com.bc.ceres.test.LongTestRunner;
import eu.esa.sar.calibration.gpf.CalibrationOp;
import eu.esa.sar.commons.test.ProcessorTest;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.common.SubsetOp;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.awt.Rectangle;
import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Terrain-Flattening's normalisation must not depend on where a subset starts.
 * <p>
 * The same ground pixels are processed twice from an S1 IW GRD: once in a full-width strip and once in a
 * subset starting at 60 % of the swath. On a GRD the simulated image is divided by dSR/dGR, which used to
 * be evaluated at {@code x * rangeSpacing} without the subset's ground range origin. The subset was then
 * normalised with the ratio of the swath's first columns and its gamma0 came out about 0.8 dB low.
 * <p>
 * Needs the S1C GRD from the shared test tree and the Copernicus 30 m DEM (auto-download).
 */
@RunWith(LongTestRunner.class)
public class TestTerrainFlatteningSubsetLevel extends ProcessorTest {

    private static final File INPUT = TestData.inputS1C_GRD;
    private static final int LINES = 300;
    private static final int SUBSET_WIDTH = 1000;
    private static final int MARGIN = 100;

    private static Product subset(final Product src, final Rectangle region) {
        final SubsetOp op = new SubsetOp();
        op.setSourceProduct(src);
        op.setRegion(region);
        op.setCopyMetadata(true);
        return op.getTargetProduct();
    }

    private static Product terrainFlatten(final Product src) {
        final CalibrationOp cal = new CalibrationOp();
        cal.setSourceProduct(src);
        cal.setParameter("selectedPolarisations", new String[]{"VV"});
        cal.setParameter("outputSigmaBand", false);
        cal.setParameter("outputBetaBand", true);

        final TerrainFlatteningOp op = (TerrainFlatteningOp) new TerrainFlatteningOp.Spi().createOperator();
        op.setSourceProduct(cal.getTargetProduct());
        op.setParameter("outputSimulatedImage", true);
        return op.getTargetProduct();
    }

    private static float[] simulated(final Product tf, final int x, final int y, final int w, final int h)
            throws Exception {
        final Band band = tf.getBand("simulatedImage");
        assertNotNull("no simulatedImage in " + String.join(", ", tf.getBandNames()), band);
        final float[] data = new float[w * h];
        band.readPixels(x, y, w, h, data, ProgressMonitor.NULL);
        return data;
    }

    @Test

    @STTM("SNAP-4377")
    public void testSimulatedImageDoesNotDependOnSubsetOrigin() throws Exception {
        assumeTrue(INPUT + " not found", INPUT.exists());

        final Product src = TestUtils.readSourceProduct(INPUT);
        try {
            final int width = src.getSceneRasterWidth();
            final int y0 = src.getSceneRasterHeight() / 2 - LINES / 2;
            final int xs = (int) (0.6 * width);

            final Product strip = terrainFlatten(subset(src, new Rectangle(0, y0, width, LINES)));
            final Product part = terrainFlatten(subset(src, new Rectangle(xs, y0, SUBSET_WIDTH, LINES)));

            final int w = SUBSET_WIDTH - 2 * MARGIN;
            final int h = LINES - 2 * MARGIN;
            final float[] a = simulated(strip, xs + MARGIN, MARGIN, w, h);
            final float[] b = simulated(part, MARGIN, MARGIN, w, h);

            final double[] ratios = new double[a.length];
            int n = 0;
            for (int i = 0; i < a.length; i++) {
                if (a[i] > 0 && b[i] > 0 && Float.isFinite(a[i]) && Float.isFinite(b[i])) {
                    ratios[n++] = b[i] / (double) a[i];
                }
            }
            assertTrue("too few valid simulated pixels: " + n, n > a.length / 2);
            final double[] valid = Arrays.copyOf(ratios, n);
            Arrays.sort(valid);
            final double median = valid[n / 2];

            // 0.01 is 0.04 dB; before the fix the subset read about 0.83 (-0.8 dB) here
            assertEquals("median subset/full-width simulated image", 1.0, median, 0.01);

            strip.dispose();
            part.dispose();
        } finally {
            src.dispose();
        }
    }
}
