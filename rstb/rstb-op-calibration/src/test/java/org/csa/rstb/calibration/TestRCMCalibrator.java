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
package org.csa.rstb.calibration;

import eu.esa.sar.calibration.gpf.support.Calibrator;
import eu.esa.sar.calibration.gpf.support.CalibratorRegistry;
import org.esa.snap.core.gpf.OperatorException;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link RCMCalibrator}.
 */
public class TestRCMCalibrator {

    @Test
    public void testDefaultConstructor() {
        final RCMCalibrator cal = new RCMCalibrator();
        assertNotNull(cal);
    }

    @Test
    public void testSupportedMissionsIsExactlyRCM() {
        final String[] missions = new RCMCalibrator().getSupportedMissions();
        assertNotNull(missions);
        assertArrayEquals("RCMCalibrator should declare exactly mission RCM",
                new String[] { "RCM" }, missions);
    }

    @Test
    public void testSetExternalAuxFileRejectsNonNullFile() {
        final RCMCalibrator cal = new RCMCalibrator();
        try {
            cal.setExternalAuxFile(new File("dummy.xml"));
            fail("RCMCalibrator must reject an external auxiliary file");
        } catch (OperatorException expected) {
            assertTrue("Error message should explain the rejection",
                    expected.getMessage().contains("RCM"));
        }
    }

    @Test
    public void testSetExternalAuxFileAcceptsNull() {
        // Passing null must always be allowed, otherwise factory construction breaks.
        new RCMCalibrator().setExternalAuxFile(null);
    }

    @Test
    public void testRegisteredInCalibratorRegistry() throws Exception {
        final Calibrator cal = CalibratorRegistry.getInstance().getCalibrator("RCM");
        assertNotNull("RCM mission must resolve to a registered calibrator", cal);
        assertTrue(cal instanceof RCMCalibrator);
    }

    @Test
    public void testRegistryLookupIsCaseInsensitive() throws Exception {
        final Calibrator cal = CalibratorRegistry.getInstance().getCalibrator("rcm");
        assertNotNull(cal);
        assertTrue(cal instanceof RCMCalibrator);
    }

    @Test
    public void testRegistryReturnsFreshInstanceEachCall() throws Exception {
        final Calibrator first = CalibratorRegistry.getInstance().getCalibrator("RCM");
        final Calibrator second = CalibratorRegistry.getInstance().getCalibrator("RCM");
        assertNotNull(first);
        assertNotNull(second);
        assertTrue("Registry must return a new instance per call", first != second);
    }

    @Test
    public void testRegisteredAsServiceProvider() {
        final Calibrator[] all = CalibratorRegistry.getInstance().getAllCalibrators();
        assertNotNull(all);
        final boolean found = Arrays.stream(all)
                .anyMatch(c -> c instanceof RCMCalibrator);
        assertTrue("RCMCalibrator must appear in CalibratorRegistry.getAllCalibrators()", found);
    }

    @Test
    public void testFirstSupportedMissionResolvesViaRegistry() throws Exception {
        final String[] missions = new RCMCalibrator().getSupportedMissions();
        for (String mission : missions) {
            final Calibrator resolved = CalibratorRegistry.getInstance().getCalibrator(mission);
            assertNotNull("Mission " + mission + " declared but not reachable through the registry",
                    resolved);
            assertEquals(RCMCalibrator.class, resolved.getClass());
        }
    }
}
