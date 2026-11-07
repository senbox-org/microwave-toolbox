package eu.esa.sar.calibration.gpf;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.test.ProcessorTest;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import java.awt.Dimension;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link RemoveGRDBorderNoiseOp}.
 * The synthetic GRD has a 40-pixel near-range border of DN 5 (below the noise amplitude 10, so it denoises to 0),
 * interior DN 200, and an interior calm-water patch of DN 1 that must not be masked.
 */
public class TestRemoveGRDBorderNoiseOp extends ProcessorTest {

    private final static OperatorSpi spi = new RemoveGRDBorderNoiseOp.Spi();

    private static final int W = 600;
    private static final int H = 400;

    @Test
    public void testSpiCreatesOperator() {
        final RemoveGRDBorderNoiseOp op = (RemoveGRDBorderNoiseOp) spi.createOperator();
        assertNotNull(op);
    }

    @Test
    public void testOperatorMetadata() {
        final OperatorMetadata md = RemoveGRDBorderNoiseOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull("RemoveGRDBorderNoiseOp must declare @OperatorMetadata", md);
        assertEquals("Remove-GRD-Border-Noise", md.alias());
        assertEquals("Radar/Sentinel-1 TOPS", md.category());
    }

    @Test
    public void testInitializeWithoutSourceProductThrows() {
        final RemoveGRDBorderNoiseOp op = (RemoveGRDBorderNoiseOp) spi.createOperator();
        try {
            op.getTargetProduct();
            fail("Expected OperatorException when no source product is set");
        } catch (OperatorException expected) {
            // success
        } catch (RuntimeException expected) {
            // framework may wrap in other runtime exceptions
        }
    }

    @Test
    public void testInitializeRejectsNonSentinel1Product() {
        final Product product = TestUtils.createProduct("GRD", 10, 10);
        final RemoveGRDBorderNoiseOp op = (RemoveGRDBorderNoiseOp) spi.createOperator();
        op.setSourceProduct(product);
        try {
            op.getTargetProduct();
            fail("Expected failure when product is not a Sentinel-1 GRD");
        } catch (OperatorException expected) {
            // success
        } catch (RuntimeException expected) {
            // framework may wrap in other runtime exceptions
        }
    }

    @Test
    public void masksContiguousBorderAndNotInteriorWater() throws Exception {
        final Product trg = process(createGrdWithBorder("Sentinel-1 IPF 002.84"), "ESA", null);
        final float[] row = new float[W];
        trg.getBand("Amplitude_VV").readPixels(0, 150, W, 1, row, ProgressMonitor.NULL);
        for (int x = 0; x < 40; x++) {
            assertEquals("border x=" + x, 0f, row[x], 0);
        }
        assertEquals(200f, row[100], 0);
        assertEquals("water must survive", 1f, row[310], 0);
    }

    @Test
    public void maskHasNoHolesAlongBorder() throws Exception {
        final int[][] cuts = cutsOf(createGrdWithBorder("Sentinel-1 IPF 002.84"), "ESA");
        for (int y = 0; y < H; y++) {
            assertTrue("left cut at y=" + y + " is " + cuts[0][y], cuts[0][y] >= 40);
            assertEquals("right cut at y=" + y, 0, cuts[1][y]);
        }
    }

    @Test
    public void bidirectionalNeedsNoNoiseVectors() throws Exception {
        final Product src = createGrdWithBorder("Sentinel-1 IPF 002.84");
        final MetadataElement orig = AbstractMetadata.getOriginalProductMetadata(src);
        orig.removeElement(orig.getElement("noise"));
        final int[][] cuts = cutsOf(src, "Bidirectional");
        assertTrue("cut " + cuts[0][200], cuts[0][200] >= 40 && cuts[0][200] <= 40 + 1 + 5);
    }

    @Test
    public void tileSizeDoesNotChangeMask() throws Exception {
        final Product small = process(createGrdWithBorder("Sentinel-1 IPF 002.84"), "ESA", new Dimension(64, 64));
        final float[] a = readInRectangles(small.getBand("Amplitude_VV"), 37, 29);
        final float[] b = readAll(process(createGrdWithBorder("Sentinel-1 IPF 002.84"), "ESA", new Dimension(W, H)));
        assertEquals("border is masked", 0f, b[150 * W + 10], 0);
        assertArrayEquals(b, a, 0f);
    }

    @Test
    public void recentIpfIsPassThroughByDefault() throws Exception {
        final Product trg = process(createGrdWithBorder("Sentinel-1 IPF 003.71"), "Auto", null);
        final float[] row = new float[W];
        trg.getBand("Amplitude_VV").readPixels(0, 150, W, 1, row, ProgressMonitor.NULL);
        assertEquals(5f, row[10], 0);
    }

    @Test
    public void esaMethodRejectsIpfBefore250() throws Exception {
        // noise vectors before IPF 2.50 are 20+ dB too low even after the MPC-0243 rescaling
        try {
            process(createGrdWithBorder("Sentinel-1 IPF 002.36"), "ESA", null);
            fail("expected OperatorException");
        } catch (OperatorException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("2.50"));
        }
    }

    @Test
    public void esaMethodRejectsRecentIpf() throws Exception {
        try {
            process(createGrdWithBorder("Sentinel-1 IPF 003.71"), "ESA", null);
            fail("expected OperatorException");
        } catch (OperatorException expected) {
            // success
        }
    }

    @Test
    public void autoUsesBidirectionalBeforeIpf250() throws Exception {
        // IPF < 2.50 noise vectors are of limited accuracy (MPC-0243): Auto must not depend on them
        final Product src = createGrdWithBorder("Sentinel-1 IPF 002.36");
        final MetadataElement orig = AbstractMetadata.getOriginalProductMetadata(src);
        orig.removeElement(orig.getElement("noise"));
        final int[][] cuts = cutsOf(src, "Auto");
        assertTrue("cut " + cuts[0][200], cuts[0][200] >= 40 && cuts[0][200] <= 40 + 1 + 5);
    }

    @Test
    public void subsetEdgesAreNotImageEdges() throws Exception {
        // a subset starting at x = 100 of a 2000-sample image: its left and right edges are inside the image
        final Product src = createGrdWithBorder("Sentinel-1 IPF 002.84");
        AbstractMetadata.getAbstractedMetadata(src).setAttributeInt(AbstractMetadata.subset_offset_x, 100);
        final MetadataElement info = AbstractMetadata.getOriginalProductMetadata(src).getElement("annotation")
                .getElementAt(0).getElement("product").getElement("imageAnnotation").getElement("imageInformation");
        info.setAttributeString("numberOfSamples", "2000");
        info.setAttributeString("numberOfLines", String.valueOf(H));
        final int[][] cuts = cutsOf(src, "ESA");
        for (int y = 0; y < H; y++) {
            assertEquals("left cut at y=" + y, 0, cuts[0][y]);
            assertEquals("right cut at y=" + y, 0, cuts[1][y]);
        }
    }

    @Test
    public void thermallyDenoisedInputUsesZeroNoise() throws Exception {
        // ThermalNoiseRemoval before this operator (common in graphs): the noise is already removed (eta = 0)
        final Product src = createGrdWithBorder("Sentinel-1 IPF 002.84", 0.1f);
        AbstractMetadata.getOriginalProductMetadata(src).getElement("annotation").getElementAt(0).getElement("product")
                .getElement("imageAnnotation").getElement("processingInformation")
                .setAttributeString("thermalNoiseCorrectionPerformed", "true");
        final int[][] cuts = cutsOf(src, "Auto");
        assertTrue("cut " + cuts[0][200], cuts[0][200] >= 40 && cuts[0][200] <= 40 + 1 + 5);
    }

    @Test
    public void topAndBottomAreOnlySearchedOnTheFirstAndLastSlice() throws Exception {
        // middle slice of a data take: dark first and last lines are scene content, not border noise
        final Product src = createGrdWithBorder("Sentinel-1 IPF 002.84", 5f, true);
        final MetadataElement info = AbstractMetadata.getOriginalProductMetadata(src).getElement("annotation")
                .getElementAt(0).getElement("product").getElement("imageAnnotation").getElement("imageInformation");
        info.setAttributeString("sliceNumber", "5");
        final MetadataElement sliceList = new MetadataElement("sliceList");
        sliceList.setAttributeString("count", "10");
        info.addElement(sliceList);
        final int[][] cuts = cutsOf(src, "Bidirectional");
        for (int x = 40; x < W; x++) {
            assertEquals("top cut at x=" + x, 0, cuts[2][x]);
            assertEquals("bottom cut at x=" + x, 0, cuts[3][x]);
        }
    }

    @Test
    public void getCutsOnPassThroughIsEmpty() throws Exception {
        final RemoveGRDBorderNoiseOp op = (RemoveGRDBorderNoiseOp) spi.createOperator();
        op.setSourceProduct(createGrdWithBorder("Sentinel-1 IPF 003.71"));
        op.getTargetProduct();
        final int[][] cuts = op.getCuts();
        assertEquals(0, java.util.Arrays.stream(cuts[0]).max().orElse(0));
    }

    @Test
    public void fullBorderFirstLinesDoNotWidenTheSideCuts() throws Exception {
        // first/last 20 lines are border over the whole width (first/last slice of a data take): the side cuts of
        // those lines span the whole strip and must not spread to the valid lines next to them
        final Product src = createGrdWithBorder("Sentinel-1 IPF 002.84", 5f, true);
        final int[][] cuts = cutsOf(src, "Bidirectional");
        assertTrue("left cut at y=100 is " + cuts[0][100], cuts[0][100] <= 40 + 1 + 5);
        assertTrue("left cut at y=300 is " + cuts[0][300], cuts[0][300] <= 40 + 1 + 5);
        assertTrue("right cut at y=100 is " + cuts[1][100], cuts[1][100] <= 1 + 5);
        final float[] row = new float[W];
        process(src, "Bidirectional", null).getBand("Amplitude_VV").readPixels(0, 100, W, 1, row, ProgressMonitor.NULL);
        assertEquals("valid pixel next to the border lines", 200f, row[100], 0);
        // the corner itself stays masked
        final float[] corner = new float[1];
        process(src, "Bidirectional", null).getBand("Amplitude_VV").readPixels(10, 10, 1, 1, corner, ProgressMonitor.NULL);
        assertEquals(0f, corner[0], 0);
    }

    @Test
    public void sliceAssembledProductsSearchTopAndBottom() throws Exception {
        // Slice Assembly keeps the first slice's sliceNumber and tags the noise vectors with their slice
        final Product src = createGrdWithBorder("Sentinel-1 IPF 002.84", 5f, true);
        final MetadataElement orig = AbstractMetadata.getOriginalProductMetadata(src);
        final MetadataElement info = orig.getElement("annotation").getElementAt(0).getElement("product")
                .getElement("imageAnnotation").getElement("imageInformation");
        info.setAttributeString("sliceNumber", "1");
        final MetadataElement sliceList = new MetadataElement("sliceList");
        sliceList.setAttributeString("count", "3");
        info.addElement(sliceList);
        orig.getElement("noise").getElementAt(0).getElement("noise").getElement("noiseVectorList")
                .getElementAt(0).setAttributeString("slice", "1");
        final int[][] cuts = cutsOf(src, "Bidirectional");
        assertTrue("bottom cut " + cuts[3][300], cuts[3][300] >= 20);
    }

    @Test
    public void recentStripmapIsPassThroughByDefault() throws Exception {
        final Product src = createGrdWithBorder("Sentinel-1 IPF 003.71");
        AbstractMetadata.getAbstractedMetadata(src).setAttributeString(AbstractMetadata.ACQUISITION_MODE, "SM");
        final float[] row = new float[W];
        process(src, "Auto", null).getBand("Amplitude_VV").readPixels(0, 150, W, 1, row, ProgressMonitor.NULL);
        assertEquals(5f, row[10], 0);
    }

    @Test
    public void thermallyDenoisedInputIsComparedWithoutNoise() throws Exception {
        // DN 5 is border only if the noise (amplitude 10) were subtracted; after TNR eta = 0, so it is kept
        final Product src = createGrdWithBorder("Sentinel-1 IPF 002.84");
        AbstractMetadata.getOriginalProductMetadata(src).getElement("annotation").getElementAt(0).getElement("product")
                .getElement("imageAnnotation").getElement("processingInformation")
                .setAttributeString("thermalNoiseCorrectionPerformed", "true");
        final int[][] cuts = cutsOf(src, "ESA");
        assertEquals(0, cuts[0][200]);
    }

    @Test
    public void thermallyDenoisedDarkEdgeIsNotMaskedAcrossTheWholeStrip() throws Exception {
        // after TNR, dark water filling the searched strip looks like a border deeper than the strip
        final Product src = createGrdWithBorder("Sentinel-1 IPF 002.84");
        final float[] data = (float[]) src.getBand("Amplitude_VV").getData().getElems();
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < 250; x++) {
                data[y * W + x] = 3f;
            }
        }
        AbstractMetadata.getOriginalProductMetadata(src).getElement("annotation").getElementAt(0).getElement("product")
                .getElement("imageAnnotation").getElement("processingInformation")
                .setAttributeString("thermalNoiseCorrectionPerformed", "true");
        final int[][] cuts = cutsOf(src, "Bidirectional");
        assertEquals(0, cuts[0][200]);
    }

    @Test
    public void dbBandsAreNotUsedForDetection() throws Exception {
        // a dB band listed before the linear co-pol band must not be taken as the detection band
        final Product src = createGrdWithBorder("Sentinel-1 IPF 002.84");
        final org.esa.snap.core.datamodel.Band amplitude = src.getBand("Amplitude_VV");
        src.removeBand(amplitude);
        final org.esa.snap.core.datamodel.Band db = new org.esa.snap.core.datamodel.Band("Sigma0_VV_db",
                ProductData.TYPE_FLOAT32, W, H);
        db.setUnit(Unit.INTENSITY_DB);
        final float[] dbData = new float[W * H];
        java.util.Arrays.fill(dbData, -15f);
        db.setData(ProductData.createInstance(dbData));
        src.addBand(db);
        src.addBand(amplitude);
        final int[][] cuts = cutsOf(src, "ESA");
        assertTrue("cut " + cuts[0][200], cuts[0][200] >= 40 && cuts[0][200] <= 46);
    }

    @Test(timeout = 120000)
    public void tilesRequestedFromForkJoinThreadsDoNotDeadlock() throws Exception {
        // GPF clients may request tiles from ForkJoin common-pool threads; the border prepass must not starve them
        // every 32x32 tile is requested at once from common-pool tasks, so the first requests block on the prepass
        final int tile = 32;
        final Product trg = process(createGrdWithBorder("Sentinel-1 IPF 002.84"), "ESA", new Dimension(tile, tile));
        final Band band = trg.getBand("Amplitude_VV");
        final int tilesX = (W + tile - 1) / tile, tilesY = (H + tile - 1) / tile;
        final float[] parallel = new float[W * H];
        java.util.concurrent.ForkJoinPool.commonPool().submit(() ->
                java.util.stream.IntStream.range(0, tilesX * tilesY).parallel().forEach(i -> {
                    final int x0 = (i % tilesX) * tile, y0 = (i / tilesX) * tile;
                    final int w = Math.min(tile, W - x0), h = Math.min(tile, H - y0);
                    final float[] v = new float[w * h];
                    try {
                        band.readPixels(x0, y0, w, h, v, ProgressMonitor.NULL);
                    } catch (java.io.IOException e) {
                        throw new RuntimeException(e);
                    }
                    for (int y = 0; y < h; y++) {
                        System.arraycopy(v, y * w, parallel, (y0 + y) * W + x0, w);
                    }
                })).get();
        final float[] sequential = readAll(process(createGrdWithBorder("Sentinel-1 IPF 002.84"), "ESA", null));
        assertArrayEquals(sequential, parallel, 0f);
    }

    @Test
    public void rightEdgeBorderIsMasked() throws Exception {
        final Product src = createGrdWithBorder("Sentinel-1 IPF 002.84");
        final float[] data = (float[]) src.getBand("Amplitude_VV").getData().getElems();
        for (int y = 0; y < H; y++) {
            for (int x = W - 30; x < W; x++) {
                data[y * W + x] = 5f;
            }
        }
        final MetadataElement info = imageInformation(src);
        info.setAttributeString("numberOfSamples", String.valueOf(W));
        info.setAttributeString("numberOfLines", String.valueOf(H));
        final int[][] cuts = cutsOf(src, "ESA");
        assertTrue("right cut " + cuts[1][200], cuts[1][200] >= 30 && cuts[1][200] <= 30 + 1 + 5);
        final float[] row = new float[W];
        process(src, "ESA", null).getBand("Amplitude_VV").readPixels(0, 150, W, 1, row, ProgressMonitor.NULL);
        assertEquals(0f, row[W - 1], 0);
        assertEquals(200f, row[W - 40], 0);
    }

    @Test
    public void crossPolarisedBandGetsTheCoPolarisedMask() throws Exception {
        final Product src = createGrdWithBorder("Sentinel-1 IPF 002.84");
        addConstantBand(src, "Amplitude_VH", Unit.AMPLITUDE, 50f);
        final Product trg = process(src, "ESA", null);
        final float[] vv = new float[W];
        final float[] vh = new float[W];
        trg.getBand("Amplitude_VV").readPixels(0, 150, W, 1, vv, ProgressMonitor.NULL);
        trg.getBand("Amplitude_VH").readPixels(0, 150, W, 1, vh, ProgressMonitor.NULL);
        assertEquals(0f, vh[10], 0);
        assertEquals(50f, vh[100], 0);
        for (int x = 0; x < W; x++) {
            assertEquals("x=" + x, vv[x] == 0f, vh[x] == 0f);
        }
    }

    @Test
    public void dbBandsAreMaskedWithNaNNotZero() throws Exception {
        // 0 dB is a valid value: a dB band must not get no-data 0
        final Product src = createGrdWithBorder("Sentinel-1 IPF 002.84");
        addConstantBand(src, "Sigma0_VV_db", Unit.INTENSITY_DB, -15f);
        final Band db = process(src, "ESA", null).getBand("Sigma0_VV_db");
        assertTrue(db.isNoDataValueUsed());
        assertTrue("no-data " + db.getNoDataValue(), Double.isNaN(db.getNoDataValue()));
        final float[] row = new float[W];
        db.readPixels(0, 150, W, 1, row, ProgressMonitor.NULL);
        assertTrue("masked " + row[10], Float.isNaN(row[10]));
        assertEquals(-15f, row[100], 0);
    }

    @Test
    public void autoFallsBackToBidirectionalWithoutNoiseAnnotation() throws Exception {
        final Product src = createGrdWithBorder("Sentinel-1 IPF 002.84");
        final MetadataElement orig = AbstractMetadata.getOriginalProductMetadata(src);
        orig.removeElement(orig.getElement("noise"));
        final int[][] cuts = cutsOf(src, "Auto");
        assertTrue("cut " + cuts[0][200], cuts[0][200] >= 40 && cuts[0][200] <= 40 + 1 + 5);
    }

    @Test
    public void esaMethodWithoutNoiseAnnotationFailsClearly() throws Exception {
        final Product src = createGrdWithBorder("Sentinel-1 IPF 002.84");
        // a noise data set without adsHeader must not cause a NullPointerException
        final MetadataElement noiseAds = AbstractMetadata.getOriginalProductMetadata(src).getElement("noise")
                .getElementAt(0).getElement("noise");
        noiseAds.removeElement(noiseAds.getElement("adsHeader"));
        try {
            process(src, "ESA", null);
            fail("expected OperatorException");
        } catch (OperatorException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("noise vectors"));
        }
    }

    @Test
    public void esaMethodRejectsStripmap() throws Exception {
        final Product src = createGrdWithBorder("Sentinel-1 IPF 002.84");
        AbstractMetadata.getAbstractedMetadata(src).setAttributeString(AbstractMetadata.ACQUISITION_MODE, "SM");
        try {
            process(src, "ESA", null);
            fail("expected OperatorException");
        } catch (OperatorException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("IW and EW"));
        }
    }

    @Test
    public void resampledInputIsRefused() throws Exception {
        // after multilooking the image edges no longer match the annotated L1 raster
        final Product src = createGrdWithBorder("Sentinel-1 IPF 002.84");
        final MetadataElement info = imageInformation(src);
        info.setAttributeString("rangePixelSpacing", "10.0");
        info.setAttributeString("azimuthPixelSpacing", "10.0");
        final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(src);
        abs.setAttributeDouble(AbstractMetadata.range_spacing, 10.0);
        abs.setAttributeDouble(AbstractMetadata.azimuth_spacing, 10.0);
        process(src, "ESA", null);                         // original spacing: accepted

        abs.setAttributeDouble(AbstractMetadata.range_spacing, 20.0);
        abs.setAttributeDouble(AbstractMetadata.azimuth_spacing, 20.0);
        try {
            process(src, "ESA", null);
            fail("expected OperatorException");
        } catch (OperatorException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("multilooking"));
        }
    }

    @Test
    public void onePixelEsaBorderIsKept() throws Exception {
        // only the first column is border: the ESA cut of 1 is genuine, unlike a jump cut of 1
        final Product src = createGrdWithBorder("Sentinel-1 IPF 002.84");
        final float[] data = (float[]) src.getBand("Amplitude_VV").getData().getElems();
        for (int y = 0; y < H; y++) {
            for (int x = 1; x < 40; x++) {
                data[y * W + x] = 200f;
            }
        }
        final int[][] cuts = cutsOf(src, "ESA");
        assertTrue("cut " + cuts[0][200], cuts[0][200] >= 1 && cuts[0][200] <= 1 + 1 + 5);
    }

    @Test
    public void tooSmallBorderLimitIsRejected() {
        final RemoveGRDBorderNoiseOp op = (RemoveGRDBorderNoiseOp) spi.createOperator();
        op.setSourceProduct(createGrdWithBorder("Sentinel-1 IPF 002.84"));
        op.setParameter("borderLimit", 5);
        try {
            op.getTargetProduct();
            fail("expected OperatorException");
        } catch (OperatorException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Border margin limit"));
        }
    }

    @Test
    public void prepassFailureIsRecordedOnceAndRethrown() throws Exception {
        // an Error on the detecting thread (e.g. OutOfMemoryError) must be reported by every later tile, not
        // turn into a NullPointerException on the missing cuts
        final Product src = createGrdWithBorder("Sentinel-1 IPF 002.84");
        final java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        src.getBand("Amplitude_VV").setSourceImage(
                new java.awt.image.BufferedImage(W, H, java.awt.image.BufferedImage.TYPE_BYTE_GRAY) {
                    @Override
                    public java.awt.image.Raster getData(final java.awt.Rectangle rect) {
                        reads.incrementAndGet();
                        throw new InjectedError();
                    }
                });
        final Band band = process(src, "Bidirectional", null).getBand("Amplitude_VV");
        for (int k = 0; k < 2; k++) {
            try {
                band.readPixels(300 + k, 200, 1, 1, new float[1], ProgressMonitor.NULL);
                fail("expected the prepass failure, read " + k);
            } catch (Throwable expected) {
                if (expected instanceof AssertionError) {
                    throw (AssertionError) expected;
                }
                assertTrue("read " + k + ": " + expected, messageChainContains(expected, "injected prepass failure"));
            }
        }
        assertEquals("the prepass is not repeated", 1, reads.get());
    }

    private static Product process(final Product src, final String method, final Dimension tileSize) {
        if (tileSize != null) {
            // GPF gives the target the source's tile size; setting it on the target afterwards has no effect
            src.setPreferredTileSize(tileSize);
        }
        final RemoveGRDBorderNoiseOp op = (RemoveGRDBorderNoiseOp) spi.createOperator();
        op.setSourceProduct(src);
        op.setParameter("borderMethod", method);
        op.setParameter("borderLimit", 200);
        final Product trg = op.getTargetProduct();
        if (tileSize != null) {
            assertEquals("target tile size", tileSize, trg.getPreferredTileSize());
        }
        return trg;
    }

    /** Reads the band in rectangles of the given size, not aligned with the tiles. */
    private static float[] readInRectangles(final Band band, final int rw, final int rh) throws Exception {
        final float[] v = new float[W * H];
        for (int y0 = 0; y0 < H; y0 += rh) {
            for (int x0 = 0; x0 < W; x0 += rw) {
                final int w = Math.min(rw, W - x0), h = Math.min(rh, H - y0);
                final float[] part = new float[w * h];
                band.readPixels(x0, y0, w, h, part, ProgressMonitor.NULL);
                for (int y = 0; y < h; y++) {
                    System.arraycopy(part, y * w, v, (y0 + y) * W + x0, w);
                }
            }
        }
        return v;
    }

    private static Band addConstantBand(final Product p, final String name, final String unit, final float value) {
        final Band band = new Band(name, ProductData.TYPE_FLOAT32, W, H);
        band.setUnit(unit);
        final float[] data = new float[W * H];
        java.util.Arrays.fill(data, value);
        band.setData(ProductData.createInstance(data));
        p.addBand(band);
        return band;
    }

    private static MetadataElement imageInformation(final Product p) {
        return AbstractMetadata.getOriginalProductMetadata(p).getElement("annotation").getElementAt(0)
                .getElement("product").getElement("imageAnnotation").getElement("imageInformation");
    }

    private static boolean messageChainContains(final Throwable t, final String text) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (String.valueOf(c.getMessage()).contains(text) || c.toString().contains(text)) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }

    private static final class InjectedError extends Error {
        InjectedError() {
            super("injected prepass failure");
        }
    }

    private static int[][] cutsOf(final Product src, final String method) throws Exception {
        final RemoveGRDBorderNoiseOp op = (RemoveGRDBorderNoiseOp) spi.createOperator();
        op.setSourceProduct(src);
        op.setParameter("borderMethod", method);
        op.setParameter("borderLimit", 200);
        final Product trg = op.getTargetProduct();
        trg.getBand("Amplitude_VV").readPixels(300, 200, 1, 1, new float[1], ProgressMonitor.NULL);
        return op.getCuts();
    }

    private static float[] readAll(final Product p) throws Exception {
        final float[] v = new float[W * H];
        p.getBand("Amplitude_VV").readPixels(0, 0, W, H, v, ProgressMonitor.NULL);
        return v;
    }

    private static Product createGrdWithBorder(final String processingSystem) {
        return createGrdWithBorder(processingSystem, 5f);
    }

    private static Product createGrdWithBorder(final String processingSystem, final float borderValue) {
        return createGrdWithBorder(processingSystem, borderValue, false);
    }

    private static Product createGrdWithBorder(final String processingSystem, final float borderValue,
                                               final boolean darkFirstAndLastLines) {
        final Product p = TestUtils.createProduct("GRD", W, H);
        final Band vv = new Band("Amplitude_VV", ProductData.TYPE_FLOAT32, W, H);
        vv.setUnit(Unit.AMPLITUDE);
        final float[] data = new float[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                float v = x < 40 ? borderValue : 200f;
                if (darkFirstAndLastLines && (y < 20 || y >= H - 20)) {
                    v = Math.min(v, 5f);                                   // dark first/last lines
                }
                if (x >= 300 && x < 340 && y >= 100 && y < 200) {
                    v = 1f;                                                 // calm-water patch
                }
                data[y * W + x] = v;
            }
        }
        vv.setData(ProductData.createInstance(data));
        vv.setNoDataValue(0);
        vv.setNoDataValueUsed(true);
        p.addBand(vv);

        final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(p);
        abs.setAttributeString(AbstractMetadata.MISSION, "SENTINEL-1A");
        abs.setAttributeString(AbstractMetadata.PRODUCT_TYPE, "GRD");
        abs.setAttributeString(AbstractMetadata.ACQUISITION_MODE, "IW");
        abs.setAttributeString(AbstractMetadata.SAMPLE_TYPE, "DETECTED");
        abs.setAttributeString(AbstractMetadata.ProcessingSystemIdentifier, processingSystem);
        abs.setAttributeInt(AbstractMetadata.abs_calibration_flag, 0);

        final MetadataElement orig = AbstractMetadata.addOriginalProductMetadata(p.getMetadataRoot());
        final String name = "s1a-iw-grd-vv-20160101t000000-20160101t000025-000001-000001-001.xml";

        final MetadataElement processingInformation = new MetadataElement("processingInformation");
        processingInformation.setAttributeString("thermalNoiseCorrectionPerformed", "false");
        final MetadataElement imageAnnotation = new MetadataElement("imageAnnotation");
        imageAnnotation.addElement(processingInformation);
        imageAnnotation.addElement(new MetadataElement("imageInformation"));
        final MetadataElement annProduct = new MetadataElement("product");
        annProduct.addElement(imageAnnotation);
        final MetadataElement annDataSet = new MetadataElement(name);
        annDataSet.addElement(annProduct);
        final MetadataElement annotation = new MetadataElement("annotation");
        annotation.addElement(annDataSet);
        orig.addElement(annotation);

        final MetadataElement vectorList = new MetadataElement("noiseVectorList");
        vectorList.setAttributeString("count", "2");
        for (int line : new int[]{0, H - 1}) {
            final MetadataElement pixel = new MetadataElement("pixel");
            pixel.setAttributeString("pixel", "0 " + (W - 1));
            pixel.setAttributeString("count", "2");
            final MetadataElement lut = new MetadataElement("noiseLut");
            lut.setAttributeString("noiseLut", "100 100");
            final MetadataElement vector = new MetadataElement("noiseVector");
            vector.setAttributeString("azimuthTime", "2016-01-01T00:00:00.000000");
            vector.setAttributeString("line", String.valueOf(line));
            vector.addElement(pixel);
            vector.addElement(lut);
            vectorList.addElement(vector);
        }
        final MetadataElement ads = new MetadataElement("adsHeader");
        ads.setAttributeString("polarisation", "VV");
        final MetadataElement noiseAds = new MetadataElement("noise");
        noiseAds.addElement(ads);
        noiseAds.addElement(vectorList);
        final MetadataElement noiseDataSet = new MetadataElement(name);
        noiseDataSet.addElement(noiseAds);
        final MetadataElement noise = new MetadataElement("noise");
        noise.addElement(noiseDataSet);
        orig.addElement(noise);
        return p;
    }
}
