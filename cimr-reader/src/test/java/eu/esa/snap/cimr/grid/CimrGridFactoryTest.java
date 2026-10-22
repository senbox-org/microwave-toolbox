package eu.esa.snap.cimr.grid;

import org.junit.Test;

import static org.junit.Assert.*;


public class CimrGridFactoryTest {

    private static final double doubleErr = 0.00001;

    @Test
    public void testCreatePlateCarreeFromBoundingBox() {
        CimrBoundingBox bBox = CimrBoundingBox.create(
                10.542, 16.542, 50.0234, 54.0234,
                CimrGridFactory.DEFAULT_CELL_SIZE_DEG
        );

        CimrGrid grid = CimrGridFactory.createPlateCarreeFromBoundingBox(bBox);

        assertEquals(351.0, grid.getWidth(), doubleErr);
        assertEquals(251.0, grid.getHeight(), doubleErr);
        assertEquals(CimrGridFactory.DEFAULT_CELL_SIZE_DEG, grid.getProjection().getDeltaLat(), doubleErr);
        assertEquals(CimrGridFactory.DEFAULT_CELL_SIZE_DEG, grid.getProjection().getDeltaLon(), doubleErr);
        assertEquals(10.04, grid.getProjection().getLonMin(), doubleErr);
        assertEquals(54.54, grid.getProjection().getLatMax(), doubleErr);
    }

}
