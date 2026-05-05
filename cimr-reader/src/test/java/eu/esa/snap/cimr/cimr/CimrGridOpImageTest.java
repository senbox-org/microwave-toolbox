package eu.esa.snap.cimr.cimr;


import com.bc.ceres.multilevel.MultiLevelModel;
import com.bc.ceres.multilevel.support.DefaultMultiLevelImage;
import com.bc.ceres.multilevel.support.DefaultMultiLevelModel;
import eu.esa.snap.cimr.grid.GridBandDataSource;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.image.ResolutionLevel;
import org.junit.Test;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;

import static org.junit.Assert.*;


public class CimrGridOpImageTest {

    private static final double doubleErr = 1e-8;


    @Test
    public void testLevel0UsesBaseGridValues() throws Exception {
        int width = 4;
        int height = 4;
        Product product = new Product("P", "T", width, height);
        Band band = product.addBand("b", ProductData.TYPE_FLOAT64);

        SimpleGrid grid = new SimpleGrid(width, height);
        CimrGridOpImage opImage = createOpImageLevel(band, 0, width, height, grid);

        ProductData data = ProductData.createInstance(ProductData.TYPE_FLOAT64, width * height);
        Rectangle rect = new Rectangle(0, 0, width, height);

        opImage.computeProductData(data, rect);

        assertEquals(0.0, data.getElemDoubleAt(0), doubleErr);
        assertEquals(1.0, data.getElemDoubleAt(1), doubleErr);
        assertEquals(10.0, data.getElemDoubleAt(4), doubleErr);
        assertEquals(33.0, data.getElemDoubleAt(3 + 3 * width), doubleErr);
    }

    @Test
    public void testLevel1AveragesBlocks() throws Exception {
        int baseWidth = 4;
        int baseHeight = 4;
        Product product = new Product("P", "T", baseWidth, baseHeight);
        Band band = product.addBand("b", ProductData.TYPE_FLOAT64);

        SimpleGrid grid = new SimpleGrid(baseWidth, baseHeight);

        int levelIndex = 1;
        CimrGridOpImage opImage = createOpImageLevel(band, levelIndex, baseWidth, baseHeight, grid);

        int w = baseWidth / 2;
        int h = baseHeight / 2;
        ProductData data = ProductData.createInstance(ProductData.TYPE_FLOAT64, w * h);
        Rectangle rect = new Rectangle(0, 0, w, h);

        opImage.computeProductData(data, rect);


        assertEquals(5.5, data.getElemDoubleAt(0), doubleErr);
        assertEquals(7.5, data.getElemDoubleAt(1), doubleErr);
        assertEquals(25.5, data.getElemDoubleAt(2), doubleErr);
        assertEquals(27.5, data.getElemDoubleAt(3), doubleErr);
    }


    @Test
    public void testIllegalArgumentFromGridDataSourceProducesNaN() {
        int width = 2;
        int height = 2;

        Product product = new Product("T", "T", width, height);
        Band band = product.addBand("test", ProductData.TYPE_FLOAT64);

        GridBandDataSource grid = new GridBandDataSource() {
            @Override
            public double getSample(int x, int y) {
                if (x == 1 && y == 0) {
                    throw new IllegalArgumentException("Test exception");
                }
                return 42.0;
            }

            @Override
            public void setSample(int x, int y, double value) {
                // not needed for this test
            }
        };

        MultiLevelModel model = new DefaultMultiLevelModel(1, new AffineTransform(), width, height);
        CimrGridMultiLevelSource source = new CimrGridMultiLevelSource(model, band, grid);
        DefaultMultiLevelImage mli = new DefaultMultiLevelImage(source);

        RenderedImage level0 = mli.getImage(0);
        Raster raster = level0.getData(new Rectangle(0, 0, width, height));


        assertEquals(42.0, raster.getSampleDouble(0, 0, 0), doubleErr);
        assertTrue(Double.isNaN(raster.getSampleDouble(1, 0, 0)));
        assertEquals(42.0, raster.getSampleDouble(0, 1, 0), doubleErr);
        assertEquals(42.0, raster.getSampleDouble(1, 1, 0), doubleErr);
    }


    private static class SimpleGrid implements GridBandDataSource {

        private final int width;
        private final int height;
        private final double[] data;

        SimpleGrid(int width, int height) {
            this.width = width;
            this.height = height;
            this.data = new double[width * height];

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    data[y * width + x] = x + 10.0 * y;
                }
            }
        }

        @Override
        public double getSample(int x, int y) {
            return data[y * width + x];
        }

        @Override
        public void setSample(int x, int y, double value) {
            data[y * width + x] = value;
        }
    }

    private CimrGridOpImage createOpImageLevel(Band band, int levelIndex, int baseWidth, int baseHeight, GridBandDataSource grid) {
        AffineTransform at = new AffineTransform();
        MultiLevelModel model = new DefaultMultiLevelModel(2, at, baseWidth, baseHeight);
        ResolutionLevel level = ResolutionLevel.create(model, levelIndex);
        return new CimrGridOpImage(band, level, grid);
    }
}