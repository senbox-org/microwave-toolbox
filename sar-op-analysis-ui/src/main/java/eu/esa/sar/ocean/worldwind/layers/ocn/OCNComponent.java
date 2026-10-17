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

import com.bc.ceres.core.ProgressMonitor;
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
import gov.nasa.worldwind.util.BufferFactory;
import gov.nasa.worldwind.util.BufferWrapper;
import gov.nasa.worldwind.util.WWMath;
import gov.nasa.worldwindx.examples.analytics.AnalyticSurface;
import gov.nasa.worldwindx.examples.analytics.AnalyticSurfaceAttributes;
import gov.nasa.worldwindx.examples.util.DirectedPath;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
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

    // fill value of the OCN variables when they carry no _FillValue attribute
    static final double OCN_FILL_VALUE = -999;
    private static final double NETCDF_DEFAULT_FILL_THRESHOLD = 1e30;

    protected double getData(MetadataElement element) {
        return getData(element, 0);
    }

    /**
     * @return the value at the given index of a variable read into the metadata, or NaN if it is missing or a fill
     * value (invalid imagettes, e.g. over sea ice or land)
     */
    protected double getData(MetadataElement element, int index) {
        if (element == null || element.getElement("Values") == null ||
                element.getElement("Values").getAttribute("data") == null) {
            return Double.NaN;
        }
        final ProductData data = element.getElement("Values").getAttribute("data").getData();
        if (index < 0 || index >= data.getNumElems()) {
            return Double.NaN;
        }
        final double value = data.getElemDoubleAt(index);
        final double fillValue = element.getAttributeDouble("_FillValue", OCN_FILL_VALUE);
        // values that were never written hold the netCDF default fill value (9.96921e36 for floats)
        if (value == fillValue || value == OCN_FILL_VALUE || Math.abs(value) > NETCDF_DEFAULT_FILL_THRESHOLD) {
            return Double.NaN;
        }
        return value;
    }

    /**
     * @return the number of values of a variable read into the metadata
     */
    protected static int getNumValues(MetadataElement element) {
        if (element == null || element.getElement("Values") == null ||
                element.getElement("Values").getAttribute("data") == null) {
            return 0;
        }
        return element.getElement("Values").getAttribute("data").getData().getNumElems();
    }

    /**
     * Reads a whole band and replaces its no-data values by NaN, so that fill values (-999 in OCN products)
     * are not mistaken for data.
     */
    protected static double[] readValues(final Band band) throws IOException {
        final int w = band.getRasterWidth();
        final int h = band.getRasterHeight();
        final double[] values = new double[w * h];
        band.readPixels(0, 0, w, h, values, ProgressMonitor.NULL);
        replaceNoData(values, band);
        return values;
    }

    static void replaceNoData(final double[] values, final Band band) {
        if (!band.isNoDataValueUsed()) {
            return;
        }
        // the pixels are read as geophysical values
        final double noData = band.getGeophysicalNoDataValue();
        for (int i = 0; i < values.length; i++) {
            if (values[i] == noData) {
                values[i] = Double.NaN;
            }
        }
    }

    static boolean isValidLatLon(final double lat, final double lon) {
        return !Double.isNaN(lat) && !Double.isNaN(lon) && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 360;
    }

    /**
     * @return the longitude in [-180, 180]; Angle.fromDegreesLongitude clamps larger values to 180
     */
    static double normalizeLon(final double lon) {
        return lon > 180 ? lon - 360 : lon;
    }

    /**
     * Accumulates directions as unit vectors: the arithmetic mean of 350 and 10 degrees is 180,
     * their circular mean is 0.
     */
    static final class DirectionMean {
        private double sumSin;
        private double sumCos;
        private int count;

        void add(final double directionDeg) {
            if (Double.isNaN(directionDeg)) {
                return;
            }
            final double rad = Math.toRadians(directionDeg);
            sumSin += Math.sin(rad);
            sumCos += Math.cos(rad);
            count++;
        }

        int getCount() {
            return count;
        }

        /**
         * @return the mean direction in [0, 360), or NaN if there is none
         */
        double getMeanDeg() {
            if (count == 0 || (Math.abs(sumSin) < 1e-12 && Math.abs(sumCos) < 1e-12)) {
                return Double.NaN;
            }
            final double deg = Math.toDegrees(Math.atan2(sumSin, sumCos));
            return deg < 0 ? deg + 360 : deg;
        }
    }

    /**
     * Accumulates positions on the unit sphere, so that the mean is correct across the antimeridian.
     */
    static final class PositionMean {
        private double x, y, z;
        private int count;

        void add(final double latDeg, final double lonDeg) {
            if (!isValidLatLon(latDeg, lonDeg)) {
                return;
            }
            final double lat = Math.toRadians(latDeg);
            final double lon = Math.toRadians(lonDeg);
            x += Math.cos(lat) * Math.cos(lon);
            y += Math.cos(lat) * Math.sin(lon);
            z += Math.sin(lat);
            count++;
        }

        int getCount() {
            return count;
        }

        double getLatDeg() {
            return Math.toDegrees(Math.atan2(z, Math.sqrt(x * x + y * y)));
        }

        double getLonDeg() {
            return Math.toDegrees(Math.atan2(y, x));
        }
    }

    /**
     * Wind and wave directions in OCN products follow the meteorological convention: the direction the wind or
     * the waves come from, clockwise from north. Arrows are drawn pointing where they go to.
     */
    static double toArrowAzimuthDeg(final double meteorologicalDirectionDeg) {
        return (meteorologicalDirectionDeg + 180.0) % 360.0;
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

    /**
     * Draws one vignette per wave mode imagette. Like the grid surfaces, the colours use the legend limits if the
     * legend exists, and the vignettes are registered with the product so that the legend's Update recolours them.
     */
    protected void createWVColorSurfaceWithGradient(Product product,
                                                  double[] latValues,
                                                  double[] lonValues,
                                                  double[] values,
                                                  double minValue, double maxValue, boolean whiteZero,
                                                  List<Renderable> renderableList,
                                                  ProductRenderablesInfo prodRenderInfo,
                                                  String comp) {
        //SystemUtils.LOG.info(":: createWVColorSurfaceWithGradient ");
        if (latValues.length != values.length || lonValues.length != values.length) {
            throw new IllegalArgumentException("Imagettes need one position per value, got " + latValues.length +
                                                       ", " + lonValues.length + " and " + values.length);
        }
        if (theColorBarLegendHash.get(comp) != null) {
            minValue = theColorBarLegendHash.get(comp).getMinValue();
            maxValue = theColorBarLegendHash.get(comp).getMaxValue();
        }

        final ShapeAttributes dpAttrs = new BasicShapeAttributes();
        dpAttrs.setOutlineMaterial(Material.WHITE);
        dpAttrs.setOutlineWidth(2d);
        // we cannot make it a scalar object because it has to be final and then we won't be able to assign to it
        // we'll store it as a first object of a final array
        //final List<Object> ctgSurfaceList = new ArrayList<Object>();

        for (int ind = 0; ind < values.length; ind++) {
            if (!isValidLatLon(latValues[ind], lonValues[ind]) || Double.isNaN(values[ind])) {
                continue;
            }
            final int finalInd = ind;
            final double lat = latValues[ind];
            final List<Position> polygonPositions = new ArrayList<>();
            // 10 km half side; a degree of longitude shrinks with the cosine of the latitude
            double vignette_half_side_deg = (180 / Math.PI) * 10000 / GLOBE_RADIUS;
            double vignette_half_side_lon_deg = vignette_half_side_deg /
                    Math.max(0.01, Math.cos(Math.toRadians(lat)));
            // keep the vignette on one side of the antimeridian: a sector cannot cross it
            final double lon = WWMath.clamp(normalizeLon(lonValues[ind]),
                                            -180 + vignette_half_side_lon_deg, 180 - vignette_half_side_lon_deg);

            polygonPositions.add(new Position(Angle.fromDegreesLatitude(lat - vignette_half_side_deg), Angle.fromDegreesLongitude(lon - vignette_half_side_lon_deg), 10.0));
            polygonPositions.add(new Position(Angle.fromDegreesLatitude(lat - vignette_half_side_deg), Angle.fromDegreesLongitude(lon + vignette_half_side_lon_deg), 10.0));
            polygonPositions.add(new Position(Angle.fromDegreesLatitude(lat + vignette_half_side_deg), Angle.fromDegreesLongitude(lon + vignette_half_side_lon_deg), 10.0));
            polygonPositions.add(new Position(Angle.fromDegreesLatitude(lat + vignette_half_side_deg), Angle.fromDegreesLongitude(lon - vignette_half_side_lon_deg), 10.0));
            polygonPositions.add(new Position(Angle.fromDegreesLatitude(lat - vignette_half_side_deg), Angle.fromDegreesLongitude(lon - vignette_half_side_lon_deg), 10.0));

            Path p = theWWLayer.createPath(polygonPositions, BaseLayer.WHITE_MATERIAL, BaseLayer.RED_MATERIAL);

            theWWLayer.addRenderable(p);
            if (renderableList != null) {
                renderableList.add(p);
            }

            String info = "";
            if (comp.equalsIgnoreCase("osw")) {
                info = "Significant Wave Height: " + values[ind] + "<br/>";
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
                    // picking reports the surface itself, the delegate owner of its clamp-to-ground surface;
                    // the info is put again on every render, as the layer drops it when a product is removed
                    theObjectInfoHash.put(this, finalInfo);
                    theSurfaceProductHash.put(this, product);
                    theSurfaceSequenceHash.put(this, finalInd);
                }
            };

            analyticSurface.setSector(Sector.fromDegrees(
                    Math.max(-90, lat - vignette_half_side_deg),
                    Math.min(90, lat + vignette_half_side_deg),
                    lon - vignette_half_side_lon_deg,
                    lon + vignette_half_side_lon_deg));
            analyticSurface.setAltitudeMode(WorldWind.CLAMP_TO_GROUND);
            // one by one square doesn't seem to work, so we'll just use the next smallest
            // possible square and repeat the same value 4 times
            analyticSurface.setDimensions(2, 2);

            final double[] vignetteValues = {values[ind], values[ind], values[ind], values[ind]};
            analyticSurface.setValues(ColorGradient.createColorGradientAttributes(vignetteValues, 2, minValue, maxValue,
                    ColorGradient.getMinHue(comp), ColorGradient.getMaxHue(comp), whiteZero));
            if (prodRenderInfo != null) {
                final BufferWrapper valueBuffer = new BufferFactory.DoubleBufferFactory().newBuffer(vignetteValues.length);
                valueBuffer.putDouble(0, vignetteValues, 0, vignetteValues.length);
                prodRenderInfo.setAnalyticSurfaceAndBuffer(analyticSurface, valueBuffer, comp);
            }

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
        if (latValues.length != waveLengthValues.length || lonValues.length != waveLengthValues.length ||
                waveDirValues.length != waveLengthValues.length) {
            throw new IllegalArgumentException("Wave arrows need one position and direction per wavelength, got " +
                                                       latValues.length + ", " + lonValues.length + ", " +
                                                       waveDirValues.length + " and " + waveLengthValues.length);
        }

        final ShapeAttributes dpAttrs = new BasicShapeAttributes();
        dpAttrs.setOutlineMaterial(Material.WHITE);
        dpAttrs.setOutlineWidth(2d);

        for (int ind = 0; ind < waveLengthValues.length; ind++) {
            //int ind = row*width + col;
            double arrowLength_deg = waveLengthValues[ind] / 4000;
            if (!(arrowLength_deg > 0) || Double.isNaN(waveDirValues[ind]) ||
                    !isValidLatLon(latValues[ind], lonValues[ind]))
                continue;

            double arrowHeadLength = Angle.fromDegrees(arrowLength_deg).radians * GLOBE_RADIUS / 3;

            final Position startPos = new Position(Angle.fromDegreesLatitude(latValues[ind]), Angle.fromDegreesLongitude(normalizeLon(lonValues[ind])), 10.0);
            final Position endPos = new Position(LatLon.greatCircleEndPosition(startPos, Angle.fromDegrees(toArrowAzimuthDeg(waveDirValues[ind])), Angle.fromDegrees(arrowLength_deg)), 10.0);

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

    /**
     * @return the first band whose name contains all search strings, or null if there is none
     */
    protected Band findFirstBand(final Product product, final String ... searchStrings) {
        final Band[] bands = findBands(product, searchStrings);
        return bands.length > 0 ? bands[0] : null;
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
