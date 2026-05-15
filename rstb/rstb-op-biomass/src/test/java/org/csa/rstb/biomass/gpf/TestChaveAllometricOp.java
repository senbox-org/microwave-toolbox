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

public class TestChaveAllometricOp {

    private static final OperatorSpi spi = new ChaveAllometricOp.Spi();

    @Test
    public void spi_creates_operator() {
        final ChaveAllometricOp op = (ChaveAllometricOp) spi.createOperator();
        assertNotNull(op);
    }

    @Test
    public void operator_metadata_alias_and_category() {
        final OperatorMetadata md = ChaveAllometricOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("Chave-Allometric-AGB", md.alias());
        assertEquals("Radar/Biomass", md.category());
    }

    /**
     * Chave 2014 Eq. 7: AGB[kg] = 0.0673 * (rho * D^2 * H)^0.976.
     * For D=30cm, H=20m, rho=0.6 -> rho*D^2*H = 0.6*900*20 = 10800.
     * AGB = 0.0673 * 10800^0.976. Verify numerically.
     */
    @Test
    public void chave_eq7_known_value() {
        final double dbh = 30.0;
        final double height = 20.0;
        final double density = 0.6;
        final double expected = 0.0673 * Math.pow(density * dbh * dbh * height, 0.976);
        final double got = ChaveAllometricOp.chaveEq7AGB(dbh, height, density);
        assertEquals(expected, got, 1.0e-9);
        // Sanity: AGB for a 30cm DBH, 20m tropical tree is ~400-600 kg
        assertTrue("plausible tree AGB: " + got + " kg", got > 200.0 && got < 1500.0);
    }

    @Test
    public void chave_eq7_invalid_returns_nan() {
        assertTrue(Double.isNaN(ChaveAllometricOp.chaveEq7AGB(-1, 20, 0.6)));
        assertTrue(Double.isNaN(ChaveAllometricOp.chaveEq7AGB(30, 0, 0.6)));
        assertTrue(Double.isNaN(ChaveAllometricOp.chaveEq7AGB(30, 20, -0.1)));
    }

    /**
     * Saatchi 2011 preset: AGB = 2.0 * H^1.5. For H=20m: AGB = 2 * 20^1.5 ~ 179 Mg/ha.
     */
    @Test
    public void saatchi_preset_at_20m() {
        final ChaveAllometricOp op = new ChaveAllometricOp();
        setField(op, "preset", ChaveAllometricOp.PRESET_SAATCHI_2011);
        setField(op, "presetA", 2.0);
        setField(op, "presetB", 1.5);
        setField(op, "usesWoodDensity", false);
        setField(op, "defaultDensity", 0.58);

        final double agb = op.computeStandAGB(20.0, 0.58);
        final double expected = 2.0 * Math.pow(20.0, 1.5);
        assertEquals(expected, agb, 1.0e-9);
        // ~178.9 Mg/ha for a 20m closed tropical forest stand — order-of-magnitude sane.
        assertTrue("AGB in physical range", agb > 100.0 && agb < 250.0);
    }

    /**
     * Asner 2012 preset: AGB = 0.91 * H^2.04.
     */
    @Test
    public void asner_preset_at_25m() {
        final ChaveAllometricOp op = new ChaveAllometricOp();
        setField(op, "preset", ChaveAllometricOp.PRESET_ASNER_2012);
        setField(op, "presetA", 0.91);
        setField(op, "presetB", 2.04);
        setField(op, "usesWoodDensity", false);

        final double agb = op.computeStandAGB(25.0, 0.58);
        final double expected = 0.91 * Math.pow(25.0, 2.04);
        assertEquals(expected, agb, 1.0e-9);
    }

    /**
     * Chave 2014 preset uses wood density. Verify the linear scaling in rho.
     */
    @Test
    public void chave_preset_scales_linearly_in_density() {
        final ChaveAllometricOp op = new ChaveAllometricOp();
        setField(op, "preset", ChaveAllometricOp.PRESET_CHAVE_2014);
        setField(op, "presetA", 0.30);
        setField(op, "presetB", 2.0);
        setField(op, "usesWoodDensity", true);

        final double agb1 = op.computeStandAGB(20.0, 0.50);
        final double agb2 = op.computeStandAGB(20.0, 1.00);
        assertEquals("AGB doubles when density doubles", 2.0 * agb1, agb2, 1.0e-9);
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
