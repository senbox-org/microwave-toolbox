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
package eu.esa.sar.sentinel1.gpf;

import com.bc.ceres.annotation.STTM;
import com.bc.ceres.test.LongTestRunner;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.GeoCoding;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.VirtualBand;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.OrbitStateVector;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.awt.Rectangle;
import java.awt.image.Raster;
import java.io.File;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Slice assembly of Sentinel-1 stripmap (SM) SLC slices. Stripmap SLC has one swath, no swath
 * in the band names (i_VV, q_VV, ...) and no bursts, so it must be assembled as continuous lines
 * like GRD while keeping the complex i/q bands and slant-range metadata.
 * <p>
 * Uses three consecutive S1A S6 SLC slices of one datatake (2019-08-10, 028502_0338D0), expected in
 * {@code SAR/S1/Stripmap/Nigeria_slices/} under the test-data root; skipped when absent. Run with:
 * <pre>
 *   mvn test -pl sar-op-sentinel1 -Dtest=TestSliceAssemblyStripmap -Denable.long.tests=true
 *       -Dtests.data.dir=E:/TestData/s1tbx
 * </pre>
 */
@RunWith(LongTestRunner.class)
public class TestSliceAssemblyStripmap {

    private static final String DIR = TestData.inputSAR + "S1/Stripmap/Nigeria_slices/";
    private final File[] sliceFiles = {
            new File(DIR + "S1A_S6_SLC__1SDV_20190810T044708_20190810T044736_028502_0338D0_50F5.zip"),
            new File(DIR + "S1A_S6_SLC__1SDV_20190810T044736_20190810T044801_028502_0338D0_01B6.zip"),
            new File(DIR + "S1A_S6_SLC__1SDV_20190810T044801_20190810T044822_028502_0338D0_B34A.zip")
    };

    @Before
    public void setUp() {
        for (File f : sliceFiles) {
            assumeTrue(f + " not found", f.exists());
        }
    }

    @Test
    @STTM("SNAP-4388")
    public void testAssembleThreeStripmapSlcSlices() throws Exception {
        final Product[] slices = new Product[sliceFiles.length];
        try {
            for (int i = 0; i < sliceFiles.length; i++) {
                slices[i] = TestUtils.readSourceProduct(sliceFiles[i]);
            }

            final SliceAssemblyOp op = new SliceAssemblyOp();
            // deliberately out of order: the operator sorts by slice number
            op.setSourceProducts(slices[1], slices[2], slices[0]);
            final Product target = op.getTargetProduct();

            assertEquals("SLC", target.getProductType());

            // --- bands: complex i/q per polarisation, names unchanged
            final Set<String> realBands = new TreeSet<>();
            for (Band b : target.getBands()) {
                if (!(b instanceof VirtualBand)) {
                    realBands.add(b.getName());
                }
            }
            assertEquals(new TreeSet<>(Arrays.asList("i_VH", "q_VH", "i_VV", "q_VV")), realBands);

            // --- dimensions. Consecutive slices of one datatake abut without overlap: the first line
            // of slice k+1 is exactly one azimuth time interval after the last line of slice k, so the
            // assembled height is the plain sum of slice heights (the same rule as the GRD path).
            final MetadataElement[] absSlices = new MetadataElement[slices.length];
            int sumHeight = 0;
            int maxWidth = 0;
            for (int i = 0; i < slices.length; i++) {
                absSlices[i] = AbstractMetadata.getAbstractedMetadata(slices[i]);
                sumHeight += slices[i].getSceneRasterHeight();
                maxWidth = Math.max(maxWidth, slices[i].getSceneRasterWidth());
            }
            final double lineTimeInterval = absSlices[0].getAttributeDouble(AbstractMetadata.line_time_interval);
            for (int i = 0; i + 1 < slices.length; i++) {
                final double gapSec = (absSlices[i + 1].getAttributeUTC(AbstractMetadata.first_line_time).getMJD()
                        - absSlices[i].getAttributeUTC(AbstractMetadata.last_line_time).getMJD()) * 86400.0;
                assertEquals("seam " + i + " is not contiguous", lineTimeInterval, gapSec, 0.1 * lineTimeInterval);
            }
            assertEquals(sumHeight, target.getSceneRasterHeight());
            assertEquals(maxWidth, target.getSceneRasterWidth());
            for (String name : realBands) {
                final Band b = target.getBand(name);
                assertEquals(name, sumHeight, b.getRasterHeight());
                assertEquals(name, maxWidth, b.getRasterWidth());
            }

            // --- times
            final MetadataElement absTgt = AbstractMetadata.getAbstractedMetadata(target);
            final double firstLineMJD = absSlices[0].getAttributeUTC(AbstractMetadata.first_line_time).getMJD();
            final double lastLineMJD = absSlices[slices.length - 1].getAttributeUTC(AbstractMetadata.last_line_time).getMJD();
            assertEquals(firstLineMJD, absTgt.getAttributeUTC(AbstractMetadata.first_line_time).getMJD(), 1e-12);
            assertEquals(lastLineMJD, absTgt.getAttributeUTC(AbstractMetadata.last_line_time).getMJD(), 1e-12);
            assertEquals(sumHeight, absTgt.getAttributeInt(AbstractMetadata.num_output_lines));
            assertEquals(maxWidth, absTgt.getAttributeInt(AbstractMetadata.num_samples_per_line));
            // slant-range geometry stays slant range
            assertEquals(0, absTgt.getAttributeInt(AbstractMetadata.srgr_flag));
            assertEquals(absSlices[0].getAttributeDouble(AbstractMetadata.slant_range_to_first_pixel),
                    absTgt.getAttributeDouble(AbstractMetadata.slant_range_to_first_pixel), 1e-6);

            // corners: first line from the first slice, last line from the last slice
            final MetadataElement absLastSlice = absSlices[slices.length - 1];
            for (String c : new String[]{AbstractMetadata.first_near_lat, AbstractMetadata.first_far_long}) {
                assertEquals(c, absSlices[0].getAttributeDouble(c), absTgt.getAttributeDouble(c), 1e-9);
            }
            for (String c : new String[]{AbstractMetadata.last_near_lat, AbstractMetadata.last_near_long,
                    AbstractMetadata.last_far_lat, AbstractMetadata.last_far_long}) {
                assertEquals(c, absLastSlice.getAttributeDouble(c), absTgt.getAttributeDouble(c), 1e-9);
            }

            // --- orbit state vectors span the whole assembled acquisition
            final OrbitStateVector[] osv = AbstractMetadata.getOrbitStateVectors(absTgt);
            assertTrue(osv.length > 0);
            assertTrue("orbit starts after first line", osv[0].time_mjd <= firstLineMJD);
            assertTrue("orbit ends before last line", osv[osv.length - 1].time_mjd >= lastLineMJD);
            for (int i = 1; i < osv.length; i++) {
                assertTrue("orbit state vectors not increasing at " + i, osv[i].time_mjd > osv[i - 1].time_mjd);
            }

            // --- geocoding covers the whole span: first line matches slice 0, last line the last slice,
            // and both sides of every seam match the slice they came from
            final GeoCoding tgtGC = target.getSceneGeoCoding();
            assertNotNull("assembled product has no scene geocoding", tgtGC);
            final double x = maxWidth / 2.0;
            assertSameGeoPos(tgtGC, x, 0.5, slices[0].getSceneGeoCoding(), x, 0.5);
            int seam = 0;
            for (int i = 0; i < slices.length; i++) {
                final int h = slices[i].getSceneRasterHeight();
                assertSameGeoPos(tgtGC, x, seam + 0.5, slices[i].getSceneGeoCoding(), x, 0.5);
                assertSameGeoPos(tgtGC, x, seam + h - 0.5, slices[i].getSceneGeoCoding(), x, h - 0.5);
                seam += h;
            }

            // --- annotation geolocation grid and image information cover the assembled image
            final MetadataElement origTgt = AbstractMetadata.getOriginalProductMetadata(target);
            for (MetadataElement ann : origTgt.getElement("annotation").getElements()) {
                final MetadataElement prod = ann.getElement("product");
                final MetadataElement info = prod.getElement("imageAnnotation").getElement("imageInformation");
                assertEquals(ann.getName(), sumHeight, Integer.parseInt(info.getAttributeString("numberOfLines")));
                final MetadataElement gridList = prod.getElement("geolocationGrid").getElement("geolocationGridPointList");
                int maxLine = 0;
                for (MetadataElement p : gridList.getElements()) {
                    maxLine = Math.max(maxLine, Integer.parseInt(p.getAttributeString("line")));
                }
                assertTrue(ann.getName() + " geolocation grid stops at line " + maxLine, maxLine >= sumHeight - 1);
                assertEquals(gridList.getNumElements(), Integer.parseInt(gridList.getAttributeString("count")));
            }

            // --- calibration / noise vectors run from the first to the last slice, in line order
            final int twoSliceHeight = slices[0].getSceneRasterHeight() + slices[1].getSceneRasterHeight();
            for (String dataName : new String[]{"calibration", "noise"}) {
                for (MetadataElement e : origTgt.getElement(dataName).getElements()) {
                    final MetadataElement dat = e.getElement(dataName);
                    MetadataElement list = dat.getElement(dataName + "VectorList");
                    if (list == null) {
                        list = dat.getElement(dataName + "RangeVectorList");
                    }
                    int prev = Integer.MIN_VALUE;
                    for (MetadataElement v : list.getElements()) {
                        final int line = Integer.parseInt(v.getAttributeString("line"));
                        assertTrue(e.getName() + " vector lines not increasing", line > prev);
                        prev = line;
                    }
                    assertTrue(e.getName() + " vectors stop at line " + prev, prev > twoSliceHeight);
                }
            }

            // --- pixel data on both sides of every seam are the slices' own rows, and are not empty
            seam = 0;
            for (int i = 0; i + 1 < slices.length; i++) {
                seam += slices[i].getSceneRasterHeight();
                for (String pol : new String[]{"VV", "VH"}) {
                    final int[] tgtI = readRow(target.getBand("i_" + pol), seam - 1);
                    final int[] tgtQ = readRow(target.getBand("q_" + pol), seam - 1);
                    final int[] tgtI2 = readRow(target.getBand("i_" + pol), seam);
                    final int[] tgtQ2 = readRow(target.getBand("q_" + pol), seam);
                    assertMostlyNonZero("before seam " + i + ' ' + pol, tgtI, tgtQ);
                    assertMostlyNonZero("after seam " + i + ' ' + pol, tgtI2, tgtQ2);

                    final int hi = slices[i].getSceneRasterHeight();
                    assertArrayEquals("row before seam " + i + ' ' + pol,
                            readRow(slices[i].getBand("i_" + pol), hi - 1), tgtI);
                    assertArrayEquals("row after seam " + i + ' ' + pol,
                            readRow(slices[i + 1].getBand("i_" + pol), 0), tgtI2);
                    assertArrayEquals("q row after seam " + i + ' ' + pol,
                            readRow(slices[i + 1].getBand("q_" + pol), 0), tgtQ2);
                }
            }
            target.dispose();
        } finally {
            for (Product p : slices) {
                if (p != null) {
                    p.dispose();
                }
            }
        }
    }

    private static void assertSameGeoPos(final GeoCoding a, final double ax, final double ay,
                                         final GeoCoding b, final double bx, final double by) {
        final GeoPos ga = a.getGeoPos(new PixelPos(ax, ay), null);
        final GeoPos gb = b.getGeoPos(new PixelPos(bx, by), null);
        final String msg = "geo pos mismatch at target line " + ay + ": " + ga + " vs " + gb;
        // ~100 m: the assembled tie-point grid is resampled from the slices' grids
        assertEquals(msg, gb.getLat(), ga.getLat(), 1e-3);
        assertEquals(msg, gb.getLon(), ga.getLon(), 1e-3);
    }

    private static int[] readRow(final Band band, final int y) {
        final Raster r = band.getSourceImage().getData(new Rectangle(0, y, band.getRasterWidth(), 1));
        return r.getSamples(0, y, band.getRasterWidth(), 1, 0, (int[]) null);
    }

    private static void assertMostlyNonZero(final String msg, final int[] i, final int[] q) {
        int nonZero = 0;
        for (int k = 0; k < i.length; k++) {
            if (i[k] != 0 || q[k] != 0) {
                nonZero++;
            }
        }
        assertTrue(msg + ": only " + nonZero + " of " + i.length + " complex pixels non-zero", nonZero > i.length / 2);
    }
}
