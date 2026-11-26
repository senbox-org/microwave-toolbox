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
import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.polsar.PolBandUtils;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.CrsGeoCoding;
import org.esa.snap.core.datamodel.GeoCoding;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.Mask;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.TiePointGrid;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.junit.After;
import org.junit.Test;

import java.awt.geom.AffineTransform;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * GCOV reader tests on the synthetic fixtures in resources/eu/esa/sar/io/nisar/gcov (SNAP-4384).
 * The expected values repeat the formulas in make_gcov_fixtures.py; change both together.
 */
public class TestNisarGCOVReader {

    private static final int ROWS = 6, COLS = 8;
    private static final String PREFIX = "gcov/NISAR_L2_PR_GCOV_001_005_A_219_4020_";
    private static final String SUFFIX = "_A_20081012T060910_20081012T060926_P01101_F_N_J_001.h5";

    private final List<Product> opened = new ArrayList<>();

    @After
    public void tearDown() {
        opened.forEach(Product::dispose);
    }

    private Product open(final String mode) throws Exception {
        final File file = new File(getClass().getResource(PREFIX + mode + SUFFIX).toURI());
        final Product product = new NisarProductReaderPlugIn().createReaderInstance().readProductNodes(file, null);
        assertNotNull(product);
        opened.add(product);
        return product;
    }

    private static float diagonal(int k, int r, int c) {
        return 0.1f * (k + 1) + 0.01f * r + 0.001f * c;
    }

    private static float offDiagonalReal(int k, int r) {
        return 0.05f * (k + 1) + 0.002f * r;
    }

    private static float offDiagonalImag(int k, int c) {
        return -0.03f * (k + 1) - 0.004f * c;
    }

    private static Band band(final Product product, final String name) throws Exception {
        final Band band = product.getBand(name);
        assertNotNull("missing band " + name + " in " + Arrays.toString(product.getBandNames()), band);
        band.readRasterDataFully(ProgressMonitor.NULL);
        return band;
    }

    private static Set<String> names(final Product product) {
        return new HashSet<>(Arrays.asList(product.getBandNames()));
    }

    @Test
    @STTM("SNAP-4384")
    public void quadPolExposesEveryTermWithComplexTermsSplit() throws Exception {
        final Set<String> names = names(open("QPQP"));
        assertTrue(names.containsAll(Arrays.asList("HHHH", "HVHV", "VVVV",
                "HHHV_real", "HHHV_imag", "HHVV_real", "HHVV_imag", "HVVV_real", "HVVV_imag")));
        assertFalse("complex term must not be a single band", names.contains("HHHV"));
        assertFalse(names.contains("HHVV"));
        assertFalse(names.contains("HVVV"));
    }

    @Test
    @STTM("SNAP-4384")
    public void diagonalTermsAreReadAsIntensity() throws Exception {
        final Product product = open("QPQP");
        final String[] terms = {"HHHH", "HVHV", "VVVV"};
        for (int k = 0; k < terms.length; k++) {
            final Band b = band(product, terms[k]);
            assertEquals(Unit.INTENSITY, b.getUnit());
            assertEquals(diagonal(k, 2, 5), b.getPixelFloat(5, 2), 1e-6);
            assertEquals(diagonal(k, ROWS - 1, COLS - 1), b.getPixelFloat(COLS - 1, ROWS - 1), 1e-6);
        }
    }

    @Test
    @STTM("SNAP-4384")
    public void offDiagonalTermsKeepRealAndImaginaryParts() throws Exception {
        final Product product = open("QPQP");
        final String[] terms = {"HHHV", "HHVV", "HVVV"};
        for (int i = 0; i < terms.length; i++) {
            final int k = i + 3;    // position in listOfCovarianceTerms
            final Band re = band(product, terms[i] + "_real");
            final Band im = band(product, terms[i] + "_imag");
            assertEquals(Unit.REAL, re.getUnit());
            assertEquals(Unit.IMAGINARY, im.getUnit());
            assertEquals(offDiagonalReal(k, 3), re.getPixelFloat(4, 3), 1e-6);
            assertEquals(offDiagonalImag(k, 4), im.getPixelFloat(4, 3), 1e-6);
        }
    }

    @Test
    @STTM("SNAP-4384")
    public void fillPixelsAreNoData() throws Exception {
        final Product product = open("QPQP");
        for (String name : new String[]{"HHHH", "HHHV_real", "HHHV_imag"}) {
            final Band b = band(product, name);
            assertTrue(name + " no-data used", b.isNoDataValueUsed());
            assertTrue(name + " no-data is NaN", Double.isNaN(b.getNoDataValue()));
            assertTrue(Float.isNaN(b.getPixelFloat(0, 0)));
            assertFalse(name + " fill pixel must be invalid", b.isPixelValid(0, 0));
            assertTrue(b.isPixelValid(1, 1));
        }
    }

    @Test
    @STTM("SNAP-4384")
    public void float16ComplexTermIsDecoded() throws Exception {
        final Product product = open("DHDH");
        final Band re = band(product, "HHHV_real");
        final Band im = band(product, "HHHV_imag");
        assertEquals(offDiagonalReal(2, 5), re.getPixelFloat(6, 5), 1e-3);
        assertEquals(offDiagonalImag(2, 6), im.getPixelFloat(6, 5), 1e-3);
    }

    @Test
    @STTM("SNAP-4384")
    public void compactPolTermsAreRead() throws Exception {
        final Product product = open("CPCP");
        assertEquals(diagonal(0, 4, 2), band(product, "RHRH").getPixelFloat(2, 4), 1e-6);
        assertEquals(diagonal(1, 4, 2), band(product, "RVRV").getPixelFloat(2, 4), 1e-6);
        assertEquals(offDiagonalReal(2, 4), band(product, "RHRV_real").getPixelFloat(2, 4), 1e-6);
        assertEquals(offDiagonalImag(2, 2), band(product, "RHRV_imag").getPixelFloat(2, 4), 1e-6);
    }

    @Test
    @STTM("SNAP-4384")
    public void auxiliaryLayersAreExposed() throws Exception {
        final Product product = open("QPQP");
        final Band looks = band(product, "numberOfLooks");
        final Band rtc = band(product, "rtcGamma0ToSigma0Factor");
        final Band mask = band(product, "mask");
        assertEquals(10 + 3 + 6, looks.getPixelFloat(6, 3), 1e-6);
        assertEquals(0.5f + 0.01f * 6, rtc.getPixelFloat(6, 3), 1e-6);
        assertEquals(255, mask.getPixelInt(0, 0));
        assertEquals(0, mask.getPixelInt(3, 1));
        assertEquals(1, mask.getPixelInt(3, 2));
    }

    @Test
    @STTM("SNAP-4384")
    public void gcovValidMaskSelectsValidSamplesOnly() throws Exception {
        final Product product = open("QPQP");
        final Mask valid = product.getMaskGroup().get("gcov_valid");
        assertNotNull("gcov_valid mask", valid);
        final java.awt.image.Raster raster = valid.getSourceImage().getData();
        assertEquals("fill (mask 255)", 0, raster.getSample(0, 0, 0));
        assertEquals("partially focused or invalid (mask 0)", 0, raster.getSample(3, 1, 0));
        assertNotEquals("valid sample (mask 1)", 0, raster.getSample(3, 2, 0));
    }

    @Test
    @STTM("SNAP-4384")
    public void gcovValidMaskIsNotTheBandsValidPixelExpression() throws Exception {
        // mask 0 pixels hold data (about 20% of the footprint in current products); hiding them is the user's choice
        final Product product = open("QPQP");
        for (String name : new String[]{"HHHH", "HHHV_real", "C11", "C12_real"}) {
            assertNull(name, product.getBand(name).getValidPixelExpression());
        }
    }

    @Test
    @STTM("SNAP-4384")
    public void polarisationsComeFromTheCovarianceTerms() throws Exception {
        MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(open("QPQP"));
        assertEquals("HH", absRoot.getAttributeString(AbstractMetadata.mds1_tx_rx_polar));
        assertEquals("HV", absRoot.getAttributeString(AbstractMetadata.mds2_tx_rx_polar));
        assertEquals("VV", absRoot.getAttributeString(AbstractMetadata.mds3_tx_rx_polar));

        absRoot = AbstractMetadata.getAbstractedMetadata(open("CPCP"));
        assertEquals("RH", absRoot.getAttributeString(AbstractMetadata.mds1_tx_rx_polar));
        assertEquals("RV", absRoot.getAttributeString(AbstractMetadata.mds2_tx_rx_polar));
    }

    // ---- P3: polarimetric matrix bands -------------------------------------------------------

    private static final int X = 4, Y = 3;
    private static final double SQRT2 = Math.sqrt(2);

    /** Diagonal term k of the fixture at (X, Y). */
    private static double d(int k) {
        return diagonal(k, Y, X);
    }

    /** Real and imaginary part of off-diagonal term k of the fixture at (X, Y). */
    private static double re(int k) {
        return offDiagonalReal(k, Y);
    }

    private static double im(int k) {
        return offDiagonalImag(k, X);
    }

    private static void assertPixel(Product product, String name, double expected, double tol) throws Exception {
        assertEquals(name, expected, band(product, name).getPixelFloat(X, Y), tol);
    }

    @Test
    @STTM("SNAP-4384")
    public void quadPolGetsC3Bands() throws Exception {
        // terms: HHHH 0, HVHV 1, VVVV 2, HHHV 3, HHVV 4, HVVV 5; SNAP's k = [HH, sqrt2 HV, VV]
        final Product product = open("QPQP");
        assertEquals(PolBandUtils.MATRIX.C3, PolBandUtils.getSourceProductType(product));
        assertPixel(product, "C11", d(0), 1e-6);
        assertPixel(product, "C12_real", SQRT2 * re(3), 1e-6);
        assertPixel(product, "C12_imag", SQRT2 * im(3), 1e-6);
        assertPixel(product, "C13_real", re(4), 1e-6);
        assertPixel(product, "C13_imag", im(4), 1e-6);
        assertPixel(product, "C22", 2 * d(1), 1e-6);
        assertPixel(product, "C23_real", SQRT2 * re(5), 1e-6);
        assertPixel(product, "C23_imag", SQRT2 * im(5), 1e-6);
        assertPixel(product, "C33", d(2), 1e-6);
    }

    @Test
    @STTM("SNAP-4384")
    public void nonSymmetrisedQuadPolIsSymmetrisedIntoC3() throws Exception {
        // terms: HHHH 0, HHHV 1, HHVH 2, HHVV 3, HVHV 4, HVVH 5, HVVV 6, VHVH 7, VHVV 8, VVVV 9
        // S_X = (S_HV + S_VH) / 2
        final Product product = open("QPDH");
        assertEquals(PolBandUtils.MATRIX.C3, PolBandUtils.getSourceProductType(product));
        assertPixel(product, "C11", d(0), 1e-6);
        assertPixel(product, "C12_real", (re(1) + re(2)) / SQRT2, 1e-6);
        assertPixel(product, "C12_imag", (im(1) + im(2)) / SQRT2, 1e-6);
        assertPixel(product, "C13_real", re(3), 1e-6);
        assertPixel(product, "C13_imag", im(3), 1e-6);
        assertPixel(product, "C22", (d(4) + d(7) + 2 * re(5)) / 2, 1e-6);
        assertPixel(product, "C23_real", (re(6) + re(8)) / SQRT2, 1e-6);
        assertPixel(product, "C23_imag", (im(6) + im(8)) / SQRT2, 1e-6);
        assertPixel(product, "C33", d(9), 1e-6);
    }

    @Test
    @STTM("SNAP-4384")
    public void matrixProductsAreMarkedComplexLikeOtherCovarianceProducts() throws Exception {
        // the polarimetric operators accept only SAMPLE_TYPE COMPLEX; RCM MLC is marked the same way
        for (String mode : new String[]{"QPDH", "DHDH", "CPCP"}) {
            assertTrue(mode, new InputProductValidator(open(mode)).isComplex());
        }
    }

    @Test
    @STTM("SNAP-4384")
    public void quadPolIsFlaggedPolarimetricNotCompact() throws Exception {
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(open("QPDH"));
        assertEquals(1, absRoot.getAttributeInt(AbstractMetadata.polsarData));
        assertNotEquals(RIGHT_CIRCULAR, absRoot.getAttributeString(AbstractMetadata.compact_mode, ""));
    }

    @Test
    @STTM("SNAP-4384")
    public void matrixBandsAreNoDataWhereTheTermsAreFill() throws Exception {
        final Product product = open("QPDH");
        for (String name : new String[]{"C11", "C12_real", "C22", "C23_imag"}) {
            final Band b = band(product, name);
            assertFalse(name + " fill pixel must be invalid", b.isPixelValid(0, 0));
            assertTrue(name, b.isPixelValid(X, Y));
        }
    }

    @Test
    @STTM("SNAP-4384")
    public void dualPolGetsC2Bands() throws Exception {
        // terms: HHHH 0, HVHV 1, HHHV 2 (float16)
        final Product product = open("DHDH");
        assertEquals(PolBandUtils.MATRIX.C2, PolBandUtils.getSourceProductType(product));
        assertPixel(product, "C11", d(0), 1e-6);
        assertPixel(product, "C12_real", re(2), 1e-3);
        assertPixel(product, "C12_imag", im(2), 1e-3);
        assertPixel(product, "C22", d(1), 1e-6);
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        assertEquals(1, absRoot.getAttributeInt(AbstractMetadata.polsarData));
        assertNotEquals(RIGHT_CIRCULAR, absRoot.getAttributeString(AbstractMetadata.compact_mode, ""));
    }

    private static final String RIGHT_CIRCULAR = "Right Circular Hybrid Mode";

    @Test
    @STTM("SNAP-4384")
    public void compactPolGetsC2BandsAndCompactMode() throws Exception {
        // terms: RHRH 0, RVRV 1, RHRV 2
        final Product product = open("CPCP");
        assertEquals(PolBandUtils.MATRIX.C2, PolBandUtils.getSourceProductType(product));
        assertPixel(product, "C11", d(0), 1e-6);
        assertPixel(product, "C12_real", re(2), 1e-6);
        assertPixel(product, "C12_imag", im(2), 1e-6);
        assertPixel(product, "C22", d(1), 1e-6);
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        assertEquals(1, absRoot.getAttributeInt(AbstractMetadata.polsarData));
        assertEquals(RIGHT_CIRCULAR, absRoot.getAttributeString(AbstractMetadata.compact_mode));
    }

    @Test
    @STTM("SNAP-4384")
    public void vvVhDualPolTakesTheConjugateOfTheListedCrossTerm() throws Exception {
        // terms: VVVV 0, VHVH 1, VHVV 2; k = [VV, VH], C12 = <S_VV S_VH*> = conj(VHVV)
        final Product product = open("DVDV");
        assertEquals(PolBandUtils.MATRIX.C2, PolBandUtils.getSourceProductType(product));
        assertPixel(product, "C11", d(0), 1e-6);
        assertPixel(product, "C12_real", re(2), 1e-6);
        assertPixel(product, "C12_imag", -im(2), 1e-6);
        assertPixel(product, "C22", d(1), 1e-6);
    }

    @Test
    @STTM("SNAP-4384")
    public void leftCircularCompactPolGetsLeftCompactMode() throws Exception {
        // terms: LHLH 0, LVLV 1, LHLV 2
        final Product product = open("CLCL");
        assertEquals(PolBandUtils.MATRIX.C2, PolBandUtils.getSourceProductType(product));
        assertPixel(product, "C12_imag", im(2), 1e-6);
        assertEquals("Left Circular Hybrid Mode",
                AbstractMetadata.getAbstractedMetadata(product).getAttributeString(AbstractMetadata.compact_mode));
    }

    @Test
    @STTM("SNAP-4384")
    public void quadPolWithVhOnlyUsesItAsTheCrossPol() throws Exception {
        // terms: HHHH 0, HHVH 1, HHVV 2, VHVH 3, VHVV 4, VVVV 5; S_X = S_VH
        final Product product = open("QPVH");
        assertEquals(PolBandUtils.MATRIX.C3, PolBandUtils.getSourceProductType(product));
        assertPixel(product, "C12_real", SQRT2 * re(1), 1e-6);
        assertPixel(product, "C12_imag", SQRT2 * im(1), 1e-6);
        assertPixel(product, "C22", 2 * d(3), 1e-6);
        assertPixel(product, "C23_real", SQRT2 * re(4), 1e-6);
        assertPixel(product, "C33", d(5), 1e-6);
    }

    /** Without HVVH, C22 of the symmetrised cross-pol cannot be formed; a C2 of two of its channels would mislead. */
    @Test
    @STTM("SNAP-4384")
    public void quadPolMissingHvVhGetsNoMatrix() throws Exception {
        final Product product = open("QPNX");
        assertNull(product.getBand("C11"));
        assertNull(product.getBand("C22"));
        assertEquals(0, AbstractMetadata.getAbstractedMetadata(product).getAttributeInt(AbstractMetadata.polsarData, 0));
    }

    @Test
    @STTM("SNAP-4384")
    public void geocodingIsTheImageGridNotTheMetadataCube() throws Exception {
        // image grid: x 259930, 259950, ... y 704150, 704130, ... (20 m, pixel centres), EPSG 32659;
        // the metadata radarGrid cube is a coarser lattice over a larger area and must not set the scale
        final Product product = open("QPDH");
        final GeoCoding geoCoding = product.getSceneGeoCoding();
        assertTrue("exact map geocoding expected, got " + geoCoding, geoCoding instanceof CrsGeoCoding);
        final AffineTransform imageToMap = (AffineTransform) ((CrsGeoCoding) geoCoding).getImageToMapTransform();
        assertEquals(20.0, imageToMap.getScaleX(), 1e-9);
        assertEquals(-20.0, imageToMap.getScaleY(), 1e-9);
        assertEquals(259930.0 - 10.0, imageToMap.getTranslateX(), 1e-6);   // corner of pixel 0
        assertEquals(704150.0 + 10.0, imageToMap.getTranslateY(), 1e-6);
    }

    @Test
    @STTM("SNAP-4384")
    public void latLonTiePointsAgreeWithTheGeocoding() throws Exception {
        final Product product = open("QPDH");
        final GeoPos exact = product.getSceneGeoCoding().getGeoPos(new PixelPos(X + 0.5, Y + 0.5), null);
        final TiePointGrid lat = product.getTiePointGrid(OperatorUtils.TPG_LATITUDE);
        final TiePointGrid lon = product.getTiePointGrid(OperatorUtils.TPG_LONGITUDE);
        assertNotNull(lat);
        assertEquals(exact.lat, lat.getPixelDouble(X + 0.5, Y + 0.5), 1e-4);
        assertEquals(exact.lon, lon.getPixelDouble(X + 0.5, Y + 0.5), 1e-4);
    }

    @Test
    @STTM("SNAP-4384")
    public void radarFrequencyComesFromTheSourceDataSwaths() throws Exception {
        // operational products keep centerFrequency under metadata/sourceData/swaths, not in the grids
        final Product product = open("QPDH");
        assertEquals(1229.0, AbstractMetadata.getAbstractedMetadata(product)
                .getAttributeDouble(AbstractMetadata.radar_frequency), 1e-6);
        assertTrue("SAR operators accept only products with a radar frequency",
                new InputProductValidator(product).isSARProduct());
    }
}
