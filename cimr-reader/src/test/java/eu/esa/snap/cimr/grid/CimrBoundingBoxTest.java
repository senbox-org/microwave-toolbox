package eu.esa.snap.cimr.grid;

import com.bc.ceres.annotation.STTM;
import org.junit.Test;

import static org.junit.Assert.*;


public class CimrBoundingBoxTest {


    private static final double doubleErr = 0.00001;


    @Test
    @STTM("SNAP-4262")
    public void testCreateBoundingBoxFromBounds() {
        CimrBoundingBox bBox = CimrBoundingBox.create(10.542, 16.542, 50.0234, 54.0234, 0.02);

        assertEquals(49.52, bBox.getLatMin(), doubleErr);
        assertEquals(10.04, bBox.getLonMin(), doubleErr);
        assertEquals(54.54, bBox.getLatMax(), doubleErr);
        assertEquals(17.06, bBox.getLonMax(), doubleErr);
    }
}
