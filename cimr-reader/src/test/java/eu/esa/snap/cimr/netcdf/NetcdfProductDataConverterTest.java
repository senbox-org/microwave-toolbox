package eu.esa.snap.cimr.netcdf;

import com.bc.ceres.annotation.STTM;
import org.esa.snap.core.datamodel.ProductData;
import org.junit.Test;
import ucar.ma2.Array;
import ucar.ma2.DataType;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


public class NetcdfProductDataConverterTest {


    private final NetcdfProductDataConverter converter = new NetcdfProductDataConverter();


    @Test
    @STTM("SNAP-4262")
    public void toProductData_returnsEmptyStringForNullArray() {
        ProductData productData = converter.toProductData((Array) null);

        assertEquals("", productData.getElemString());
    }

    @Test
    @STTM("SNAP-4262")
    public void toProductData_convertsStringArrayToStringProductData() {
        Array array = mock(Array.class);
        when(array.getDataType()).thenReturn(DataType.STRING);
        when(array.toString()).thenReturn("string-array");

        ProductData productData = converter.toProductData(array);

        assertEquals("string-array", productData.getElemString());
    }

    @Test
    @STTM("SNAP-4262")
    public void toProductData_convertsSupportedNumericArray() {
        Array array = Array.factory(DataType.INT, new int[]{3}, new int[]{1, 2, 3});

        ProductData productData = converter.toProductData(array);

        assertEquals(ProductData.TYPE_INT32, productData.getType());
        assertArrayEquals(new int[]{1, 2, 3}, (int[]) productData.getElems());
    }

    @Test
    @STTM("SNAP-4262")
    public void toProductData_fallsBackToArrayStringForUnsupportedArrayType() {
        Array array = mock(Array.class);
        when(array.getDataType()).thenReturn(DataType.STRUCTURE);
        when(array.toString()).thenReturn("unsupported-array");

        ProductData productData = converter.toProductData(array);

        assertEquals("unsupported-array", productData.getElemString());
    }
}
