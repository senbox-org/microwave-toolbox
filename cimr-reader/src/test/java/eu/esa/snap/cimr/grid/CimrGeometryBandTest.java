package eu.esa.snap.cimr.grid;

import org.esa.snap.core.datamodel.GeoPos;
import org.junit.Test;

import static org.junit.Assert.*;


public class CimrGeometryBandTest {

    private static final double doubleErr = 1e-6;


    @Test
    public void testValuesAndGeometryAreWiredCorrectly() {
        double[][] values = {
                {1.0, 2.0, 3.0, 4.0}
        };

        GeoPos[][][] tp = new GeoPos[1][2][1];
        tp[0][0][0] = new GeoPos(50f, 0f);
        tp[0][1][0] = new GeoPos(50f, 10f);

        CimrTiepointGeometry geom = new CimrTiepointGeometry(tp, 4);

        CimrGeometryBand band = new CimrGeometryBand(values, geom, 5);

        assertEquals(1, band.getScanCount());
        assertEquals(4, band.getSampleCount());
        assertEquals(5, band.getFeedIndex());

        assertEquals(1.0, band.getValue(0, 0), doubleErr);
        assertEquals(4.0, band.getValue(0, 3), doubleErr);

        GeoPos g0 = band.getGeoPos(0, 0);
        GeoPos g2 = band.getGeoPos(0, 2);
        GeoPos g3 = band.getGeoPos(0, 3);

        assertEquals(50.0, g0.getLat(), doubleErr);
        assertEquals(0.0,  g0.getLon(), doubleErr);
        assertEquals(50.0, g2.getLat(), doubleErr);
        assertEquals(6.666666, g2.getLon(), doubleErr);
        assertEquals(50.0, g3.getLat(), doubleErr);
        assertEquals(10.0, g3.getLon(), doubleErr);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructor_FailsWhenValuesNull() {
        GeoPos[][][] tp = new GeoPos[1][1][1];
        tp[0][0][0] = new GeoPos(0f, 0f);
        CimrTiepointGeometry geom = new CimrTiepointGeometry(tp, 2);

        new CimrGeometryBand(new double[0][0], geom, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructor_FailsWhenGeometryNull() {
        double[][] values = {
                {1.0, 2.0}
        };
        new CimrGeometryBand(values, null, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructor_FailsWhenNoScans() {
        double[][] values = new double[0][];
        GeoPos[][][] tp = new GeoPos[1][1][1];
        tp[0][0][0] = new GeoPos(0f, 0f);

        CimrTiepointGeometry geom = new CimrTiepointGeometry(tp, 1);
        new CimrGeometryBand(values, geom, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructor_FailsWhenSampleDimMismatchInValues() {
        double[][] values = new double[2][];
        values[0] = new double[]{1.0, 2.0};
        values[1] = new double[]{3.0};

        GeoPos[][][] tp = new GeoPos[2][1][1];
        tp[0][0][0] = new GeoPos(0f, 0f);
        tp[1][0][0] = new GeoPos(1f, 1f);

        CimrTiepointGeometry geom = new CimrTiepointGeometry(tp, 2);
        new CimrGeometryBand(values, geom, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructor_FailsWhenGeometryScanCountMismatch() {
        double[][] values = {
                {1.0, 2.0}
        };

        GeoPos[][][] tp = new GeoPos[2][1][1];
        tp[0][0][0] = new GeoPos(0f, 0f);
        tp[1][0][0] = new GeoPos(1f, 1f);

        CimrTiepointGeometry geom = new CimrTiepointGeometry(tp, 2);
        new CimrGeometryBand(values, geom, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructor_FailsWhenGeometrySampleCountMismatch() {
        double[][] values = {
                {1.0, 2.0}
        };
        GeoPos[][][] tp = new GeoPos[1][1][1];
        tp[0][0][0] = new GeoPos(0f, 0f);

        CimrTiepointGeometry geom = new CimrTiepointGeometry(tp, 3);
        new CimrGeometryBand(values, geom, 0);
    }
}