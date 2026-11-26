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
package eu.esa.sar.io.nisar;

import com.bc.ceres.annotation.STTM;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.GeoCoding;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Product;
import org.junit.After;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Bands on a grid other than the scene's get the geocoding of their own grid: the 20 m wrapped
 * interferogram of a GUNW under its 80 m scene, and the coarse pixel offsets of RIFG and RUNW. Reference
 * values are from h5py (L1 positions: bilinear in the 0 m layer of the geolocation grid at the band's own
 * zero-Doppler time and slant range, converted from UTM with pyproj). Skipped without the test data.
 */
public class TestNisarBandGeocoding {

    private static final File NISAR_DIR = new File(TestData.inputSAR + "NISAR");
    private static final File GUNW = new File(NISAR_DIR,
            "NISAR_L2_PR_GUNW_035_064_D_097_036_4000_SH_20261109T154941_20261109T154944_20261121T154941_20261121T154944_P05036_N_P_J_001.h5");
    private static final File RIFG_SIM = new File(NISAR_DIR,
            "NISAR_L1_PR_RIFG_001_005_A_219_220_4020_SH_20081012T060910_20081012T060926_20081127T060959_20081127T061015_P01101_M_F_J_001.h5");
    private static final File RUNW_SIM = new File(NISAR_DIR,
            "NISAR_L1_PR_RUNW_001_005_A_219_220_4020_SH_20081012T060910_20081012T060926_20081127T060959_20081127T061015_P01101_M_F_J_001.h5");
    private static final File RIFG = new File(NISAR_DIR,
            "NISAR_L1_PR_RIFG_035_056_A_045_036_7700_SH_20261109T020117_20261109T020123_20261121T020117_20261121T020124_P05036_N_P_J_001.h5");

    /** 1e-5 degrees is about a metre. */
    private static final double TOLERANCE_DEG = 1e-5;

    private Product product;

    @After
    public void tearDown() {
        if (product != null) {
            product.dispose();
        }
    }

    private Product open(final File file) throws Exception {
        assumeTrue(file + " not found", file.exists());
        product = new NisarProductReaderPlugIn().createReaderInstance().readProductNodes(file, null);
        assertNotNull(product);
        return product;
    }

    private Band band(final String name) {
        final Band band = product.getBand(name);
        assertNotNull("missing band " + name, band);
        return band;
    }

    private static float sample(final Band band, final int x, final int y) throws Exception {
        final float[] v = new float[1];
        band.readPixels(x, y, 1, 1, v);
        return v[0];
    }

    private static void assertGeoPos(final Band band, final int row, final int col, final double lat, final double lon) {
        final GeoPos pos = band.getGeoCoding().getGeoPos(new PixelPos(col + 0.5, row + 0.5), null);
        assertEquals(band.getName() + " lat at row " + row + " col " + col, lat, pos.lat, TOLERANCE_DEG);
        assertEquals(band.getName() + " lon at row " + row + " col " + col, lon, pos.lon, TOLERANCE_DEG);
    }

    /**
     * The coordinates are pixel centres and the two grids share their outer edge: the first 20 m pixel
     * centre (30 m west and north of the first 80 m one) is an eighth of a pixel into the scene's first
     * pixel, and the last an eighth of a pixel inside the scene's far corner.
     */
    @Test
    @STTM("SNAP-4384")
    public void gunwWrappedInterferogramIsGeocodedOnIts20mGrid() throws Exception {
        open(GUNW);
        final Band wrapped = band("wrappedInterferogram_HH");
        assertEquals(16776, wrapped.getRasterWidth());
        final GeoCoding scene = product.getSceneGeoCoding();
        for (double[] p : new double[][]{{0, 0, 0.125, 0.125}, {16775, 16307, 4193.875, 4076.875}}) {
            final GeoPos geo = wrapped.getGeoCoding().getGeoPos(new PixelPos(p[0] + 0.5, p[1] + 0.5), null);
            final PixelPos inScene = scene.getPixelPos(geo, null);
            assertEquals("scene x of wrapped pixel " + p[0], p[2], inScene.x, 1e-3);
            assertEquals("scene y of wrapped pixel " + p[1], p[3], inScene.y, 1e-3);
        }
    }

    @Test
    @STTM("SNAP-4384")
    public void gunwWrappedInterferogramKeepsBothPartsAndItsPhase() throws Exception {
        open(GUNW);
        assertEquals(-0.117082745f, sample(band("i_wrappedInterferogram_HH"), 1412, 10500), 1e-7f);
        assertEquals(-0.011034134f, sample(band("q_wrappedInterferogram_HH"), 1412, 10500), 1e-7f);
        final Band phase = band("wrappedInterferogram_HH");
        assertEquals(Unit.PHASE, phase.getUnit());
        assertEquals(-3.047628f, sample(phase, 1412, 10500), 1e-5f);
        assertSame(phase.getGeoCoding(), band("i_wrappedInterferogram_HH").getGeoCoding());
        final Band coherence = band("wrappedCoherenceMagnitude_HH");
        assertEquals(0.57680947f, sample(coherence, 1412, 10500), 1e-6f);
        assertSame(phase.getGeoCoding(), coherence.getGeoCoding());
    }

    @Test
    @STTM("SNAP-4384")
    public void rifgPixelOffsetsAreGeolocatedOnTheirOwnAxes() throws Exception {
        open(RIFG_SIM);
        checkInterferogramAndOffsets();
    }

    @Test
    @STTM("SNAP-4384")
    public void runwPixelOffsetsAreGeolocatedOnTheirOwnAxes() throws Exception {
        open(RUNW_SIM);
        checkInterferogramAndOffsets();
    }

    private void checkInterferogramAndOffsets() {
        final Band coherence = band("coherenceMagnitude_HH");
        assertGeoPos(coherence, 0, 0, 34.2404364, -118.2469088);
        assertGeoPos(coherence, 723, 456, 34.7998588, -117.9753481);
        assertGeoPos(coherence, 1446, 911, 35.3513038, -117.7404784);
        for (String name : new String[]{"alongTrackOffset_HH", "slantRangeOffset_HH", "correlationSurfacePeak_HH"}) {
            final Band offsets = band(name);
            assertEquals(199, offsets.getRasterWidth());
            assertGeoPos(offsets, 0, 0, 34.2411427, -118.2447597);
            assertGeoPos(offsets, 474, 99, 34.7995597, -117.9761282);
            assertGeoPos(offsets, 948, 198, 35.3502341, -117.7429034);
        }
    }

    /** The DEM is beside the polarisation groups, not in them. */
    @Test
    @STTM("SNAP-4384")
    public void rifgHasItsDem() throws Exception {
        open(RIFG);
        final Band dem = band("digitalElevationModel");
        assertEquals(product.getSceneRasterWidth(), dem.getRasterWidth());
        assertEquals(Unit.METERS, dem.getUnit());
        assertEquals(2387.1162f, sample(dem, 2469, 886), 1e-3f);

        final Band coarse = band("digitalElevationModel_pixelOffsets");
        assertEquals(327, coarse.getRasterWidth());
        assertEquals(139, coarse.getRasterHeight());
        assertEquals(2383.5059f, sample(coarse, 160, 70), 1e-3f);
    }
}
