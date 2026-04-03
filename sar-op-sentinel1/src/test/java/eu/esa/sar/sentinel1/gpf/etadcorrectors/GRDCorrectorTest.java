package eu.esa.sar.sentinel1.gpf.etadcorrectors;

import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.dataop.resamp.Resampling;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@Ignore
public class GRDCorrectorTest {

    protected final static File grd = new File(TestData.inputSAR +"S1/ETAD/IW/InSAR/S1B_IW_SLC__1SDV_20200815T173048_20200815T173116_022937_02B897_F7CF.SAFE.zip");
    protected final static File etadGRD = new File(TestData.inputSAR +"S1/ETAD/IW/InSAR/S1B_IW_ETA__AXDV_20200815T173048_20200815T173116_022937_02B897_E56D.SAFE.zip");

    private GRDCorrector grdCorrector;

    @Before
    public void setUp() throws Exception {
        // If any of the file does not exist: the test will be ignored
        assumeTrue(grd + " not found", grd.exists());
        assumeTrue(etadGRD + " not found", etadGRD.exists());

        try(final Product sourceProduct = TestUtils.readSourceProduct(grd)) {
            try(final Product etadFile = TestUtils.readSourceProduct(etadGRD)) {
                ETADUtils etadUtils = new ETADUtils(etadFile);
                grdCorrector = new GRDCorrector(sourceProduct, etadUtils, Resampling.BILINEAR_INTERPOLATION);
            }
        }
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