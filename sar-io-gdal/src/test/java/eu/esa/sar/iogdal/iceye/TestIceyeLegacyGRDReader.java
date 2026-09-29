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

import eu.esa.sar.commons.product.Missions;
import eu.esa.sar.commons.test.ProductValidator;
import eu.esa.sar.commons.test.ReaderTest;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.TiePointGrid;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * The legacy ICEYE GRD delivery: a plain GeoTIFF whose metadata sits in
 * individual GDAL items rather than an embedded JSON document.
 * <p>
 * This is the third ICEYE GeoTIFF flavour {@link IceyeCOGReader} dispatches to,
 * and the one with no embedded JSON at all - it is reached by elimination, when
 * neither {@code ICEYE_PROPERTIES} nor {@code METADATA_JSON} is present.
 * <p>
 * Unlike the AML/CPX and Open Data deliveries, this one is <em>not</em> stored
 * transposed: {@code NUMBER_OF_RANGE_SAMPLES} matches the TIFF's ImageWidth and
 * {@code COORD_FIRST_FAR} sits at the last sample of the first line, so range
 * already runs along x. {@link #testRasterIsNotTransposed()} pins that, so the
 * transpose applied to the other two flavours is never applied here by mistake.
 *
 * @author Luis Veci
 */
public class TestIceyeLegacyGRDReader extends ReaderTest {

    private static final File inputGRD = new File(TestData.inputSAR
            + "Iceye/GRD/ICEYE_GRD_SM_7247_20190801T043405.tif");
    private static final File inputXML = new File(TestData.inputSAR
            + "Iceye/GRD/ICEYE_GRD_SM_7247_20190801T043405.xml");

    private static final int RANGE_SAMPLES = 11903;
    private static final int AZIMUTH_LINES = 21598;

    public TestIceyeLegacyGRDReader() {
        super(new IceyeCOGReaderPlugIn());
    }

    @Before
    public void setUp() {
        assumeTrue(inputGRD + " not found", inputGRD.exists());
    }

    @After
    public void tearDown() throws IOException {
        close();
    }

    private static double sample(final Band band, final int x, final int y) throws IOException {
        final double[] value = new double[1];
        band.readPixels(x, y, 1, 1, value);
        return value[0];
    }

    @Test
    public void testOpen() throws Exception {
        final Product product = testReader(inputGRD.toPath());

        assertEquals(RANGE_SAMPLES, product.getSceneRasterWidth());
        assertEquals(AZIMUTH_LINES, product.getSceneRasterHeight());

        final ProductValidator validator = new ProductValidator(product);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[]{"Amplitude_VV", "Intensity_VV"});
        validator.validateBandData();
    }

    /** The side-car .xml is what the SNAP open dialog offers beside the .tif. */
    @Test
    public void testOpenViaSidecarXml() throws Exception {
        assumeTrue(inputXML + " not found", inputXML.exists());

        final Product fromXml = testReader(inputXML.toPath());
        assertEquals(RANGE_SAMPLES, fromXml.getSceneRasterWidth());
        assertEquals(AZIMUTH_LINES, fromXml.getSceneRasterHeight());
        assertNotNull(fromXml.getBand("Amplitude_VV"));
    }

    /**
     * Range already runs along x in this delivery, so the samples must come
     * through at the same position the TIFF holds them. The expected values were
     * read straight out of the strips, independently of SNAP.
     */
    @Test
    public void testRasterIsNotTransposed() throws Exception {
        final Product product = testReader(inputGRD.toPath());
        final Band amplitude = product.getBand("Amplitude_VV");

        assertEquals(72, sample(amplitude, 0, 0), 0.0);
        assertEquals(90, sample(amplitude, 1, 0), 0.0);
        assertEquals(131, sample(amplitude, 0, 1), 0.0);
        assertEquals(174, sample(amplitude, 100, 200), 0.0);
        assertEquals(114, sample(amplitude, 5000, 10000), 0.0);
        assertEquals(291, sample(amplitude, RANGE_SAMPLES - 1, AZIMUTH_LINES - 1), 0.0);
    }

    @Test
    public void testBands() throws Exception {
        final Product product = testReader(inputGRD.toPath());

        final Band amplitude = product.getBand("Amplitude_VV");
        assertNotNull(amplitude);
        assertEquals(Unit.AMPLITUDE, amplitude.getUnit());
        assertEquals(RANGE_SAMPLES, amplitude.getRasterWidth());
        assertEquals(AZIMUTH_LINES, amplitude.getRasterHeight());

        final Band intensity = product.getBand("Intensity_VV");
        assertNotNull(intensity);
        assertEquals(Unit.INTENSITY, intensity.getUnit());
    }

    @Test
    public void testAbstractedMetadata() throws Exception {
        final Product product = testReader(inputGRD.toPath());
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);

        assertEquals(Missions.ICEYE, absRoot.getAttributeString(AbstractMetadata.MISSION));
        assertEquals("GRD", absRoot.getAttributeString(AbstractMetadata.SPH_DESCRIPTOR));
        assertEquals("stripmap", absRoot.getAttributeString(AbstractMetadata.ACQUISITION_MODE));
        assertEquals("right", absRoot.getAttributeString(AbstractMetadata.antenna_pointing));
        assertEquals("DESCENDING", absRoot.getAttributeString(AbstractMetadata.PASS));
        assertEquals("VV", absRoot.getAttributeString(AbstractMetadata.mds1_tx_rx_polar));

        assertEquals(RANGE_SAMPLES, absRoot.getAttributeInt(AbstractMetadata.num_samples_per_line));
        assertEquals(AZIMUTH_LINES, absRoot.getAttributeInt(AbstractMetadata.num_output_lines));
        assertEquals(3.0, absRoot.getAttributeDouble(AbstractMetadata.range_spacing), 1e-9);
        assertEquals(3.0, absRoot.getAttributeDouble(AbstractMetadata.azimuth_spacing), 1e-9);
        assertEquals(29.03208798034085, absRoot.getAttributeDouble(AbstractMetadata.incidence_near), 1e-9);
        assertEquals(31.98806690515206, absRoot.getAttributeDouble(AbstractMetadata.incidence_far), 1e-9);
        assertEquals(2.7184652383663924e-06,
                absRoot.getAttributeDouble(AbstractMetadata.calibration_factor), 1e-18);

        assertEquals(1, absRoot.getAttributeInt(AbstractMetadata.srgr_flag));
        assertTrue(AbstractMetadata.getOrbitStateVectors(absRoot).length > 0);
    }

    /** Corners come from the COORD_* items, which carry their own sample/line indices. */
    @Test
    public void testGeoCoding() throws Exception {
        final Product product = testReader(inputGRD.toPath());
        assertNotNull(product.getSceneGeoCoding());

        assertGeoPos(product, 0, 0, 56.337173057664415, 93.41705806765316);
        assertGeoPos(product, RANGE_SAMPLES - 1, 0, 56.40306932171218, 92.85116911989874);
        assertGeoPos(product, 0, AZIMUTH_LINES - 1, 55.771395884541704, 93.19548886388792);
        assertGeoPos(product, RANGE_SAMPLES - 1, AZIMUTH_LINES - 1, 55.83674114729113, 92.63827132806995);
    }

    @Test
    public void testIncidenceAngleRunsNearToFar() throws Exception {
        final Product product = testReader(inputGRD.toPath());

        final TiePointGrid incidence = product.getTiePointGrid("incident_angle");
        assertNotNull(incidence);
        assertEquals(29.032088, incidence.getPixelDouble(0, 0), 5e-3);
        assertTrue(incidence.getPixelDouble(RANGE_SAMPLES - 1, 0) > incidence.getPixelDouble(0, 0));

        assertNotNull(product.getTiePointGrid("slant_range_time"));
    }

    private static void assertGeoPos(final Product product, final int x, final int y,
                                     final double expectedLat, final double expectedLon) {
        final GeoPos geoPos = product.getSceneGeoCoding().getGeoPos(new PixelPos(x + 0.5, y + 0.5), null);
        assertEquals("lat at x=" + x + " y=" + y, expectedLat, geoPos.getLat(), 1e-3);
        assertEquals("lon at x=" + x + " y=" + y, expectedLon, geoPos.getLon(), 1e-3);
    }
}
