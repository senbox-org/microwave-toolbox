package eu.esa.snap.cimr.grid;

import org.esa.snap.core.datamodel.GeoPos;


public class CimrGeometryBand implements CimrBand {

    private final CimrSampleBuffer values;
    private final int scanCount;
    private final int sampleCount;
    private final CimrGeometry geometry;
    private final int feedIndex;


    public CimrGeometryBand(double[][] values, CimrGeometry geometry, int feedIndex) {
        this(toSampleBuffer(values), getScanCount(values), getSampleCount(values), geometry, feedIndex);
    }

    public CimrGeometryBand(CimrSampleBuffer values, int scanCount, int sampleCount,
                            CimrGeometry geometry, int feedIndex) {
        if (values == null || geometry == null) {
            throw new IllegalArgumentException("values and geometry must not be null");
        }
        if (scanCount <= 0 || sampleCount <= 0) {
            throw new IllegalArgumentException("scanCount and sampleCount must be positive");
        }
        if (values.size() != scanCount * sampleCount) {
            throw new IllegalArgumentException("values length must be scanCount * sampleCount");
        }
        if (geometry.getScanCount() != scanCount) {
            throw new IllegalArgumentException("geometry scanCount does not match values");
        }
        if (geometry.getSampleCount() != sampleCount) {
            throw new IllegalArgumentException("geometry sampleCount does not match values");
        }

        this.values = values;
        this.scanCount = scanCount;
        this.sampleCount = sampleCount;
        this.geometry = geometry;
        this.feedIndex = feedIndex;
    }

    @Override
    public int getScanCount() {
        return scanCount;
    }

    @Override
    public int getSampleCount() {
        return sampleCount;
    }

    @Override
    public GeoPos getGeoPos(int scanIndex, int sampleIndex) {
        return geometry.getGeoPos(scanIndex, sampleIndex, 0);
    }

    @Override
    public double getValue(int scanIndex, int sampleIndex) {
        if (scanIndex < 0 || scanIndex >= scanCount || sampleIndex < 0 || sampleIndex >= sampleCount) {
            throw new IllegalArgumentException("Sample index out of range: scan=" + scanIndex + ", sample=" + sampleIndex);
        }
        return values.get(scanIndex * sampleCount + sampleIndex);
    }

    public int getFeedIndex() {
        return feedIndex;
    }

    public int getRasterDataType() {
        return values.getRasterDataType();
    }

    private static CimrSampleBuffer toSampleBuffer(double[][] values) {
        if (values == null || values.length == 0 || values[0].length == 0) {
            throw new IllegalArgumentException("values must not be empty");
        }

        int sampleCount = values[0].length;
        double[] flattened = new double[values.length * sampleCount];

        for (int scanIndex = 0; scanIndex < values.length; scanIndex++) {
            double[] row = values[scanIndex];
            if (row.length != sampleCount) {
                throw new IllegalArgumentException("all scan rows must have same sample count");
            }
            System.arraycopy(row, 0, flattened, scanIndex * sampleCount, sampleCount);
        }
        return CimrSampleBuffer.wrap(org.esa.snap.core.datamodel.ProductData.TYPE_FLOAT64, flattened, flattened.length);
    }

    private static int getScanCount(double[][] values) {
        return values.length;
    }

    private static int getSampleCount(double[][] values) {
        return values[0].length;
    }
}
