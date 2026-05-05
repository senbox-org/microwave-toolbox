package eu.esa.snap.cimr.grid;

import org.esa.snap.core.datamodel.GeoPos;
import org.junit.Test;

import static org.junit.Assert.*;


public class CimrGridFactoryTest {

    private static final double doubleErr = 0.00001;

    @Test
    public void testCreatePlateCarreeFromBoundingBox() {
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
        CimrBoundingBox bBox = CimrBoundingBox.create(geometry, CimrGridFactory.DEFAULT_CELL_SIZE_DEG);

        CimrGrid grid = CimrGridFactory.createPlateCarreeFromBoundingBox(bBox);

        assertEquals(351.0, grid.getWidth(), doubleErr);
        assertEquals(251.0, grid.getHeight(), doubleErr);
        assertEquals(CimrGridFactory.DEFAULT_CELL_SIZE_DEG, grid.getProjection().getDeltaLat(), doubleErr);
        assertEquals(CimrGridFactory.DEFAULT_CELL_SIZE_DEG, grid.getProjection().getDeltaLon(), doubleErr);
        assertEquals(10.04, grid.getProjection().getLonMin(), doubleErr);
        assertEquals(54.54, grid.getProjection().getLatMax(), doubleErr);
    }

}