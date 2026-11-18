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
package eu.esa.sar.calibration.gpf;

import com.bc.ceres.annotation.STTM;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.VirtualBand;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The virtual Beta0/Gamma0 bands divide their source by sin/cos(incidence), which is only right
 * when the source is sigma0. Calibrators now write real beta0/gamma0 into Beta0/Gamma0 bands, so
 * those must never be used as a source.
 */
public class TestCalibrationVirtualBands {

    private static Product product(final String... bandNames) {
        final Product product = new Product("p", "GRD", 4, 4);
        for (String name : bandNames) {
            final Band band = new Band(name, ProductData.TYPE_FLOAT32, 4, 4);
            band.setUnit(Unit.INTENSITY);
            product.addBand(band);
        }
        return product;
    }

    @Test

    @STTM("SNAP-4377")
    public void testBetaIsDerivedFromSigmaNotFromGamma() {
        final Product product = product("Gamma0_VV", "Sigma0_VV");
        CalibrationOp.createBetaVirtualBand(product, false);

        final Band beta0 = product.getBand("Beta0_VV");
        assertTrue(beta0 instanceof VirtualBand);
        assertEquals("Sigma0_VV / sin(incident_angle * PI/180.0)", ((VirtualBand) beta0).getExpression());
    }

    @Test

    @STTM("SNAP-4377")
    public void testGammaIsDerivedFromSigmaNotFromBeta() {
        final Product product = product("Beta0_VV", "Sigma0_VV");
        CalibrationOp.createGammaVirtualBand(product, false);

        final Band gamma0 = product.getBand("Gamma0_VV");
        assertTrue(gamma0 instanceof VirtualBand);
        assertEquals("Sigma0_VV / cos(incident_angle * PI/180.0)", ((VirtualBand) gamma0).getExpression());
    }

    @Test

    @STTM("SNAP-4377")
    public void testNothingIsDerivedWithoutASigmaSource() {
        final Product product = product("Gamma0_VV");
        CalibrationOp.createBetaVirtualBand(product, false);
        assertNull(product.getBand("Beta0_VV"));

        final Product other = product("Beta0_VV");
        CalibrationOp.createGammaVirtualBand(other, false);
        assertNull(other.getBand("Gamma0_VV"));
    }
}
