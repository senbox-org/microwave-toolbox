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
package eu.esa.sar.calibration.gpf.support;

import eu.esa.sar.calibration.gpf.calibrators.ALOSCalibrator;
import eu.esa.sar.calibration.gpf.calibrators.ASARCalibrator;
import eu.esa.sar.calibration.gpf.calibrators.BiomassCalibrator;
import eu.esa.sar.calibration.gpf.calibrators.CapellaCalibrator;
import eu.esa.sar.calibration.gpf.calibrators.ERSCalibrator;
import eu.esa.sar.calibration.gpf.calibrators.IceyeCalibrator;
import eu.esa.sar.calibration.gpf.calibrators.Kompsat5Calibrator;
import eu.esa.sar.calibration.gpf.calibrators.PazCalibrator;
import eu.esa.sar.calibration.gpf.calibrators.Risat1Calibrator;
import eu.esa.sar.calibration.gpf.calibrators.SaocomCalibrator;
import eu.esa.sar.calibration.gpf.calibrators.Sentinel1Calibrator;
import eu.esa.sar.calibration.gpf.calibrators.SpacetyCalibrator;
import eu.esa.sar.calibration.gpf.calibrators.StriXCalibrator;
import eu.esa.sar.calibration.gpf.calibrators.TerraSARXCalibrator;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link CalibratorRegistry}.
 */
public class TestCalibratorRegistry {

    @BeforeClass
    public static void setUpClass() {
        TestUtils.initTestEnvironment();
    }

    @Test
    public void testGetInstanceIsSingleton() {
        final CalibratorRegistry a = CalibratorRegistry.getInstance();
        final CalibratorRegistry b = CalibratorRegistry.getInstance();
        assertNotNull(a);
        assertEquals(a, b);
    }

    @Test
    public void testGetAllCalibratorsReturnsRegisteredServices() {
        final Calibrator[] calibrators = CalibratorRegistry.getInstance().getAllCalibrators();
        assertNotNull(calibrators);
        // 15 calibrators are registered in META-INF/services
        assertTrue("Expected at least 14 registered calibrators, got " + calibrators.length,
                calibrators.length >= 14);
    }

    @Test
    public void testGetCalibratorSentinel1() throws Exception {
        final Calibrator cal = CalibratorRegistry.getInstance().getCalibrator("SENTINEL-1A");
        assertNotNull(cal);
        assertTrue(cal instanceof Sentinel1Calibrator);
    }

    @Test
    public void testGetCalibratorEnvisat() throws Exception {
        final Calibrator cal = CalibratorRegistry.getInstance().getCalibrator("ENVISAT");
        assertNotNull(cal);
        assertTrue(cal instanceof ASARCalibrator);
    }

    @Test
    public void testGetCalibratorErs1() throws Exception {
        final Calibrator cal = CalibratorRegistry.getInstance().getCalibrator("ERS1");
        assertNotNull(cal);
        assertTrue(cal instanceof ERSCalibrator);
    }

    @Test
    public void testGetCalibratorAlos() throws Exception {
        final Calibrator cal = CalibratorRegistry.getInstance().getCalibrator("ALOS");
        assertNotNull(cal);
        assertTrue(cal instanceof ALOSCalibrator);
    }

    @Test
    public void testGetCalibratorIceye() throws Exception {
        final Calibrator cal = CalibratorRegistry.getInstance().getCalibrator("ICEYE");
        assertNotNull(cal);
        assertTrue(cal instanceof IceyeCalibrator);
    }

    @Test
    public void testGetCalibratorCapella() throws Exception {
        final Calibrator cal = CalibratorRegistry.getInstance().getCalibrator("Capella");
        assertNotNull(cal);
        assertTrue(cal instanceof CapellaCalibrator);
    }

    @Test
    public void testGetCalibratorBiomass() throws Exception {
        final Calibrator cal = CalibratorRegistry.getInstance().getCalibrator("BIOMASS");
        assertNotNull(cal);
        assertTrue(cal instanceof BiomassCalibrator);
    }

    @Test
    public void testGetCalibratorTerraSarX() throws Exception {
        final Calibrator cal = CalibratorRegistry.getInstance().getCalibrator("TSX");
        assertNotNull(cal);
        assertTrue(cal instanceof TerraSARXCalibrator);
    }

    @Test
    public void testGetCalibratorPaz() throws Exception {
        final Calibrator cal = CalibratorRegistry.getInstance().getCalibrator("PAZ");
        assertNotNull(cal);
        assertTrue(cal instanceof PazCalibrator);
    }

    @Test
    public void testGetCalibratorKompsat5() throws Exception {
        final Calibrator cal = CalibratorRegistry.getInstance().getCalibrator("Kompsat5");
        assertNotNull(cal);
        assertTrue(cal instanceof Kompsat5Calibrator);
    }

    @Test
    public void testGetCalibratorRisat1() throws Exception {
        final Calibrator cal = CalibratorRegistry.getInstance().getCalibrator("RISAT1");
        assertNotNull(cal);
        assertTrue(cal instanceof Risat1Calibrator);
    }

    @Test
    public void testGetCalibratorSaocom() throws Exception {
        final Calibrator cal = CalibratorRegistry.getInstance().getCalibrator("SAOCOM");
        assertNotNull(cal);
        assertTrue(cal instanceof SaocomCalibrator);
    }

    @Test
    public void testGetCalibratorSpacety() throws Exception {
        final Calibrator cal = CalibratorRegistry.getInstance().getCalibrator("Spacety");
        assertNotNull(cal);
        assertTrue(cal instanceof SpacetyCalibrator);
    }

    @Test
    public void testGetCalibratorStriX() throws Exception {
        final Calibrator cal = CalibratorRegistry.getInstance().getCalibrator("STRIX-1");
        assertNotNull(cal);
        assertTrue(cal instanceof StriXCalibrator);
    }

    @Test
    public void testGetCalibratorCaseInsensitive() throws Exception {
        final Calibrator upper = CalibratorRegistry.getInstance().getCalibrator("sentinel-1a");
        assertNotNull(upper);
        assertTrue(upper instanceof Sentinel1Calibrator);
    }

    @Test
    public void testGetCalibratorReturnsNewInstancePerCall() throws Exception {
        final Calibrator first = CalibratorRegistry.getInstance().getCalibrator("ENVISAT");
        final Calibrator second = CalibratorRegistry.getInstance().getCalibrator("ENVISAT");
        assertNotNull(first);
        assertNotNull(second);
        // Registry returns a fresh instance per call
        assertTrue(first != second);
    }

    @Test
    public void testGetCalibratorUnknownMissionReturnsNull() throws Exception {
        final Calibrator cal = CalibratorRegistry.getInstance().getCalibrator("MADE-UP-MISSION-XYZ");
        assertNull(cal);
    }

    @Test
    public void testGetCalibratorNullNameThrows() {
        try {
            CalibratorRegistry.getInstance().getCalibrator(null);
            fail("Expected exception for null mission name");
        } catch (Exception expected) {
            // Guardian throws IllegalArgumentException via assertNotNullOrEmpty
        }
    }

    @Test
    public void testGetCalibratorEmptyNameThrows() {
        try {
            CalibratorRegistry.getInstance().getCalibrator("");
            fail("Expected exception for empty mission name");
        } catch (Exception expected) {
            // Guardian throws IllegalArgumentException via assertNotNullOrEmpty
        }
    }
}
