package eu.esa.snap.cimr.grid;

import org.esa.snap.core.datamodel.GeoPos;


public interface CimrBand {

    int getScanCount();
    int getSampleCount();
    double getValue(int scanIndex, int sampleIndex);
    GeoPos getGeoPos(int scanIndex, int sampleIndex);
}
