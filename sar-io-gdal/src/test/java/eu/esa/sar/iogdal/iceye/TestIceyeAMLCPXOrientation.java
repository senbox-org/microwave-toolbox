/*
 * Copyright (C) 2025 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.iogdal.iceye;

import eu.esa.sar.commons.test.ReaderTest;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Product;
import org.junit.After;
import org.junit.Test;

import java.awt.image.RenderedImage;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Pins the raster orientation of the ICEYE AML/CPX reader.
 * <p>
 * The reader declared its bands transposed - width from the TIFF's ImageLength,
 * height from its ImageWidth, which is right - but then handed them the GDAL
 * image untransposed. On a square frame that is invisible; on any other it is
 * wrong in both axes. Every delivered AML/CPX frame at hand was square, so the
 * defect sat unnoticed.
 * <p>
 * The fixtures are synthetic and deliberately non-square (96 azimuth columns x
 * 48 range rows), with every sample encoding its own position. They establish
 * the orientation contract; they do not claim to reproduce ICEYE's schema, since
 * no real AML or CPX product was available to check against. See
 * {@code src/test/resources/eu/esa/sar/iogdal/iceye/amlcpx/}.
 *
 * @author Luis Veci
 */
public class TestIceyeAMLCPXOrientation extends ReaderTest {

    private static final String RESOURCE_DIR = "/eu/esa/sar/iogdal/iceye/amlcpx/";

    /** The fixture rasters hold 100 * column + row at every TIFF position. */
    private static final int AZIMUTH_LINES = 96;
    private static final int RANGE_SAMPLES = 48;

    public TestIceyeAMLCPXOrientation() {
        super(new IceyeCOGReaderPlugIn());
    }

    @After
    public void tearDown() throws IOException {
        close();
    }

    private static File fixture(final String name) {
        final URL url = TestIceyeAMLCPXOrientation.class.getResource(RESOURCE_DIR + name);
        assertNotNull("missing fixture " + RESOURCE_DIR + name, url);
        try {
            return Paths.get(url.toURI()).toFile();
        } catch (Exception e) {
            throw new IllegalStateException("cannot resolve " + url, e);
        }
    }

    private static double sample(final Band band, final int x, final int y) throws IOException {
        final double[] value = new double[1];
        band.readPixels(x, y, 1, 1, value);
        return value[0];
    }

    /**
     * The dimensions were always declared correctly; it was the raster behind
     * them that was not turned to match.
     */
    @Test
    public void testAmlRasterIsRangeByAzimuth() throws Exception {
        final Product product = testReader(fixture("ICEYE_SYNTHETIC_LOOKLEFT_AML.tif").toPath());

        assertEquals(RANGE_SAMPLES, product.getSceneRasterWidth());
        assertEquals(AZIMUTH_LINES, product.getSceneRasterHeight());

        final Band amplitude = product.getBand("Amplitude_VV");
        assertNotNull(amplitude);
        assertEquals(RANGE_SAMPLES, amplitude.getRasterWidth());
        assertEquals(AZIMUTH_LINES, amplitude.getRasterHeight());
    }

    /** Left-looking: SNAP(x, y) == TIFF(column = azimuthLines - 1 - y, row = x). */
    @Test
    public void testAmlSamplesLookingLeft() throws Exception {
        final Product product = testReader(fixture("ICEYE_SYNTHETIC_LOOKLEFT_AML.tif").toPath());
        final Band amplitude = product.getBand("Amplitude_VV");

        for (int y : new int[]{0, 1, 37, AZIMUTH_LINES - 1}) {
            for (int x : new int[]{0, 1, 23, RANGE_SAMPLES - 1}) {
                assertEquals("at x=" + x + " y=" + y,
                        100 * (AZIMUTH_LINES - 1 - y) + x, sample(amplitude, x, y), 0.0);
            }
        }
    }

    /** Right-looking flips azimuth: SNAP(x, y) == TIFF(column = y, row = x). */
    @Test
    public void testAmlSamplesLookingRight() throws Exception {
        final Product product = testReader(fixture("ICEYE_SYNTHETIC_LOOKRIGHT_AML.tif").toPath());
        final Band amplitude = product.getBand("Amplitude_VV");

        for (int y : new int[]{0, 1, 37, AZIMUTH_LINES - 1}) {
            for (int x : new int[]{0, 1, 23, RANGE_SAMPLES - 1}) {
                assertEquals("at x=" + x + " y=" + y,
                        100 * y + x, sample(amplitude, x, y), 0.0);
            }
        }
    }

    /** The phase band is a second GDAL band and has to be turned the same way. */
    @Test
    public void testCpxAmplitudeAndPhaseShareOneOrientation() throws Exception {
        final Product product = testReader(fixture("ICEYE_SYNTHETIC_LOOKLEFT_CPX.tif").toPath());

        assertEquals(RANGE_SAMPLES, product.getSceneRasterWidth());
        assertEquals(AZIMUTH_LINES, product.getSceneRasterHeight());

        final Band amplitude = product.getBand("Amplitude_VV");
        final Band phase = product.getBand("Phase_VV");
        assertNotNull(amplitude);
        assertNotNull(phase);

        for (int y : new int[]{0, 1, 37, AZIMUTH_LINES - 1}) {
            for (int x : new int[]{0, 1, 23, RANGE_SAMPLES - 1}) {
                final int column = AZIMUTH_LINES - 1 - y;
                assertEquals("amplitude at x=" + x + " y=" + y,
                        100.0 * column + x, sample(amplitude, x, y), 1e-6);
                assertEquals("phase at x=" + x + " y=" + y,
                        (column - x) / 100.0, sample(phase, x, y), 1e-6);
            }
        }
    }

    /**
     * The reported defect: an AML product opens with Amplitude_VV and
     * Intensity_VV, and the two views are turned against each other. Sample
     * values alone do not catch it - both bands answer the same coordinates -
     * what differed was the extent each one draws: the untransposed GDAL image
     * for the amplitude, the declared range-by-azimuth raster for the virtual
     * intensity built over it.
     */
    @Test
    public void testAmlIntensityIsDrawnLikeItsAmplitude() throws Exception {
        final Product product = testReader(fixture("ICEYE_SYNTHETIC_LOOKLEFT_AML.tif").toPath());

        final Band amplitude = product.getBand("Amplitude_VV");
        final Band intensity = product.getBand("Intensity_VV");
        assertNotNull(amplitude);
        assertNotNull(intensity);

        final RenderedImage amplitudeImage = amplitude.getSourceImage().getImage(0);
        final RenderedImage intensityImage = intensity.getSourceImage().getImage(0);

        assertEquals("amplitude image width", RANGE_SAMPLES, amplitudeImage.getWidth());
        assertEquals("amplitude image height", AZIMUTH_LINES, amplitudeImage.getHeight());
        assertEquals("intensity image width", RANGE_SAMPLES, intensityImage.getWidth());
        assertEquals("intensity image height", AZIMUTH_LINES, intensityImage.getHeight());

        for (int y : new int[]{0, 1, 37, AZIMUTH_LINES - 1}) {
            for (int x : new int[]{0, 1, 23, RANGE_SAMPLES - 1}) {
                final double amplitudeSample = sample(amplitude, x, y);
                assertEquals("at x=" + x + " y=" + y,
                        amplitudeSample * amplitudeSample, sample(intensity, x, y), 1.0);
            }
        }
    }

    /** i and q are expressions over the two real bands, so they inherit the rotation. */
    @Test
    public void testCpxVirtualBandsFollowTheRotation() throws Exception {
        final Product product = testReader(fixture("ICEYE_SYNTHETIC_LOOKLEFT_CPX.tif").toPath());

        for (int[] p : new int[][]{{0, 0}, {1, 0}, {23, 37}, {RANGE_SAMPLES - 1, AZIMUTH_LINES - 1}}) {
            final double amplitude = sample(product.getBand("Amplitude_VV"), p[0], p[1]);
            final double phase = sample(product.getBand("Phase_VV"), p[0], p[1]);
            assertEquals(amplitude * Math.cos(phase), sample(product.getBand("i_VV"), p[0], p[1]), 1e-2);
            assertEquals(amplitude * Math.sin(phase), sample(product.getBand("q_VV"), p[0], p[1]), 1e-2);
        }
    }

    /**
     * The geocoding already followed the look side; this checks the raster now
     * agrees with it rather than being rotated the other way underneath.
     */
    @Test
    public void testGeoCodingAgreesWithTheRaster() throws Exception {
        assertCornerLongitude("ICEYE_SYNTHETIC_LOOKLEFT_AML.tif", AZIMUTH_LINES);
        assertCornerLongitude("ICEYE_SYNTHETIC_LOOKRIGHT_AML.tif", 0);
    }

    private void assertCornerLongitude(final String fixture, final int firstLineColumn) throws Exception {
        final Product product = testReader(fixture(fixture).toPath());
        assertNotNull(product.getSceneGeoCoding());

        // the fixture's ModelTransformation is lon = 1e-4*column + 2e-4*row - 77
        final double expected = 1.0e-4 * firstLineColumn - 77.0;
        final GeoPos geoPos = product.getSceneGeoCoding().getGeoPos(new PixelPos(0.5, 0.5), null);
        assertEquals(fixture, expected, geoPos.getLon(), 2.0e-4);
    }
}
