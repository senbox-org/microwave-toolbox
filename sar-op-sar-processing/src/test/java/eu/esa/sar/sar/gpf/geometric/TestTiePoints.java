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
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Test performance of tie point grid geocoding.
 *
 * <p>Fixtures load once per class via {@link #setUpClass()} since reading the
 * ASAR WSM product is the expensive part; the tests themselves only read
 * tie-point grids and raster dimensions.</p>
 */
public class TestTiePoints extends ProcessorTest {

    private static final File inputFile = TestData.inputASAR_WSM;

    private static Product product1 = null;
    private static Product product2 = null;

    @BeforeClass
    public static void setUpClass() throws Exception {
        // Bail out cheaply if the fixture isn't available; per-test @Before
        // turns this into a JUnit "skipped" via assumeTrue. assumeTrue inside
        // @BeforeClass is not reliable across JUnit 4 versions, hence this
        // two-step pattern.
        if (!inputFile.exists()) {
            return;
        }

        product1 = ProductIO.readProduct(inputFile);
        product2 = ProductIO.readProduct(inputFile);
    }

    @AfterClass
    public static void tearDownClass() {
        if (product1 != null) {
            product1.dispose();
            product1 = null;
        }
        if (product2 != null) {
            product2.dispose();
            product2 = null;
        }
    }

    @Before
    public void setUp() {
        // Per-test skip when the fixture isn't on this machine.
        assumeTrue(inputFile + " not found", inputFile.exists());
        assertNotNull("product1 fixture was not initialised", product1);
        assertNotNull("product2 fixture was not initialised", product2);
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
