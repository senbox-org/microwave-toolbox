/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. https://www.skywatch.com
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
package eu.esa.sar.insar.gpf.timeseries;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestNetwork {

    @Test
    public void design_matrix_has_two_nonzeros_per_row_and_correct_shape() {
        final int N = 6;
        final List<String> dates = Arrays.asList("d0", "d1", "d2", "d3", "d4", "d5");
        final List<Long> mjd = Arrays.asList(0L, 1L, 2L, 3L, 4L, 5L);
        final int[] pm = {0, 1, 2, 3, 4, 0, 1, 2};
        final int[] ps = {1, 2, 3, 4, 5, 2, 3, 4};
        final int refIdx = 2;

        final Network net = new Network(dates, mjd, pm, ps, refIdx);
        final double[][] A = net.designMatrix();

        assertEquals("M rows", pm.length, A.length);
        assertEquals("N-1 columns", N - 1, A[0].length);

        for (int k = 0; k < A.length; k++) {
            int plus = 0, minus = 0, zero = 0;
            for (int j = 0; j < A[k].length; j++) {
                if (A[k][j] == 1.0) plus++;
                else if (A[k][j] == -1.0) minus++;
                else if (A[k][j] == 0.0) zero++;
            }
            // Row has at most two nonzeros (could be 1 if one end is the reference)
            assertTrue("plus<=1", plus <= 1);
            assertTrue("minus<=1", minus <= 1);
            assertEquals(A[k].length, plus + minus + zero);
        }
    }

    @Test
    public void triplets_enumerated_correctly_for_dense_network() {
        // 4 epochs, complete graph -> C(4,3) = 4 triangles
        final List<String> dates = Arrays.asList("a", "b", "c", "d");
        final List<Long> mjd = Arrays.asList(0L, 1L, 2L, 3L);
        final int[] pm = {0, 0, 0, 1, 1, 2};
        final int[] ps = {1, 2, 3, 2, 3, 3};
        final Network net = new Network(dates, mjd, pm, ps, 0);
        assertEquals(4, net.numTriplets());
    }

    @Test
    public void reference_column_eliminated() {
        final List<String> dates = Arrays.asList("a", "b", "c");
        final List<Long> mjd = Arrays.asList(0L, 1L, 2L);
        final int[] pm = {0, 0, 1};
        final int[] ps = {1, 2, 2};
        final Network net = new Network(dates, mjd, pm, ps, 1);  // ref = epoch index 1

        // Column 0 corresponds to epoch 0; column 1 corresponds to epoch 2.
        // pair (0->1): A row should have -1 at col-for-0 (=0), reference column dropped -> no +1.
        // pair (0->2): -1 at col 0, +1 at col 1.
        // pair (1->2): no -1 (ref), +1 at col 1.
        final double[][] A = net.designMatrix();
        assertEquals(-1.0, A[0][0], 0.0);
        assertEquals(0.0, A[0][1], 0.0);
        assertEquals(-1.0, A[1][0], 0.0);
        assertEquals(1.0, A[1][1], 0.0);
        assertEquals(0.0, A[2][0], 0.0);
        assertEquals(1.0, A[2][1], 0.0);
    }
}
