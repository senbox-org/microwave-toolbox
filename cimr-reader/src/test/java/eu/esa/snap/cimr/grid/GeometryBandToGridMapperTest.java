package eu.esa.snap.cimr.grid;

import com.bc.ceres.annotation.STTM;
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
        assertEquals(GridSampleState.VALID, target.getSampleState(0, 0));
        assertTrue(target.isCovered(0, 0));
        assertTrue(target.isValid(0, 0));
    }

    @Test
    @STTM("SNAP-4262")
    public void testMapNearest_nanHitBecomesInvalid() {
        PlateCarreeProjection proj = new PlateCarreeProjection(1, 1, 0.0, 1.0, 1.0, 1.0);
        CimrGrid grid = new CimrGrid(proj, 1, 1);
        CimrGridBandDataSource target = CimrGridBandDataSource.createEmpty(1, 1);

        GeometryBandToGridMapper mapper = new GeometryBandToGridMapper();
        mapper.mapNearest(new SingleSampleBand(Double.NaN), grid, target);

        assertTrue(Double.isNaN(target.getSample(0, 0)));
        assertEquals(GridSampleState.INVALID, target.getSampleState(0, 0));
        assertTrue(target.isCovered(0, 0));
        assertFalse(target.isValid(0, 0));
    }

    @Test
    @STTM("SNAP-4262")
    public void testMapNearest_unhitPixelRemainsEmpty() {
        PlateCarreeProjection proj = new PlateCarreeProjection(2, 1, 0.0, 1.0, 1.0, 1.0);
        CimrGrid grid = new CimrGrid(proj, 2, 1);
        CimrGridBandDataSource target = CimrGridBandDataSource.createEmpty(2, 1);

        GeometryBandToGridMapper mapper = new GeometryBandToGridMapper();
        mapper.mapNearest(new SingleSampleBand(3.0), grid, target);

        assertEquals(GridSampleState.VALID, target.getSampleState(0, 0));
        assertEquals(GridSampleState.EMPTY, target.getSampleState(1, 0));
        assertFalse(target.isCovered(1, 0));
    }

    @Test
    @STTM("SNAP-4262")
    public void testMapNearest_usesValidatorForInvalidValues() {
        PlateCarreeProjection proj = new PlateCarreeProjection(1, 1, 0.0, 1.0, 1.0, 1.0);
        CimrGrid grid = new CimrGrid(proj, 1, 1);
        CimrGridBandDataSource target = CimrGridBandDataSource.createEmpty(1, 1);

        GeometryBandToGridMapper mapper = new GeometryBandToGridMapper();
        mapper.mapNearest(new SingleSampleBand(-9999.0), grid, target, value -> value != -9999.0);

        assertTrue(Double.isNaN(target.getSample(0, 0)));
        assertEquals(GridSampleState.INVALID, target.getSampleState(0, 0));
        assertTrue(target.isCovered(0, 0));
        assertFalse(target.isValid(0, 0));
    }

    @Test
    @STTM("SNAP-4262")
    public void testMapNearest_laterInvalidHitDoesNotOverwriteValidSample() {
        PlateCarreeProjection proj = new PlateCarreeProjection(1, 1, 0.0, 1.0, 1.0, 1.0);
        CimrGrid grid = new CimrGrid(proj, 1, 1);
        CimrGridBandDataSource target = CimrGridBandDataSource.createEmpty(1, 1);

        GeometryBandToGridMapper mapper = new GeometryBandToGridMapper();
        mapper.mapNearest(new SamePixelBand(42.0, Double.NaN), grid, target);

        assertEquals(42.0, target.getSample(0, 0), doubleErr);
        assertEquals(GridSampleState.VALID, target.getSampleState(0, 0));
        assertTrue(target.isCovered(0, 0));
        assertTrue(target.isValid(0, 0));
    }

    @Test
    @STTM("SNAP-4262")
    public void testMapNearest_laterValidHitReplacesEarlierInvalidSample() {
        PlateCarreeProjection proj = new PlateCarreeProjection(1, 1, 0.0, 1.0, 1.0, 1.0);
        CimrGrid grid = new CimrGrid(proj, 1, 1);
        CimrGridBandDataSource target = CimrGridBandDataSource.createEmpty(1, 1);

        GeometryBandToGridMapper mapper = new GeometryBandToGridMapper();
        mapper.mapNearest(new SamePixelBand(Double.NaN, 42.0), grid, target);

        assertEquals(42.0, target.getSample(0, 0), doubleErr);
        assertEquals(GridSampleState.VALID, target.getSampleState(0, 0));
        assertTrue(target.isCovered(0, 0));
        assertTrue(target.isValid(0, 0));
    }
}


class SingleSampleBand implements CimrBand {
    private final double value;

    SingleSampleBand(double value) {
        this.value = value;
    }

    @Override
    public int getScanCount() {
        return 1;
    }

    @Override
    public int getSampleCount() {
        return 1;
    }

    @Override
    public double getValue(int scanIndex, int sampleIndex) {
        return value;
    }

    @Override
    public GeoPos getGeoPos(int scanIndex, int sampleIndex) {
        return new GeoPos(0.5f, 0.5f);
    }
}

class SamePixelBand implements CimrBand {
    private final double[] values;

    SamePixelBand(double... values) {
        this.values = values;
    }

    @Override
    public int getScanCount() {
        return 1;
    }

    @Override
    public int getSampleCount() {
        return values.length;
    }

    @Override
    public double getValue(int scanIndex, int sampleIndex) {
        return values[sampleIndex];
    }

    @Override
    public GeoPos getGeoPos(int scanIndex, int sampleIndex) {
        return new GeoPos(0.5f, 0.5f);
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