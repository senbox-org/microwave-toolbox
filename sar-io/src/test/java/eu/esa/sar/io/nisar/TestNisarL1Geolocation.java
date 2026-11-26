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
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.GeoCoding;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.TiePointGrid;
import org.esa.snap.core.gpf.common.SubsetOp;
import org.esa.snap.core.util.io.FileUtils;
import org.junit.After;
import org.junit.Test;

import java.awt.Rectangle;
import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Geolocation of NISAR L1 (radar geometry) products from the metadata geolocation grid.
 * <p>
 * The grid is a (height, zeroDopplerTime, slantRange) cube that covers more than the image. The
 * reference positions are bilinear interpolations, with h5py, of its 0 m (ellipsoid) layer at the
 * pixel's own zero-Doppler time and slant range. Skipped when the products are not in the test data folder.
 */
public class TestNisarL1Geolocation {

    private static final File NISAR_DIR = new File(TestData.inputSAR + "NISAR");
    /** Current RSLC carrying frequency B only (VV, 25 m slant range); the grid starts 2.5 km before the image in range. */
    private static final File RSLC_B_ONLY = new File(NISAR_DIR,
            "NISAR_L1_PR_RSLC_036_079_D_076_0005_NASV_A_20261122T163605_20261122T163610_P05036_N_P_J_001.h5");
    /** Simulated 2008 RSLC, frequency A only. */
    private static final File RSLC_SIM = new File(NISAR_DIR,
            "NISAR_L1_PR_RSLC_001_005_A_219_2005_DHDH_A_20081012T060910_20081012T060926_P01101_F_N_J_001.h5");

    /** 1e-5 degrees is about a metre. */
    private static final double TOLERANCE_DEG = 1e-5;

    private Product product;

    @After
    public void tearDown() {
        if (product != null) {
            product.dispose();
        }
    }

    private GeoCoding open(final File file, final String band) throws Exception {
        assumeTrue(file + " not found", file.exists());
        product = new NisarProductReaderPlugIn().createReaderInstance().readProductNodes(file, null);
        assertNotNull(product);
        assertNotNull("missing band " + band, product.getBand(band));
        final GeoCoding gc = product.getBand(band).getGeoCoding();
        assertNotNull("no geocoding on " + band, gc);
        return gc;
    }

    private static void assertGeoPos(final GeoCoding gc, final int row, final int col, final double lat, final double lon) {
        final GeoPos pos = gc.getGeoPos(new PixelPos(col + 0.5, row + 0.5), null);
        assertEquals("lat at row " + row + " col " + col, lat, pos.lat, TOLERANCE_DEG);
        assertEquals("lon at row " + row + " col " + col, lon, pos.lon, TOLERANCE_DEG);
    }

    @Test
    @STTM("SNAP-4384")
    public void frequencyBOnlyRslcIsGeolocatedFromTheGridAtTheEllipsoid() throws Exception {
        final GeoCoding gc = open(RSLC_B_ONLY, "i_VV_S");
        assertGeoPos(gc, 0, 0, 24.9780513, 29.7758554);
        assertGeoPos(gc, 4560, 2115, 24.5959696, 30.5694912);
        assertGeoPos(gc, 9119, 6344, 24.0519712, 31.9805699);
    }

    /** Current RSLC with both frequencies (HH, 6.2 m and 25 m slant range, same first range and azimuth lines). */
    private static final File RSLC_A_B = new File(NISAR_DIR,
            "NISAR_L1_PR_RSLC_036_083_D_080_2005_SHSH_A_20261122T231741_20261122T231746_P05036_N_P_J_001.h5");

    @Test
    @STTM("SNAP-4384")
    public void dualFrequencyRslcFrequencyAIsGeolocatedFromTheGrid() throws Exception {
        final GeoCoding gc = open(RSLC_A_B, "i_HH");
        assertSame(product.getSceneGeoCoding(), gc);
        assertGeoPos(gc, 0, 0, 17.6434660, -72.1501364);
        assertGeoPos(gc, 4560, 8648, 17.2678010, -71.3661904);
        assertGeoPos(gc, 9119, 25944, 16.7399252, -69.9786003);
    }

    @Test
    @STTM("SNAP-4384")
    public void dualFrequencyRslcFrequencyBIsGeolocatedOnItsOwnRangeAxis() throws Exception {
        final GeoCoding gc = open(RSLC_A_B, "i_HH_S");
        assertEquals(6487, product.getBand("i_HH_S").getRasterWidth());
        assertGeoPos(gc, 0, 0, 17.6434660, -72.1501364);
        assertGeoPos(gc, 4560, 2162, 17.2678010, -71.3661904);
        assertGeoPos(gc, 9119, 6486, 16.7399252, -69.9786003);
        for (String name : new String[]{"q_HH_S", "Intensity_HH_S"}) {
            assertSame(name, gc, product.getBand(name).getGeoCoding());
        }
    }

    /**
     * Calibration to beta0 and gamma0 reads the incidence angle at the band's own pixel, so frequency B
     * has its own grid, placed on its own range axis. References: the grid's 0 m layer at line 4560.
     */
    @Test
    @STTM("SNAP-4384")
    public void dualFrequencyRslcFrequencyBHasItsOwnIncidenceAngleGrid() throws Exception {
        open(RSLC_A_B, "i_HH_S");
        final TiePointGrid incA = product.getTiePointGrid("incident_angle");
        final TiePointGrid incB = product.getTiePointGrid("incident_angle_S");
        assertNotNull(incB);
        final double[] expected = {33.76779, 40.96878, 46.44647};
        final int[] colsB = {0, 3000, 6000}, colsA = {0, 12000, 24000};
        for (int i = 0; i < expected.length; i++) {
            assertEquals("B col " + colsB[i], expected[i], incB.getPixelDouble(colsB[i] + 0.5, 4560.5), 1e-3);
            assertEquals("A col " + colsA[i], expected[i], incA.getPixelDouble(colsA[i] + 0.5, 4560.5), 1e-3);
        }
    }

    /** Frequency A pixels of the subset, which the regionMap carries over to frequency B (about a quarter in range). */
    private static final Rectangle SUBSET_A = new Rectangle(8000, 3000, 4000, 2000);

    private static Product subset(final Product source, final String referenceBand, final Rectangle region,
                                  final String... bands) {
        final SubsetOp op = new SubsetOp();
        op.setSourceProduct(source);
        op.setParameter("referenceBand", referenceBand);
        op.setRegion(region);
        op.setBandNames(bands);
        op.setCopyMetadata(false);
        return op.getTargetProduct();
    }

    /**
     * The derived product's band is geolocated as the source band: the source pixel it came from (found
     * through the source geocoding's approximate inverse, then rounded) holds the same sample, and the two
     * geocodings give the same position for corresponding pixels across the raster.
     */
    private static void assertSameGeolocation(final Product source, final Product derived, final String band)
            throws Exception {
        final Band b = derived.getBand(band);
        assertNotNull("missing " + band, b);
        final GeoCoding gc = b.getGeoCoding(), sourceGc = source.getBand(band).getGeoCoding();
        assertNotNull(gc);
        final GeoPos first = gc.getGeoPos(new PixelPos(1.5, 1.5), null);
        final PixelPos back = sourceGc.getPixelPos(first, null);
        final int dx = (int) Math.rint(back.x - 1.5), dy = (int) Math.rint(back.y - 1.5);
        assertTrue("column offset " + dx, dx >= 0 && dx < source.getBand(band).getRasterWidth());
        assertTrue("row offset " + dy, dy >= 0 && dy < source.getBand(band).getRasterHeight());

        final float[] mine = new float[1], theirs = new float[1];
        b.readPixels(1, 1, 1, 1, mine);
        source.getBand(band).readPixels(1 + dx, 1 + dy, 1, 1, theirs);
        assertEquals("the sample at the pixel the geocoding points to", theirs[0], mine[0], 0f);

        for (int[] p : new int[][]{{1, 1}, {b.getRasterWidth() / 2, b.getRasterHeight() / 2},
                {b.getRasterWidth() - 2, b.getRasterHeight() - 2}}) {
            final GeoPos mineGeo = gc.getGeoPos(new PixelPos(p[0] + 0.5, p[1] + 0.5), null);
            final GeoPos theirsGeo = sourceGc.getGeoPos(new PixelPos(p[0] + dx + 0.5, p[1] + dy + 0.5), null);
            assertEquals(band + " lat at " + p[0] + "," + p[1], theirsGeo.lat, mineGeo.lat, 1e-6);
            assertEquals(band + " lon at " + p[0] + "," + p[1], theirsGeo.lon, mineGeo.lon, 1e-6);
        }
    }

    @Test
    @STTM("SNAP-4384")
    public void frequencyBGeolocationSurvivesSubset() throws Exception {
        open(RSLC_A_B, "i_HH_S");
        final Product sub = subset(product, "i_HH", SUBSET_A, "i_HH", "i_HH_S");
        try {
            assertSameGeolocation(product, sub, "i_HH_S");
            assertSameGeolocation(product, sub, "i_HH");
        } finally {
            sub.dispose();
        }
    }

    @Test
    @STTM("SNAP-4384")
    public void frequencyBGeolocationSurvivesSubsetAndDimapRoundTrip() throws Exception {
        open(RSLC_A_B, "i_HH_S");
        final Product sub = subset(product, "i_HH", SUBSET_A, "i_HH", "i_HH_S");
        final File dir = Files.createTempDirectory("nisarB").toFile();
        Product reread = null;
        try {
            final File file = new File(dir, "sub.dim");
            ProductIO.writeProduct(sub, file, "BEAM-DIMAP", false);
            reread = ProductIO.readProduct(file);
            assertSameGeolocation(product, reread, "i_HH_S");
            assertSameGeolocation(product, reread, "i_HH");
            // Not covered: a further multi-size subset of the re-opened product. BEAM-DIMAP does not
            // keep a tie-point grid's own geocoding, so SNAP cuts latitude_S with frequency A's region.
        } finally {
            if (reread != null) reread.dispose();
            sub.dispose();
            FileUtils.deleteTree(dir);
        }
    }

    @Test
    @STTM("SNAP-4384")
    public void simulatedRslcIsGeolocatedFromTheGridAtTheEllipsoid() throws Exception {
        final GeoCoding gc = open(RSLC_SIM, "i_HH");
        assertGeoPos(gc, 0, 0, 34.2400446, -118.2472204);
        assertGeoPos(gc, 15270, 2162, 34.7817296, -118.0992090);
    }
}
