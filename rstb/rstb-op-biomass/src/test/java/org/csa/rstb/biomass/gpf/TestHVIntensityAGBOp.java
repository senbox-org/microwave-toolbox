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

public class TestHVIntensityAGBOp {

    private static final OperatorSpi spi = new HVIntensityAGBOp.Spi();

    @Test
    public void spi_creates_operator() {
        final HVIntensityAGBOp op = (HVIntensityAGBOp) spi.createOperator();
        assertNotNull(op);
    }

    @Test
    public void operator_metadata_alias_and_category() {
        final OperatorMetadata md = HVIntensityAGBOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("HV-Intensity-AGB-Fit", md.alias());
        assertEquals("Radar/Biomass", md.category());
    }

    /**
     * Mitchard 2009 exponential: AGB = a * (1 - exp(-b * s_lin)).
     * At s_lin = 0 -> AGB = 0; at s_lin -> infinity -> AGB -> a (saturation).
     */
    @Test
    public void mitchard_exponential_saturates_to_coefA() {
        final HVIntensityAGBOp op = new HVIntensityAGBOp();
        setField(op, "model", HVIntensityAGBOp.MODEL_MITCHARD_EXP);
        setField(op, "coefA", 31.6);
        setField(op, "coefB", 0.069);
        setField(op, "inputIsDb", false);

        final double agbZero = op.invertAGB(0.0);
        assertEquals("AGB at zero HV", 0.0, agbZero, 1.0e-9);

        // Very large HV should approach coefA
        final double agbLarge = op.invertAGB(1.0e6);
        assertEquals("AGB saturates to a", 31.6, agbLarge, 1.0e-6);
    }

    /**
     * Saatchi 2011 power law: AGB = a * s^b. Verify monotonic, zero at zero.
     */
    @Test
    public void saatchi_power_law_monotonic() {
        final HVIntensityAGBOp op = new HVIntensityAGBOp();
        setField(op, "model", HVIntensityAGBOp.MODEL_SAATCHI_POWER);
        setField(op, "coefA", 30.0);
        setField(op, "coefB", 0.5);
        setField(op, "inputIsDb", false);

        final double a1 = op.invertAGB(0.01);
        final double a2 = op.invertAGB(0.1);
        final double a3 = op.invertAGB(1.0);

        assertTrue("monotonic increasing: a1 < a2", a1 < a2);
        assertTrue("monotonic increasing: a2 < a3", a2 < a3);
        // a * s^b at s=1 -> a
        assertEquals(30.0, a3, 1.0e-9);
    }

    /**
     * Decibel exponential: AGB = exp(a + b * s_dB). Verify shape.
     */
    @Test
    public void db_exponential_increases_with_db() {
        final HVIntensityAGBOp op = new HVIntensityAGBOp();
        setField(op, "model", HVIntensityAGBOp.MODEL_DB_EXP);
        setField(op, "coefA", 3.0);
        setField(op, "coefB", 0.1);
        setField(op, "inputIsDb", true);

        final double a1 = op.invertAGB(-20.0);
        final double a2 = op.invertAGB(-15.0);
        final double a3 = op.invertAGB(-10.0);

        assertTrue("AGB grows with HV in dB: a1 < a2", a1 < a2);
        assertTrue("AGB grows with HV in dB: a2 < a3", a2 < a3);
        // At s_dB = -a/b -> exp(0) = 1
        final double aZero = op.invertAGB(-3.0 / 0.1);
        assertEquals(1.0, aZero, 1.0e-9);
    }

    /**
     * Verify dB->linear conversion is internally consistent: feeding the same
     * physical signal in dB or linear should produce the same AGB
     * (Mitchard model).
     */
    @Test
    public void db_and_linear_inputs_agree_for_mitchard() {
        final double sigmaLin = 0.05;
        final double sigmaDb = 10.0 * Math.log10(sigmaLin);

        final HVIntensityAGBOp opLin = new HVIntensityAGBOp();
        setField(opLin, "model", HVIntensityAGBOp.MODEL_MITCHARD_EXP);
        setField(opLin, "coefA", 31.6);
        setField(opLin, "coefB", 0.069);
        setField(opLin, "inputIsDb", false);

        final HVIntensityAGBOp opDb = new HVIntensityAGBOp();
        setField(opDb, "model", HVIntensityAGBOp.MODEL_MITCHARD_EXP);
        setField(opDb, "coefA", 31.6);
        setField(opDb, "coefB", 0.069);
        setField(opDb, "inputIsDb", true);

        final double a1 = opLin.invertAGB(sigmaLin);
        final double a2 = opDb.invertAGB(sigmaDb);
        assertEquals("dB <-> linear should agree", a1, a2, 1.0e-9);
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
