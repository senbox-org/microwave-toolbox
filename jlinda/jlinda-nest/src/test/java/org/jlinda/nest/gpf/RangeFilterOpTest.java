package org.jlinda.nest.gpf;

import com.bc.ceres.annotation.STTM;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.datamodel.metadata.AbstractMetadataIO;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import org.junit.Test;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.gpf.StackUtils;
import org.esa.snap.engine_utilities.util.TestUtils;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class RangeFilterOpTest {

    private final static OperatorSpi spi = new RangeFilterOp.Spi();

    /** RangeFilter.sanityChecks rejects an even walking-mean window, so the value set must offer odd values only. */
    @Test
    public void testWalkingMeanValueSetIsOddOnly() throws Exception {
        final org.esa.snap.core.gpf.annotations.Parameter p = parameter("nlMean");
        for (String v : p.valueSet()) {
            assertTrue("nlMean value " + v + " must be odd", Integer.parseInt(v) % 2 == 1);
        }
        assertTrue(Arrays.asList(p.valueSet()).contains(p.defaultValue()));
    }

    @Test
    public void testDeWeightingDefaultIsABoolean() throws Exception {
        assertEquals("false", parameter("doWeightCorrel").defaultValue());
    }

    private static org.esa.snap.core.gpf.annotations.Parameter parameter(final String fieldName) throws Exception {
        final Field f = RangeFilterOp.class.getDeclaredField(fieldName);
        final org.esa.snap.core.gpf.annotations.Parameter p = f.getAnnotation(org.esa.snap.core.gpf.annotations.Parameter.class);
        assertNotNull(p);
        return p;
    }

    @Test
    @STTM("SNAP-3848")
    public void testDualPolInput() throws IOException {

        Product srcProduct = createDualPolProduct();
        RangeFilterOp op = (RangeFilterOp) spi.createOperator();
        op.setSourceProduct(srcProduct);
        Product trgProduct = op.getTargetProduct();

        assertNotNull(trgProduct);
        assertNotNull(trgProduct.getBand("i_IW1_VH_ref_10Jul2018"));
        assertNotNull(trgProduct.getBand("q_IW1_VH_ref_10Jul2018"));
        assertNotNull(trgProduct.getBand("Intensity_IW1_VH_ref_10Jul2018"));
        assertNotNull(trgProduct.getBand("i_IW1_VV_ref_10Jul2018"));
        assertNotNull(trgProduct.getBand("q_IW1_VV_ref_10Jul2018"));
        assertNotNull(trgProduct.getBand("Intensity_IW1_VV_ref_10Jul2018"));

        assertNotNull(trgProduct.getBand("i_IW1_VH_sec1_22Jul2018"));
        assertNotNull(trgProduct.getBand("q_IW1_VH_sec1_22Jul2018"));
        assertNotNull(trgProduct.getBand("Intensity_IW1_VH_sec1_22Jul2018"));
        assertNotNull(trgProduct.getBand("i_IW1_VV_sec1_22Jul2018"));
        assertNotNull(trgProduct.getBand("q_IW1_VV_sec1_22Jul2018"));
        assertNotNull(trgProduct.getBand("Intensity_IW1_VV_sec1_22Jul2018"));
    }

    /**
     * Each output line's walking mean must see its (nlMean-1)/2 neighbours even across a tile edge, so the
     * result cannot depend on the tile height. Before the fix the edge lines of every tile kept the all-pass filter.
     */
    @Test
    public void testOutputIndependentOfTileHeight() throws IOException {
        final float[] oneTile = filterFringe(10, 5, "i_IW1_VV_sec1_22Jul2018");
        final float[] twoTiles = filterFringe(5, 5, "i_IW1_VV_sec1_22Jul2018");
        assertArrayEquals(oneTile, twoTiles, 1e-5f);
        assertArrayEquals(filterFringe(10, 5, "i_IW1_VV_ref_10Jul2018"),
                filterFringe(5, 5, "i_IW1_VV_ref_10Jul2018"), 1e-5f);
    }

    /** A tile with fewer lines than the walking-mean window used to throw IllegalArgumentException. */
    @Test
    public void testTileShorterThanWindow() throws IOException {
        for (float v : filterFringe(10, 15, "i_IW1_VV_sec1_22Jul2018")) {
            assertTrue(Float.isFinite(v));
        }
    }

    private static final int FRINGE_W = 32, FRINGE_H = 10;

    /** Range-filters a single-pol pair with one strong range fringe, tiled at the given height. */
    private static float[] filterFringe(final int tileHeight, final int nlMean, final String band) throws IOException {
        final Product src = TestUtils.createProduct("SLC", FRINGE_W, FRINGE_H);
        addFringeBands(src, "VV_ref_10Jul2018", 3);
        addFringeBands(src, "VV_sec1_22Jul2018", 5);
        AbstractMetadataIO.Load(src, src.getMetadataRoot(), new File("src/test/resources/dualPolMetadata.xml"));
        final MetadataElement secRoot = StackUtils.findSecondaryMetadataRoot(src);
        final MetadataElement[] secondaryElem = secRoot.getElements();
        secRoot.removeElement(secondaryElem[1]);
        secRoot.removeElement(secondaryElem[2]);
        src.setPreferredTileSize(FRINGE_W, tileHeight);

        final RangeFilterOp op = (RangeFilterOp) spi.createOperator();
        op.setSourceProduct(src);
        op.setParameter("nlMean", nlMean);
        final Product trg = op.getTargetProduct();
        assertEquals(tileHeight, trg.getPreferredTileSize().height);
        final float[] out = new float[FRINGE_W * FRINGE_H];
        trg.getBand(band).readPixels(0, 0, FRINGE_W, FRINGE_H, out);
        return out;
    }

    private static void addFringeBands(final Product p, final String suffix, final int cycles) {
        final float[] re = new float[FRINGE_W * FRINGE_H];
        final float[] im = new float[FRINGE_W * FRINGE_H];
        for (int y = 0; y < FRINGE_H; y++) {
            for (int x = 0; x < FRINGE_W; x++) {
                final double phase = 2 * Math.PI * cycles * x / FRINGE_W + 0.3 * y;
                re[y * FRINGE_W + x] = (float) Math.cos(phase);
                im[y * FRINGE_W + x] = (float) Math.sin(phase);
            }
        }
        final Band i = p.addBand("i_IW1_" + suffix, ProductData.TYPE_FLOAT32);
        i.setUnit(Unit.REAL);
        i.setRasterData(ProductData.createInstance(re));
        final Band q = p.addBand("q_IW1_" + suffix, ProductData.TYPE_FLOAT32);
        q.setUnit(Unit.IMAGINARY);
        q.setRasterData(ProductData.createInstance(im));
        ReaderUtils.createVirtualIntensityBand(p, i, q, "");
    }

    /**
     * Create a dummy dual-pol product
     */
    private static Product createDualPolProduct() throws IOException {

        int size = 10;
        Band targetBandI;
        Band targetBandQ;

        final Product testProduct = TestUtils.createProduct("SLC", size, size);
        targetBandI = TestUtils.createBand(testProduct, "i_IW1_VH_ref_10Jul2018", ProductData.TYPE_FLOAT32,
                Unit.REAL, size, size, true);
        targetBandQ = TestUtils.createBand(testProduct, "q_IW1_VH_ref_10Jul2018", ProductData.TYPE_FLOAT32,
                Unit.IMAGINARY, size, size, true);
        ReaderUtils.createVirtualIntensityBand(testProduct, targetBandI, targetBandQ, "");

        targetBandI = TestUtils.createBand(testProduct, "i_IW1_VV_ref_10Jul2018", ProductData.TYPE_FLOAT32,
                Unit.REAL, size, size, true);
        targetBandQ = TestUtils.createBand(testProduct, "q_IW1_VV_ref_10Jul2018", ProductData.TYPE_FLOAT32,
                Unit.IMAGINARY, size, size, true);
        ReaderUtils.createVirtualIntensityBand(testProduct, targetBandI, targetBandQ, "");

        targetBandI = TestUtils.createBand(testProduct, "i_IW1_VH_sec1_22Jul2018", ProductData.TYPE_FLOAT32,
                Unit.REAL, size, size, true);
        targetBandQ = TestUtils.createBand(testProduct, "q_IW1_VH_sec1_22Jul2018", ProductData.TYPE_FLOAT32,
                Unit.IMAGINARY, size, size, true);
        ReaderUtils.createVirtualIntensityBand(testProduct, targetBandI, targetBandQ, "");

        targetBandI = TestUtils.createBand(testProduct, "i_IW1_VV_sec1_22Jul2018", ProductData.TYPE_FLOAT32,
                Unit.REAL, size, size, true);
        targetBandQ = TestUtils.createBand(testProduct, "q_IW1_VV_sec1_22Jul2018", ProductData.TYPE_FLOAT32,
                Unit.IMAGINARY, size, size, true);
        ReaderUtils.createVirtualIntensityBand(testProduct, targetBandI, targetBandQ, "");

        AbstractMetadataIO.Load(testProduct, testProduct.getMetadataRoot(), new File("src/test/resources/dualPolMetadata.xml"));

        final MetadataElement secRoot = StackUtils.findSecondaryMetadataRoot(testProduct);
        final MetadataElement[] secondaryElem = secRoot.getElements();
        secRoot.removeElement(secondaryElem[1]);
        secRoot.removeElement(secondaryElem[2]);

        return testProduct;
    }
}
