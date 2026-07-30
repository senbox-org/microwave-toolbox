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
package eu.esa.sar.insar.gpf;

import org.esa.snap.core.datamodel.MetadataElement;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the ETAD reference-to-secondary burst index mapping used by the classical (non-GSLC)
 * TOPS ETAD interferogram path.
 * <p>
 * These live in their own class deliberately. {@link TestInterferogramOp} has a class-level
 * {@code @Before} that {@code assumeTrue}s on {@code TestData.inputStackS1}, so every test in it
 * skips when that product is absent. The tests here need no product at all and must always run —
 * they guard a defect that shipped precisely because nothing exercised this code without a fixture.
 */
public class TestInterferogramEtadBurstMap {

    /**
     * Regression: the ETAD burst-index attribute names were left behind by the
     * master/slave -> reference/secondary rename.
     * <p>
     * {@code BackGeocodingOp.saveSecondaryBurstIndexArray} writes {@code reference_bursts} /
     * {@code secondary_bursts}, but {@code createRefSecBurstMap} read {@code master_bursts} /
     * {@code slave_bursts} — names nothing in the repository writes. Because the single-argument
     * {@link MetadataElement#getAttributeString(String)} throws {@code IllegalArgumentException}
     * for a missing attribute, and this map is rebuilt for every burst of every TOPS ETAD
     * interferogram, the classical TOPS ETAD path failed on its first tile.
     */
    @Test
    public void readsCurrentAttributeNames() {
        final MetadataElement elem = new MetadataElement("ETAD_Burst_Index_Array");
        // Exactly what BackGeocodingOp writes, trailing space included.
        elem.setAttributeString("reference_bursts", "3 4 5 ");
        elem.setAttributeString("secondary_bursts", "7 8 9 ");

        final Map<Integer, Integer> map = InterferogramOp.parseRefSecBurstMap(elem);

        assertEquals(3, map.size());
        assertEquals(Integer.valueOf(7), map.get(3));
        assertEquals(Integer.valueOf(8), map.get(4));
        assertEquals(Integer.valueOf(9), map.get(5));
    }

    /** Stacks written before the rename carry the old names and must keep working. */
    @Test
    public void acceptsLegacyAttributeNames() {
        final MetadataElement elem = new MetadataElement("ETAD_Burst_Index_Array");
        elem.setAttributeString("master_bursts", "1 2 ");
        elem.setAttributeString("slave_bursts", "4 5 ");

        final Map<Integer, Integer> map = InterferogramOp.parseRefSecBurstMap(elem);

        assertEquals(2, map.size());
        assertEquals(Integer.valueOf(4), map.get(1));
        assertEquals(Integer.valueOf(5), map.get(2));
    }

    /** Current names win when both are somehow present. */
    @Test
    public void currentNamesTakePrecedenceOverLegacy() {
        final MetadataElement elem = new MetadataElement("ETAD_Burst_Index_Array");
        elem.setAttributeString("reference_bursts", "1 ");
        elem.setAttributeString("secondary_bursts", "2 ");
        elem.setAttributeString("master_bursts", "8 ");
        elem.setAttributeString("slave_bursts", "9 ");

        final Map<Integer, Integer> map = InterferogramOp.parseRefSecBurstMap(elem);

        assertEquals(1, map.size());
        assertEquals(Integer.valueOf(2), map.get(1));
    }

    /**
     * Absent or blank attributes must yield an empty map rather than throwing.
     * <p>
     * Note a whitespace-only value is used rather than the empty string:
     * {@code ProductData.ASCII.setElems} rejects a zero-length value
     * ({@code ProductData.java:2858-2869}), so an empty attribute cannot be constructed through
     * {@code setAttributeString} at all. That is itself worth knowing —
     * {@code BackGeocodingOp.saveSecondaryBurstIndexArray} builds {@code ""} when every burst index
     * is -1 and would throw on the write. Separate latent defect, not addressed here; the guard
     * below protects the read side either way.
     */
    @Test
    public void handlesMissingAndBlank() {
        assertNotNull(InterferogramOp.parseRefSecBurstMap(null));
        assertTrue(InterferogramOp.parseRefSecBurstMap(null).isEmpty());

        assertTrue(InterferogramOp.parseRefSecBurstMap(
                new MetadataElement("ETAD_Burst_Index_Array")).isEmpty());

        final MetadataElement blank = new MetadataElement("ETAD_Burst_Index_Array");
        blank.setAttributeString("reference_bursts", " ");
        blank.setAttributeString("secondary_bursts", " ");
        assertTrue(InterferogramOp.parseRefSecBurstMap(blank).isEmpty());
    }

    /** Mismatched counts must not throw; pair up what is pairable and warn. */
    @Test
    public void handlesMismatchedCounts() {
        final MetadataElement ragged = new MetadataElement("ETAD_Burst_Index_Array");
        ragged.setAttributeString("reference_bursts", "1 2 3 ");
        ragged.setAttributeString("secondary_bursts", "9 ");

        final Map<Integer, Integer> map = InterferogramOp.parseRefSecBurstMap(ragged);

        assertEquals(1, map.size());
        assertEquals(Integer.valueOf(9), map.get(1));
    }

    /** Multiple spaces and no trailing space must both parse. */
    @Test
    public void toleratesIrregularWhitespace() {
        final MetadataElement elem = new MetadataElement("ETAD_Burst_Index_Array");
        elem.setAttributeString("reference_bursts", "10  11");
        elem.setAttributeString("secondary_bursts", "20  21");

        final Map<Integer, Integer> map = InterferogramOp.parseRefSecBurstMap(elem);

        assertEquals(2, map.size());
        assertEquals(Integer.valueOf(20), map.get(10));
        assertEquals(Integer.valueOf(21), map.get(11));
    }
}
