package eu.esa.snap.cimr.cimr;

import org.esa.snap.core.datamodel.GeoPos;
import org.junit.Test;

import static org.junit.Assert.*;


public class CimrFootprintShapeTest {

    private static final double doubleErr = 1e-9;

    @Test
    public void testConstructorAndGetters() {
        GeoPos geoPos = new GeoPos(24.5f, 280.1f);
        double angle = 123.4;
        double minor = 2500.0;
        double major = 5000.0;

        CimrFootprintShape fp = new CimrFootprintShape(geoPos, angle, minor, major);

        assertSame(geoPos, fp.getGeoPos());
        assertEquals(angle, fp.getAngle(), doubleErr);
    }

    @Test
    public void testMinorAxisToDegree() {
        GeoPos geoPos = new GeoPos(0.0f, 0.0f);
        CimrFootprintShape fp = new CimrFootprintShape(geoPos, 0.0, 111_320.0, 0.0);

        assertEquals(1.0, fp.getMinorAxisDegree(), doubleErr);

        CimrFootprintShape fpHalf = new CimrFootprintShape(geoPos, 0.0, 55_660.0, 0.0);
        assertEquals(0.5, fpHalf.getMinorAxisDegree(), doubleErr);
    }

    @Test
    public void testMajorAxisToDegreeAtEquator() {
        GeoPos geoPos = new GeoPos(0.0f, 10.0f);
        CimrFootprintShape fp = new CimrFootprintShape(geoPos, 0.0, 0.0, 111_320.0);

        assertEquals(1.0, fp.getMajorAxisDegree(), doubleErr);
    }

    @Test
    public void testMajorAxisToDegreeAtMidLatitude() {
        GeoPos geoPos = new GeoPos(60.0f, 10.0f);
        CimrFootprintShape fp = new CimrFootprintShape(geoPos, 0.0, 0.0, 55_660.0);

        assertEquals(1.0, fp.getMajorAxisDegree(), doubleErr);
    }

    @Test
    public void testMajorAxisToDegreeAtNegativeLatitude() {
        GeoPos geoPos = new GeoPos(-60.0f, 10.0f);
        CimrFootprintShape fp = new CimrFootprintShape(geoPos, 0.0, 0.0, 55_660.0);

        assertEquals(1.0, fp.getMajorAxisDegree(), doubleErr);
    }
}