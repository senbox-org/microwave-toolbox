package eu.esa.sar.sentinel1.gpf.etadcorrectors;

import eu.esa.sar.commons.test.TestData;
import eu.esa.sar.sentinel1.gpf.TOPSARSplitOp;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.dataop.resamp.Resampling;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

/**
 * TOPSCorrector tests that need real SLC + ETAD fixtures plus a
 * {@link TOPSARSplitOp} preprocessing step. Fixtures load once per class
 * via {@link #setUpClass()} (~2 minutes); individual tests then run
 * instantly.
 */
public class TOPSCorrectorTest {

    private static final File slc = new File(TestData.inputSAR +
            "S1/ETAD/IW/InSAR/S1B_IW_SLC__1SDV_20200815T173048_20200815T173116_022937_02B897_F7CF.SAFE.zip");
    private static final File etadSLC = new File(TestData.inputSAR +
            "S1/ETAD/IW/InSAR/S1B_IW_ETA__AXDV_20200815T173048_20200815T173116_022937_02B897_E56D.SAFE.zip");

    private static Product sourceProduct;
    private static Product etadFileProduct;
    private static TOPSCorrector topsCorrector;

    @BeforeClass
    public static void setUpClass() throws Exception {
        if (!slc.exists() || !etadSLC.exists()) {
            return;
        }

        sourceProduct = TestUtils.readSourceProduct(slc);

        final TOPSARSplitOp splitOp = new TOPSARSplitOp();
        splitOp.setSourceProduct(sourceProduct);
        splitOp.setParameter("subswath", "IW1");
        splitOp.setParameter("selectedPolarisations", "VV");
        final Product splitProduct = splitOp.getTargetProduct();

        etadFileProduct = TestUtils.readSourceProduct(etadSLC);
        final ETADUtils etadUtils = new ETADUtils(etadFileProduct);
        topsCorrector = new TOPSCorrector(splitProduct, etadUtils, Resampling.BILINEAR_INTERPOLATION);
    }

    @AfterClass
    public static void tearDownClass() {
        if (sourceProduct != null) {
            sourceProduct.dispose();
            sourceProduct = null;
        }
        if (etadFileProduct != null) {
            etadFileProduct.dispose();
            etadFileProduct = null;
        }
        topsCorrector = null;
    }

    @Before
    public void setUp() {
        assumeTrue(slc + " not found", slc.exists());
        assumeTrue(etadSLC + " not found", etadSLC.exists());
        assertNotNull("TOPSCorrector fixture was not initialised", topsCorrector);
    }

    @Test
    public void constructor_initializesCorrectly() {
        assertNotNull(topsCorrector);
    }

    @Test
    public void getSourceProductMetadata_initializesMetadataCorrectly() {
        topsCorrector.initialize();

        // assertions
    }
}
