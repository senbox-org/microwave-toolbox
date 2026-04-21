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
package org.csa.rstb.soilmoisture.gpf.support;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link SVFMinimizer}.
 *
 * Placed in the same package so the tests can reach the package-private
 * constructor and {@code minimize} method. Uses analytic single-variable
 * functions whose minima are known in closed form.
 */
public class TestSVFMinimizer {

    @Test
    public void testMinimizeParabolaFindsVertex() {
        // f(x) = (x - 3)^2 has a minimum at x = 3.
        final SingleVarFunc parabola = (x, fixed) -> (x - 3.0) * (x - 3.0);
        final SVFMinimizer opt = new SVFMinimizer(-999.0, /*lower*/ 0.0, /*upper*/ 10.0, parabola);

        final double min = opt.minimize(new double[0]);
        assertEquals(3.0, min, 1e-6);
    }

    @Test
    public void testMinimizeParabolaWithFixedOffsetParameter() {
        // f(x; c) = (x - c)^2, minimum at x = c.
        final SingleVarFunc parabola = (x, fixed) -> (x - fixed[0]) * (x - fixed[0]);
        final SVFMinimizer opt = new SVFMinimizer(-999.0, -10.0, 10.0, parabola);

        assertEquals(2.5, opt.minimize(new double[] { 2.5 }), 1e-6);
        assertEquals(-4.0, opt.minimize(new double[] { -4.0 }), 1e-6);
    }

    @Test
    public void testMinimizeShiftedSquareWithMultipleFixedParams() {
        // f(x; a, b) = a*(x - b)^2
        final SingleVarFunc func = (x, fixed) -> fixed[0] * (x - fixed[1]) * (x - fixed[1]);
        final SVFMinimizer opt = new SVFMinimizer(-999.0, -5.0, 5.0, func);

        assertEquals(1.5, opt.minimize(new double[] { 2.0, 1.5 }), 1e-6);
    }

    @Test
    public void testMinimizeQuartic() {
        // f(x) = x^4 - 4 * x^2  — local minima at x = ±sqrt(2).
        // Constrain the search to the positive half so Brent converges to one of them.
        final SingleVarFunc quartic = (x, fixed) -> x * x * x * x - 4.0 * x * x;
        final SVFMinimizer opt = new SVFMinimizer(-999.0, 0.0, 5.0, quartic);

        final double min = opt.minimize(new double[0]);
        assertEquals(Math.sqrt(2.0), min, 1e-5);
    }

    @Test
    public void testMinimizeResultStaysWithinSearchInterval() {
        // Monotonically decreasing function — minimum is at the right edge of the interval.
        final SingleVarFunc f = (x, fixed) -> -x;
        final SVFMinimizer opt = new SVFMinimizer(-999.0, 0.0, 7.0, f);

        final double min = opt.minimize(new double[0]);
        assertTrue("result must lie inside [0,7]", min >= 0.0 && min <= 7.0);
        assertEquals(7.0, min, 1e-4);
    }
}
