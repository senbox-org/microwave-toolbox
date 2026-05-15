package eu.esa.sar.sentinel1.gpf.etadcorrectors;

import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.dataop.resamp.Resampling;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Lightweight BaseCorrector tests that don't need a real SLC or ETAD product.
 *
 * <p>These exercise the corrector's boolean-flag setters/getters, which
 * depend only on the corrector's internal default state. Constructing a
 * {@link BaseCorrector} with a 10x10 stub source product is enough — no
 * SAFE.zip extraction, no NetCDF parsing, no DEM loads. Tests run in
 * milliseconds.</p>
 *
 * <p>Tests that genuinely require the real SLC + ETAD fixtures
 * ({@code createTargetProduct}, {@code getSourceRectangle}, the
 * {@code getInstrument*TimeCalibration} methods that dispatch into
 * {@link ETADUtils}) live in {@code BaseCorrectorTest}.</p>
 */
public class BaseCorrectorFlagsTest {

    private static BaseCorrector baseCorrector;

    @BeforeClass
    public static void setUpClass() {
        final Product stubSource = new Product("stub", "type", 10, 10);
        baseCorrector = new BaseCorrector(stubSource, null, Resampling.NEAREST_NEIGHBOUR) {
        };
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
}
