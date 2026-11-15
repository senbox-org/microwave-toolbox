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
package org.csa.rstb.polarimetric.gpf;

import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

/**
 * Unit tests for {@link CrossChannelSNRCorrectionOp}.
 */
public class TestCrossChannelSNRCorrectionOp {

    @Test
    public void testSpiCreatesOperator() {
        final CrossChannelSNRCorrectionOp op =
                (CrossChannelSNRCorrectionOp) new CrossChannelSNRCorrectionOp.Spi().createOperator();
        assertNotNull(op);
    }

    @Test
    public void testOperatorMetadata() {
        final OperatorMetadata md = CrossChannelSNRCorrectionOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("Cross-Channel-SNR-Correction", md.alias());
    }

    @Test
    public void testDescriptionIsNotCopyPaste() {
        final OperatorMetadata md = CrossChannelSNRCorrectionOp.class.getAnnotation(OperatorMetadata.class);
        assertFalse(md.description().contains("general polarimetric parameters"));
    }

    /**
     * Noise model of the operator: HV = s + n1, VH = s + n2, with s the reciprocal cross-pol signal and
     * n1, n2 uncorrelated noise of equal power N. Then &lt;HV VH*&gt; = S, &lt;|HV|^2&gt; = S + N and the
     * HV/VH coherence is gamma = S / (S + N). The noise-free power S = gamma (S + N), so the amplitude
     * must be scaled by sqrt(gamma).
     * <p>Test input (8 x 8, window covering the whole image so the window statistics are exact):
     * s = 1, n1 = (-1)^x, n2 = (-1)^y (mutually orthogonal over the image). SNR = 0 dB, gamma = 0.5,
     * S = 1, S + N = 2. The corrected HV and VH power must be 1 (was 0.5 = -3 dB with a gamma factor).</p>
     */
    @Test
    public void testCorrectedCrossPolPowerEqualsNoiseFreePower() throws Exception {
        final int w = 8, h = 8;
        final double[][] kRe = new double[4][w * h];
        final double[][] kIm = new double[4][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                final int p = y * w + x;
                kRe[0][p] = 3.0;                                   // HH (copied through)
                kRe[1][p] = 1.0 + ((x % 2 == 0) ? 1 : -1);         // HV = s + n1
                kRe[2][p] = 1.0 + ((y % 2 == 0) ? 1 : -1);         // VH = s + n2
                kRe[3][p] = 2.0;                                   // VV (copied through)
            }
        }
        final Product src = SyntheticPolProducts.createFullProduct(w, h, kRe, kIm);

        final CrossChannelSNRCorrectionOp op =
                (CrossChannelSNRCorrectionOp) new CrossChannelSNRCorrectionOp.Spi().createOperator();
        op.setSourceProduct(src);
        op.setParameter("windowSize", 17);   // half window 8 covers the whole 8 x 8 image
        final Product trg = op.getTargetProduct();

        for (String pol : new String[]{"HV", "VH"}) {
            final float[] i = SyntheticPolProducts.readBand(trg, "i_" + pol);
            final float[] q = SyntheticPolProducts.readBand(trg, "q_" + pol);
            double power = 0;
            for (int p = 0; p < i.length; p++) {
                power += i[p] * i[p] + q[p] * q[p];
            }
            power /= i.length;
            assertEquals(pol + " corrected power", 1.0, power, 1e-5);
        }
        final float[] hh = SyntheticPolProducts.readBand(trg, "i_HH");
        assertEquals(3.0, hh[0], 0.0);
    }
}
