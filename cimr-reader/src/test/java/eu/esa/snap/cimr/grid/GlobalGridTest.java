package eu.esa.snap.cimr.grid;

import org.esa.snap.core.datamodel.GeoPos;
import org.junit.Before;
import org.junit.Test;

import java.awt.*;

import static org.junit.Assert.*;


public class GlobalGridTest {

    GlobalGrid grid;

    @Before
    public void setUp() {
        PlateCarreeProjection proj = new PlateCarreeProjection(
                360, 180,
                -180.0, 90.0,
                1.0, 1.0
        );
        grid = new GlobalGrid(proj, 180, 360);
    }

    @Test
    public void gridToGeoPos() {
        GeoPos pos = grid.gridToGeoPos(50, 100);
        assertEquals(-10.5, pos.getLat(), 1e-6);
        assertEquals(-129.5, pos.getLon(), 1e-6);
    }

    @Test
    public void geoPosToGrid() {
        Point p = new Point();
        int x = 50;
        int y = 100;
        GeoPos pos = grid.gridToGeoPos(x, y);
        boolean inside = grid.geoPosToGrid(pos, p);

        assertTrue("Point should be inside grid", inside);
        assertEquals("x mismatch", x, p.x);
        assertEquals("y mismatch", y, p.y);
    }
}