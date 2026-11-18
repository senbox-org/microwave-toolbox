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

import com.bc.ceres.annotation.STTM;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.TiePointGrid;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link BaseCalibrator}.
 */
public class TestBaseCalibrator {

    @BeforeClass
    public static void setUpClass() {
        TestUtils.initTestEnvironment();
    }

    @Test
    public void testSettersStoreFlags() {
        final BaseCalibratorProbe probe = new BaseCalibratorProbe();
        probe.setOutputImageInComplex(true);
        probe.setOutputImageIndB(false);
        probe.setIncidenceAngleForSigma0("dem");

        assertTrue(probe.isOutputInComplex());
        assertTrue(!probe.isOutputScaleInDb());
        assertEquals("dem", probe.getIncidenceAngleSelection());
    }

    @Test
    public void testSetUserSelectionsDefaultsToSigma() {
        final Product srcProduct = TestUtils.createProduct("GRD", 8, 8);
        TestUtils.createBand(srcProduct, "Amplitude_VV", 8, 8);

        final BaseCalibratorProbe cal = new BaseCalibratorProbe();
        cal.setUserSelections(srcProduct, new String[] { "VV" }, false, false, false, false);

        assertTrue("When no output is requested, sigma0 must be enabled by default",
                cal.isOutputSigmaBand());
        assertTrue(!cal.isOutputGammaBand());
        assertTrue(!cal.isOutputBetaBand());
        assertTrue(!cal.isOutputDNBand());
    }

    @Test
    public void testSetUserSelectionsHonorsExplicitSelections() {
        final Product srcProduct = TestUtils.createProduct("GRD", 8, 8);
        TestUtils.createBand(srcProduct, "Amplitude_VV", 8, 8);

        final BaseCalibratorProbe cal = new BaseCalibratorProbe();
        cal.setUserSelections(srcProduct, new String[] { "VV" }, false, true, true, false);

        assertTrue("sigma flag should stay false when caller requested only gamma/beta",
                !cal.isOutputSigmaBand());
        assertTrue(cal.isOutputGammaBand());
        assertTrue(cal.isOutputBetaBand());
    }

    @Test
    public void testSetUserSelectionsNormalisesPolarisationsToUppercase() {
        final Product srcProduct = TestUtils.createProduct("GRD", 8, 8);
        TestUtils.createBand(srcProduct, "Amplitude_VV", 8, 8);

        final BaseCalibratorProbe cal = new BaseCalibratorProbe();
        cal.setUserSelections(srcProduct, new String[] { "vv", "hh" }, true, false, false, false);

        assertTrue(cal.getSelectedPolList().contains("VV"));
        assertTrue(cal.getSelectedPolList().contains("HH"));
    }

    @Test
    public void testCreateTargetProductCopiesBasicNodes() {
        final Product srcProduct = TestUtils.createProduct("GRD", 8, 8);
        final Band srcBand = TestUtils.createBand(srcProduct, "Amplitude_VV",
                ProductData.TYPE_FLOAT32, Unit.AMPLITUDE, 8, 8, true);
        srcBand.setNoDataValue(-9999.0);
        srcBand.setNoDataValueUsed(true);

        final BaseCalibratorProbe cal = new BaseCalibratorProbe();
        cal.setUserSelections(srcProduct, new String[] { "VV" }, true, false, false, false);

        final Product target = cal.createTargetProduct(srcProduct, new String[] { "Amplitude_VV" });

        assertNotNull(target);
        assertEquals(srcProduct.getName() + "_Cal", target.getName());
        assertEquals(srcProduct.getProductType(), target.getProductType());
        assertEquals(srcProduct.getSceneRasterWidth(), target.getSceneRasterWidth());
        assertEquals(srcProduct.getSceneRasterHeight(), target.getSceneRasterHeight());

        final Band sigma0 = target.getBand("Sigma0_VV");
        assertNotNull("Sigma0_VV band should have been added", sigma0);
        assertEquals(Unit.INTENSITY, sigma0.getUnit());
        assertTrue(sigma0.isNoDataValueUsed());
    }

    @Test
    public void testCreateTargetProductWithoutMatchingPolarisationSkipsBand() {
        final Product srcProduct = TestUtils.createProduct("GRD", 8, 8);
        TestUtils.createBand(srcProduct, "Amplitude_VV",
                ProductData.TYPE_FLOAT32, Unit.AMPLITUDE, 8, 8, true);

        final BaseCalibratorProbe cal = new BaseCalibratorProbe();
        // selected polarisation that doesn't match any band
        cal.setUserSelections(srcProduct, new String[] { "HH" }, true, false, false, false);

        final Product target = cal.createTargetProduct(srcProduct, new String[] { "Amplitude_VV" });

        assertNull("Sigma0_VV should not exist when VV was not selected",
                target.getBand("Sigma0_VV"));
    }

    @Test
    public void testCreateTargetProductRejectsBandWithoutUnit() {
        final Product srcProduct = TestUtils.createProduct("GRD", 8, 8);
        final Band band = new Band("Amplitude_VV", ProductData.TYPE_FLOAT32, 8, 8);
        // intentionally no unit set
        srcProduct.addBand(band);

        final BaseCalibratorProbe cal = new BaseCalibratorProbe();
        cal.setUserSelections(srcProduct, new String[] { "VV" }, true, false, false, false);

        try {
            cal.createTargetProduct(srcProduct, new String[] { "Amplitude_VV" });
            fail("Expected OperatorException when source band has no unit");
        } catch (OperatorException expected) {
            assertTrue(expected.getMessage().contains("requires a unit"));
        }
    }

    @Test
    public void testComplexOutputRequiresIQPairs() {
        final Product srcProduct = TestUtils.createProduct("SLC", 8, 8);
        TestUtils.createBand(srcProduct, "i_VV", ProductData.TYPE_FLOAT32, Unit.REAL, 8, 8, true);
        // Intentionally missing q_VV companion.

        final BaseCalibratorProbe cal = new BaseCalibratorProbe();
        cal.setOutputImageInComplex(true);
        cal.setUserSelections(srcProduct, new String[] { "VV" }, true, false, false, false);

        try {
            cal.createTargetProduct(srcProduct, new String[] { "i_VV" });
            fail("Expected OperatorException because Q band is missing");
        } catch (OperatorException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("pairs"));
        }
    }

    @Test

    @STTM("SNAP-4377")
    public void testQuantityOfFollowsTargetBandNames() {
        assertEquals(BaseCalibrator.CalibratedQuantity.SIGMA0, BaseCalibrator.quantityOf("Sigma0_VV"));
        assertEquals(BaseCalibrator.CalibratedQuantity.BETA0, BaseCalibrator.quantityOf("Beta0_VV"));
        assertEquals(BaseCalibrator.CalibratedQuantity.BETA0, BaseCalibrator.quantityOf("Beta0_HV_dB"));
        assertEquals(BaseCalibrator.CalibratedQuantity.GAMMA0, BaseCalibrator.quantityOf("Gamma0_HH"));
        assertEquals(BaseCalibrator.CalibratedQuantity.OTHER, BaseCalibrator.quantityOf("DN_VV"));
        assertEquals(BaseCalibrator.CalibratedQuantity.OTHER, BaseCalibrator.quantityOf("i_VV"));
        assertEquals(BaseCalibrator.CalibratedQuantity.OTHER, BaseCalibrator.quantityOf(null));
    }

    @Test

    @STTM("SNAP-4377")
    public void testSigma0ToBeta0AndGamma0() {
        final double sigma0 = 0.25;
        final double theta = 34.0;
        final double rad = Math.toRadians(theta);
        assertEquals(sigma0, BaseCalibrator.sigma0To(BaseCalibrator.CalibratedQuantity.SIGMA0, sigma0, theta), 0.0);
        assertEquals(sigma0, BaseCalibrator.sigma0To(BaseCalibrator.CalibratedQuantity.OTHER, sigma0, theta), 0.0);
        assertEquals(sigma0 / Math.sin(rad),
                BaseCalibrator.sigma0To(BaseCalibrator.CalibratedQuantity.BETA0, sigma0, theta), 1e-15);
        assertEquals(sigma0 / Math.cos(rad),
                BaseCalibrator.sigma0To(BaseCalibrator.CalibratedQuantity.GAMMA0, sigma0, theta), 1e-15);
        // gamma0 = beta0 * tan(theta), the flat-ground limit Terrain-Flattening must reproduce
        assertEquals(BaseCalibrator.sigma0To(BaseCalibrator.CalibratedQuantity.BETA0, sigma0, theta) * Math.tan(rad),
                BaseCalibrator.sigma0To(BaseCalibrator.CalibratedQuantity.GAMMA0, sigma0, theta), 1e-15);
    }

    @Test

    @STTM("SNAP-4377")
    public void testBeta0TargetUsesIncidenceAngleTiePointGrid() {
        final Product srcProduct = TestUtils.createProduct("GRD", 8, 8);
        final float[] angles = new float[16];
        java.util.Arrays.fill(angles, 30.0f);
        srcProduct.addTiePointGrid(new TiePointGrid(OperatorUtils.TPG_INCIDENT_ANGLE, 4, 4, 0.5f, 0.5f,
                8 / 3.0f, 8 / 3.0f, angles));

        final BaseCalibratorProbe cal = new BaseCalibratorProbe();
        cal.setSource(srcProduct);
        final Band beta0 = new Band("Beta0_VV", ProductData.TYPE_FLOAT32, 8, 8);
        final Band sigma0 = new Band("Sigma0_VV", ProductData.TYPE_FLOAT32, 8, 8);

        final BaseCalibrator.CalibratedQuantity qb = cal.targetQuantity(beta0);
        assertEquals(0.2 / Math.sin(Math.toRadians(30.0)), cal.toTargetQuantity(0.2, qb, 3, 4), 1e-6);
        final BaseCalibrator.CalibratedQuantity qs = cal.targetQuantity(sigma0);
        assertEquals(0.2, cal.toTargetQuantity(0.2, qs, 3, 4), 0.0);
    }

    @Test

    @STTM("SNAP-4377")
    public void testBeta0TargetWithoutIncidenceAngleFailsLoudly() {
        final Product srcProduct = TestUtils.createProduct("GRD", 8, 8); // latitude/longitude grids only

        final BaseCalibratorProbe cal = new BaseCalibratorProbe();
        cal.setSource(srcProduct);
        // sigma0 needs no incidence angle
        assertEquals(BaseCalibrator.CalibratedQuantity.SIGMA0,
                cal.targetQuantity(new Band("Sigma0_VV", ProductData.TYPE_FLOAT32, 8, 8)));
        try {
            cal.targetQuantity(new Band("Beta0_VV", ProductData.TYPE_FLOAT32, 8, 8));
            fail("Beta0 without an incidence angle grid must not silently fall back to sigma0");
        } catch (OperatorException expected) {
            assertTrue(expected.getMessage().contains("incidence_angle"));
        }
    }

    /**
     * Probe subclass that exposes protected state so tests can verify behavior
     * without reflection or operator wiring.
     */
    private static class BaseCalibratorProbe extends BaseCalibrator {
        void setSource(Product product) { sourceProduct = product; }
        CalibratedQuantity targetQuantity(Band band) { return getTargetQuantity(band); }
        double toTargetQuantity(double sigma0, CalibratedQuantity q, int x, int y) {
            return sigma0ToTargetQuantity(sigma0, q, x, y);
        }
        boolean isOutputInComplex() { return outputImageInComplex; }
        boolean isOutputScaleInDb() { return outputImageScaleInDb; }
        String getIncidenceAngleSelection() { return incidenceAngleSelection; }
        boolean isOutputSigmaBand() { return outputSigmaBand; }
        boolean isOutputGammaBand() { return outputGammaBand; }
        boolean isOutputBetaBand() { return outputBetaBand; }
        boolean isOutputDNBand() { return outputDNBand; }
        java.util.List<String> getSelectedPolList() { return selectedPolList; }
    }
}
