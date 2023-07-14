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
package eu.esa.sar.sar.gpf;

import eu.esa.sar.commons.test.ProcessorTest;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.GeoCoding;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

/**
 * Unit test for Geocoding.
 */
public class TestGeocoding extends ProcessorTest {

    private final static File inputFile = TestData.inputASAR_WSM;

    static {
        TestUtils.initTestEnvironment();
    }

    @Before
    public void setup() throws Exception {
        // If the file does not exist: the test will be ignored
        Assume.assumeTrue("Input file " + inputFile + "does not exist - Skipping test", inputFile.exists());
    }

    /**
     * Processes a product and compares it to processed product known to be correct
     *
     * @throws Exception general exception
     */
    @Test
    public void testProcessing() throws Exception {
        final Product sourceProduct = TestUtils.readSourceProduct(inputFile);

        GeoCoding gc = sourceProduct.getSceneGeoCoding();
        GeoPos geo = new GeoPos();
        PixelPos pix1 = new PixelPos();
        PixelPos pix2 = new PixelPos();
        double errorX = 0, errorY = 0;

        int n = 0;
        for (int i = 0; i < 1000; i += 10) {
            pix1.setLocation(i + 0.5, i + 0.5);

            gc.getGeoPos(pix1, geo);
            gc.getPixelPos(geo, pix2);

            errorX += Math.abs(pix1.getX() - pix2.getX());
            errorY += Math.abs(pix1.getY() - pix2.getY());

            TestUtils.log.info(pix1.getX() + " == " + pix2.getX() + ", " + pix1.getY() + " == " + pix2.getY());
            ++n;
        }
        System.out.println("\nerrorX=" + errorX);
        System.out.println("errorY=" + errorY);
    }
}
