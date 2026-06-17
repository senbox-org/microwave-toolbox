package eu.esa.sar.sar.gpf.geometric;

import eu.esa.sar.commons.Sentinel1Utils;
import org.junit.Test;

import java.awt.Rectangle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Layer 1 unit tests for the GSLC TOPS path: deterministic, no I/O, no DEM, no network.
 * Builds a synthetic {@link Sentinel1Utils.SubSwathInfo} and exercises the static
 * deramp/reramp, range-flatten and burst-selection helpers of {@link GSLCGeocodingOp}.
 */
public class GSLCTopsInSarTest {

    /** Build a deterministic single-subswath SubSwathInfo array for the helper tests. */
    private static Sentinel1Utils.SubSwathInfo[] synthSubSwath() {
        final Sentinel1Utils.SubSwathInfo ss = new Sentinel1Utils.SubSwathInfo();
        ss.numOfBursts = 2;
        ss.linesPerBurst = 10;
        ss.numOfSamples = 5;
        ss.azimuthTimeInterval = 0.002;
        ss.dopplerRate = new double[2][5];
        ss.referenceTime = new double[2][5];
        ss.dopplerCentroid = new double[2][5];
        for (int b = 0; b < 2; b++) {
            for (int x = 0; x < 5; x++) {
                ss.dopplerRate[b][x] = 2000.0 + 10.0 * x + 3.0 * b;
                ss.referenceTime[b][x] = 0.01 + 0.001 * x + 0.0005 * b;
                ss.dopplerCentroid[b][x] = -50.0 + 7.0 * x - 4.0 * b;
            }
        }
        return new Sentinel1Utils.SubSwathInfo[]{ss};
    }

    /** Closed-form reference for the analytic reramp phase (mirrors S1 IPF DerampDemod). */
    private static double expectedPhase(Sentinel1Utils.SubSwathInfo ss, int burst,
                                        double xFrac, double yFrac) {
        final int firstLine = burst * ss.linesPerBurst;
        final double ta = (yFrac - firstLine) * ss.azimuthTimeInterval;
        final int x0 = (int) Math.floor(xFrac);
        final int x0c = Math.max(0, Math.min(ss.numOfSamples - 1, x0));
        final int x1c = Math.max(0, Math.min(ss.numOfSamples - 1, x0 + 1));
        final double fx = xFrac - x0;
        final double kt = ss.dopplerRate[burst][x0c] + fx * (ss.dopplerRate[burst][x1c] - ss.dopplerRate[burst][x0c]);
        final double tr = ss.referenceTime[burst][x0c] + fx * (ss.referenceTime[burst][x1c] - ss.referenceTime[burst][x0c]);
        final double fdc = ss.dopplerCentroid[burst][x0c] + fx * (ss.dopplerCentroid[burst][x1c] - ss.dopplerCentroid[burst][x0c]);
        final double dt = ta - tr;
        return -Math.PI * kt * dt * dt - 2.0 * Math.PI * fdc * ta;
    }

    @Test
    public void testComputeDerampDemodPhaseAt_MatchesClosedFormAtIntegerAndFractional() {
        final Sentinel1Utils.SubSwathInfo[] subSwath = synthSubSwath();
        final Sentinel1Utils.SubSwathInfo ss = subSwath[0];

        // Integer position in burst 1.
        assertEquals(expectedPhase(ss, 1, 2.0, 13.0),
                GSLCGeocodingOp.computeDerampDemodPhaseAt(subSwath, 1, 1, 2.0, 13.0), 1e-9);

        // Fractional range position (linear interpolation of kt/tr/fdc).
        assertEquals(expectedPhase(ss, 1, 2.4, 13.7),
                GSLCGeocodingOp.computeDerampDemodPhaseAt(subSwath, 1, 1, 2.4, 13.7), 1e-9);

        // Burst 0, near far-range edge (clamp branch x0+1 == numOfSamples).
        assertEquals(expectedPhase(ss, 0, 4.0, 3.0),
                GSLCGeocodingOp.computeDerampDemodPhaseAt(subSwath, 1, 0, 4.0, 3.0), 1e-9);
    }

    @Test
    public void testRangeCarrierPreFlatten_RoundTripsWithRestore() {
        // 1x1 tile so the applied phase equals the first-column phase phi0.
        final double origI = 0.7, origQ = -0.3;
        final double[][] I = {{origI}};
        final double[][] Q = {{origQ}};
        final int X = 3;
        final Rectangle rect = new Rectangle(X, 0, 1, 1);
        final double rangeSpacing = 2.33, wavelength = 0.0555, nearEdge = 8.0e5;
        final boolean nearRangeOnLeft = true;
        final int sourceImageWidth = 100;

        GSLCGeocodingOp.preFlattenRangeCarrier(I, Q, rect, rangeSpacing, wavelength,
                nearEdge, nearRangeOnLeft, sourceImageWidth);

        // The flatten applied exp(+j*phi0); restore with exp(-j*phi0).
        final int srcPx0 = nearRangeOnLeft ? X : (sourceImageWidth - 1 - X);
        final double phi0 = 4.0 * Math.PI * (nearEdge + srcPx0 * rangeSpacing) / wavelength;
        final double[] out = new double[2];
        GSLCGeocodingOp.multiplyByExpMinusJPhi(I[0][0], Q[0][0],
                Math.cos(phi0), Math.sin(phi0), out);

        assertEquals(origI, out[0], 1e-9);
        assertEquals(origQ, out[1], 1e-9);
    }

    @Test
    public void testSelectBurst_MidpointRuleAndOutOfRange() {
        final Sentinel1Utils.SubSwathInfo ss = new Sentinel1Utils.SubSwathInfo();
        ss.numOfBursts = 2;
        ss.burstFirstValidLineTime = new double[]{100.0, 110.0};
        ss.burstLastValidLineTime  = new double[]{112.0, 122.0};
        // overlap region is [110, 112]; midTime = (112 + 110)/2 = 111.

        assertEquals("inside burst 0 only", 0, GSLCGeocodingOp.selectBurst(105.0, ss));
        assertEquals("inside burst 1 only", 1, GSLCGeocodingOp.selectBurst(120.0, ss));
        assertEquals("overlap, below midpoint", 0, GSLCGeocodingOp.selectBurst(110.5, ss));
        assertEquals("overlap, at/above midpoint", 1, GSLCGeocodingOp.selectBurst(111.5, ss));
        assertEquals("before all bursts", -1, GSLCGeocodingOp.selectBurst(50.0, ss));
        assertEquals("after all bursts", -1, GSLCGeocodingOp.selectBurst(200.0, ss));
    }

    @Test
    public void testIsValidBurstSample_Boundaries() {
        final Sentinel1Utils.SubSwathInfo ss = new Sentinel1Utils.SubSwathInfo();
        ss.numOfBursts = 1;
        ss.linesPerBurst = 10;
        ss.firstValidLine = new int[]{2};
        ss.lastValidLine = new int[]{7};
        ss.firstValidSample = new int[1][10];
        ss.lastValidSample = new int[1][10];
        for (int line = 0; line < 10; line++) {
            ss.firstValidSample[0][line] = -1;
            ss.lastValidSample[0][line] = -1;
        }
        ss.firstValidSample[0][5] = 3;
        ss.lastValidSample[0][5] = 20;

        // burst 0, line 5, sample within [3,20]
        assertTrue(GSLCGeocodingOp.isValidBurstSample(0, 5.0, 10.0, ss));
        // sample below firstValidSample
        assertTrue(!GSLCGeocodingOp.isValidBurstSample(0, 5.0, 2.0, ss));
        // sample above lastValidSample
        assertTrue(!GSLCGeocodingOp.isValidBurstSample(0, 5.0, 25.0, ss));
        // line below firstValidLine
        assertTrue(!GSLCGeocodingOp.isValidBurstSample(0, 1.0, 10.0, ss));
        // line whose valid-sample range is -1 (line 6 is within [2,7] but unset)
        assertTrue(!GSLCGeocodingOp.isValidBurstSample(0, 6.0, 10.0, ss));
    }

    @Test
    public void testApplyAzimuthOffsetToBurstTimes_ShiftsAllBurstTimes() {
        final Sentinel1Utils.SubSwathInfo ss = new Sentinel1Utils.SubSwathInfo();
        ss.numOfBursts = 2;
        ss.azimuthTimeInterval = 0.002;
        ss.burstFirstLineTime      = new double[]{100.0, 110.0};
        ss.burstFirstValidLineTime = new double[]{100.5, 110.5};
        ss.burstLastValidLineTime  = new double[]{108.0, 118.0};

        // +5 px azimuth offset => shift each burst time by -5*azimuthTimeInterval seconds
        // (so a ground point's geometric row becomes originalRow + 5).
        GSLCGeocodingOp.applyAzimuthOffsetToBurstTimes(ss, 5.0);
        final double d = 5.0 * 0.002; // 0.01 s

        assertEquals(100.0 - d, ss.burstFirstLineTime[0], 1e-12);
        assertEquals(110.0 - d, ss.burstFirstLineTime[1], 1e-12);
        assertEquals(100.5 - d, ss.burstFirstValidLineTime[0], 1e-12);
        assertEquals(108.0 - d, ss.burstLastValidLineTime[0], 1e-12);
        assertEquals(118.0 - d, ss.burstLastValidLineTime[1], 1e-12);
    }
}
