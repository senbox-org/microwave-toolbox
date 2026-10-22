package eu.esa.snap.cimr.grid;

import com.bc.ceres.annotation.STTM;
import org.esa.snap.core.datamodel.ProductData;
import org.junit.Test;

import static org.junit.Assert.*;


public class CimrSampleBufferTest {


    @Test
    @STTM("SNAP-4262")
    public void testCreate_usesRasterTypeSpecificStorage() {
        CimrSampleBuffer floatBuffer = CimrSampleBuffer.create(ProductData.TYPE_FLOAT32, 2);
        CimrSampleBuffer integerBuffer = CimrSampleBuffer.create(ProductData.TYPE_INT32, 2);

        assertTrue(floatBuffer.getStorage() instanceof float[]);
        assertTrue(integerBuffer.getStorage() instanceof int[]);
    }

    @Test
    @STTM("SNAP-4262")
    public void create_usesStorageForEverySupportedRasterType() {
        assertTrue(CimrSampleBuffer.create(ProductData.TYPE_INT8, 1).getStorage() instanceof byte[]);
        assertTrue(CimrSampleBuffer.create(ProductData.TYPE_UINT8, 1).getStorage() instanceof byte[]);
        assertTrue(CimrSampleBuffer.create(ProductData.TYPE_INT16, 1).getStorage() instanceof short[]);
        assertTrue(CimrSampleBuffer.create(ProductData.TYPE_UINT16, 1).getStorage() instanceof short[]);
        assertTrue(CimrSampleBuffer.create(ProductData.TYPE_INT32, 1).getStorage() instanceof int[]);
        assertTrue(CimrSampleBuffer.create(ProductData.TYPE_UINT32, 1).getStorage() instanceof int[]);
        assertTrue(CimrSampleBuffer.create(ProductData.TYPE_INT64, 1).getStorage() instanceof long[]);
        assertTrue(CimrSampleBuffer.create(ProductData.TYPE_UINT64, 1).getStorage() instanceof long[]);
        assertTrue(CimrSampleBuffer.create(ProductData.TYPE_FLOAT32, 1).getStorage() instanceof float[]);
        assertTrue(CimrSampleBuffer.create(ProductData.TYPE_FLOAT64, 1).getStorage() instanceof double[]);
    }

    @Test
    @STTM("SNAP-4262")
    public void setAndGet_supportEveryRasterType() {
        assertSetAndGet(ProductData.TYPE_INT8, -4.0);
        assertSetAndGet(ProductData.TYPE_UINT8, 255.0);
        assertSetAndGet(ProductData.TYPE_INT16, -1234.0);
        assertSetAndGet(ProductData.TYPE_UINT16, 65535.0);
        assertSetAndGet(ProductData.TYPE_INT32, -1234567.0);
        assertSetAndGet(ProductData.TYPE_UINT32, 4294967295.0);
        assertSetAndGet(ProductData.TYPE_INT64, 42.0);
        assertSetAndGet(ProductData.TYPE_UINT64, 42.0);
        assertSetAndGet(ProductData.TYPE_FLOAT32, 1.25);
        assertSetAndGet(ProductData.TYPE_FLOAT64, 2.5);
    }

    @Test
    @STTM("SNAP-4262")
    public void testUnsignedValues_areReadWithoutSignExtension() {
        CimrSampleBuffer byteBuffer = CimrSampleBuffer.wrap(ProductData.TYPE_UINT8, new byte[] {(byte) 0xFF}, 1);
        CimrSampleBuffer shortBuffer = CimrSampleBuffer.wrap(ProductData.TYPE_UINT16, new short[] {(short) 0xFFFF}, 1);
        CimrSampleBuffer intBuffer = CimrSampleBuffer.wrap(ProductData.TYPE_UINT32, new int[] {0xFFFF_FFFF}, 1);
        CimrSampleBuffer longBuffer = CimrSampleBuffer.wrap(ProductData.TYPE_UINT64, new long[] {-1L}, 1);

        assertEquals(255.0, byteBuffer.get(0), 0.0);
        assertEquals(65535.0, shortBuffer.get(0), 0.0);
        assertEquals(4294967295.0, intBuffer.get(0), 0.0);
        assertEquals(Double.parseDouble("18446744073709551615"), longBuffer.get(0), 0.0);
    }

    @Test
    public void createAndWrap_rejectInvalidArguments() {
        IllegalArgumentException negativeSize = assertThrows(IllegalArgumentException.class, () -> CimrSampleBuffer.create(ProductData.TYPE_FLOAT32, -1));
        assertEquals("size must not be negative", negativeSize.getMessage());

        IllegalArgumentException unsupportedType = assertThrows(IllegalArgumentException.class, () -> CimrSampleBuffer.create(-1, 1));
        assertEquals("Unsupported CIMR raster data type: -1", unsupportedType.getMessage());

        IllegalArgumentException nullStorage = assertThrows(IllegalArgumentException.class, () -> CimrSampleBuffer.wrap(ProductData.TYPE_UINT8, null, 0));
        assertEquals("storage must not be null", nullStorage.getMessage());

        IllegalArgumentException invalidLength = assertThrows(IllegalArgumentException.class, () -> CimrSampleBuffer.wrap(ProductData.TYPE_UINT8, new byte[1], 2));
        assertEquals("storage length must match size", invalidLength.getMessage());

        IllegalArgumentException invalidStorageType = assertThrows(IllegalArgumentException.class, () -> CimrSampleBuffer.wrap(ProductData.TYPE_UINT8, new int[1], 1));
        assertEquals("Storage does not match raster data type " + ProductData.TYPE_UINT8, invalidStorageType.getMessage());

        IllegalArgumentException unsupportedStorageType = assertThrows(IllegalArgumentException.class, () -> CimrSampleBuffer.wrap(ProductData.TYPE_UINT8, "not-an-array", 1));
        assertEquals("Unsupported storage type: java.lang.String", unsupportedStorageType.getMessage());
    }

    @Test
    public void getAndSet_rejectOutOfRangeIndices() {
        CimrSampleBuffer buffer = CimrSampleBuffer.create(ProductData.TYPE_FLOAT64, 1);

        assertThrows(IllegalArgumentException.class, () -> buffer.get(-1));
        assertThrows(IllegalArgumentException.class, () -> buffer.get(1));
        assertThrows(IllegalArgumentException.class, () -> buffer.set(-1, 1.0));
        assertThrows(IllegalArgumentException.class, () -> buffer.set(1, 1.0));
    }

    @Test
    public void set_rejectsInvalidUnsignedValues() {
        CimrSampleBuffer uint8 = CimrSampleBuffer.create(ProductData.TYPE_UINT8, 1);
        assertThrows(IllegalArgumentException.class, () -> uint8.set(0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> uint8.set(0, 0.5));
        assertThrows(IllegalArgumentException.class, () -> uint8.set(0, -1.0));
        assertThrows(IllegalArgumentException.class, () -> uint8.set(0, 256.0));

        CimrSampleBuffer uint16 = CimrSampleBuffer.create(ProductData.TYPE_UINT16, 1);
        assertThrows(IllegalArgumentException.class, () -> uint16.set(0, 65536.0));

        CimrSampleBuffer uint32 = CimrSampleBuffer.create(ProductData.TYPE_UINT32, 1);
        uint32.set(0, 4294967295.0);
        assertEquals(4294967295.0, uint32.get(0), 0.0);
        assertThrows(IllegalArgumentException.class, () -> uint32.set(0, 4294967296.0));

        CimrSampleBuffer uint64 = CimrSampleBuffer.create(ProductData.TYPE_UINT64, 1);
        assertThrows(IllegalArgumentException.class, () -> uint64.set(0, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> uint64.set(0, -1.0));
        assertThrows(IllegalArgumentException.class, () -> uint64.set(0, 0.5));
        assertThrows(IllegalArgumentException.class, () -> uint64.set(0, (double) Long.MAX_VALUE));
    }

    @Test
    @STTM("SNAP-4262")
    public void testIntegerGrid_preservesInvalidStateWithoutStoringNaN() {
        CimrGridBandDataSource source = CimrGridBandDataSource.createEmpty(2, 1, ProductData.TYPE_INT32);
        source.setValidSample(0, 0, 4.0);
        source.setInvalidSample(1, 0);

        assertEquals(ProductData.TYPE_INT32, source.getRasterDataType());
        assertEquals(4.0, source.getSample(0, 0), 0.0);
        assertTrue(Double.isNaN(source.getSample(1, 0)));
        assertEquals(GridSampleState.INVALID, source.getSampleState(1, 0));
        assertTrue(new GridCoverageDataSource(source).isCovered(1, 0));
    }

    private static void assertSetAndGet(int rasterDataType, double value) {
        CimrSampleBuffer buffer = CimrSampleBuffer.create(rasterDataType, 1);
        buffer.set(0, value);
        assertEquals(value, buffer.get(0), 0.0);
    }
}