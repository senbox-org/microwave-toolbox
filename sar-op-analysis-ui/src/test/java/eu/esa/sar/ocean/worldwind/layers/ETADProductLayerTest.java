package eu.esa.sar.ocean.worldwind.layers;

import gov.nasa.worldwind.awt.WorldWindowGLCanvas;
import gov.nasa.worldwind.render.Path;
import gov.nasa.worldwind.render.Renderable;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class ETADProductLayerTest {

    private static MetadataElement coordinate(double lat, double lon) {
        final MetadataElement coordinate = new MetadataElement("coordinates");
        final MetadataElement latitude = new MetadataElement("latitude");
        latitude.setAttributeDouble("latitude", lat);
        final MetadataElement longitude = new MetadataElement("longitude");
        longitude.setAttributeDouble("longitude", lon);
        coordinate.addElement(latitude);
        coordinate.addElement(longitude);
        return coordinate;
    }

    private static MetadataElement burst(MetadataElement... coordinates) {
        final MetadataElement burst = new MetadataElement("etadBurst");
        final MetadataElement burstCoverage = new MetadataElement("burstCoverage");
        final MetadataElement spatialCoverage = new MetadataElement("spatialCoverage");
        for (MetadataElement coordinate : coordinates) {
            spatialCoverage.addElement(coordinate);
        }
        burstCoverage.addElement(spatialCoverage);
        burst.addElement(burstCoverage);
        return burst;
    }

    private static Product createProduct(String name, MetadataElement... bursts) {
        final Product product = new Product(name, "ETAD", 10, 10);
        final MetadataElement orig = AbstractMetadata.getOriginalProductMetadata(product);
        if (bursts != null) {
            final MetadataElement annotation = new MetadataElement("annotation");
            final MetadataElement etadProduct = new MetadataElement("etadProduct");
            final MetadataElement burstList = new MetadataElement("etadBurstList");
            for (MetadataElement burst : bursts) {
                burstList.addElement(burst);
            }
            etadProduct.addElement(burstList);
            annotation.addElement(etadProduct);
            orig.addElement(annotation);
        }
        return product;
    }

    private static Product createValidProduct(String name) {
        return createProduct(name,
                             burst(coordinate(10, 20), coordinate(10, 21), coordinate(11, 20), coordinate(11, 21)),
                             // too few coordinates
                             burst(coordinate(10, 20), coordinate(10, 21), coordinate(11, 20)),
                             // missing longitude value
                             burst(coordinate(10, 20), coordinate(10, Double.NaN), coordinate(11, 20), coordinate(11, 21)),
                             // no coverage at all
                             new MetadataElement("etadBurst"));
    }

    @Test
    public void testInvalidBurstsAreSkipped() {
        final ETADProductLayer layer = new ETADProductLayer();

        layer.addProduct(createValidProduct("etad"), mock(WorldWindowGLCanvas.class));

        assertEquals(1, layer.getNumRenderables());
    }

    @Test
    public void testProductWithoutBurstListIsAddedOnce() {
        final ETADProductLayer layer = new ETADProductLayer();
        final Product product = createProduct("etad", (MetadataElement[]) null);

        layer.addProduct(product, mock(WorldWindowGLCanvas.class));
        layer.addProduct(product, mock(WorldWindowGLCanvas.class));
        layer.removeProduct(product);

        assertEquals(0, layer.getNumRenderables());
    }

    @Test
    public void testRenamedProductIsRemoved() {
        final ETADProductLayer layer = new ETADProductLayer();
        final Product product = createValidProduct("etad");
        product.setRefNo(3);
        layer.addProduct(product, mock(WorldWindowGLCanvas.class));

        // the outline used to be looked up by the current name and reference number: renaming the product, or the
        // product manager resetting the reference number before the removal reaches the layer, left it behind
        product.setName("renamed");
        product.resetRefNo();
        layer.removeProduct(product);

        assertEquals(0, layer.getNumRenderables());

        // re-adding draws it once, not twice
        layer.addProduct(product, mock(WorldWindowGLCanvas.class));
        layer.addProduct(product, mock(WorldWindowGLCanvas.class));
        assertEquals(1, layer.getNumRenderables());
    }

    @Test
    public void testSelection() {
        final ETADProductLayer layer = new ETADProductLayer();
        final Product first = createValidProduct("first");
        final Product second = createValidProduct("second");
        layer.addProduct(first, mock(WorldWindowGLCanvas.class));
        layer.addProduct(second, mock(WorldWindowGLCanvas.class));

        layer.setSelectedProduct(first);
        assertEquals(1, countHighlighted(layer));

        // deselecting used to leave the last selection highlighted
        layer.setSelectedProduct(null);
        assertEquals(0, countHighlighted(layer));

        // the outlines keep their normal colour; only the highlight is green
        for (Renderable renderable : layer.getRenderables()) {
            final Path path = (Path) renderable;
            assertFalse(path.getAttributes().getOutlineMaterial().equals(path.getHighlightAttributes().getOutlineMaterial()));
        }
    }

    private static int countHighlighted(ETADProductLayer layer) {
        int count = 0;
        for (Renderable renderable : layer.getRenderables()) {
            if (((Path) renderable).isHighlighted()) {
                count++;
            }
        }
        assertTrue(count <= 2);
        return count;
    }
}
