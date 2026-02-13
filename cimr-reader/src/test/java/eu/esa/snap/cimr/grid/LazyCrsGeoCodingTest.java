package eu.esa.snap.cimr.grid;

import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.PixelPos;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;


public class LazyCrsGeoCodingTest {

    @Test
    public void test_canGetFlagsAndIsGlobalDoNotInitDelegate() throws Exception {
        CimrGrid grid = mock(CimrGrid.class);

        LazyCrsGeoCoding gc = new LazyCrsGeoCoding(grid);

        assertTrue(gc.canGetGeoPos());
        assertTrue(gc.canGetPixelPos());
        assertTrue(gc.isGlobal());

        assertNull(getField(gc, "delegate"));
        verifyNoInteractions(grid);
    }

    @Test
    public void test_delegateIsCreatedLazilyAndReusedForAllDelegatingMethods() throws Exception {
        CimrGrid grid = CimrGridFactory.createGlobalPlateCarree(1.0);
        LazyCrsGeoCoding gc = new LazyCrsGeoCoding(grid);

        assertNull(getField(gc, "delegate"));

        GeoPos gp1 = gc.getGeoPos(new PixelPos(0.5f, 0.5f), null);

        Object delegate1 = getField(gc, "delegate");
        assertNotNull(delegate1);
        assertNotNull(gp1);

        GeoPos gp2 = gc.getGeoPos(new PixelPos(10.5f, 20.5f), null);
        Object delegate2 = getField(gc, "delegate");
        assertSame(delegate1, delegate2);
        assertNotNull(gp2);

        gc.isCrossingMeridianAt180();
        gc.getPixelPos(new GeoPos(0.0f, 0.0f), null);
        assertNotNull(gc.getDatum());
        assertNotNull(gc.getImageCRS());
        assertNotNull(gc.getMapCRS());
        assertNotNull(gc.getGeoCRS());
        assertNotNull(gc.getImageToMapTransform());
        assertFalse(gc.canClone());

        gc.dispose();
    }

    @Test
    public void wrapsDelegateCreationFailuresInRuntimeException() {
        CimrGrid badGrid = mock(CimrGrid.class);
        when(badGrid.getWidth()).thenReturn(10);
        when(badGrid.getHeight()).thenReturn(10);
        when(badGrid.getProjection()).thenThrow(new RuntimeException("boom"));

        LazyCrsGeoCoding gc = new LazyCrsGeoCoding(badGrid);

        try {
            gc.getGeoPos(new PixelPos(0.5f, 0.5f), null);
            fail("Expected RuntimeException to be thrown");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("Failed to create CrsGeoCoding"));
            assertNotNull(e.getCause());
            assertEquals("boom", e.getCause().getMessage());
        }
    }

    @Test(expected = IllegalStateException.class)
    public void cloneThrowsIllegalStateException() {
        CimrGrid grid = CimrGridFactory.createGlobalPlateCarree(1.0);
        LazyCrsGeoCoding gc = new LazyCrsGeoCoding(grid);

        gc.clone();
    }

    private static Object getField(Object target, String name) throws NoSuchFieldException, IllegalAccessException {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}