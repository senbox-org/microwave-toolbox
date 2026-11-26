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
import eu.esa.sar.commons.polsar.PolBandUtils;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.GeoCoding;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.Mask;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.TiePointGrid;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * GCOV reader tests on real NISAR products (SNAP-4384). Skipped when the products are not in
 * the test data folder. Reference values were read with h5py from the same files.
 */
public class TestNisarGCOVRealProducts {

    private static final File NISAR_DIR = new File(TestData.inputSAR + "NISAR");
    private static final File DHDH = new File(NISAR_DIR,
            "NISAR_L2_PR_GCOV_036_066_A_004_2005_DHDH_A_20261121T181649_20261121T181653_P05036_N_P_J_001.h5");

    // A valid (mask = 1) pixel of frequency A in the DHDH product
    private static final int ROW = 3392, COL = 10240;

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

    private static float pixel(final Product product, final String bandName, final int x, final int y) throws Exception {
        final Band band = product.getBand(bandName);
        assertNotNull("missing band " + bandName + " in " + Arrays.toString(product.getBandNames()), band);
        final float[] value = new float[1];
        band.readPixels(x, y, 1, 1, value);
        return value[0];
    }

    @Test
    @STTM("SNAP-4384")
    public void dualPolGcovOpens() throws Exception {
        final Product p = open(DHDH);
        assertEquals(16812, p.getSceneRasterWidth());
        assertEquals(16452, p.getSceneRasterHeight());
    }

    @Test
    @STTM("SNAP-4384")
    public void dualPolCovarianceTermsMatchTheFile() throws Exception {
        final Product p = open(DHDH);
        assertEquals(0.012961984f, pixel(p, "HHHH", COL, ROW), 1e-9f);
        assertEquals(0.001630351f, pixel(p, "HVHV", COL, ROW), 1e-9f);
    }

    @Test
    @STTM("SNAP-4384")
    public void currentAuxiliaryLayerNamesAreRead() throws Exception {
        final Product p = open(DHDH);
        assertEquals(1f, pixel(p, "mask", COL, ROW), 0f);
        assertEquals(8.881041f, pixel(p, "numberOfLooks", COL, ROW), 1e-5f);
        assertEquals(0.78756356f, pixel(p, "rtcGamma0ToSigma0Factor", COL, ROW), 1e-7f);
        assertEquals(768f, pixel(p, "inputDataExceptionMask", COL, ROW), 0f);
    }

    private static final File QPDH = new File(NISAR_DIR,
            "NISAR_L2_PR_GCOV_036_076_A_020_2005_QPDH_A_20261122T110437_20261122T110441_P05036_N_P_J_001.h5");
    /** Complex terms carry statistics per part; the display range of each band comes from its own part. */
    @Test
    @STTM("SNAP-4384")
    public void complexTermPartsTakeTheirOwnStatistics() throws Exception {
        final Product p = open(QPDH);
        assertEquals(-278.42554, p.getBand("HHHV_real").getStx().getMinimum(), 1e-3);
        assertEquals(-157.30556, p.getBand("HHHV_imag").getStx().getMinimum(), 1e-3);
        assertEquals(80.55556, p.getBand("HHHV_imag").getStx().getMaximum(), 1e-3);
    }

    // A valid pixel of frequency A in the QPDH product
    private static final int Q_ROW = 3072, Q_COL = 11200;

    @Test
    @STTM("SNAP-4384")
    public void quadPolComplex64TermsAreSplit() throws Exception {
        final Product p = open(QPDH);
        assertEquals(0.000380788f, pixel(p, "HHHV_real", Q_COL, Q_ROW), 1e-9f);
        assertEquals(0.003090918f, pixel(p, "HHHV_imag", Q_COL, Q_ROW), 1e-9f);
        assertEquals(0.0015612692f, pixel(p, "HVVH_real", Q_COL, Q_ROW), 1e-9f);
        assertEquals(-0.00073774904f, pixel(p, "HVVH_imag", Q_COL, Q_ROW), 1e-9f);
    }

    @Test
    @STTM("SNAP-4384")
    public void quadPolC3MatchesAnIndependentComputation() throws Exception {
        // reference: numpy on the HDF5 terms with S_X = (S_HV + S_VH) / 2
        final Product p = open(QPDH);
        assertEquals(PolBandUtils.MATRIX.C3, PolBandUtils.getSourceProductType(p));
        final double[][] expected = {
                {0.0369286537, 0}, {0.00452031536, 0.0049563008}, {0.0300474167, 0.00387480855},
                {0.00570678711, 0}, {0.00641919684, -0.00816418173}, {0.0448665619, 0}};
        final String[] names = {"C11", "C12", "C13", "C22", "C23", "C33"};
        for (int k = 0; k < names.length; k++) {
            final boolean diagonal = names[k].charAt(1) == names[k].charAt(2);
            final String re = diagonal ? names[k] : names[k] + "_real";
            assertEquals(re, expected[k][0], pixel(p, re, Q_COL, Q_ROW), 1e-6 * Math.abs(expected[k][0]) + 1e-10);
            if (!diagonal) {
                final String im = names[k] + "_imag";
                assertEquals(im, expected[k][1], pixel(p, im, Q_COL, Q_ROW), 1e-6 * Math.abs(expected[k][1]) + 1e-10);
            }
        }
        assertNull("matrix bands for frequency A only", p.getBand("C11_S"));
    }

    @Test
    @STTM("SNAP-4384")
    public void diagonalOnlyDualPolGetsNoMatrixBands() throws Exception {
        // this DHDH product carries HHHH and HVHV only, no HHHV
        final Product p = open(DHDH);
        assertNull(p.getBand("C11"));
        assertEquals(PolBandUtils.MATRIX.UNKNOWN, PolBandUtils.getSourceProductType(p));
        assertEquals(0, AbstractMetadata.getAbstractedMetadata(p).getAttributeInt(AbstractMetadata.polsarData));
        assertEquals("DETECTED", AbstractMetadata.getAbstractedMetadata(p).getAttributeString(AbstractMetadata.SAMPLE_TYPE));
    }

    private static int sample(final Product product, final String maskName, final int x, final int y) {
        final Mask mask = product.getMaskGroup().get(maskName);
        assertNotNull("missing mask " + maskName, mask);
        return mask.getSourceImage().getData(new java.awt.Rectangle(x, y, 1, 1)).getSample(x, y, 0);
    }

    @Test
    @STTM("SNAP-4384")
    public void gcovValidMaskFollowsTheMaskLayer() throws Exception {
        final Product p = open(DHDH);
        assertNotEquals("mask 1", 0, sample(p, "gcov_valid", COL, ROW));
        // mask 0: partially focused, but the terms hold data
        assertEquals(0f, pixel(p, "mask", 11671, 3401), 0f);
        assertEquals(0.034148216f, pixel(p, "HHHH", 11671, 3401), 1e-9f);
        assertEquals(0, sample(p, "gcov_valid", 11671, 3401));
        assertEquals("outside the swath", 0, sample(p, "gcov_valid", 8406, 8226));
    }

    @Test
    @STTM("SNAP-4384")
    public void frequencyBMaskIsOnTheFrequencyBGrid() throws Exception {
        final Product p = open(DHDH);
        final Band maskB = p.getBand("mask_S");
        final Mask validB = p.getMaskGroup().get("gcov_valid_S");
        assertNotNull(validB);
        assertEquals(maskB.getRasterWidth(), validB.getRasterWidth());
        assertEquals(maskB.getRasterHeight(), validB.getRasterHeight());
        // the frequency A valid pixel, scaled to the frequency B grid
        final int x = COL * maskB.getRasterWidth() / p.getSceneRasterWidth();
        final int y = ROW * maskB.getRasterHeight() / p.getSceneRasterHeight();
        final float code = pixel(p, "mask_S", x, y);
        assertTrue("expected a valid sample, mask_S = " + code, code > 0 && code < 255);
        assertNotEquals(0, sample(p, "gcov_valid_S", x, y));
    }

    /**
     * Frequency B is an 80 m grid (x from 259960, y from 704120, pixel centres) under frequency A's
     * 20 m grid (x from 259930, y from 704150). A B pixel centre must geolocate where A's geocoding
     * puts the same easting and northing.
     */
    /** Frequency B has its own incidence angle grid: at the same ground position it equals frequency A's. */
    @Test
    @STTM("SNAP-4384")
    public void frequencyBHasItsOwnIncidenceAngleGrid() throws Exception {
        final Product p = open(DHDH);
        final TiePointGrid incA = p.getTiePointGrid("incident_angle");
        final TiePointGrid incB = p.getTiePointGrid("incident_angle_S");
        assertNotNull(incB);
        final GeoCoding gcB = p.getBand("HHHH_S").getGeoCoding();
        for (double[] b : new double[][]{{500.5, 500.5}, {2000.5, 2000.5}, {3500.5, 3000.5}}) {
            final PixelPos a = p.getSceneGeoCoding().getPixelPos(gcB.getGeoPos(new PixelPos(b[0], b[1]), null), null);
            assertEquals("B pixel " + b[0] + "," + b[1], incA.getPixelDouble(a.x, a.y), incB.getPixelDouble(b[0], b[1]), 1e-3);
        }
        assertTrue(Math.abs(incA.getPixelDouble(3500.5, 3000.5) - incB.getPixelDouble(3500.5, 3000.5)) > 0.1);
    }

    @Test
    @STTM("SNAP-4384")
    public void frequencyBBandsAreGeocodedOnTheirOwnGrid() throws Exception {
        final Product p = open(DHDH);
        final GeoCoding sceneGeoCoding = p.getSceneGeoCoding();
        for (String name : new String[]{"HHHH_S", "HVHV_S", "mask_S", "numberOfLooks_S"}) {
            final Band band = p.getBand(name);
            assertNotNull(name, band);
            assertEquals(4203, band.getRasterWidth());
            final GeoCoding gc = band.getGeoCoding();
            assertNotNull(name + " geocoding", gc);
            assertNotSame(name + " must not use frequency A's geocoding", sceneGeoCoding, gc);
            for (int[] ij : new int[][]{{0, 0}, {100, 200}, {4202, 4112}}) {
                final double easting = 259960 + 80.0 * ij[0], northing = 704120 - 80.0 * ij[1];
                final PixelPos onA = new PixelPos((easting - 259930) / 20 + 0.5, (704150 - northing) / 20 + 0.5);
                final GeoPos expected = sceneGeoCoding.getGeoPos(onA, null);
                final GeoPos actual = gc.getGeoPos(new PixelPos(ij[0] + 0.5, ij[1] + 0.5), null);
                assertEquals(name + " lat at " + Arrays.toString(ij), expected.lat, actual.lat, 1e-7);
                assertEquals(name + " lon at " + Arrays.toString(ij), expected.lon, actual.lon, 1e-7);
            }
        }
        assertNotSame("gcov_valid_S", sceneGeoCoding, p.getMaskGroup().get("gcov_valid_S").getGeoCoding());
        assertSame("frequency A bands keep the scene geocoding", sceneGeoCoding, p.getBand("HHHH").getGeoCoding());
    }

    @Test
    @STTM("SNAP-4384")
    public void outsideSwathIsNoData() throws Exception {
        final Product p = open(DHDH);
        assertEquals(255f, pixel(p, "mask", 8406, 8226), 0f);
        final Band hh = p.getBand("HHHH");
        assertTrue(hh.isNoDataValueUsed());
        assertTrue(Float.isNaN(pixel(p, "HHHH", 8406, 8226)));
    }
}
