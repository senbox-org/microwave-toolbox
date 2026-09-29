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

/**
 * Unit tests for the {@link SingleVarFunc} single-abstract-method interface.
 */
public class TestSingleVarFunc {

    @Test
    public void testLambdaImplementationEvaluatesAtPoint() {
        final SingleVarFunc square = (x, fixed) -> x * x;
        assertEquals(0.0, square.compute(0.0, new double[0]), 0.0);
        assertEquals(4.0, square.compute(2.0, new double[0]), 0.0);
        assertEquals(9.0, square.compute(-3.0, new double[0]), 0.0);
    }

    @Test
    public void testLambdaImplementationReadsFixedParameters() {
        // f(x; a, b) = a * x + b
        final SingleVarFunc linear = (x, fixed) -> fixed[0] * x + fixed[1];
        assertEquals(7.0, linear.compute(2.0, new double[] { 3.0, 1.0 }), 0.0);
        assertEquals(-5.0, linear.compute(1.0, new double[] { -2.0, -3.0 }), 0.0);
    }

    @Test
    public void testEmptyFixedArrayIsAccepted() {
        final SingleVarFunc f = (x, fixed) -> x + fixed.length;
        assertEquals(5.0, f.compute(5.0, new double[0]), 0.0);
    }
}
