/*
 * Copyright (C) 2021 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.orbits.io.sentinel1;

import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for SentinelPODOrbitFile that don't require test data files.
 */
public class TestSentinelPODOrbitFileUnit {

    @Test
    public void testOrbitTypeConstants() {
        assertEquals("Sentinel Restituted", SentinelPODOrbitFile.RESTITUTED);
        assertEquals("Sentinel Precise", SentinelPODOrbitFile.PRECISE);
        assertNotEquals(SentinelPODOrbitFile.RESTITUTED, SentinelPODOrbitFile.PRECISE);
    }

    @Test
    public void testGetAvailableOrbitTypes() {
        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(null);
        final SentinelPODOrbitFile podOrbitFile = new SentinelPODOrbitFile(absRoot, 3);

        String[] types = podOrbitFile.getAvailableOrbitTypes();
        assertNotNull(types);
        assertEquals(2, types.length);
        assertEquals(SentinelPODOrbitFile.PRECISE, types[0]);
        assertEquals(SentinelPODOrbitFile.RESTITUTED, types[1]);
    }

    @Test
    public void testGetOrbitFileBeforeRetrieve() {
        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(null);
        final SentinelPODOrbitFile podOrbitFile = new SentinelPODOrbitFile(absRoot, 3);

        assertNull(podOrbitFile.getOrbitFile());
    }

    @Test
    public void testGetVersionBeforeRetrieve() {
        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(null);
        final SentinelPODOrbitFile podOrbitFile = new SentinelPODOrbitFile(absRoot, 3);

        assertNull(podOrbitFile.getVersion());
    }

    @Test
    public void testOrbitFileImplementsInterface() {
        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(null);
        final SentinelPODOrbitFile podOrbitFile = new SentinelPODOrbitFile(absRoot, 3);

        assertTrue(podOrbitFile instanceof eu.esa.sar.orbits.io.OrbitFile);
    }

    @Test
    public void testDifferentPolyDegrees() {
        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(null);

        // Verify different polynomial degrees don't cause issues
        SentinelPODOrbitFile pod3 = new SentinelPODOrbitFile(absRoot, 3);
        SentinelPODOrbitFile pod5 = new SentinelPODOrbitFile(absRoot, 5);

        assertNotNull(pod3.getAvailableOrbitTypes());
        assertNotNull(pod5.getAvailableOrbitTypes());
    }
}
