package eu.esa.snap.cimr.grid;

import org.esa.snap.core.datamodel.GeoPos;
import org.junit.Test;

import static org.junit.Assert.*;


public class GeometryBandToGridMapperTest {

    private static final double doubleErr = 1e-6;

    @Test
    public void testMap_simpleCase_mapNearest() {
        PlateCarreeProjection projection = new PlateCarreeProjection(
                4, 2,
                -10.0, 81.0,
                1.0, 1.0
        );
        CimrGrid grid = new CimrGrid(projection, 4, 2);
        CimrBand swath = new DummyCimrBand();

        CimrGridBandDataSource target = CimrGridBandDataSource.createEmpty(4, 2);

        GeometryBandToGridMapper mapper = new GeometryBandToGridMapper();
        mapper.mapNearest(swath, grid, target);

        assertEquals(0.0, target.getSample(0, 0), doubleErr);
        assertEquals(1.0, target.getSample(1, 0), doubleErr);
        assertEquals(2.0, target.getSample(2, 0), doubleErr);
        assertEquals(3.0, target.getSample(3, 0), doubleErr);

        assertEquals(10.0, target.getSample(0, 1), doubleErr);
        assertEquals(11.0, target.getSample(1, 1), doubleErr);
        assertEquals(12.0, target.getSample(2, 1), doubleErr);
        assertEquals(13.0, target.getSample(3, 1), doubleErr);
    }


    @Test
    public void testMap_simpleCase_mapAverage() {
        PlateCarreeProjection proj = new PlateCarreeProjection(1, 1, -180, 90, 360, 180);
        CimrGrid grid = new CimrGrid(proj, 1, 1);

        CimrBand swath = new DummyCimrBand();
        CimrGridBandDataSource target = CimrGridBandDataSource.createEmpty(1, 1);
        GeometryBandToGridMapper mapper = new GeometryBandToGridMapper();
        mapper.mapAverage(swath, grid, target);

        assertEquals(6.5, target.getSample(0, 0), 1e-6);
    }
}

class DummyCimrBand implements CimrBand {
    @Override
    public int getScanCount() {
        return 2;
    }

    @Override
    public int getSampleCount() {
        return 4;
    }

    @Override
    public double getValue(int scanIndex, int sampleIndex) {
        return scanIndex * 10 + sampleIndex;
    }

    @Override
    public GeoPos getGeoPos(int scanIndex, int sampleIndex) {
        float lat = 80.5f - scanIndex;
        float lon = -9.5f + sampleIndex;
        return new GeoPos(lat, lon);
    }
}