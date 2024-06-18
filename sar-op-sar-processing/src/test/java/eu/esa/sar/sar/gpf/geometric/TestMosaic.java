package eu.esa.sar.sar.gpf.geometric;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.test.ProcessorTest;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.dataop.resamp.ResamplingFactory;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

/**
 * Created by lveci on 24/10/2014.
 */
public class TestMosaic extends ProcessorTest {

    private final static OperatorSpi spi = new MosaicOp.Spi();

    private final static File inputFile1 = TestData.inputASAR_IMM;
    private final static File inputFile2 = TestData.inputASAR_IMMSub;

    @Before
    public void setUp() throws Exception {
        try {
            // If any of the file does not exist: the test will be ignored
            assumeTrue(inputFile1 + " not found", inputFile1.exists());
            assumeTrue(inputFile2 + " not found", inputFile2.exists());
        } catch (Exception e) {
            TestUtils.skipTest(this, e.getMessage());
            throw e;
        }
    }

    /**
     * Processes a product and compares it to processed product known to be correct
     *
     * @throws Exception general exception
     */
    @Test
    @Ignore
    public void testAllOff() throws Exception {
        final float[] expected = new float[] { 1288.0f, 1241.0f, 1309.0f, 1279.0f };
        process(false, false, false, expected);
    }

    @Test
    public void testAverage() throws Exception {
        final float[] expected = new float[] { 1238.0f, 1202.0f, 1303.0f, 1254.0f };
        process(true, false, false, expected);
    }

    @Test
    public void testAverageNormalized() throws Exception {
        final float[] expected = new float[] { 1.2861735f, 1.1919507f, 1.4611204f, 1.3296762f };
        process(true, true, false, expected);
    }

    @Test
    @Ignore
    public void testGradientDomain() throws Exception {
        final float[] expected = new float[] { 1287.7158f, 1240.6554f, 1308.471f, 1279.0542f };
        process(false, false, true, expected);
    }

    private void process(boolean average, boolean normalizeByMean, boolean gradientDomain, final float[] expected) throws Exception {

        try(final Product sourceProduct1 = TestUtils.readSourceProduct(inputFile1)) {
            try (final Product sourceProduct2 = TestUtils.readSourceProduct(inputFile2)) {

                final MosaicOp op = (MosaicOp) spi.createOperator();
                assertNotNull(op);
                op.setSourceProducts(sourceProduct1, sourceProduct2);
                op.setParameter("average", average);
                op.setParameter("normalizeByMean", normalizeByMean);
                op.setParameter("gradientDomainMosaic", gradientDomain);
                op.setParameter("resamplingMethod", ResamplingFactory.BILINEAR_INTERPOLATION_NAME);

                // get targetProduct: execute initialize()
                final Product targetProduct = op.getTargetProduct();
                TestUtils.verifyProduct(targetProduct, false, true, true);

                final Band band = targetProduct.getBandAt(0);
                assertNotNull(band);

                // readPixels gets computeTiles to be executed
                final float[] floatValues = new float[4];
                band.readPixels(1000, 1000, 2, 2, floatValues, ProgressMonitor.NULL);

                // compare with expected outputs:
                assertArrayEquals(Arrays.toString(floatValues), expected, floatValues, 0.0001f);
            }
        }
    }
}
