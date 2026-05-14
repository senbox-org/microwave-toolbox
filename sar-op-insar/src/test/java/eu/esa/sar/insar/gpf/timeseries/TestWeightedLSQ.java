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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TestWeightedLSQ {

    @Test
    public void noise_free_inversion_recovers_truth() {
        // 5 epochs, 4 sequential pairs + 2 longer-baseline = 6 equations
        final List<String> dates = Arrays.asList("d0", "d1", "d2", "d3", "d4");
        final List<Long> mjd = Arrays.asList(0L, 1L, 2L, 3L, 4L);
        final int[] pm = {0, 1, 2, 3, 0, 1};
        final int[] ps = {1, 2, 3, 4, 2, 3};
        final Network net = new Network(dates, mjd, pm, ps, 0);

        final double[] truePhase = {0.0, 0.3, -0.5, 1.2, -0.7};  // phase per epoch, epoch 0 = ref
        final int M = pm.length;
        final double[] phi = new double[M];
        for (int k = 0; k < M; k++) {
            phi[k] = truePhase[ps[k]] - truePhase[pm[k]];
        }
        final double[] w = new double[M];
        Arrays.fill(w, 1.0);

        final WeightedLSQ solver = new WeightedLSQ(net.designMatrix(), 1.0e-3, 1.0e6);
        final WeightedLSQ.Result res = new WeightedLSQ.Result();
        solver.solve(phi, w, res);

        assertTrue("solver should succeed", res.ok);
        assertFalse("Tikhonov should NOT activate on well-conditioned system", res.regularized);

        // x has K = N-1 = 4 entries, indexed 0..3 -> epochs 1..4 (ref = 0)
        for (int i = 1; i < 5; i++) {
            assertEquals("epoch " + i, truePhase[i], res.x[i - 1], 1.0e-10);
        }
        for (int k = 0; k < M; k++) {
            assertEquals("residual " + k, 0.0, res.residuals[k], 1.0e-9);
        }
    }

    @Test
    public void disconnected_pixel_returns_ok_false() {
        final List<String> dates = Arrays.asList("d0", "d1", "d2");
        final List<Long> mjd = Arrays.asList(0L, 1L, 2L);
        final int[] pm = {0, 1};
        final int[] ps = {1, 2};
        final Network net = new Network(dates, mjd, pm, ps, 0);

        final WeightedLSQ solver = new WeightedLSQ(net.designMatrix(), 1.0e-3, 1.0e6);
        final WeightedLSQ.Result res = new WeightedLSQ.Result();
        // Only one equation has weight -> can't solve for 2 unknowns
        solver.solve(new double[]{1.0, 0.5}, new double[]{1.0, 0.0}, res);
        assertFalse("rank-deficient inversion must fail", res.ok);
    }
}
