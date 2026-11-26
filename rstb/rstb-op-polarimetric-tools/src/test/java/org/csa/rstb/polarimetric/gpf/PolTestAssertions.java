/*
 * Copyright (C) 2026 Skywatch. https://www.skywatch.com
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
package org.csa.rstb.polarimetric.gpf;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Shared assertions for polarimetric operator tests.
 */
final class PolTestAssertions {

    /**
     * Acquisition dates of the QuadPolStack test products (RS2-Quad_Pol_Stack, RS2-C3-Stack, RS2-T3-Stack):
     * a 64x64 crop of three coregistered RS2 FQ10W quad-pol SLCs over Manitoba (reference first).
     */
    static final String[] STACK_DATES = {"13Jun2012", "07Jul2012", "17Sep2012"};

    private PolTestAssertions() {
    }

    static double[] readBand(final Product product, final String bandName) throws IOException {
        final Band band = product.getBand(bandName);
        assertNotNull("band " + bandName + " in " + Arrays.toString(product.getBandNames()), band);
        final int w = product.getSceneRasterWidth();
        final int h = product.getSceneRasterHeight();
        final double[] data = new double[w * h];
        band.readPixels(0, 0, w, h, data, ProgressMonitor.NULL);
        return data;
    }

    /**
     * Asserts that a stack product has, for every acquisition date, one band per band of the
     * corresponding single-date product (band name + "_" + date), and nothing else.
     */
    static void assertStackBandsPerDate(final Product singleDate, final Product stack, final String[] dates) {
        final String[] singleNames = singleDate.getBandNames();
        for (String date : dates) {
            for (String name : singleNames) {
                assertTrue("stack is missing " + name + '_' + date + " in " + Arrays.toString(stack.getBandNames()),
                        stack.containsBand(name + '_' + date));
            }
        }
        assertEquals("number of stack bands " + Arrays.toString(stack.getBandNames()),
                singleNames.length * dates.length, stack.getNumBands());
    }

    /**
     * Asserts that two products computed from the same data through different inputs agree band by band:
     * every band of {@code expected} must exist in {@code actual} and match within the given tolerance.
     * The floor of each band is {@code floorFraction} times the band's mean absolute value.
     */
    static void assertSameBands(final String msg, final Product expected, final Product actual,
                                final double relTol, final double floorFraction) throws IOException {
        assertSameBands(msg, expected, actual, relTol, floorFraction, 0.0, 0);
    }

    /**
     * As {@link #assertSameBands(String, Product, Product, double, double)}, with an absolute floor (for bands that
     * are ~0 by construction) and ignoring a {@code border}-pixel frame at the image edges.
     */
    static void assertSameBands(final String msg, final Product expected, final Product actual,
                                final double relTol, final double floorFraction, final double absFloor,
                                final int border) throws IOException {
        final int w = expected.getSceneRasterWidth();
        final int h = expected.getSceneRasterHeight();
        for (String name : expected.getBandNames()) {
            final double[] e = interior(readBand(expected, name), w, h, border);
            final double[] a = interior(readBand(actual, name), w, h, border);
            double meanAbs = 0;
            for (double v : e) {
                meanAbs += Math.abs(v);
            }
            meanAbs /= e.length;
            assertClose(msg + ' ' + name, e, a, relTol, Math.max(floorFraction * meanAbs, absFloor) + 1e-12);
        }
    }

    private static double[] interior(final double[] values, final int w, final int h, final int border) {
        if (border == 0) {
            return values;
        }
        final double[] out = new double[(w - 2 * border) * (h - 2 * border)];
        int k = 0;
        for (int y = border; y < h - border; y++) {
            for (int x = border; x < w - border; x++) {
                out[k++] = values[y * w + x];
            }
        }
        return out;
    }

    /** Asserts that every value is finite. */
    static void assertAllFinite(final String msg, final double[] values) {
        for (int i = 0; i < values.length; i++) {
            if (!Double.isFinite(values[i])) {
                fail(msg + ": non-finite value " + values[i] + " at index " + i);
            }
        }
    }

    /** Asserts element-wise |expected - actual| <= relTol * max(|expected|, floor). */
    static void assertClose(final String msg, final double[] expected, final double[] actual,
                            final double relTol, final double floor) {
        assertEquals(msg + " length", expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            final double tol = relTol * Math.max(Math.abs(expected[i]), floor);
            if (Math.abs(expected[i] - actual[i]) > tol) {
                fail(msg + ": at index " + i + " expected " + expected[i] + " but was " + actual[i]);
            }
        }
    }
}
