/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.io.nisar;

import org.esa.snap.core.datamodel.CrsGeoCoding;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.PixelPos;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Pins the coordinate convention {@code NisarSubReader.setProjectedCrsGeoCoding} relies on.
 * <p>
 * NISAR L2 products list pixel-<em>centre</em> coordinates in {@code xCoordinates} /
 * {@code yCoordinates}. The reader passes {@code x[0], y[0]} as the geocoding origin together with
 * {@code referencePixelX/Y = 0.5}, which asserts that "the origin is the centre of pixel (0,0)".
 * If that assumption were wrong the whole NISAR grid would sit half a pixel off — a silent,
 * plausible-looking error of exactly the kind that is expensive to find later, so it is pinned here
 * rather than assumed.
 * <p>
 * The convention is a property of {@link CrsGeoCoding} and independent of the CRS, so a geographic
 * CRS is used to keep the test free of any EPSG-database dependency.
 */
public class TestNisarProjectedGeoCoding {

    private static final double EPS = 1.0e-9;

    /** referencePixel (0.5, 0.5) must mean: the origin is the CENTRE of the first pixel. */
    @Test
    public void testOriginIsFirstPixelCentre() throws Exception {
        final double x0 = -68.0, y0 = 10.0, step = 0.001;
        final CrsGeoCoding gc = new CrsGeoCoding(DefaultGeographicCRS.WGS84,
                16, 16, x0, y0, step, step, 0.5, 0.5);

        // The map coordinate of the first pixel's centre must be exactly (x0, y0).
        final GeoPos g = gc.getGeoPos(new PixelPos(0.5, 0.5), null);
        assertEquals("origin longitude is not the first pixel centre", x0, g.lon, EPS);
        assertEquals("origin latitude is not the first pixel centre", y0, g.lat, EPS);
    }

    /**
     * Every listed coordinate must land on its own pixel centre — i.e. coordinate index i maps to
     * pixel position i + 0.5. This is the round trip the reader's affine transform stands or falls
     * on, checked across the raster rather than at one corner.
     */
    @Test
    public void testListedCoordinatesLandOnPixelCentres() throws Exception {
        final int w = 16, h = 12;
        final double x0 = 500000.0, y0 = 4000000.0, stepX = 5.0, stepY = 10.0;
        final CrsGeoCoding gc = new CrsGeoCoding(DefaultGeographicCRS.WGS84,
                w, h, x0, y0, stepX, stepY, 0.5, 0.5);

        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                // NISAR lists ascending eastings and DESCENDING northings.
                final double xi = x0 + i * stepX;
                final double yj = y0 - j * stepY;

                final PixelPos p = gc.getPixelPos(new GeoPos(yj, xi), null);
                assertEquals("x index " + i + " off centre", i + 0.5, p.x, 1.0e-6);
                assertEquals("y index " + j + " off centre", j + 0.5, p.y, 1.0e-6);
            }
        }
    }

    /**
     * Rectangular cells must survive: an X step different from the Y step has to be preserved
     * independently, since NISAR (5 m east x 10 m north for OPERA-style posting) and BIOMASS both
     * use non-square map cells.
     */
    @Test
    public void testRectangularCellStepsArePreservedPerAxis() throws Exception {
        final double x0 = 0.0, y0 = 1000.0, stepX = 5.0, stepY = 10.0;
        final CrsGeoCoding gc = new CrsGeoCoding(DefaultGeographicCRS.WGS84,
                8, 8, x0, y0, stepX, stepY, 0.5, 0.5);

        final java.awt.geom.AffineTransform at =
                (java.awt.geom.AffineTransform) gc.getImageToMapTransform();
        assertEquals("X step not preserved", stepX, Math.abs(at.getScaleX()), EPS);
        assertEquals("Y step not preserved", stepY, Math.abs(at.getScaleY()), EPS);
    }
}
