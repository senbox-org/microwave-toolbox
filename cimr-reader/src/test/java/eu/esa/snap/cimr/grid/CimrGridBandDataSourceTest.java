package eu.esa.snap.cimr.grid;

import org.junit.Test;

import static org.junit.Assert.*;


public class CimrGridBandDataSourceTest {

    @Test
    public void testGetSample_basicLayout() {
        int width = 2;
        int height = 2;
        double[] data = {
                1.0, 2.0,
                3.0, 4.0
        };
        CimrGridBandDataSource ds = new CimrGridBandDataSource(width, height, data);

        assertEquals(1.0, ds.getSample(0, 0), 1e-12);
        assertEquals(2.0, ds.getSample(1, 0), 1e-12);
        assertEquals(3.0, ds.getSample(0, 1), 1e-12);
        assertEquals(4.0, ds.getSample(1, 1), 1e-12);
    }

    @Test
    public void testCreateEmpty_initialNaN() {
        CimrGridBandDataSource ds = CimrGridBandDataSource.createEmpty(2, 2);

        assertTrue(Double.isNaN(ds.getSample(0, 0)));
        assertTrue(Double.isNaN(ds.getSample(1, 0)));
        assertTrue(Double.isNaN(ds.getSample(0, 1)));
        assertTrue(Double.isNaN(ds.getSample(1, 1)));
    }

    @Test
    public void testSetSample() {
        CimrGridBandDataSource ds = CimrGridBandDataSource.createEmpty(2, 1);

        ds.setSample(0, 0, 42.0);
        ds.setSample(1, 0, 7.0);

        assertEquals(42.0, ds.getSample(0, 0), 1e-12);
        assertEquals(7.0, ds.getSample(1, 0), 1e-12);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructor_invalidLength_throws() {
        new CimrGridBandDataSource(2, 2, new double[]{1.0, 2.0, 3.0});
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetSample_outOfBounds_throws() {
        CimrGridBandDataSource ds = CimrGridBandDataSource.createEmpty(2, 2);
        ds.getSample(2, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetSample_outOfBounds_throws() {
        CimrGridBandDataSource ds = CimrGridBandDataSource.createEmpty(2, 2);
        ds.setSample(-1, 0, 5.0);
    }
}