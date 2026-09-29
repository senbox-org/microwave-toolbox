package eu.esa.sar.sentinel1.gpf.etadcorrectors;

import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.dataop.resamp.Resampling;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@Ignore
public class GRDCorrectorTest {

    protected final static File grd = new File(TestData.inputSAR +"S1/ETAD/IW/InSAR/S1B_IW_SLC__1SDV_20200815T173048_20200815T173116_022937_02B897_F7CF.SAFE.zip");
    protected final static File etadGRD = new File(TestData.inputSAR +"S1/ETAD/IW/InSAR/S1B_IW_ETA__AXDV_20200815T173048_20200815T173116_022937_02B897_E56D.SAFE.zip");

    private static Product sourceProduct;
    private static Product etadFileProduct;
    private static GRDCorrector grdCorrector;

    @BeforeClass
    public static void setUpClass() throws Exception {
        // Bail out cheaply if fixtures aren't available; per-test @Before will
        // turn this into a JUnit "skipped" via assumeTrue. assumeTrue inside
        // @BeforeClass is not reliable across JUnit 4 versions, hence this
        // two-step pattern.
        if (!grd.exists() || !etadGRD.exists()) {
            return;
        }

        sourceProduct = TestUtils.readSourceProduct(grd);
        etadFileProduct = TestUtils.readSourceProduct(etadGRD);
        ETADUtils etadUtils = new ETADUtils(etadFileProduct);
        grdCorrector = new GRDCorrector(sourceProduct, etadUtils, Resampling.BILINEAR_INTERPOLATION);
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
        grdCorrector = null;
    }

    @Before
    public void setUp() {
        // Per-test skip when fixtures aren't on this machine.
        assumeTrue(grd + " not found", grd.exists());
        assumeTrue(etadGRD + " not found", etadGRD.exists());
        assertNotNull("GRDCorrector fixture was not initialised", grdCorrector);
    }

    @Test
    public void constructor_initializesCorrectly() {
        assertNotNull(grdCorrector);
    }

    @Test
    public void getSourceProductMetadata_initializesMetadataCorrectly() {
        grdCorrector.initialize();
        assertTrue(grdCorrector.firstLineTime > 0);
        assertTrue(grdCorrector.lastLineTime > 0);
        assertTrue(grdCorrector.lineTimeInterval > 0);
        assertTrue(grdCorrector.groundRangeSpacing > 0);
    }
}
