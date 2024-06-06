package eu.esa.sar.teststacks.insar;

import eu.esa.sar.commons.test.ProcessorTest;
import eu.esa.sar.commons.test.TestData;
import eu.esa.sar.insar.gpf.InterferogramOp;
import eu.esa.sar.teststacks.coregistration.TestCrossCorrelationCoregistrationStack;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.Product;
import com.bc.ceres.test.LongTestRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.List;

import static org.junit.Assume.assumeTrue;

@RunWith(LongTestRunner.class)
public class TestInterferogram extends ProcessorTest {

    private final static File asarSantoriniFolder = new File(TestData.inputSAR + "ASAR/Santorini");

    @Before
    public void setUp() {
        // If any of the file does not exist: the test will be ignored
        assumeTrue(asarSantoriniFolder + " not found", asarSantoriniFolder.exists());
    }

    @Test
    public void testStack1() throws Exception {
        final List<Product> products = readProducts(asarSantoriniFolder);

        Product coregisteredStack = TestCrossCorrelationCoregistrationStack.coregister(products);

        InterferogramOp interferogram = new InterferogramOp();
        interferogram.setSourceProduct(coregisteredStack);

        Product trgProduct = interferogram.getTargetProduct();

        File tmpFolder = createTmpFolder("stack1");
        ProductIO.writeProduct(trgProduct, new File(tmpFolder,"stack.dim"), "BEAM-DIMAP", true);

        trgProduct.close();
        for(Product product : products) {
            product.close();
        }
        delete(tmpFolder);
    }
}
