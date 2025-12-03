package eu.esa.snap.cimr.cimr;

import org.esa.snap.core.datamodel.GeoPos;

public class CimrFootprint {

    GeoPos geoPos;
    double angle; // degree
    double minor_axis;
    double major_axis;
    double value;

    public CimrFootprint(GeoPos geoPos, double angle, double minor_axis, double major_axis, double value) {
        this.geoPos = geoPos;
        this.angle = angle;
        this.minor_axis = minor_axis;
        this.major_axis = major_axis;
        this.value = value;
    }

    public GeoPos getGeoPos() {
        return geoPos;
    }

    public double getAngle() {
        return angle;
    }

    public double getValue() {
        return value;
    }

    public double getMinorAxisDegree() {
        return metersToLatDeg(minor_axis);
    }

    public double getMajorAxisDegree() {
        return metersToLonDeg(major_axis, geoPos.getLat());
    }

    private double metersToLatDeg(double meters) {
        return meters / 111320.0;
    }

    private double metersToLonDeg(double meters, double latDeg) {
        return meters / (111320.0 * Math.cos(Math.toRadians(latDeg)));
    }
}
