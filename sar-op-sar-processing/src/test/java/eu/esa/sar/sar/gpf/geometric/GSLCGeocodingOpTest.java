package eu.esa.sar.sar.gpf.geometric;

import eu.esa.sar.commons.test.ProcessorTest;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

public class GSLCGeocodingOpTest extends ProcessorTest {

    private GSLCGeocodingOp op;
    private final static OperatorSpi spi = new GSLCGeocodingOp.Spi();
    private final static File inputFile1 = TestData.inputS1_StripmapSLC;
    private final static File inputFile2 = TestData.inputCapella_StripmapSLC;

    @Before
    public void setUp() throws Exception {
        op = new GSLCGeocodingOp();
        try {
            // If any of the file does not exist: the test will be ignored
            assumeTrue(inputFile1 + " not found", inputFile1.exists());
            assumeTrue(inputFile2 + " not found", inputFile2.exists());
        } catch (Exception e) {
            TestUtils.skipTest(this, e.getMessage());
            throw e;
        }
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
}
