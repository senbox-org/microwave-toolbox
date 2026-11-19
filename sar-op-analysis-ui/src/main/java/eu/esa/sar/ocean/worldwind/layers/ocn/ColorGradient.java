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
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.worldwind.ProductRenderablesInfo;
import org.esa.snap.worldwind.layers.WWLayer;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

public class ColorGradient {

    public static final double HUE_BLUE = 240d / 360d;
    public static final double HUE_RED = 0d / 360d;
    public static final double HUE_MAX_RED = 1.0;

    /**
     * @return true for the radial velocity, which is drawn white at zero
     */
    public static boolean isWhiteZero(final String comp) {
        return comp.equalsIgnoreCase("rvl");
    }

    /**
     * Hue of the minimum value. All components use a blue (minimum) to red (maximum) scale: the full hue circle
     * from HUE_RED to HUE_MAX_RED gives both ends the same red, so that calm sea and a gale, or opposite radial
     * velocities, could not be told apart.
     */
    public static double getMinHue(final String comp) {
        return HUE_BLUE;
    }

    public static double getMaxHue(final String comp) {
        return HUE_RED;
    }

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
                final double[] values = new double[analyticSurfaceValueBuffer.length()];
                analyticSurfaceValueBuffer.getDouble(0, values, 0, values.length);

                analyticSurface.setValues(createColorGradientAttributes(values, analyticSurface.getDimensions()[0],
                        minValue, maxValue, getMinHue(comp), getMaxHue(comp), whiteZero));
            }
        }
    }

    /**
     * Colours of all grid points. No-data points (NaN) are fully transparent, but take the colour of the nearest
     * valid point in the same row, or for rows without any valid point the colours of the nearest row that has
     * one, as sanitizeGrid does with the positions: the colours are interpolated across the grid triangles, and a
     * transparent black vertex, or one with the colour of the far end of the previous row, would draw a rim of a
     * wrong colour along every coastline and swath edge.
     *
     * @param width the number of grid points per row
     */
    static List<AnalyticSurface.GridPointAttributes> createColorGradientAttributes(final double[] values, final int width,
                                                                                double minValue, double maxValue,
                                                                                double minHue, double maxHue,
                                                                                boolean whiteZero) {
        final int n = values.length;
        final int height = width > 0 ? (n + width - 1) / width : 0;
        final Color[] colors = new Color[n];
        final boolean[] rowHasValid = new boolean[height];
        boolean anyValid = false;

        for (int row = 0; row < height; row++) {
            final int start = row * width;
            final int end = Math.min(n, start + width);
            int prev = -1;
            final int[] prevValid = new int[end - start];
            for (int i = start; i < end; i++) {
                if (!Double.isNaN(values[i])) {
                    colors[i] = createColorGradientAttributes(values[i], minValue, maxValue, minHue, maxHue, whiteZero).getColor();
                    prev = i;
                }
                prevValid[i - start] = prev;
            }
            if (prev < 0) {
                continue;
            }
            rowHasValid[row] = true;
            anyValid = true;
            int next = -1;
            for (int i = end - 1; i >= start; i--) {
                if (prevValid[i - start] == i) {
                    next = i;
                    continue;
                }
                int source = prevValid[i - start];
                if (source < 0 || (next >= 0 && next - i < i - source)) {
                    source = next;
                }
                colors[i] = transparent(colors[source]);
            }
        }

        for (int row = 0; row < height; row++) {
            if (rowHasValid[row]) {
                continue;
            }
            int source = -1;
            for (int d = 1; anyValid && source < 0; d++) {
                if (row - d >= 0 && rowHasValid[row - d]) {
                    source = row - d;
                } else if (row + d < height && rowHasValid[row + d]) {
                    source = row + d;
                }
            }
            for (int col = 0; col < width && row * width + col < n; col++) {
                final int sourceIndex = source * width + col;
                colors[row * width + col] = source >= 0 && sourceIndex < n ?
                        transparent(colors[sourceIndex]) : new Color(255, 255, 255, 0);
            }
        }

        final List<AnalyticSurface.GridPointAttributes> attributes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            attributes.add(AnalyticSurface.createGridPointAttributes(values[i], colors[i]));
        }
        return attributes;
    }

    private static Color transparent(final Color c) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), 0);
    }

    public static AnalyticSurface.GridPointAttributes createColorGradientAttributes(final double value,
                                                                                     double minValue, double maxValue,
                                                                                     double minHue, double maxHue,
                                                                                     boolean whiteZero) {
        if (Double.isNaN(value)) {
            // no data (fill value, land): fully transparent
            return AnalyticSurface.createGridPointAttributes(value, new Color(255, 255, 255, 0));
        }
        // values outside the range are clamped to the end colours instead of being hidden, so that e.g. strong
        // negative radial velocities stay visible
        final double hueFactor = (maxValue > minValue) ?
                WWMath.clamp((value - minValue) / (maxValue - minValue), 0.0, 1.0) : 0.5;

        //double hue = WWMath.mixSmooth(hueFactor, minHue, maxHue);
        final double hue = WWMath.mix(hueFactor, minHue, maxHue);
        double sat = 1.0;
        if (whiteZero) {
            // white at zero whatever the range, saturated towards the larger magnitude
            final double maxMagnitude = Math.max(Math.abs(minValue), Math.abs(maxValue));
            sat = maxMagnitude > 0 ? WWMath.clamp(Math.abs(value) / maxMagnitude, 0.0, 1.0) : 0.0;
        }
        final Color color = Color.getHSBColor((float) hue, (float) sat, 1f);
        final Color rgbaColor = new Color(color.getRed(), color.getGreen(), color.getBlue(), 255);

        return AnalyticSurface.createGridPointAttributes(value, rgbaColor);
    }

    /**
     * One analytic surface of a grid: its sector and the longitudes of its vertices.
     */
    static final class SurfacePart {
        final Sector sector;
        final double[] lonValues;

        SurfacePart(final Sector sector, final double[] lonValues) {
            this.sector = sector;
            this.lonValues = lonValues;
        }
    }

    /**
     * @return true if horizontally or vertically adjacent grid points lie on both sides of the antimeridian
     */
    static boolean crossesAntimeridian(final double[] lonValues, final int width, final int height) {
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                final int i = row * width + col;
                if ((col + 1 < width && Math.abs(lonValues[i] - lonValues[i + 1]) > 180) ||
                        (row + 1 < height && Math.abs(lonValues[i] - lonValues[i + width]) > 180)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Splits a grid into the surfaces to draw. The sector of a surface is used for culling and selects the surface
     * tiles it is drawn into, and the vertices are drawn as longitude offsets from its centre, so a swath crossing
     * the antimeridian is drawn as two copies with continuous longitudes: one east of the antimeridian, clipped at
     * 180, and one shifted by -360 degrees, clipped at -180. With wrapped longitudes, neighbouring points at 179.9
     * and -179.9 would be joined by triangles spanning the whole globe.
     *
     * @param latValues valid latitudes, see {@link #sanitizeGrid}
     * @param lonValues valid longitudes in [-180, 180], see {@link #sanitizeGrid}
     */
    static List<SurfacePart> computeSurfaceParts(final double[] latValues, final double[] lonValues,
                                                 final int width, final int height) {
        final int numPoints = width * height;
        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
        for (int i = 0; i < numPoints; i++) {
            minLat = Math.min(minLat, latValues[i]);
            maxLat = Math.max(maxLat, latValues[i]);
            minLon = Math.min(minLon, lonValues[i]);
            maxLon = Math.max(maxLon, lonValues[i]);
        }

        final List<SurfacePart> parts = new ArrayList<>(2);
        if (!crossesAntimeridian(lonValues, width, height)) {
            parts.add(new SurfacePart(Sector.fromDegrees(minLat, maxLat, minLon, maxLon), lonValues));
            return parts;
        }

        final double[] eastLon = new double[numPoints];
        final double[] westLon = new double[numPoints];
        double minEastLon = Double.MAX_VALUE, maxEastLon = -Double.MAX_VALUE;
        for (int i = 0; i < numPoints; i++) {
            eastLon[i] = lonValues[i] < 0 ? lonValues[i] + 360 : lonValues[i];
            westLon[i] = eastLon[i] - 360;
            minEastLon = Math.min(minEastLon, eastLon[i]);
            maxEastLon = Math.max(maxEastLon, eastLon[i]);
        }
        if (maxEastLon - minEastLon > 180) {
            // the grid also wraps around in the other direction (around a pole): it cannot be unwrapped
            SystemUtils.LOG.warning("OCN grid covers all longitudes and cannot be drawn correctly");
            parts.add(new SurfacePart(Sector.fromDegrees(minLat, maxLat, -180, 180), lonValues));
            return parts;
        }
        // a grid that only touches the antimeridian has nothing to draw on one side
        if (minEastLon < 180) {
            parts.add(new SurfacePart(Sector.fromDegrees(minLat, maxLat, minEastLon, 180), eastLon));
        }
        if (maxEastLon > 180) {
            parts.add(new SurfacePart(Sector.fromDegrees(minLat, maxLat, -180, maxEastLon - 360), westLon));
        }
        return parts;
    }

    /**
     * Returns copies of the grid positions in which invalid positions (fill values) are replaced by the position
     * of the nearest valid point in the same row, or, for rows without any valid point, by the positions of the
     * nearest row that has one. The grid triangles of such points collapse onto the edge of the valid data instead
     * of stretching to latitude -999 or across the swath. The values at those points are set to NaN so that they
     * are drawn transparent. Longitudes are normalised to [-180, 180].
     *
     * @return the latitudes, longitudes and values, or null if there is no valid position
     */
    static double[][] sanitizeGrid(final double[] latValues, final double[] lonValues, final double[] values,
                                   final int width, final int height) {
        final double[] lat = latValues.clone();
        final double[] lon = lonValues.clone();
        final double[] val = values.clone();
        final boolean[] rowHasValid = new boolean[height];
        boolean anyValid = false;

        for (int row = 0; row < height; row++) {
            final int start = row * width;
            int prev = -1;
            final int[] prevValid = new int[width];
            for (int col = 0; col < width; col++) {
                final int i = start + col;
                if (OCNComponent.isValidLatLon(lat[i], lon[i])) {
                    lon[i] = OCNComponent.normalizeLon(lon[i]);
                    prev = col;
                }
                prevValid[col] = prev;
            }
            if (prev < 0) {
                continue;
            }
            rowHasValid[row] = true;
            anyValid = true;
            int next = -1;
            for (int col = width - 1; col >= 0; col--) {
                final int i = start + col;
                if (prevValid[col] == col) {
                    next = col;
                    continue;
                }
                int source = prevValid[col];
                if (source < 0 || (next >= 0 && next - col < col - source)) {
                    source = next;
                }
                lat[i] = lat[start + source];
                lon[i] = lon[start + source];
                val[i] = Double.NaN;
            }
        }
        if (!anyValid) {
            return null;
        }

        for (int row = 0; row < height; row++) {
            if (rowHasValid[row]) {
                continue;
            }
            int source = -1;
            for (int d = 1; source < 0; d++) {
                if (row - d >= 0 && rowHasValid[row - d]) {
                    source = row - d;
                } else if (row + d < height && rowHasValid[row + d]) {
                    source = row + d;
                }
            }
            System.arraycopy(lat, source * width, lat, row * width, width);
            System.arraycopy(lon, source * width, lon, row * width, width);
            for (int col = 0; col < width; col++) {
                val[row * width + col] = Double.NaN;
            }
        }
        return new double[][]{lat, lon, val};
    }

    public static void createColorSurface(WWLayer theWWLayer, GeoPos geoPos1, GeoPos geoPos2, double[] inLatValues, double[] inLonValues, double[] inVals,
                                          int width, int height, List<Renderable> renderableList,
                                          ProductRenderablesInfo prodRenderInfo, String comp) {
        final int numPoints = width * height;
        if (width < 0 || height < 0 ||
                inLatValues.length < numPoints || inLonValues.length < numPoints || inVals.length < numPoints) {
            throw new IllegalArgumentException("Grid of " + width + " x " + height + " points needs " + numPoints +
                                                       " positions and values, got " + inLatValues.length + ", " +
                                                       inLonValues.length + " and " + inVals.length);
        }
        if (width < 2 || height < 2) {
            // WorldWind cannot triangulate it, and would silently never draw the surface
            SystemUtils.LOG.warning("OCN " + comp + " grid of " + width + " x " + height +
                                            " points is too small to be drawn");
            return;
        }
        final double[][] grid = sanitizeGrid(inLatValues, inLonValues, inVals, width, height);
        if (grid == null) {
            SystemUtils.LOG.warning("OCN " + comp + " grid of " + width + " x " + height +
                                            " points has no valid position and is not drawn");
            return;
        }
        final double[] latValues = grid[0];
        final double[] vals = grid[2];

        //SystemUtils.LOG.info("createColorSurface " + latValues.length + " " + lonValues.length + " " + vals.length + " " + width + " " + height);

        //BufferWrapper analyticSurfaceValueBuffer = randomGridValues(width, height, minValue, maxValue);
        BufferWrapper analyticSurfaceValueBuffer = (new BufferFactory.DoubleBufferFactory()).newBuffer(numPoints);
        analyticSurfaceValueBuffer.putDouble(0, vals, 0, numPoints);

        //smoothValues(width, height, values, 0.5d);
        //scaleValues(values, values.length, minValue, maxValue);

        //mixValuesOverTime(2000L, firstBuffer, analyticSurfaceValueBuffer, minValue, maxValue, minHue, maxHue, analyticSurface);

        for (SurfacePart part : computeSurfaceParts(latValues, grid[1], width, height)) {
            final AnalyticSurface analyticSurface = createSurface(theWWLayer, latValues, part.lonValues, width, height,
                                                                  part.sector);
            // the copies of a surface share the value buffer
            prodRenderInfo.setAnalyticSurfaceAndBuffer(analyticSurface, analyticSurfaceValueBuffer, comp);
            if (renderableList != null) {
                renderableList.add(analyticSurface);
            }
        }
    }

    private static AnalyticSurface createSurface(final WWLayer theWWLayer, final double[] latValues,
                                                 final double[] lonValues, final int width, final int height,
                                                 final Sector sector) {
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
        // the sector is used for culling and surface tile intersection, so it must bound the whole grid;
        // two diagonal scene corners do not bound a rotated swath and give an inverted sector on one axis
        analyticSurface.setSector(sector);
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
        return analyticSurface;
    }
}
