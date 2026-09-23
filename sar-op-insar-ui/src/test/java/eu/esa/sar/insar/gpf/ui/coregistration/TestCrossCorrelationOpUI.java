package eu.esa.sar.insar.gpf.ui.coregistration;

import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * "Apply Fine Registration for SLCs" must be enabled for any complex product.
 * It was grayed out whenever the parameter map had no 'applyFineRegistration' entry: the
 * unguarded unboxing threw an NPE out of initParameters(), leaving the panel in the state
 * createPanel() built it (isComplex still false). PropertySet.setDefaultValues() never writes
 * a primitive boolean whose default is false, so an operator dialog started from defaults hit
 * this on every SLC.
 */
public class TestCrossCorrelationOpUI {

    @Before
    public void setUp() {
        assumeTrue(TestData.inputASAR_IMS.exists());
    }

    /** What CoregistrationGraph.xml carries for the Cross-Correlation node. */
    private static Map<String, Object> graphParams() {
        final Map<String, Object> m = new HashMap<>();
        m.put("numGCPtoGenerate", 2000);
        m.put("coarseRegistrationWindowWidth", "128");
        m.put("coarseRegistrationWindowHeight", "128");
        m.put("rowInterpFactor", "4");
        m.put("columnInterpFactor", "4");
        m.put("maxIteration", 10);
        m.put("gcpTolerance", 0.25);
        m.put("applyFineRegistration", true);
        m.put("fineRegistrationWindowWidth", "32");
        m.put("fineRegistrationWindowHeight", "32");
        m.put("fineRegistrationWindowAccAzimuth", "16");
        m.put("fineRegistrationWindowAccRange", "16");
        m.put("fineRegistrationOversampling", "16");
        m.put("coherenceWindowSize", 3);
        m.put("coherenceThreshold", 0.6);
        m.put("useSlidingWindow", false);
        m.put("inSAROptimized", true);
        m.put("computeOffset", false);
        m.put("onlyGCPsOnLand", false);
        return m;
    }

    private static CrossCorrelationOpUI createTab(final Product product, final Map<String, Object> paramMap) {
        final CrossCorrelationOpUI ui = new CrossCorrelationOpUI();
        ui.CreateOpTab("Cross-Correlation", paramMap, null);
        ui.setSourceProducts(new Product[]{product});
        return ui;
    }

    @Test
    public void testAsarIMSIsComplex() throws Exception {
        final Product product = ProductIO.readProduct(TestData.inputASAR_IMS);
        assertTrue("ASA_IMS_1P should be reported complex",
                   new InputProductValidator(product).isComplex());
    }

    @Test
    public void testFineRegistrationEnabledFromGraphParameters() throws Exception {
        final Product product = ProductIO.readProduct(TestData.inputASAR_IMS);
        assertTrue(createTab(product, graphParams()).applyFineRegistrationCheckBox.isEnabled());
    }

    /** The regression: no 'applyFineRegistration' key, as an operator dialog started from defaults. */
    @Test
    public void testFineRegistrationEnabledWithoutStoredParameter() throws Exception {
        final Product product = ProductIO.readProduct(TestData.inputASAR_IMS);
        final Map<String, Object> paramMap = graphParams();
        paramMap.remove("applyFineRegistration");
        paramMap.remove("inSAROptimized");
        assertTrue(createTab(product, paramMap).applyFineRegistrationCheckBox.isEnabled());
    }

    /** ERS CEOS SLC: bands are the bare letters i/q, SAMPLE_TYPE is still COMPLEX. */
    @Test
    public void testFineRegistrationEnabledForErsIMS() throws Exception {
        assumeTrue(TestData.inputERS_IMS.exists());
        final Product product = ProductIO.readProduct(TestData.inputERS_IMS);
        assertTrue("ERS SLC should be reported complex",
                   new InputProductValidator(product).isComplex());
        assertTrue(createTab(product, graphParams()).applyFineRegistrationCheckBox.isEnabled());
    }

    @Test
    public void testFineRegistrationEnabledOnCoregisteredStack() throws Exception {
        assumeTrue(TestData.inputStackIMS.exists());
        final Product stack = ProductIO.readProduct(TestData.inputStackIMS);
        assertTrue(createTab(stack, graphParams()).applyFineRegistrationCheckBox.isEnabled());
    }

    @Test
    public void testFineRegistrationDisabledForDetectedProduct() throws Exception {
        assumeTrue(TestData.inputASAR_WSM.exists());
        final Product product = ProductIO.readProduct(TestData.inputASAR_WSM);
        assertFalse("a detected product is not an SLC",
                    new InputProductValidator(product).isComplex());
        assertFalse(createTab(product, graphParams()).applyFineRegistrationCheckBox.isEnabled());
    }
}
