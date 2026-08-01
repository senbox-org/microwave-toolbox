package eu.esa.snap.cimr.netcdf;

import eu.esa.snap.cimr.cimr.CimrFootprintShape;
import eu.esa.snap.cimr.grid.CimrGeometryBand;
import org.esa.snap.core.datamodel.GeoPos;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


public class NetcdfCimrFootprintFactoryTest {

    private static final double EPS = 1e-9;

    @Test
    public void testCreateFootprints_populatesAllScanSampleCombinations() {
        CimrGeometryBand geometryBand  = mock(CimrGeometryBand.class);
        CimrGeometryBand minorAxisBand = mock(CimrGeometryBand.class);
        CimrGeometryBand majorAxisBand = mock(CimrGeometryBand.class);
        CimrGeometryBand angleBand     = mock(CimrGeometryBand.class);

        int scans = 2;
        int samples = 3;

        when(geometryBand.getScanCount()).thenReturn(scans);
        when(geometryBand.getSampleCount()).thenReturn(samples);

        when(geometryBand.getGeoPos(anyInt(), anyInt())).thenAnswer(inv -> {
            int s = inv.getArgument(0);
            int t = inv.getArgument(1);
            return new GeoPos((float) (10 + s), (float) (20 + t));
        });

        when(geometryBand.getValue(anyInt(), anyInt())).thenAnswer(inv -> {
            int s = inv.getArgument(0);
            int t = inv.getArgument(1);
            return 100.0 + 10.0 * s + t;
        });

        when(angleBand.getValue(anyInt(), anyInt())).thenAnswer(inv -> {
            int s = inv.getArgument(0);
            int t = inv.getArgument(1);
            return 1.0 * s + 0.1 * t;
        });

        when(minorAxisBand.getValue(anyInt(), anyInt())).thenAnswer(inv -> {
            int s = inv.getArgument(0);
            int t = inv.getArgument(1);
            return 1000.0 + s + t;
        });

        when(majorAxisBand.getValue(anyInt(), anyInt())).thenAnswer(inv -> {
            int s = inv.getArgument(0);
            int t = inv.getArgument(1);
            return 2000.0 + 2.0 * s + 3.0 * t;
        });

        NetcdfCimrFootprintFactory factory = new NetcdfCimrFootprintFactory();


        List<CimrFootprintShape> footprints = factory.createFootprintShapes(
                geometryBand, minorAxisBand, majorAxisBand, angleBand);


        assertEquals(scans * samples, footprints.size());

        int idx = 0;
        for (int s = 0; s < scans; s++) {
            for (int t = 0; t < samples; t++) {
                CimrFootprintShape fp = footprints.get(idx++);

                assertEquals(10.0 + s, fp.getGeoPos().getLat(), EPS);
                assertEquals(20.0 + t, fp.getGeoPos().getLon(), EPS);

                double expectedAngle     = 1.0 * s + 0.1 * t;
                double expectedMinorAxis = 1000.0 + s + t;

                assertEquals(expectedAngle, fp.getAngle(), EPS);
                assertEquals(expectedMinorAxis, fp.getMinorAxisDegree() * 111320.0, 1e-6 * 111320.0);
            }
        }
    }


    @Test
    public void testCreateFootprints_emptyGeometryBandReturnsEmptyList() {
        CimrGeometryBand geometryBand  = mock(CimrGeometryBand.class);
        CimrGeometryBand minorAxisBand = mock(CimrGeometryBand.class);
        CimrGeometryBand majorAxisBand = mock(CimrGeometryBand.class);
        CimrGeometryBand angleBand     = mock(CimrGeometryBand.class);

        when(geometryBand.getScanCount()).thenReturn(0);
        when(geometryBand.getSampleCount()).thenReturn(0);

        NetcdfCimrFootprintFactory factory = new NetcdfCimrFootprintFactory();

        List<CimrFootprintShape> footprints = factory.createFootprintShapes(
                geometryBand, minorAxisBand, majorAxisBand, angleBand);

        assertNotNull(footprints);
        assertTrue(footprints.isEmpty());
    }

    @Test
    public void testgetFootprintValues() {
        CimrGeometryBand geometryBand = mock(CimrGeometryBand.class);

        int scans = 2;
        int samples = 3;

        when(geometryBand.getScanCount()).thenReturn(scans);
        when(geometryBand.getSampleCount()).thenReturn(samples);

        when(geometryBand.getValue(anyInt(), anyInt())).thenAnswer(inv -> {
            int s = inv.getArgument(0);
            int t = inv.getArgument(1);
            return 100.0 + 10.0 * s + t;
        });

        NetcdfCimrFootprintFactory factory = new NetcdfCimrFootprintFactory();

        List<Double> values = factory.getFootprintValues(geometryBand);

        assertEquals(scans * samples, values.size());

        int idx = 0;
        for (int s = 0; s < scans; s++) {
            for (int t = 0; t < samples; t++) {
                double value = values.get(idx++);

                double expectedValue     = 100.0 + 10.0 * s + t;
                assertEquals(expectedValue, value, EPS);
            }
        }
    }
}