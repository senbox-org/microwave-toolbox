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
package eu.esa.sar.fex.gpf.oceantools;

import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Unit tests for {@link WindFieldEstimationOp}.
 */
public class TestWindFieldEstimationOp {

    @Test
    public void testSpiCreatesOperator() {
        final WindFieldEstimationOp op = (WindFieldEstimationOp) new WindFieldEstimationOp.Spi().createOperator();
        assertNotNull(op);
    }

    @Test
    public void testOperatorMetadata() {
        final OperatorMetadata md = WindFieldEstimationOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("Wind-Field-Estimation", md.alias());
        assertEquals("Radar/SAR Applications/Ocean", md.category());
    }

    private static double cmod5(double v, double cosFI, double incidenceDeg) throws Exception {
        Class<?> cmod5 = null;
        for (Class<?> c : WindFieldEstimationOp.class.getDeclaredClasses()) {
            if (c.getSimpleName().equals("CMOD5")) {
                cmod5 = c;
            }
        }
        assertNotNull(cmod5);
        final Method m = cmod5.getDeclaredMethod("compute", double.class, double.class, double.class);
        m.setAccessible(true);
        return (Double) m.invoke(null, v, cosFI, incidenceDeg);
    }

    /**
     * CMOD5 (Hersbach, Stoffelen and de Haan 2007, JGR 112 C03006, Table 1; ECMWF TM 395) evaluated
     * with the published coefficients, upwind (cos phi = 1). The expected values are the closed-form
     * GMF with c9..c11 = 6.34, 2.57, -2.18. The previous hybrid c9..c11 (6.7329, 2.7541, -2.2918)
     * gave sigma0 0.4-0.8 dB lower, i.e. about +0.6 m/s wind-speed bias on inversion.
     */
    @Test
    public void testCmod5MatchesPublishedCoefficients() throws Exception {
        assertEquals(5.825847e-02, cmod5(10.0, 1.0, 40.0), 1e-7);  // x = 0: gamma = c9
        assertEquals(1.574314e-01, cmod5(10.0, 1.0, 30.0), 1e-6);  // x = -0.4: c10, c11 matter
        assertEquals(4.412607e-01, cmod5(5.0, 1.0, 20.0), 1e-6);
        assertEquals(1.183246e-01, cmod5(15.0, 1.0, 40.0), 1e-6);
    }
}
