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

import eu.esa.sar.calibration.gpf.calibrators.Sentinel1Calibrator;
import eu.esa.sar.commons.SARGeocoding;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.VirtualBand;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link CalibrationFactory}.
 */
public class TestCalibrationFactory {

    @BeforeClass
    public static void setUpClass() {
        TestUtils.initTestEnvironment();
    }

    @Test
    public void testCreateCalibratorSentinel1() throws Exception {
        final Product product = TestUtils.createProduct("GRD", 10, 10);
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        absRoot.setAttributeString(AbstractMetadata.MISSION, "SENTINEL-1A");

        final Calibrator calibrator = CalibrationFactory.createCalibrator(product);

        assertNotNull(calibrator);
        assertTrue(calibrator instanceof Sentinel1Calibrator);
    }

    @Test
    public void testCreateCalibratorUnsupportedMissionThrows() {
        final Product product = TestUtils.createProduct("GRD", 10, 10);
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        absRoot.setAttributeString(AbstractMetadata.MISSION, "NONEXISTENT-MISSION");

        try {
            CalibrationFactory.createCalibrator(product);
            fail("Expected OperatorException for unsupported mission");
        } catch (OperatorException expected) {
            assertTrue("Expected message to mention mission not supported, got: " + expected.getMessage(),
                    expected.getMessage().contains("currently not supported"));
        } catch (Exception e) {
            fail("Expected OperatorException, got " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @Test
    public void testCreateBetaNoughtVirtualBand() {
        final Product product = new Product("t", "GRD", 8, 8);
        final Band sigma0 = new Band("Sigma0_VV", ProductData.TYPE_FLOAT32, 8, 8);
        sigma0.setUnit(Unit.INTENSITY);
        sigma0.setNoDataValue(-9999.0);
        sigma0.setNoDataValueUsed(true);
        product.addBand(sigma0);

        CalibrationFactory.createBetaNoughtVirtualBand(product);

        final Band beta0 = product.getBand("Beta0_VV");
        assertNotNull("Beta0_VV virtual band should have been added", beta0);
        assertTrue(beta0 instanceof VirtualBand);
        assertEquals(Unit.INTENSITY, beta0.getUnit());
        assertTrue(beta0.isNoDataValueUsed());
    }

    @Test
    public void testCreateBetaNoughtVirtualBandSkipsNonSigmaBands() {
        final Product product = new Product("t", "GRD", 8, 8);
        final Band amp = new Band("Amplitude_VV", ProductData.TYPE_FLOAT32, 8, 8);
        amp.setUnit(Unit.AMPLITUDE);
        product.addBand(amp);

        CalibrationFactory.createBetaNoughtVirtualBand(product);

        assertNull("Beta0 should not be created when no Sigma0 band exists",
                product.getBand("Beta0_VV"));
    }

    @Test
    public void testCreateBetaNoughtVirtualBandSkipsExistingVirtualBand() {
        final Product product = new Product("t", "GRD", 8, 8);
        final VirtualBand vb = new VirtualBand("Sigma0_VV", ProductData.TYPE_FLOAT32, 8, 8, "0");
        vb.setUnit(Unit.INTENSITY);
        product.addBand(vb);

        CalibrationFactory.createBetaNoughtVirtualBand(product);

        // virtual Sigma0 bands are skipped by the factory
        assertNull(product.getBand("Beta0_VV"));
    }

    @Test
    public void testCreateGammaNoughtVirtualBandFromEllipsoid() {
        final Product product = new Product("t", "GRD", 8, 8);
        final Band sigma0 = new Band("Sigma0_VV", ProductData.TYPE_FLOAT32, 8, 8);
        sigma0.setUnit(Unit.INTENSITY);
        sigma0.setNoDataValue(-9999.0);
        sigma0.setNoDataValueUsed(true);
        product.addBand(sigma0);

        CalibrationFactory.createGammaNoughtVirtualBand(
                product, SARGeocoding.USE_INCIDENCE_ANGLE_FROM_ELLIPSOID);

        final Band gamma0 = product.getBand("Gamma0_VV_use_inci_angle_from_ellipsoid");
        assertNotNull(gamma0);
        assertTrue(gamma0 instanceof VirtualBand);
    }

    @Test
    public void testCreateGammaNoughtVirtualBandFromLocalDem() {
        final Product product = new Product("t", "GRD", 8, 8);
        final Band sigma0 = new Band("Sigma0_HH", ProductData.TYPE_FLOAT32, 8, 8);
        sigma0.setUnit(Unit.INTENSITY);
        sigma0.setNoDataValue(-9999.0);
        product.addBand(sigma0);

        CalibrationFactory.createGammaNoughtVirtualBand(
                product, SARGeocoding.USE_LOCAL_INCIDENCE_ANGLE_FROM_DEM);

        assertNotNull(product.getBand("Gamma0_HH_use_local_inci_angle_from_dem"));
    }

    @Test
    public void testCreateGammaNoughtVirtualBandProjectedDem() {
        final Product product = new Product("t", "GRD", 8, 8);
        final Band sigma0 = new Band("Sigma0_HV", ProductData.TYPE_FLOAT32, 8, 8);
        sigma0.setUnit(Unit.INTENSITY);
        product.addBand(sigma0);

        CalibrationFactory.createGammaNoughtVirtualBand(
                product, SARGeocoding.USE_PROJECTED_INCIDENCE_ANGLE_FROM_DEM);

        assertNotNull(product.getBand("Gamma0_HV_use_projected_local_inci_angle_from_dem"));
    }

    @Test
    public void testCreateSigmaNoughtVirtualBandProjectedDemSkips() {
        final Product product = new Product("t", "GRD", 8, 8);
        final Band sigma0 = new Band("Sigma0_VV", ProductData.TYPE_FLOAT32, 8, 8);
        sigma0.setUnit(Unit.INTENSITY);
        product.addBand(sigma0);

        CalibrationFactory.createSigmaNoughtVirtualBand(
                product, SARGeocoding.USE_PROJECTED_INCIDENCE_ANGLE_FROM_DEM);

        // projected-from-DEM is handled natively; no extra virtual band created.
        assertEquals(1, product.getNumBands());
    }

    @Test
    public void testCreateSigmaNoughtVirtualBandFromEllipsoid() {
        final Product product = new Product("t", "GRD", 8, 8);
        final Band sigma0 = new Band("Sigma0_VV", ProductData.TYPE_FLOAT32, 8, 8);
        sigma0.setUnit(Unit.INTENSITY);
        sigma0.setNoDataValue(-9999.0);
        sigma0.setNoDataValueUsed(true);
        product.addBand(sigma0);

        CalibrationFactory.createSigmaNoughtVirtualBand(
                product, SARGeocoding.USE_INCIDENCE_ANGLE_FROM_ELLIPSOID);

        final Band virtual = product.getBand("Sigma0_VV_use_inci_angle_from_ellipsoid");
        assertNotNull(virtual);
        assertTrue(virtual instanceof VirtualBand);
    }
}
