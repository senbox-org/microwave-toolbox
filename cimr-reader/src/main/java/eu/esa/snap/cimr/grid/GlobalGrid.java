package eu.esa.snap.cimr.grid;

import org.esa.snap.core.datamodel.GeoPos;

import java.awt.*;


public class GlobalGrid {

    private int width;
    private int height;
    private final GridProjection projection;


    public GlobalGrid(GridProjection projection, int width, int height) {
        this.projection = projection;
        this.height = height;
        this.width = width;
    }

    public GeoPos gridToGeoPos(int x, int y) {
        return projection.gridToGeoPos(x, y);
    }

    public boolean geoPosToGrid(GeoPos pos, Point out) {
        return projection.geoPosToGrid(pos, out);
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public GridProjection getProjection() {
        return projection;
    }
}
