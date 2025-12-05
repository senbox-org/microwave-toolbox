package eu.esa.snap.cimr.grid;

import org.esa.snap.core.datamodel.GeoPos;

import java.awt.*;


public class CimrGrid {

    private final int width;
    private final int height;
    private final GridProjection projection;


    public CimrGrid(GridProjection projection, int width, int height) {
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
