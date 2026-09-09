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
package eu.esa.sar.io.cosmo;

import com.bc.ceres.core.ProgressMonitor;
import com.bc.ceres.multilevel.MultiLevelImage;
import eu.esa.sar.commons.test.ProductValidator;
import eu.esa.sar.commons.test.ReaderTest;
import eu.esa.sar.commons.test.SARTests;
import eu.esa.sar.commons.test.TestData;
import eu.esa.sar.io.netcdf.NcRasterDim;
import eu.esa.sar.io.netcdf.NetCDFUtils;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataAttribute;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.junit.Test;
import ucar.nc2.Dimension;
import ucar.nc2.Variable;

import java.awt.Rectangle;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Regression tests for COSMO-SkyMed Second Generation (CSG) products that are larger than a
 * single standard frame.
 *
 * <p>Reference product: an 80 km CSG Stripmap DGM_B scene (two standard frames merged into one
 * delivery), 34131 x 65619 pixels, 8.9 GB. Two independent defects kept it from opening:</p>
 *
 * <ol>
 *   <li><b>Integer overflow when picking the image raster.</b>
 *       {@code NetCDFUtils.getBestRasterDim} compared candidate rasters by
 *       {@code dimX.getLength() * dimY.getLength()} in {@code int} arithmetic. The IMG raster
 *       holds 34131 * 65619 = 2,239,642,089 pixels, which overflows a signed 32-bit int to
 *       -2,055,325,207. The 434 x 379 local-height map (LRHM) therefore compared as "larger",
 *       so the reader built the product around LRHM instead of IMG. Every downstream lookup then
 *       resolved against the wrong metadata element, which is what surfaced as the misleading
 *       {@code Metadata attribute 'S01_B001_Azimuth_First_Time' not found} error.</li>
 *   <li><b>Hard-coded three-digit burst group name.</b> The COSMO-SkyMed Mission Products
 *       Description specifies {@code B<nnn>}, and the reader looked up exactly {@code B001}.
 *       This delivery names its burst group {@code B0001}, so the azimuth first/last time
 *       fallback threw instead of finding the attribute.</li>
 *   <li><b>No sub-sampled reading, so the image view was blank.</b> With the first two fixed the
 *       product opened and full-resolution reads were correct, but the image showed nothing.
 *       The reader did not advertise sub-sampled reading, so SNAP rendered each coarse pyramid
 *       level by reading every full-resolution tile intersecting the source region and
 *       pre-fetching them into the JAI tile cache - for the coarsest levels, the entire 8.9 GB
 *       band at once. That throws {@link OutOfMemoryError} per tile and the view stays empty.
 *       The reader now reads strided NetCDF sections and declares
 *       {@code isSubsetReadingFullySupported()}.</li>
 * </ol>
 *
 * <p>The first three tests are pure unit tests and always run: they reproduce the first two
 * defects without any product file. {@code testSubSampledReadMatchesFullResolution} uses an
 * ordinary CSG product from the shared test data. The remaining two need the 8.9 GB reference
 * product, which is too large to keep in the shared test-data tree, so it is not distributed and
 * those tests are skipped unless the file is present locally. Point at it with</p>
 *
 * <pre>
 *   -Dcosmo.csg.largeProduct=&lt;path to the .h5 file, or to the folder holding it&gt;
 * </pre>
 *
 * <p>or with {@code test.cosmoLargeProduct} in {@code ~/.snap/etc/s1tbx.tests.properties}. If
 * neither is set, the default location under the shared test-data tree is checked, and the test
 * is skipped when nothing is found there either.</p>
 */
public class TestCosmoSkymedLargeProduct extends ReaderTest {

    /**
     * File name of the CSG Stripmap DGM_B product covering two standard frames (80 km).
     */
    private static final String LARGE_PRODUCT_NAME =
            "CSG_SSAR2_DGM_B_0101_STR_017_HH_RD_P_20260726174641_20260726174654_1_F_36N_Z30_N00.h5";

    /** System property holding the local path of the reference product (file or its folder). */
    private static final String LARGE_PRODUCT_PROPERTY = "cosmo.csg.largeProduct";

    /** {@code s1tbx.tests} preference holding the same, for a persistent local setting. */
    private static final String LARGE_PRODUCT_PREFERENCE = "test.cosmoLargeProduct";

    /** Where the product would live if it were small enough to ship with the test data. */
    private static final String DEFAULT_LOCATION =
            TestData.inputSAR + "Cosmo/NG/DGM_B_hdf5_HH_2frames/" + LARGE_PRODUCT_NAME;

    public final static File inputLargeSM_DGM_H5 = resolveLargeProduct();

    // Raster dimensions of the reference product's IMG dataset.
    private static final int IMG_WIDTH = 34131;
    private static final int IMG_HEIGHT = 65619;
    // Raster dimensions of its local-height map, the decoy that used to win the comparison.
    private static final int LRHM_WIDTH = 379;
    private static final int LRHM_HEIGHT = 434;

    public TestCosmoSkymedLargeProduct() {
        super(new CosmoSkymedReaderPlugIn());
    }

    /**
     * The image raster must win over a small auxiliary raster even when its pixel count exceeds
     * {@link Integer#MAX_VALUE}.
     */
    @Test
    public void testBestRasterDimNotOverflowedByLargeRaster() {
        assertTrue("test fixture must actually overflow a 32-bit int",
                (long) IMG_WIDTH * IMG_HEIGHT > Integer.MAX_VALUE);

        final NcRasterDim imgDim = rasterDim("imgX", IMG_WIDTH, "imgY", IMG_HEIGHT);
        final NcRasterDim lrhmDim = rasterDim("lrhmX", LRHM_WIDTH, "lrhmY", LRHM_HEIGHT);

        // Both orderings, so the result cannot depend on map iteration order.
        assertSame("large raster must be preferred over the local-height map",
                imgDim, NetCDFUtils.getBestRasterDim(rasterMap(imgDim, lrhmDim)));
        assertSame("large raster must be preferred over the local-height map",
                imgDim, NetCDFUtils.getBestRasterDim(rasterMap(lrhmDim, imgDim)));
    }

    /**
     * Guard the ordinary, non-overflowing case at the same time.
     */
    @Test
    public void testBestRasterDimPicksLargestForOrdinarySizes() {
        final NcRasterDim small = rasterDim("aX", 100, "aY", 100);
        final NcRasterDim large = rasterDim("bX", 5000, "bY", 5000);

        assertSame(large, NetCDFUtils.getBestRasterDim(rasterMap(small, large)));
        assertSame(large, NetCDFUtils.getBestRasterDim(rasterMap(large, small)));
    }

    /**
     * The burst group is {@code B<nnn>} per the products handbook, but CSG deliveries also use
     * {@code B<nnnn>}. Both spellings, flattened and nested, must resolve. For a multi-burst
     * sub-swath the first time comes from the lowest-numbered burst and the last time from the
     * highest-numbered one.
     */
    @Test
    public void testBurstAttributeAcceptsAnyBurstNumberWidth() {
        // Flattened, four digits - the reference product.
        final MetadataElement flat4 = globalElem();
        addDouble(flat4, "S01_B0001_Azimuth_First_Time", 64001.11065385115);
        addDouble(flat4, "S01_B0001_Azimuth_Last_Time", 64013.70287569115);
        assertEquals(64001.11065385115,
                CosmoSkymedNetCDFReader.findBurstAttribute(flat4, "S01", "Azimuth_First_Time", false), 1e-9);
        assertEquals(64013.70287569115,
                CosmoSkymedNetCDFReader.findBurstAttribute(flat4, "S01", "Azimuth_Last_Time", true), 1e-9);

        // Flattened, three digits - the pre-existing spelling must keep working.
        final MetadataElement flat3 = globalElem();
        addDouble(flat3, "S01_B001_Azimuth_First_Time", 100.0);
        addDouble(flat3, "S01_B001_Azimuth_Last_Time", 200.0);
        assertEquals(100.0,
                CosmoSkymedNetCDFReader.findBurstAttribute(flat3, "S01", "Azimuth_First_Time", false), 1e-9);
        assertEquals(200.0,
                CosmoSkymedNetCDFReader.findBurstAttribute(flat3, "S01", "Azimuth_Last_Time", true), 1e-9);

        // Nested elements, four digits.
        final MetadataElement nested = globalElem();
        final MetadataElement s01 = new MetadataElement("S01");
        final MetadataElement b0001 = new MetadataElement("B0001");
        addDouble(b0001, "Azimuth_First_Time", 7.0);
        s01.addElement(b0001);
        nested.addElement(s01);
        assertEquals(7.0,
                CosmoSkymedNetCDFReader.findBurstAttribute(nested, "S01", "Azimuth_First_Time", false), 1e-9);

        // Multi-burst: first burst for the first time, last burst for the last time.
        final MetadataElement multi = globalElem();
        addDouble(multi, "S01_B0001_Azimuth_First_Time", 10.0);
        addDouble(multi, "S01_B0001_Azimuth_Last_Time", 20.0);
        addDouble(multi, "S01_B0002_Azimuth_First_Time", 20.0);
        addDouble(multi, "S01_B0002_Azimuth_Last_Time", 30.0);
        assertEquals(10.0,
                CosmoSkymedNetCDFReader.findBurstAttribute(multi, "S01", "Azimuth_First_Time", false), 1e-9);
        assertEquals(30.0,
                CosmoSkymedNetCDFReader.findBurstAttribute(multi, "S01", "Azimuth_Last_Time", true), 1e-9);

        // Nothing to find - report absence rather than throwing.
        assertNull(CosmoSkymedNetCDFReader.findBurstAttribute(globalElem(), "S01", "Azimuth_First_Time", false));
    }

    /**
     * A sub-sampled read must return exactly the samples a full-resolution read would, taken every
     * {@code step} pixels. This is the contract {@code isSubsetReadingFullySupported} promises the
     * framework, and it is what lets the higher pyramid levels be rendered without materialising
     * the full-resolution source region.
     *
     * <p>Runs against any ordinary CSG product from the shared test data - the defect it guards is
     * not size-specific, only its consequences are.</p>
     */
    @Test
    public void testSubSampledReadMatchesFullResolution() throws Exception {
        final File input = TestCosmoSkymedReader.inputSM_DGM_H5;
        assumeTrue(input + " not found", input.isFile());

        final CosmoSkymedReaderPlugIn plugIn = new CosmoSkymedReaderPlugIn();
        final CosmoSkymedNetCDFReader ncReader =
                new CosmoSkymedNetCDFReader(plugIn, new CosmoSkymedReader(plugIn));
        final Product prod = ncReader.createProduct(input.toPath());
        try {
            final Band band = prod.getBandAt(0);
            final int n = 8;
            final int offsetX = 1000;
            final int offsetY = 2000;

            for (final int step : new int[]{2, 8, 64}) {
                final int span = (n - 1) * step + 1;
                assertTrue("test region must fit in the raster",
                        offsetX + span <= prod.getSceneRasterWidth()
                                && offsetY + span <= prod.getSceneRasterHeight());

                // Full resolution over the same span.
                final ProductData full = ProductData.createInstance(band.getDataType(), span * span);
                ncReader.readBandRasterDataImpl(offsetX, offsetY, span, span, 1, 1,
                        band, offsetX, offsetY, span, span, full, ProgressMonitor.NULL);

                // Sub-sampled over that span.
                final ProductData sub = ProductData.createInstance(band.getDataType(), n * n);
                ncReader.readBandRasterDataImpl(offsetX, offsetY, span, span, step, step,
                        band, 0, 0, n, n, sub, ProgressMonitor.NULL);

                for (int j = 0; j < n; j++) {
                    for (int i = 0; i < n; i++) {
                        assertEquals("step " + step + " sample (" + i + ',' + j + ')',
                                full.getElemDoubleAt((j * step) * span + i * step),
                                sub.getElemDoubleAt(j * n + i), 0.0);
                    }
                }
            }
        } finally {
            ncReader.close();
            prod.dispose();
        }
    }

    /**
     * Every pyramid level of the two-frame product must render real data.
     *
     * <p>This is the guard for the reported "product opens but the image is blank". When the reader
     * does not advertise sub-sampled reading, SNAP renders a coarse level by reading every
     * full-resolution tile intersecting the source region - the whole 8.9 GB band for the coarsest
     * levels - and pre-fetching them into the JAI tile cache. That throws
     * {@link OutOfMemoryError} and the image view is left empty.</p>
     */
    @Test
    public void testAllPyramidLevelsRender() throws Exception {
        assumeTrue("CSG two-frame reference product not available locally - run with -D"
                        + LARGE_PRODUCT_PROPERTY + "=<path> to enable this test (looked for "
                        + inputLargeSM_DGM_H5 + ')',
                inputLargeSM_DGM_H5.isFile());
        final Product prod = testReader(inputLargeSM_DGM_H5.toPath());
        final Band band = prod.getBand("Amplitude_HH");
        assertNotNull(band);

        final MultiLevelImage image = band.getSourceImage();
        final int levelCount = image.getModel().getLevelCount();
        assertTrue("a scene this large must have a deep pyramid", levelCount > 6);

        for (int level = 0; level < levelCount; level++) {
            final RenderedImage levelImage = image.getImage(level);
            final int w = Math.min(32, levelImage.getWidth());
            final int h = Math.min(32, levelImage.getHeight());
            final int x = levelImage.getMinX() + levelImage.getWidth() / 2 - w / 2;
            final int y = levelImage.getMinY() + levelImage.getHeight() / 2 - h / 2;

            final Raster raster = levelImage.getData(new Rectangle(x, y, w, h));
            boolean anyNonZero = false;
            for (int j = 0; j < h; j++) {
                for (int i = 0; i < w; i++) {
                    final double v = raster.getSampleDouble(x + i, y + j, 0);
                    assertTrue("level " + level + " sample must be finite", Double.isFinite(v));
                    anyNonZero |= v != 0.0;
                }
            }
            assertTrue("level " + level + " rendered all zeros - the image view would be blank",
                    anyNonZero);
        }
        close();
    }

    /**
     * End-to-end read of the two-frame CSG Stripmap product.
     */
    @Test
    public void testOpeningLargeSM_DGM_H5() throws Exception {
        assumeTrue("CSG two-frame reference product not available locally - run with -D"
                        + LARGE_PRODUCT_PROPERTY + "=<path> to enable this test (looked for "
                        + inputLargeSM_DGM_H5 + ')',
                inputLargeSM_DGM_H5.isFile());
        final Product prod = testReader(inputLargeSM_DGM_H5.toPath());

        // The IMG raster, not the 379 x 434 local-height map.
        assertEquals("scene width", IMG_WIDTH, prod.getSceneRasterWidth());
        assertEquals("scene height", IMG_HEIGHT, prod.getSceneRasterHeight());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[]{"Amplitude_HH", "Intensity_HH"});

        // Line times must come from the IMG "Zero Doppler Azimuth" attributes, i.e. be inside the
        // sensing window of the delivery note (2026-07-26 17:46:41 .. 17:46:53).
        final ProductData.UTC start = prod.getStartTime();
        final ProductData.UTC end = prod.getEndTime();
        assertNotNull("start time", start);
        assertNotNull("end time", end);
        assertTrue("start time must precede end time", start.getMJD() < end.getMJD());
        final double durationSeconds = (end.getMJD() - start.getMJD()) * 24 * 3600;
        assertEquals("acquisition duration", 11.94, durationSeconds, 0.5);

        // Reading the far corner exercises a file offset beyond 2^31 pixels.
        final Band band = prod.getBand("Amplitude_HH");
        assertNotNull(band);
        final float[] corner = new float[16];
        band.readPixels(IMG_WIDTH - 4, IMG_HEIGHT - 4, 4, 4, corner);
        boolean anyFinite = false;
        for (float v : corner) {
            assertTrue("pixel value must be finite", !Float.isNaN(v) && !Float.isInfinite(v));
            anyFinite |= v != 0f;
        }
        assertTrue("last pixels of the scene are all zero - the far corner was not read", anyFinite);

        close();
    }

    // --- helpers ---

    /**
     * Locates the reference product: system property first, then the {@code s1tbx.tests}
     * preference, then the shared test-data tree. Never returns null - the caller checks whether
     * the returned file actually exists and skips the test if it does not.
     */
    private static File resolveLargeProduct() {
        final String override = System.getProperty(LARGE_PRODUCT_PROPERTY);
        if (override != null && !override.trim().isEmpty()) {
            return asProductFile(new File(override.trim()));
        }
        final File[] fromPreferences = SARTests.loadFilePath(LARGE_PRODUCT_PREFERENCE, DEFAULT_LOCATION);
        if (fromPreferences.length > 0) {
            return asProductFile(fromPreferences[0]);
        }
        return new File(DEFAULT_LOCATION);
    }

    /**
     * Accept either the product file itself or the folder that holds it.
     */
    private static File asProductFile(final File file) {
        return file.isDirectory() ? new File(file, LARGE_PRODUCT_NAME) : file;
    }

    private static MetadataElement globalElem() {
        return new MetadataElement("Global_Attributes");
    }

    private static void addDouble(final MetadataElement elem, final String name, final double value) {
        elem.addAttribute(new MetadataAttribute(name, ProductData.createInstance(new double[]{value}), true));
    }

    private static NcRasterDim rasterDim(final String xName, final int width,
                                         final String yName, final int height) {
        return new NcRasterDim(new Dimension(xName, width), new Dimension(yName, height));
    }

    private static Map<NcRasterDim, List<Variable>> rasterMap(final NcRasterDim... dims) {
        // LinkedHashMap so the declared order is the iteration order.
        final Map<NcRasterDim, List<Variable>> map = new LinkedHashMap<>();
        for (NcRasterDim dim : dims) {
            map.put(dim, new ArrayList<>());
        }
        return map;
    }
}
