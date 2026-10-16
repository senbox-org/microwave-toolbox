package eu.esa.snap.cimr.grid;

import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.dataio.netcdf.util.DataTypeUtils;
import ucar.ma2.Array;


/**
 * Compact, raster-type-preserving storage for CIMR sample values.
 *
 * <p>Algorithms access samples as {@code double}, but the backing storage retains
 * the actual SNAP raster type. This keeps the numerical processing boundary simple
 * without retaining all source and grid values as doubles.</p>
 */
public final class CimrSampleBuffer {


    private final int rasterDataType;
    private final Object storage;
    private final int size;


    private CimrSampleBuffer(int rasterDataType, Object storage, int size) {
        this.rasterDataType = rasterDataType;
        this.storage = storage;
        this.size = size;
    }


    public static CimrSampleBuffer create(int rasterDataType, int size) {
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
        return new CimrSampleBuffer(rasterDataType, createStorage(rasterDataType, size), size);
    }

    public static CimrSampleBuffer wrap(int rasterDataType, Object storage, int size) {
        if (storage == null) {
            throw new IllegalArgumentException("storage must not be null");
        }
        if (getStorageLength(storage) != size) {
            throw new IllegalArgumentException("storage length must match size");
        }
        validateStorageType(rasterDataType, storage);
        return new CimrSampleBuffer(rasterDataType, storage, size);
    }

    public static CimrSampleBuffer fromNetcdfArray(Array array) {
        int rasterDataType = getRasterDataType(array);
        long size = array.getSize();
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Array is too large for a CIMR sample buffer: " + size);
        }
        return wrap(rasterDataType, array.copyTo1DJavaArray(), (int) size);
    }

    public static int getRasterDataType(Array array) {
        int rasterDataType = DataTypeUtils.getEquivalentProductDataType(array.getDataType(),
                                                                         array.getDataType().isUnsigned(), false);
        if (rasterDataType == -1) {
            throw new IllegalArgumentException("Unsupported raster data type: " + array.getDataType());
        }
        return rasterDataType;
    }


    public int getRasterDataType() {
        return rasterDataType;
    }

    public int size() {
        return size;
    }

    public double get(int index) {
        checkIndex(index);
        return switch (rasterDataType) {
            case ProductData.TYPE_INT8 -> ((byte[]) storage)[index];
            case ProductData.TYPE_UINT8 -> Byte.toUnsignedInt(((byte[]) storage)[index]);
            case ProductData.TYPE_INT16 -> ((short[]) storage)[index];
            case ProductData.TYPE_UINT16 -> Short.toUnsignedInt(((short[]) storage)[index]);
            case ProductData.TYPE_INT32 -> ((int[]) storage)[index];
            case ProductData.TYPE_UINT32 -> Integer.toUnsignedLong(((int[]) storage)[index]);
            case ProductData.TYPE_INT64 -> ((long[]) storage)[index];
            case ProductData.TYPE_UINT64 -> unsignedLongToDouble(((long[]) storage)[index]);
            case ProductData.TYPE_FLOAT32 -> ((float[]) storage)[index];
            case ProductData.TYPE_FLOAT64 -> ((double[]) storage)[index];
            default -> throw unsupportedType(rasterDataType);
        };
    }

    public void set(int index, double value) {
        checkIndex(index);
        switch (rasterDataType) {
            case ProductData.TYPE_INT8 -> ((byte[]) storage)[index] = (byte) Math.round(value);
            case ProductData.TYPE_UINT8 -> ((byte[]) storage)[index] = (byte) checkedUnsigned(value, 8);
            case ProductData.TYPE_INT16 -> ((short[]) storage)[index] = (short) Math.round(value);
            case ProductData.TYPE_UINT16 -> ((short[]) storage)[index] = (short) checkedUnsigned(value, 16);
            case ProductData.TYPE_INT32 -> ((int[]) storage)[index] = (int) Math.round(value);
            case ProductData.TYPE_UINT32 -> ((int[]) storage)[index] = (int) checkedUnsigned(value, 32);
            case ProductData.TYPE_INT64 -> ((long[]) storage)[index] = Math.round(value);
            case ProductData.TYPE_UINT64 -> ((long[]) storage)[index] = checkedUnsignedLong(value);
            case ProductData.TYPE_FLOAT32 -> ((float[]) storage)[index] = (float) value;
            case ProductData.TYPE_FLOAT64 -> ((double[]) storage)[index] = value;
            default -> throw unsupportedType(rasterDataType);
        }
    }

    Object getStorage() {
        return storage;
    }


    private void checkIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IllegalArgumentException("Sample index out of range: " + index);
        }
    }

    private static Object createStorage(int rasterDataType, int size) {
        return switch (rasterDataType) {
            case ProductData.TYPE_INT8, ProductData.TYPE_UINT8 -> new byte[size];
            case ProductData.TYPE_INT16, ProductData.TYPE_UINT16 -> new short[size];
            case ProductData.TYPE_INT32, ProductData.TYPE_UINT32 -> new int[size];
            case ProductData.TYPE_INT64, ProductData.TYPE_UINT64 -> new long[size];
            case ProductData.TYPE_FLOAT32 -> new float[size];
            case ProductData.TYPE_FLOAT64 -> new double[size];
            default -> throw unsupportedType(rasterDataType);
        };
    }

    private static void validateStorageType(int rasterDataType, Object storage) {
        boolean matches = switch (rasterDataType) {
            case ProductData.TYPE_INT8, ProductData.TYPE_UINT8 -> storage instanceof byte[];
            case ProductData.TYPE_INT16, ProductData.TYPE_UINT16 -> storage instanceof short[];
            case ProductData.TYPE_INT32, ProductData.TYPE_UINT32 -> storage instanceof int[];
            case ProductData.TYPE_INT64, ProductData.TYPE_UINT64 -> storage instanceof long[];
            case ProductData.TYPE_FLOAT32 -> storage instanceof float[];
            case ProductData.TYPE_FLOAT64 -> storage instanceof double[];
            default -> false;
        };
        if (!matches) {
            throw new IllegalArgumentException("Storage does not match raster data type " + rasterDataType);
        }
    }

    private static int getStorageLength(Object storage) {
        if (storage instanceof byte[] values) {
            return values.length;
        }
        if (storage instanceof short[] values) {
            return values.length;
        }
        if (storage instanceof int[] values) {
            return values.length;
        }
        if (storage instanceof long[] values) {
            return values.length;
        }
        if (storage instanceof float[] values) {
            return values.length;
        }
        if (storage instanceof double[] values) {
            return values.length;
        }
        throw new IllegalArgumentException("Unsupported storage type: " + storage.getClass().getName());
    }

    private static long checkedUnsigned(double value, int bits) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Unsigned integer value must be finite: " + value);
        }
        double rounded = Math.rint(value);
        double maximum = bits == 32 ? 0xFFFF_FFFFL : (1L << bits) - 1L;
        if (rounded != value || rounded < 0.0 || rounded > maximum) {
            throw new IllegalArgumentException("Value out of range for UINT" + bits + ": " + value);
        }
        return (long) rounded;
    }

    private static long checkedUnsignedLong(double value) {
        if (!Double.isFinite(value) || value < 0.0 || Math.rint(value) != value) {
            throw new IllegalArgumentException("Value out of range for UINT64: " + value);
        }
        if (value <= Long.MAX_VALUE) {
            return (long) value;
        }
        throw new IllegalArgumentException("UINT64 value cannot be represented exactly as double: " + value);
    }

    private static double unsignedLongToDouble(long value) {
        if (value >= 0L) {
            return value;
        }
        return Double.parseDouble(Long.toUnsignedString(value));
    }

    private static IllegalArgumentException unsupportedType(int rasterDataType) {
        return new IllegalArgumentException("Unsupported CIMR raster data type: " + rasterDataType);
    }
}
