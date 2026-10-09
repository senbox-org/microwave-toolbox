package eu.esa.snap.cimr.grid;


public class CimrBoundingBox {


    private static final double DEFAULT_BOUNDING_BOX_OFFSET_DEG = .5;

    double lonMin;
    double lonMax;
    double latMin;
    double latMax;


    public CimrBoundingBox(double lonMin, double lonMax, double latMin, double latMax) {
        this.lonMin = lonMin;
        this.lonMax = lonMax;
        this.latMin = latMin;
        this.latMax = latMax;
    }


    public double getLonMin() {
        return lonMin;
    }

    public double getLonMax() {
        return lonMax;
    }

    public double getLatMin() {
        return latMin;
    }

    public double getLatMax() {
        return latMax;
    }

    public double getWidth() {
        return lonMax - lonMin;
    }

    public double getHeight() {
        return latMax - latMin;
    }

    public static CimrBoundingBox create(double lonMin, double lonMax, double latMin, double latMax, double cellSizeDeg) {
        lonMin = snapToGrid(lonMin - DEFAULT_BOUNDING_BOX_OFFSET_DEG, true, cellSizeDeg);
        lonMax = snapToGrid(lonMax + DEFAULT_BOUNDING_BOX_OFFSET_DEG, false, cellSizeDeg);
        latMin = snapToGrid(latMin - DEFAULT_BOUNDING_BOX_OFFSET_DEG, true, cellSizeDeg);
        latMax = snapToGrid(latMax + DEFAULT_BOUNDING_BOX_OFFSET_DEG, false, cellSizeDeg);

        return new CimrBoundingBox(lonMin, lonMax, latMin, latMax);
    }

    private static double snapToGrid(double val, boolean isMin, double cellSizeDeg) {
        if (isMin) {
            return Math.floor(val / cellSizeDeg) * cellSizeDeg;
        } else {
            return Math.ceil(val / cellSizeDeg) * cellSizeDeg;
        }
    }
}
