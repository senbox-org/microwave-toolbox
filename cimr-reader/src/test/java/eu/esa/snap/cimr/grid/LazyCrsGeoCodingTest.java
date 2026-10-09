package eu.esa.snap.cimr.grid;

import com.bc.ceres.annotation.STTM;
import org.esa.snap.core.dataio.ProductSubsetDef;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Product;
import org.geotools.referencing.CRS;
import org.junit.Test;

import java.awt.Rectangle;
import java.awt.geom.AffineTransform;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;


public class LazyCrsGeoCodingTest {


    @Test
    public void test_canGetFlagsAndIsGlobalDoNotInitDelegate() throws Exception {
        CimrGrid grid = mock(CimrGrid.class);
        GridProjection projection = mock(GridProjection.class);
        when(grid.getProjection()).thenReturn(projection);
        when(projection.getCrs()).thenReturn(CRS.decode("EPSG:4326", true));
        when(projection.getAffineTransform(grid)).thenThrow(new AssertionError("delegate should not be initialized"));

        LazyCrsGeoCoding gc = new LazyCrsGeoCoding(grid);

        assertTrue(gc.canGetGeoPos());
        assertTrue(gc.canGetPixelPos());
        assertTrue(gc.isGlobal());
        assertFalse(gc.canClone());

        verify(projection, never()).getAffineTransform(grid);
    }

    @Test
    public void test_delegateIsCreatedLazilyAndReusedForAllDelegatingMethods() throws Exception {
        CimrGrid grid = mock(CimrGrid.class);
        GridProjection projection = mock(GridProjection.class);
        AffineTransform imageToMapTransform = new AffineTransform(1.0, 0.0, 0.0, -1.0, -180.0, 90.0);
        when(grid.getWidth()).thenReturn(360);
        when(grid.getHeight()).thenReturn(180);
        when(grid.getProjection()).thenReturn(projection);
        when(projection.getCrs()).thenReturn(CRS.decode("EPSG:4326", true));
        when(projection.getAffineTransform(grid)).thenReturn(imageToMapTransform);

        LazyCrsGeoCoding gc = new LazyCrsGeoCoding(grid);

        verify(projection, never()).getAffineTransform(grid);

        GeoPos gp1 = gc.getGeoPos(new PixelPos(0.5f, 0.5f), null);

        verify(projection).getAffineTransform(grid);
        assertNotNull(gp1);

        GeoPos gp2 = gc.getGeoPos(new PixelPos(10.5f, 20.5f), null);
        verify(projection).getAffineTransform(grid);
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
        verify(projection).getAffineTransform(grid);
    }

    @Test
    public void wrapsDelegateCreationFailuresInRuntimeException() throws Exception {
        CimrGrid badGrid = mock(CimrGrid.class);
        GridProjection projection = mock(GridProjection.class);
        when(badGrid.getWidth()).thenReturn(10);
        when(badGrid.getHeight()).thenReturn(10);
        when(badGrid.getProjection()).thenReturn(projection);
        try {
            when(projection.getCrs()).thenReturn(CRS.decode("EPSG:4326", true));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        when(projection.getAffineTransform(badGrid)).thenThrow(new RuntimeException("boom"));

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

    @Test
    @STTM("SNAP-4262")
    public void transferGeoCodingTransfersSubsetTransform() throws Exception {
        CimrGrid grid = CimrGridFactory.createPlateCarreeFromBoundingBox(new CimrBoundingBox(-180.0, 180.0, -90.0, 90.0), 1.0);
        LazyCrsGeoCoding gc = new LazyCrsGeoCoding(grid);
        Product source = new Product("source", "type", 360, 180);
        Product target = new Product("target", "type", 100, 50);
        ProductSubsetDef subsetDef = new ProductSubsetDef();
        subsetDef.setRegion(new Rectangle(10, 20, 100, 50));
        source.setSceneGeoCoding(gc);

        boolean transferred = source.transferGeoCodingTo(target, subsetDef);

        assertTrue(transferred);
        assertNotNull(target.getSceneGeoCoding());
        AffineTransform transform = Product.findImageToModelTransform(target.getSceneGeoCoding());
        assertEquals(1.0, transform.getScaleX(), 1e-8);
        assertEquals(-1.0, transform.getScaleY(), 1e-8);
        assertEquals(-170.0, transform.getTranslateX(), 1e-8);
        assertEquals(70.0, transform.getTranslateY(), 1e-8);
    }

    @Test(expected = IllegalStateException.class)
    public void cloneThrowsIllegalStateException() throws Exception {
        CimrGrid grid = CimrGridFactory.createPlateCarreeFromBoundingBox(new CimrBoundingBox(-180.0, 180.0, -90.0, 90.0), 1.0);
        LazyCrsGeoCoding gc = new LazyCrsGeoCoding(grid);

        gc.clone();
    }

}