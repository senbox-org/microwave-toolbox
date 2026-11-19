package eu.esa.sar.ocean.worldwind.layers.ocn;

import eu.esa.sar.ocean.worldwind.layers.Level2ProductLayer;
import gov.nasa.worldwind.geom.Angle;
import gov.nasa.worldwind.geom.LatLon;
import gov.nasa.worldwind.geom.Position;
import gov.nasa.worldwind.geom.Sector;
import gov.nasa.worldwind.render.Renderable;
import gov.nasa.worldwindx.examples.analytics.AnalyticSurface;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataAttribute;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.VirtualBand;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.worldwind.ArrowInfo;
import org.esa.snap.worldwind.ColorBarLegend;
import org.esa.snap.worldwind.ProductRenderablesInfo;
import org.junit.Test;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the OCN wind/wave/radial velocity rendering fixes.
 */
public class OCNRegressionTest {

    private static OWI createOWI() {
        return new OWI(mock(Level2ProductLayer.class), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());
    }

    // 8 x 8 grid = 2 x 2 arrow cells of 4 x 4 samples
    private static final int W = 8, H = 8;

    private static double[] grid(double value) {
        final double[] values = new double[W * H];
        Arrays.fill(values, value);
        return values;
    }

    private static double[] lats(double north, double step) {
        final double[] values = new double[W * H];
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                values[r * W + c] = north - r * step;
            }
        }
        return values;
    }

    private static double[] lons(double west, double step) {
        final double[] values = new double[W * H];
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                double lon = west + c * step;
                values[r * W + c] = lon > 180 ? lon - 360 : lon;
            }
        }
        return values;
    }

    private static Position[] arrowPositions(ArrowInfo arrow) {
        final List<Position> list = new ArrayList<>();
        for (Position p : arrow.theDirectedPath.getPositions()) {
            list.add(p);
        }
        return list.toArray(new Position[0]);
    }

    @Test
    public void testDirectionsAreAveragedOnTheCircle() {
        final double[] dirs = grid(10);
        // half of the first cell blows from 350 degrees, half from 10 degrees: a northerly wind
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 2; c++) {
                dirs[r * W + c] = 350;
            }
        }

        final ArrowInfo[][][] arrows = createOWI().addWindSpeedArrows(lats(50, 0.01), lons(10, 0.01), grid(30),
                                                                       grid(8), dirs, W, H, null);

        final ArrowInfo cell = arrows[0][0][0];
        assertNotNull(cell);
        final double dir = cell.theAvgWindDir;
        // the arithmetic mean was 180, i.e. the opposite direction
        assertTrue("mean direction " + dir, dir < 0.5 || dir > 359.5);
    }

    @Test
    public void testArrowsPointDownwind() {
        // wind from the north (meteorological 0 degrees) blows towards the south
        final ArrowInfo[][][] arrows = createOWI().addWindSpeedArrows(lats(50, 0.01), lons(10, 0.01), grid(30),
                                                                       grid(8), grid(0), W, H, null);

        final Position[] positions = arrowPositions(arrows[0][0][0]);
        assertTrue(positions[1].getLatitude().degrees < positions[0].getLatitude().degrees);
        assertEquals(positions[0].getLongitude().degrees, positions[1].getLongitude().degrees, 1e-6);
    }

    @Test
    public void testArrowPositionsAcrossTheAntimeridian() {
        // the first cell straddles the antimeridian: lon 179.98 .. -179.99
        final ArrowInfo[][][] arrows = createOWI().addWindSpeedArrows(lats(10, 0.01), lons(179.98, 0.01), grid(30),
                                                                       grid(8), grid(90), W, H, null);

        final double lon = arrowPositions(arrows[0][0][0])[0].getLongitude().degrees;
        // a linear mean of the longitudes would give about 45 degrees
        assertTrue("arrow longitude " + lon, Math.abs(lon) > 179.9);
    }

    @Test
    public void testFillValuesAreIgnored() {
        final double[] speed = grid(8);
        final double[] dirs = grid(90);
        final double[] lat = lats(50, 0.01);
        // first cell all fill, second cell half fill
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                speed[r * W + c] = Double.NaN;
            }
            for (int c = 4; c < 6; c++) {
                dirs[r * W + c] = Double.NaN;
                lat[r * W + c] = Double.NaN;
            }
        }

        final ArrowInfo[][][] arrows = createOWI().addWindSpeedArrows(lat, lons(10, 0.01), grid(30), speed, dirs,
                                                                       W, H, null);

        assertNull(arrows[0][0][0]);
        assertNotNull(arrows[0][0][1]);
        assertEquals(8.0, arrows[0][0][1].theAvgWindSpeed, 1e-9);
        assertEquals(90.0, arrows[0][0][1].theAvgWindDir, 1e-9);
        // the coarser level is built from the remaining cells
        assertNotNull(arrows[1][0][0]);
        assertEquals(90.0, arrows[1][0][0].theAvgWindDir, 1e-6);
    }

    @Test
    public void testDirectionMean() {
        final OCNComponent.DirectionMean mean = new OCNComponent.DirectionMean();
        assertTrue(Double.isNaN(mean.getMeanDeg()));
        mean.add(270);
        mean.add(Double.NaN);
        mean.add(90 + 180 + 20);
        assertEquals(280.0, mean.getMeanDeg(), 1e-9);
        assertEquals(2, mean.getCount());
    }

    @Test
    public void testArrowAzimuth() {
        assertEquals(180.0, OCNComponent.toArrowAzimuthDeg(0), 0.0);
        assertEquals(10.0, OCNComponent.toArrowAzimuthDeg(190), 0.0);
    }

    @Test
    public void testSurfaceSectorBoundsARotatedSwath() {
        // ascending right-looking swath: the first pixel is at the south-east corner
        final double[] lat = {10, 10.5, 11, 9.5, 10, 10.5};
        final double[] lon = {20, 19.8, 19.6, 19.5, 19.3, 19.1};

        final List<ColorGradient.SurfacePart> parts = ColorGradient.computeSurfaceParts(lat, lon, 3, 2);

        assertEquals(1, parts.size());
        final Sector sector = parts.get(0).sector;
        assertEquals(9.5, sector.getMinLatitude().degrees, 1e-9);
        assertEquals(11.0, sector.getMaxLatitude().degrees, 1e-9);
        assertEquals(19.1, sector.getMinLongitude().degrees, 1e-9);
        assertEquals(20.0, sector.getMaxLongitude().degrees, 1e-9);
    }

    @Test
    public void testSwathAcrossTheAntimeridianIsSplitWithContinuousLongitudes() {
        final double[] lat = {10, 10, 10, 11, 11, 11};
        final double[] lon = {179.5, 179.9, -179.7, 179.4, 179.8, -179.8};

        final List<ColorGradient.SurfacePart> parts = ColorGradient.computeSurfaceParts(lat, lon, 3, 2);

        // a single surface with wrapped longitudes joins 179.9 and -179.7 by a triangle around the whole globe
        assertEquals(2, parts.size());
        final ColorGradient.SurfacePart east = parts.get(0);
        final ColorGradient.SurfacePart west = parts.get(1);
        assertEquals(179.4, east.sector.getMinLongitude().degrees, 1e-9);
        assertEquals(180.0, east.sector.getMaxLongitude().degrees, 1e-9);
        assertEquals(-180.0, west.sector.getMinLongitude().degrees, 1e-9);
        assertEquals(-179.7, west.sector.getMaxLongitude().degrees, 1e-9);
        for (ColorGradient.SurfacePart part : parts) {
            for (int i = 0; i + 1 < 3; i++) {
                // neighbouring vertices stay neighbours
                assertTrue(Math.abs(part.lonValues[i + 1] - part.lonValues[i]) < 1);
            }
            // the vertices are continuous around the sector of the part
            assertTrue(part.sector.getMinLongitude().degrees - 1 < part.lonValues[0]);
        }
        assertEquals(180.3, east.lonValues[2], 1e-9);
        assertEquals(-180.1, west.lonValues[1], 1e-9);
    }

    @Test
    public void testGridAroundAPoleIsNotUnwrapped() {
        // longitudes all around: no unwrapping gives continuous longitudes
        final double[] lat = {88, 88, 88, 88};
        final double[] lon = {0, 90, 179, -90};

        final List<ColorGradient.SurfacePart> parts = ColorGradient.computeSurfaceParts(lat, lon, 4, 1);

        assertEquals(1, parts.size());
        assertEquals(-180.0, parts.get(0).sector.getMinLongitude().degrees, 1e-9);
        assertEquals(180.0, parts.get(0).sector.getMaxLongitude().degrees, 1e-9);
    }

    @Test
    public void testAntimeridianSurfaceCreatesTwoSurfacesSharingTheValues() {
        final double[] lat = {10, 10, 11, 11};
        final double[] lon = {179.9, -179.9, 179.9, -179.9};
        final List<Renderable> renderables = new ArrayList<>();
        final ProductRenderablesInfo info = new ProductRenderablesInfo();

        ColorGradient.createColorSurface(null, null, null, lat, lon, new double[]{1, 2, 3, 4}, 2, 2, renderables,
                                         info, "owi");

        assertEquals(2, renderables.size());
        assertEquals(2, info.owiAnalyticSurfaces.size());
        assertSame(info.owiAnalyticSurfaceValueBuffers.get(0), info.owiAnalyticSurfaceValueBuffers.get(1));
    }

    @Test
    public void testFillPositionsDoNotBecomeGridVertices() {
        final double[][] grid = ColorGradient.sanitizeGrid(new double[]{10, -999, 11}, new double[]{20, -999, 21},
                                                           new double[]{1, 2, 3}, 3, 1);

        assertEquals(10.0, grid[0][1], 0.0);
        assertEquals(20.0, grid[1][1], 0.0);
        assertTrue(Double.isNaN(grid[2][1]));
        assertEquals(3.0, grid[2][2], 0.0);
        assertNull(ColorGradient.sanitizeGrid(new double[]{-999}, new double[]{-999}, new double[]{1}, 1, 1));
    }

    @Test
    public void testLeadingFillCollapsesWithinItsRow() {
        // row 1 starts with fill (slanted swath edge); it used to take the last point of row 0, i.e. the far edge
        final double[] lat = {10, 10, 10, 10, Double.NaN, Double.NaN, 11, 11, Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        final double[] lon = {20, 21, 22, 23, Double.NaN, Double.NaN, 22, 23, Double.NaN, Double.NaN, Double.NaN, Double.NaN};

        final double[][] grid = ColorGradient.sanitizeGrid(lat, lon, new double[12], 4, 3);

        assertEquals(22.0, grid[1][4], 0.0);
        assertEquals(22.0, grid[1][5], 0.0);
        assertEquals(11.0, grid[0][4], 0.0);
        // a row without any valid point takes the positions of the nearest row
        assertEquals(11.0, grid[0][8], 0.0);
        assertEquals(23.0, grid[1][11], 0.0);
        assertTrue(Double.isNaN(grid[2][8]));
        assertEquals(0.0, grid[2][6], 0.0);
    }

    @Test
    public void testSanitizeNormalisesLongitudes() {
        final double[][] grid = ColorGradient.sanitizeGrid(new double[]{10, 10}, new double[]{190, 170},
                                                           new double[]{1, 2}, 2, 1);
        assertEquals(-170.0, grid[1][0], 0.0);
        assertEquals(170.0, grid[1][1], 0.0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGridSizeMismatchIsRejectedUpFront() {
        ColorGradient.createColorSurface(null, null, null, new double[]{0, 1}, new double[]{0, 1},
                                         new double[]{0, 1}, 2, 2, null, null, "owi");
    }

    @Test
    public void testColourMapping() {
        final AnalyticSurface.GridPointAttributes noData =
                ColorGradient.createColorGradientAttributes(Double.NaN, -6, 6, ColorGradient.HUE_RED, ColorGradient.HUE_MAX_RED, true);
        assertEquals(0, noData.getColor().getAlpha());

        // values below the minimum used to be fully transparent; they now get the colour of the minimum
        final AnalyticSurface.GridPointAttributes strongNegative =
                ColorGradient.createColorGradientAttributes(-8, -6, 6, ColorGradient.HUE_BLUE, ColorGradient.HUE_RED, true);
        assertEquals(255, strongNegative.getColor().getAlpha());
        assertEquals(ColorGradient.createColorGradientAttributes(-6, -6, 6, ColorGradient.HUE_BLUE, ColorGradient.HUE_RED, true)
                             .getColor().getRGB(), strongNegative.getColor().getRGB());

        // zero is white for radial velocities, also for an asymmetric range
        final Color zero = ColorGradient.createColorGradientAttributes(0, -6, 5, ColorGradient.HUE_RED,
                                                                        ColorGradient.HUE_MAX_RED, true).getColor();
        assertEquals(Color.WHITE.getRGB(), zero.getRGB());

        // degenerate range does not produce NaN colours
        final Color flat = ColorGradient.createColorGradientAttributes(3, 3, 3, ColorGradient.HUE_RED,
                                                                        ColorGradient.HUE_MAX_RED, false).getColor();
        assertEquals(255, flat.getAlpha());
    }

    @Test
    public void testRadialVelocitySignsAreDistinguishable() {
        assertTrue(ColorGradient.isWhiteZero("rvl"));
        assertFalse(ColorGradient.isWhiteZero("owi"));
        final Color negative = ColorGradient.createColorGradientAttributes(-6, -6, 6, ColorGradient.getMinHue("rvl"),
                                                                            ColorGradient.getMaxHue("rvl"), true).getColor();
        final Color positive = ColorGradient.createColorGradientAttributes(6, -6, 6, ColorGradient.getMinHue("rvl"),
                                                                            ColorGradient.getMaxHue("rvl"), true).getColor();
        // with the full hue circle both ends were the same red
        assertEquals(Color.BLUE.getRGB(), negative.getRGB());
        assertEquals(Color.RED.getRGB(), positive.getRGB());
    }

    @Test
    public void testNoDataPointsTakeTheNeighbourColourWhenTransparent() {
        final List<AnalyticSurface.GridPointAttributes> attributes = ColorGradient.createColorGradientAttributes(
                new double[]{Double.NaN, 10, Double.NaN, Double.NaN, Double.NaN, 0}, 6, 0, 10,
                ColorGradient.HUE_BLUE, ColorGradient.HUE_RED, false);

        final Color atTen = attributes.get(1).getColor();
        final Color atZero = attributes.get(5).getColor();
        for (int i : new int[]{0, 2, 3, 4}) {
            assertEquals(0, attributes.get(i).getColor().getAlpha());
        }
        // a transparent black vertex darkens the interpolated colours along the edge of the data
        assertEquals(atTen.getRed(), attributes.get(0).getColor().getRed());
        assertEquals(atTen.getGreen(), attributes.get(2).getColor().getGreen());
        assertEquals(atZero.getBlue(), attributes.get(4).getColor().getBlue());
        assertEquals(255, atTen.getAlpha());
    }

    @Test
    public void testNoDataColourComesFromTheSameRow() {
        final double nan = Double.NaN;
        // row 0 runs from 0 (blue) to 10 (red); row 1 starts with no data, row 2 has no data at all
        final List<AnalyticSurface.GridPointAttributes> attributes = ColorGradient.createColorGradientAttributes(
                new double[]{0, 5, 10,
                             nan, 0, 0,
                             nan, nan, nan}, 3, 0, 10, ColorGradient.HUE_BLUE, ColorGradient.HUE_RED, false);

        // in scan order the previous valid point is the red far end of row 0
        final Color leading = attributes.get(3).getColor();
        assertEquals(0, leading.getAlpha());
        assertEquals(Color.BLUE.getRGB() & 0xFFFFFF, leading.getRGB() & 0xFFFFFF);
        // an empty row takes the colours of the nearest row
        for (int col = 0; col < 3; col++) {
            final Color c = attributes.get(6 + col).getColor();
            assertEquals(0, c.getAlpha());
            assertEquals(attributes.get(3 + col).getColor().getRGB() & 0xFFFFFF, c.getRGB() & 0xFFFFFF);
        }
    }

    @Test
    public void testWindAndWaveScaleEndsDiffer() {
        for (String comp : new String[]{"owi", "osw", "rvl"}) {
            final Color min = ColorGradient.createColorGradientAttributes(0, 0, 10, ColorGradient.getMinHue(comp),
                                                                           ColorGradient.getMaxHue(comp), false).getColor();
            final Color max = ColorGradient.createColorGradientAttributes(10, 0, 10, ColorGradient.getMinHue(comp),
                                                                           ColorGradient.getMaxHue(comp), false).getColor();
            // with the full hue circle 0 m/s and 10 m/s and above were the same red
            assertNotEquals(comp, min.getRGB(), max.getRGB());
        }
    }

    @Test
    public void testSingleRowGridIsNotDrawn() {
        final List<Renderable> renderables = new ArrayList<>();
        final ProductRenderablesInfo info = new ProductRenderablesInfo();

        // WorldWind cannot triangulate it and would fail silently while rendering
        ColorGradient.createColorSurface(null, null, null, new double[]{10, 10, 10}, new double[]{20, 21, 22},
                                         new double[]{1, 2, 3}, 3, 1, renderables, info, "owi");

        assertTrue(renderables.isEmpty());
        assertTrue(info.owiAnalyticSurfaces.isEmpty());
    }

    @Test
    public void testGridTouchingTheAntimeridianHasNoEmptyPart() {
        final List<ColorGradient.SurfacePart> parts = ColorGradient.computeSurfaceParts(
                new double[]{10, 10, 11, 11}, new double[]{179.5, -180, 179.5, -180}, 2, 2);

        assertEquals(1, parts.size());
        assertEquals(179.5, parts.get(0).sector.getMinLongitude().degrees, 1e-9);
        assertEquals(180.0, parts.get(0).sector.getMaxLongitude().degrees, 1e-9);
    }

    @Test
    public void testImagetteVignettesUseTheLegendRange() {
        final ColorBarLegend legend = mock(ColorBarLegend.class);
        when(legend.getMinValue()).thenReturn(0.0);
        when(legend.getMaxValue()).thenReturn(20.0);
        final Map<String, ColorBarLegend> legends = new HashMap<>();
        legends.put("owi", legend);
        final OWI owi = new OWI(mock(Level2ProductLayer.class), legends, new HashMap<>(), new HashMap<>(), new HashMap<>());
        final ProductRenderablesInfo info = new ProductRenderablesInfo();

        owi.createWVColorSurfaceWithGradient(mock(Product.class), new double[]{10}, new double[]{20}, new double[]{10},
                                             0, 10, false, new ArrayList<>(), info, "owi");

        // 10 m/s is the middle of the legend range of 0..20, not the end of the default 0..10
        final Color c = info.owiAnalyticSurfaces.get(0).getValues().iterator().next().getColor();
        assertEquals(ColorGradient.createColorGradientAttributes(10, 0, 20, ColorGradient.HUE_BLUE, ColorGradient.HUE_RED,
                                                                 false).getColor().getRGB(), c.getRGB());
        assertNotEquals(Color.RED.getRGB(), c.getRGB());
    }

    @Test
    public void testNetCDFDefaultFillIsNaN() {
        final OSW osw = new OSW(mock(Level2ProductLayer.class), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());
        // unwritten WV annotation values hold the netCDF default fill and have no _FillValue attribute
        assertTrue(Double.isNaN(osw.getData(variable("owiWindSpeed", 9.969209968386869E36))));
    }

    @Test(expected = IOException.class)
    public void testRadialVelocityBandsOfDifferentSizesAreRejected() throws Exception {
        final Product product = new Product("p", "OCN", 10, 10);
        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(product.getMetadataRoot());
        absRoot.setAttributeString(AbstractMetadata.ACQUISITION_MODE, "IW");
        product.addBand(new Band("rvlLon_Swath1_IMG001_VV", ProductData.TYPE_FLOAT32, 10, 10));
        product.addBand(new Band("rvlLat_Swath1_IMG001_VV", ProductData.TYPE_FLOAT32, 10, 10));
        product.addBand(new Band("rvlRadVel_Swath1_IMG001_VV", ProductData.TYPE_FLOAT32, 5, 5));

        new RVL(mock(Level2ProductLayer.class), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>())
                .addProduct(product, new ProductRenderablesInfo());
    }

    @Test
    public void testRecolouringKeepsNoDataTransparent() {
        final ProductRenderablesInfo info = new ProductRenderablesInfo();
        ColorGradient.createColorSurface(null, null, null, new double[]{10, 10, 11, 11}, new double[]{20, 21, 20, 21},
                                         new double[]{1, Double.NaN, 3, 4}, 2, 2, null, info, "rvl");

        ColorGradient.createColorGradient(-6, 6, true, info, "rvl");

        final List<AnalyticSurface.GridPointAttributes> attributes = new ArrayList<>();
        for (AnalyticSurface.GridPointAttributes a : info.rvlAnalyticSurfaces.get(0).getValues()) {
            attributes.add(a);
        }
        assertEquals(4, attributes.size());
        assertEquals(0, attributes.get(1).getColor().getAlpha());
        assertEquals(255, attributes.get(0).getColor().getAlpha());
    }

    @Test
    public void testRadialVelocityExtentUsesAllRowsAndColumns() {
        final double nan = Double.NaN;
        // 4 x 3 grid: row 0 is shorter than row 1 (slanted edge), the last row and column are padding
        final double[] lat = {1, 1, nan, nan,
                              1, 1, 1, nan,
                              nan, nan, nan, nan};
        final double[] lon = lat.clone();

        final int[] extent = RVL.getValidExtent(lat, lon, 4, 3);

        // trimming from the first row only gave a width of 2 and dropped the third column of row 1
        assertEquals(3, extent[0]);
        assertEquals(2, extent[1]);
        assertArrayEquals(new double[]{1, 1, nan, 1, 1, 1}, RVL.crop(lat, 4, 3, 2), 0.0);
        assertArrayEquals(new int[]{0, 0}, RVL.getValidExtent(new double[]{nan}, new double[]{nan}, 1, 1));
    }

    @Test
    public void testGridSpacingIsTakenFromNeighbours() {
        // descending swath at 75 N, heading 213 degrees: the corners have almost the same latitude
        final int w = 20, h = 20;
        final double[] lat = new double[w * h];
        final double[] lon = new double[w * h];
        final LatLon origin = LatLon.fromDegrees(75, 10);
        final Angle step = Angle.fromDegrees(0.009);
        for (int r = 0; r < h; r++) {
            final LatLon rowStart = LatLon.greatCircleEndPosition(origin, Angle.fromDegrees(213), step.multiply(r));
            for (int c = 0; c < w; c++) {
                final LatLon p = LatLon.greatCircleEndPosition(rowStart, Angle.fromDegrees(213 - 90), step.multiply(c));
                lat[r * w + c] = p.getLatitude().degrees;
                lon[r * w + c] = p.getLongitude().degrees;
            }
        }

        assertEquals(0.009, OWI.computeGridSpacingDeg(lat, lon, w, h), 1e-4);
        assertTrue(Double.isNaN(OWI.computeGridSpacingDeg(grid(Double.NaN), grid(Double.NaN), W, H)));

        // fill values at the corners do not matter
        lat[0] = lat[w * h - 1] = Double.NaN;
        final ArrowInfo[][][] arrows = createOWI().addWindSpeedArrows(lat, lon, grid400(30), grid400(8), grid400(0),
                                                                       w, h, null);
        assertEquals(0.009 * 4, arrows[0][1][1].theArrowLength, 1e-4);
    }

    private static double[] grid400(double value) {
        final double[] values = new double[400];
        Arrays.fill(values, value);
        return values;
    }

    @Test
    public void testCoarseLevelsCoverPartialCellsAtTheGridEdge() {
        // 20 x 20 samples = 5 x 5 cells of 4: the fifth cell row and column used to be dropped at coarser levels
        final int n = 20;
        final double[] lat = new double[n * n];
        final double[] lon = new double[n * n];
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                lat[r * n + c] = 50 - r * 0.01;
                lon[r * n + c] = 10 + c * 0.01;
            }
        }
        final double[] inc = grid400(30);
        inc[0] = Double.NaN;

        final ArrowInfo[][][] arrows = createOWI().addWindSpeedArrows(lat, lon, inc, grid400(8), grid400(90),
                                                                       n, n, null);

        assertNotNull(arrows[1][2][2]);
        assertNotNull(arrows[2][1][1]);
        assertNotNull(arrows[4][0][0]);
        // a NaN incidence angle no longer makes the cell average NaN
        assertEquals(30.0, arrows[0][0][0].theAvgIncAngle, 1e-9);
        assertEquals(30.0, arrows[4][0][0].theAvgIncAngle, 1e-9);
    }

    @Test
    public void testNoDataIsReplacedOnlyWhenUsed() {
        final Band band = new Band("b", ProductData.TYPE_FLOAT32, 2, 2);
        band.setNoDataValue(-999);
        final double[] values = {-999, 0, 5, -999};

        band.setNoDataValueUsed(false);
        OCNComponent.replaceNoData(values, band);
        assertEquals(-999.0, values[0], 0.0);

        band.setNoDataValueUsed(true);
        OCNComponent.replaceNoData(values, band);
        assertTrue(Double.isNaN(values[0]));
        assertTrue(Double.isNaN(values[3]));
        assertEquals(0.0, values[1], 0.0);
        assertEquals(5.0, values[2], 0.0);

        // the pixels are read as geophysical values
        final Band scaled = new Band("s", ProductData.TYPE_INT16, 2, 1);
        scaled.setScalingFactor(0.5);
        scaled.setNoDataValue(-999);
        scaled.setNoDataValueUsed(true);
        final double[] scaledValues = {-499.5, -999};
        OCNComponent.replaceNoData(scaledValues, scaled);
        assertTrue(Double.isNaN(scaledValues[0]));
        assertEquals(-999.0, scaledValues[1], 0.0);
    }

    @Test
    public void testReadValuesReplacesFillValues() throws Exception {
        final Product product = new Product("p", "OCN", 2, 2);
        final VirtualBand band = new VirtualBand("owiWindSpeed", ProductData.TYPE_FLOAT32, 2, 2, "X < 1 ? -999 : X");
        band.setNoDataValue(-999);
        band.setNoDataValueUsed(true);
        product.addBand(band);

        final double[] values = OCNComponent.readValues(band);

        assertTrue(Double.isNaN(values[0]));
        assertTrue(Double.isNaN(values[2]));
        assertEquals(1.5, values[1], 1e-6);
    }

    private static MetadataElement variable(String name, double... values) {
        final MetadataElement element = new MetadataElement(name);
        final MetadataElement valuesElem = new MetadataElement("Values");
        valuesElem.addAttribute(new MetadataAttribute("data", ProductData.createInstance(values), true));
        element.addElement(valuesElem);
        return element;
    }

    @Test
    public void testImagetteFillValuesAreNaN() {
        final OSW osw = new OSW(mock(Level2ProductLayer.class), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());

        assertEquals(2.5, osw.getData(variable("oswHs", 2.5)), 0.0);
        // invalid imagettes (sea ice, land) carry -999; they used to be drawn as opaque vignettes
        assertTrue(Double.isNaN(osw.getData(variable("oswHs", -999))));
        final MetadataElement withFill = variable("oswHs", 9.96921e36);
        withFill.addAttribute(new MetadataAttribute("_FillValue", ProductData.createInstance(new double[]{9.96921e36}), true));
        assertTrue(Double.isNaN(osw.getData(withFill)));
        assertTrue(Double.isNaN(osw.getData(variable("oswHs", 1, 2), 2)));
    }

    @Test
    public void testDominantSwellPartition() {
        final OSW osw = new OSW(mock(Level2ProductLayer.class), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());

        assertEquals(2, osw.getDominantPartition(variable("oswHs", 0.5, -999, 3.0, 1.0)));
        assertEquals(0, osw.getDominantPartition(variable("oswHs", -999, -999)));
        assertEquals(0, osw.getDominantPartition(null));
    }

    @Test
    public void testImagetteVignettesFollowTheLegendAndAreRecoloured() {
        final Map<String, ColorBarLegend> legends = new HashMap<>();
        final RVL rvl = new RVL(mock(Level2ProductLayer.class), legends, new HashMap<>(), new HashMap<>(), new HashMap<>());
        final ProductRenderablesInfo info = new ProductRenderablesInfo();
        final double[] radVel = {-3, Double.NaN, 6};

        rvl.createWVColorSurfaceWithGradient(mock(Product.class), new double[]{10, 11, 12}, new double[]{20, 21, 179.99},
                                             radVel, -6, 6, true, new ArrayList<>(), info, "rvl");

        // registered, so that the legend's Update recolours them; the NaN imagette is skipped
        assertEquals(2, info.rvlAnalyticSurfaces.size());
        final Color negative = info.rvlAnalyticSurfaces.get(0).getValues().iterator().next().getColor();
        // negative velocities used to be drawn on a 0..10 scale, i.e. clamped to the red of 0
        assertTrue(negative.getBlue() > negative.getRed());
        assertNotEquals(Color.BLUE.getRGB(), negative.getRGB());

        // the vignette next to the antimeridian does not cross it
        assertTrue(info.rvlAnalyticSurfaces.get(1).getSector().getMaxLongitude().degrees <= 180);

        // with the range narrowed to +-3, -3 is the end of the scale
        ColorGradient.createColorGradient(-3, 3, true, info, "rvl");
        final Color recoloured = info.rvlAnalyticSurfaces.get(0).getValues().iterator().next().getColor();
        assertEquals(Color.BLUE.getRGB(), recoloured.getRGB());
    }

    @Test(expected = IOException.class)
    public void testWindBandsOfDifferentSizesAreRejected() throws Exception {
        final Product product = new Product("p", "OCN", 10, 10);
        for (String name : new String[]{"owiLon", "owiLat", "owiIncidenceAngle", "owiWindDirection"}) {
            product.addBand(new Band(name, ProductData.TYPE_FLOAT32, 10, 10));
        }
        product.addBand(new Band("owiWindSpeed", ProductData.TYPE_FLOAT32, 5, 5));

        createOWI().addProduct(product, new ProductRenderablesInfo());
    }
}
