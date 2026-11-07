package eu.esa.sar.calibration.gpf.support;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TestS1NoiseRecalibration {

    @Test
    public void knownConstant() {
        assertEquals(Math.pow(10, 0.323 / 10), S1NoiseRecalibration.linearFactor("S1A", "IW3", 'V'), 1e-12);
        assertEquals(Math.pow(10, -0.730 / 10), S1NoiseRecalibration.linearFactor("S1A", "EW3", 'H'), 1e-12);
        assertEquals(Math.pow(10, 0.133 / 10), S1NoiseRecalibration.linearFactor("S1B", "IW3", 'H'), 1e-12);
    }

    @Test
    public void missingResourceFailsWithClearMessage() {
        try {
            S1NoiseRecalibration.load("no_such_noise_constants.properties");
            org.junit.Assert.fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            org.junit.Assert.assertTrue(e.getMessage(), e.getMessage().contains("no_such_noise_constants.properties"));
        }
        org.junit.Assert.assertFalse(S1NoiseRecalibration.load(S1NoiseRecalibration.RESOURCE).isEmpty());
    }

    @Test
    public void missingConstantIsIdentity() {
        assertEquals(1.0, S1NoiseRecalibration.linearFactor("S1B", "EW1", 'H'), 0);
        assertEquals(1.0, S1NoiseRecalibration.linearFactor("S1C", "IW1", 'V'), 0);
    }
}
