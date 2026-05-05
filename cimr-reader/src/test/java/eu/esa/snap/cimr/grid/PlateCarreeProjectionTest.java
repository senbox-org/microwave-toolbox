package eu.esa.snap.cimr.grid;

import org.esa.snap.core.datamodel.GeoPos;
import org.junit.Before;
import org.junit.Test;

import java.awt.*;

import static org.junit.Assert.*;


public class PlateCarreeProjectionTest {

    private PlateCarreeProjection proj;
    private double doubleErr = 1e-6;

    @Before
    public void setUp() {
        proj = new PlateCarreeProjection(
                360, 180,
                -180.0, 90.0,
                1.0, 1.0
        );
    }


    @Test
    public void testGridToGeoPos_corners() {
        GeoPos ul = proj.gridToGeoPos(0, 0);
        assertEquals(89.5, ul.getLat(), doubleErr);
        assertEquals(-179.5, ul.getLon(), doubleErr);

        GeoPos ur = proj.gridToGeoPos(359, 0);
        assertEquals(89.5, ur.getLat(), doubleErr);
        assertEquals(179.5, ur.getLon(), doubleErr);

        GeoPos ll = proj.gridToGeoPos(0, 179);
        assertEquals(-89.5, ll.getLat(), doubleErr);
        assertEquals(-179.5, ll.getLon(), doubleErr);

        GeoPos lr = proj.gridToGeoPos(359, 179);
        assertEquals(-89.5, lr.getLat(), doubleErr);
        assertEquals(179.5, lr.getLon(), doubleErr);
    }

    @Test
    public void testGeoPosToGrid_roundTrip() {
        Point p = new Point();

        int[][] samples = {
                {0, 0},
                {100, 50},
                {359, 0},
                {0, 179},
                {359, 179}
        };

        for (int[] s : samples) {
            int x = s[0];
            int y = s[1];

            GeoPos geo = proj.gridToGeoPos(x, y);
            boolean inside = proj.geoPosToGrid(geo, p);

            assertTrue("Point should be inside grid", inside);
            assertEquals("x mismatch", x, p.x);
            assertEquals("y mismatch", y, p.y);
        }
    }

    @Test
    public void testGeoPosToGrid_outside() {
        Point p = new Point();

        assertFalse(proj.geoPosToGrid(new GeoPos(0f, -181f), p));
        assertFalse(proj.geoPosToGrid(new GeoPos(0f, 181f), p));

        assertFalse(proj.geoPosToGrid(new GeoPos(91f, 0f), p));
        assertFalse(proj.geoPosToGrid(new GeoPos(-91f, 0f), p));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGridToGeoPos_outOfRange_throws() {
        proj.gridToGeoPos(-1, 0);
    }
}