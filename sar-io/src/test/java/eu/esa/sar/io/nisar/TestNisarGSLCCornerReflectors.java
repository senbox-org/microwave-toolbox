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

import com.bc.ceres.annotation.STTM;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.CrsGeoCoding;
import org.esa.snap.core.datamodel.GeoCoding;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Product;
import org.junit.After;
import org.junit.Test;

import java.awt.geom.AffineTransform;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Geolocation of a NISAR GSLC checked against the JPL corner reflectors at Rosamond, California.
 * <p>
 * Each reflector's surveyed position is mapped into the image with the product's geocoding and the
 * reflector's peak is located to sub-pixel precision in the HH SLC. A reflector counts only where it
 * stands out of the clutter; only those facing the radar in this pass do. Skipped when the product is
 * not in the test data folder.
 * <p>
 * Reflector list: JPL, epoch 2025-05-22, plate motion applied. The plate motion to the acquisition
 * date (about 5 cm/yr) and the reflector height above the geocoding DEM are well below a pixel.
 */
public class TestNisarGSLCCornerReflectors {

    private static final File GSLC = new File(TestData.inputSAR + "NISAR",
            "NISAR_L2_PR_GSLC_036_034_A_019_4005_DHDH_A_20261119T130824_20261119T130859_P05036_N_F_J_001.h5");
    private static final String REFLECTORS = "cr/2025-05-22_0000_Rosamond-corner-reflectors_with_plate_motion.csv";

    /** Half-size of the peak search window on the ground, metres: 16 pixels of frequency A. */
    private static final double HALF_WINDOW_M = 80;
    /** The search window is at least this many pixels either side, to hold the target's main lobe. */
    private static final int MIN_HALF_PX = 4;

    /** At least this many reflectors must be detected (2026-11: 19 on frequency A, 17 on B). */
    private static final int MIN_DETECTED = 12;

    private Product product;

    @After
    public void tearDown() {
        if (product != null) {
            product.dispose();
        }
    }

    private static final class Reflector {
        final String id;
        final double lat, lon, azimuth;

        Reflector(String id, double lat, double lon, double azimuth) {
            this.id = id;
            this.lat = lat;
            this.lon = lon;
            this.azimuth = azimuth;
        }
    }

    private List<Reflector> readReflectors() throws Exception {
        final List<Reflector> list = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream(REFLECTORS), StandardCharsets.UTF_8))) {
            in.readLine();  // header
            String line;
            while ((line = in.readLine()) != null) {
                final String[] f = line.trim().split(",");
                if (f.length >= 5) {
                    list.add(new Reflector(f[0].trim(), Double.parseDouble(f[1]), Double.parseDouble(f[2]),
                            Double.parseDouble(f[4])));
                }
            }
        }
        return list;
    }

    private static int nearest(final List<PixelPos> positions, final double col, final double row) {
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        for (int k = 0; k < positions.size(); k++) {
            final double d = Math.hypot(positions.get(k).x - col, positions.get(k).y - row);
            if (d < bestDist) {
                bestDist = d;
                best = k;
            }
        }
        return best;
    }

    @Test
    @STTM("SNAP-4384")
    public void dualFrequencyGslcOpensWithFrequencyBSuffix() throws Exception {
        assumeTrue(GSLC + " not found", GSLC.exists());
        product = new NisarProductReaderPlugIn().createReaderInstance().readProductNodes(GSLC, null);
        for (String name : new String[]{"i_HH", "q_HH", "Intensity_HH", "i_HV", "q_HV",
                "i_HH_S", "q_HH_S", "Intensity_HH_S", "i_HV_S", "q_HV_S"}) {
            assertNotNull("missing band " + name, product.getBand(name));
        }
    }

    /**
     * Frequency A, 40 MHz on a 5 m grid: each reflector within a pixel (5 m), the mean within 2 m, so a
     * half-pixel convention error (2.5 m) fails.
     */
    @Test
    @STTM("SNAP-4384")
    public void cornerReflectorsAreWhereTheGeocodingPutsThem() throws Exception {
        checkReflectors("", 5, 2);
    }

    /**
     * Frequency B, 5 MHz on a 40 m (east) by 5 m grid, with its own geocoding. Its ground-range resolution
     * is about 50 m and the 40 m pixels barely sample it, so single reflectors scatter by up to ~20 m
     * along range: each within one resolution cell (50 m). Measured 2026-11: 17 reflectors, mean 1.7 m
     * west and 0.7 m south of the surveyed positions (1.8 m); frequency A's geocoding on these bands put
     * them hundreds of metres off. The mean must stay within 4 m, so a half-pixel error along the 40 m
     * axis (20 m) fails; the 5 m axis shares its pixel convention with frequency A's test.
     */
    @Test
    @STTM("SNAP-4384")
    public void frequencyBCornerReflectorsAreWhereItsGeocodingPutsThem() throws Exception {
        checkReflectors("_S", 50, 4);
    }

    /**
     * @param maxErrorM     largest horizontal error of a single reflector, metres
     * @param maxMeanErrorM largest horizontal mean error, metres
     */
    private void checkReflectors(final String suffix, final double maxErrorM, final double maxMeanErrorM) throws Exception {
        assumeTrue(GSLC + " not found", GSLC.exists());
        product = new NisarProductReaderPlugIn().createReaderInstance().readProductNodes(GSLC, null);

        final Band i = product.getBand("i_HH" + suffix), q = product.getBand("q_HH" + suffix);
        assertNotNull(i);
        assertNotNull(q);
        final GeoCoding geoCoding = i.getGeoCoding();
        assertTrue("GSLC should carry an exact map geocoding", geoCoding instanceof CrsGeoCoding);
        final AffineTransform imageToMap = (AffineTransform) ((CrsGeoCoding) geoCoding).getImageToMapTransform();
        final double spacingX = Math.abs(imageToMap.getScaleX()), spacingY = Math.abs(imageToMap.getScaleY());
        final int width = i.getRasterWidth(), height = i.getRasterHeight();
        final int halfRow = Math.max(MIN_HALF_PX, (int) Math.round(HALF_WINDOW_M / spacingY));
        final int halfCol = Math.max(MIN_HALF_PX, (int) Math.round(HALF_WINDOW_M / spacingX));

        final List<double[]> errors = new ArrayList<>();
        final StringBuilder table = new StringBuilder(String.format(
                "NISAR GSLC Rosamond HH%s, spacing %.3f x %.3f m%n  id  azimuth  exp_row    exp_col    d_row(px) d_col(px) d_north(m) d_east(m)  SCR(dB)%n",
                suffix, spacingX, spacingY));
        final List<Reflector> reflectors = readReflectors();
        final List<PixelPos> expected = new ArrayList<>();
        for (Reflector cr : reflectors) {
            // getPixelPos is corner based (pixel i spans i..i+1); the peak finder works in pixel centres.
            final PixelPos pos = geoCoding.getPixelPos(new GeoPos(cr.lat, cr.lon), null);
            expected.add(new PixelPos(pos.x - 0.5, pos.y - 0.5));
        }

        for (int n = 0; n < reflectors.size(); n++) {
            final Reflector cr = reflectors.get(n);
            final PixelPos pos = expected.get(n);
            final double expRow = pos.y, expCol = pos.x;

            final int x0 = Math.max(0, (int) expCol - 2 * halfCol), y0 = Math.max(0, (int) expRow - 2 * halfRow);
            final int w = Math.min(width, (int) expCol + 2 * halfCol) - x0;
            final int h = Math.min(height, (int) expRow + 2 * halfRow) - y0;
            if (!pos.isValid() || w <= 2 * halfCol || h <= 2 * halfRow) {
                continue;
            }
            final float[] re = new float[w * h], im = new float[w * h];
            i.readPixels(x0, y0, w, h, re);
            q.readPixels(x0, y0, w, h, im);

            final CornerReflectorPeak.Peak peak = CornerReflectorPeak.find(re, im, w, h, expRow - y0, expCol - x0, halfRow, halfCol);
            if (peak == null) {
                continue;
            }
            // The reflectors are about 100 m apart, closer than the search window reaches: a peak
            // nearer another reflector's expected position is that reflector, not this one.
            final int nearest = nearest(expected, x0 + peak.col, y0 + peak.row);
            if (nearest != n) {
                table.append(String.format("  %s  %7.2f  peak belongs to %s%n", cr.id, cr.azimuth, reflectors.get(nearest).id));
                continue;
            }
            final double dRow = y0 + peak.row - expRow, dCol = x0 + peak.col - expCol;
            if (Math.abs(dRow) > halfRow || Math.abs(dCol) > halfCol) {
                table.append(String.format("  %s  %7.2f  peak refined outside the search window%n", cr.id, cr.azimuth));
                continue;
            }
            errors.add(new double[]{dRow, dCol});
            // rows run south
            table.append(String.format("  %s  %7.2f  %9.2f  %9.2f  %8.3f  %8.3f  %9.2f  %8.2f  %6.1f%n",
                    cr.id, cr.azimuth, expRow, expCol, dRow, dCol, -dRow * spacingY, dCol * spacingX, peak.scrDb));
        }

        double meanRow = 0, meanCol = 0;
        for (double[] e : errors) {
            meanRow += e[0] / errors.size();
            meanCol += e[1] / errors.size();
        }
        table.append(String.format("  detected %d, mean d_row %.3f px (%.2f m), mean d_col %.3f px (%.2f m)%n",
                errors.size(), meanRow, meanRow * spacingY, meanCol, meanCol * spacingX));
        System.out.print(table);

        assertTrue("too few reflectors detected:\n" + table, errors.size() >= MIN_DETECTED);
        for (double[] e : errors) {
            assertTrue("reflector error over " + maxErrorM + " m:\n" + table,
                    Math.hypot(e[0] * spacingY, e[1] * spacingX) <= maxErrorM);
        }
        assertTrue("mean error over " + maxMeanErrorM + " m:\n" + table,
                Math.hypot(meanRow * spacingY, meanCol * spacingX) < maxMeanErrorM);
    }
}
