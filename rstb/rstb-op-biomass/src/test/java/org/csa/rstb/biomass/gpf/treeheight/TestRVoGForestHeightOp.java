/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. https://www.skywatch.com
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
package org.csa.rstb.biomass.gpf.treeheight;

import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TestRVoGForestHeightOp {

    private static final OperatorSpi spi = new RVoGForestHeightOp.Spi();

    @Test
    public void spi_creates_operator() {
        final RVoGForestHeightOp op = (RVoGForestHeightOp) spi.createOperator();
        assertNotNull(op);
    }

    @Test
    public void operator_metadata_alias_and_category() {
        final OperatorMetadata md = RVoGForestHeightOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("RVoG-Forest-Height", md.alias());
        assertEquals("Radar/Biomass", md.category());
    }

    /**
     * Forward-compute sinc(kz*hv/(2pi)) for a known (kz, hv), then invert
     * and verify we recover hv to within 1 cm.
     */
    @Test
    public void sinc_inversion_round_trips_for_known_height() {
        final double kz = 0.15;          // rad/m (typical TanDEM-X / L-band PolInSAR)
        final double[] heights = {2.0, 5.0, 10.0, 20.0, 30.0};
        for (double hv : heights) {
            final double x = kz * hv / (2.0 * Math.PI);
            final double gammaModel = (x == 0.0) ? 1.0 : Math.sin(Math.PI * x) / (Math.PI * x);
            final double hvInv = RVoGForestHeightOp.invertHeightSinC(gammaModel, kz);
            assertEquals("round-trip for hv=" + hv, hv, hvInv, 1.0e-3);
        }
    }

    /**
     * |gamma| close to 1 -> very low canopy. |gamma| close to 0 -> canopy
     * approaching the first sinc null (2*pi/kz). Verify the monotone trend.
     */
    @Test
    public void sinc_inversion_monotone_in_gamma() {
        final double kz = 0.1;
        final double h1 = RVoGForestHeightOp.invertHeightSinC(0.9, kz);
        final double h2 = RVoGForestHeightOp.invertHeightSinC(0.7, kz);
        final double h3 = RVoGForestHeightOp.invertHeightSinC(0.4, kz);
        assertTrue("higher |gamma| -> lower hv", h1 < h2);
        assertTrue("higher |gamma| -> lower hv", h2 < h3);
        // First sinc null at hv = 2*pi/kz
        assertTrue("hv <= first null", h3 < 2.0 * Math.PI / kz + 1.0);
    }

    @Test
    public void invalid_inputs_return_nan() {
        assertTrue(Double.isNaN(RVoGForestHeightOp.invertHeightSinC(-0.1, 0.1)));
        assertTrue(Double.isNaN(RVoGForestHeightOp.invertHeightSinC(1.5, 0.1)));
        assertTrue(Double.isNaN(RVoGForestHeightOp.invertHeightSinC(0.5, 0.0)));
        assertTrue(Double.isNaN(RVoGForestHeightOp.invertHeightSinC(0.5, -0.1)));
    }
}
