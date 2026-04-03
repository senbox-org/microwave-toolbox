package eu.esa.sar.sar.gpf.geometric;

import com.bc.ceres.annotation.STTM;
import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.test.ProcessorTest;
import eu.esa.sar.commons.test.SARTests;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.GeoCoding;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.engine_utilities.gpf.TestProcessor;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

/**
 * Created by lveci on 24/10/2014.
 */
public class TestUpdateGeoRef extends ProcessorTest {

    private final static File inputFile = TestData.inputASAR_WSM;

    @Before
    public void setUp() throws Exception {
        try {
            // If the file does not exist: the test will be ignored
            assumeTrue(inputFile + " not found", inputFile.exists());
        } catch (Exception e) {
            TestUtils.skipTest(this, e.getMessage());
            throw e;
        }
    }

    private final static OperatorSpi spi = new UpdateGeoRefOp.Spi();

    private String[] exceptionExemptions = {};

    /**
     * Processes a product and compares it to processed product known to be correct
     *
     * @throws Exception general exception
     */
    @Test
    @STTM("SNAP-3719")
    public void testProcessing() throws Exception {
        try(final Product sourceProduct = TestUtils.readSourceProduct(inputFile)) {

            final UpdateGeoRefOp op = (UpdateGeoRefOp) spi.createOperator();
            assertNotNull(op);
            op.setSourceProduct(sourceProduct);

            // get targetProduct: execute initialize()
            final Product targetProduct = op.getTargetProduct();
            TestUtils.verifyProduct(targetProduct, true, true, true);

            final Band band = targetProduct.getBandAt(0);
            assertNotNull(band);

            // readPixels gets computeTiles to be executed
            final float[] floatValues = new float[4];
            band.readPixels(0, 0, 2, 2, floatValues, ProgressMonitor.NULL);

            // compare with expected outputs:
            final float[] expected = new float[]{446224.0f, 318096.0f, 403225.0f, 330625.0f};
            assertArrayEquals(Arrays.toString(floatValues), expected, floatValues, 0.0001f);

            final GeoCoding geoCoding = targetProduct.getSceneGeoCoding();
            final GeoPos geoPos = geoCoding.getGeoPos(new PixelPos(100, 100), null);
            //assertEquals(46.72579102050234, geoPos.getLat(), 0.00001);
            //assertEquals(10.359693240977476, geoPos.getLon(), 0.00001);
        }
    }

    @Test
    public void testProcessAllALOS() throws Exception {
        TestProcessor testProcessor = SARTests.createTestProcessor();
        testProcessor.testProcessAllInPath(spi, SARTests.rootPathsALOS, "ALOS PALSAR CEOS", null, exceptionExemptions);
    }
}
