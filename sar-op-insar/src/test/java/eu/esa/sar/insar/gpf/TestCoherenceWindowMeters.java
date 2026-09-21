package eu.esa.sar.insar.gpf;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * The coherence window must span the requested distance ON THE GROUND in both geometries.
 * Before 2026-09-18 the radar-geometry branch divided by SLANT range spacing, so a 100 m
 * request spanned ~150 m on the ground at S1 IW incidence - a ~1.5x bias against any
 * geocoded product it was compared with.
 */
public class TestCoherenceWindowMeters {

    @Test
    public void radarGeometryUsesGroundRangeSpacing() {
        // S1 IW SLC: 2.33 m slant range spacing, 14.0 m azimuth, ~39 deg incidence.
        // ground range spacing = 2.33 / sin(39 deg) = 3.701 m -> 100 / 3.701 = 27 px
        final int[] win = InterferogramOp.coherenceWindowFromMeters(100.0, 2.33, 14.0, 39.0, false);
        assertEquals("range window (ground-corrected)", 27, win[0]);
        assertEquals("azimuth window", 7, win[1]);
    }

    @Test
    public void geocodedGeometryUsesSpacingDirectly() {
        // A GSLC product's range_spacing is already a ground/map step - no incidence term.
        final int[] win = InterferogramOp.coherenceWindowFromMeters(100.0, 10.0, 10.0, 39.0, true);
        assertEquals("range window", 10, win[0]);
        assertEquals("azimuth window", 10, win[1]);
    }

    @Test
    public void windowNeverDropsBelowThree() {
        final int[] win = InterferogramOp.coherenceWindowFromMeters(1.0, 100.0, 100.0, 39.0, true);
        assertEquals(3, win[0]);
        assertEquals(3, win[1]);
    }

    @Test
    public void degenerateIncidenceFallsBackToSlantSpacing() {
        // incidence 0 or absent must not produce a divide-by-zero or an absurd window.
        final int[] win = InterferogramOp.coherenceWindowFromMeters(100.0, 2.33, 14.0, 0.0, false);
        assertEquals(43, win[0]);
        assertEquals(7, win[1]);
    }
}
