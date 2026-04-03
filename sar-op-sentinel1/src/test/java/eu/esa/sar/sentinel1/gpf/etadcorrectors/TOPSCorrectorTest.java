package eu.esa.sar.sentinel1.gpf.etadcorrectors;

import eu.esa.sar.commons.test.TestData;
import eu.esa.sar.sentinel1.gpf.TOPSARSplitOp;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.dataop.resamp.Resampling;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class TOPSCorrectorTest {

    protected final static File slc = new File(TestData.inputSAR +"S1/ETAD/IW/InSAR/S1B_IW_SLC__1SDV_20200815T173048_20200815T173116_022937_02B897_F7CF.SAFE.zip");
    protected final static File etadSLC = new File(TestData.inputSAR +"S1/ETAD/IW/InSAR/S1B_IW_ETA__AXDV_20200815T173048_20200815T173116_022937_02B897_E56D.SAFE.zip");

    private TOPSCorrector topsCorrector;

    @Before
    public void setUp() throws Exception {
        // If any of the file does not exist: the test will be ignored
        assumeTrue(slc + " not found", slc.exists());
        assumeTrue(etadSLC + " not found", etadSLC.exists());

        try(final Product sourceProduct = TestUtils.readSourceProduct(slc)) {
            TOPSARSplitOp splitOp = new TOPSARSplitOp();
            splitOp.setSourceProduct(sourceProduct);
            splitOp.setParameter("subswath", "IW1");
            splitOp.setParameter("selectedPolarisations", "VV");
            Product splitProduct = splitOp.getTargetProduct();

            try(final Product etadFile = TestUtils.readSourceProduct(etadSLC)) {
                ETADUtils etadUtils = new ETADUtils(etadFile);
                topsCorrector = new TOPSCorrector(splitProduct, etadUtils, Resampling.BILINEAR_INTERPOLATION);
            }
        }
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