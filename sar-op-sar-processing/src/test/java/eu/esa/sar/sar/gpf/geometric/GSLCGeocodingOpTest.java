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

        // absent by default, so existing products and graphs are unchanged
        final GSLCGeocodingOp plain = new GSLCGeocodingOp();
        plain.setSourceProduct(TestUtils.readSourceProduct(inputFile1));
        plain.setParameter("nodataValueAtSea", false);
        final Product plainTgt = plain.getTargetProduct();
        assertNull("phase-term bands must be opt-in", plainTgt.getBand("azimuthCarrierPhase"));
        assertNull("phase-term bands must be opt-in", plainTgt.getBand("flatteningPhase"));
    }
}
