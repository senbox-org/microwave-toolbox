package eu.esa.sar.sar.gpf.geometric;

import eu.esa.sar.commons.test.ProcessorTest;
import eu.esa.sar.commons.test.SARTests;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.engine_utilities.gpf.TestProcessor;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

/**
 * Created by lveci on 24/10/2014.
 */
public class TestALOSDeskew extends ProcessorTest {

    private final static File inputFile = TestData.inputALOS1_1;

    // testProcessing gates itself on inputFile;
    // testProcessAllALOS scans its own roots and skips via TestProcessor.

    private final static OperatorSpi spi = new ALOSDeskewingOp.Spi();
    private final static TestProcessor testProcessor = SARTests.createTestProcessor();

    private String[] exceptionExemptions = {"PALSAR products only"};

    /**
     * Processes a product and compares it to processed product known to be correct
     *
     * @throws Exception general exception
     */
    @Test
    public void testProcessing() throws Exception {
        assumeTrue(inputFile + " not found", inputFile.exists());
        try(final Product sourceProduct = TestUtils.readSourceProduct(inputFile)) {

            final ALOSDeskewingOp op = (ALOSDeskewingOp) spi.createOperator();
            assertNotNull(op);
            op.setSourceProduct(sourceProduct);

            // get targetProduct: execute initialize()
            final Product targetProduct = op.getTargetProduct();
            TestUtils.verifyProduct(targetProduct, true, true, true);

            final float[] expected = new float[]{178303.08f, 33205.94f, -130.6396f};
            TestUtils.comparePixels(targetProduct, targetProduct.getBandAt(0).getName(), 300, 400, expected);
        }
    }

    @Test
    public void testProcessAllALOS() throws Exception {
        testProcessor.testProcessAllInPath(spi, SARTests.rootPathsALOS, "ALOS PALSAR CEOS", null, exceptionExemptions);
    }
}
