package eu.esa.snap.cimr.cimr;

import eu.esa.snap.cimr.grid.*;
import org.junit.Test;

import static org.junit.Assert.*;


public class CimrGridBuilderTest {


    @Test
    public void build_whenUseAverageTrue_usesMapAverage() {
        RecordingMapper mapper = new RecordingMapper();
        CimrGridBuilder builder = new CimrGridBuilder(mapper);
        GlobalGrid grid = GlobalGridFactory.createGlobalPlateCarree(10.0);

        GlobalGridBandDataSource result = builder.build(null, grid, true);

        assertTrue(mapper.mapAverageCalled);
        assertFalse(mapper.mapNearestCalled);
        assertSame(grid, mapper.lastGrid);
        assertSame(result, mapper.lastTarget);
        assertNull(mapper.lastBand);
    }

    @Test
    public void build_whenUseAverageFalse_usesMapNearest() {
        RecordingMapper mapper = new RecordingMapper();
        CimrGridBuilder builder = new CimrGridBuilder(mapper);
        GlobalGrid grid = GlobalGridFactory.createGlobalPlateCarree(10.0);

        GlobalGridBandDataSource result = builder.build(null, grid, false);

        assertFalse(mapper.mapAverageCalled);
        assertTrue(mapper.mapNearestCalled);
        assertSame(grid, mapper.lastGrid);
        assertSame(result, mapper.lastTarget);
        assertNull(mapper.lastBand);
    }

    private static class RecordingMapper extends GeometryBandToGridMapper {
        boolean mapAverageCalled;
        boolean mapNearestCalled;
        CimrBand lastBand;
        GlobalGrid lastGrid;
        GridBandDataSource lastTarget;

        @Override
        public void mapAverage(CimrBand band, GlobalGrid grid, GridBandDataSource target) {
            mapAverageCalled = true;
            lastBand = band;
            lastGrid = grid;
            lastTarget = target;
        }

        @Override
        public void mapNearest(CimrBand band, GlobalGrid grid, GridBandDataSource target) {
            mapNearestCalled = true;
            lastBand = band;
            lastGrid = grid;
            lastTarget = target;
        }
    }
}