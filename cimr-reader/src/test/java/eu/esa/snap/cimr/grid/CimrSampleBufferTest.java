package eu.esa.snap.cimr.grid;

import com.bc.ceres.annotation.STTM;
import org.esa.snap.core.datamodel.ProductData;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;


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
    public void testUnsignedValues_areReadWithoutSignExtension() {
        CimrSampleBuffer buffer = CimrSampleBuffer.wrap(ProductData.TYPE_UINT8, new byte[] {(byte) 0xFF}, 1);

        assertEquals(255.0, buffer.get(0), 0.0);
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
}