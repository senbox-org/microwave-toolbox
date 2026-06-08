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

public class TestClosurePhase {

    @Test
    public void closure_is_zero_for_consistent_phase() {
        final List<String> dates = Arrays.asList("a", "b", "c");
        final List<Long> mjd = Arrays.asList(0L, 1L, 2L);
        // Three pairs: (a,b), (b,c), (a,c)
        final int[] pm = {0, 1, 0};
        final int[] ps = {1, 2, 2};
        final Network net = new Network(dates, mjd, pm, ps, 0);

        // phi_ab = 0.3, phi_bc = 0.4, phi_ac = phi_ab + phi_bc = 0.7
        final double[] phi = {0.3, 0.4, 0.7};
        assertEquals(0.0, ClosurePhase.rms(net, phi), 1.0e-12);
    }

    @Test
    public void closure_recovers_two_pi_unwrap_error() {
        final List<String> dates = Arrays.asList("a", "b", "c");
        final List<Long> mjd = Arrays.asList(0L, 1L, 2L);
        final int[] pm = {0, 1, 0};
        final int[] ps = {1, 2, 2};
        final Network net = new Network(dates, mjd, pm, ps, 0);

        // Inject a 2 pi shift on phi_ac. Wrap-to-(-pi,pi] makes closure approx 0
        // (because 2 pi wraps to 0), so the unwrap-error indicator must come
        // from the UN-wrapped magnitude. Verify the wrapped closure is near 0.
        final double[] phiCorrect = {0.3, 0.4, 0.7};
        final double[] phiWrong = {0.3, 0.4, 0.7 + 2 * Math.PI};
        final double rmsCorrect = ClosurePhase.rms(net, phiCorrect);
        final double rmsWrong = ClosurePhase.rms(net, phiWrong);
        // Wrapped: 0.7 + 2pi - 0.7 = 2 pi -> wraps to 0
        assertTrue("wrapped closure should still be small: " + rmsWrong, rmsWrong < 1.0e-9);
        assertEquals(rmsCorrect, rmsWrong, 1.0e-9);
    }

    @Test
    public void closure_is_nonzero_for_inconsistent_phase() {
        final List<String> dates = Arrays.asList("a", "b", "c");
        final List<Long> mjd = Arrays.asList(0L, 1L, 2L);
        final int[] pm = {0, 1, 0};
        final int[] ps = {1, 2, 2};
        final Network net = new Network(dates, mjd, pm, ps, 0);

        final double[] phi = {0.3, 0.4, 0.5};   // phi_ab + phi_bc = 0.7, but phi_ac = 0.5
        // expected closure = 0.2
        assertEquals(0.2, ClosurePhase.rms(net, phi), 1.0e-9);
    }
}
