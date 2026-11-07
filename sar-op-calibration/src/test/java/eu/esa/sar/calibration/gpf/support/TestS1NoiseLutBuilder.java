package eu.esa.sar.calibration.gpf.support;

import eu.esa.sar.commons.Sentinel1Utils;
import org.esa.snap.core.datamodel.ProductData;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestS1NoiseLutBuilder {

    private static final double DT = 1.0 / 86400.0;          // 1 s per line, in days

    private static Sentinel1Utils.NoiseVector rangeVector(double lineTime, int line, float value) {
        return new Sentinel1Utils.NoiseVector(new ProductData.UTC(lineTime * DT), line,
                new int[]{0, 9}, new float[]{value, value});
    }

    // One swath block covering lines 0..9, samples 0..9; azimuth LUT constant 2;
    // two range vectors at line 0 and line 9, values 10 and 20 everywhere.
    static S1NoiseLutBuilder simpleGrd() {
        final Sentinel1Utils.NoiseAzimuthVector az = new Sentinel1Utils.NoiseAzimuthVector(
                "IW1", 0, 0, 9, 9, new int[]{0, 9}, new float[]{2, 2});
        final Map<String, double[]> swathTimes = new HashMap<>();
        swathTimes.put("IW1", new double[]{0.0, 9 * DT});
        return new S1NoiseLutBuilder(new Sentinel1Utils.NoiseVector[]{rangeVector(0, 0, 10), rangeVector(9, 9, 20)},
                new Sentinel1Utils.NoiseAzimuthVector[]{az}, 0.0, DT, swathTimes);
    }

    @Test
    public void bilinearProductOfRangeAndAzimuth() {
        final double[][] m = simpleGrd().buildGRD(0, 0, 10, 10);
        assertEquals(2 * 10.0, m[0][5], 1e-9);
        assertEquals(2 * (10.0 + 40.0 / 9), m[4][5], 1e-9);     // between the two range vectors
        assertEquals(2 * 20.0, m[9][5], 1e-9);
    }

    @Test
    public void rangeVectorsAreNotExtrapolatedInTime() {
        // range vectors at lines 2 and 7 (values 10 and 20) inside a block of lines 0..9: beyond them the
        // nearest vector is used; linear extrapolation would give 6 at line 0 and 24 at line 9
        final Sentinel1Utils.NoiseAzimuthVector az = new Sentinel1Utils.NoiseAzimuthVector(
                "IW1", 0, 0, 9, 9, new int[]{0, 9}, new float[]{1, 1});
        final Map<String, double[]> swathTimes = new HashMap<>();
        swathTimes.put("IW1", new double[]{0.0, 9 * DT});
        final S1NoiseLutBuilder b = new S1NoiseLutBuilder(
                new Sentinel1Utils.NoiseVector[]{rangeVector(2, 2, 10), rangeVector(7, 7, 20)},
                new Sentinel1Utils.NoiseAzimuthVector[]{az}, 0.0, DT, swathTimes);
        final double[][] m = b.buildGRD(0, 0, 10, 10);
        assertEquals(10.0, m[0][5], 1e-9);
        assertEquals(10.0, m[2][5], 1e-9);
        assertEquals(15.0, m[4][5] + (m[5][5] - m[4][5]) / 2, 1e-9);   // linear between the vectors
        assertEquals(20.0, m[9][5], 1e-9);
    }

    @Test(expected = IllegalStateException.class)
    public void slcWithoutAzimuthVectorFailsClearly() {
        new S1NoiseLutBuilder(new Sentinel1Utils.NoiseVector[]{rangeVector(0, 0, 1)},
                new Sentinel1Utils.NoiseAzimuthVector[0], 0.0, DT, new HashMap<>()).buildTopsSLC(0, 0, 10, 10, 10);
    }

    @Test
    public void outsideAnyBlockIsUnknown() {
        final double[][] m = simpleGrd().buildGRD(5, 5, 10, 10);      // tile spills to x,y = 14
        assertEquals(0.0, m[9][9], 0);                              // pixel (14,14) is in no block
    }

    @Test
    public void tileOverlappingBlockByOnePixelIsStillDenoised() {
        final S1NoiseLutBuilder b = simpleGrd();
        assertTrue(b.buildGRD(0, 9, 10, 1)[0][5] > 0);      // tile row == block lastAzimuthLine
        assertTrue(b.buildGRD(9, 0, 1, 10)[5][0] > 0);      // tile column == block lastRangeSample
        assertTrue(b.buildGRD(9, 9, 1, 1)[0][0] > 0);       // single-pixel tile on the block corner
    }

    @Test
    public void tilingDoesNotChangeValues() {
        final S1NoiseLutBuilder b = simpleGrd();
        final double[][] whole = b.buildGRD(0, 0, 10, 10);
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 10; x++) {
                assertEquals("x=" + x + " y=" + y, whole[y][x], b.buildGRD(x, y, 1, 1)[0][0], 1e-12);
            }
        }
    }

    @Test
    public void slcRangeVectorChosenByBurstStartLineAndBounded() {
        // range vectors annotated at the burst start lines: one before the image, then bursts 0..2 (values 1..3);
        // the times are irregular (burst cycle differs from 10 lines x 1 s) and must not matter
        final Sentinel1Utils.NoiseVector[] r = {
                rangeVector(-7, -10, 99), rangeVector(0, 0, 1), rangeVector(7, 10, 2), rangeVector(14, 20, 3)};
        final Sentinel1Utils.NoiseAzimuthVector az = new Sentinel1Utils.NoiseAzimuthVector(
                "IW1", 0, 0, 39, 9, new int[]{0, 39}, new float[]{1, 1});
        final S1NoiseLutBuilder b = new S1NoiseLutBuilder(r, new Sentinel1Utils.NoiseAzimuthVector[]{az},
                0.0, DT, new HashMap<>());
        final double[][] m = b.buildTopsSLC(0, 0, 10, 40, 10);
        assertEquals(1.0, m[0][0], 1e-9);
        assertEquals(2.0, m[15][0], 1e-9);
        assertEquals(3.0, m[25][0], 1e-9);
        assertEquals(3.0, m[35][0], 1e-9);    // burst 3: nearest vector, no ArrayIndexOutOfBounds
    }

    @Test
    public void slcBurstSubsetUsesAbsoluteLines() {
        // a TOPSAR-Split subset starting at burst 2 is evaluated at its absolute (original swath) lines
        final Sentinel1Utils.NoiseVector[] r = {rangeVector(0, 0, 1), rangeVector(7, 10, 2), rangeVector(14, 20, 3)};
        final Sentinel1Utils.NoiseAzimuthVector az = new Sentinel1Utils.NoiseAzimuthVector(
                "IW1", 0, 0, 29, 9, new int[]{0, 29}, new float[]{1, 1});
        final S1NoiseLutBuilder b = new S1NoiseLutBuilder(r, new Sentinel1Utils.NoiseAzimuthVector[]{az},
                0.0, DT, new HashMap<>());
        assertEquals(3.0, b.buildTopsSLC(0, 20, 10, 10, 10)[0][0], 1e-9);
    }

    @Test
    public void slcRangeVectorChosenByTimeWhenLinesAreShifted() {
        // MPC-0392: before IPF 3.71 the annotated line can be shifted; azimuthTime is authoritative.
        // vectors annotated 6 lines late; burst 1 starts at line 10: by line, vector 0 (line 6) would be nearest
        final Sentinel1Utils.NoiseVector[] r = {rangeVector(0, 6, 1), rangeVector(7, 16, 2), rangeVector(14, 26, 3)};
        final Sentinel1Utils.NoiseAzimuthVector az = new Sentinel1Utils.NoiseAzimuthVector(
                "IW1", 0, 0, 29, 9, new int[]{0, 29}, new float[]{1, 1});
        final S1NoiseLutBuilder b = new S1NoiseLutBuilder(r, new Sentinel1Utils.NoiseAzimuthVector[]{az},
                0.0, DT, new HashMap<>());
        final double[] burstTimes = {0, 7 * DT, 14 * DT};
        final double[][] m = b.buildTopsSLC(0, 0, 10, 30, 10, burstTimes, 0);
        assertEquals(1.0, m[5][0], 1e-9);
        assertEquals(2.0, m[15][0], 1e-9);
        assertEquals(3.0, m[25][0], 1e-9);
    }

    @Test
    public void slcTrimmedBurstListIsIndexedFromTheSubsetStart() {
        // TOPSAR-Split bursts 2..3: burst list holds only those bursts, image lines start at 20 (subset offset)
        final Sentinel1Utils.NoiseVector[] r = {rangeVector(0, 0, 1), rangeVector(7, 10, 2), rangeVector(14, 20, 3),
                rangeVector(21, 30, 4)};
        final Sentinel1Utils.NoiseAzimuthVector az = new Sentinel1Utils.NoiseAzimuthVector(
                "IW1", 0, 0, 39, 9, new int[]{0, 39}, new float[]{1, 1});
        final S1NoiseLutBuilder b = new S1NoiseLutBuilder(r, new Sentinel1Utils.NoiseAzimuthVector[]{az},
                0.0, DT, new HashMap<>());
        final double[] trimmedBurstTimes = {14 * DT, 21 * DT};
        final double[][] m = b.buildTopsSLC(0, 20, 10, 20, 10, trimmedBurstTimes, 20);
        assertEquals(3.0, m[0][0], 1e-9);
        assertEquals(4.0, m[15][0], 1e-9);
    }
}
