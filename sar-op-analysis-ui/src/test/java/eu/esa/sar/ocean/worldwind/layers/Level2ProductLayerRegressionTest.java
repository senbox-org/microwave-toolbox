package eu.esa.sar.ocean.worldwind.layers;

import gov.nasa.worldwind.awt.WorldWindowGLCanvas;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataAttribute;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class Level2ProductLayerRegressionTest {

    private static MetadataElement value(String name, double v) {
        final MetadataElement element = new MetadataElement(name);
        final MetadataElement values = new MetadataElement("Values");
        values.addAttribute(new MetadataAttribute("data", ProductData.createInstance(new double[]{v}), true));
        element.addElement(values);
        return element;
    }

    /**
     * A wave mode OCN product whose OSW part renders.
     */
    private static Product createWaveModeProduct() {
        final Product product = new Product("ocn", "OCN", 10, 10);
        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(product.getMetadataRoot());
        absRoot.setAttributeString(AbstractMetadata.ACQUISITION_MODE, "WV");

        final MetadataElement orig = new MetadataElement("Original_Product_Metadata");
        final MetadataElement annotation = new MetadataElement("annotation");
        final MetadataElement imagette = new MetadataElement("imagette1");
        imagette.addElement(value("oswLat", 40));
        imagette.addElement(value("oswLon", 10));
        imagette.addElement(value("oswHs", 2));
        imagette.addElement(value("oswWl", 200));
        imagette.addElement(value("oswDirmet", 45));
        annotation.addElement(imagette);
        orig.addElement(annotation);
        product.getMetadataRoot().addElement(orig);
        return product;
    }

    /**
     * A wave mode OCN product whose OSW part renders, but whose OWI part fails: its wind speed band does not match
     * the position bands.
     */
    private static Product createPartiallyBrokenWaveModeProduct() {
        final Product product = createWaveModeProduct();
        for (String name : new String[]{"owiLon", "owiLat", "owiIncidenceAngle", "owiWindDirection"}) {
            product.addBand(new Band(name, ProductData.TYPE_FLOAT32, 10, 10));
        }
        product.addBand(new Band("owiWindSpeed", ProductData.TYPE_FLOAT32, 5, 5));
        return product;
    }

    @Test
    public void testFailedAddLeavesNoRenderablesBehind() {
        final Level2ProductLayer layer = new Level2ProductLayer();
        final List<String> errors = new ArrayList<>();
        layer.errorHandler = (message, e) -> errors.add(message);
        final Product product = createPartiallyBrokenWaveModeProduct();
        layer.getSurfaceProductHash().put(new Object(), product);

        layer.addProduct(product, mock(WorldWindowGLCanvas.class));

        // only the shared info annotation remains; the OSW arrows and vignettes were removed again
        assertEquals(1, errors.size());
        assertEquals(1, layer.getNumRenderables());
        assertEquals(0, layer.getNumProducts());
        assertFalse(layer.getSurfaceProductHash().containsValue(product));

        // a retry (the views re-add products) does not accumulate renderables either
        layer.addProduct(product, mock(WorldWindowGLCanvas.class));
        assertEquals(1, layer.getNumRenderables());
    }

    @Test
    public void testFailureAfterRegistrationAllowsARetry() {
        final Level2ProductLayer layer = new Level2ProductLayer();
        final List<String> errors = new ArrayList<>();
        layer.errorHandler = (message, e) -> errors.add(message);
        final Product product = createWaveModeProduct();

        // showing the product fails after all components succeeded (no window to redraw)
        layer.addProduct(product, null);

        assertEquals(1, errors.size());
        assertEquals(0, layer.getNumProducts());
        assertEquals(1, layer.getNumRenderables());

        // the product used to stay registered without renderables, and could never be shown again
        layer.addProduct(product, mock(WorldWindowGLCanvas.class));
        assertEquals(1, errors.size());
        assertEquals(1, layer.getNumProducts());
        assertTrue(layer.getNumRenderables() > 1);
    }

    @Test
    public void testRemoveProductHidesItsInfo() {
        final Level2ProductLayer layer = new Level2ProductLayer();
        final Product product = createWaveModeProduct();
        layer.addProduct(product, mock(WorldWindowGLCanvas.class));
        layer.theInfoAnnotation.getAttributes().setVisible(true);

        layer.removeProduct(product);

        assertFalse(layer.theInfoAnnotation.getAttributes().isVisible());
        assertEquals(1, layer.getNumRenderables());
    }

    @Test
    public void testRemoveProductWithoutControlPanel() {
        final Level2ProductLayer layer = new Level2ProductLayer();
        final Product product = new Product("p", "OCN", 10, 10);

        // the control panel is only created by the analysis view; this used to throw a NullPointerException
        layer.removeProduct(product);
        assertEquals(0, layer.getNumProducts());
    }

    @Test
    public void testUpdateLimits() {
        final Level2ProductLayer layer = new Level2ProductLayer();
        final WorldWindowGLCanvas wwd = mock(WorldWindowGLCanvas.class);
        layer.getControlPanel(wwd);
        final List<String> warnings = new ArrayList<>();
        layer.warningHandler = warnings::add;

        // no component selected yet: this used to throw a NullPointerException
        layer.setLimitsChanged(true, true);
        layer.updateLimits(2, 20, 4, wwd);
        assertEquals(2, layer.theColorBarLegendHash.get("owi").getMinValue(), 0.0);
        assertEquals(20, layer.theColorBarLegendHash.get("owi").getMaxValue(), 0.0);
        assertEquals(-4, layer.theColorBarLegendHash.get("rvl").getMinValue(), 0.0);
        assertEquals(4, layer.theColorBarLegendHash.get("rvl").getMaxValue(), 0.0);
        assertTrue(warnings.isEmpty());

        // an invalid range is rejected and stays pending until it is corrected
        layer.setLimitsChanged(true, false);
        layer.updateLimits(10, 10, 4, wwd);
        assertEquals(1, warnings.size());
        assertEquals(20, layer.theColorBarLegendHash.get("owi").getMaxValue(), 0.0);
        layer.updateLimits(10, 12, 4, wwd);
        assertEquals(12, layer.theColorBarLegendHash.get("owi").getMaxValue(), 0.0);
        assertEquals(1, warnings.size());
    }
}
