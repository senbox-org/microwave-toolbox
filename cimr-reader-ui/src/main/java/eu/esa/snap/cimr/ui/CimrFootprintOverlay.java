package eu.esa.snap.cimr.ui;

import com.bc.ceres.glayer.swing.LayerCanvas;
import com.bc.ceres.grender.Rendering;
import com.bc.ceres.grender.Viewport;
import eu.esa.snap.cimr.cimr.CimrFootprint;

import java.awt.*;
import java.awt.geom.*;
import java.util.List;


public class CimrFootprintOverlay implements LayerCanvas.Overlay {

    public static final CimrFootprintOverlay INSTANCE = new CimrFootprintOverlay();

    private List<CimrFootprint> footprints;

    private CimrFootprintOverlay() {}

    public void setFootprints(List<CimrFootprint> footprints) {
        this.footprints = footprints;
    }

    @Override
    public void paintOverlay(LayerCanvas canvas, Rendering rendering) {
        Graphics2D g = rendering.getGraphics();

        Color oldColor = g.getColor();
        java.awt.Stroke oldStroke = g.getStroke();

        Viewport vp = canvas.getViewport();
        AffineTransform m2vBase = vp.getModelToViewTransform();

        for (CimrFootprint fp : footprints) {
            double cx = fp.getGeoPos().getLon();
            double cy = fp.getGeoPos().getLat();
            double rx = fp.getMajorAxisDegree();
            double ry = fp.getMinorAxisDegree();

            Ellipse2D modelEllipse = new Ellipse2D.Double(cx - rx, cy - ry, 2 * rx, 2 * ry);
            double angleRad = Math.toRadians(fp.getAngle());

            AffineTransform rotModel = AffineTransform.getRotateInstance(angleRad, cx, cy);
            Shape rotatedModelShape = rotModel.createTransformedShape(modelEllipse);
            Shape viewEllipse = m2vBase.createTransformedShape(rotatedModelShape);

            g.setColor(new Color(255, 255, 255, 255));
            g.fill(viewEllipse);
        }

        g.setStroke(oldStroke);
        g.setColor(oldColor);
    }

}
