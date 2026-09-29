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
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A GSLC stack must not mix ETAD-corrected and uncorrected acquisitions.
 * <p>
 * The classical path has a real fail-safe: {@code subtractETADPhase = hasRefETADPhaseTPG &
 * hasSecETADPhaseTPG} — a logical AND, so a one-sided pair gets no correction at all and the
 * interferogram is uncorrected but internally consistent. The geocode-first chain cannot degrade that
 * way, because the correction is baked into the complex data per product before the pair exists and
 * cannot be undone.
 * <p>
 * If only one acquisition was ETAD-corrected, the interferogram retains an uncompensated
 * range-delay phase of order tens of radians — smooth, spatially correlated, and indistinguishable
 * from deformation. Nothing downstream can detect it from the data, so it has to be caught from the
 * provenance.
 * <p>
 * Hermetic: the decision is a pure function of the reference state and the secondary metadata tree.
 */
public class TestInterferogramEtadSymmetry {

    private static final String ATTR = "etad_phase_applied";

    private static MetadataElement secondaryRoot(final Object... nameStatePairs) {
        final MetadataElement root = new MetadataElement("Secondary_Metadata");
        for (int i = 0; i < nameStatePairs.length; i += 2) {
            final MetadataElement sec = new MetadataElement((String) nameStatePairs[i]);
            final Integer state = (Integer) nameStatePairs[i + 1];
            if (state != null) {
                sec.setAttributeInt(ATTR, state);
            }
            root.addElement(sec);
        }
        return root;
    }

    @Test
    public void bothCorrectedIsSymmetric() {
        assertTrue(InterferogramOp.findETADPhaseMismatches(1,
                secondaryRoot("sec_27Aug2020", 1)).isEmpty());
    }

    @Test
    public void neitherCorrectedIsSymmetric() {
        assertTrue(InterferogramOp.findETADPhaseMismatches(0,
                secondaryRoot("sec_27Aug2020", 0)).isEmpty());
    }

    /** The common case for a stack predating the provenance flag: no attribute anywhere. */
    @Test
    public void missingAttributeOnBothSidesIsSymmetric() {
        assertTrue("absent must read as 0 on both sides, not as a mismatch",
                InterferogramOp.findETADPhaseMismatches(0,
                        secondaryRoot("sec_27Aug2020", null)).isEmpty());
    }

    /** THE case this exists for: reference corrected, secondary not. */
    @Test
    public void referenceCorrectedSecondaryNotIsAMismatch() {
        final List<String> bad = InterferogramOp.findETADPhaseMismatches(1,
                secondaryRoot("sec_27Aug2020", 0));

        assertEquals(1, bad.size());
        assertTrue("must name the offending secondary: " + bad.get(0),
                bad.get(0).contains("sec_27Aug2020"));
    }

    /** And the reverse. */
    @Test
    public void secondaryCorrectedReferenceNotIsAMismatch() {
        assertEquals(1, InterferogramOp.findETADPhaseMismatches(0,
                secondaryRoot("sec_27Aug2020", 1)).size());
    }

    /** Multi-secondary stacks: report every offender, not just the first. */
    @Test
    public void reportsEveryMismatchedSecondary() {
        final List<String> bad = InterferogramOp.findETADPhaseMismatches(1,
                secondaryRoot("sec_a", 1, "sec_b", 0, "sec_c", 0));

        assertEquals(2, bad.size());
        assertTrue(bad.toString().contains("sec_b"));
        assertTrue(bad.toString().contains("sec_c"));
    }

    /** Original_Product_Metadata is not an acquisition and must be skipped. */
    @Test
    public void originalProductMetadataIsIgnored() {
        final MetadataElement root = secondaryRoot("sec_27Aug2020", 1);
        root.addElement(new MetadataElement(AbstractMetadata.ORIGINAL_PRODUCT_METADATA));

        assertTrue(InterferogramOp.findETADPhaseMismatches(1, root).isEmpty());
    }

    @Test
    public void nullSecondaryRootIsSafe() {
        assertTrue(InterferogramOp.findETADPhaseMismatches(1, null).isEmpty());
    }

    /** A non-numeric attribute must not blow up the check. */
    @Test
    public void nonNumericAttributeIsToleratedNotThrown() {
        final MetadataElement root = new MetadataElement("Secondary_Metadata");
        final MetadataElement sec = new MetadataElement("sec_odd");
        sec.setAttributeString(ATTR, "yes");
        root.addElement(sec);

        // Must not propagate NumberFormatException; treated as unknown, i.e. 0.
        final List<String> bad = InterferogramOp.findETADPhaseMismatches(1, root);
        assertEquals(1, bad.size());
    }
}
