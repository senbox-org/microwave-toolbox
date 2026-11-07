package eu.esa.sar.calibration.gpf.support;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestBorderCutDetector {

    private static double[] line(int noiseWidth, double noiseValue, double signal, int len) {
        final double[] a = new double[len];
        for (int i = 0; i < len; i++) {
            a[i] = i < noiseWidth ? noiseValue : signal;
        }
        return a;
    }

    @Test
    public void esaCutIsContiguousFromEdge() {
        final double[] a = line(20, 1.0, 100, 200);
        a[50] = 0.0;                                   // isolated dark interior pixel (e.g. calm water)
        final double[] eta = new double[200];
        Arrays.fill(eta, 4.0);                         // noise power 4 -> noise amplitude 2 > border DN 1
        assertEquals(20, BorderCutDetector.esaCut(a, eta, 0.5));   // interior pixel 50 is NOT included
    }

    @Test
    public void esaCutZeroWhenEdgeIsValid() {
        assertEquals(0, BorderCutDetector.esaCut(line(0, 0, 100, 100), new double[100], 0.5));
    }

    @Test
    public void esaCutTreatsNaNAsBorder() {
        final double[] a = line(10, Double.NaN, 100, 50);
        assertEquals(10, BorderCutDetector.esaCut(a, new double[50], 0.5));
    }

    @Test
    public void jumpCutFindsNoValueZoneEdge() {
        assertEquals(30, BorderCutDetector.jumpCut(line(30, 2, 150, 200), 0.5, 0.1, 150), 1);
    }

    @Test
    public void jumpCutSkipsLinesThatStartValid() {
        assertEquals(0, BorderCutDetector.jumpCut(line(30, 80, 150, 200), 0.5, 0.1, 150));
    }

    @Test
    public void jumpCutIgnoresSpeckleOnLowAmplitudeProducts() {
        // no border, Rayleigh-like speckle around a mean amplitude of 45 (e.g. GRDM): first pixel below 50 DN
        final java.util.Random rnd = new java.util.Random(1);
        for (int k = 0; k < 200; k++) {
            final double[] a = new double[400];
            for (int i = 0; i < a.length; i++) {
                a[i] = 45 * Math.sqrt(-Math.log(1 - rnd.nextDouble())) / 0.886;
            }
            a[0] = Math.min(a[0], 20);
            assertEquals("line " + k, 0, BorderCutDetector.jumpCut(a, 0.5, 0.1, 45));
        }
    }

    @Test
    public void jumpCutScalesWithAmplitude() {
        assertEquals(30, BorderCutDetector.jumpCut(line(30, 0.2, 15, 200), 0.5, 0.1, 15), 1);
    }

    @Test
    public void regulariseRemovesOutliersAndAddsBuffer() {
        final int[] cuts = new int[500];
        Arrays.fill(cuts, 40);
        cuts[100] = 400;                               // single-line spike (e.g. bright target)
        cuts[300] = 0;                                 // single-line miss
        final int[] r = BorderCutDetector.regularise(cuts, 51, 201, 5);
        for (int v : r) {
            assertEquals(40 + 1 + 5, v);
        }
    }

    @Test
    public void regulariseKeepsRealSteps() {
        final int[] cuts = new int[600];
        for (int i = 0; i < 600; i++) {
            cuts[i] = i < 300 ? 40 : 120;              // swath-start step along azimuth
        }
        final int[] r = BorderCutDetector.regularise(cuts, 51, 201, 5);
        assertEquals(46, r[10]);
        assertEquals(126, r[590]);
    }

    @Test
    public void regulariseZeroesTinyWidths() {
        final int[] cuts = new int[200];
        Arrays.fill(cuts, 1);
        for (int v : BorderCutDetector.regularise(cuts, 51, 201, 5)) {
            assertEquals(0, v);
        }
    }

    @Test
    public void regulariseKeepsOnePixelCutsWhenAsked() {
        // ESA cuts of 1 are genuine border pixels; only a zero cut means no border
        final int[] cuts = new int[200];
        Arrays.fill(cuts, 1);
        for (int v : BorderCutDetector.regularise(cuts, 51, 201, 5, false)) {
            assertEquals(1 + 1 + 5, v);
        }
        for (int v : BorderCutDetector.regularise(new int[200], 51, 201, 5, false)) {
            assertEquals(0, v);
        }
    }

    @Test
    public void jumpCutIgnoresNaNInTheLevels() {
        // NaN samples are left out of the edge and interior medians
        final double[] a = line(30, 2, 150, 200);
        a[3] = Double.NaN;
        a[150] = Double.NaN;
        assertEquals(30, BorderCutDetector.jumpCut(a, 0.5, 0.1, 150), 1);
    }

    @Test
    public void regulariseHandlesEmptyAndShortInput() {
        assertEquals(0, BorderCutDetector.regularise(new int[0], 51, 201, 5).length);
        assertTrue(BorderCutDetector.regularise(new int[]{40}, 51, 201, 5)[0] > 40);
    }

    @Test
    public void jumpCutStopsAtWaterBehindTheBorder() {
        // no-value zone (DN 3) then calm water (DN 20) then land (DN 150): the border ends where the water starts
        final double[] a = new double[400];
        for (int i = 0; i < a.length; i++) {
            a[i] = i < 30 ? 3 : i < 80 ? 20 : 150;
        }
        assertEquals(30, BorderCutDetector.jumpCut(a, 0.5, 0.1, 150), 1);
    }

    @Test
    public void jumpCutKeepsWaterTouchingTheEdge() {
        // no border: calm water (DN 20) at the image edge, land further in
        final double[] a = new double[400];
        for (int i = 0; i < a.length; i++) {
            a[i] = i < 60 ? 20 : 150;
        }
        assertEquals(0, BorderCutDetector.jumpCut(a, 0.5, 0.1, 150));
    }

    @Test
    public void regulariseFollowsAStaircaseBorder() {
        // border widening in six steps along azimuth (SWST changes): each step keeps its own cut
        final int[] cuts = new int[6000];
        for (int i = 0; i < cuts.length; i++) {
            cuts[i] = 40 + 20 * (i / 1000);
        }
        final int[] r = BorderCutDetector.regularise(cuts, 51, 201, 5);
        for (int step = 0; step < 6; step++) {
            assertEquals("step " + step, 40 + 20 * step + 1 + 5, r[step * 1000 + 500]);
        }
    }

    @Test
    public void regulariseFollowsARampWithinTheWindow() {
        final int[] cuts = new int[2000];
        for (int i = 0; i < cuts.length; i++) {
            cuts[i] = 40 + i / 20;                     // 40 .. 139
        }
        final int[] r = BorderCutDetector.regularise(cuts, 51, 201, 5);
        for (int i = 0; i < cuts.length; i++) {
            assertTrue("i=" + i, r[i] >= cuts[i] + 1 && r[i] <= cuts[Math.min(cuts.length - 1, i + 100)] + 1 + 5);
        }
    }

    @Test
    public void regulariseKeepsAFalseClumpLocal() {
        // a 30-line clump of large cuts (e.g. a dark feature) must not widen the whole edge
        final int[] cuts = new int[6000];
        Arrays.fill(cuts, 40);
        for (int i = 3000; i < 3030; i++) {
            cuts[i] = 900;
        }
        final int[] r = BorderCutDetector.regularise(cuts, 51, 201, 5);
        assertEquals(46, r[1000]);
        assertEquals(46, r[5000]);
        assertEquals(46, r[3300]);
    }

    @Test
    public void jumpCutMasksTheWholeStripWhenTheBorderIsDeeper() {
        // border deeper than the searched strip (e.g. an old graph with borderLimit 500)
        final double[] a = new double[500];
        Arrays.fill(a, 2);
        assertEquals(500, BorderCutDetector.jumpCut(a, 0.5, 0.1, 150, true));
    }

    @Test
    public void jumpCutDoesNotExtendBeyondTheStripWhenNotAllowed() {
        // thermally denoised input: a strip that is dark throughout may be water, not a deep border
        final double[] a = new double[500];
        Arrays.fill(a, 2);
        assertEquals(0, BorderCutDetector.jumpCut(a, 0.5, 0.1, 150, false));
    }
}
