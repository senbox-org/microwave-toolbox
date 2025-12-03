package eu.esa.snap.cimr.grid;

import org.esa.snap.core.datamodel.GeoPos;
import org.geotools.referencing.CRS;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import java.awt.*;
import java.awt.geom.AffineTransform;


public class PlateCarreeProjection implements GridProjection {

    private final int width;
    private final int height;
    private final double lonMin;
    private final double latMax;
    private final double deltaLon;
    private final double deltaLat;


    public PlateCarreeProjection(int width, int height, double lonMin, double latMax, double deltaLon, double deltaLat) {
        this.width = width;
        this.height = height;
        this.lonMin = lonMin;
        this.latMax = latMax;
        this.deltaLon = deltaLon;
        this.deltaLat = deltaLat;
    }

    @Override
    public GeoPos gridToGeoPos(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            throw new IllegalArgumentException("Grid index out of range: x=" + x + ", y=" + y);
        }

        double lon = lonMin + (x + 0.5) * deltaLon;
        double lat = latMax - (y + 0.5) * deltaLat;

        return new GeoPos((float) lat, (float) lon);
    }

    @Override
    public boolean geoPosToGrid(GeoPos geoPos, Point out) {
        double lat = geoPos.getLat();
        double lon = geoPos.getLon();

        double lonMax = lonMin + width * deltaLon;
        double latMin = latMax - height * deltaLat;

        if (lon < lonMin || lon >= lonMax || lat <= latMin || lat > latMax) {
            return false;
        }

        int x = (int) ((lon - lonMin) / deltaLon);
        int y = (int) ((latMax - lat) / deltaLat);

        out.x = x;
        out.y = y;
        return true;
    }

    @Override
    public double getLonMin() {
        return lonMin;
    }

    @Override
    public double getLatMax() {
        return latMax;
    }

    @Override
    public double getDeltaLon() {
        return deltaLon;
    }

    @Override
    public double getDeltaLat() {
        return deltaLat;
    }

    @Override
    public CoordinateReferenceSystem getCrs() throws FactoryException {
        return CRS.decode("EPSG:4326", true);
    }

    @Override
    public AffineTransform getAffineTransform(GlobalGrid grid) {
        double lonMin = grid.getProjection().getLonMin();
        double latMax = grid.getProjection().getLatMax();
        double deltaLon = grid.getProjection().getDeltaLon();
        double deltaLat = grid.getProjection().getDeltaLat();

        return new AffineTransform(
                deltaLon, 0.0,
                0.0,     -deltaLat,
                lonMin, latMax
        );
    }
}
