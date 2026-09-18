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

import com.bc.ceres.multilevel.MultiLevelImage;
import eu.esa.sar.commons.product.Missions;
import eu.esa.sar.commons.test.MetadataValidator;
import eu.esa.sar.commons.test.ProductValidator;
import eu.esa.sar.commons.test.ReaderTest;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.awt.image.RenderedImage;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Reads the full-size ICEYE Open Data products from the shared test tree.
 * <p>
 * These are the only tests that exercise the BigTIFF path (the 15.8 GB SLC) and
 * the real 1.8-gigapixel raster, so they are skipped rather than failed when
 * the products have not been synced. The committed windows in
 * {@link TestIceyeOpenDataCOGReader} cover everything else.
 * <p>
 * Note what is deliberately not asserted: overviews on the full SLC. SNAP's own
 * GDAL multi-level source cannot build any pyramid level above 0 for a raster
 * 114636 samples wide - it exhausts the heap in
 * {@code AbstractMosaicSubsetMultiLevelSource.buildMosaicOp} - and that is true
 * with or without this reader's transpose. The GRD covers the transpose across
 * a real, working pyramid instead.
 *
 * @author Luis Veci
 */
public class TestIceyeOpenDataFullProduct extends ReaderTest {

    private static final int GRD_RANGE_SAMPLES = 20000;
    private static final int GRD_AZIMUTH_LINES = 20000;
    private static final int SLC_RANGE_SAMPLES = 15828;
    private static final int SLC_AZIMUTH_LINES = 114636;

    public TestIceyeOpenDataFullProduct() {
        super(new IceyeCOGReaderPlugIn());
    }

    @Before
    public void setUp() {
        assumeTrue(IceyeOpenDataFixtures.fullGrdCog() + " not found",
                IceyeOpenDataFixtures.fullGrdCog().exists());
    }

    @After
    public void tearDown() throws IOException {
        close();
    }

    @Test
    public void testFullGrd() throws Exception {
        final Product product = testReader(IceyeOpenDataFixtures.fullGrdCog().toPath());

        assertEquals("GRD-COG", product.getProductType());
        assertEquals(GRD_RANGE_SAMPLES, product.getSceneRasterWidth());
        assertEquals(GRD_AZIMUTH_LINES, product.getSceneRasterHeight());

        final ProductValidator validator = new ProductValidator(product);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[]{"Amplitude_VV", "Intensity_VV"});
        validator.validateBandData();
    }

    /** 114636 x 15828 complex samples in a BigTIFF - the case that motivated the reader. */
    @Test
    public void testFullSlc() throws Exception {
        assumeTrue(IceyeOpenDataFixtures.fullSlcCog() + " not found",
                IceyeOpenDataFixtures.fullSlcCog().exists());

        final Product product = testReader(IceyeOpenDataFixtures.fullSlcCog().toPath());

        assertEquals("SLC-COG", product.getProductType());
        assertEquals(SLC_RANGE_SAMPLES, product.getSceneRasterWidth());
        assertEquals(SLC_AZIMUTH_LINES, product.getSceneRasterHeight());

        final MetadataValidator.Options options = new MetadataValidator.Options();
        options.validateSRGR = false;

        final ProductValidator validator = new ProductValidator(product);
        validator.validateProduct();
        validator.validateMetadata(options);
        validator.validateBands(new String[]{"Amplitude_VV", "Phase_VV", "i_VV", "q_VV", "Intensity_VV"});
        validator.validateBandData();
    }

    /**
     * Reads scattered windows rather than one block, so a wrong tile index or a
     * wrong transpose shows up as an out-of-bounds read instead of passing
     * quietly on the top-left corner.
     */
    @Test
    public void testScatteredReadsAcrossTheFullSlc() throws Exception {
        assumeTrue(IceyeOpenDataFixtures.fullSlcCog() + " not found",
                IceyeOpenDataFixtures.fullSlcCog().exists());

        final Product product = testReader(IceyeOpenDataFixtures.fullSlcCog().toPath());
        final Band amplitude = product.getBand("Amplitude_VV");

        final int[][] windows = {
                {0, 0},
                {SLC_RANGE_SAMPLES - 16, 0},
                {0, SLC_AZIMUTH_LINES - 16},
                {SLC_RANGE_SAMPLES - 16, SLC_AZIMUTH_LINES - 16},
                {SLC_RANGE_SAMPLES / 2, SLC_AZIMUTH_LINES / 2},
        };
        final float[] samples = new float[16 * 16];
        for (int[] window : windows) {
            amplitude.readPixels(window[0], window[1], 16, 16, samples);
            boolean finite = false;
            for (float sample : samples) {
                assertTrue("NaN at " + window[0] + "," + window[1], !Float.isNaN(sample));
                assertTrue("negative amplitude at " + window[0] + "," + window[1], sample >= 0);
                finite |= sample > 0;
            }
            assertTrue("window at " + window[0] + "," + window[1] + " is entirely zero", finite);
        }
    }

    /**
     * Without the COG overviews the image view has to decode a 400-megapixel
     * frame to draw a thumbnail, so the transpose must not flatten the pyramid.
     */
    @Test
    public void testOverviewsSurviveTheTranspose() throws Exception {
        final Product product = testReader(IceyeOpenDataFixtures.fullGrdCog().toPath());
        final MultiLevelImage image = product.getBand("Amplitude_VV").getSourceImage();

        final int levelCount = image.getModel().getLevelCount();
        assertTrue("expected a pyramid, got " + levelCount + " level(s)", levelCount > 1);
        assertEquals(levelCount, product.getNumResolutionsMax());

        for (int level = 0; level < levelCount; ++level) {
            final RenderedImage levelImage = image.getImage(level);
            assertEquals("level " + level, GRD_RANGE_SAMPLES >> level, levelImage.getWidth());
            assertEquals("level " + level, GRD_AZIMUTH_LINES >> level, levelImage.getHeight());
        }
    }

    /**
     * ICEYE builds these COGs with overviews that decimate by 4, 8 and 16 - there
     * is no 2x level. SNAP sizes pyramid level n as {@code size >> n}, so a reader
     * that takes overview {@code n-1} for level n fills each level from an
     * overview half its size and leaves the rest as no-data; on screen the scene
     * collapses into one corner as soon as the view leaves level 0.
     * <p>
     * Only the far corner of each level is read, which is the first thing to
     * disappear and costs a couple of tiles rather than a whole level.
     */
    @Test
    public void testEveryPyramidLevelIsFilledToItsFarCorner() throws Exception {
        final Product product = testReader(IceyeOpenDataFixtures.fullGrdCog().toPath());
        final MultiLevelImage image = product.getBand("Amplitude_VV").getSourceImage();

        for (int level = 0; level < image.getModel().getLevelCount(); ++level) {
            final RenderedImage levelImage = image.getImage(level);
            final int x = levelImage.getWidth() - 1;
            final int y = levelImage.getHeight() - 1;
            final double corner = levelImage.getData(new java.awt.Rectangle(x, y, 1, 1))
                    .getSampleDouble(x, y, 0);
            assertTrue("level " + level + " (" + levelImage.getWidth() + "x" + levelImage.getHeight()
                            + ") is empty at its far corner (" + x + "," + y + ")",
                    corner != 0);
        }
    }

    @Test
    public void testFullProductMetadata() throws Exception {
        final Product product = testReader(IceyeOpenDataFixtures.fullGrdCog().toPath());
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);

        assertEquals(Missions.ICEYE, absRoot.getAttributeString(AbstractMetadata.MISSION));
        assertEquals("spotlight", absRoot.getAttributeString(AbstractMetadata.ACQUISITION_MODE));
        assertEquals("DESCENDING", absRoot.getAttributeString(AbstractMetadata.PASS));
        assertEquals(GRD_RANGE_SAMPLES, absRoot.getAttributeInt(AbstractMetadata.num_samples_per_line));
        assertEquals(GRD_AZIMUTH_LINES, absRoot.getAttributeInt(AbstractMetadata.num_output_lines));
        assertEquals(50, AbstractMetadata.getOrbitStateVectors(absRoot).length);

        assertEquals("10-NOV-2025 18:21:11.159320",
                absRoot.getAttributeUTC(AbstractMetadata.first_line_time).format());
        assertEquals("10-NOV-2025 18:21:11.874747",
                absRoot.getAttributeUTC(AbstractMetadata.last_line_time).format());

        assertNotNull(product.getStartTime());
        assertNotNull(product.getEndTime());
        assertTrue(product.getStartTime().getMJD() <= product.getEndTime().getMJD());
    }

    /**
     * The far-range incidence angle only comes out right if the tie point grid
     * spans the range axis, which is the axis the delivery transposes.
     */
    @Test
    public void testIncidenceAngleSpansTheFullRangeSwath() throws Exception {
        final Product product = testReader(IceyeOpenDataFixtures.fullGrdCog().toPath());
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);

        final org.esa.snap.core.datamodel.TiePointGrid incidence =
                product.getTiePointGrid("incident_angle");
        assertNotNull(incidence);
        assertEquals(absRoot.getAttributeDouble(AbstractMetadata.incidence_near),
                incidence.getPixelDouble(0, 0), 5e-3);
        assertEquals(absRoot.getAttributeDouble(AbstractMetadata.incidence_far),
                incidence.getPixelDouble(GRD_RANGE_SAMPLES - 1, 0), 5e-3);
    }
}
