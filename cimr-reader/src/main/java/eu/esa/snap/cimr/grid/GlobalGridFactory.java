package eu.esa.snap.cimr.grid;


public class GlobalGridFactory {

    public static GlobalGrid createGlobalPlateCarree(double cellSizeDeg) {
        int width  = (int) Math.round(360.0 / cellSizeDeg);
        int height = (int) Math.round(180.0 / cellSizeDeg);

        double lonMin = 0.0;
        double latMax = 90.0;

        PlateCarreeProjection proj = new PlateCarreeProjection(
                width, height, lonMin, latMax, cellSizeDeg, cellSizeDeg
        );
        return new GlobalGrid(proj, width, height);
    }
}
