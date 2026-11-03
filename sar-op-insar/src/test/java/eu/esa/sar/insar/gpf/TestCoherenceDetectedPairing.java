/*
 * Copyright (C) 2026 SkyWatch Space Applications Inc. https://www.skywatch.com
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

import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.OperatorException;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * CoherenceOp's detected (non-complex) path: which reference band each secondary band is correlated
 * against, and what the coherence band is called.
 */
public class TestCoherenceDetectedPairing {

    /** CreateStack tags each detected band separately: a dual-pol secondary carries _sec1 and _sec2. */
    @Test
    public void testCreateStackDualPol_EachPolAgainstItsOwnReference() {
        final Product p = product("Amplitude_VV_ref_07Jan2025", "Amplitude_VH_ref_07Jan2025",
                "Amplitude_VV_sec1_04Feb2025", "Amplitude_VH_sec2_04Feb2025");

        final List<CoherenceOp.DetectedPair> pairs = CoherenceOp.pairDetectedBands(p);

        assertEquals(2, pairs.size());
        assertPair(pairs.get(0), "Coherence_sec1", "Amplitude_VV_ref_07Jan2025", "Amplitude_VV_sec1_04Feb2025");
        // used to be correlated against the first _ref band - VV
        assertPair(pairs.get(1), "Coherence_sec2", "Amplitude_VH_ref_07Jan2025", "Amplitude_VH_sec2_04Feb2025");
    }

    /** BackGeocoding / DEM-assisted tag per product: both pols share _sec1, and used to yield one band. */
    @Test
    public void testPerProductTags_OneCoherencePerPolWithDistinctNames() {
        final Product p = product("Amplitude_VV_ref_07Jan2025", "Amplitude_VH_ref_07Jan2025",
                "Amplitude_VV_sec1_04Feb2025", "Amplitude_VH_sec1_04Feb2025");

        final List<CoherenceOp.DetectedPair> pairs = CoherenceOp.pairDetectedBands(p);

        assertEquals(2, pairs.size());
        assertPair(pairs.get(0), "Coherence_Amplitude_VV_sec1", "Amplitude_VV_ref_07Jan2025", "Amplitude_VV_sec1_04Feb2025");
        assertPair(pairs.get(1), "Coherence_Amplitude_VH_sec1", "Amplitude_VH_ref_07Jan2025", "Amplitude_VH_sec1_04Feb2025");
    }

    /** The old loop stopped at the first missing sec index, silently dropping later secondaries. */
    @Test
    public void testGapInSecondaryNumbering_LaterSecondaryKept() {
        final Product p = product("Amplitude_VV_ref_07Jan2025",
                "Amplitude_VV_sec1_04Feb2025", "Amplitude_VV_sec3_04Mar2025");

        final List<CoherenceOp.DetectedPair> pairs = CoherenceOp.pairDetectedBands(p);

        assertEquals(2, pairs.size());
        assertPair(pairs.get(1), "Coherence_sec3", "Amplitude_VV_ref_07Jan2025", "Amplitude_VV_sec3_04Mar2025");
    }

    /** sec10 must not be taken for sec1 - the old "contains(\"sec\" + i)" lookup could. */
    @Test
    public void testTenOrMoreSecondaries_NamesDoNotAlias() {
        final String[] names = new String[11];
        names[0] = "Amplitude_VV_ref_01Jan2025";
        for (int i = 1; i <= 10; i++) {
            names[i] = "Amplitude_VV_sec" + (11 - i) + "_0" + (i % 9 + 1) + "Feb2025"; // sec10 listed first
        }
        final List<CoherenceOp.DetectedPair> pairs = CoherenceOp.pairDetectedBands(product(names));

        assertEquals(10, pairs.size());
        for (CoherenceOp.DetectedPair pair : pairs) {
            final String n = pair.coherenceBandName.substring("Coherence_sec".length());
            assertTrue(pair.secondary.getName() + " under " + pair.coherenceBandName,
                    pair.secondary.getName().startsWith("Amplitude_VV_sec" + n + "_"));
        }
    }

    /** Single reference band with a different name: kept paired, as before. */
    @Test
    public void testSingleReferenceWithDifferentName_StillPaired() {
        final Product p = product("Sigma0_VV_ref_07Jan2025", "Amplitude_VV_sec1_04Feb2025");

        final List<CoherenceOp.DetectedPair> pairs = CoherenceOp.pairDetectedBands(p);

        assertEquals(1, pairs.size());
        assertPair(pairs.get(0), "Coherence_sec1", "Sigma0_VV_ref_07Jan2025", "Amplitude_VV_sec1_04Feb2025");
    }

    /** Several reference bands and no name match: guessing would correlate unrelated bands. */
    @Test
    public void testAmbiguousReference_Rejected() {
        final Product p = product("Amplitude_VV_ref_07Jan2025", "Amplitude_VH_ref_07Jan2025",
                "Amplitude_HH_sec1_04Feb2025");
        try {
            CoherenceOp.pairDetectedBands(p);
            fail("an unmatched secondary among several reference bands must be rejected");
        } catch (OperatorException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Amplitude_HH_sec1_04Feb2025"));
        }
    }

    /** Non-stack bands (elevation, lat/lon) are neither reference nor secondary. */
    @Test
    public void testAuxiliaryBandsIgnored() {
        final Product p = product("Amplitude_VV_ref_07Jan2025", "elevation", "Amplitude_VV_sec1_04Feb2025");
        final List<CoherenceOp.DetectedPair> pairs = CoherenceOp.pairDetectedBands(p);
        assertEquals(1, pairs.size());
        assertPair(pairs.get(0), "Coherence_sec1", "Amplitude_VV_ref_07Jan2025", "Amplitude_VV_sec1_04Feb2025");
    }

    private static void assertPair(final CoherenceOp.DetectedPair pair, final String name,
                                   final String ref, final String sec) {
        assertEquals(name, pair.coherenceBandName);
        assertEquals(ref, pair.reference.getName());
        assertEquals(sec, pair.secondary.getName());
    }

    private static Product product(final String... bandNames) {
        final Product p = new Product("stack", "test", 4, 4);
        for (String name : bandNames) {
            p.addBand(name, ProductData.TYPE_FLOAT32);
        }
        return p;
    }
}
