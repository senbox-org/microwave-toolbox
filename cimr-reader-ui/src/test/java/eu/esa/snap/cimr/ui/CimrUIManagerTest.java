package eu.esa.snap.cimr.ui;

import eu.esa.snap.cimr.CimrL1BProductReader;
import org.esa.snap.core.dataio.dimap.DimapProductReader;
import org.esa.snap.core.datamodel.RasterDataNode;
import org.esa.snap.ui.product.ProductSceneView;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;


public class CimrUIManagerTest {


    @Test
    public void testGetCimrReader_returnsReaderOnlyForCimr() {
        ProductSceneView view = mock(ProductSceneView.class);
        RasterDataNode raster = mock(RasterDataNode.class);
        CimrL1BProductReader cimrReader = mock(CimrL1BProductReader.class);
        when(view.getRaster()).thenReturn(raster);
        when(raster.getProductReader()).thenReturn(cimrReader);

        CimrL1BProductReader result = CimrUIManager.getCimrReader(view);

        assertSame(cimrReader, result);
    }

    @Test
    public void testGetCimrReader_returnsNullNoRaster() {
        ProductSceneView view = mock(ProductSceneView.class);
        when(view.getRaster()).thenReturn(null);

        CimrL1BProductReader result = CimrUIManager.getCimrReader(view);

        assertNull(result);
    }

    @Test
    public void testGetCimrReader_returnsNullNotCimrReader() {
        ProductSceneView view = mock(ProductSceneView.class);
        RasterDataNode raster = mock(RasterDataNode.class);
        DimapProductReader reader = mock(DimapProductReader.class);
        when(view.getRaster()).thenReturn(null);
        when(raster.getProductReader()).thenReturn(reader);

        CimrL1BProductReader result = CimrUIManager.getCimrReader(view);

        assertNull(result);
    }
}