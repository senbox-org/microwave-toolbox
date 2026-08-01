package eu.esa.snap.cimr.grid;

import org.esa.snap.core.datamodel.GeoPos;


public interface CimrGeometry {

    int getScanCount();
    int getSampleCount();
    int getTiePointCount();
    GeoPos getGeoPos(int scanIndex, int sampleIndex, int feedIndex);
}
