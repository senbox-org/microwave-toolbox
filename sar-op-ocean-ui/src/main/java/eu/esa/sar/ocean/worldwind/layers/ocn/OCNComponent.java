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

import eu.esa.sar.ocean.worldwind.layers.Level2ProductLayer;
import gov.nasa.worldwind.WorldWind;
import gov.nasa.worldwind.avlist.AVKey;
import gov.nasa.worldwind.geom.Angle;
import gov.nasa.worldwind.geom.LatLon;
import gov.nasa.worldwind.geom.Position;
import gov.nasa.worldwind.geom.Sector;
import gov.nasa.worldwind.render.BasicShapeAttributes;
import gov.nasa.worldwind.render.DrawContext;
import gov.nasa.worldwind.render.Material;
import gov.nasa.worldwind.render.Path;
import gov.nasa.worldwind.render.Renderable;
import gov.nasa.worldwind.render.ShapeAttributes;
import gov.nasa.worldwindx.examples.analytics.AnalyticSurface;
import gov.nasa.worldwindx.examples.analytics.AnalyticSurfaceAttributes;
import gov.nasa.worldwindx.examples.util.DirectedPath;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.worldwind.ColorBarLegend;
import org.esa.snap.worldwind.ProductRenderablesInfo;
import org.esa.snap.worldwind.layers.BaseLayer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public abstract class OCNComponent {

    protected final Level2ProductLayer theWWLayer;
    protected final Map<String, ColorBarLegend> theColorBarLegendHash;
    protected final Map<Object, String> theObjectInfoHash;
    protected final Map<Object, Product> theSurfaceProductHash;
    protected final Map<Object, Integer> theSurfaceSequenceHash;

    protected static final double GLOBE_RADIUS = 6371000;

    public OCNComponent(final Level2ProductLayer wwLayer,
                        final Map<String, ColorBarLegend> theColorBarLegendHash,
                        final Map<Object, String> theObjectInfoHash,
                        final Map<Object, Product> theSurfaceProductHash,
                        final Map<Object, Integer> theSurfaceSequenceHash) {
        this.theWWLayer = wwLayer;
        this.theColorBarLegendHash = theColorBarLegendHash;
        this.theObjectInfoHash = theObjectInfoHash;
        this.theSurfaceProductHash = theSurfaceProductHash;
        this.theSurfaceSequenceHash = theSurfaceSequenceHash;
    }

    public abstract void addProduct(final Product product, final ProductRenderablesInfo productRenderablesInfo) throws IOException;

    protected double getData(MetadataElement element) {
        if (element.getElement("Values") != null) {
            return element.getElement("Values").getAttribute("data").getData().getElemDouble();
        }
        return 0;
    }

    public void setArrowsDisplayed(boolean displayed) {
    }

    protected void createColorSurfaceWithGradient(GeoPos geoPos1, GeoPos geoPos2, double[] latValues,
                                                  double[] lonValues, double[] values, int width, int height,
                                                  double minValue, double maxValue, boolean whiteZero,
                                                  List<Renderable> renderableList,
                                                  ProductRenderablesInfo prodRenderInfo, String comp) {
        ColorGradient.createColorSurface(theWWLayer, geoPos1, geoPos2, latValues, lonValues, values, width, height,
                renderableList, prodRenderInfo, comp);

        // don't create color legend if one already exists
        if (theColorBarLegendHash.get(comp) != null) {
            // use the existing limits
            minValue = theColorBarLegendHash.get(comp).getMinValue();
            maxValue = theColorBarLegendHash.get(comp).getMaxValue();
        }

        ColorGradient.createColorGradient(minValue, maxValue, whiteZero, prodRenderInfo, comp);
    }

    protected void createWVColorSurfaceWithGradient(Product product,
                                                  double[] latValues,
                                                  double[] lonValues,
                                                  double[] values,
                                                  List<Renderable> renderableList,
                                                  String comp) {
        //SystemUtils.LOG.info(":: createWVColorSurfaceWithGradient ");

        final ShapeAttributes dpAttrs = new BasicShapeAttributes();
        dpAttrs.setOutlineMaterial(Material.WHITE);
        dpAttrs.setOutlineWidth(2d);
        // we cannot make it a scalar object because it has to be final and then we won't be able to assign to it
        // we'll store it as a first object of a final array
        //final List<Object> ctgSurfaceList = new ArrayList<Object>();

        for (int ind = 0; ind < values.length; ind++) {
            final int finalInd = ind;
            final List<Position> polygonPositions = new ArrayList<>();
            double vignette_half_side_deg = (180 / Math.PI) * 10000 / GLOBE_RADIUS;

            polygonPositions.add(new Position(Angle.fromDegreesLatitude(latValues[ind] - vignette_half_side_deg), Angle.fromDegreesLongitude(lonValues[ind] - vignette_half_side_deg), 10.0));
            polygonPositions.add(new Position(Angle.fromDegreesLatitude(latValues[ind] - vignette_half_side_deg), Angle.fromDegreesLongitude(lonValues[ind] + vignette_half_side_deg), 10.0));
            polygonPositions.add(new Position(Angle.fromDegreesLatitude(latValues[ind] + vignette_half_side_deg), Angle.fromDegreesLongitude(lonValues[ind] + vignette_half_side_deg), 10.0));
            polygonPositions.add(new Position(Angle.fromDegreesLatitude(latValues[ind] + vignette_half_side_deg), Angle.fromDegreesLongitude(lonValues[ind] - vignette_half_side_deg), 10.0));
            polygonPositions.add(new Position(Angle.fromDegreesLatitude(latValues[ind] - vignette_half_side_deg), Angle.fromDegreesLongitude(lonValues[ind] - vignette_half_side_deg), 10.0));

            Path p = theWWLayer.createPath(polygonPositions, BaseLayer.WHITE_MATERIAL, BaseLayer.RED_MATERIAL);

            theWWLayer.addRenderable(p);
            if (renderableList != null) {
                renderableList.add(p);
            }

            String info = "";
            if (comp.equalsIgnoreCase("osw")) {
                info = "Wave Length: " + values[ind] + "<br/>";
            } else if (comp.equalsIgnoreCase("owi")) {
                info = "Wind Speed: " + values[ind] + "<br/>";
            } else if (comp.equalsIgnoreCase("rvl")) {
                info = "Radial Velocity: " + values[ind] + "<br/>";
            }
            String finalInfo = info;

            //AnalyticSurface analyticSurface = new AnalyticSurface();
            AnalyticSurface analyticSurface = new AnalyticSurface() {
                public void render(DrawContext dc) {
                    super.render(dc);
                    if (clampToGroundSurface != null) {
                        theObjectInfoHash.put(clampToGroundSurface, finalInfo);
                        theSurfaceProductHash.put(clampToGroundSurface, product);
                        theSurfaceSequenceHash.put(clampToGroundSurface, finalInd);
                    }
                }
            };

            analyticSurface.setSector(Sector.fromDegrees(
                     latValues[ind] - vignette_half_side_deg,
                    latValues[ind] + vignette_half_side_deg,
                    lonValues[ind] - vignette_half_side_deg,
                    lonValues[ind] + vignette_half_side_deg));
            analyticSurface.setAltitudeMode(WorldWind.CLAMP_TO_GROUND);
            // one by one square doesn't seem to work, so we'll just use the next smallest
            // possible square and repeat the same value 4 times
            analyticSurface.setDimensions(2, 2);

            final List<AnalyticSurface.GridPointAttributes> attributesList = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                attributesList.add(ColorGradient.createColorGradientAttributes(values[ind], 0, 10,
                        ColorGradient.HUE_RED, ColorGradient.HUE_MAX_RED, false));
            }

            analyticSurface.setValues(attributesList);

            AnalyticSurfaceAttributes attr = new AnalyticSurfaceAttributes();
            attr.setDrawShadow(false);
            attr.setInteriorOpacity(1.0);
            //attr.setOutlineWidth(3);
            attr.setDrawOutline(false);
            analyticSurface.setSurfaceAttributes(attr);
            analyticSurface.setClientLayer(theWWLayer);

            theWWLayer.addRenderable(analyticSurface);
            if (renderableList != null) {
                renderableList.add(analyticSurface);
            }
        }
    }

    void addWaveLengthArrows(double[] latValues,
                             double[] lonValues,
                             double[] waveLengthValues,
                             double[] waveDirValues,
                             List<Renderable> renderableList) {
        //SystemUtils.LOG.info(":: addWaveLengthArrows ");

        final ShapeAttributes dpAttrs = new BasicShapeAttributes();
        dpAttrs.setOutlineMaterial(Material.WHITE);
        dpAttrs.setOutlineWidth(2d);

        for (int ind = 0; ind < waveLengthValues.length; ind++) {
            try {
                //int ind = row*width + col;
                double arrowLength_deg = waveLengthValues[ind] / 4000;
                if(arrowLength_deg < 0)
                    continue;

                double arrowHeadLength = Angle.fromDegrees(arrowLength_deg).radians * GLOBE_RADIUS / 3;

                final Position startPos = new Position(Angle.fromDegreesLatitude(latValues[ind]), Angle.fromDegreesLongitude(lonValues[ind]), 10.0);
                final Position endPos = new Position(LatLon.greatCircleEndPosition(startPos, Angle.fromDegrees(waveDirValues[ind]), Angle.fromDegrees(arrowLength_deg)), 10.0);

                //System.out.println("waveLengthValues[i] " + waveLengthValues[i]);

                final List<Position> positions = new ArrayList<>();
                positions.add(startPos);
                positions.add(endPos);

                DirectedPath directedPath = getDirectedPath(positions, dpAttrs);
                directedPath.setArrowLength(arrowHeadLength);

                theWWLayer.addRenderable(directedPath);
                if (renderableList != null) {
                    renderableList.add(directedPath);
                }
            } catch (Exception e) {
                SystemUtils.LOG.info(":: addWaveLengthArrows exception " + e);
            }
        }
    }

    protected static DirectedPath getDirectedPath(final List<Position> positions, final ShapeAttributes dpAttrs) {
        DirectedPath directedPath = new DirectedPath(positions);
        directedPath.setAttributes(dpAttrs);
        //directedPath.setHighlightAttributes(highlightAttrs);
        directedPath.setVisible(true);
        directedPath.setFollowTerrain(true);
        directedPath.setAltitudeMode(WorldWind.RELATIVE_TO_GROUND);
        directedPath.setPathType(AVKey.GREAT_CIRCLE);
        return directedPath;
    }

    protected Band[] findBands(final Product product, final String ... searchStrings) {
        List<Band> bandList = new ArrayList<>();
        for(Band band : product.getBands()) {
            int found = 0;
            for(String searchString : searchStrings) {
                if(band.getName().contains(searchString)) {
                    found++;
                }
            }
            if (found == searchStrings.length) {
                bandList.add(band);
            }
        }
        return bandList.toArray(new Band[0]);
    }
}
