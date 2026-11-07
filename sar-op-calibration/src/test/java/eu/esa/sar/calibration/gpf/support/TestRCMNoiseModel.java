package eu.esa.sar.calibration.gpf.support;

import org.junit.Test;

import static eu.esa.sar.calibration.gpf.support.TestRCMNoiseModelSupport.load;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TestRCMNoiseModel {

    private static double lin(double dB) {
        return Math.pow(10, dB / 10);
    }

    @Test
    public void vectorInterpolatesLinearlyAndClamps() {
        RCMNoiseModel.Vector v = new RCMNoiseModel.Vector(0, 10, new double[]{1, 3, 5});
        assertEquals(1.0, v.valueAt(-5), 1e-12);   // clamp left
        assertEquals(2.0, v.valueAt(5), 1e-12);
        assertEquals(5.0, v.valueAt(25), 1e-12);   // clamp right
    }

    @Test
    public void vectorSupportsNegativeStep() {
        // positions 20, 10, 0
        RCMNoiseModel.Vector v = new RCMNoiseModel.Vector(20, -10, new double[]{1, 3, 5});
        assertEquals(1.0, v.valueAt(20), 1e-12);
        assertEquals(4.0, v.valueAt(5), 1e-12);
    }

    @Test
    public void usesBetaReferenceNotSigmaWhenNoBurstMap() throws Exception {
        RCMNoiseModel m = RCMNoiseModel.parse(load("noiseLevels_HV_small.xml", null),
                load("lutBeta_HV_small.xml", "lutBeta_HV"), null, false);
        assertEquals(lin(-20), m.betaNoise(10, 0), 1e-15);
        assertEquals(lin(-20) * 100, m.noiseDN2(10, 0), 1e-12);     // GRD: NE * A
    }

    @Test
    public void slcUsesSquaredGain() throws Exception {
        RCMNoiseModel m = RCMNoiseModel.parse(load("noiseLevels_HV_small.xml", null),
                load("lutBeta_HV_small.xml", "lutBeta_HV"), null, true);
        assertEquals(lin(-20) * 100 * 100, m.noiseDN2(10, 0), 1e-9);
    }

    @Test
    public void scanSarUsesPerBeamVectorAndAzimuthScaling() throws Exception {
        RCMNoiseModel m = RCMNoiseModel.parse(load("noiseLevels_HV_small.xml", null),
                load("lutBeta_HV_small.xml", "lutBeta_HV"), load("grdBurstMap_small.xml", null), false);
        // burst SC-1 spans lines 0..4, centre line 2 -> scaling index 2 (0 dB)
        assertEquals(lin(-30), m.betaNoise(3, 2), 1e-15);
        // line 0 is 2 lines before centre -> index 0 -> +3 dB
        assertEquals(lin(-30) * lin(3), m.betaNoise(3, 0), 1e-15);
        // SC-2 has no azimuth scaling -> per-beam value only
        assertEquals(lin(-20), m.betaNoise(12, 0), 1e-15);
    }

    @Test
    public void burstOverlapUsesTheNearestBurstCentre() throws Exception {
        // bursts of one beam overlap on lines 7..8; line 8 is 4 lines from burst 0 centre (4), 3 from burst 1 (11)
        final RCMNoiseModel m = RCMNoiseModel.parse(load("noiseLevels_overlap.xml", null),
                load("lutBeta_HV_small.xml", "lutBeta_HV"), load("grdBurstMap_overlap.xml", null), false);
        assertEquals(lin(-30) * lin(6), m.betaNoise(3, 8), 1e-15);   // burst 1: offset -3 -> index 1 -> 6 dB
    }

    @Test
    public void spotlightAzimuthScalingStartsAtLineFirst() throws Exception {
        final RCMNoiseModel m = RCMNoiseModel.parse(load("noiseLevels_spotlight.xml", null),
                load("lutBeta_HV_small.xml", "lutBeta_HV"), null, true);
        assertEquals(lin(-30), m.betaNoise(5, 0), 1e-15);
        assertEquals(lin(-30) * lin(3), m.betaNoise(5, 2), 1e-15);
        assertEquals(lin(-30) * (lin(3) + lin(6)) / 2, m.betaNoise(5, 3), 1e-15);   // linear interpolation
    }

    @Test
    public void spotlightNegativeStepWalksUpFromLineFirst() throws Exception {
        // lineFirst 4, step -2 (decreasing line time order): value k at line 4 - 2k
        final RCMNoiseModel m = RCMNoiseModel.parse(load("noiseLevels_spotlight_negstep.xml", null),
                load("lutBeta_HV_small.xml", "lutBeta_HV"), null, true);
        assertEquals(lin(-30), m.betaNoise(5, 4), 1e-15);
        assertEquals(lin(-30) * lin(3), m.betaNoise(5, 2), 1e-15);
        assertEquals(lin(-30) * lin(6), m.betaNoise(5, 0), 1e-15);
        assertEquals(lin(-30), m.betaNoise(5, 6), 1e-15);                                 // clamped
    }

    @Test
    public void severalSpotlightScalingsAreRefused() throws Exception {
        try {
            RCMNoiseModel.parse(load("noiseLevels_spotlight_twobeams.xml", null),
                    load("lutBeta_HV_small.xml", "lutBeta_HV"), null, true);
            fail("expected IllegalArgumentException: no rule to pick one of two Spotlight scalings");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("several azimuth noise scaling"));
        }
    }

    @Test
    public void nonFiniteNoiseValuesAreRejected() throws Exception {
        try {
            RCMNoiseModel.parse(load("noiseLevels_nan.xml", null), load("lutBeta_HV_small.xml", "lutBeta_HV"),
                    null, false);
            fail("expected IllegalArgumentException for NaN noise level");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("non-finite"));
        }
    }

    @Test
    public void complexProductTypesFollowTheRcmSpec() {
        // RCM-SP-53-0419 7.5.1: complex products including SLC and GRC use |DN|^2 / A^2
        assertTrue(RCMNoiseModel.isComplexProductType("SLC"));
        assertTrue(RCMNoiseModel.isComplexProductType("GRC"));
        assertTrue(RCMNoiseModel.isComplexProductType("GCC"));
        assertFalse(RCMNoiseModel.isComplexProductType("GRD"));
        assertFalse(RCMNoiseModel.isComplexProductType("GCD"));
        assertFalse(RCMNoiseModel.isComplexProductType("MLC"));
        assertFalse(RCMNoiseModel.isComplexProductType(null));
    }
}
