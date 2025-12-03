package eu.esa.snap.cimr.netcdf;

import eu.esa.snap.cimr.cimr.CimrFootprint;
import eu.esa.snap.cimr.grid.CimrGeometryBand;
import org.esa.snap.core.datamodel.GeoPos;

import java.util.ArrayList;
import java.util.List;


public class NetcdfCimrFootprintFactory {


    public List<CimrFootprint> createFootprints(CimrGeometryBand geometryBand, CimrGeometryBand minorAxisBand, CimrGeometryBand majorAxisBand, CimrGeometryBand angleBand) {
        List<CimrFootprint> footprints = new ArrayList<>();
        int scans = geometryBand.getScanCount();
        int samples = geometryBand.getSampleCount();

        for (int scanIndex = 0; scanIndex < scans; scanIndex++) {
            for (int sampleIndex = 0; sampleIndex < samples; sampleIndex++) {
                final GeoPos pos = geometryBand.getGeoPos(scanIndex, sampleIndex);
                final double angle = angleBand.getValue(scanIndex, sampleIndex);
                final double minorAxis = minorAxisBand.getValue(scanIndex, sampleIndex);
                final double majorAxis = majorAxisBand.getValue(scanIndex, sampleIndex);
                final double value = geometryBand.getValue(scanIndex, sampleIndex);
                footprints.add( new CimrFootprint(pos, angle, minorAxis, majorAxis, value));
            }
        }

        return footprints;
    }
}
