package eu.esa.snap.cimr.grid;

import org.esa.snap.core.datamodel.GeoPos;


public class CimrGeometryBand implements CimrBand {

    private final double[][] values;
    private final CimrGeometry geometry;
    private final int feedIndex;


    public CimrGeometryBand(double[][] values,
                            CimrGeometry geometry,
                            int feedIndex) {
        if (values == null || geometry == null) {
            throw new IllegalArgumentException("values and geometry must not be null");
        }
        if (values.length == 0) {
            throw new IllegalArgumentException("values must not be empty");
        }
        int sampleCount = values[0].length;
        for (double[] row : values) {
            if (row.length != sampleCount) {
                throw new IllegalArgumentException("all scan rows must have same sample count");
            }
        }
        if (geometry.getScanCount() != values.length) {
            throw new IllegalArgumentException("geometry scanCount does not match values");
        }
        if (geometry.getSampleCount() != sampleCount) {
            throw new IllegalArgumentException("geometry sampleCount does not match values");
        }

        this.values = values;
        this.geometry = geometry;
        this.feedIndex = feedIndex;
    }

    @Override
    public int getScanCount() {
        return values.length;
    }

    @Override
    public int getSampleCount() {
        return values[0].length;
    }

    @Override
    public GeoPos getGeoPos(int scanIndex, int sampleIndex) {
        return geometry.getGeoPos(scanIndex, sampleIndex, 0);
    }

    @Override
    public double getValue(int scanIndex, int sampleIndex) {
        return values[scanIndex][sampleIndex];
    }

    public int getFeedIndex() {
        return feedIndex;
    }
}
