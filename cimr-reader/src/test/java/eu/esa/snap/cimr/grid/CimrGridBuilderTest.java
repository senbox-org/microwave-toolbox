package eu.esa.snap.cimr.grid;

import eu.esa.snap.cimr.cimr.CimrGridBuilder;
import org.esa.snap.core.datamodel.GeoPos;
import org.junit.Test;

import static org.junit.Assert.*;


public class CimrGridBuilderTest {

    private static final double doubleErr = 1e-6;


    @Test
    public void testBuild_usesAverageWhenTrue() {
        GeometryBandToGridMapper mapper = new GeometryBandToGridMapper();
        CimrGridBuilder builder = new CimrGridBuilder(mapper);

        CimrBand swath = createDummySwath();
        PlateCarreeProjection proj = new PlateCarreeProjection(
                1, 1,
                0.0, 1.0,
                1.0, 1.0
        );
        GlobalGrid grid =  new GlobalGrid(proj, 1, 1);

        GlobalGridBandDataSource target = builder.build(swath, grid, true);

        assertEquals(41.0, target.getSample(0, 0), doubleErr);
        assertEquals(1, target.getWidth());
        assertEquals(1, target.getHeight());
    }

    @Test
    public void testBuild_usesNearestWhenFalse() {
        GeometryBandToGridMapper mapper = new GeometryBandToGridMapper();
        CimrGridBuilder builder = new CimrGridBuilder(mapper);

        CimrBand swath = createDummySwath();
        PlateCarreeProjection proj = new PlateCarreeProjection(
                1, 1,
                0.0, 1.0,
                1.0, 1.0
        );
        GlobalGrid grid =  new GlobalGrid(proj, 1, 1);

        GlobalGridBandDataSource target = builder.build(swath, grid, false);

        assertEquals(40.0, target.getSample(0, 0), doubleErr);
        assertEquals(1, target.getWidth());
        assertEquals(1, target.getHeight());
    }

    private CimrBand createDummySwath() {
        return new CimrBand() {
            @Override
            public int getScanCount() {
                return 1;
            }

            @Override
            public int getSampleCount() {
                return 2;
            }

            @Override
            public double getValue(int scanIndex, int sampleIndex) {
                if (sampleIndex == 0) {
                    return 42.0;
                } else {
                    return 40.0;
                }
            }

            @Override
            public GeoPos getGeoPos(int scanIndex, int sampleIndex) {
                return new GeoPos(0.5f, 0.5f);
            }
        };
    }
}