package eu.esa.sar.insar.gpf;

import com.bc.ceres.annotation.STTM;
import eu.esa.sar.commons.test.ProcessorTest;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

@STTM("SNAP-3748")
public class TestInSAROverviewOp extends ProcessorTest {

    public final static File santorini_3 = new File(TestData.inputSAR + "ASAR/Santorini/subset_3_of_ASA_IMS_1PNUPA20040225_200558_000000162024_00329_10403_7640.dim");
    public final static File santorini_4 = new File(TestData.inputSAR + "ASAR/Santorini/subset_4_of_ASA_IMS_1PNUPA20040331_200601_000000162025_00329_10904_7641.dim");
    public final static File santorini_5 = new File(TestData.inputSAR + "ASAR/Santorini/subset_5_of_ASA_IMS_1PNUPA20040714_200605_000000162028_00329_12407_7642.dim");
    public final static File santorini_6 = new File(TestData.inputSAR + "ASAR/Santorini/subset_6_of_ASA_IMS_1PNUPA20041027_200604_000000162031_00329_13910_7643.dim");

    public final static File[] santorini_files = new File[] { santorini_3, santorini_4, santorini_5, santorini_6 };
    private final static File inputIMS = TestData.inputStackIMS;
    private final static File inputGRD = TestData.inputS1_GRD;

    private final File outputFile = new File("/tmp/overview.json");

    @Before
    public void setUp() {
        // If the file does not exist: the test will be ignored
        assumeTrue(santorini_3 + " not found", santorini_3.exists());
    }

    private final static OperatorSpi spi = new InSAROverviewOp.Spi();

    @Test
    public void testFileList() throws Exception {
        List<Product> products = readProducts(santorini_files);
        process(products);

        assumeTrue(outputFile.exists());
        outputFile.delete();
    }

    @Test
    public void testJSON() throws Exception {
        List<Product> products = readProducts(santorini_files);
        JSONObject json = InSAROverviewOp.produceInSAROverview(products.toArray(new Product[0]));

        System.out.println(json.toJSONString());
        assert(json.containsKey("reference"));
        assert(json.containsKey("secondary"));

        JSONObject reference = (JSONObject) json.get("reference");
        assert(reference.containsKey("product_name"));
        assert(reference.containsKey("file"));
        assert(reference.containsKey("start_time"));
        assert(reference.containsKey("pass"));
        assert(reference.containsKey("orbit_state_vector_file"));

        JSONArray secondaries = (JSONArray) json.get("secondary");
        for(Object o : secondaries) {
            JSONObject secondary = (JSONObject) o;
            assert(secondary.containsKey("product_name"));
            assert(secondary.containsKey("file"));
            assert(secondary.containsKey("start_time"));

            JSONObject overview = (JSONObject) secondary.get("insar_overview");
            assert(overview.containsKey("coherence"));
            assert(overview.containsKey("perpendicular_baseline_m"));
            assert(overview.containsKey("temporal_baseline_days"));
            assert(overview.containsKey("height_of_ambiguity_m"));
            assert(overview.containsKey("doppler_difference_hz"));
        }
    }

    @Test
    public void testSingleFile() throws Exception {
        List<Product> products = readProducts(new File[] {inputIMS});
        Exception exception = assertThrows(Exception.class, ()->process(products));
        assertEquals("Please add a list of source products of two or more products", exception.getMessage());
    }

    @Test
    public void testGRDFiles() throws Exception {
        List<Product> products = readProducts(new File[] {inputGRD, inputGRD});
        Exception exception = assertThrows(Exception.class, ()->process(products));
        assertEquals("Input should be a Single Look Complex(SLC) product.", exception.getMessage());
    }

    @Test
    public void testNullSourceProduct() {
        List<Product> products = new ArrayList<>();
        products.add(null);
        Exception exception = assertThrows(Exception.class, ()->process(products));
        assertEquals("Please add a list of source products of two or more products", exception.getMessage());
    }

    public void process(final List<Product> products) throws Exception {

        final InSAROverviewOp op = (InSAROverviewOp) spi.createOperator();
        assertNotNull(op);
        op.setSourceProducts(products.toArray(new Product[0]));
        op.setParameter("overviewJSONFile", outputFile.getPath());

        // get targetProduct: execute initialize()
        final Product targetProduct = op.getTargetProduct();
        TestUtils.verifyProduct(targetProduct, true, true, true);
    }
}
