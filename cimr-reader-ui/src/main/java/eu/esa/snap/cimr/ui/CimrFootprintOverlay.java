package eu.esa.snap.cimr.ui;

import com.bc.ceres.glayer.swing.LayerCanvas;
import com.bc.ceres.grender.Rendering;
import com.bc.ceres.grender.Viewport;
import eu.esa.snap.cimr.cimr.CimrFootprints;
import eu.esa.snap.cimr.cimr.CimrFootprintShape;
import org.esa.snap.core.datamodel.ColorPaletteDef;
import org.esa.snap.core.datamodel.ImageInfo;
import org.esa.snap.core.datamodel.RasterDataNode;
import org.esa.snap.core.image.ImageManager;

import java.awt.*;
import java.awt.geom.*;
import java.util.List;


public class CimrFootprintOverlay implements LayerCanvas.Overlay {

    public static final CimrFootprintOverlay INSTANCE = new CimrFootprintOverlay();

    private CimrFootprints footprints;
    private RasterDataNode raster;

    private CimrFootprintOverlay() {}

    public void setFootprints(CimrFootprints footprints) {
        this.footprints = footprints;
    }

    public void setRaster(RasterDataNode raster) {
        this.raster = raster;
    }

    @Override
    public void paintOverlay(LayerCanvas canvas, Rendering rendering) {
        if (footprints == null || footprints.getShapes().isEmpty()) {
            return;
        }

        Graphics2D g = rendering.getGraphics();

        Color oldColor = g.getColor();
        java.awt.Stroke oldStroke = g.getStroke();

        Viewport vp = canvas.getViewport();
        AffineTransform m2vBase = vp.getModelToViewTransform();

        ImageInfo imageInfo = raster.getImageInfo();
        Color baseColor = Color.WHITE;
        Color[] fullPalette = null;
        ColorPaletteDef cpd = null;

        if (imageInfo != null) {
            cpd = imageInfo.getColorPaletteDef();
            fullPalette = ImageManager.createColorPalette(imageInfo);
        }

        List<CimrFootprintShape> shapes = footprints.getShapes();
        List<Double> values = footprints.getValues();

        for (int ii = 0; ii < shapes.size(); ii++) {
            CimrFootprintShape shape = shapes.get(ii);
            double cx = shape.getGeoPos().getLon();
            double cy = shape.getGeoPos().getLat();
            double rx = shape.getMajorAxisDegree();
            double ry = shape.getMinorAxisDegree();

            Ellipse2D modelEllipse = new Ellipse2D.Double(cx - rx, cy - ry, 2 * rx, 2 * ry);
            double angleRad = Math.toRadians(shape.getAngle());

            AffineTransform rotModel = AffineTransform.getRotateInstance(angleRad, cx, cy);
            Shape rotatedModelShape = rotModel.createTransformedShape(modelEllipse);
            Shape viewEllipse = m2vBase.createTransformedShape(rotatedModelShape);

            if (imageInfo != null && cpd != null) {
                baseColor = getColorForValue(cpd, fullPalette, values.get(ii));
            }

            g.setColor(baseColor);
            g.fill(viewEllipse);
        }

        g.setStroke(oldStroke);
        g.setColor(oldColor);
    }

    private Color getColorForValue(ColorPaletteDef cpd, Color[] fullPalette, double value) {
        int numColors = cpd.getNumColors();
        double min = cpd.getMinDisplaySample();
        double max = cpd.getMaxDisplaySample();

        if (Double.compare(min, max) == 0) {
            Color c = cpd.getLastPoint().getColor();
            return new Color(c.getRed(), c.getGreen(), c.getBlue());
        }

        double v = Math.max(min, Math.min(max, value));
        double f = (v - min) / (max - min);

        int idx = (int) Math.round(f * (numColors - 1));
        idx = Math.max(0, Math.min(idx, numColors - 1));

        return fullPalette[idx];
    }
}
