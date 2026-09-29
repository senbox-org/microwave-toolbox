package eu.esa.snap.cimr.grid;

import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.dataop.maptransf.Datum;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;

import java.awt.*;
import java.awt.geom.AffineTransform;


public class LazyCrsGeoCoding implements GeoCoding {

    private final CimrGrid grid;
    private GeoCoding delegate;

    public LazyCrsGeoCoding(CimrGrid grid) {
        this.grid = grid;
    }

    private GeoCoding getDelegate() {
        if (delegate == null) {
            synchronized (this) {
                if (delegate == null) {
                    try {
                        final int width = grid.getWidth();
                        final int height = grid.getHeight();
                        CoordinateReferenceSystem crs = grid.getProjection().getCrs();
                        AffineTransform imageToModel = grid.getProjection().getAffineTransform(grid);
                        delegate = new CrsGeoCoding(crs, new Rectangle(width, height), imageToModel);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to create CrsGeoCoding", e);
                    }
                }
            }
        }
        return delegate;
    }

    @Override
    public boolean isCrossingMeridianAt180() {
        return getDelegate().isCrossingMeridianAt180();
    }

    @Override
    public boolean canGetPixelPos() {
        return true;
    }

    @Override
    public boolean canGetGeoPos() {
        return true;
    }

    @Override
    public PixelPos getPixelPos(GeoPos geoPos, PixelPos pixelPos) {
        return getDelegate().getPixelPos(geoPos, pixelPos);
    }

    @Override
    public GeoPos getGeoPos(PixelPos pixelPos, GeoPos geoPos) {
        return getDelegate().getGeoPos(pixelPos, geoPos);
    }

    @Override
    public Datum getDatum() {
        return getDelegate().getDatum();
    }

    @Override
    public void dispose() {
        getDelegate().dispose();
    }

    @Override
    public CoordinateReferenceSystem getImageCRS() {
        return getDelegate().getImageCRS();
    }

    @Override
    public CoordinateReferenceSystem getMapCRS() {
        return getDelegate().getMapCRS();
    }

    @Override
    public CoordinateReferenceSystem getGeoCRS() {
        return getDelegate().getGeoCRS();
    }

    @Override
    public MathTransform getImageToMapTransform() {
        return getDelegate().getImageToMapTransform();
    }

    @Override
    public GeoCoding clone() {
        return getDelegate().clone();
    }

    @Override
    public boolean canClone() {
        return getDelegate().canClone();
    }

    @Override
    public boolean isGlobal() {
        return true;
    }
}
