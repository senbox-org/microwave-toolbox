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
package org.csa.rstb.soilmoisture.gpf.support;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Unit tests for {@link DielectricModelFactory}.
 */
public class TestDielectricModelFactory {

    @Test
    public void testModelNameConstants() {
        // Pin the public string constants — they feed the SM operator UI and saved graphs.
        assertEquals("Hallikainen", DielectricModelFactory.HALLIKAINEN);
        assertEquals("Mironov", DielectricModelFactory.MIRONOV);
    }

    @Test
    public void testConstantsAreNotNullOrEmpty() {
        assertNotNull(DielectricModelFactory.HALLIKAINEN);
        assertNotNull(DielectricModelFactory.MIRONOV);
    }

    @Test
    public void testUnknownModelNameReturnsNull() {
        // The factory silently returns null for unsupported model names (see default case).
        final DielectricModel m = DielectricModelFactory.createDielectricModel(
                null, null, null, -999.0, 0.0, 0.5, null, null, "rdc", "NonExistentModel");
        assertNull(m);
    }

    @Test
    public void testEmptyModelNameReturnsNull() {
        final DielectricModel m = DielectricModelFactory.createDielectricModel(
                null, null, null, -999.0, 0.0, 0.5, null, null, "rdc", "");
        assertNull(m);
    }
}
