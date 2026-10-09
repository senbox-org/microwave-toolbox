package eu.esa.snap.cimr.grid;


public class CimrGridFactory {

    // TODO: BL-01/10/2026 - discuss appropriate default cell size
    public static final double DEFAULT_CELL_SIZE_DEG = .02;


    public static CimrGrid createPlateCarreeFromBoundingBox(CimrBoundingBox bBox) {
        return createPlateCarreeFromBoundingBox(bBox, DEFAULT_CELL_SIZE_DEG);
    }

    public static CimrGrid createPlateCarreeFromBoundingBox(CimrBoundingBox bBox, double cellSizeDeg) {
        int width  = (int) Math.round(bBox.getWidth() / cellSizeDeg);
        int height = (int) Math.round(bBox.getHeight() / cellSizeDeg);

        double lonMin = bBox.lonMin;
        double latMax = bBox.latMax;

        PlateCarreeProjection proj = new PlateCarreeProjection(
                width, height, lonMin, latMax, cellSizeDeg, cellSizeDeg
        );
        return new CimrGrid(proj, width, height);
    }
}
