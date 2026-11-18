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
package org.csa.rstb.polarimetric.gpf.decompositions;

import eu.esa.sar.commons.polsar.PolBandUtils;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Closed-form checks of individual decomposition formulas (synthetic input, no test data).
 */
public class TestDecompositionFormulas {

    // ---- van Zyl (1989/1993): the two co-pol eigenvectors are k = [a, 0, 1] with
    // a = 2 rho / (zeta - 1 +- sqrt(Delta)). The (+) eigenvector (larger eigenvalue) has an HH/VV phase
    // equal to arg(rho); the (-) one is shifted by 180 deg. An in-phase eigenvector (Re(HH VV*) > 0) is an
    // odd-bounce (surface) mechanism, an anti-phase one is even-bounce (double bounce). So the larger
    // eigenvalue is surface only when Re(<S_HH S_VV*>) > 0.

    @Test
    public void testVanZylDihedralDominatedIsDoubleBounce() {
        // C11 = C33 = 1, C22 = 0.2, C13 = -0.8 (HH and VV in anti-phase).
        // zeta = 1, rho = -0.8, Delta = 4 * 0.64 = 2.56 -> lambda1 = 1.8, lambda2 = 0.2.
        final double[][] cr = {{1, 0, -0.8}, {0, 0.2, 0}, {-0.8, 0, 1}};
        final double[][] ci = new double[3][3];
        final vanZyl.VDD d = vanZyl.getVanZylDecomposition(cr, ci);
        assertEquals("double bounce", 1.8, d.pd, 1e-9);   // was 0.2
        assertEquals("surface", 0.2, d.ps, 1e-9);         // was 1.8
        assertEquals("volume", 0.2, d.pv, 1e-9);
    }

    @Test
    public void testVanZylSurfaceDominatedIsSurface() {
        final double[][] cr = {{1, 0, 0.8}, {0, 0.2, 0}, {0.8, 0, 1}};
        final double[][] ci = new double[3][3];
        final vanZyl.VDD d = vanZyl.getVanZylDecomposition(cr, ci);
        assertEquals("surface", 1.8, d.ps, 1e-9);
        assertEquals("double bounce", 0.2, d.pd, 1e-9);
    }

    // ---- Cameron & Leung (1990): tau_sym is the angle between S and its maximum symmetric component,
    // measured with the Hermitian inner product. A target with an axis of symmetry, S = R(psi) diag(a, b)
    // R(-psi), is its own symmetric component, so tau = 0 for any complex a, b.

    @Test
    public void testCameronTauZeroForSymmetricQuarterWave() {
        // S = diag(1, i): the symmetric component is S itself.
        final double[][] sr = {{1, 0}, {0, 0}};
        final double[][] si = {{0, 0}, {0, 1}};
        assertEquals(0.0, Cameron.getCameronDecomposition(sr, si).tau, 1e-6);  // was pi/2
    }

    @Test
    public void testCameronTauZeroForRotatedSymmetricTarget() {
        // S = R(20 deg) diag(1, 0.5 i) R(-20 deg)
        final double psi = Math.toRadians(20);
        final double c = Math.cos(psi), s = Math.sin(psi);
        // diag(a, b) rotated: [[a c^2 + b s^2, (b - a) c s], [(b - a) c s, a s^2 + b c^2]] with a = 1, b = 0.5 i
        final double[][] sr = {{c * c, -c * s}, {-c * s, s * s}};
        final double[][] si = {{0.5 * s * s, 0.5 * c * s}, {0.5 * c * s, 0.5 * c * c}};
        assertEquals(0.0, Cameron.getCameronDecomposition(sr, si).tau, 1e-6);  // was 53.13 deg
    }

    @Test
    public void testCameronTauFortyFiveDegForHelix() {
        // Left helix S = 1/2 [[1, i], [i, -1]]: tau_sym = pi/4.
        final double[][] sr = {{0.5, 0}, {0, -0.5}};
        final double[][] si = {{0, 0.5}, {0.5, 0}};
        assertEquals(Math.PI / 4, Cameron.getCameronDecomposition(sr, si).tau, 1e-6);
    }

    // ---- Krogager: ks, kd, kh are square roots of powers (linear amplitudes); the dB conversion is
    // commented out in the code, so the band unit must not be dB.

    @Test
    public void testKrogagerUnitIsLinearAmplitude() {
        final Krogager k = new Krogager(null, PolBandUtils.MATRIX.T3, 5, 10, 10);
        final Band band = new Band("kd_r", ProductData.TYPE_FLOAT32, 10, 10);
        k.setBandUnit("kd_r", band);
        assertEquals(Unit.AMPLITUDE, band.getUnit());
    }

    @Test
    public void testKrogagerSphereIsLinearAmplitude() {
        // Sphere S = diag(2, 2): T11 = |HH + VV|^2 / 2 = 8 -> ks = sqrt(T11 / 2) = 2 = |S_HH|.
        final double[][] tr = {{8, 0, 0}, {0, 0, 0}, {0, 0, 0}};
        final double[][] ti = new double[3][3];
        final Krogager.KDD d = Krogager.getKrogagerDecomposition(tr, ti);
        assertEquals(2.0, d.ks, 1e-12);
        assertEquals(0.0, d.kd, 1e-12);
        assertEquals(0.0, d.kh, 1e-12);
    }
}
