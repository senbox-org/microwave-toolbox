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
package org.csa.rstb.classification.gpf;

import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Data-free unit tests for {@link PolarimetricClassificationOp}.
 * Complements {@link TestClassifcationOp} which requires polarimetric input products.
 */
public class TestPolarimetricClassificationOp {

    @Test
    public void testSpiCreatesOperator() {
        final PolarimetricClassificationOp op =
                (PolarimetricClassificationOp) new PolarimetricClassificationOp.Spi().createOperator();
        assertNotNull(op);
    }

    @Test
    public void testOperatorMetadata() {
        final OperatorMetadata md = PolarimetricClassificationOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("Polarimetric-Classification", md.alias());
    }

    @Test
    public void testClassificationConstants() {
        // Pin the public classification mode strings — they feed into graph XML and UI wiring
        // and changing them silently would break saved graphs.
        assertEquals("Cloude-Pottier",
                PolarimetricClassificationOp.UNSUPERVISED_CLOUDE_POTTIER_CLASSIFICATION);
        assertEquals("H Alpha Wishart",
                PolarimetricClassificationOp.UNSUPERVISED_HALPHA_WISHART_CLASSIFICATION);
        assertEquals("Freeman-Durden Wishart",
                PolarimetricClassificationOp.UNSUPERVISED_FREEMAN_DURDEN_CLASSIFICATION);
        assertEquals("General Wishart",
                PolarimetricClassificationOp.UNSUPERVISED_GENERAL_WISHART_CLASSIFICATION);
        assertTrue(PolarimetricClassificationOp.UNSUPERVISED_CLOUDE_POTTIER_DUAL_POL_CLASSIFICATION
                .contains("Dual Pol"));
    }
}
