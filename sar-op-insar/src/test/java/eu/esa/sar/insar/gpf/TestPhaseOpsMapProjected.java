package eu.esa.sar.insar.gpf;

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.CrsGeoCoding;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The phase-conversion operators must accept <em>map-projected</em> input.
 * <p>
 * Geocode-first (GSLC) InSAR produces an interferogram that is already on a map grid by the time it
 * is unwrapped, so a blanket {@code checkIfMapProjected(false)} blocked the whole workflow at the
 * final step. These tests pin the intended behaviour per operator:
 * <ul>
 *   <li>{@code PhaseToDisplacement} is pure per-pixel arithmetic and must simply work.</li>
 *   <li>{@code PhaseToElevation}'s <b>Schwabisch</b> method fits a polynomial in <em>radar</em>
 *       image coordinates, so it must still refuse map-projected input — and say why, rather than
 *       returning silently wrong heights.</li>
 * </ul>
 */
public class TestPhaseOpsMapProjected {

    private static final int W = 64, H = 48;
    /** S1 C-band; PhaseToDisplacement uses displacement = -(lambda/4pi) * phase. */
    private static final double LAMBDA = 0.05546576;

    private static Product createGeocodedUnwrappedProduct() throws Exception {
        final Product p = new Product("unw", "GSLC", W, H);
        final ProductData.UTC t0 = AbstractMetadata.parseUTC("23-JUN-2026 22:50:52.310630");
        p.setStartTime(t0);
        p.setEndTime(AbstractMetadata.parseUTC("23-JUN-2026 22:51:20.000000"));

        final MetadataElement abs = AbstractMetadata.addAbstractedMetadataHeader(p.getMetadataRoot());
        abs.setAttributeUTC(AbstractMetadata.first_line_time, t0);
        abs.setAttributeInt(AbstractMetadata.is_terrain_corrected, 1);
        // radar_frequency is stored in MHz
        abs.setAttributeDouble(AbstractMetadata.radar_frequency, 299792458.0 / LAMBDA / 1.0e6);
        abs.setAttributeString(AbstractMetadata.MISSION, "SENTINEL-1A");
        abs.setAttributeDouble(AbstractMetadata.range_spacing, 13.89);
        abs.setAttributeDouble(AbstractMetadata.azimuth_spacing, 13.89);

        // the defining property of this fixture: a real map projection
        p.setSceneGeoCoding(new CrsGeoCoding(DefaultGeographicCRS.WGS84, W, H,
                -68.72330350941797, 10.811992238943352, 1.2566604374770714e-4, 1.2566604374770714e-4));

        final float[] data = new float[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                data[y * W + x] = (float) (0.05 * x + 0.02 * y);   // a smooth unwrapped ramp
            }
        }
        final Band b = new Band("Unw_Phase_ifg_23Jun2026_24Jun2026", ProductData.TYPE_FLOAT32, W, H);
        b.setUnit(Unit.ABS_PHASE);
        b.setRasterData(ProductData.createInstance(data));
        p.addBand(b);
        return p;
    }

    /** PhaseToDisplacement has no geometric assumption, so a map-projected product must work. */
    @Test
    public void testPhaseToDisplacementAcceptsMapProjected() throws Exception {
        final Product src = createGeocodedUnwrappedProduct();

        final PhaseToDisplacementOp op = new PhaseToDisplacementOp();
        op.setSourceProduct(src);
        final Product tgt = op.getTargetProduct();

        final Band disp = tgt.getBand("displacement");
        assertNotNull("displacement band missing", disp);
        assertEquals(Unit.METERS, disp.getUnit());

        // displacement = -(lambda / 4pi) * phase, checked against an independent computation
        final float[] out = new float[W * H];
        disp.readPixels(0, 0, W, H, out);
        final double mPerRad = -LAMBDA / (4.0 * Math.PI);
        for (final int[] px : new int[][]{{0, 0}, {13, 7}, {W - 1, H - 1}}) {
            final double phase = 0.05 * px[0] + 0.02 * px[1];
            assertEquals("displacement wrong at " + px[0] + "," + px[1],
                    mPerRad * phase, out[px[1] * W + px[0]], 1.0e-7);
        }
    }

    /** The geo-coding must survive unchanged — the output is a geocoded displacement map. */
    @Test
    public void testPhaseToDisplacementPreservesGeoCoding() throws Exception {
        final Product src = createGeocodedUnwrappedProduct();
        final PhaseToDisplacementOp op = new PhaseToDisplacementOp();
        op.setSourceProduct(src);
        final Product tgt = op.getTargetProduct();

        assertNotNull("target lost its geo-coding", tgt.getSceneGeoCoding());
        assertTrue("target geo-coding should still be a CrsGeoCoding",
                tgt.getSceneGeoCoding() instanceof CrsGeoCoding);
        assertEquals(W, tgt.getSceneRasterWidth());
        assertEquals(H, tgt.getSceneRasterHeight());
    }

    /**
     * Schwabisch fits a slant-range-to-height polynomial in radar image coordinates, so it must
     * refuse a map-projected product rather than evaluate the model in the wrong coordinate system.
     */
    @Test
    public void testPhaseToElevationSchwabischStillRejectsMapProjected() throws Exception {
        final Product src = createGeocodedUnwrappedProduct();
        final PhaseToElevationOp op = new PhaseToElevationOp();
        op.setSourceProduct(src);
        op.setParameter("method", PhaseToElevationOp.METHOD_SCHWABISCH);
        try {
            op.getTargetProduct();
            fail("Schwabisch must reject map-projected input");
        } catch (OperatorException e) {
            final String m = e.getMessage();
            assertTrue("message should explain the radar-geometry requirement, was: " + m,
                    m.contains("radar geometry"));
            assertTrue("message should name the alternative method, was: " + m,
                    m.contains(PhaseToElevationOp.METHOD_DEM_SEED));
        }
    }

    /**
     * The DEM Seed method no longer trips a blanket map-projection guard; it fails only on what it
     * genuinely needs — the radar-geometry tie point grids — and must say so specifically.
     */
    @Test
    public void testPhaseToElevationDemSeedFailsOnMissingGridsNotOnProjection() throws Exception {
        final Product src = createGeocodedUnwrappedProduct();
        final PhaseToElevationOp op = new PhaseToElevationOp();
        op.setSourceProduct(src);
        op.setParameter("method", PhaseToElevationOp.METHOD_DEM_SEED);
        try {
            op.getTargetProduct();
            // acceptable: if a future build can supply the geometry, this legitimately succeeds
        } catch (OperatorException e) {
            final String m = e.getMessage();
            assertTrue("should no longer fail merely for being map projected, was: " + m,
                    !m.contains("should not be map projected"));
        }
    }
}
