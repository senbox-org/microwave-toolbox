package eu.esa.snap.cimr.grid;

import org.esa.snap.core.datamodel.GeoPos;

import java.awt.*;


public class GeometryBandToGridMapper {


    public void mapNearest(CimrBand band, CimrGrid grid, GridBandDataSource target) {
        mapNearest(band, grid, target, GridSampleValidator.NAN_ONLY);
    }

    public void mapNearest(CimrBand band, CimrGrid grid, GridBandDataSource target, GridSampleValidator validator) {
        Point gridPoint = new Point();

        for (int ss = 0; ss < band.getScanCount(); ss++) {
            for (int cc = 0; cc < band.getSampleCount(); cc++) {

                GeoPos geoPos = band.getGeoPos(ss, cc);
                boolean inside = grid.geoPosToGrid(geoPos, gridPoint);

                if (!inside) {
                    continue;
                }
                double value = band.getValue(ss, cc);

                if (validator.isValid(value)) {
                    target.setValidSample(gridPoint.x, gridPoint.y, value);
                } else if (!target.isValid(gridPoint.x, gridPoint.y)) {
                    target.setInvalidSample(gridPoint.x, gridPoint.y);
                }
            }
        }
    }
}
