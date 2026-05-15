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
package org.csa.rstb.biomass.gpf;

import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TestAGBUncertaintyOp {

    private static final OperatorSpi spi = new AGBUncertaintyOp.Spi();

    @Test
    public void spi_creates_operator() {
        final AGBUncertaintyOp op = (AGBUncertaintyOp) spi.createOperator();
        assertNotNull(op);
    }

    @Test
    public void operator_metadata_alias_and_category() {
        final OperatorMetadata md = AGBUncertaintyOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("AGB-Uncertainty", md.alias());
        assertEquals("Radar/Biomass", md.category());
    }

    /**
     * Error budget for AGB = 100 Mg/ha with:
     *   - radio SE = 0.5 dB, sensitivity 0.23/dB -> rel = 0.115
     *   - regression RMSE = 20 Mg/ha (rel = 0.20)
     *   - allometric = 0.15
     *   - spatial = 0.10
     * sigma_rel = sqrt(0.115^2 + 0.20^2 + 0.15^2 + 0.10^2)
     *           = sqrt(0.0132 + 0.04 + 0.0225 + 0.01) = sqrt(0.0857) = 0.293
     * sigma_abs = 29.3 Mg/ha.
     */
    @Test
    public void standard_error_known_inputs() {
        final AGBUncertaintyOp op = new AGBUncertaintyOp();
        setField(op, "sensitivityPerDb", 0.23);
        setField(op, "regressionRMSE", 20.0);
        setField(op, "allometricRelative", 0.15);
        setField(op, "spatialRelative", 0.10);

        final double se = op.computeStandardError(100.0, 0.5);

        final double relRad = 0.23 * 0.5;
        final double relModel = 20.0 / 100.0;
        final double expectedRel = Math.sqrt(relRad * relRad
                + relModel * relModel
                + 0.15 * 0.15
                + 0.10 * 0.10);
        final double expected = 100.0 * expectedRel;
        assertEquals("SE matches error budget", expected, se, 1.0e-9);
        // sanity: SE in plausible range for IPCC Tier-1 reporting
        assertTrue("SE in 5-50 Mg/ha range", se > 5.0 && se < 50.0);
    }

    /**
     * SE should scale linearly with AGB for fixed relative uncertainties.
     */
    @Test
    public void standard_error_scales_linearly_with_agb() {
        final AGBUncertaintyOp op = new AGBUncertaintyOp();
        setField(op, "sensitivityPerDb", 0.23);
        setField(op, "regressionRMSE", 0.0);  // remove RMSE term to make it purely relative
        setField(op, "allometricRelative", 0.15);
        setField(op, "spatialRelative", 0.10);

        final double se50 = op.computeStandardError(50.0, 0.5);
        final double se100 = op.computeStandardError(100.0, 0.5);
        final double se200 = op.computeStandardError(200.0, 0.5);

        assertEquals("SE(100)/SE(50) ~ 2", 2.0 * se50, se100, 1.0e-9);
        assertEquals("SE(200)/SE(100) ~ 2", 2.0 * se100, se200, 1.0e-9);
    }

    /**
     * Quadrature combination: identical inputs should give SE = AGB * uncertainty * sqrt(N).
     */
    @Test
    public void quadrature_combines_correctly() {
        final AGBUncertaintyOp op = new AGBUncertaintyOp();
        setField(op, "sensitivityPerDb", 0.0);
        setField(op, "regressionRMSE", 0.0);
        setField(op, "allometricRelative", 0.10);
        setField(op, "spatialRelative", 0.10);

        // Only two relative terms of 0.10 each -> total = sqrt(0.01 + 0.01) = 0.1414
        final double se = op.computeStandardError(100.0, 0.0);
        assertEquals(100.0 * Math.sqrt(0.02), se, 1.0e-9);
    }

    private static void setField(final Object obj, final String name, final Object value) {
        try {
            final java.lang.reflect.Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(obj, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
