package eu.esa.sar.sentinel1.gpf.etadcorrectors;

import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.dataop.resamp.Resampling;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Before;
import org.junit.Test;

import java.awt.*;
import java.io.File;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class BaseCorrectorTest {

    protected final static File slcInSAR1 = new File(TestData.inputSAR +"S1/ETAD/IW/InSAR/S1B_IW_SLC__1SDV_20200815T173048_20200815T173116_022937_02B897_F7CF.SAFE.zip");
    protected final static File etadInSAR1 = new File(TestData.inputSAR +"S1/ETAD/IW/InSAR/S1B_IW_ETA__AXDV_20200815T173048_20200815T173116_022937_02B897_E56D.SAFE.zip");

    private BaseCorrector baseCorrector;

    @Before
    public void setUp() throws Exception {
        // If any of the file does not exist: the test will be ignored
        assumeTrue(slcInSAR1 + " not found", slcInSAR1.exists());
        assumeTrue(etadInSAR1 + " not found", etadInSAR1.exists());

        try(final Product sourceProduct = TestUtils.readSourceProduct(slcInSAR1)) {
            try (final Product etadFile = TestUtils.readSourceProduct(etadInSAR1)) {
                Product targetProduct = new Product("targetProduct", "type", 100, 100);
                ETADUtils etadUtils = new ETADUtils(etadFile);
                baseCorrector = new BaseCorrector(sourceProduct, etadUtils, Resampling.NEAREST_NEIGHBOUR) {
                };
                baseCorrector.sourceProduct = sourceProduct;
                baseCorrector.targetProduct = targetProduct;
                baseCorrector.etadUtils = etadUtils;
            }
        }
    }

    @Test
    public void setIonosphericCorrectionRg_setsFlagCorrectly() {
        baseCorrector.setIonosphericCorrectionRg(true);
        assertTrue(baseCorrector.ionosphericCorrectionRg);

        baseCorrector.setIonosphericCorrectionRg(false);
        assertFalse(baseCorrector.ionosphericCorrectionRg);
    }

    @Test
    public void setGeodeticCorrectionRg_setsFlagCorrectly() {
        baseCorrector.setGeodeticCorrectionRg(true);
        assertTrue(baseCorrector.geodeticCorrectionRg);

        baseCorrector.setGeodeticCorrectionRg(false);
        assertFalse(baseCorrector.geodeticCorrectionRg);
    }

    @Test
    public void setDopplerShiftCorrectionRg_setsFlagCorrectly() {
        baseCorrector.setDopplerShiftCorrectionRg(true);
        assertTrue(baseCorrector.dopplerShiftCorrectionRg);

        baseCorrector.setDopplerShiftCorrectionRg(false);
        assertFalse(baseCorrector.dopplerShiftCorrectionRg);
    }

    @Test
    public void setGeodeticCorrectionAz_setsFlagCorrectly() {
        baseCorrector.setGeodeticCorrectionAz(true);
        assertTrue(baseCorrector.geodeticCorrectionAz);

        baseCorrector.setGeodeticCorrectionAz(false);
        assertFalse(baseCorrector.geodeticCorrectionAz);
    }

    @Test
    public void setBistaticShiftCorrectionAz_setsFlagCorrectly() {
        baseCorrector.setBistaticShiftCorrectionAz(true);
        assertTrue(baseCorrector.bistaticShiftCorrectionAz);

        baseCorrector.setBistaticShiftCorrectionAz(false);
        assertFalse(baseCorrector.bistaticShiftCorrectionAz);
    }

    @Test
    public void setFmMismatchCorrectionAz_setsFlagCorrectly() {
        baseCorrector.setFmMismatchCorrectionAz(true);
        assertTrue(baseCorrector.fmMismatchCorrectionAz);

        baseCorrector.setFmMismatchCorrectionAz(false);
        assertFalse(baseCorrector.fmMismatchCorrectionAz);
    }

    @Test
    public void setSumOfAzimuthCorrections_setsFlagCorrectly() {
        baseCorrector.setSumOfAzimuthCorrections(true);
        assertTrue(baseCorrector.sumOfAzimuthCorrections);

        baseCorrector.setSumOfAzimuthCorrections(false);
        assertFalse(baseCorrector.sumOfAzimuthCorrections);
    }

    @Test
    public void setSumOfRangeCorrections_setsFlagCorrectly() {
        baseCorrector.setSumOfRangeCorrections(true);
        assertTrue(baseCorrector.sumOfRangeCorrections);

        baseCorrector.setSumOfRangeCorrections(false);
        assertFalse(baseCorrector.sumOfRangeCorrections);
    }

    @Test
    public void setResamplingImage_setsFlagCorrectly() {
        baseCorrector.setResamplingImage(true);
        assertTrue(baseCorrector.resamplingImage);

        baseCorrector.setResamplingImage(false);
        assertFalse(baseCorrector.resamplingImage);
    }

    @Test
    public void setOutputPhaseCorrections_setsFlagCorrectly() {
        baseCorrector.setOutputPhaseCorrections(true);
        assertTrue(baseCorrector.outputPhaseCorrections);

        baseCorrector.setOutputPhaseCorrections(false);
        assertFalse(baseCorrector.outputPhaseCorrections);
    }

    @Test
    public void createTargetProduct_createsProductCorrectly() {
        Product result = baseCorrector.createTargetProduct();
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

    @Test
    public void getSourceRectangle_returnsCorrectRectangle() {
        Rectangle result = baseCorrector.getSourceRectangle(0, 0, 10, 10, 1);
        assertNotNull(result);
    }
}
