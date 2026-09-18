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
import com.bc.ceres.multilevel.support.AbstractMultiLevelSource;
import com.bc.ceres.multilevel.support.DefaultMultiLevelModel;

import javax.media.jai.Interpolation;
import javax.media.jai.JAI;
import javax.media.jai.operator.TransposeDescriptor;
import javax.media.jai.operator.TransposeType;
import javax.media.jai.operator.TranslateDescriptor;
import java.awt.geom.AffineTransform;
import java.awt.image.RenderedImage;

/**
 * Presents an ICEYE GeoTIFF in SNAP's SAR raster convention.
 * <p>
 * ICEYE writes these products "shadows-down", with azimuth along the TIFF
 * columns and range along the TIFF rows - the transpose of SNAP, where x is
 * range and y is azimuth. Which way azimuth time runs along the columns depends
 * on the look side, so there are two rotations:
 *
 * <pre>
 *     left-looking:   SNAP(x, y) == TIFF(column = width - 1 - y, row = x)
 *     right-looking:  SNAP(x, y) == TIFF(column = y,             row = x)
 * </pre>
 *
 * Both put range down the rows with near range at x = 0; they differ only in the
 * direction of the azimuth axis.
 * <p>
 * The left-looking case is confirmed twice over on an ICEYE Open Data frame:
 * {@code iceye:incidence_angle_coeffs} is a polynomial in the range sample index
 * that reproduces the stated far incidence angle only when evaluated over
 * {@code proj:shape[1]} (the TIFF row count), and solving the zero-Doppler
 * condition against {@code iceye:orbit_states} puts column 0 at the end of the
 * aperture. The right-looking case follows the corner assignment that
 * {@link IceyeAMLCPXProductReader} has always used to geocode those products; no
 * right-looking frame was available to check it against.
 * <p>
 * The rotation is applied per pyramid level rather than to the level 0 image, so
 * the COG's overviews survive it - without them a gigapixel frame has to be
 * decoded in full to draw a thumbnail.
 *
 * @author Luis Veci
 */
public class IceyeTransposedMultiLevelSource extends AbstractMultiLevelSource {

    /**
     * JAI's ROTATE_270 is the operator that yields
     * {@code dst(x, y) == src(width - 1 - y, x)}; ROTATE_90 is its inverse and
     * would mirror the frame in azimuth.
     */
    private static final TransposeType LOOK_LEFT = TransposeDescriptor.ROTATE_270;

    /** The main-diagonal transpose, {@code dst(x, y) == src(y, x)}. */
    private static final TransposeType LOOK_RIGHT = TransposeDescriptor.FLIP_DIAGONAL;

    private final MultiLevelImage sourceImage;
    private final TransposeType rotation;

    /**
     * @param sourceImage the GDAL band image, in the TIFF's azimuth-by-range layout
     * @param width       the SNAP raster width, i.e. the number of range samples
     * @param height      the SNAP raster height, i.e. the number of azimuth lines
     * @param lookLeft    true for a left-looking acquisition
     */
    public IceyeTransposedMultiLevelSource(final MultiLevelImage sourceImage,
                                           final int width, final int height,
                                           final boolean lookLeft) {
        super(new DefaultMultiLevelModel(sourceImage.getModel().getLevelCount(),
                new AffineTransform(), width, height));
        this.sourceImage = sourceImage;
        this.rotation = lookLeft ? LOOK_LEFT : LOOK_RIGHT;
    }

    @Override
    protected RenderedImage createImage(final int level) {
        final RenderedImage rotated = TransposeDescriptor.create(
                sourceImage.getImage(level), rotation, null);

        // JAI leaves the rotated image on its source's origin; SNAP bands start at (0, 0).
        if (rotated.getMinX() == 0 && rotated.getMinY() == 0) {
            return rotated;
        }
        return TranslateDescriptor.create(rotated,
                (float) -rotated.getMinX(), (float) -rotated.getMinY(),
                Interpolation.getInstance(Interpolation.INTERP_NEAREST),
                JAI.getDefaultInstance().getRenderingHints());
    }
}
