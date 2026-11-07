package eu.esa.snap.cimr.grid;

import org.esa.snap.core.datamodel.GeoPos;


public class CimrTiepointGeometry implements CimrGeometry {

    private final int scanCount;
    private final int sampleCount;
    private final int tiePointCount;
    private final GeoPos[][][] tiePoints;  // [scan][tiePoint][feed]


    public CimrTiepointGeometry(GeoPos[][][] tiePoints, int sampleCount) {
        if (tiePoints.length == 0) {
            throw new IllegalArgumentException("scan dimension must be > 0");
        }
        int scans = tiePoints.length;
        int tpCount = tiePoints[0].length;
        if (tpCount == 0) {
            throw new IllegalArgumentException("tiePoint dimension must be > 0");
        }
        int feeds = tiePoints[0][0].length;
        for (int s = 0; s < scans; s++) {
            if (tiePoints[s].length != tpCount) {
                throw new IllegalArgumentException("tiePoint dimension mismatch at scan " + s);
            }
            for (int tp = 0; tp < tpCount; tp++) {
                if (tiePoints[s][tp].length != feeds) {
                    throw new IllegalArgumentException("feed dimension mismatch at scan " + s + ", tp " + tp);
                }
            }
        }
        if (sampleCount < 2) {
            throw new IllegalArgumentException("sampleCount must be >= 2");
        }

        this.scanCount = scans;
        this.tiePointCount = tpCount;
        this.sampleCount = sampleCount;
        this.tiePoints = tiePoints;
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
    public int getTiePointCount() {
        return tiePointCount;
    }

    @Override
    public GeoPos getGeoPos(int scanIndex, int sampleIndex, int feedIndex) {
        if (scanIndex < 0 || scanIndex >= scanCount) {
            throw new IllegalArgumentException("scanIndex out of range: " + scanIndex);
        }
        if (sampleIndex < 0 || sampleIndex >= sampleCount) {
            throw new IllegalArgumentException("sampleIndex out of range: " + sampleIndex);
        }
        if (feedIndex < 0 || feedIndex >= tiePoints[0][0].length) {
            throw new IllegalArgumentException("feedIndex out of range: " + feedIndex);
        }

        TiepointInterpolator.Position position = TiepointInterpolator.position(sampleIndex, sampleCount, tiePointCount);
        GeoPos p0 = tiePoints[scanIndex][position.getLowerIndex()][feedIndex];
        GeoPos p1 = tiePoints[scanIndex][position.getUpperIndex()][feedIndex];

        return TiepointInterpolator.interpolate(p0, p1, position.getFraction());
    }
}
