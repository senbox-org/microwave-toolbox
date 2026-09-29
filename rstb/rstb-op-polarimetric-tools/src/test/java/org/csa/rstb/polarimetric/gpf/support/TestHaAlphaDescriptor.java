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

/**
 * Unit tests for {@link HaAlphaDescriptor}.
 * Each test picks an entropy/alpha pair well inside a single zone and pins the
 * returned zone index for both the Lee and PolSARPro plane definitions.
 */
public class TestHaAlphaDescriptor {

    // ---------- Constants ----------

    @Test
    public void testAlphaBoundaryConstants() {
        assertEquals(55.0, HaAlphaDescriptor.Alpha1, 0.0);
        assertEquals(50.0, HaAlphaDescriptor.Alpha2, 0.0);
        assertEquals(48.0, HaAlphaDescriptor.Alpha3, 0.0);
        assertEquals(42.0, HaAlphaDescriptor.Alpha4, 0.0);
        assertEquals(40.0, HaAlphaDescriptor.Alpha5, 0.0);
    }

    @Test
    public void testEntropyBoundaryConstants() {
        assertEquals(0.9, HaAlphaDescriptor.H1, 0.0);
        assertEquals(0.5, HaAlphaDescriptor.H2, 0.0);
    }

    // ---------- Lee H-Alpha plane ----------

    @Test
    public void testLeeZone1HighEntropyHighAlpha() {
        // H > 0.9 and alpha > 55
        assertEquals(1, HaAlphaDescriptor.getZoneIndex(0.95, 60.0, true));
    }

    @Test
    public void testLeeZone2HighEntropyMediumAlpha() {
        // H > 0.9, 40 < alpha <= 55
        assertEquals(2, HaAlphaDescriptor.getZoneIndex(0.95, 45.0, true));
    }

    @Test
    public void testLeeZone3HighEntropyLowAlpha() {
        // H > 0.9, alpha <= 40
        assertEquals(3, HaAlphaDescriptor.getZoneIndex(0.95, 30.0, true));
    }

    @Test
    public void testLeeZone4MediumEntropyHighAlpha() {
        // 0.5 < H <= 0.9, alpha > 50
        assertEquals(4, HaAlphaDescriptor.getZoneIndex(0.7, 55.0, true));
    }

    @Test
    public void testLeeZone5MediumEntropyMediumAlpha() {
        // 0.5 < H <= 0.9, 40 < alpha <= 50
        assertEquals(5, HaAlphaDescriptor.getZoneIndex(0.7, 45.0, true));
    }

    @Test
    public void testLeeZone6MediumEntropyLowAlpha() {
        // 0.5 < H <= 0.9, alpha <= 40
        assertEquals(6, HaAlphaDescriptor.getZoneIndex(0.7, 30.0, true));
    }

    @Test
    public void testLeeZone7LowEntropyHighAlpha() {
        // H <= 0.5, alpha > 48
        assertEquals(7, HaAlphaDescriptor.getZoneIndex(0.3, 55.0, true));
    }

    @Test
    public void testLeeZone8LowEntropyMediumAlpha() {
        // H <= 0.5, 42 < alpha <= 48
        assertEquals(8, HaAlphaDescriptor.getZoneIndex(0.3, 45.0, true));
    }

    @Test
    public void testLeeZone9LowEntropyLowAlpha() {
        // H <= 0.5, alpha <= 42
        assertEquals(9, HaAlphaDescriptor.getZoneIndex(0.3, 20.0, true));
    }

    // ---------- PolSARPro H-Alpha plane ----------

    @Test
    public void testPolSARProZone7HighEntropyHighAlpha() {
        assertEquals(7, HaAlphaDescriptor.getZoneIndex(0.95, 60.0, false));
    }

    @Test
    public void testPolSARProZone8HighEntropyMediumAlpha() {
        assertEquals(8, HaAlphaDescriptor.getZoneIndex(0.95, 45.0, false));
    }

    @Test
    public void testPolSARProZone9HighEntropyLowAlpha() {
        assertEquals(9, HaAlphaDescriptor.getZoneIndex(0.95, 30.0, false));
    }

    @Test
    public void testPolSARProZone4MediumEntropyHighAlpha() {
        assertEquals(4, HaAlphaDescriptor.getZoneIndex(0.7, 55.0, false));
    }

    @Test
    public void testPolSARProZone1LowEntropyHighAlpha() {
        assertEquals(1, HaAlphaDescriptor.getZoneIndex(0.3, 55.0, false));
    }

    @Test
    public void testPolSARProZone2LowEntropyMediumAlpha() {
        assertEquals(2, HaAlphaDescriptor.getZoneIndex(0.3, 45.0, false));
    }

    @Test
    public void testPolSARProZone3LowEntropyLowAlpha() {
        assertEquals(3, HaAlphaDescriptor.getZoneIndex(0.3, 20.0, false));
    }

    // ---------- Cross-check: all 9 zones reachable ----------

    @Test
    public void testAllLeeZonesReachable() {
        final double[][] samples = {
                { 0.95, 60.0 }, { 0.95, 45.0 }, { 0.95, 30.0 },
                { 0.70, 55.0 }, { 0.70, 45.0 }, { 0.70, 30.0 },
                { 0.30, 55.0 }, { 0.30, 45.0 }, { 0.30, 20.0 }
        };
        for (int i = 0; i < samples.length; i++) {
            final int zone = HaAlphaDescriptor.getZoneIndex(samples[i][0], samples[i][1], true);
            assertEquals("expected zone " + (i + 1) + " for sample " + i, i + 1, zone);
        }
    }
}
