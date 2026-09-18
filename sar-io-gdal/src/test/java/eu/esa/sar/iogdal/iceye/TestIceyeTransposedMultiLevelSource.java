/*
 * Copyright (C) 2025 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.iogdal.iceye;

import com.bc.ceres.multilevel.MultiLevelImage;
import com.bc.ceres.multilevel.support.DefaultMultiLevelImage;
import com.bc.ceres.multilevel.support.DefaultMultiLevelModel;
import com.bc.ceres.multilevel.support.DefaultMultiLevelSource;
import org.junit.Test;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;

import static org.junit.Assert.assertEquals;

/**
 * ICEYE writes the Open Data COGs with azimuth along the TIFF columns and range
 * along the TIFF rows, which is the transpose of SNAP's convention. The
 * delivered frames happen to be square in the axis that would expose a mistake,
 * so the contract is pinned here on a deliberately non-square image.
 *
 * @author Luis Veci
 */
public class TestIceyeTransposedMultiLevelSource {

    /** Source pixel value encodes its own position, so a mix-up is unmissable. */
    private static BufferedImage rampImage(final int width, final int height) {
        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_USHORT_GRAY);
        for (int row = 0; row < height; ++row) {
            for (int col = 0; col < width; ++col) {
                image.getRaster().setSample(col, row, 0, 100 * col + row);
            }
        }
        return image;
    }

    private static MultiLevelImage asMultiLevelImage(final RenderedImage image) {
        return new DefaultMultiLevelImage(new DefaultMultiLevelSource(image,
                new DefaultMultiLevelModel(1, new AffineTransform(), image.getWidth(), image.getHeight())));
    }

    @Test
    public void testDimensionsAreSwapped() {
        final MultiLevelImage source = asMultiLevelImage(rampImage(7, 3));
        final IceyeTransposedMultiLevelSource transposed =
                new IceyeTransposedMultiLevelSource(source, 3, 7, true);

        final RenderedImage image = transposed.getImage(0);
        assertEquals(3, image.getWidth());
        assertEquals(7, image.getHeight());
    }

    /**
     * SNAP(x, y) == TIFF(column = width - 1 - y, row = x): range runs near to
     * far down the TIFF rows, and azimuth runs first to last line backwards
     * along the TIFF columns.
     */
    @Test
    public void testSampleMappingLookingLeft() {
        final int width = 7;
        final int height = 3;
        final BufferedImage source = rampImage(width, height);
        final IceyeTransposedMultiLevelSource transposed =
                new IceyeTransposedMultiLevelSource(asMultiLevelImage(source), height, width, true);

        final RenderedImage image = transposed.getImage(0);
        final Raster raster = image.getData();
        assertEquals(0, raster.getMinX());
        assertEquals(0, raster.getMinY());

        for (int y = 0; y < width; ++y) {
            for (int x = 0; x < height; ++x) {
                final int expected = source.getRaster().getSample(width - 1 - y, x, 0);
                assertEquals("at x=" + x + " y=" + y, expected, raster.getSample(x, y, 0));
            }
        }
    }

    @Test
    public void testCornersLandWhereTheyShouldLookingLeft() {
        final int width = 7;
        final int height = 3;
        final BufferedImage source = rampImage(width, height);
        final Raster raster = new IceyeTransposedMultiLevelSource(
                asMultiLevelImage(source), height, width, true).getImage(0).getData();

        // first azimuth line, near range <- last TIFF column, first TIFF row
        assertEquals(100 * (width - 1), raster.getSample(0, 0, 0));
        // last azimuth line, near range <- first TIFF column, first TIFF row
        assertEquals(0, raster.getSample(0, width - 1, 0));
        // first azimuth line, far range <- last TIFF column, last TIFF row
        assertEquals(100 * (width - 1) + height - 1, raster.getSample(height - 1, 0, 0));
    }

    @Test
    public void testLevelCountIsPreserved() {
        final MultiLevelImage source = new DefaultMultiLevelImage(new DefaultMultiLevelSource(
                rampImage(64, 32), new DefaultMultiLevelModel(3, new AffineTransform(), 64, 32)));
        final IceyeTransposedMultiLevelSource transposed =
                new IceyeTransposedMultiLevelSource(source, 32, 64, true);

        assertEquals(3, transposed.getModel().getLevelCount());
        for (int level = 0; level < 3; ++level) {
            final RenderedImage sourceLevel = source.getImage(level);
            final RenderedImage transposedLevel = transposed.getImage(level);
            assertEquals("level " + level, sourceLevel.getHeight(), transposedLevel.getWidth());
            assertEquals("level " + level, sourceLevel.getWidth(), transposedLevel.getHeight());
        }
    }

    /**
     * Right-looking flips the azimuth axis relative to left-looking:
     * SNAP(x, y) == TIFF(column = y, row = x), the plain transpose. This is the
     * corner assignment {@link IceyeAMLCPXProductReader} geocodes right-looking
     * products with.
     */
    @Test
    public void testSampleMappingLookingRight() {
        final int width = 7;
        final int height = 3;
        final BufferedImage source = rampImage(width, height);
        final Raster raster = new IceyeTransposedMultiLevelSource(
                asMultiLevelImage(source), height, width, false).getImage(0).getData();

        assertEquals(0, raster.getMinX());
        assertEquals(0, raster.getMinY());

        for (int y = 0; y < width; ++y) {
            for (int x = 0; x < height; ++x) {
                assertEquals("at x=" + x + " y=" + y,
                        source.getRaster().getSample(y, x, 0), raster.getSample(x, y, 0));
            }
        }
    }

    /** The two look sides must differ only by a flip in azimuth, never in range. */
    @Test
    public void testLookSidesAreMirroredInAzimuthOnly() {
        final int width = 7;
        final int height = 3;
        final BufferedImage source = rampImage(width, height);

        final Raster left = new IceyeTransposedMultiLevelSource(
                asMultiLevelImage(source), height, width, true).getImage(0).getData();
        final Raster right = new IceyeTransposedMultiLevelSource(
                asMultiLevelImage(source), height, width, false).getImage(0).getData();

        for (int y = 0; y < width; ++y) {
            for (int x = 0; x < height; ++x) {
                assertEquals("at x=" + x + " y=" + y,
                        left.getSample(x, y, 0), right.getSample(x, width - 1 - y, 0));
            }
        }
    }

    @Test
    public void testDimensionsAreSwappedLookingRight() {
        final MultiLevelImage source = asMultiLevelImage(rampImage(7, 3));
        final RenderedImage image = new IceyeTransposedMultiLevelSource(source, 3, 7, false).getImage(0);
        assertEquals(3, image.getWidth());
        assertEquals(7, image.getHeight());
    }
}
