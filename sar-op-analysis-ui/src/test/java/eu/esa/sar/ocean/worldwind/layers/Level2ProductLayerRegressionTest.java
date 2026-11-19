package eu.esa.sar.ocean.worldwind.layers;

import com.bc.ceres.core.ProgressMonitor;
import gov.nasa.worldwind.awt.WorldWindowGLCanvas;
import gov.nasa.worldwindx.examples.analytics.AnalyticSurface;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataAttribute;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
    public void testFailedAddLeavesNoRenderablesBehind() throws Exception {
        final Level2ProductLayer layer = new Level2ProductLayer();
        final List<String> errors = new ArrayList<>();
        layer.errorHandler = (message, e) -> errors.add(message);
        final Product product = createPartiallyBrokenWaveModeProduct();
        layer.getSurfaceProductHash().put(new Object(), product);

        layer.addProduct(product, mock(WorldWindowGLCanvas.class));

        assertTrue(layer.awaitPendingProducts(10000));

        // only the shared info annotation remains; the OSW arrows and vignettes were removed again
        assertEquals(1, errors.size());
        assertEquals(1, layer.getNumRenderables());
        assertEquals(0, layer.getNumProducts());
        assertFalse(layer.getSurfaceProductHash().containsValue(product));

        // a retry (the views re-add products) does not accumulate renderables either
        layer.addProduct(product, mock(WorldWindowGLCanvas.class));
        assertTrue(layer.awaitPendingProducts(10000));
        assertEquals(1, layer.getNumRenderables());
    }

    @Test
    public void testFailureAfterRegistrationAllowsARetry() throws Exception {
        final Level2ProductLayer layer = new Level2ProductLayer();
        final List<String> errors = new ArrayList<>();
        layer.errorHandler = (message, e) -> errors.add(message);
        final Product product = createWaveModeProduct();

        // showing the product fails after all components succeeded (no window to redraw)
        layer.addProduct(product, null);
        assertTrue(layer.awaitPendingProducts(10000));

        assertEquals(1, errors.size());
        assertEquals(0, layer.getNumProducts());
        assertEquals(1, layer.getNumRenderables());

        // the product used to stay registered without renderables, and could never be shown again
        layer.addProduct(product, mock(WorldWindowGLCanvas.class));
        assertTrue(layer.awaitPendingProducts(10000));
        assertEquals(1, errors.size());
        assertEquals(1, layer.getNumProducts());
        assertTrue(layer.getNumRenderables() > 1);
    }

    @Test
    public void testRemoveProductHidesItsInfo() throws Exception {
        final Level2ProductLayer layer = new Level2ProductLayer();
        final Product product = createWaveModeProduct();
        layer.addProduct(product, mock(WorldWindowGLCanvas.class));
        assertTrue(layer.awaitPendingProducts(10000));
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

    /**
     * A wide-swath OCN product with a 8 x 8 wind grid whose bands record the thread they are read on.
     */
    private static Product createWindGridProduct(final List<String> readThreads) {
        final Product product = new Product("iw", "OCN", 8, 8);
        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(product.getMetadataRoot());
        absRoot.setAttributeString(AbstractMetadata.ACQUISITION_MODE, "IW");
        final String[] names = {"owiLon", "owiLat", "owiIncidenceAngle", "owiWindSpeed", "owiWindDirection"};
        final double[] constants = {10, 50, 30, 8, 90};
        for (int b = 0; b < names.length; b++) {
            final String name = names[b];
            final double constant = constants[b];
            product.addBand(new Band(name, ProductData.TYPE_FLOAT32, 8, 8) {
                @Override
                public double[] readPixels(int x, int y, int w, int h, double[] pixels, ProgressMonitor pm) {
                    readThreads.add(Thread.currentThread().getName());
                    for (int i = 0; i < w * h; i++) {
                        // a regular grid of positions 0.01 degrees apart
                        pixels[i] = name.equals("owiLon") ? constant + (i % w) * 0.01 :
                                name.equals("owiLat") ? constant - (i / w) * 0.01 : constant;
                    }
                    return pixels;
                }
            });
        }
        return product;
    }

    @Test
    public void testBandsAreReadOffTheEventDispatchThread() throws Exception {
        final Level2ProductLayer layer = new Level2ProductLayer();
        final List<String> readThreads = Collections.synchronizedList(new ArrayList<>());
        final Product product = createWindGridProduct(readThreads);

        // the views add products on the EDT
        SwingUtilities.invokeAndWait(() -> layer.addProduct(product, mock(WorldWindowGLCanvas.class)));
        assertTrue(layer.awaitPendingProducts(10000));

        assertEquals(5, readThreads.size());
        for (String thread : readThreads) {
            assertTrue(thread, thread.startsWith("WorldWind-OCN"));
        }
        assertEquals(1, layer.getNumProducts());
        // the info annotation, the wind arrow renderables and the wind speed surface
        assertTrue(layer.getNumRenderables() > 2);
    }

    @Test
    public void testRemovalBeforeTheInstallCancelsIt() throws Exception {
        final Level2ProductLayer layer = new Level2ProductLayer();
        final CountDownLatch reading = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final List<String> readThreads = new ArrayList<String>() {
            @Override
            public synchronized boolean add(String thread) {
                reading.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.add(thread);
            }
        };
        final Product product = createWindGridProduct(readThreads);

        SwingUtilities.invokeAndWait(() -> layer.addProduct(product, mock(WorldWindowGLCanvas.class)));
        assertTrue(reading.await(10, TimeUnit.SECONDS));
        // the product is closed while its bands are being read
        SwingUtilities.invokeAndWait(() -> layer.removeProduct(product));
        release.countDown();
        assertTrue(layer.awaitPendingProducts(10000));

        assertEquals(0, layer.getNumProducts());
        assertEquals(1, layer.getNumRenderables());

        // it can be added again
        SwingUtilities.invokeAndWait(() -> layer.addProduct(product, mock(WorldWindowGLCanvas.class)));
        assertTrue(layer.awaitPendingProducts(10000));
        assertEquals(1, layer.getNumProducts());
    }

    @Test
    public void testLegendLimitsChangedWhileReadingAreApplied() throws Exception {
        final Level2ProductLayer layer = new Level2ProductLayer();
        final WorldWindowGLCanvas wwd = mock(WorldWindowGLCanvas.class);
        layer.getControlPanel(wwd);
        final Product product = createWindGridProduct(Collections.synchronizedList(new ArrayList<>()));

        layer.addProduct(product, wwd);
        // the Update button on the EDT, possibly before the product is installed
        SwingUtilities.invokeAndWait(() -> {
            layer.setLimitsChanged(true, false);
            layer.updateLimits(0, 8, 6, wwd);
        });
        assertTrue(layer.awaitPendingProducts(10000));

        // 8 m/s is the maximum of the new range: red, not the middle of the default 0..10
        final AnalyticSurface surface = layer.theProductRenderablesInfoHash.get(product).owiAnalyticSurfaces.get(0);
        assertEquals(Color.RED.getRGB(), surface.getValues().iterator().next().getColor().getRGB());
    }
}
