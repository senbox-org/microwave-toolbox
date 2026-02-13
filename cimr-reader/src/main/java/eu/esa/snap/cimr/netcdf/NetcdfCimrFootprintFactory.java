package eu.esa.snap.cimr.netcdf;

import eu.esa.snap.cimr.cimr.CimrFootprintShape;
import eu.esa.snap.cimr.grid.CimrGeometryBand;
import org.esa.snap.core.datamodel.GeoPos;

import java.util.ArrayList;
import java.util.List;


public class NetcdfCimrFootprintFactory {


    public List<CimrFootprintShape> createFootprintShapes(CimrGeometryBand geometryBand, CimrGeometryBand minorAxisBand, CimrGeometryBand majorAxisBand, CimrGeometryBand angleBand) {
        List<CimrFootprintShape> footprints = new ArrayList<>();
        int scans = geometryBand.getScanCount();
        int samples = geometryBand.getSampleCount();

        for (int scanIndex = 0; scanIndex < scans; scanIndex++) {
            for (int sampleIndex = 0; sampleIndex < samples; sampleIndex++) {
                final GeoPos pos = geometryBand.getGeoPos(scanIndex, sampleIndex);
                final double angle = angleBand.getValue(scanIndex, sampleIndex);
                final double minorAxis = minorAxisBand.getValue(scanIndex, sampleIndex);
                final double majorAxis = majorAxisBand.getValue(scanIndex, sampleIndex);
                footprints.add( new CimrFootprintShape(pos, angle, minorAxis, majorAxis));
            }
        }

        return footprints;
    }

    public List<Double> getFootprintValues(CimrGeometryBand geometryBand) {
        List<Double> values = new ArrayList<>();
        int scans = geometryBand.getScanCount();
        int samples = geometryBand.getSampleCount();

        for (int scanIndex = 0; scanIndex < scans; scanIndex++) {
            for (int sampleIndex = 0; sampleIndex < samples; sampleIndex++) {
                values.add(geometryBand.getValue(scanIndex, sampleIndex));
            }
        }

        return values;
    }
}
