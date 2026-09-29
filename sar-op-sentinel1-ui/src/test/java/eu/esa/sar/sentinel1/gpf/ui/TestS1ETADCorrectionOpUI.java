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
package eu.esa.sar.sentinel1.gpf.ui;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * The two ETAD mode checkboxes used to clear each other, which made the combined mode — resample the
 * image AND remove the range-delay phase — unreachable from this dialog. That combination is what
 * the geocode-first (GSLC) InSAR chain needs, since both corrections then live in the pixels and
 * survive geocoding.
 * <p>
 * The defect was purely INTERACTIVE. {@code CreateOpTab} registers the item listeners *after*
 * {@code initParameters()} runs, so nothing fired on graph load and a saved graph with both flags
 * set loaded and ran correctly. Only a user click triggered the clearing. Tests must therefore drive
 * the checkboxes with {@code doClick()}; a paramMap round trip alone passes with the bug present and
 * proves nothing.
 * <p>
 * {@code appContext} is unused by this UI, so null is safe.
 */
public class TestS1ETADCorrectionOpUI {

    private static Map<String, Object> params(final boolean resamplingImage,
                                              final boolean outputPhaseCorrections) {
        final Map<String, Object> p = new HashMap<>();
        p.put("resamplingImage", resamplingImage);
        p.put("outputPhaseCorrections", outputPhaseCorrections);
        p.put("sumOfRangeCorrections", true);
        p.put("sumOfAzimuthCorrections", true);
        return p;
    }

    private static S1ETADCorrectionOpUI open(final Map<String, Object> in) {
        final S1ETADCorrectionOpUI ui = new S1ETADCorrectionOpUI();
        assertNotNull(ui.CreateOpTab("S1-ETAD-Correction", in, null));
        return ui;
    }

    private static Map<String, Object> roundTrip(final Map<String, Object> in) {
        open(in).updateParameters();
        return in;
    }

    /**
     * THE regression guard: tick both boxes as a user would. Under the old mutual clearing, ticking
     * the phase box cleared the resampling box, so this combination could not be produced at all.
     */
    @Test
    public void userCanEnableBothCorrectionsTogether() {
        final Map<String, Object> in = params(true, false);   // start: geometry only
        final S1ETADCorrectionOpUI ui = open(in);

        // Ticking the phase box must NOT untick resampling.
        ui.outputPhaseCorrectionsCheckBox.doClick();

        assertEquals("ticking the phase box must not clear the resampling box",
                true, ui.resamplingImageCheckBox.isSelected());
        assertEquals(true, ui.outputPhaseCorrectionsCheckBox.isSelected());

        ui.updateParameters();
        assertEquals(Boolean.TRUE, in.get("resamplingImage"));
        assertEquals(Boolean.TRUE, in.get("outputPhaseCorrections"));
    }

    /** And the other click order must reach the same state. */
    @Test
    public void userCanEnableBothCorrectionsInEitherOrder() {
        final Map<String, Object> in = params(false, true);   // start: grids only
        final S1ETADCorrectionOpUI ui = open(in);

        // Ticking resampling must NOT untick the phase box.
        ui.resamplingImageCheckBox.doClick();

        assertEquals(true, ui.resamplingImageCheckBox.isSelected());
        assertEquals("ticking the resampling box must not clear the phase box",
                true, ui.outputPhaseCorrectionsCheckBox.isSelected());

        ui.updateParameters();
        assertEquals(Boolean.TRUE, in.get("resamplingImage"));
        assertEquals(Boolean.TRUE, in.get("outputPhaseCorrections"));
    }

    /**
     * In the combined mode the layer selections still drive the GEOMETRIC correction, so they must
     * not be wiped — {@code S1ETADCorrectionOp} rejects a resampling run with no layer selected.
     * The old code disabled and deselected all nine layer boxes whenever phase was ticked.
     */
    @Test
    public void combinedModeKeepsACorrectionLayerSelectedAfterClicking() {
        final Map<String, Object> in = params(true, false);
        final S1ETADCorrectionOpUI ui = open(in);

        ui.outputPhaseCorrectionsCheckBox.doClick();
        ui.updateParameters();

        final boolean anyLayer = Boolean.TRUE.equals(in.get("sumOfRangeCorrections"))
                || Boolean.TRUE.equals(in.get("sumOfAzimuthCorrections"));
        assertEquals("combined mode must keep a correction layer selected, or the operator throws "
                + "\"No correction layer is selected\"", true, anyLayer);
        assertEquals("the layer panel must stay usable in combined mode",
                true, ui.sumOfRangeCorrectionsCheckBox.isEnabled());
    }

    /** The resampling kernel must stay selectable in the mode that resamples the image. */
    @Test
    public void resamplingKernelStaysSelectableInCombinedMode() {
        final Map<String, Object> in = params(true, false);
        final S1ETADCorrectionOpUI ui = open(in);

        ui.outputPhaseCorrectionsCheckBox.doClick();

        assertEquals("resamplingType must remain enabled while the image is being resampled",
                true, ui.resamplingImageCheckBox.isSelected());
    }

    /** Unticking the phase box must not leave the invalid neither-correction state. */
    @Test
    public void untickingPhaseTurnsResamplingOn() {
        final Map<String, Object> in = params(false, true);   // grids only
        final S1ETADCorrectionOpUI ui = open(in);

        ui.outputPhaseCorrectionsCheckBox.doClick();          // untick phase

        assertEquals("neither correction is invalid; resampling must switch on",
                true, ui.resamplingImageCheckBox.isSelected());
    }

    /** Unticking resampling must force phase on, mirroring S1ETADCorrectionOp:202-204. */
    @Test
    public void untickingResamplingTurnsPhaseOn() {
        final Map<String, Object> in = params(true, false);   // geometry only
        final S1ETADCorrectionOpUI ui = open(in);

        ui.resamplingImageCheckBox.doClick();                 // untick resampling

        assertEquals(true, ui.outputPhaseCorrectionsCheckBox.isSelected());
    }

    @Test
    public void loadedParametersRoundTripUnchanged() {
        final Map<String, Object> out = roundTrip(params(true, true));

        final boolean anyLayer = Boolean.TRUE.equals(out.get("sumOfRangeCorrections"))
                || Boolean.TRUE.equals(out.get("sumOfAzimuthCorrections"))
                || Boolean.TRUE.equals(out.get("troposphericCorrectionRg"))
                || Boolean.TRUE.equals(out.get("ionosphericCorrectionRg"))
                || Boolean.TRUE.equals(out.get("geodeticCorrectionRg"))
                || Boolean.TRUE.equals(out.get("dopplerShiftCorrectionRg"))
                || Boolean.TRUE.equals(out.get("geodeticCorrectionAz"))
                || Boolean.TRUE.equals(out.get("bistaticShiftCorrectionAz"))
                || Boolean.TRUE.equals(out.get("fmMismatchCorrectionAz"));

        assertEquals("combined mode must keep a correction layer selected, or the operator throws "
                + "\"No correction layer is selected\"", true, anyLayer);
    }

    /** Geometry-only (the operator default) must round-trip unchanged. */
    @Test
    public void geometryOnlyModeSurvivesRoundTrip() {
        final Map<String, Object> out = roundTrip(params(true, false));

        assertEquals(Boolean.TRUE, out.get("resamplingImage"));
        assertEquals(Boolean.FALSE, out.get("outputPhaseCorrections"));
    }

    /**
     * Grids-only must round-trip, and phase corrections stay on — the operator forces that when the
     * image is not resampled, so the dialog must agree rather than submitting a state the operator
     * would rewrite.
     */
    @Test
    public void gridsOnlyModeSurvivesRoundTripAndKeepsPhaseOn() {
        final Map<String, Object> out = roundTrip(params(false, true));

        assertEquals(Boolean.FALSE, out.get("resamplingImage"));
        assertEquals(Boolean.TRUE, out.get("outputPhaseCorrections"));
    }

    /**
     * Loading a graph must not silently rewrite the user's saved layer choices. An earlier version of
     * the fix refreshed the panel at the end of initParameters in a way that cleared them in
     * grids-only mode, which updateParameters would then have written back as false.
     */
    @Test
    public void loadingGridsOnlyGraphDoesNotRewriteSavedLayerChoices() {
        final Map<String, Object> in = params(false, true);
        in.put("troposphericCorrectionRg", true);

        final Map<String, Object> out = roundTrip(in);

        assertEquals("a saved layer choice must not be silently cleared on load",
                Boolean.TRUE, out.get("troposphericCorrectionRg"));
    }
}
