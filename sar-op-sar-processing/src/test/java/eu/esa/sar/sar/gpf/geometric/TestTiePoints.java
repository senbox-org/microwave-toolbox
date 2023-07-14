/*
 * Copyright (C) 2014 by Array Systems Computing Inc. http://www.array.ca
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

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.test.ProcessorTest;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.TiePointGrid;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Test performance of tie point grid geocoding
 */
public class TestTiePoints extends ProcessorTest {

    private final File inputFile = TestData.inputASAR_WSM;

    private Product product1 = null;
    private Product product2 = null;

    @Before
    public void setUp() throws Exception {
        try {
            // If the file does not exist: the test will be ignored
            assumeTrue(inputFile + " not found", inputFile.exists());

            product1 = ProductIO.readProduct(inputFile);
            product2 = ProductIO.readProduct(inputFile);

            // If the product does not exist: the test will be ignored
            assumeTrue(product1 + " not found", product1 != null);
            assumeTrue(product2 + " not found", product2 != null);
        } catch (Exception e) {
            TestUtils.skipTest(this, e.getMessage());
            throw e;
        }
    }

    @Test
    public void testGetPixelDouble() throws Exception {
        TiePointGrid tpg = product1.getTiePointGridAt(0);
        int w = product1.getSceneRasterWidth();
        int h = product1.getSceneRasterHeight();

        double[] floats1 = new double[w * h];
        int i = 0;
        for (int x = 0; x < w; ++x) {
            for (int y = 0; y < h; ++y) {
                floats1[i++] = tpg.getPixelDouble(x, y);
            }
        }
    }

    @Test
    public void testGetPixels() throws Exception {
        TiePointGrid tpg = product2.getTiePointGridAt(0);
        int w = product2.getSceneRasterWidth();
        int h = product2.getSceneRasterHeight();

        double[] floats = new double[w * h];
        tpg.getPixels(0, 0, w, h, floats, ProgressMonitor.NULL);
    }

    @Test
    public void testCompareFloats() throws Exception {

        final TiePointGrid tpg = product1.getTiePointGridAt(0);
        int w = product1.getSceneRasterWidth();
        int h = product1.getSceneRasterHeight();

        final double[] floats = new double[w * h];
        tpg.getPixels(0, 0, w, h, floats, ProgressMonitor.NULL);

        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                final double f = tpg.getPixelDouble(x, y);
                assertTrue(f == floats[y * w + x]);
            }
        }
    }
}
