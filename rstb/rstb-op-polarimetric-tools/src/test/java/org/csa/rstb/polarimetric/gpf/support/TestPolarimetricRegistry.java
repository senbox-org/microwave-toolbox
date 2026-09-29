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

import org.csa.rstb.polarimetric.gpf.decompositions.Decomposition;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link PolarimetricRegistry}.
 */
public class TestPolarimetricRegistry {

    @Test
    public void testGetInstanceIsSingleton() {
        final PolarimetricRegistry a = PolarimetricRegistry.getInstance();
        final PolarimetricRegistry b = PolarimetricRegistry.getInstance();
        assertNotNull(a);
        assertSame(a, b);
    }

    @Test
    public void testGetAllDecompositionsNotNull() {
        // The registry may return an empty array when no decompositions are
        // declared as service providers — we just verify the call succeeds.
        final Decomposition[] decompositions = PolarimetricRegistry.getInstance().getAllDecompositions();
        assertNotNull(decompositions);
    }

    @Test
    public void testGetAllSpeckleFiltersNotNull() {
        final PolarimetricSpeckleFilter[] filters = PolarimetricRegistry.getInstance().getAllSpeckleFilters();
        assertNotNull(filters);
    }

    @Test
    public void testGetDecompositionNullNameThrows() {
        try {
            PolarimetricRegistry.getInstance().getDecomposition(null);
            fail("Expected exception for null decomposition name");
        } catch (IllegalArgumentException | NullPointerException expected) {
            // Guardian throws IllegalArgumentException for null
        }
    }

    @Test
    public void testGetDecompositionEmptyNameThrows() {
        try {
            PolarimetricRegistry.getInstance().getDecomposition("");
            fail("Expected exception for empty decomposition name");
        } catch (IllegalArgumentException expected) {
            // Guardian.assertNotNullOrEmpty
        }
    }
}
