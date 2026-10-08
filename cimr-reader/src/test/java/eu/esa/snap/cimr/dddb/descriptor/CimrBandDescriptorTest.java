package eu.esa.snap.cimr.dddb.descriptor;

import com.bc.ceres.annotation.STTM;
import org.esa.snap.core.datamodel.ProductData;
import org.junit.Test;

import static org.junit.Assert.assertEquals;


public class CimrBandDescriptorTest {


    @Test
    @STTM("SNAP-4262")
    public void constructor_defaultsRasterDataTypeWhenDataTypeIsNull() {
        assertEquals(ProductData.TYPE_FLOAT64, descriptor(null).getRasterDataType());
    }

    @Test
    @STTM("SNAP-4262")
    public void constructor_mapsByteRasterDataType() {
        assertEquals(ProductData.TYPE_INT8, descriptor("byte").getRasterDataType());
    }

    @Test
    @STTM("SNAP-4262")
    public void constructor_mapsShortRasterDataType() {
        assertEquals(ProductData.TYPE_INT16, descriptor("short").getRasterDataType());
    }

    @Test
    @STTM("SNAP-4262")
    public void constructor_mapsIntRasterDataType() {
        assertEquals(ProductData.TYPE_INT32, descriptor("int").getRasterDataType());
    }

    @Test
    @STTM("SNAP-4262")
    public void constructor_mapsIntegerRasterDataType() {
        assertEquals(ProductData.TYPE_INT32, descriptor("integer").getRasterDataType());
    }

    @Test
    @STTM("SNAP-4262")
    public void constructor_mapsLongRasterDataType() {
        assertEquals(ProductData.TYPE_INT64, descriptor("long").getRasterDataType());
    }

    @Test
    @STTM("SNAP-4262")
    public void constructor_mapsFloatRasterDataType() {
        assertEquals(ProductData.TYPE_FLOAT32, descriptor("float").getRasterDataType());
    }

    @Test
    @STTM("SNAP-4262")
    public void constructor_mapsDoubleRasterDataType() {
        assertEquals(ProductData.TYPE_FLOAT64, descriptor("double").getRasterDataType());
    }

    @Test
    @STTM("SNAP-4262")
    public void constructor_mapsDataTypeCaseInsensitively() {
        assertEquals(ProductData.TYPE_FLOAT32, descriptor("FLOAT").getRasterDataType());
    }

    @Test
    @STTM("SNAP-4262")
    public void constructor_defaultsRasterDataTypeForUnknownDataType() {
        assertEquals(ProductData.TYPE_FLOAT64, descriptor("unsupported").getRasterDataType());
    }

    private static CimrBandDescriptor descriptor(String dataType) {
        return new CimrBandDescriptor(
                "test",
                "test",
                CimrFrequencyBand.C_BAND,
                new String[0],
                new String[0],
                "/dummy",
                0,
                CimrDescriptorKind.VARIABLE,
                new String[0],
                dataType,
                "",
                ""
        );
    }
}
