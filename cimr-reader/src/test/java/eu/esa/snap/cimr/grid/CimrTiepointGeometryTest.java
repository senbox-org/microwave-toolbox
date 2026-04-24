package eu.esa.snap.cimr.grid;

import org.esa.snap.core.datamodel.GeoPos;
import org.junit.Test;

import static org.junit.Assert.*;


public class CimrTiepointGeometryTest {

    private static final double doubleErr = 1e-6;

    @Test
    public void testInterpolation_simple4Samples2TiePoints() {
        int scans = 1;
        int tiePoints = 2;
        int feeds = 1;
        int samples = 4;

        GeoPos[][][] tp = new GeoPos[scans][tiePoints][feeds];
        tp[0][0][0] = new GeoPos(50f, 0f);
        tp[0][1][0] = new GeoPos(50f, 10f);

        CimrTiepointGeometry geom = new CimrTiepointGeometry(tp, samples);

        GeoPos g0 = geom.getGeoPos(0, 0, 0);
        GeoPos g1 = geom.getGeoPos(0, 1, 0);
        GeoPos g2 = geom.getGeoPos(0, 2, 0);
        GeoPos g3 = geom.getGeoPos(0, 3, 0);

        assertEquals(2, geom.getTiePointCount());

        assertEquals(50.0, g0.getLat(), doubleErr);
        assertEquals(0.0,  g0.getLon(), doubleErr);

        assertEquals(50.0, g1.getLat(), doubleErr);
        assertEquals(3.333333,  g1.getLon(), doubleErr);

        assertEquals(50.0, g2.getLat(), doubleErr);
        assertEquals(6.666666, g2.getLon(), doubleErr);

        assertEquals(50.0, g3.getLat(), doubleErr);
        assertEquals(10.0, g3.getLon(), doubleErr);
    }

    @Test
    public void testInterpolation_realisticCounts() {
        int scans = 1;
        int tiePoints = 274;
        int feeds = 1;
        int samples = 549;

        GeoPos[][][] tp = new GeoPos[scans][tiePoints][feeds];
        for (int i = 0; i < tiePoints; i++) {
            float lon = (float) (10.0 * i / (tiePoints - 1));
            tp[0][i][0] = new GeoPos(60f, lon);
        }

        CimrTiepointGeometry geom = new CimrTiepointGeometry(tp, samples);

        GeoPos gStart = geom.getGeoPos(0, 0, 0);
        GeoPos gMiddle = geom.getGeoPos(0, 200, 0);
        GeoPos gEnd   = geom.getGeoPos(0, samples - 1, 0);

        assertEquals(60.0, gStart.getLat(), doubleErr);
        assertEquals(0.0,  gStart.getLon(), doubleErr);

        assertEquals(60.0, gMiddle.getLat(), doubleErr);
        assertEquals(3.649635,  gMiddle.getLon(), doubleErr);

        assertEquals(60.0, gEnd.getLat(), doubleErr);
        assertEquals(10.0, gEnd.getLon(), doubleErr);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructor_failsWhenNoScans() {
        GeoPos[][][] tp = new GeoPos[0][0][0];
        new CimrTiepointGeometry(tp, 10);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructor_failsWhenNoTiePoints() {
        GeoPos[][][] tp = new GeoPos[1][0][0];
        new CimrTiepointGeometry(tp, 10);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructor_failsWhenTiePointDimMismatchBetweenScans() {
        GeoPos[][][] tp = new GeoPos[2][][];
        tp[0] = new GeoPos[2][1];
        tp[1] = new GeoPos[3][1];

        new CimrTiepointGeometry(tp, 10);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructor_failsWhenFeedDimMismatch() {
        GeoPos[][][] tp = new GeoPos[1][][];
        tp[0] = new GeoPos[2][];
        tp[0][0] = new GeoPos[1];
        tp[0][1] = new GeoPos[2];

        new CimrTiepointGeometry(tp, 10);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructor_failsWhenSampleCountTooSmall() {
        GeoPos[][][] tp = new GeoPos[1][1][1];
        tp[0][0][0] = new GeoPos(0f, 0f);
        new CimrTiepointGeometry(tp, 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetGeoPos_failsWhenScanIndexOutOfRange() {
        GeoPos[][][] tp = new GeoPos[1][1][1];
        tp[0][0][0] = new GeoPos(0f, 0f);
        CimrTiepointGeometry geom = new CimrTiepointGeometry(tp, 2);

        geom.getGeoPos(1, 0, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetGeoPos_failsWhenSampleIndexOutOfRange() {
        GeoPos[][][] tp = new GeoPos[1][1][1];
        tp[0][0][0] = new GeoPos(0f, 0f);
        CimrTiepointGeometry geom = new CimrTiepointGeometry(tp, 2);

        geom.getGeoPos(0, 2, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetGeoPos_failsWhenFeedIndexOutOfRange() {
        GeoPos[][][] tp = new GeoPos[1][1][1];
        tp[0][0][0] = new GeoPos(0f, 0f);
        CimrTiepointGeometry geom = new CimrTiepointGeometry(tp, 2);

        geom.getGeoPos(0, 0, 1);
    }
}