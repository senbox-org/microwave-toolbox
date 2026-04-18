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
package eu.esa.sar.calibration.gpf.calibrators;

import eu.esa.sar.calibration.gpf.support.Calibrator;
import eu.esa.sar.calibration.gpf.support.CalibratorRegistry;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests that verify each concrete {@link Calibrator} declares at least one
 * supported mission and that every mission resolves back to a calibrator
 * instance via the {@link CalibratorRegistry}.
 */
public class TestCalibratorsSupportedMissions {

    @BeforeClass
    public static void setUpClass() {
        TestUtils.initTestEnvironment();
    }

    @Test
    public void testALOSSupportedMissions() {
        assertMissions(new ALOSCalibrator(), "ALOS", "ALOS2", "ALOS4");
    }

    @Test
    public void testASARSupportedMissions() {
        assertMissions(new ASARCalibrator(), "ENVISAT");
    }

    @Test
    public void testBiomassSupportedMissions() {
        assertMissions(new BiomassCalibrator(), "BIOMASS");
    }

    @Test
    public void testCapellaSupportedMissions() {
        assertMissions(new CapellaCalibrator(), "Capella");
    }

    @Test
    public void testCosmoSkymedSupportedMissions() {
        assertMissions(new CosmoSkymedCalibrator(), "CSK");
    }

    @Test
    public void testERSSupportedMissions() {
        assertMissions(new ERSCalibrator(), "ERS1", "ERS2");
    }

    @Test
    public void testIceyeSupportedMissions() {
        final String[] missions = new IceyeCalibrator().getSupportedMissions();
        assertNotNull(missions);
        assertTrue("Iceye calibrator should list ICEYE", asSet(missions).contains("ICEYE"));
    }

    @Test
    public void testKompsat5SupportedMissions() {
        assertMissions(new Kompsat5Calibrator(), "Kompsat5");
    }

    @Test
    public void testPazSupportedMissions() {
        assertMissions(new PazCalibrator(), "PAZ");
    }

    @Test
    public void testRisat1SupportedMissions() {
        assertMissions(new Risat1Calibrator(), "RISAT1", "EOS-04");
    }

    @Test
    public void testSaocomSupportedMissions() {
        assertMissions(new SaocomCalibrator(), "SAOCOM");
    }

    @Test
    public void testSentinel1SupportedMissions() {
        assertMissions(new Sentinel1Calibrator(),
                "SENTINEL-1A", "SENTINEL-1B", "SENTINEL-1C", "SENTINEL-1D");
    }

    @Test
    public void testSpacetySupportedMissions() {
        assertMissions(new SpacetyCalibrator(), "Spacety");
    }

    @Test
    public void testStriXSupportedMissions() {
        final String[] missions = new StriXCalibrator().getSupportedMissions();
        assertNotNull(missions);
        final Set<String> set = asSet(missions);
        assertTrue("StriX calibrator should include the STRIX umbrella entry", set.contains("STRIX"));
        assertTrue("StriX calibrator should include STRIX-1", set.contains("STRIX-1"));
        assertTrue("StriX calibrator should include STRIX-ALPHA", set.contains("STRIX-ALPHA"));
    }

    @Test
    public void testTerraSarXSupportedMissions() {
        assertMissions(new TerraSARXCalibrator(), "TSX", "TDX");
    }

    @Test
    public void testAllRegisteredCalibratorsExposeMissions() throws Exception {
        final Calibrator[] all = CalibratorRegistry.getInstance().getAllCalibrators();
        assertNotNull(all);
        assertTrue(all.length > 0);
        for (Calibrator calibrator : all) {
            final String[] missions = calibrator.getSupportedMissions();
            assertNotNull(calibrator.getClass().getSimpleName() + ".getSupportedMissions() returned null",
                    missions);
            assertTrue(calibrator.getClass().getSimpleName() + " should declare at least one supported mission",
                    missions.length > 0);
            for (String mission : missions) {
                assertNotNull("null mission entry in " + calibrator.getClass().getSimpleName(), mission);
                assertTrue("empty mission entry in " + calibrator.getClass().getSimpleName(),
                        !mission.isEmpty());
            }
            // Verify first declared mission round-trips through the registry.
            final Calibrator resolved = CalibratorRegistry.getInstance().getCalibrator(missions[0]);
            assertNotNull("registry could not resolve mission '" + missions[0]
                    + "' declared by " + calibrator.getClass().getSimpleName(), resolved);
        }
    }

    @Test
    public void testSetExternalAuxFileRejectsInputForVendorCalibrators() {
        // These calibrators document that no external aux file should be supplied.
        final Calibrator[] rejectsAuxFile = {
                new Sentinel1Calibrator(),
                new CapellaCalibrator(),
                new BiomassCalibrator(),
                new IceyeCalibrator(),
                new TerraSARXCalibrator(),
                new PazCalibrator(),
                new CosmoSkymedCalibrator(),
                new Kompsat5Calibrator(),
                new SaocomCalibrator()
        };
        for (Calibrator cal : rejectsAuxFile) {
            try {
                cal.setExternalAuxFile(new java.io.File("dummy.xml"));
                fail(cal.getClass().getSimpleName()
                        + ".setExternalAuxFile should reject non-null aux file");
            } catch (OperatorException expected) {
                // success
            }
        }
    }

    @Test
    public void testSetExternalAuxFileAcceptsNullForVendorCalibrators() {
        // Passing null must always be allowed, otherwise factory construction breaks.
        final Calibrator[] calibrators = {
                new Sentinel1Calibrator(),
                new CapellaCalibrator(),
                new BiomassCalibrator(),
                new IceyeCalibrator(),
                new TerraSARXCalibrator()
        };
        for (Calibrator cal : calibrators) {
            cal.setExternalAuxFile(null);
        }
    }

    private static void assertMissions(Calibrator calibrator, String... expected) {
        final String[] actual = calibrator.getSupportedMissions();
        assertNotNull(actual);
        final Set<String> actualSet = asSet(actual);
        for (String e : expected) {
            assertTrue(calibrator.getClass().getSimpleName()
                    + " should advertise mission " + e + " but had " + Arrays.toString(actual),
                    actualSet.contains(e));
        }
    }

    private static Set<String> asSet(String[] arr) {
        return new HashSet<>(Arrays.asList(arr));
    }
}
