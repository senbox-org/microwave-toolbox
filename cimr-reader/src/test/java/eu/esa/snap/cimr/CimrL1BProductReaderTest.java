package eu.esa.snap.cimr;

import com.bc.ceres.annotation.STTM;
import com.bc.ceres.core.ProgressMonitor;
import com.bc.ceres.multilevel.MultiLevelImage;
import com.bc.ceres.multilevel.MultiLevelSource;
import com.bc.ceres.multilevel.support.DefaultMultiLevelImage;
import com.bc.ceres.multilevel.support.DefaultMultiLevelSource;
import eu.esa.snap.cimr.cimr.CimrFootprints;
import eu.esa.snap.cimr.cimr.CimrGridProduct;
import eu.esa.snap.cimr.cimr.CimrSnapProductBuilder;
import eu.esa.snap.cimr.dddb.descriptor.CimrBandDescriptor;
import eu.esa.snap.cimr.dddb.descriptor.CimrDescriptorSet;
import eu.esa.snap.cimr.metadata.CimrProductMetadata;
import eu.esa.snap.cimr.metadata.CimrProductMetadataReader;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.dataio.netcdf.util.NetcdfFileOpener;
import org.junit.Test;
import org.mockito.MockedStatic;
import ucar.nc2.NetcdfFile;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;

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

    @Test
    public void close_clearsContextAndNullsFieldsWhenNcFileCloseFails() throws Exception {
        CimrL1BProductReader reader = new CimrL1BProductReader(null);

        NetcdfFile ncFile = mock(NetcdfFile.class);
        CimrReaderContext ctx = mock(CimrReaderContext.class);
        IOException closeFailure = new IOException("close failed");
        doThrow(closeFailure).when(ncFile).close();

        setField(reader, "ncFile", ncFile);
        setField(reader, "readerContext", ctx);

        IOException actual = assertThrows(IOException.class, reader::close);

        assertSame(closeFailure, actual);
        verify(ctx).clearCache();
        assertNull(getField(reader, "ncFile"));
        assertNull(getField(reader, "readerContext"));
    }

    @Test
    @STTM("SNAP-4262")
    public void readProductNodesImpl_buildsProductAndKeepsNetcdfOpenForLazyReads() throws Exception {
        TestReader reader = new TestReader();
        reader.setReaderInput("test-product.nc");

        NetcdfFile ncFile = mock(NetcdfFile.class);
        CimrReaderContext context = mock(CimrReaderContext.class);
        CimrGridProduct gridProduct = mock(CimrGridProduct.class);
        CimrProductMetadata metadata = new CimrProductMetadata("TEST", "CIMR_L1B", new MetadataElement("CIMR_Metadata"));
        Product product = new Product("TEST", "CIMR_L1B", 1, 1);
        when(context.getAutoGrouping()).thenReturn("L_BAND");

        try (MockedStatic<NetcdfFileOpener> opener = mockStatic(NetcdfFileOpener.class);
             MockedStatic<CimrReaderContextFactory> contextFactory = mockStatic(CimrReaderContextFactory.class);
             MockedStatic<CimrGridProduct> gridProductFactory = mockStatic(CimrGridProduct.class);
             MockedStatic<CimrProductMetadataReader> metadataReader = mockStatic(CimrProductMetadataReader.class);
             MockedStatic<CimrSnapProductBuilder> productBuilder = mockStatic(CimrSnapProductBuilder.class)) {

            opener.when(() -> NetcdfFileOpener.open("test-product.nc")).thenReturn(ncFile);
            contextFactory.when(() -> CimrReaderContextFactory.create(ncFile)).thenReturn(context);
            gridProductFactory.when(() -> CimrGridProduct.buildLazy(context, false)).thenReturn(gridProduct);
            metadataReader.when(() -> CimrProductMetadataReader.read(ncFile, "test-product.nc", CimrL1BProductReader.PRODUCT_TYPE)).thenReturn(metadata);
            productBuilder.when(() -> CimrSnapProductBuilder.buildProduct(context, gridProduct, metadata, "test-product.nc")).thenReturn(product);

            Product actual = reader.exposedReadProductNodesImpl();

            assertSame(product, actual);
            assertSame(ncFile, getField(reader, "ncFile"));
            assertSame(context, getField(reader, "readerContext"));
            verify(ncFile, never()).close();
        }
    }

    @Test
    @STTM("SNAP-4262")
    public void readProductNodesImpl_usesFileInputPath() throws Exception {
        TestReader reader = new TestReader();
        java.io.File inputFile = new java.io.File("test-product.nc");
        reader.setReaderInput(inputFile);

        NetcdfFile ncFile = mock(NetcdfFile.class);
        RuntimeException readFailure = new RuntimeException("stop after open");

        try (MockedStatic<NetcdfFileOpener> opener = mockStatic(NetcdfFileOpener.class);
             MockedStatic<CimrReaderContextFactory> contextFactory = mockStatic(CimrReaderContextFactory.class)) {

            opener.when(() -> NetcdfFileOpener.open(inputFile.getPath())).thenReturn(ncFile);
            contextFactory.when(() -> CimrReaderContextFactory.create(ncFile)).thenThrow(readFailure);

            IOException actual = assertThrows(IOException.class, reader::exposedReadProductNodesImpl);

            assertSame(readFailure, actual.getCause());
            opener.verify(() -> NetcdfFileOpener.open(inputFile.getPath()));
        }
    }

    @Test
    @STTM("SNAP-4262")
    public void readProductNodesImpl_rejectsUnsupportedInputType() {
        TestReader reader = new TestReader();
        Object unsupportedInput = new Object();
        reader.setReaderInput(unsupportedInput);

        IllegalArgumentException actual = assertThrows(IllegalArgumentException.class, reader::exposedReadProductNodesImpl);

        assertTrue(actual.getMessage().contains("Unsupported input"));
        assertTrue(actual.getMessage().contains(unsupportedInput.toString()));
    }

    @Test
    @STTM("SNAP-4262")
    public void readProductNodesImpl_closesNetcdfAfterFailedReadAndPreservesCloseFailure() throws Exception {
        TestReader reader = new TestReader();
        reader.setReaderInput("test-product.nc");

        NetcdfFile ncFile = mock(NetcdfFile.class);
        RuntimeException readFailure = new RuntimeException("read failed");
        IOException closeFailure = new IOException("close failed");
        doThrow(closeFailure).when(ncFile).close();

        try (MockedStatic<NetcdfFileOpener> opener = mockStatic(NetcdfFileOpener.class);
             MockedStatic<CimrReaderContextFactory> contextFactory = mockStatic(CimrReaderContextFactory.class)) {

            opener.when(() -> NetcdfFileOpener.open("test-product.nc")).thenReturn(ncFile);
            contextFactory.when(() -> CimrReaderContextFactory.create(ncFile)).thenThrow(readFailure);

            IOException actual = assertThrows(IOException.class, reader::exposedReadProductNodesImpl);

            assertSame(readFailure, actual.getCause());
            assertArrayEquals(new Throwable[]{closeFailure}, actual.getCause().getSuppressed());
            assertNull(getField(reader, "ncFile"));
            assertNull(getField(reader, "readerContext"));
        }
    }

    @Test
    @STTM("SNAP-4262")
    public void getFootprints_returnsMeasurementFootprints() throws Exception {
        CimrL1BProductReader reader = new CimrL1BProductReader(null);
        CimrReaderContext context = mock(CimrReaderContext.class);
        CimrDescriptorSet descriptorSet = mock(CimrDescriptorSet.class);
        CimrBandDescriptor descriptor = mock(CimrBandDescriptor.class);
        CimrFootprints footprints = new CimrFootprints(List.of(), List.of(1.0, 2.0));

        when(context.getDescriptorSet()).thenReturn(descriptorSet);
        when(descriptorSet.getMeasurementByName("measurement")).thenReturn(descriptor);
        when(context.getOrCreateFootprints(descriptor)).thenReturn(footprints);
        setField(reader, "readerContext", context);

        assertSame(footprints, reader.getFootprints("measurement"));
    }

    @Test
    @STTM("SNAP-4262")
    public void getFootprints_fallsBackToTiepointVariableFootprints() throws Exception {
        CimrL1BProductReader reader = new CimrL1BProductReader(null);
        CimrReaderContext context = mock(CimrReaderContext.class);
        CimrDescriptorSet descriptorSet = mock(CimrDescriptorSet.class);
        CimrBandDescriptor descriptor = mock(CimrBandDescriptor.class);
        CimrFootprints footprints = new CimrFootprints(List.of(), List.of(3.0));

        when(context.getDescriptorSet()).thenReturn(descriptorSet);
        when(descriptorSet.getMeasurementByName("tiepoint")).thenReturn(null);
        when(descriptorSet.getTpVariableByName("tiepoint")).thenReturn(descriptor);
        when(context.getOrCreateFootprints(descriptor)).thenReturn(footprints);
        setField(reader, "readerContext", context);

        assertSame(footprints, reader.getFootprints("tiepoint"));
    }

    @Test
    @STTM("SNAP-4262")
    public void getFootprints_returnsEmptyFootprintsForUnknownName() throws Exception {
        CimrL1BProductReader reader = new CimrL1BProductReader(null);
        CimrReaderContext context = mock(CimrReaderContext.class);
        CimrDescriptorSet descriptorSet = mock(CimrDescriptorSet.class);

        when(context.getDescriptorSet()).thenReturn(descriptorSet);
        when(descriptorSet.getMeasurementByName("missing")).thenReturn(null);
        when(descriptorSet.getTpVariableByName("missing")).thenReturn(null);
        setField(reader, "readerContext", context);

        CimrFootprints footprints = reader.getFootprints("missing");

        assertTrue(footprints.getShapes().isEmpty());
        assertTrue(footprints.getValues().isEmpty());
        verify(context, never()).getOrCreateFootprints(any());
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

    private static class TestReader extends CimrL1BProductReader {

        private TestReader() {
            super(null);
        }

        void setReaderInput(Object input) {
            setInput(input);
        }

        Product exposedReadProductNodesImpl() throws IOException {
            return readProductNodesImpl();
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
        Field f = getDeclaredField(target, name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object getField(Object target, String name) throws NoSuchFieldException, IllegalAccessException {
        Field f = getDeclaredField(target, name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static Field getDeclaredField(Object target, String name) throws NoSuchFieldException {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                return type.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
