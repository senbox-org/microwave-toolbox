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

public class TestWCMSoilVegetationOp {

    private static final OperatorSpi spi = new WCMSoilVegetationOp.Spi();

    @Test
    public void spi_creates_operator() {
        final WCMSoilVegetationOp op = (WCMSoilVegetationOp) spi.createOperator();
        assertNotNull(op);
    }

    @Test
    public void operator_metadata_alias_and_category() {
        final OperatorMetadata md = WCMSoilVegetationOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("WCM-Soil-Vegetation-Decoupling", md.alias());
        assertEquals("Radar/Biomass", md.category());
    }

    /**
     * Round-trip: forward-compute sigma0_obs from a known V, then invert and
     * verify V recovery to within 1e-3 kg/m^2.
     */
    @Test
    public void round_trip_for_known_V() {
        final double A = 0.0014;
        final double B = 0.084;
        final double theta = 30.0;
        final double cosT = Math.cos(Math.toRadians(theta));
        final double sigma0Soil = 0.05;

        for (double vTrue : new double[]{0.5, 1.0, 2.0, 4.0, 6.0}) {
            final double tau2 = Math.exp(-2.0 * B * vTrue / cosT);
            final double sigma0Obs = A * vTrue * cosT * (1.0 - tau2) + tau2 * sigma0Soil;

            final WCMSoilVegetationOp.Result r = new WCMSoilVegetationOp.Result();
            WCMSoilVegetationOp.invertWCM(sigma0Obs, sigma0Soil, theta, A, B, 8.0, r);

            assertEquals("V recovery for V=" + vTrue, vTrue, r.V, 1.0e-3);

            // Conservation: vegBackscatter + soilAttenuated should reconstruct sigma0_obs
            assertEquals("backscatter conservation",
                    sigma0Obs, r.vegBackscatter + r.soilAttenuated, 1.0e-6);
        }
    }

    /**
     * At V = 0, sigma0_obs = sigma0_soil. The inverter should return V = 0
     * exactly (or within bisection tolerance).
     */
    @Test
    public void zero_vegetation_recovered_for_soil_only_signal() {
        final WCMSoilVegetationOp.Result r = new WCMSoilVegetationOp.Result();
        WCMSoilVegetationOp.invertWCM(0.05, 0.05, 30.0, 0.0014, 0.084, 8.0, r);
        assertTrue("V close to 0", r.V < 1.0e-3);
    }

    /**
     * sigma0_obs out of bracket -> no inversion (out.V stays at NO_DATA).
     */
    @Test
    public void out_of_bracket_returns_no_data() {
        final WCMSoilVegetationOp.Result r = new WCMSoilVegetationOp.Result();
        // sigma0_obs = 1.0 (very high; well beyond fHi at vMax)
        WCMSoilVegetationOp.invertWCM(1.0, 0.05, 30.0, 0.0014, 0.084, 8.0, r);
        assertTrue("out-of-bracket should not produce a positive V", r.V < 0.0);
    }

    /**
     * Vegetation backscatter should approach the asymptote
     * A * V_inf * cos(theta) as canopy opacity saturates. With L-band-style
     * extinction (B large) this is achievable at V ~ 8; with C-band B=0.084
     * we choose V=20 to push tau^2 below 5%.
     */
    @Test
    public void vegetation_backscatter_asymptote() {
        final double A = 0.0014;
        final double B = 0.084;
        final double theta = 30.0;
        final double cosT = Math.cos(Math.toRadians(theta));

        final double vInf = 20.0;
        final double tau2 = Math.exp(-2.0 * B * vInf / cosT);
        assertTrue("tau^2 small at high V (tau2=" + tau2 + ")", tau2 < 0.05);

        final double vegBs = A * vInf * cosT * (1.0 - tau2);
        final double asymptote = A * vInf * cosT;
        assertTrue("veg backscatter approaches asymptote",
                Math.abs(vegBs - asymptote) / asymptote < 0.05);
    }

    /**
     * Invalid incidence (cos(theta) = 0 at theta = 90) is gracefully rejected.
     */
    @Test
    public void invalid_incidence_returns_no_data() {
        final WCMSoilVegetationOp.Result r = new WCMSoilVegetationOp.Result();
        WCMSoilVegetationOp.invertWCM(0.05, 0.05, 90.0, 0.0014, 0.084, 8.0, r);
        assertTrue(r.V < 0.0);
    }
}
