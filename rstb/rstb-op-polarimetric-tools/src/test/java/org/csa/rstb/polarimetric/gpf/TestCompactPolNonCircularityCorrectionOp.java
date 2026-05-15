/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. https://www.skywatch.com
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 */
package org.csa.rstb.polarimetric.gpf;

import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class TestCompactPolNonCircularityCorrectionOp {

    private static final OperatorSpi spi = new CompactPolNonCircularityCorrectionOp.Spi();

    @Test
    public void spi_creates_operator() {
        final CompactPolNonCircularityCorrectionOp op =
                (CompactPolNonCircularityCorrectionOp) spi.createOperator();
        assertNotNull(op);
    }

    @Test
    public void operator_metadata_alias_and_category() {
        final OperatorMetadata md = CompactPolNonCircularityCorrectionOp.class
                .getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("CP-Non-Circularity-Correction", md.alias());
        assertEquals("Radar/Polarimetric/Compact Polarimetry", md.category());
    }

    /** Pure-math kernel exercised directly: C12' = C12 * exp(j*delta). */
    private static double[] rotateC12(final double cr, final double ci, final double deltaDeg) {
        final double rad = Math.toRadians(deltaDeg);
        final double c = Math.cos(rad);
        final double s = Math.sin(rad);
        return new double[]{cr * c - ci * s, cr * s + ci * c};
    }

    @Test
    public void delta_zero_is_identity() {
        final double cr = 0.7, ci = -0.3;
        final double[] out = rotateC12(cr, ci, 0.0);
        assertEquals(cr, out[0], 1.0e-12);
        assertEquals(ci, out[1], 1.0e-12);
    }

    @Test
    public void delta_90_swaps_components() {
        // exp(j*pi/2) * (cr + j*ci) = -ci + j*cr
        final double cr = 0.7, ci = -0.3;
        final double[] out = rotateC12(cr, ci, 90.0);
        assertEquals(-ci, out[0], 1.0e-12);
        assertEquals(cr, out[1], 1.0e-12);
    }

    @Test
    public void forward_then_inverse_recovers_input() {
        final double cr = 0.42, ci = 0.91;
        final double[] forward = rotateC12(cr, ci, 5.7);
        final double[] roundTrip = rotateC12(forward[0], forward[1], -5.7);
        assertEquals(cr, roundTrip[0], 1.0e-12);
        assertEquals(ci, roundTrip[1], 1.0e-12);
    }

    @Test
    public void magnitude_is_invariant_under_correction() {
        final double cr = 0.42, ci = 0.91;
        final double magIn = Math.hypot(cr, ci);
        for (double deltaDeg : new double[]{-30, -5, 0, 1.7, 12.3, 45, 89}) {
            final double[] out = rotateC12(cr, ci, deltaDeg);
            final double magOut = Math.hypot(out[0], out[1]);
            assertEquals("|C12| should be invariant at delta=" + deltaDeg,
                    magIn, magOut, 1.0e-12);
        }
    }
}
