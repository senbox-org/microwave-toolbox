package eu.esa.sar.sar.gpf.geometric;

import eu.esa.sar.commons.test.ProcessorTest;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.esa.snap.core.datamodel.ProductData;

import org.esa.snap.engine_utilities.datamodel.Unit;

import static org.junit.Assert.assertNull;

public class GSLCGeocodingOpTest extends ProcessorTest {

    private GSLCGeocodingOp op;
    private final static OperatorSpi spi = new GSLCGeocodingOp.Spi();
    private final static File inputFile1 = TestData.inputS1_StripmapSLC;
    private final static File inputFile2 = TestData.inputCapella_StripmapSLC;

    @Before
    public void setUp() {
        op = new GSLCGeocodingOp();
    }

    @Test
    public void testConstruction() {
        assertNotNull(op);
    }

    @Test(expected = OperatorException.class)
    public void testInitializeWithoutSourceProduct() {
        op.initialize();
    }

    /**
     * The output grid's metadata spacing must be the real GROUND step. The Campi Flegrei GSLC used
     * 2.1110409e-5 deg x 1.2531498e-4 deg at 40.80 N: 1.779 m east x 13.95 m north. The nominal lattice
     * step (2.35 m, i.e. the east step converted at the equator) was written instead, which made a
     * "100 m" coherence window ~76 m east-west.
     */
    @Test
    public void testOutputGroundSpacingAtSceneLatitude() {
        final double[] cf = GSLCGeocodingOp.outputGroundSpacing(2.1110409176808754E-5, 1.2531498213467324E-4, 40.7977);
        assertEquals(1.779, cf[0], 0.001);
        assertEquals(13.950, cf[1], 0.001);
        final double[] eq = GSLCGeocodingOp.outputGroundSpacing(2.1110409176808754E-5, 1.2531498213467324E-4, 0.0);
        assertEquals(2.350, eq[0], 0.001);                 // at the equator east equals the nominal step
        final double[] south = GSLCGeocodingOp.outputGroundSpacing(2.1110409176808754E-5, 1.2531498213467324E-4, -40.7977);
        assertEquals(cf[0], south[0], 1e-12);              // symmetric in latitude
    }

    /** East/north ground step of a map product measured from its own geocoding at the image centre. */
    private static double[] measuredGroundStep(final Product p) {
        final double x = p.getSceneRasterWidth() / 2.0 + 0.5, y = p.getSceneRasterHeight() / 2.0 + 0.5;
        final GeoPos c = p.getSceneGeoCoding().getGeoPos(new PixelPos(x, y), null);
        final GeoPos e = p.getSceneGeoCoding().getGeoPos(new PixelPos(x + 1, y), null);
        final GeoPos n = p.getSceneGeoCoding().getGeoPos(new PixelPos(x, y + 1), null);
        final double mPerDeg = org.esa.snap.engine_utilities.eo.Constants.semiMajorAxis * Math.PI / 180.0;
        return new double[]{Math.abs(e.lon - c.lon) * mPerDeg * Math.cos(Math.toRadians(c.lat)),
                Math.abs(n.lat - c.lat) * mPerDeg};
    }

    @Test
    public void testProcessS1Stripmap() throws Exception {
        assumeTrue(inputFile1 + " not found", inputFile1.exists());
        try(final Product sourceProduct = TestUtils.readSourceProduct(inputFile1)) {

            final GSLCGeocodingOp op = (GSLCGeocodingOp) spi.createOperator();
            assertNotNull(op);
            op.setSourceProduct(sourceProduct);
            op.setParameter("demName", "SRTM 3Sec");
            op.setParameter("imgResamplingMethod", "BILINEAR_INTERPOLATION");

            // get targetProduct: execute initialize()
            final Product targetProduct = op.getTargetProduct();
            TestUtils.verifyProduct(targetProduct, true, true, true);

            // metadata spacing = the real ground step of the output grid (1% for the scene-centre
            // latitude vs the image-centre latitude)
            final org.esa.snap.core.datamodel.MetadataElement abs =
                    org.esa.snap.engine_utilities.datamodel.AbstractMetadata.getAbstractedMetadata(targetProduct);
            final double[] step = measuredGroundStep(targetProduct);
            assertEquals(step[0], abs.getAttributeDouble("range_spacing"), 0.01 * step[0]);
            assertEquals(step[1], abs.getAttributeDouble("azimuth_spacing"), 0.01 * step[1]);

            // Check if complex bands are present (ASAR IMS typically has 'i' and 'q' bands)
            Band iBand = targetProduct.getBand("i");
            if (iBand == null) {
                iBand = targetProduct.getBand("i_VV");
            }
            assertNotNull("Real band (i or i_VV) not found", iBand);
            
            Band qBand = targetProduct.getBand("q");
            if (qBand == null) {
                qBand = targetProduct.getBand("q_VV");
            }
            assertNotNull("Imaginary band (q or q_VV) not found", qBand);
        }
    }

    @Test
    public void testProcessCapellaStripmap() throws Exception {
        assumeTrue(inputFile2 + " not found", inputFile2.exists());
        try(final Product sourceProduct = TestUtils.readSourceProduct(inputFile2)) {

            final GSLCGeocodingOp op = (GSLCGeocodingOp) spi.createOperator();
            assertNotNull(op);
            op.setSourceProduct(sourceProduct);
            op.setParameter("demName", "SRTM 3Sec");
            op.setParameter("imgResamplingMethod", "BILINEAR_INTERPOLATION");

            // get targetProduct: execute initialize()
            final Product targetProduct = op.getTargetProduct();
            TestUtils.verifyProduct(targetProduct, true, true, true);

            // metadata spacing = the real ground step of the output grid (1% for the scene-centre
            // latitude vs the image-centre latitude)
            final org.esa.snap.core.datamodel.MetadataElement abs =
                    org.esa.snap.engine_utilities.datamodel.AbstractMetadata.getAbstractedMetadata(targetProduct);
            final double[] step = measuredGroundStep(targetProduct);
            assertEquals(step[0], abs.getAttributeDouble("range_spacing"), 0.01 * step[0]);
            assertEquals(step[1], abs.getAttributeDouble("azimuth_spacing"), 0.01 * step[1]);

            // Check if complex bands are present (ASAR IMS typically has 'i' and 'q' bands)
            Band iBand = targetProduct.getBand("i_HH");
            assertNotNull("Real band (i_HH) not found", iBand);

            Band qBand = targetProduct.getBand("q_HH");
            assertNotNull("Imaginary band (q_HH) not found", qBand);
        }
    }

    /**
     * Two GSLCs of the same scene, run with default parameters, must land on a grid
     * compatible with InSAR stacking: same pixel size, and the pixel corner offsets between
     * them are integer multiples of the pixel size in both axes (so master pixel
     * {@code (i,j)} maps to slave pixel {@code (i+dx, j+dy)} for integer {@code dx,dy}).
     * That's the property the always-on standard-grid alignment guarantees, and it's
     * what CreateStack relies on instead of a user-supplied reference product.
     */
    @Test
    public void testTwoGSLCs_AutoAlignedByStandardGrid() throws Exception {
        assumeTrue(inputFile2 + " not found", inputFile2.exists());
        try (final Product sourceProduct = TestUtils.readSourceProduct(inputFile2)) {
            final Product g1 = runDefaultGslc(sourceProduct);
            final Product g2 = runDefaultGslc(sourceProduct);

            assertEquals("same pixel-X size",
                    g1.getSceneGeoCoding().getGeoPos(new PixelPos(1.5, 0.5), null).lon
                            - g1.getSceneGeoCoding().getGeoPos(new PixelPos(0.5, 0.5), null).lon,
                    g2.getSceneGeoCoding().getGeoPos(new PixelPos(1.5, 0.5), null).lon
                            - g2.getSceneGeoCoding().getGeoPos(new PixelPos(0.5, 0.5), null).lon,
                    1e-12);

            // For the same ground point, the difference between the two grids' pixel
            // positions must be an integer in both axes — that's the property that lets
            // CreateStack do an exact integer-pixel mapping with no resampling.
            final GeoPos anchor = g1.getSceneGeoCoding().getGeoPos(new PixelPos(10.5, 15.5), null);
            final PixelPos g1pp = new PixelPos();
            final PixelPos g2pp = new PixelPos();
            g1.getSceneGeoCoding().getPixelPos(anchor, g1pp);
            g2.getSceneGeoCoding().getPixelPos(anchor, g2pp);
            final double diffX = g2pp.x - g1pp.x;
            final double diffY = g2pp.y - g1pp.y;
            final double fracX = diffX - Math.round(diffX);
            final double fracY = diffY - Math.round(diffY);
            assertTrue("offset between the two GSLC grids must be integer pixels " +
                    "(got fracX=" + fracX + ", fracY=" + fracY + ")",
                    Math.abs(fracX) < 1e-6 && Math.abs(fracY) < 1e-6);
        }
    }

    private static Product runDefaultGslc(final Product source) {
        final GSLCGeocodingOp op = (GSLCGeocodingOp) spi.createOperator();
        op.setSourceProduct(source);
        op.setParameter("demName", "SRTM 3Sec");
        op.setParameter("imgResamplingMethod", "BILINEAR_INTERPOLATION");
        op.setParameter("nodataValueAtSea", false);
        return op.getTargetProduct();
    }
    /**
     * The separable phase terms must be present and declared as phase in float64.
     * <p>
     * These bands exist so the product's phase convention is REVERSIBLE rather than baked in: a
     * consumer multiplies by exp(-j*phase) to remove a term or exp(+j*phase) to restore it. That is
     * only usable if the bands carry enough precision — the flattening phase is 4*pi*R/lambda with
     * R ~ 9e5 m, so float32 quantisation there is ~13 rad and would make the term useless. This test
     * pins the contract (presence, unit, dtype); the numerical agreement with ISCE3's
     * carrierPhaseRaster / flattenPhaseRaster is Phase 1a of the cross-tool validation spec.
     */
    @Test
    public void testSeparablePhaseTermsContract() throws Exception {
        assumeTrue(inputFile1 + " not found", inputFile1.exists());

        final Product src = TestUtils.readSourceProduct(inputFile1);
        final GSLCGeocodingOp op = new GSLCGeocodingOp();
        op.setSourceProduct(src);
        op.setParameter("outputPhaseTerms", true);
        op.setParameter("nodataValueAtSea", false);
        final Product tgt = op.getTargetProduct();

        for (final String name : new String[]{"azimuthCarrierPhase", "flatteningPhase"}) {
            final Band b = tgt.getBand(name);
            assertNotNull("separable phase term band missing: " + name, b);
            assertEquals("band " + name + " must be tagged as phase", Unit.PHASE, b.getUnit());
            assertEquals("band " + name + " must be float64 — float32 is ~13 rad at these magnitudes",
                    ProductData.TYPE_FLOAT64, b.getDataType());
        }

        // the interpolation kernel must be stamped so CreateStack builds the secondary with the
        // SAME kernel as the reference (asymmetric kernels decorrelate the legs)
        assertEquals("BISINC_5_POINT_INTERPOLATION",
                org.esa.snap.engine_utilities.datamodel.AbstractMetadata.getAbstractedMetadata(tgt)
                        .getAttributeString("gslc_img_resampling", null));

        // Default true (InSAR-ready convention: InterferogramOp's exact carrier-difference
        // subtraction needs the bands on both stack legs) — but explicitly opt-out-able.
        final GSLCGeocodingOp byDefault = new GSLCGeocodingOp();
        byDefault.setSourceProduct(TestUtils.readSourceProduct(inputFile1));
        byDefault.setParameter("nodataValueAtSea", false);
        assertNotNull("phase-term bands are on by default (InSAR-ready convention)",
                byDefault.getTargetProduct().getBand("azimuthCarrierPhase"));

        final GSLCGeocodingOp plain = new GSLCGeocodingOp();
        plain.setSourceProduct(TestUtils.readSourceProduct(inputFile1));
        plain.setParameter("outputPhaseTerms", false);
        plain.setParameter("nodataValueAtSea", false);
        final Product plainTgt = plain.getTargetProduct();
        assertNull("phase-term bands must honour opt-out", plainTgt.getBand("azimuthCarrierPhase"));
        assertNull("phase-term bands must honour opt-out", plainTgt.getBand("flatteningPhase"));
    }
}
