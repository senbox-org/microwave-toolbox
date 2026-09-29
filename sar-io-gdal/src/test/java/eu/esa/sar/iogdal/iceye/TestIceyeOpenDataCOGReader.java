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
import eu.esa.sar.commons.test.MetadataValidator;
import eu.esa.sar.commons.test.ProductValidator;
import eu.esa.sar.commons.test.ReaderTest;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Reads the committed ICEYE Open Data COG fixtures end to end.
 * <p>
 * The expected samples were taken independently of SNAP (with {@code tifffile})
 * from the same windows, then mapped through the documented orientation rule
 * {@code SNAP(x, y) == TIFF(column = width - 1 - y, row = x)}.
 *
 * @author Luis Veci
 */
public class TestIceyeOpenDataCOGReader extends ReaderTest {

    private static final double SCALE = 1.25763964389257654;

    public TestIceyeOpenDataCOGReader() {
        super(new IceyeCOGReaderPlugIn());
    }

    @After
    public void tearDown() throws IOException {
        close();
    }

    @Test
    public void testOpenGrdCog() throws Exception {
        final Product product = testReader(IceyeOpenDataFixtures.grdCog().toPath());

        assertEquals("GRD-COG", product.getProductType());
        assertEquals(IceyeOpenDataFixtures.GRD_RANGE_SAMPLES, product.getSceneRasterWidth());
        assertEquals(IceyeOpenDataFixtures.GRD_AZIMUTH_LINES, product.getSceneRasterHeight());

        final ProductValidator validator = new ProductValidator(product);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[]{"Amplitude_VV", "Intensity_VV"});
        validator.validateBandData();
    }

    @Test
    public void testOpenSlcCog() throws Exception {
        final Product product = testReader(IceyeOpenDataFixtures.slcCog().toPath());

        assertEquals("SLC-COG", product.getProductType());
        assertEquals(IceyeOpenDataFixtures.SLC_RANGE_SAMPLES, product.getSceneRasterWidth());
        assertEquals(IceyeOpenDataFixtures.SLC_AZIMUTH_LINES, product.getSceneRasterHeight());

        final MetadataValidator.Options options = new MetadataValidator.Options();
        options.validateSRGR = false;

        final ProductValidator validator = new ProductValidator(product);
        validator.validateProduct();
        validator.validateMetadata(options);
        validator.validateBands(new String[]{"Amplitude_VV", "Phase_VV", "i_VV", "q_VV", "Intensity_VV"});
        validator.validateBandData();
    }

    /** The side-car STAC item is a legitimate way in - that is what SNAP's open dialog offers. */
    @Test
    public void testOpenViaStacItem() throws Exception {
        final Product fromJson = testReader(IceyeOpenDataFixtures.grdStacItem().toPath());
        final Product fromTif = testReader(IceyeOpenDataFixtures.grdCog().toPath());

        assertEquals(fromTif.getProductType(), fromJson.getProductType());
        assertEquals(fromTif.getSceneRasterWidth(), fromJson.getSceneRasterWidth());
        assertEquals(fromTif.getSceneRasterHeight(), fromJson.getSceneRasterHeight());
        assertArrayEquals(fromTif.getBandNames(), fromJson.getBandNames());
    }

    @Test
    public void testGrdBands() throws Exception {
        final Product product = testReader(IceyeOpenDataFixtures.grdCog().toPath());

        final Band amplitude = product.getBand("Amplitude_VV");
        assertNotNull(amplitude);
        assertEquals(Unit.AMPLITUDE, amplitude.getUnit());
        assertEquals(ProductData.TYPE_UINT16, amplitude.getDataType());
        assertEquals(IceyeOpenDataFixtures.GRD_RANGE_SAMPLES, amplitude.getRasterWidth());
        assertEquals(IceyeOpenDataFixtures.GRD_AZIMUTH_LINES, amplitude.getRasterHeight());

        // the delivery stores DN with a GDAL SCALE item that converts to amplitude
        assertEquals(SCALE, amplitude.getScalingFactor(), 1e-15);
        assertEquals(0.0, amplitude.getScalingOffset(), 1e-15);

        final Band intensity = product.getBand("Intensity_VV");
        assertNotNull(intensity);
        assertEquals(Unit.INTENSITY, intensity.getUnit());
    }

    @Test
    public void testGrdSamples() throws Exception {
        final Product product = testReader(IceyeOpenDataFixtures.grdCog().toPath());
        final Band amplitude = product.getBand("Amplitude_VV");

        assertRawSample(amplitude, 0, 0, 141);
        assertRawSample(amplitude, 1, 0, 145);
        assertRawSample(amplitude, 0, 1, 140);
        assertRawSample(amplitude, 100, 200, 79);
        assertRawSample(amplitude, 255, 512, 109);
        assertRawSample(amplitude, 511, 1023, 187);

        // geophysical value applies the GDAL scale
        assertEquals(141 * SCALE, sample(amplitude, 0, 0), 1e-9);
    }

    @Test
    public void testSlcSamples() throws Exception {
        final Product product = testReader(IceyeOpenDataFixtures.slcCog().toPath());
        final Band amplitude = product.getBand("Amplitude_VV");
        final Band phase = product.getBand("Phase_VV");

        assertEquals(Unit.AMPLITUDE, amplitude.getUnit());
        assertEquals(Unit.PHASE, phase.getUnit());
        assertEquals(ProductData.TYPE_FLOAT32, amplitude.getDataType());

        assertEquals(198.339783, sample(amplitude, 0, 0), 1e-4);
        assertEquals(-0.486617, sample(phase, 0, 0), 1e-6);
        assertEquals(188.157593, sample(amplitude, 1, 0), 1e-4);
        assertEquals(0.341691, sample(phase, 1, 0), 1e-6);
        assertEquals(573.434570, sample(amplitude, 0, 1), 1e-4);
        assertEquals(0.144619, sample(phase, 0, 1), 1e-6);
        assertEquals(147.800781, sample(amplitude, 100, 200), 1e-4);
        assertEquals(-2.208143, sample(phase, 100, 200), 1e-6);
        assertEquals(558.927490, sample(amplitude, 511, 511), 1e-4);
        assertEquals(0.163273, sample(phase, 511, 511), 1e-6);
    }

    /**
     * The COG declares a no-data of 0. That is fill for amplitude, but 0 is a
     * perfectly good phase, so inheriting it would punch holes in the phase band.
     */
    @Test
    public void testNoDataIsFillForAmplitudeAndOutOfRangeForPhase() throws Exception {
        final Product product = testReader(IceyeOpenDataFixtures.slcCog().toPath());

        final Band amplitude = product.getBand("Amplitude_VV");
        assertTrue(amplitude.isNoDataValueUsed());
        assertEquals(0.0, amplitude.getNoDataValue(), 0.0);

        final Band phase = product.getBand("Phase_VV");
        assertTrue(phase.isNoDataValueUsed());
        assertTrue("no-data " + phase.getNoDataValue() + " would mask valid phase",
                Math.abs(phase.getNoDataValue()) > Math.PI);
    }

    /**
     * A GRD opens with Amplitude_VV and its virtual Intensity_VV, and the two
     * have to be drawn over the same extent - the reported defect was an
     * intensity view turned against its amplitude, which only the image bounds
     * expose, since both bands answer the same coordinates.
     */
    @Test
    public void testGrdIntensityIsDrawnLikeItsAmplitude() throws Exception {
        final Product product = testReader(IceyeOpenDataFixtures.grdCog().toPath());

        final Band amplitude = product.getBand("Amplitude_VV");
        final Band intensity = product.getBand("Intensity_VV");

        final java.awt.image.RenderedImage amplitudeImage = amplitude.getSourceImage().getImage(0);
        final java.awt.image.RenderedImage intensityImage = intensity.getSourceImage().getImage(0);

        assertEquals(IceyeOpenDataFixtures.GRD_RANGE_SAMPLES, amplitudeImage.getWidth());
        assertEquals(IceyeOpenDataFixtures.GRD_AZIMUTH_LINES, amplitudeImage.getHeight());
        assertEquals(IceyeOpenDataFixtures.GRD_RANGE_SAMPLES, intensityImage.getWidth());
        assertEquals(IceyeOpenDataFixtures.GRD_AZIMUTH_LINES, intensityImage.getHeight());

        for (int[] p : new int[][]{{0, 0}, {1, 0}, {0, 1}, {100, 200}, {255, 700},
                {IceyeOpenDataFixtures.GRD_RANGE_SAMPLES - 1, IceyeOpenDataFixtures.GRD_AZIMUTH_LINES - 1}}) {
            final double amplitudeSample = sample(amplitude, p[0], p[1]);
            assertEquals("at x=" + p[0] + " y=" + p[1], amplitudeSample * amplitudeSample,
                    sample(intensity, p[0], p[1]), 1.0);
        }
    }

    /** i and q are derived from amplitude and phase, so they must close the loop. */
    @Test
    public void testSlcVirtualComplexBands() throws Exception {
        final Product product = testReader(IceyeOpenDataFixtures.slcCog().toPath());
        final Band i = product.getBand("i_VV");
        final Band q = product.getBand("q_VV");

        assertEquals(Unit.REAL, i.getUnit());
        assertEquals(Unit.IMAGINARY, q.getUnit());

        for (int[] p : new int[][]{{0, 0}, {1, 0}, {100, 200}, {511, 511}}) {
            final double amplitude = sample(product.getBand("Amplitude_VV"), p[0], p[1]);
            final double phase = sample(product.getBand("Phase_VV"), p[0], p[1]);
            assertEquals(amplitude * Math.cos(phase), sample(i, p[0], p[1]), 1e-3);
            assertEquals(amplitude * Math.sin(phase), sample(q, p[0], p[1]), 1e-3);
            assertEquals(amplitude * amplitude,
                    sample(product.getBand("Intensity_VV"), p[0], p[1]), 1.0);
        }
    }

    /**
     * The delivery has no ModelTransformation tag (34264) - it geocodes with
     * ModelTiepoint (33922) - and the geocoding has to follow the same
     * transpose as the raster.
     */
    @Test
    public void testGeoCoding() throws Exception {
        final Product product = testReader(IceyeOpenDataFixtures.grdCog().toPath());
        assertNotNull(product.getSceneGeoCoding());

        final IceyeOpenDataMetadata metadata =
                IceyeOpenDataMetadata.fromStacItem(IceyeOpenDataFixtures.grdStacItem());
        final double[] t = metadata.getGeoTransform();
        final int lastColumn = metadata.getAzimuthLines() - 1;
        final int lastRow = metadata.getRangeSamples() - 1;

        // SNAP (0, 0) is the last TIFF column, first TIFF row
        assertGeoPos(product, 0, 0, t[3] + t[4] * lastColumn, t[0] + t[1] * lastColumn);
        // SNAP (width-1, 0) is the last TIFF column, last TIFF row
        assertGeoPos(product, lastRow, 0,
                t[3] + t[4] * lastColumn + t[5] * lastRow,
                t[0] + t[1] * lastColumn + t[2] * lastRow);
        // SNAP (0, height-1) is the first TIFF column, first TIFF row
        assertGeoPos(product, 0, lastColumn, t[3], t[0]);
    }

    @Test
    public void testAbstractedMetadata() throws Exception {
        final Product product = testReader(IceyeOpenDataFixtures.grdCog().toPath());
        final org.esa.snap.core.datamodel.MetadataElement absRoot =
                AbstractMetadata.getAbstractedMetadata(product);

        assertEquals(Missions.ICEYE, absRoot.getAttributeString(AbstractMetadata.MISSION));
        assertEquals("GRD-COG", absRoot.getAttributeString(AbstractMetadata.PRODUCT_TYPE));
        assertEquals("VV", absRoot.getAttributeString(AbstractMetadata.mds1_tx_rx_polar));
        assertEquals(IceyeOpenDataFixtures.GRD_RANGE_SAMPLES,
                absRoot.getAttributeInt(AbstractMetadata.num_samples_per_line));
        assertEquals(IceyeOpenDataFixtures.GRD_AZIMUTH_LINES,
                absRoot.getAttributeInt(AbstractMetadata.num_output_lines));
        assertEquals(1, absRoot.getAttributeInt(AbstractMetadata.srgr_flag));
        assertEquals(50, AbstractMetadata.getOrbitStateVectors(absRoot).length);

        // the whole STAC item is kept verbatim under Original_Product_Metadata
        assertNotNull(AbstractMetadata.getOriginalProductMetadata(product)
                .getElement(IceyeOpenDataConstants.PRODUCT_METADATA));
    }

    @Test
    public void testTiePointGrids() throws Exception {
        final Product product = testReader(IceyeOpenDataFixtures.grdCog().toPath());

        final org.esa.snap.core.datamodel.TiePointGrid incidence =
                product.getTiePointGrid("incident_angle");
        assertNotNull(incidence);
        assertEquals(Unit.DEGREES, incidence.getUnit());
        // near range at x = 0, increasing towards far range
        assertEquals(28.822174, incidence.getPixelDouble(0, 0), 1e-3);
        assertTrue(incidence.getPixelDouble(product.getSceneRasterWidth() - 1, 0)
                > incidence.getPixelDouble(0, 0));

        assertNotNull(product.getTiePointGrid("slant_range_time"));
    }

    /**
     * Opening a STAC item whose COG is missing must fail cleanly rather than
     * hand back a half-built product - the original defect showed up as a
     * silent null.
     */
    @Test
    public void testMissingCogIsReportedNotSwallowed() throws Exception {
        final java.io.File orphan = java.io.File.createTempFile("ICEYE_orphan_", "_GRD.json");
        orphan.deleteOnExit();
        java.nio.file.Files.copy(IceyeOpenDataFixtures.grdStacItem().toPath(), orphan.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        try {
            new IceyeCOGReaderPlugIn().createReaderInstance().readProductNodes(orphan, null);
            org.junit.Assert.fail("expected an IOException naming the missing COG");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(".tif"));
        }
    }

    @Test
    public void testReReadingIsStable() throws Exception {
        final Product first = testReader(IceyeOpenDataFixtures.slcCog().toPath());
        final Product second = testReader(IceyeOpenDataFixtures.slcCog().toPath());

        assertEquals(sample(first.getBand("Amplitude_VV"), 100, 200),
                sample(second.getBand("Amplitude_VV"), 100, 200), 0.0);
    }

    @Test
    public void testNonIceyeFileIsNotClaimed() {
        assertEquals(org.esa.snap.core.dataio.DecodeQualification.UNABLE,
                new IceyeCOGReaderPlugIn().getDecodeQualification(new java.io.File("SomethingElse_GRD.tif")));
    }

    /** Bands are backed by a source image, so their raster data is not loaded up front. */
    private static double sample(final Band band, final int x, final int y) throws IOException {
        final double[] value = new double[1];
        band.readPixels(x, y, 1, 1, value);
        return value[0];
    }

    private static void assertRawSample(final Band band, final int x, final int y, final int expected) {
        final int actual = band.getSourceImage().getData(
                new java.awt.Rectangle(x, y, 1, 1)).getSample(x, y, 0);
        assertEquals("raw DN at x=" + x + " y=" + y, expected, actual);
    }

    private static void assertGeoPos(final Product product, final int x, final int y,
                                     final double expectedLat, final double expectedLon) {
        final GeoPos geoPos = product.getSceneGeoCoding().getGeoPos(new PixelPos(x + 0.5, y + 0.5), null);
        assertEquals("lat at x=" + x + " y=" + y, expectedLat, geoPos.getLat(), 1e-5);
        assertEquals("lon at x=" + x + " y=" + y, expectedLon, geoPos.getLon(), 1e-5);
    }
}
