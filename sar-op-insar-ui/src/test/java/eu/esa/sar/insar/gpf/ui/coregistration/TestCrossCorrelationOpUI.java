package eu.esa.sar.insar.gpf.ui.coregistration;

import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
        assumeTrue(TestData.inputASAR_IMS.exists());
        final Product product = ProductIO.readProduct(TestData.inputASAR_IMS);
        assertTrue("ASA_IMS_1P should be reported complex",
                   new InputProductValidator(product).isComplex());
    }

    @Test
    public void testFineRegistrationEnabledFromGraphParameters() throws Exception {
        assumeTrue(TestData.inputASAR_IMS.exists());
        final Product product = ProductIO.readProduct(TestData.inputASAR_IMS);
        assertTrue(createTab(product, graphParams()).applyFineRegistrationCheckBox.isEnabled());
    }

    /** The regression: no 'applyFineRegistration' key, as an operator dialog started from defaults. */
    @Test
    public void testFineRegistrationEnabledWithoutStoredParameter() throws Exception {
        assumeTrue(TestData.inputASAR_IMS.exists());
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

    /**
     * The regression this time: the multi-tab Coregistration dialog only pushes source products
     * into a tab once GraphExecuter.initGraph() succeeds, so initParameters() routinely runs with
     * sourceProducts == null. Treating that as "not complex" grays every SLC control on perfectly
     * good complex data. Needs no test product - the absence of one IS the case under test.
     */
    @Test
    public void testFineRegistrationNotGrayedBeforeSourceProductArrives() {
        final CrossCorrelationOpUI ui = new CrossCorrelationOpUI();
        ui.CreateOpTab("Cross-Correlation", graphParams(), null);
        assertTrue("SLC controls must not be grayed merely because no source product has arrived yet",
                   ui.applyFineRegistrationCheckBox.isEnabled());
    }

    /**
     * Same root cause, worse consequence: updateParameters() gated the fine-registration entries on
     * the same flag, so with no source product yet they were silently dropped from the graph.
     */
    @Test
    public void testFineRegistrationParametersWrittenBeforeSourceProductArrives() {
        final CrossCorrelationOpUI ui = new CrossCorrelationOpUI();
        final Map<String, Object> paramMap = graphParams();
        ui.CreateOpTab("Cross-Correlation", paramMap, null);
        paramMap.remove("applyFineRegistration");
        ui.updateParameters();
        assertEquals("applyFineRegistration must still reach the graph without a source product",
                     Boolean.TRUE, paramMap.get("applyFineRegistration"));
    }

    /** A minimal product carrying only the one attribute isComplex() reads. No test data needed. */
    private static Product productWithSampleType(final String sampleType) {
        final Product p = new Product("synthetic", "SLC", 4, 4);
        AbstractMetadata.addAbstractedMetadataHeader(p.getMetadataRoot())
                .setAttributeString(AbstractMetadata.SAMPLE_TYPE, sampleType);
        return p;
    }

    /**
     * The safety half of the contract, with no dependency on staged test data: a product that IS
     * known detected must still gray the SLC controls and must keep their entries out of the graph.
     * Without this, a regression that simply enabled the controls unconditionally would pass green
     * on any machine lacking E:\TestData.
     */
    @Test
    public void testDetectedProductGraysControlsAndDropsParameters() {
        final CrossCorrelationOpUI ui = new CrossCorrelationOpUI();
        final Map<String, Object> paramMap = graphParams();
        ui.CreateOpTab("Cross-Correlation", paramMap, null);
        ui.setSourceProducts(new Product[]{productWithSampleType("DETECTED")});
        assertFalse("a detected product must gray the SLC controls",
                    ui.applyFineRegistrationCheckBox.isEnabled());
        paramMap.remove("applyFineRegistration");
        ui.updateParameters();
        assertNull("fine-registration entries must not be written for a detected product",
                   paramMap.get("applyFineRegistration"));
    }

    /**
     * Stale-state regression: once a detected product had been seen, isComplex stayed FALSE even
     * after the source product was withdrawn, so the controls stayed gray on good data and
     * updateParameters() kept dropping their entries.
     */
    @Test
    public void testControlsRecoverWhenSourceProductIsWithdrawn() {
        final CrossCorrelationOpUI ui = new CrossCorrelationOpUI();
        ui.CreateOpTab("Cross-Correlation", graphParams(), null);
        ui.setSourceProducts(new Product[]{productWithSampleType("DETECTED")});
        assertFalse(ui.applyFineRegistrationCheckBox.isEnabled());
        ui.setSourceProducts(null);
        assertTrue("withdrawing the source product must return the UI to 'unknown', not keep the "
                   + "stale 'not complex' answer", ui.applyFineRegistrationCheckBox.isEnabled());
    }

    /** Fix #3 covered every dependent entry, not just the headline one. */
    @Test
    public void testAllFineRegistrationParametersSurviveWithoutSourceProduct() {
        final CrossCorrelationOpUI ui = new CrossCorrelationOpUI();
        final Map<String, Object> paramMap = graphParams();
        ui.CreateOpTab("Cross-Correlation", paramMap, null);
        for (String key : new String[]{"applyFineRegistration", "inSAROptimized",
                "fineRegistrationWindowWidth", "fineRegistrationWindowHeight",
                "fineRegistrationWindowAccAzimuth", "fineRegistrationWindowAccRange",
                "fineRegistrationOversampling", "coherenceThreshold", "useSlidingWindow"}) {
            paramMap.remove(key);
        }
        ui.updateParameters();
        for (String key : new String[]{"applyFineRegistration", "inSAROptimized",
                "fineRegistrationWindowWidth", "fineRegistrationWindowHeight",
                "fineRegistrationWindowAccAzimuth", "fineRegistrationWindowAccRange",
                "fineRegistrationOversampling", "coherenceThreshold", "useSlidingWindow"}) {
            assertNotNull("'" + key + "' must reach the graph without a source product",
                          paramMap.get(key));
        }
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
