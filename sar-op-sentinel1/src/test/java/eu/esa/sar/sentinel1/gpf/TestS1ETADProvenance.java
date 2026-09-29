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
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.junit.Test;

import org.esa.snap.core.gpf.OperatorException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * ETAD provenance must be READABLE after writing.
 * <p>
 * {@code AbstractMetadata.setAttribute(MetadataElement, String, int)} does NOT create a missing
 * attribute — it prints "&lt;tag&gt; not found in metadata" and returns
 * ({@code AbstractMetadata.java:527-535}). That is why the pre-existing
 * {@code etad_correction_flag} write in {@code S1ETADCorrectionOp.updateTargetProductMetadata()}
 * has always been a silent no-op: the attribute is not in the abstracted-metadata header, so every
 * ETAD run to date has printed that message and recorded nothing.
 * <p>
 * Every test here asserts a read-back, so that trap cannot reappear.
 * <p>
 * These tests are hermetic — no ETAD product, no fixture — and must never be gated behind an
 * {@code assumeTrue}.
 */
public class TestS1ETADProvenance {

    private static Product target() {
        final Product p = new Product("t", "SLC", 10, 10);
        AbstractMetadata.addAbstractedMetadataHeader(p.getMetadataRoot());
        return p;
    }

    private static MetadataElement abs(final Product p) {
        return AbstractMetadata.getAbstractedMetadata(p);
    }

    /** IW combined mode: geometry resampled in AND range-delay phase removed from the pixels. */
    @Test
    public void geometryAndPhaseBothAppliedIsRecorded() {
        final Product p = target();
        S1ETADCorrectionOp.writeETADProvenance(p, "S1B_IW_ETA__AXDV_X", true, true, true);

        assertEquals(1, abs(p).getAttributeInt(S1ETADCorrectionOp.ETAD_CORRECTION_APPLIED));
        assertEquals(1, abs(p).getAttributeInt(S1ETADCorrectionOp.ETAD_GEOMETRY_APPLIED));
        assertEquals(1, abs(p).getAttributeInt(S1ETADCorrectionOp.ETAD_PHASE_APPLIED));
        assertEquals("S1B_IW_ETA__AXDV_X",
                abs(p).getAttributeString(S1ETADCorrectionOp.ETAD_PRODUCT));
    }

    /** The operator default: resampling only, phase left in the data. */
    @Test
    public void geometryOnlyIsRecorded() {
        final Product p = target();
        S1ETADCorrectionOp.writeETADProvenance(p, "E", true, false, true);

        assertEquals(1, abs(p).getAttributeInt(S1ETADCorrectionOp.ETAD_CORRECTION_APPLIED));
        assertEquals(1, abs(p).getAttributeInt(S1ETADCorrectionOp.ETAD_GEOMETRY_APPLIED));
        assertEquals(0, abs(p).getAttributeInt(S1ETADCorrectionOp.ETAD_PHASE_APPLIED));
        assertEquals("E", abs(p).getAttributeString(S1ETADCorrectionOp.ETAD_PRODUCT));
    }

    /**
     * InSAR (grid) mode: corrections are emitted as tie-point grids and NOTHING is applied to the
     * pixels, even though the operator forces outputPhaseCorrections=true here
     * ({@code S1ETADCorrectionOp:202-204}).
     */
    @Test
    public void insarGridModeRecordsNeitherBakedIn() {
        final Product p = target();
        S1ETADCorrectionOp.writeETADProvenance(p, "E", false, true, true);

        assertEquals(1, abs(p).getAttributeInt(S1ETADCorrectionOp.ETAD_CORRECTION_APPLIED));
        assertEquals(0, abs(p).getAttributeInt(S1ETADCorrectionOp.ETAD_GEOMETRY_APPLIED));
        assertEquals(0, abs(p).getAttributeInt(S1ETADCorrectionOp.ETAD_PHASE_APPLIED));
        assertEquals("E", abs(p).getAttributeString(S1ETADCorrectionOp.ETAD_PRODUCT));
    }

    /** Regression for the silent no-op: the legacy flag must now actually be set. */
    @Test
    public void legacyEtadCorrectionFlagIsActuallyWritten() {
        final Product p = target();
        S1ETADCorrectionOp.writeETADProvenance(p, "E", true, true, true);

        assertEquals(1, abs(p).getAttributeInt("etad_correction_flag"));
    }

    /**
     * {@code MetadataElement.addAttribute} performs no name-uniqueness check, so duplicates
     * accumulate silently and {@code getAttribute} then returns only the first. Measure the count:
     * a fresh abstracted header already carries ~110 attributes, so asserting
     * {@code getNumAttributes() > 0} would count nothing.
     */
    @Test
    public void writingTwiceUpdatesInPlaceAndAddsNoAttributes() {
        final Product p = target();
        S1ETADCorrectionOp.writeETADProvenance(p, "E", true, true, true);
        final int afterFirst = abs(p).getNumAttributes();

        S1ETADCorrectionOp.writeETADProvenance(p, "E", true, false, true);

        assertEquals("second write must update in place, not append",
                afterFirst, abs(p).getNumAttributes());
        assertEquals("value must reflect the second write",
                0, abs(p).getAttributeInt(S1ETADCorrectionOp.ETAD_PHASE_APPLIED));
    }

    // --- azimuth provenance ---------------------------------------------------------------------
    // GSLCGeocodingOp suppresses its own bistatic azimuth residual on the strength of this flag.
    // Measured on a real S1B IW product, ETAD's bistaticCorrectionAz spans -0.1700 ms across the
    // sub-swath while the geocoder's (rFar-rNear)/c residual spans 0.1687 ms - the same quantity to
    // within 0.8%. Getting this flag wrong therefore either double-corrects or under-corrects the
    // absolute geolocation, so each combination is pinned.

    @Test
    public void azimuthAppliedWhenResamplingWithAzimuthLayers() {
        final Product p = target();
        S1ETADCorrectionOp.writeETADProvenance(p, "E", true, false, true);

        assertEquals(1, abs(p).getAttributeInt(S1ETADCorrectionOp.ETAD_AZIMUTH_APPLIED));
    }

    @Test
    public void azimuthNotAppliedWhenNoAzimuthLayerSelected() {
        final Product p = target();
        S1ETADCorrectionOp.writeETADProvenance(p, "E", true, false, false);

        assertEquals(0, abs(p).getAttributeInt(S1ETADCorrectionOp.ETAD_AZIMUTH_APPLIED));
    }

    /**
     * Grids-only mode moves no pixels, so an azimuth layer selection changes nothing and must NOT
     * cause the geocoder to suppress its own bistatic residual.
     */
    @Test
    public void azimuthNotAppliedInGridsOnlyModeEvenIfSelected() {
        final Product p = target();
        S1ETADCorrectionOp.writeETADProvenance(p, "E", false, true, true);

        assertEquals(0, abs(p).getAttributeInt(S1ETADCorrectionOp.ETAD_AZIMUTH_APPLIED));
    }

    /** A null ETAD product name must not prevent the flags being recorded. */
    @Test
    public void nullEtadProductNameStillRecordsFlags() {
        final Product p = target();
        S1ETADCorrectionOp.writeETADProvenance(p, null, true, true, true);

        assertEquals(1, abs(p).getAttributeInt(S1ETADCorrectionOp.ETAD_PHASE_APPLIED));
    }

    // --- combined-mode guard -------------------------------------------------------------------
    // The guard is what makes etad_phase_applied sound: it ensures that reaching
    // writeETADProvenance with both flags true implies TOPS, the only corrector that bakes the
    // phase into the pixels.

    /** The IW combined mode is the configuration the geocode-first chain needs. Must be allowed. */
    @Test
    public void combinedModeAllowedForIW() {
        S1ETADCorrectionOp.checkCombinedModeSupported(true, true, "IW");
    }

    /**
     * SMCorrector dispatches its target product on outputPhaseCorrections but its tiles on
     * resamplingImage and never bakes phase into pixels, so the combined mode would fail on a
     * computed-band tile pull. Reject it with a message that names the mode and both alternatives.
     */
    @Test
    public void combinedModeRejectedForStripmap() {
        try {
            S1ETADCorrectionOp.checkCombinedModeSupported(true, true, "SM");
            fail("expected OperatorException for the stripmap combined mode");
        } catch (OperatorException e) {
            final String m = e.getMessage();
            assertTrue("must name the supported mode: " + m, m.contains("IW"));
            assertTrue("must name the rejected mode: " + m, m.contains("SM"));
        }
    }

    /** Each single-correction mode remains available for every acquisition mode. */
    @Test
    public void singleCorrectionModesAllowedForStripmap() {
        S1ETADCorrectionOp.checkCombinedModeSupported(true, false, "SM");   // geometry only
        S1ETADCorrectionOp.checkCombinedModeSupported(false, true, "SM");   // grids only
    }

    /** A null acquisition mode must be rejected rather than slipping through as "IW". */
    @Test
    public void combinedModeRejectedForUnknownAcquisitionMode() {
        try {
            S1ETADCorrectionOp.checkCombinedModeSupported(true, true, null);
            fail("expected OperatorException for an unknown acquisition mode");
        } catch (OperatorException e) {
            assertTrue(e.getMessage().contains("IW"));
        }
    }
}
