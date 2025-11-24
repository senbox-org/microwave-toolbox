package eu.esa.snap.cimr.grid;

import org.esa.snap.core.datamodel.GeoPos;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import java.awt.*;
import java.awt.geom.AffineTransform;


public interface GridProjection {

    GeoPos gridToGeoPos(int x, int y);
    boolean geoPosToGrid(GeoPos lat, Point out);

    CoordinateReferenceSystem getCrs()  throws FactoryException;
    AffineTransform getAffineTransform(GlobalGrid grid);

    double getLonMin();
    double getLatMax();
    double getDeltaLon();
    double getDeltaLat();
}
