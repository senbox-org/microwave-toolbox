package eu.esa.snap.cimr.grid;


import com.bc.ceres.annotation.STTM;
import com.bc.ceres.multilevel.MultiLevelModel;
import com.bc.ceres.multilevel.support.DefaultMultiLevelImage;
import com.bc.ceres.multilevel.support.DefaultMultiLevelModel;
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
    @STTM("SNAP-4262")
    public void testLevel1UsesNearestSourceCodeForCategoricalData() throws Exception {
        Product product = new Product("P", "T", 2, 2);
        Band band = product.addBand("status", ProductData.TYPE_INT32);
        StateAwareGrid grid = new StateAwareGrid(2, 2,
                                                  new double[]{0.0, 4.0, 1.0, 5.0},
                                                  new GridSampleState[]{
                                                          GridSampleState.VALID, GridSampleState.VALID,
                                                          GridSampleState.VALID, GridSampleState.VALID
                                                  });
        CimrGridOpImage opImage = createOpImageLevel(band, 1, 2, 2, grid, CimrOverviewResampling.CATEGORICAL_NEAREST);

        ProductData data = ProductData.createInstance(ProductData.TYPE_INT32, 1);
        opImage.computeProductData(data, new Rectangle(0, 0, 1, 1));

        assertEquals(0.0, data.getElemDoubleAt(0), doubleErr);
    }

    @Test
    @STTM("SNAP-4262")
    public void testLevel1MarksCoverageWhenAnySourcePixelIsCovered() throws Exception {
        Product product = new Product("P", "T", 2, 2);
        Band band = product.addBand("coverage", ProductData.TYPE_UINT8);
        StateAwareGrid source = new StateAwareGrid(2, 2,
                                                    new double[]{Double.NaN, Double.NaN, Double.NaN, Double.NaN},
                                                    new GridSampleState[]{
                                                            GridSampleState.EMPTY, GridSampleState.EMPTY,
                                                            GridSampleState.INVALID, GridSampleState.EMPTY
                                                    });
        CimrGridOpImage opImage = createOpImageLevel(band, 1, 2, 2, new GridCoverageDataSource(source), CimrOverviewResampling.COVERAGE_ANY);

        ProductData data = ProductData.createInstance(ProductData.TYPE_UINT8, 1);
        opImage.computeProductData(data, new Rectangle(0, 0, 1, 1));

        assertEquals(1.0, data.getElemDoubleAt(0), doubleErr);
    }

    @Test
    @STTM("SNAP-4262")
    public void testLevel0UsesSampleState() throws Exception {
        int width = 2;
        int height = 1;
        Product product = new Product("P", "T", width, height);
        Band band = product.addBand("b", ProductData.TYPE_FLOAT64);

        StateAwareGrid grid = new StateAwareGrid(width, height, new double[]{1.0, 99.0}, new GridSampleState[]{GridSampleState.VALID, GridSampleState.INVALID});
        CimrGridOpImage opImage = createOpImageLevel(band, 0, width, height, grid);

        ProductData data = ProductData.createInstance(ProductData.TYPE_FLOAT64, width * height);
        opImage.computeProductData(data, new Rectangle(0, 0, width, height));

        assertEquals(1.0, data.getElemDoubleAt(0), doubleErr);
        assertTrue(Double.isNaN(data.getElemDoubleAt(1)));
    }

    @Test
    @STTM("SNAP-4262")
    public void testLevel1AveragesOnlyValidSamples() throws Exception {
        int baseWidth = 2;
        int baseHeight = 2;
        Product product = new Product("P", "T", baseWidth, baseHeight);
        Band band = product.addBand("b", ProductData.TYPE_FLOAT64);

        StateAwareGrid grid = new StateAwareGrid(baseWidth, baseHeight,
                new double[]{
                        1.0, 100.0,
                        3.0, 200.0
                },
                new GridSampleState[]{
                        GridSampleState.VALID, GridSampleState.INVALID,
                        GridSampleState.VALID, GridSampleState.EMPTY
                });
        CimrGridOpImage opImage = createOpImageLevel(band, 1, baseWidth, baseHeight, grid);

        ProductData data = ProductData.createInstance(ProductData.TYPE_FLOAT64, 1);
        opImage.computeProductData(data, new Rectangle(0, 0, 1, 1));

        assertEquals(2.0, data.getElemDoubleAt(0), doubleErr);
    }

    @Test
    @STTM("SNAP-4262")
    public void testLevel1AllInvalidOrEmptyProducesNaN() throws Exception {
        int baseWidth = 2;
        int baseHeight = 2;
        Product product = new Product("P", "T", baseWidth, baseHeight);
        Band band = product.addBand("b", ProductData.TYPE_FLOAT64);

        StateAwareGrid grid = new StateAwareGrid(baseWidth, baseHeight,
                new double[]{
                        10.0, 20.0,
                        30.0, 40.0
                },
                new GridSampleState[]{
                        GridSampleState.INVALID, GridSampleState.EMPTY,
                        GridSampleState.INVALID, GridSampleState.EMPTY
                });
        CimrGridOpImage opImage = createOpImageLevel(band, 1, baseWidth, baseHeight, grid);

        ProductData data = ProductData.createInstance(ProductData.TYPE_FLOAT64, 1);
        opImage.computeProductData(data, new Rectangle(0, 0, 1, 1));

        assertTrue(Double.isNaN(data.getElemDoubleAt(0)));
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

    private static class StateAwareGrid implements GridBandDataSource {

        private final int width;
        private final double[] data;
        private final GridSampleState[] states;

        StateAwareGrid(int width, int height, double[] data, GridSampleState[] states) {
            if (data.length != width * height || states.length != width * height) {
                throw new IllegalArgumentException("data and states must match width * height");
            }
            this.width = width;
            this.data = data;
            this.states = states;
        }

        @Override
        public double getSample(int x, int y) {
            return data[y * width + x];
        }

        @Override
        public void setSample(int x, int y, double value) {
            data[y * width + x] = value;
        }

        @Override
        public GridSampleState getSampleState(int x, int y) {
            return states[y * width + x];
        }
    }

    private CimrGridOpImage createOpImageLevel(Band band, int levelIndex, int baseWidth, int baseHeight, GridBandDataSource grid) {
        return createOpImageLevel(band, levelIndex, baseWidth, baseHeight, grid, CimrOverviewResampling.CONTINUOUS_AVERAGE);
    }

    private CimrGridOpImage createOpImageLevel(Band band, int levelIndex, int baseWidth, int baseHeight, GridBandDataSource grid, CimrOverviewResampling overviewResampling) {
        AffineTransform at = new AffineTransform();
        MultiLevelModel model = new DefaultMultiLevelModel(2, at, baseWidth, baseHeight);
        ResolutionLevel level = ResolutionLevel.create(model, levelIndex);
        return new CimrGridOpImage(band, level, grid, overviewResampling);
    }
}