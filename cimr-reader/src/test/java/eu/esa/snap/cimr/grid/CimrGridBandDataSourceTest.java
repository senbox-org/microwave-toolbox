package eu.esa.snap.cimr.grid;

import com.bc.ceres.annotation.STTM;
import eu.esa.snap.cimr.test.CimrGridTestData;
import org.esa.snap.core.datamodel.ProductData;
import org.junit.Test;

import static org.junit.Assert.*;


public class CimrGridBandDataSourceTest {


    @Test
    public void testGetSample_basicLayout() {
        int width = 2;
        int height = 2;
        CimrGridBandDataSource ds = CimrGridTestData.grid(width, height, ProductData.TYPE_FLOAT32, 1.0, 2.0, 3.0, 4.0);

        assertEquals(1.0, ds.getSample(0, 0), 1e-12);
        assertEquals(2.0, ds.getSample(1, 0), 1e-12);
        assertEquals(3.0, ds.getSample(0, 1), 1e-12);
        assertEquals(4.0, ds.getSample(1, 1), 1e-12);
        assertEquals(GridSampleState.VALID, ds.getSampleState(0, 0));
        assertTrue(ds.isCovered(0, 0));
        assertTrue(ds.isValid(0, 0));
    }

    @Test
    public void testCreateEmpty_initialNaNAndEmptyState() {
        CimrGridBandDataSource ds = CimrGridBandDataSource.createEmpty(2, 2, ProductData.TYPE_FLOAT32);

        assertTrue(Double.isNaN(ds.getSample(0, 0)));
        assertTrue(Double.isNaN(ds.getSample(1, 0)));
        assertTrue(Double.isNaN(ds.getSample(0, 1)));
        assertTrue(Double.isNaN(ds.getSample(1, 1)));
        assertEquals(GridSampleState.EMPTY, ds.getSampleState(0, 0));
        assertFalse(ds.isCovered(0, 0));
        assertFalse(ds.isValid(0, 0));
    }

    @Test
    public void testSetSample_setsValidStateForRealValues() {
        CimrGridBandDataSource ds = CimrGridBandDataSource.createEmpty(2, 1, ProductData.TYPE_FLOAT32);

        ds.setSample(0, 0, 42.0);
        ds.setSample(1, 0, 7.0);

        assertEquals(42.0, ds.getSample(0, 0), 1e-12);
        assertEquals(7.0, ds.getSample(1, 0), 1e-12);
        assertEquals(GridSampleState.VALID, ds.getSampleState(0, 0));
        assertEquals(GridSampleState.VALID, ds.getSampleState(1, 0));
    }

    @Test
    @STTM("SNAP-4262")
    public void testSetSample_setsInvalidStateForNaN() {
        CimrGridBandDataSource ds = CimrGridBandDataSource.createEmpty(2, 1, ProductData.TYPE_FLOAT32);

        ds.setSample(0, 0, Double.NaN);

        assertTrue(Double.isNaN(ds.getSample(0, 0)));
        assertEquals(GridSampleState.INVALID, ds.getSampleState(0, 0));
        assertTrue(ds.isCovered(0, 0));
        assertFalse(ds.isValid(0, 0));
    }

    @Test
    @STTM("SNAP-4262")
    public void testSetValidAndInvalidSample() {
        CimrGridBandDataSource ds = CimrGridBandDataSource.createEmpty(2, 1, ProductData.TYPE_FLOAT32);

        ds.setValidSample(0, 0, 5.0);
        ds.setInvalidSample(1, 0);

        assertEquals(5.0, ds.getSample(0, 0), 1e-12);
        assertEquals(GridSampleState.VALID, ds.getSampleState(0, 0));
        assertTrue(Double.isNaN(ds.getSample(1, 0)));
        assertEquals(GridSampleState.INVALID, ds.getSampleState(1, 0));
    }

    @Test
    @STTM("SNAP-4262")
    public void setValidSample_withNaNMarksSampleInvalid() {
        CimrGridBandDataSource ds = CimrGridBandDataSource.createEmpty(1, 1, ProductData.TYPE_FLOAT32);

        ds.setValidSample(0, 0, Double.NaN);

        assertTrue(Double.isNaN(ds.getSample(0, 0)));
        assertEquals(GridSampleState.INVALID, ds.getSampleState(0, 0));
        assertTrue(ds.isCovered(0, 0));
        assertFalse(ds.isValid(0, 0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testTypedFactory_invalidLength_throws() {
        CimrGridTestData.grid(2, 2, ProductData.TYPE_FLOAT32, 1.0, 2.0, 3.0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetSample_outOfBounds_throws() {
        CimrGridBandDataSource ds = CimrGridBandDataSource.createEmpty(2, 2, ProductData.TYPE_FLOAT32);
        ds.getSample(2, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetSample_outOfBounds_throws() {
        CimrGridBandDataSource ds = CimrGridBandDataSource.createEmpty(2, 2, ProductData.TYPE_FLOAT32);
        ds.setSample(-1, 0, 5.0);
    }
}