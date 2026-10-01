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

import com.bc.ceres.annotation.STTM;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Regression test for https://forum.step.esa.int/t/alos-4-error/46165
 * <p>
 * {@link ALOSCalibrator#getSupportedMissions()} advertises "ALOS4" (and is registered under that
 * mission by the {@code CalibratorRegistry}), but {@link ALOSCalibrator#initialize} had its own,
 * separate mission gate that was never updated to match, so any ALOS-4 product reached
 * {@code CalibrationOp} only to be rejected with "ALOS4 is not a valid mission for ALOS Calibration".
 * {@link TestCalibratorsSupportedMissions} only exercises {@code getSupportedMissions()}, so it could
 * not catch this; this test exercises {@code initialize()} itself.
 */
public class ALOSCalibratorInitializeTest {

    @BeforeClass
    public static void setUpClass() {
        TestUtils.initTestEnvironment();
    }

    @Test
    @STTM("SNAP-4265")
    public void testInitializeAcceptsALOS4() throws Exception {
        initializeWithMission("ALOS4");
    }

    @Test
    public void testInitializeAcceptsALOS2() throws Exception {
        initializeWithMission("ALOS2");
    }

    @Test
    public void testInitializeAcceptsALOS() throws Exception {
        initializeWithMission("ALOS");
    }

    @Test
    public void testInitializeRejectsUnsupportedMission() throws Exception {
        try {
            initializeWithMission("RADARSAT2");
            fail("Expected an OperatorException for an unsupported mission");
        } catch (OperatorException e) {
            assertEquals("RADARSAT2 is not a valid mission for ALOS Calibration", e.getMessage());
        }
    }

    private static void initializeWithMission(final String mission) throws Exception {
        final Product sourceProduct = createTestProduct(mission);
        final Product targetProduct = createTestProduct(mission);

        final ALOSCalibrator calibrator = new ALOSCalibrator();
        calibrator.initialize(null, sourceProduct, targetProduct, false, false);
    }

    private static Product createTestProduct(final String mission) {
        final Product product = new Product("test", "ALOS4", 10, 10);

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.MISSION, mission);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SAMPLE_TYPE, "DETECTED");
        absRoot.getAttribute(AbstractMetadata.abs_calibration_flag).getData().setElemBoolean(false);
        absRoot.getAttribute(AbstractMetadata.calibration_factor).getData().setElemDouble(0.0);

        return product;
    }
}
