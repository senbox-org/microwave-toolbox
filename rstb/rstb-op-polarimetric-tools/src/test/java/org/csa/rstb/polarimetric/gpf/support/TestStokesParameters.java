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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link StokesParameters}.
 */
public class TestStokesParameters {

    // ---------- computeDegreeOfPolarization ----------

    @Test
    public void testDegreeOfPolarizationFullyPolarized() {
        // g1^2 + g2^2 + g3^2 = g0^2 → DoP = 1
        final double[] g = { 1.0, 1.0, 0.0, 0.0 };
        assertEquals(1.0, StokesParameters.computeDegreeOfPolarization(g), 1e-12);
    }

    @Test
    public void testDegreeOfPolarizationUnpolarized() {
        // Only g0, rest zero → DoP = 0
        final double[] g = { 4.0, 0.0, 0.0, 0.0 };
        assertEquals(0.0, StokesParameters.computeDegreeOfPolarization(g), 1e-12);
    }

    @Test
    public void testDegreeOfPolarizationHalf() {
        // sqrt(0.25+0.25+0.5) / 2 = 1 / 2
        final double[] g = { 2.0, 0.5, 0.5, Math.sqrt(0.5) };
        assertEquals(0.5, StokesParameters.computeDegreeOfPolarization(g), 1e-12);
    }

    @Test
    public void testDegreeOfPolarizationZeroTotalIntensityReturnsMinusOne() {
        final double[] g = { 0.0, 0.0, 0.0, 0.0 };
        assertEquals(-1.0, StokesParameters.computeDegreeOfPolarization(g), 0.0);
    }

    // ---------- computeCompactPolStokesVector from C2 matrix ----------

    @Test
    public void testComputeStokesVectorFromCovarianceMatrixIdentity() {
        final double[][] cr = { { 1.0, 0.0 }, { 0.0, 1.0 } };
        final double[][] ci = new double[2][2];
        final double[] g = new double[4];

        StokesParameters.computeCompactPolStokesVector(cr, ci, g);

        // Identity: I = C11 + C22 = 2; Q = 0; U = 0; V = 0
        assertEquals(2.0, g[0], 1e-12);
        assertEquals(0.0, g[1], 1e-12);
        assertEquals(0.0, g[2], 1e-12);
        assertEquals(0.0, g[3], 1e-12);
    }

    @Test
    public void testComputeStokesVectorFromCovarianceMatrixDiagonal() {
        final double[][] cr = { { 3.0, 0.0 }, { 0.0, 1.0 } };
        final double[][] ci = new double[2][2];
        final double[] g = new double[4];

        StokesParameters.computeCompactPolStokesVector(cr, ci, g);

        assertEquals(4.0, g[0], 1e-12);  // C11 + C22
        assertEquals(2.0, g[1], 1e-12);  // C11 - C22
    }

    @Test
    public void testComputeStokesVectorFromCovarianceMatrixOffDiagonalRealAndImag() {
        final double[][] cr = { { 1.0, 0.5 }, { 0.5, 1.0 } };
        final double[][] ci = { { 0.0, 0.25 }, { -0.25, 0.0 } };
        final double[] g = new double[4];

        StokesParameters.computeCompactPolStokesVector(cr, ci, g);

        assertEquals(2.0, g[0], 1e-12);
        assertEquals(0.0, g[1], 1e-12);
        assertEquals(1.0, g[2], 1e-12);   // 2 * Cr[0][1]
        assertEquals(0.5, g[3], 1e-12);   // 2 * Ci[0][1]
    }

    // ---------- computeCompactPolStokesVector from scatter vector ----------

    @Test
    public void testComputeStokesVectorFromScatterVectorRealOnly() {
        // k = [1, 0] → |k0|^2 = 1, |k1|^2 = 0 → I=1, Q=1, U=0, V=0
        final double[] kr = { 1.0, 0.0 };
        final double[] ki = new double[2];
        final double[] g = new double[4];

        StokesParameters.computeCompactPolStokesVector(kr, ki, g);
        assertEquals(1.0, g[0], 1e-12);
        assertEquals(1.0, g[1], 1e-12);
        assertEquals(0.0, g[2], 1e-12);
        assertEquals(0.0, g[3], 1e-12);
    }

    @Test
    public void testComputeStokesVectorFromScatterVectorEqualRealComponents() {
        // k = [1, 1] → I=2, Q=0, U=2, V=0
        final double[] kr = { 1.0, 1.0 };
        final double[] ki = new double[2];
        final double[] g = new double[4];

        StokesParameters.computeCompactPolStokesVector(kr, ki, g);
        assertEquals(2.0, g[0], 1e-12);
        assertEquals(0.0, g[1], 1e-12);
        assertEquals(2.0, g[2], 1e-12);
        assertEquals(0.0, g[3], 1e-12);
    }

    // ---------- computeStokesParameters high-level ----------

    @Test
    public void testComputeStokesParametersFullyPolarizedRCH() {
        // Fully polarised circular: g = {I, 0, 0, -I}  (RCH)
        final double i = 1.0;
        final double[] g = { i, 0.0, 0.0, -i };
        final StokesParameters p = StokesParameters.computeStokesParameters(
                g, CompactPolProcessor.rch);

        assertNotNull(p);
        assertEquals(1.0, p.DegreeOfPolarization, 1e-12);
        assertEquals(0.0, p.DegreeOfDepolarization, 1e-12);
    }

    @Test
    public void testComputeStokesParametersFullyPolarizedLCH() {
        final double i = 1.0;
        final double[] g = { i, 0.0, 0.0, i };
        final StokesParameters p = StokesParameters.computeStokesParameters(
                g, CompactPolProcessor.lch);

        assertNotNull(p);
        assertEquals(1.0, p.DegreeOfPolarization, 1e-12);
    }

    @Test
    public void testComputeStokesParametersDegreeOfPolarizationFromStokes() {
        // Partially polarised: I=2, Q=1, U=0, V=0 → DoP = 1/2
        final double[] g = { 2.0, 1.0, 0.0, 0.0 };
        final StokesParameters p = StokesParameters.computeStokesParameters(
                g, CompactPolProcessor.rch);
        assertEquals(0.5, p.DegreeOfPolarization, 1e-12);
        assertTrue("depolarization must be 1 - DoP", p.DegreeOfDepolarization > 0.0);
    }
}
