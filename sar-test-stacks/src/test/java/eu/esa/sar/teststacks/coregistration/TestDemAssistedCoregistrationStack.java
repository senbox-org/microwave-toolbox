package eu.esa.sar.teststacks.coregistration;

import eu.esa.sar.commons.test.ProcessorTest;
import eu.esa.sar.commons.test.TestData;
import eu.esa.sar.insar.gpf.coregistration.DEMAssistedCoregistrationOp;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.Product;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.List;

import static org.junit.Assume.assumeTrue;

public class TestDemAssistedCoregistrationStack extends ProcessorTest {

    private final static File asarSantoriniFolder = new File(TestData.inputSAR + "ASAR/Santorini");

    @Before
    public void setUp() {
        // If any of the file does not exist: the test will be ignored
        assumeTrue(asarSantoriniFolder + " not found", asarSantoriniFolder.exists());
    }

    @Test
    public void testStack1() throws Exception {
        final List<Product> products = readProducts(asarSantoriniFolder);

        DEMAssistedCoregistrationOp demAssistedCoregistration = new DEMAssistedCoregistrationOp();
        int cnt = 0;
        for(Product product : products) {
            demAssistedCoregistration.setSourceProduct("input"+cnt, product);
            ++cnt;
        }

        Product trgProduct = demAssistedCoregistration.getTargetProduct();

        File tmpFolder = createTmpFolder("stack1");
        ProductIO.writeProduct(trgProduct, new File(tmpFolder,"stack.dim"), "BEAM-DIMAP", true);

        trgProduct.close();
        for(Product product : products) {
            product.close();
        }
        delete(tmpFolder);
    }
}
