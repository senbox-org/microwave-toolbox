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
                } else {
                    target.setInvalidSample(gridPoint.x, gridPoint.y);
                }
            }
        }
    }

    public void mapAverage(CimrBand band, CimrGrid grid, GridBandDataSource target) {
        mapAverage(band, grid, target, GridSampleValidator.NAN_ONLY);
    }

    public void mapAverage(CimrBand band, CimrGrid grid, GridBandDataSource target, GridSampleValidator validator) {
        int width = grid.getWidth();
        int height = grid.getHeight();

        double[] sum = new double[width * height];
        int[] count = new int[width * height];
        boolean[] hit = new boolean[width * height];

        Point p = new Point();

        for (int ss = 0; ss < band.getScanCount(); ss++) {
            for (int cc = 0; cc < band.getSampleCount(); cc++) {
                GeoPos geo = band.getGeoPos(ss, cc);
                if (!grid.geoPosToGrid(geo, p)) {
                    continue;
                }
                double value = band.getValue(ss, cc);
                int idx = p.y * width + p.x;
                hit[idx] = true;
                if (!validator.isValid(value)) {
                    continue;
                }
                sum[idx] += value;
                count[idx]++;
            }
        }

        for (int yy = 0; yy < height; yy++) {
            for (int xx = 0; xx < width; xx++) {
                int idx = yy * width + xx;
                if (count[idx] > 0) {
                    target.setValidSample(xx, yy, sum[idx] / count[idx]);
                } else if (hit[idx]) {
                    target.setInvalidSample(xx, yy);
                }
            }
        }
    }
}
