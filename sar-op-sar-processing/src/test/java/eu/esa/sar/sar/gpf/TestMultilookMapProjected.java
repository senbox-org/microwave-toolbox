package eu.esa.sar.sar.gpf;

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.CrsGeoCoding;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.junit.Test;

import java.awt.geom.AffineTransform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Multilook must accept <em>map-projected</em> input.
 * <p>
 * Multilooking is spatial averaging over an {@code nRgLooks x nAzLooks} block, which is as well
 * defined on a map grid as in radar geometry. Rejecting map-projected products blocked the
 * geocode-first (GSLC) workflow, where multilooking before phase unwrapping is exactly what makes
 * the unwrap tractable — on a real S1 pair it lifted coherence from 0.23 to 0.67 and removed the
 * need for snaphu tiling altogether.
 * <p>
 * Two things must be right for a map-projected target: the geo-coding must be the source affine with
 * its step scaled by the look factors (not an 11x11 tie-point approximation), and the radar timing
 * annotation must be left alone, since row index carries no acquisition time on a map grid.
 */
public class TestMultilookMapProjected {

    private static final int W = 64, H = 48;
    private static final double LON0 = -68.72330350941797, LAT0 = 10.811992238943352;
    private static final double STEP = 1.2566604374770714e-4;
    private static final String FIRST_LINE_TIME = "23-JUN-2026 22:50:52.310630";
    private static final double SLANT_RANGE_FIRST = 910021.0446979;

    private static Product createGeocodedSARProduct() throws Exception {
        // TestUtils.createProduct supplies the abstracted-metadata skeleton that
        // InputProductValidator.checkIfSARProduct() requires; this test then makes it map projected,
        // which is the property under test.
        final Product p = TestUtils.createProduct("GSLC", W, H);

        final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(p);
        // InputProductValidator.isSARProduct() keys off radar_frequency being present
        AbstractMetadata.setAttribute(abs, AbstractMetadata.radar_frequency, 5405.0);
        AbstractMetadata.setAttribute(abs, AbstractMetadata.MISSION, "SENTINEL-1A");
        AbstractMetadata.setAttribute(abs, AbstractMetadata.SAMPLE_TYPE, "COMPLEX");
        AbstractMetadata.setAttribute(abs, AbstractMetadata.is_terrain_corrected, 1);
        AbstractMetadata.setAttribute(abs, AbstractMetadata.range_spacing, 13.89);
        AbstractMetadata.setAttribute(abs, AbstractMetadata.azimuth_spacing, 13.89);
        AbstractMetadata.setAttribute(abs, AbstractMetadata.range_looks, 1);
        AbstractMetadata.setAttribute(abs, AbstractMetadata.azimuth_looks, 1);
        AbstractMetadata.setAttribute(abs, AbstractMetadata.line_time_interval, 0.002);
        AbstractMetadata.setAttribute(abs, AbstractMetadata.slant_range_to_first_pixel, SLANT_RANGE_FIRST);
        AbstractMetadata.setAttribute(abs, AbstractMetadata.first_line_time,
                AbstractMetadata.parseUTC(FIRST_LINE_TIME));

        p.setSceneGeoCoding(new CrsGeoCoding(DefaultGeographicCRS.WGS84, W, H, LON0, LAT0, STEP, STEP));

        // constant-valued bands: the mean over any block must equal the constant, which makes the
        // averaging itself checkable without modelling the block geometry
        for (final Band b : p.getBands().clone()) {
            p.removeBand(b);
        }
        final float[] iv = new float[W * H];
        final float[] qv = new float[W * H];
        java.util.Arrays.fill(iv, 3.0f);
        java.util.Arrays.fill(qv, 4.0f);
        addBand(p, "i_IW3_VV", Unit.REAL, iv);
        addBand(p, "q_IW3_VV", Unit.IMAGINARY, qv);
        return p;
    }

    private static void addBand(final Product p, final String name, final String unit, final float[] d) {
        final Band b = new Band(name, ProductData.TYPE_FLOAT32, W, H);
        b.setUnit(unit);
        b.setRasterData(ProductData.createInstance(d));
        p.addBand(b);
    }

    private static Product multilook(final Product src, final int rg, final int az) {
        final MultilookOp op = new MultilookOp();
        op.setSourceProduct(src);
        op.setParameter("nRgLooks", rg);
        op.setParameter("nAzLooks", az);
        op.setParameter("grSquarePixel", false);
        op.setParameter("outputIntensity", false);
        return op.getTargetProduct();
    }

    /** The headline: a map-projected product must no longer be rejected. */
    @Test
    public void testAcceptsMapProjectedInput() throws Exception {
        final Product tgt = multilook(createGeocodedSARProduct(), 8, 8);
        assertNotNull(tgt);
        assertEquals("width should be W/nRgLooks", W / 8, tgt.getSceneRasterWidth());
        assertEquals("height should be H/nAzLooks", H / 8, tgt.getSceneRasterHeight());
    }

    /**
     * The target geo-coding must be the source affine with its step scaled by the look factors —
     * exactly, not an interpolated tie-point approximation.
     */
    @Test
    public void testGeoCodingStepScalesExactly() throws Exception {
        final int rg = 8, az = 4;
        final Product tgt = multilook(createGeocodedSARProduct(), rg, az);

        assertTrue("expected an exact CrsGeoCoding on a map-projected target",
                tgt.getSceneGeoCoding() instanceof CrsGeoCoding);
        final CrsGeoCoding gc = (CrsGeoCoding) tgt.getSceneGeoCoding();
        final AffineTransform at = (AffineTransform) gc.getImageToMapTransform();

        assertEquals("X step must scale by nRgLooks", STEP * rg, Math.abs(at.getScaleX()), 1.0e-12);
        assertEquals("Y step must scale by nAzLooks", STEP * az, Math.abs(at.getScaleY()), 1.0e-12);
    }

    /**
     * The target must cover the same ground as the source: the upper-left corner is unchanged and
     * the extent matches to within the pixels dropped by integer division.
     */
    @Test
    public void testGeoCodingCoversTheSameGround() throws Exception {
        final int rg = 8, az = 8;
        final Product src = createGeocodedSARProduct();
        final Product tgt = multilook(src, rg, az);

        // Compare source against target rather than against a literal: the invariant is that both
        // describe the same ground origin. (Note LON0/LAT0 are the first pixel's CENTRE, so the
        // raster corner sits half a source pixel outside them — asserting the literal here would
        // be testing the constructor's convention, not the operator.)
        final AffineTransform srcAt =
                (AffineTransform) ((CrsGeoCoding) src.getSceneGeoCoding()).getImageToMapTransform();
        final AffineTransform tgtAt =
                (AffineTransform) ((CrsGeoCoding) tgt.getSceneGeoCoding()).getImageToMapTransform();

        assertEquals("upper-left easting must be unchanged",
                srcAt.getTranslateX(), tgtAt.getTranslateX(), 1.0e-9);
        assertEquals("upper-left northing must be unchanged",
                srcAt.getTranslateY(), tgtAt.getTranslateY(), 1.0e-9);

        final double srcSpanX = W * STEP;
        final double tgtSpanX = tgt.getSceneRasterWidth() * STEP * rg;
        assertEquals("east-west extent should match", srcSpanX, tgtSpanX, STEP * rg);
    }

    /** Averaging a constant field must return the constant. */
    @Test
    public void testAveragingIsCorrect() throws Exception {
        final Product tgt = multilook(createGeocodedSARProduct(), 8, 8);
        final Band bi = tgt.getBand("i_IW3_VV");
        assertNotNull("i band missing from target", bi);
        final int w = tgt.getSceneRasterWidth(), h = tgt.getSceneRasterHeight();
        final float[] out = new float[w * h];
        bi.readPixels(0, 0, w, h, out);
        for (final float v : out) {
            assertEquals("mean of a constant field must be that constant", 3.0f, v, 1.0e-4f);
        }
    }

    /**
     * Radar timing annotation must be left untouched on a map grid: the row index is not an azimuth
     * line, so advancing first_line_time or the near-edge slant range would corrupt valid metadata.
     */
    @Test
    public void testRadarTimingMetadataUntouchedWhenMapProjected() throws Exception {
        final Product tgt = multilook(createGeocodedSARProduct(), 8, 8);
        final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(tgt);

        assertEquals("slant range to first pixel must not be shifted on a map grid",
                SLANT_RANGE_FIRST, abs.getAttributeDouble(AbstractMetadata.slant_range_to_first_pixel), 1.0e-6);
        assertEquals("line time interval must not be scaled on a map grid",
                0.002, abs.getAttributeDouble(AbstractMetadata.line_time_interval), 1.0e-12);
        assertEquals("first line time must not be advanced on a map grid",
                AbstractMetadata.parseUTC(FIRST_LINE_TIME).getMJD(),
                abs.getAttributeUTC(AbstractMetadata.first_line_time).getMJD(), 1.0e-12);

        // the genuinely spatial annotation SHOULD still be updated
        assertEquals("range spacing should scale with looks",
                13.89 * 8, abs.getAttributeDouble(AbstractMetadata.range_spacing), 1.0e-6);
        assertEquals(1, abs.getAttributeInt(AbstractMetadata.multilook_flag));
    }
}
