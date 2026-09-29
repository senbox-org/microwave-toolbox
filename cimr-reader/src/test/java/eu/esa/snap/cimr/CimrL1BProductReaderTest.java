package eu.esa.snap.cimr;

import com.bc.ceres.core.ProgressMonitor;
import com.bc.ceres.multilevel.MultiLevelImage;
import com.bc.ceres.multilevel.MultiLevelSource;
import com.bc.ceres.multilevel.support.DefaultMultiLevelImage;
import com.bc.ceres.multilevel.support.DefaultMultiLevelSource;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.ProductData;
import org.junit.Test;
import ucar.nc2.NetcdfFile;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.lang.reflect.Field;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;


public class CimrL1BProductReaderTest {


    @Test
    public void testReadBandRasterDataImplCopiesFromSourceImage() throws Exception {
        int width = 4;
        int height = 3;
        BufferedImage image = createSampleImage(width, height);
        Band band = new TestBand(createMultiLevelImage(image));
        CimrL1BProductReader reader = new CimrL1BProductReader( null);

        int sourceOffsetX = 1;
        int sourceOffsetY = 1;
        int sourceWidth = 2;
        int sourceHeight = 2;
        int destWidth = 2;
        int destHeight = 2;

        ProductData destBuffer = ProductData.createInstance(ProductData.TYPE_FLOAT64, destWidth * destHeight);
        reader.readBandRasterDataImpl(sourceOffsetX, sourceOffsetY, sourceWidth, sourceHeight, 1, 1, band, 0, 0, destWidth, destHeight, destBuffer, ProgressMonitor.NULL);

        double[] expected = {sampleValue(1, 1), sampleValue(2, 1), sampleValue(1, 2), sampleValue(2, 2)};
        assertArrayEquals(expected, (double[]) destBuffer.getElems(), 1e-8);
    }

    @Test
    public void testReadBandRasterDataImplUsesSourceOffsetsAndSteps() throws Exception {
        int width = 6;
        int height = 5;
        BufferedImage image = createSampleImage(width, height);
        Band band = new TestBand(createMultiLevelImage(image));
        CimrL1BProductReader reader = new CimrL1BProductReader(null);

        ProductData destBuffer = ProductData.createInstance(ProductData.TYPE_FLOAT64, 4);
        reader.readBandRasterDataImpl(1, 1, 4, 4, 2, 2, band, 7, 9, 2, 2, destBuffer, ProgressMonitor.NULL);

        double[] expected = {sampleValue(1, 1), sampleValue(3, 1), sampleValue(1, 3), sampleValue(3, 3)};
        assertArrayEquals(expected, (double[]) destBuffer.getElems(), 1e-8);
    }

    @Test
    public void close_closesNcFileAndClearsContextAndNullsFields() throws IOException, NoSuchFieldException, IllegalAccessException {
        CimrL1BProductReader reader = new CimrL1BProductReader(null);

        NetcdfFile ncFile = mock(NetcdfFile.class);
        CimrReaderContext ctx = mock(CimrReaderContext.class);

        setField(reader, "ncFile", ncFile);
        setField(reader, "readerContext", ctx);

        assertNotNull(getField(reader, "ncFile"));
        assertNotNull(getField(reader, "readerContext"));

        reader.close();

        verify(ncFile).close();
        verify(ctx).clearCache();
        assertNull(getField(reader, "ncFile"));
        assertNull(getField(reader, "readerContext"));
    }

    @Test
    public void close_NullFields() throws IOException {
        CimrL1BProductReader reader = new CimrL1BProductReader(null);

        reader.close();
        reader.close();
    }



    private static class TestBand extends Band {
        private final MultiLevelImage sourceImage;

        TestBand(MultiLevelImage sourceImage) {
            super("test_band", ProductData.TYPE_INT32, sourceImage.getWidth(), sourceImage.getHeight());
            this.sourceImage = sourceImage;
        }

        @Override
        public MultiLevelImage getSourceImage() {
            return sourceImage;
        }
    }

    private static MultiLevelImage createMultiLevelImage(BufferedImage image) {
        MultiLevelSource source = new DefaultMultiLevelSource(image, 1);
        return new DefaultMultiLevelImage(source);
    }

    private static BufferedImage createSampleImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster raster = image.getRaster();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                raster.setSample(x, y, 0, sampleValue(x, y));
            }
        }
        return image;
    }

    private static int sampleValue(int x, int y) {
        return y * 10 + x;
    }

    private static void setField(Object target, String name, Object value) throws NoSuchFieldException, IllegalAccessException {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object getField(Object target, String name) throws NoSuchFieldException, IllegalAccessException {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}