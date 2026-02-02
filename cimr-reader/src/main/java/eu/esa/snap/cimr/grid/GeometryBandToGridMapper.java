package eu.esa.snap.cimr.grid;


import org.esa.snap.core.datamodel.GeoPos;

import java.awt.*;


public class GeometryBandToGridMapper {


    public void mapNearest(CimrBand band, CimrGrid grid, GridBandDataSource target) {
        Point gridPoint = new Point();

        for (int ss = 0; ss < band.getScanCount(); ss++) {
            for (int cc = 0; cc < band.getSampleCount(); cc++) {

                GeoPos geoPos = band.getGeoPos(ss, cc);
                boolean inside = grid.geoPosToGrid(geoPos, gridPoint);

                if (!inside) {
                    continue;
                }
                double value = band.getValue(ss, cc);

                target.setSample(gridPoint.x, gridPoint.y, value);
            }
        }
    }

    public void mapAverage(CimrBand band, CimrGrid grid, GridBandDataSource target) {
        int width = grid.getWidth();
        int height = grid.getHeight();

        double[] sum = new double[width * height];
        int[] count = new int[width * height];

        Point p = new Point();

        for (int ss = 0; ss < band.getScanCount(); ss++) {
            for (int cc = 0; cc < band.getSampleCount(); cc++) {
                GeoPos geo = band.getGeoPos(ss, cc);
                if (!grid.geoPosToGrid(geo, p)) {
                    continue;
                }
                double value = band.getValue(ss, cc);
                if (Double.isNaN(value)) {
                    continue;
                }
                int idx = p.y * width + p.x;
                sum[idx] += value;
                count[idx]++;
            }
        }

        for (int yy = 0; yy < height; yy++) {
            for (int xx = 0; xx < width; xx++) {
                int idx = yy * width + xx;
                if (count[idx] > 0) {
                    target.setSample(xx, yy, sum[idx] / count[idx]);
                }
            }
        }
    }
}
