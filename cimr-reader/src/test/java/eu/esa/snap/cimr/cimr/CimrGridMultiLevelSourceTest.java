package eu.esa.snap.cimr.cimr;


import com.bc.ceres.multilevel.MultiLevelModel;
import com.bc.ceres.multilevel.support.DefaultMultiLevelImage;
import com.bc.ceres.multilevel.support.DefaultMultiLevelModel;
import eu.esa.snap.cimr.grid.GridBandDataSource;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.junit.Test;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;

import static org.junit.Assert.*;


public class CimrGridMultiLevelSourceTest {

    private static final double doubleErr = 1e-6;


    @Test
    public void testLevel0ImageMatchesGridValues() {
        int width = 2;
        int height = 2;

        Product product = new Product("T", "T", width, height);
        Band band = product.addBand("test", ProductData.TYPE_FLOAT64);

        GridBandDataSource grid = new GridBandDataSource() {
            @Override
            public double getSample(int x, int y) {
                return x + 10 * y;
            }

            @Override
            public void setSample(int x, int y, double value) {
            }
        };

        MultiLevelModel model = new DefaultMultiLevelModel(1, new AffineTransform(), width, height);
        CimrGridMultiLevelSource source = new CimrGridMultiLevelSource(model, band, grid);
        DefaultMultiLevelImage mli = new DefaultMultiLevelImage(source);

        RenderedImage level0 = mli.getImage(0);
        assertEquals(width, level0.getWidth());
        assertEquals(height, level0.getHeight());

        Raster raster = level0.getData(new Rectangle(0, 0, width, height));
        assertEquals(0.0,  raster.getSampleDouble(0, 0, 0), doubleErr);
        assertEquals(1.0,  raster.getSampleDouble(1, 0, 0), doubleErr);
        assertEquals(10.0, raster.getSampleDouble(0, 1, 0), doubleErr);
        assertEquals(11.0, raster.getSampleDouble(1, 1, 0), doubleErr);
    }

    @Test
    public void testAttachToBand_setsSourceImageAndUsesGridValues() {
        int width = 2;
        int height = 2;

        Product product = new Product("T", "T", width, height);
        Band band = product.addBand("test", ProductData.TYPE_FLOAT64);

        GridBandDataSource grid = new GridBandDataSource() {
            @Override
            public double getSample(int x, int y) {
                return x + 10 * y;
            }

            @Override
            public void setSample(int x, int y, double value) {
                // not needed for this test
            }
        };

        MultiLevelModel model = new DefaultMultiLevelModel(1, new AffineTransform(), width, height);

        CimrGridMultiLevelSource.attachToBand(band, grid, model);

        assertNotNull(band.getSourceImage());
        assertTrue(band.getSourceImage() instanceof DefaultMultiLevelImage);

        DefaultMultiLevelImage mli = (DefaultMultiLevelImage) band.getSourceImage();
        RenderedImage level0 = mli.getImage(0);
        assertEquals(width, level0.getWidth());
        assertEquals(height, level0.getHeight());

        Raster raster = level0.getData(new Rectangle(0, 0, width, height));
        assertEquals(0.0,  raster.getSampleDouble(0, 0, 0), doubleErr);
        assertEquals(1.0,  raster.getSampleDouble(1, 0, 0), doubleErr);
        assertEquals(10.0, raster.getSampleDouble(0, 1, 0), doubleErr);
        assertEquals(11.0, raster.getSampleDouble(1, 1, 0), doubleErr);
    }
}