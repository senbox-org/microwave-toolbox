package eu.esa.snap.cimr.grid;

import org.esa.snap.core.datamodel.GeoPos;
import org.junit.Test;

import static org.junit.Assert.*;


public class CimrBoundingBoxTest {

    private static final double doubleErr = 0.00001;

    @Test
    public void testCreateBoundingBox() {
        int scanCount = 5;
        int tpCount = 4;
        int sampleCount = 8;
        GeoPos[][][] tiePoints = new GeoPos[scanCount][tpCount][1];

        for (int s = 0; s < scanCount; s++) {
            for (int tp = 0; tp < tpCount; tp++) {
                double lat = 50.0234 + s;
                double lon = 10.542 + tp * 2;
                tiePoints[s][tp][0] = new GeoPos(lat, lon);
            }
        }

        CimrGeometry geometry = new CimrTiepointGeometry(tiePoints, sampleCount);
        CimrBoundingBox bBox = CimrBoundingBox.create(geometry, 0.02);

        assertEquals(49.52, bBox.getLatMin(), doubleErr);
        assertEquals(10.04, bBox.getLonMin(), doubleErr);
        assertEquals(54.54, bBox.getLatMax(), doubleErr);
        assertEquals(17.06, bBox.getLonMax(), doubleErr);
    }
}