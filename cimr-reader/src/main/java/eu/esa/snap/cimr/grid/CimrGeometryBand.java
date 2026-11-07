package eu.esa.snap.cimr.grid;

import org.esa.snap.core.datamodel.GeoPos;


public class CimrGeometryBand implements CimrBand {


    private final CimrSampleBuffer values;
    private final int scanCount;
    private final int sampleCount;
    private final CimrGeometry geometry;
    private final int feedIndex;


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
}
