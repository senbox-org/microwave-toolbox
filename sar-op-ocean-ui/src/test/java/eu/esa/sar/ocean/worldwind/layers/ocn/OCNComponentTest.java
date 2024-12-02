package eu.esa.sar.ocean.worldwind.layers.ocn;

import com.bc.ceres.annotation.STTM;
import eu.esa.sar.ocean.worldwind.layers.Level2ProductLayer;
import gov.nasa.worldwindx.examples.util.DirectedPath;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.worldwind.ColorBarLegend;
import org.junit.Before;
import org.junit.Test;
import gov.nasa.worldwind.render.Renderable;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.worldwind.ProductRenderablesInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class OCNComponentTest {

    private OCNComponent ocnComponent;
    private Level2ProductLayer mockWWLayer;
    private Map<String, ColorBarLegend> mockColorBarLegendHash;
    private Map<Object, String> mockObjectInfoHash;
    private Map<Object, Product> mockSurfaceProductHash;
    private Map<Object, Integer> mockSurfaceSequenceHash;
    private Product mockProduct;
    private ProductRenderablesInfo mockProdRenderInfo;

    @Before
    public void setUp() {
        mockWWLayer = mock(Level2ProductLayer.class);
        mockColorBarLegendHash = mock(Map.class);
        mockObjectInfoHash = mock(Map.class);
        mockSurfaceProductHash = mock(Map.class);
        mockSurfaceSequenceHash = mock(Map.class);
        mockProduct = mock(Product.class);
        mockProdRenderInfo = mock(ProductRenderablesInfo.class);

        ocnComponent = new OCNComponent(mockWWLayer, mockColorBarLegendHash, mockObjectInfoHash, mockSurfaceProductHash, mockSurfaceSequenceHash) {
            @Override
            public void addProduct(Product product, ProductRenderablesInfo productRenderablesInfo) {
                // Implementation not needed for tests
            }
        };
    }

    @Test
    public void getData_returnsZeroWhenNoValuesElement() {
        MetadataElement mockElement = mock(MetadataElement.class);
        when(mockElement.getElement("Values")).thenReturn(null);

        double result = ocnComponent.getData(mockElement);

        assertEquals(0.0, result, 0.0);
    }

    @Test
    public void createColorSurfaceWithGradient_createsSurfaceAndGradient() {
        GeoPos geoPos1 = new GeoPos(0, 0);
        GeoPos geoPos2 = new GeoPos(1, 1);
        double[] latValues = {0, 1};
        double[] lonValues = {0, 1};
        double[] values = {0.5, 1.0};
        List<Renderable> renderableList = new ArrayList<>();

        ocnComponent.createColorSurfaceWithGradient(geoPos1, geoPos2, latValues, lonValues, values, 2, 2, 0.0, 1.0, true, renderableList, mockProdRenderInfo, "comp");

        assertEquals(1, renderableList.size());
    }

    @Test
    public void createWVColorSurfaceWithGradient_createsSurfaceCorrectly() {
        double[] latValues = {0, 1};
        double[] lonValues = {0, 1};
        double[] values = {0.5, 1.0};
        List<Renderable> renderableList = new ArrayList<>();

        ocnComponent.createWVColorSurfaceWithGradient(mockProduct, latValues, lonValues, values, renderableList, "osw");

        assertEquals(4, renderableList.size());
    }

    @Test
    @STTM("SNAP-2521")
    public void addWaveLengthArrows_createsArrowsCorrectly() {
        double[] latValues = {0.0, 1.0};
        double[] lonValues = {0.0, 1.0};
        double[] waveLengthValues = {4000.0, 8000.0, -999.0};
        double[] waveDirValues = {45.0, 90.0};
        List<Renderable> renderableList = new ArrayList<>();

        ocnComponent.addWaveLengthArrows(latValues, lonValues, waveLengthValues, waveDirValues, renderableList);

        assertEquals(2, renderableList.size());
        assertTrue(renderableList.get(0) instanceof DirectedPath);
        assertTrue(renderableList.get(1) instanceof DirectedPath);
    }
}
