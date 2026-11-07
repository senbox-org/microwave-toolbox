package eu.esa.snap.cimr.grid;

import org.esa.snap.core.datamodel.GeoPos;


public class TiepointInterpolator {


    private TiepointInterpolator() {}


    public static double interpolate(double v0, double v1, double fraction) {
        return v0 + fraction * (v1 - v0);
    }

    public static GeoPos interpolate(GeoPos p0, GeoPos p1, double fraction) {
        double lat = interpolate(p0.getLat(), p1.getLat(), fraction);
        double lon = interpolate(p0.getLon(), p1.getLon(), fraction);
        return new GeoPos((float) lat, (float) lon);
    }

    public static Position position(int sampleIndex, int sampleCount, int tiePointCount) {
        if (sampleIndex < 0 || sampleIndex >= sampleCount) {
            throw new IllegalArgumentException("sampleIndex out of range: " + sampleIndex);
        }
        if (sampleCount < 2) {
            throw new IllegalArgumentException("sampleCount must be >= 2");
        }
        if (tiePointCount < 1) {
            throw new IllegalArgumentException("tiePointCount must be >= 1");
        }

        double t = (double) sampleIndex * (tiePointCount - 1) / (double) (sampleCount - 1);
        int lowerIndex = (int) Math.floor(t);
        int upperIndex = Math.min(lowerIndex + 1, tiePointCount - 1);
        double fraction = t - lowerIndex;

        return new Position(lowerIndex, upperIndex, fraction);
    }


    public static class Position {


        private final int lowerIndex;
        private final int upperIndex;
        private final double fraction;


        private Position(int lowerIndex, int upperIndex, double fraction) {
            this.lowerIndex = lowerIndex;
            this.upperIndex = upperIndex;
            this.fraction = fraction;
        }


        public int getLowerIndex() {
            return lowerIndex;
        }

        public int getUpperIndex() {
            return upperIndex;
        }

        public double getFraction() {
            return fraction;
        }
    }
}
