package eu.esa.snap.cimr.ui;

import com.bc.ceres.glayer.swing.LayerCanvas;
import com.bc.ceres.grender.Rendering;
import com.bc.ceres.grender.Viewport;
import eu.esa.snap.cimr.cimr.CimrFootprint;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.RasterDataNode;
import org.junit.Test;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.Collections;

import static org.mockito.Mockito.*;


public class CimrFootprintOverlayTest {


    @Test
    public void testPaintOverlay_doesNotThrow() {
        CimrFootprintOverlay overlay = CimrFootprintOverlay.INSTANCE;

        CimrFootprint fp = new CimrFootprint(
                new GeoPos(10f, 20f), 30.0, 1000.0, 2000.0, 300.0
        );
        overlay.setFootprints(Collections.singletonList(fp));

        BufferedImage img = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = img.createGraphics();

        LayerCanvas canvas = mock(LayerCanvas.class);
        Rendering rendering = mock(Rendering.class);
        Viewport vp = mock(Viewport.class);
        RasterDataNode raster = mock(RasterDataNode.class);
        CimrFootprintOverlay.INSTANCE.setRaster(raster);

        when(canvas.getViewport()).thenReturn(vp);
        when(vp.getModelToViewTransform()).thenReturn(new AffineTransform());
        when(rendering.getGraphics()).thenReturn(g2d);
        when(raster.getImageInfo()).thenReturn(null);

        // should not throw exception
        overlay.paintOverlay(canvas, rendering);
    }

}