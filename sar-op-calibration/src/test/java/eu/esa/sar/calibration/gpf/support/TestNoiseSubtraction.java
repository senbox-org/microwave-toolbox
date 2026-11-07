package eu.esa.sar.calibration.gpf.support;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TestNoiseSubtraction {

    @Test
    public void zeroNoiseMeansUnknownAndPassesThrough() {
        assertEquals(5.0, NoiseSubtraction.apply(5.0, 0.0, true, 1e-5), 0);
    }

    @Test
    public void nonFiniteNoiseIsUnknownAndPassesThrough() {
        assertEquals(5.0, NoiseSubtraction.apply(5.0, Double.NaN, true, 1e-5), 0);
        assertEquals(5.0, NoiseSubtraction.apply(5.0, Double.POSITIVE_INFINITY, true, 1e-5), 0);
        assertEquals(5.0, NoiseSubtraction.apply(5.0, Double.NEGATIVE_INFINITY, false, 1e-5), 0);
    }

    @Test
    public void subtractsNoise() {
        assertEquals(3.0, NoiseSubtraction.apply(5.0, 2.0, true, 1e-5), 1e-12);
    }

    @Test
    public void clipsNegativeToFloor() {
        assertEquals(1e-5, NoiseSubtraction.apply(1.0, 2.0, true, 1e-5), 0);
        assertEquals(0.0, NoiseSubtraction.apply(1.0, 2.0, true, 0.0), 0);      // ESA MPC-0392 Eq-2
    }

    @Test
    public void keepsNegativeWhenNotClipping() {
        assertEquals(-1.0, NoiseSubtraction.apply(1.0, 2.0, false, 1e-5), 1e-12); // ESA MPC-0392 Eq-3 use
    }

    @Test
    public void smallPositiveIsNotRaisedToFloor() {
        assertEquals(5e-6, NoiseSubtraction.apply(2.0 + 5e-6, 2.0, true, 1e-5), 1e-12);
    }

    @Test
    public void negativeNoiseReintroduces() {
        assertEquals(7.0, NoiseSubtraction.apply(5.0, -2.0, true, 1e-5), 1e-12);
    }
}
