package eu.esa.sar.insar.gpf;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The GSLC carrier-difference add-back sign. {@code GSLCGeocodingOp} restores the carrier with exp(-j*m), so
 * a carrier-free TOPS leg carries {@code truth x exp(+j*m)}; the add-back must therefore be
 * {@code (m_ref - m_sec)}, i.e. {@code CARRIER_DIFF_SIGN = -1} applied to {@code (m_sec - m_ref)}.
 * Only an explicit "+1" / "1" selects the legacy sign; unset or malformed values never do.
 */
public class TestCarrierDiffSign {

    @Test
    public void defaultIsMinusOne() {
        assertEquals(-1.0, InterferogramOp.readCarrierDiffSign(null), 0.0);
        assertEquals(-1.0, InterferogramOp.readCarrierDiffSign(""), 0.0);
        assertEquals(-1.0, InterferogramOp.readCarrierDiffSign("-1"), 0.0);
    }

    @Test
    public void onlyExplicitPlusOneSelectsLegacy() {
        assertEquals(1.0, InterferogramOp.readCarrierDiffSign("+1"), 0.0);
        assertEquals(1.0, InterferogramOp.readCarrierDiffSign(" 1 "), 0.0);
    }

    @Test
    public void malformedValuesFallBackToMinusOne() {
        assertEquals(-1.0, InterferogramOp.readCarrierDiffSign("-2"), 0.0);
        assertEquals(-1.0, InterferogramOp.readCarrierDiffSign("true"), 0.0);
        assertEquals(-1.0, InterferogramOp.readCarrierDiffSign("minus one"), 0.0);
    }

    @Test
    public void jvmDefaultWithoutTheProperty() {
        // the unit-test JVM does not set the property
        assertEquals(-1.0, InterferogramOp.CARRIER_DIFF_SIGN, 0.0);
    }

    /**
     * Convention regression with DISTINCT reference/secondary carrier models (the synthetic pairs used
     * elsewhere have m_ref = m_sec, which hides a wrong sign). Legs carry truth x exp(+j*m) as
     * GSLCGeocodingOp produces them; the interferogram is ref x conj(sec) multiplied by
     * exp(-j*(base + carrierDiffAngle)). The result must be the classical truth phase minus base.
     */
    @Test
    public void addBackRestoresTruthWhenLegsCarryPlusJm() {
        final double truthRef = 0.7, truthSec = -0.4, base = 0.25;
        final double mRef = 41.3, mSec = 38.9; // radians, large and different
        final double ifgPhase = (truthRef + mRef) - (truthSec + mSec);
        final double refPhase = base + InterferogramOp.carrierDiffAngle(mRef, mSec);
        final double out = wrap(ifgPhase - refPhase);
        assertEquals(wrap((truthRef - truthSec) - base), out, 1e-9);
    }

    private static double wrap(final double a) {
        return Math.atan2(Math.sin(a), Math.cos(a));
    }
}
