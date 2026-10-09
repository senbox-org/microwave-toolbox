package eu.esa.snap.cimr.grid;

import org.esa.snap.core.dataio.ProductSubsetDef;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.dataop.maptransf.Datum;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;

import java.awt.*;
import java.awt.geom.AffineTransform;


public class LazyCrsGeoCoding extends AbstractGeoCoding {


    private final CimrGrid grid;
    private GeoCoding delegate;


    public LazyCrsGeoCoding(CimrGrid grid) throws FactoryException {
        super(grid.getProjection().getCrs());
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
        GeoCoding geoCoding = delegate;
        if (geoCoding != null) {
            geoCoding.dispose();
        }
    }

    @Override
    public boolean transferGeoCoding(Scene srcScene, Scene destScene, ProductSubsetDef subsetDef) {
        return ((AbstractGeoCoding) getDelegate()).transferGeoCoding(srcScene, destScene, subsetDef);
    }

    @Override
    public MathTransform getImageToMapTransform() {
        return getDelegate().getImageToMapTransform();
    }

    @Override
    public GeoCoding clone() {
        throw new IllegalStateException("not implemented");
    }

    @Override
    public boolean canClone() {
        return false;
    }

    @Override
    public boolean isGlobal() {
        return true;
    }
}
