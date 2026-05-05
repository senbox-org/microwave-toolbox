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
import java.io.IOException;
import java.lang.reflect.Field;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;


public class CimrL1BProductReaderTest {


    @Test
    public void testReadBandRasterDataImplCopiesFromSourceImage() throws Exception {
        int width = 4;
        int height = 3;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int value = 1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, value++);
            }
        }

        MultiLevelSource source = new DefaultMultiLevelSource(image, 1);
        MultiLevelImage multiLevelImage = new DefaultMultiLevelImage(source);

        Band band = new TestBand(multiLevelImage);
        CimrL1BProductReader reader = new CimrL1BProductReader( null);

        int destOffsetX = 1;
        int destOffsetY = 1;
        int destWidth = 2;
        int destHeight = 2;

        ProductData destBuffer = ProductData.createInstance(ProductData.TYPE_INT32,
                destWidth * destHeight);

        reader.readBandRasterDataImpl(
                destOffsetX, destOffsetY,
                destWidth, destHeight,
                1, 1,
                band,
                destOffsetX, destOffsetY,
                destWidth, destHeight,
                destBuffer,
                ProgressMonitor.NULL
        );

        int[] expected = new int[destWidth * destHeight];
        int idx = 0;
        for (int y = destOffsetY; y < destOffsetY + destHeight; y++) {
            for (int x = destOffsetX; x < destOffsetX + destWidth; x++) {
                expected[idx++] = image.getRGB(x, y);
            }
        }

        assertArrayEquals(expected, (int[]) destBuffer.getElems());
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