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
package org.csa.rstb.biomass.gpf.treeheight;

import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TestRVoG3StageInversionOp {

    private static final OperatorSpi spi = new RVoG3StageInversionOp.Spi();

    @Test
    public void spi_creates_operator() {
        final RVoG3StageInversionOp op = (RVoG3StageInversionOp) spi.createOperator();
        assertNotNull(op);
    }

    @Test
    public void operator_metadata_alias_and_category() {
        final OperatorMetadata md = RVoG3StageInversionOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("RVoG-3SI-Forest-Height", md.alias());
        assertEquals("Radar/Biomass", md.category());
    }

    /**
     * Synthetic 3SI scenario: place three points on a straight line in the
     * complex plane that crosses the unit circle. The ground point should be
     * the unit-circle intersection closest to the lowest-magnitude input.
     */
    @Test
    public void three_collinear_points_recover_ground_on_unit_circle() {
        // Line: from g_g = (1, 0) (ground, on unit circle) to g_v = (0.5, 0) (volume, inside)
        // Three coherences sampled along this line:
        final double kz = 0.10;

        final RVoG3StageInversionOp.Result r = new RVoG3StageInversionOp.Result();
        RVoG3StageInversionOp.invert3SI(
                1.00, 0.0,    // gamma_1: pure ground
                0.75, 0.0,    // gamma_2: mix
                0.50, 0.0,    // gamma_3: pure volume
                kz, 1.0e-3, 60.0, r);

        // gamma_g should be approximately (1, 0); |gamma_g| ~ 1
        assertEquals("|gamma_g| ~ 1", 1.0, r.gammaGMag, 0.02);
        // |gamma_v_pure| should approximate 0.5 (the volume input has half the
        // ground coherence magnitude). The exact value depends on the line span.
        assertTrue("|gamma_v_pure| in (0, 1]", r.gammaVMag > 0.0 && r.gammaVMag <= 1.0);
        // Height recovered should be > 0
        assertTrue("hv > 0: " + r.height, r.height > 0.0);
    }

    /**
     * Degenerate input (3 identical points): should reject the line fit.
     */
    @Test
    public void coincident_points_yield_no_data() {
        final RVoG3StageInversionOp.Result r = new RVoG3StageInversionOp.Result();
        RVoG3StageInversionOp.invert3SI(
                0.7, 0.2, 0.7, 0.2, 0.7, 0.2,
                0.10, 1.0e-3, 60.0, r);
        // Spread is zero; no inversion possible
        assertTrue("height should be no-data: " + r.height, r.height < 0.0);
    }

    /**
     * Round-trip: build a known volume coherence at hv = 15 m, kz = 0.15, and
     * verify the 3SI recovers hv when the three input points are placed on
     * a line from gamma_g=1 to gamma_v.
     */
    @Test
    public void round_trip_volume_height() {
        final double kz = 0.15;
        final double hv = 15.0;
        final double x = kz * hv / (2.0 * Math.PI);
        final double gammaV = Math.sin(Math.PI * x) / (Math.PI * x);  // ~0.66 for hv=15, kz=0.15

        // Three points on the real axis: (1, 0), midpoint, (gammaV, 0)
        final double midX = 0.5 * (1.0 + gammaV);
        final RVoG3StageInversionOp.Result r = new RVoG3StageInversionOp.Result();
        RVoG3StageInversionOp.invert3SI(
                1.0, 0.0,
                midX, 0.0,
                gammaV, 0.0,
                kz, 1.0e-3, 60.0, r);

        assertEquals("|gamma_v_pure| recovered", gammaV, r.gammaVMag, 0.01);
        // Height recovered should approximate hv (the round-trip-of-truth value)
        assertEquals("forest height recovered", hv, r.height, 0.5);
    }

    @Test
    public void invalid_kz_returns_no_data() {
        final RVoG3StageInversionOp.Result r = new RVoG3StageInversionOp.Result();
        RVoG3StageInversionOp.invert3SI(
                1.0, 0.0, 0.7, 0.0, 0.4, 0.0,
                0.0, 1.0e-3, 60.0, r);
        assertTrue(r.height < 0.0);
    }
}
