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
package org.csa.rstb.polarimetric.gpf;

import org.csa.rstb.polarimetric.gpf.support.PolarimetricParameters;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class TestCompactPolParametersOp {

    private static final OperatorSpi spi = new CompactPolParametersOp.Spi();

    @Test
    public void spi_creates_operator() {
        final CompactPolParametersOp op = (CompactPolParametersOp) spi.createOperator();
        assertNotNull(op);
    }

    @Test
    public void operator_metadata_alias_and_category() {
        final OperatorMetadata md = CompactPolParametersOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("CP-Parameters", md.alias());
        assertEquals("Radar/Polarimetric/Compact Polarimetry", md.category());
    }

    @Test
    public void span_equals_C11_plus_C22() {
        final double[][] Cr = {{2.5, 0.1}, {0.1, 1.5}};
        final double[][] Ci = {{0.0, -0.2}, {0.2, 0.0}};
        final PolarimetricParameters p =
                PolarimetricParameters.computePolarimetricParameters(Cr, Ci, "RCH", false);
        assertEquals(4.0, p.Span, 1.0e-12);
        // Reserved fields should be zero (v1).
        assertEquals(0.0, p.PedestalHeight, 0.0);
        assertEquals(0.0, p.RVI, 0.0);
    }
}
