/*
 * Copyright (C) 2014 by Array Systems Computing Inc. http://www.array.ca
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */
package eu.esa.microwave.dat.layers.maptools.components;

import eu.esa.microwave.dat.layers.ScreenPixelConverter;
import org.esa.snap.core.datamodel.RasterDataNode;
import org.esa.snap.ui.UIUtils;

import javax.swing.ImageIcon;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/**
 * map tools logo component
 */
public class LogoComponent implements MapToolsComponent {

    private static final ImageIcon logoIcon = UIUtils.loadImageIcon("/eu/esa/microwave/dat/icons/SNAP_icon_128.png", LogoComponent.class);
    private final BufferedImage image;
    private final static double marginPct = 0.05;
    private final double scale;
    private final Point point;

    public LogoComponent(final RasterDataNode raster) {

        if(logoIcon != null) {
            image = new BufferedImage(logoIcon.getIconWidth(), logoIcon.getIconHeight(), BufferedImage.TYPE_4BYTE_ABGR);
            final Graphics2D g = image.createGraphics();
            g.drawImage(logoIcon.getImage(), null, null);

            final int rasterWidth = raster.getRasterWidth();
            final int rasterHeight = raster.getRasterHeight();
            final int size = Math.min(rasterWidth, rasterHeight);
            final int margin = (int) (size * marginPct);

            scale = (marginPct * 2 * size) / (double) image.getWidth();
            point = new Point((int) (rasterWidth - (image.getWidth() * scale) - margin),
                    (int) (rasterHeight - (image.getHeight() * scale) - margin));
        }  else {
            image = null;
            scale = 0;
            point = new Point(0,0);
        }
    }

    public void render(final Graphics2D graphics, final ScreenPixelConverter screenPixel) {
        final AffineTransform transformSave = (AffineTransform)graphics.getTransform().clone();
        try {
            final AffineTransform transform = screenPixel.getImageTransform(graphics.getTransform());

            transform.translate(point.x, point.y);
            transform.scale(scale, scale);

            final double[] vpts = new double[2];
            screenPixel.pixelToScreen(point, vpts);

            graphics.translate(vpts[0], vpts[1]);
            double scale = transform.getScaleX();
            graphics.scale(scale, scale);

            graphics.drawRenderedImage(image, null);
        } finally {
            graphics.setTransform(transformSave);
        }
    }
}
