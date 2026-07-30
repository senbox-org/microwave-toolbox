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
package eu.esa.sar.sentinel1.gpf;

import org.esa.snap.core.datamodel.MetadataElement;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The ETAD burst-index bookkeeping written by {@code saveSecondaryBurstIndexArray}.
 * <p>
 * The crash this guards: when no secondary burst matches a reference burst, the emitted list is empty,
 * and {@code ProductData.ASCII.setElems} rejects a zero-length value — so
 * {@code setAttributeString("reference_bursts", "")} throws {@code IllegalArgumentException} and aborts
 * coregistration from inside what is purely a bookkeeping step. The element is now omitted instead,
 * which {@code InterferogramOp.parseRefSecBurstMap} already treats as "no mapping available".
 */
public class TestBackGeocodingBurstIndexArray {

    @Test
    public void emitsOnlyMatchedBurstsInTheHistoricalFormat() {
        final int[] ref = {3, 4, 5};
        final int[] sec = {7, 8, 9};

        assertEquals("3 4 5 ", BackGeocodingOp.joinMatchedBurstIndices(ref, ref));
        assertEquals("7 8 9 ", BackGeocodingOp.joinMatchedBurstIndices(sec, ref));
    }

    @Test
    public void unmatchedReferenceBurstsAreDroppedFromBothLists() {
        final int[] ref = {3, -1, 5};
        final int[] sec = {7, 8, 9};

        assertEquals("3 5 ", BackGeocodingOp.joinMatchedBurstIndices(ref, ref));
        assertEquals("the secondary list must drop the SAME positions, keeping the pairing",
                "7 9 ", BackGeocodingOp.joinMatchedBurstIndices(sec, ref));
    }

    /** THE crash case: nothing matched. Must yield empty, which the caller then refuses to write. */
    @Test
    public void allUnmatchedYieldsEmptyRatherThanThrowing() {
        final int[] ref = {-1, -1, -1};
        final int[] sec = {7, 8, 9};

        assertEquals("", BackGeocodingOp.joinMatchedBurstIndices(ref, ref));
        assertEquals("", BackGeocodingOp.joinMatchedBurstIndices(sec, ref));
    }

    /**
     * Demonstrates why the empty string must never be written — this is the exact call that used to
     * abort coregistration.
     */
    @Test
    public void writingAnEmptyAttributeValueThrows() {
        final MetadataElement elem = new MetadataElement("ETAD_Burst_Index_Array");
        try {
            elem.setAttributeString("reference_bursts", "");
            fail("expected ProductData.ASCII to reject a zero-length value");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage() != null);
        }
    }

    @Test
    public void mismatchedArrayLengthsDoNotOverrun() {
        final int[] ref = {3, 4, 5};
        final int[] sec = {7};

        assertEquals("pairs only what is pairable", "7 ",
                BackGeocodingOp.joinMatchedBurstIndices(sec, ref));
    }

    @Test
    public void nullArraysAreSafe() {
        assertEquals("", BackGeocodingOp.joinMatchedBurstIndices(null, new int[]{1}));
        assertEquals("", BackGeocodingOp.joinMatchedBurstIndices(new int[]{1}, null));
        assertEquals("", BackGeocodingOp.joinMatchedBurstIndices(null, null));
    }
}
