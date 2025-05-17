/*
 * Copyright (C) 2024 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.ocean.worldwind.layers.ocn;

import gov.nasa.worldwind.WorldWind;
import gov.nasa.worldwind.geom.Angle;
import gov.nasa.worldwind.geom.Position;
import gov.nasa.worldwind.geom.Sector;
import gov.nasa.worldwind.render.DrawContext;
import gov.nasa.worldwind.render.Renderable;
import gov.nasa.worldwind.util.BufferFactory;
import gov.nasa.worldwind.util.BufferWrapper;
import gov.nasa.worldwind.util.WWMath;
import gov.nasa.worldwindx.examples.analytics.AnalyticSurface;
import gov.nasa.worldwindx.examples.analytics.AnalyticSurfaceAttributes;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.worldwind.ProductRenderablesInfo;
import org.esa.snap.worldwind.layers.WWLayer;

import java.awt.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class ColorGradient {

    public static double HUE_BLUE = 240d / 360d;
    public static final double HUE_RED = 0d / 360d;
    public static final double HUE_MAX_RED = 1.0;


    public static void createColorGradient(double minValue, double maxValue, boolean whiteZero,
                                            ProductRenderablesInfo prodRenderInfo, String comp) {
        //SystemUtils.LOG.info("createColorGradient " + minValue + " " + maxValue + " " + comp);
        List<AnalyticSurface> analyticSurfaces = null;
        List<BufferWrapper> analyticSurfaceValueBuffers = null;

        if (comp.equalsIgnoreCase("owi")) {
            analyticSurfaces = prodRenderInfo.owiAnalyticSurfaces;
            analyticSurfaceValueBuffers = prodRenderInfo.owiAnalyticSurfaceValueBuffers;
        } else if (comp.equalsIgnoreCase("osw")) {
            analyticSurfaces = prodRenderInfo.oswAnalyticSurfaces;
            analyticSurfaceValueBuffers = prodRenderInfo.oswAnalyticSurfaceValueBuffers;
        } else if (comp.equalsIgnoreCase("rvl")) {
            analyticSurfaces = prodRenderInfo.rvlAnalyticSurfaces;
            analyticSurfaceValueBuffers = prodRenderInfo.rvlAnalyticSurfaceValueBuffers;
        }

        if (analyticSurfaces != null) {

            for (int currSurfInd = 0; currSurfInd < analyticSurfaces.size(); currSurfInd++) {

                AnalyticSurface analyticSurface = analyticSurfaces.get(currSurfInd);
                BufferWrapper analyticSurfaceValueBuffer = analyticSurfaceValueBuffers.get(currSurfInd);
                final List<AnalyticSurface.GridPointAttributes> attributesList = new ArrayList<>();
                for (int i = 0; i < analyticSurfaceValueBuffer.length(); i++) {
                    double d = analyticSurfaceValueBuffer.getDouble(i);
                    attributesList.add(
                            createColorGradientAttributes(d, minValue, maxValue, HUE_RED, HUE_MAX_RED, whiteZero));
                }

                analyticSurface.setValues(attributesList);
            }
        }
    }

    public static AnalyticSurface.GridPointAttributes createColorGradientAttributes(final double value,
                                                                                     double minValue, double maxValue,
                                                                                     double minHue, double maxHue,
                                                                                     boolean whiteZero) {
        final double hueFactor = WWMath.computeInterpolationFactor(value, minValue, maxValue);

        //double hue = WWMath.mixSmooth(hueFactor, minHue, maxHue);
        final double hue = WWMath.mix(hueFactor, minHue, maxHue);
        double sat = 1.0;
        if (whiteZero) {
            sat = Math.abs(WWMath.mixSmooth(hueFactor, -1, 1));
        }
        final Color color = Color.getHSBColor((float) hue, (float) sat, 1f);
        final double opacity = WWMath.computeInterpolationFactor(value, minValue, minValue + (maxValue - minValue) * 0.1);
        final Color rgbaColor = new Color(color.getRed(), color.getGreen(), color.getBlue(), (int) (255 * opacity));

        return AnalyticSurface.createGridPointAttributes(value, rgbaColor);
    }

    public static void createColorSurface(WWLayer theWWLayer, GeoPos geoPos1, GeoPos geoPos2, double[] latValues, double[] lonValues, double[] vals,
                                          int width, int height, List<Renderable> renderableList,
                                          ProductRenderablesInfo prodRenderInfo, String comp) {
        //SystemUtils.LOG.info("createColorSurface " + latValues.length + " " + lonValues.length + " " + vals.length + " " + width + " " + height);

        // analytic surface has to be overidden in order to allow for non-rectangular surfaces
        // the approach is render the surface point by point and not use the sector as the boundary
        AnalyticSurface analyticSurface = new AnalyticSurface() {

            protected void doUpdate(DrawContext dc) {
                this.referencePos = new Position(this.sector.getCentroid(), this.altitude);
                this.referencePoint = dc.getGlobe().computePointFromPosition(this.referencePos);

                if (this.surfaceRenderInfo == null ||
                        this.surfaceRenderInfo.getGridWidth() != this.width ||
                        this.surfaceRenderInfo.getGridHeight() != this.height) {
                    this.surfaceRenderInfo = new RenderInfo(this.width, this.height) {
                        public void drawInterior(DrawContext dc) {
                            if (dc == null) {
                                cartesianVertexBuffer.rewind();
                                geographicVertexBuffer.rewind();
                                colorBuffer.rewind();
                                shadowColorBuffer.rewind();
                                return;
                            }
                            super.drawInterior(dc);
                        }
                    };
                }

                this.updateSurfacePoints(dc, this.surfaceRenderInfo);
                this.updateSurfaceNormals(this.surfaceRenderInfo);
            }

            protected void updateSurfacePoints(DrawContext dc, RenderInfo outRenderInfo) {
                Iterator<? extends GridPointAttributes> iter = this.values.iterator();

                for (int row = 0; row < this.height; row++) {
                    for (int col = 0; col < this.width; col++) {
                        int i = row * (this.width) + col;
                        GridPointAttributes attr = iter.hasNext() ? iter.next() : null;

                        this.updateNextSurfacePoint(dc, Angle.fromDegrees(latValues[i]),
                                Angle.fromDegrees(lonValues[i]), attr, outRenderInfo);
                    }
                }

                outRenderInfo.drawInterior(null);
            }
        };
        analyticSurface.setSector(Sector.fromDegrees(geoPos2.getLat(), geoPos1.getLat(), geoPos1.getLon(), geoPos2.getLon()));
        analyticSurface.setAltitudeMode(WorldWind.CLAMP_TO_GROUND);
        analyticSurface.setDimensions(width, height);

        AnalyticSurfaceAttributes attr = new AnalyticSurfaceAttributes();
        attr.setDrawShadow(false);
        attr.setInteriorOpacity(1.0);
        //attr.setOutlineWidth(3);
        attr.setDrawOutline(false);
        analyticSurface.setSurfaceAttributes(attr);

        analyticSurface.setClientLayer(theWWLayer);
        //addRenderable(analyticSurface);

        //BufferWrapper analyticSurfaceValueBuffer = randomGridValues(width, height, minValue, maxValue);
        BufferWrapper analyticSurfaceValueBuffer = (new BufferFactory.DoubleBufferFactory()).newBuffer(vals.length);
        analyticSurfaceValueBuffer.putDouble(0, vals, 0, vals.length);

        //BufferWrapper analyticSurfaceValueBuffer = (new BufferFactory.DoubleBufferFactory()).newBuffer(latValues.length);
        //analyticSurfaceValueBuffer.putDouble(0, latValues, 0, latValues.length);

        //smoothValues(width, height, values, 0.5d);
        //scaleValues(values, values.length, minValue, maxValue);

        //mixValuesOverTime(2000L, firstBuffer, analyticSurfaceValueBuffer, minValue, maxValue, minHue, maxHue, analyticSurface);

        prodRenderInfo.setAnalyticSurfaceAndBuffer(analyticSurface, analyticSurfaceValueBuffer, comp);
        if (renderableList != null) {
            renderableList.add(analyticSurface);
        }
    }
}
