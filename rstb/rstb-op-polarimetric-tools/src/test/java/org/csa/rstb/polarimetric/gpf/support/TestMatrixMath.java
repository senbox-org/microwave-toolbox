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
package org.csa.rstb.polarimetric.gpf.support;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Unit tests for the default methods of the {@link MatrixMath} interface.
 */
public class TestMatrixMath {

    private static final MatrixMath MATH = new MatrixMath() { };

    @Test
    public void testMatrixPlusEqualsAddsInPlace() {
        final double[][] a = { { 1.0, 2.0 }, { 3.0, 4.0 } };
        final double[][] b = { { 10.0, 20.0 }, { 30.0, 40.0 } };

        MATH.matrixPlusEquals(a, b);

        assertArrayEquals(new double[] { 11.0, 22.0 }, a[0], 1e-12);
        assertArrayEquals(new double[] { 33.0, 44.0 }, a[1], 1e-12);
        // b is unchanged.
        assertArrayEquals(new double[] { 10.0, 20.0 }, b[0], 1e-12);
    }

    @Test
    public void testMatrixPlusEqualsWithZeroMatrixIsIdentity() {
        final double[][] a = { { 1.0, 2.0 }, { 3.0, 4.0 } };
        final double[][] zero = new double[2][2];
        MATH.matrixPlusEquals(a, zero);
        assertArrayEquals(new double[] { 1.0, 2.0 }, a[0], 1e-12);
        assertArrayEquals(new double[] { 3.0, 4.0 }, a[1], 1e-12);
    }

    @Test
    public void testMatrixTimesEqualsScalesInPlace() {
        final double[][] a = { { 1.0, 2.0 }, { 3.0, 4.0 } };
        MATH.matrixTimesEquals(a, 2.5);
        assertArrayEquals(new double[] { 2.5, 5.0 }, a[0], 1e-12);
        assertArrayEquals(new double[] { 7.5, 10.0 }, a[1], 1e-12);
    }

    @Test
    public void testMatrixTimesEqualsByZeroZerosMatrix() {
        final double[][] a = { { 1.0, 2.0 }, { 3.0, 4.0 } };
        MATH.matrixTimesEquals(a, 0.0);
        assertArrayEquals(new double[] { 0.0, 0.0 }, a[0], 1e-12);
        assertArrayEquals(new double[] { 0.0, 0.0 }, a[1], 1e-12);
    }

    @Test
    public void testMatrixTransposeSwapsRowsAndCols() {
        final double[][] a = { { 1.0, 2.0 }, { 3.0, 4.0 } };
        final double[][] t = new double[2][2];
        MATH.matrixtranspose(a, t);
        // transpose of [[1,2],[3,4]] = [[1,3],[2,4]]
        assertArrayEquals(new double[] { 1.0, 3.0 }, t[0], 1e-12);
        assertArrayEquals(new double[] { 2.0, 4.0 }, t[1], 1e-12);
    }

    @Test
    public void testMatrixTransposeOfIdentityIsIdentity() {
        final double[][] I = { { 1.0, 0.0 }, { 0.0, 1.0 } };
        final double[][] t = new double[2][2];
        MATH.matrixtranspose(I, t);
        assertArrayEquals(new double[] { 1.0, 0.0 }, t[0], 1e-12);
        assertArrayEquals(new double[] { 0.0, 1.0 }, t[1], 1e-12);
    }

    @Test
    public void testMatrixMultiplyIdentity() {
        final double[][] I = { { 1.0, 0.0 }, { 0.0, 1.0 } };
        final double[][] a = { { 5.0, 6.0 }, { 7.0, 8.0 } };
        final double[][] out = new double[2][2];
        MATH.matrixmultiply(I, a, out);
        assertArrayEquals(a[0], out[0], 1e-12);
        assertArrayEquals(a[1], out[1], 1e-12);
    }

    @Test
    public void testMatrixMultiplyProducesExpectedValues() {
        // [[1,2],[3,4]] * [[5,6],[7,8]] = [[19,22],[43,50]]
        final double[][] a = { { 1.0, 2.0 }, { 3.0, 4.0 } };
        final double[][] b = { { 5.0, 6.0 }, { 7.0, 8.0 } };
        final double[][] out = new double[2][2];
        MATH.matrixmultiply(a, b, out);
        assertEquals(19.0, out[0][0], 1e-12);
        assertEquals(22.0, out[0][1], 1e-12);
        assertEquals(43.0, out[1][0], 1e-12);
        assertEquals(50.0, out[1][1], 1e-12);
    }

    @Test
    public void testMatrixMultiply3x3Identity() {
        final double[][] I = {
                { 1.0, 0.0, 0.0 },
                { 0.0, 1.0, 0.0 },
                { 0.0, 0.0, 1.0 }
        };
        final double[][] a = {
                { 1.0, 2.0, 3.0 },
                { 4.0, 5.0, 6.0 },
                { 7.0, 8.0, 9.0 }
        };
        final double[][] out = new double[3][3];
        MATH.matrixmultiply(I, a, out);
        for (int i = 0; i < 3; i++) {
            assertArrayEquals(a[i], out[i], 1e-12);
        }
    }
}
