package eu.esa.sar.sentinel1.gpf.etadcorrectors;

import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.dataop.resamp.Resampling;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.awt.Rectangle;
import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

/**
 * BaseCorrector tests that need real SLC + ETAD fixtures. The fixtures load
 * once per class via {@link #setUpClass()} (SAFE-zip extraction + NetCDF
 * parsing is ~2 minutes), so each individual test runs in milliseconds.
 *
 * <p>If you only need to exercise setter/getter flags or default-state
 * methods that don't touch the products, add the test to
 * {@link BaseCorrectorFlagsTest} instead — no fixture loading required.</p>
 */
public class BaseCorrectorTest {

    private static final File slcInSAR1 = new File(TestData.inputSAR +
            "S1/ETAD/IW/InSAR/S1B_IW_SLC__1SDV_20200815T173048_20200815T173116_022937_02B897_F7CF.SAFE.zip");
    private static final File etadInSAR1 = new File(TestData.inputSAR +
            "S1/ETAD/IW/InSAR/S1B_IW_ETA__AXDV_20200815T173048_20200815T173116_022937_02B897_E56D.SAFE.zip");

    private static Product sourceProduct;
    private static Product etadFileProduct;
    private static BaseCorrector baseCorrector;

    @BeforeClass
    public static void setUpClass() throws Exception {
        // Bail out cheaply if fixtures aren't available; per-test @Before will
        // turn this into a JUnit "skipped" via assumeTrue. assumeTrue inside
        // @BeforeClass is not reliable across JUnit 4 versions, hence this
        // two-step pattern.
        if (!slcInSAR1.exists() || !etadInSAR1.exists()) {
            return;
        }

        sourceProduct = TestUtils.readSourceProduct(slcInSAR1);
        etadFileProduct = TestUtils.readSourceProduct(etadInSAR1);
        final Product targetProduct = new Product("targetProduct", "type", 100, 100);
        final ETADUtils etadUtils = new ETADUtils(etadFileProduct);
        baseCorrector = new BaseCorrector(sourceProduct, etadUtils, Resampling.NEAREST_NEIGHBOUR) {
        };
        baseCorrector.sourceProduct = sourceProduct;
        baseCorrector.targetProduct = targetProduct;
        baseCorrector.etadUtils = etadUtils;
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
        baseCorrector = null;
    }

    @Before
    public void setUp() {
        // Per-test skip when fixtures aren't on this machine.
        assumeTrue(slcInSAR1 + " not found", slcInSAR1.exists());
        assumeTrue(etadInSAR1 + " not found", etadInSAR1.exists());
        assertNotNull("BaseCorrector fixture was not initialised", baseCorrector);
    }

    @Test
    public void createTargetProduct_createsProductCorrectly() {
        Product result = baseCorrector.createTargetProduct();
        assertNotNull(result);
    }

    @Test
    public void getSourceRectangle_returnsCorrectRectangle() {
        Rectangle result = baseCorrector.getSourceRectangle(0, 0, 10, 10, 1);
        assertNotNull(result);
    }

    @Test
    public void getInstrumentAzimuthTimeCalibration_returnsCorrectCalibration() {
        double result = baseCorrector.getInstrumentAzimuthTimeCalibration("swath1");
        assertEquals(0.0, result, 0.0);
    }

    @Test
    public void getInstrumentRangeTimeCalibration_returnsCorrectCalibration() {
        double result = baseCorrector.getInstrumentRangeTimeCalibration("swath1");
        assertEquals(0.0, result, 0.0);
    }
}
