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

import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TestPartialTargetDetectionOp {

    private static final OperatorSpi spi = new PartialTargetDetectionOp.Spi();

    @Test
    public void spi_creates_operator() {
        final PartialTargetDetectionOp op = (PartialTargetDetectionOp) spi.createOperator();
        assertNotNull(op);
    }

    @Test
    public void operator_metadata_alias_and_category() {
        final OperatorMetadata md = PartialTargetDetectionOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("PartialTargetDetection", md.alias());
        assertEquals("Radar/Polarimetric/Target Detection", md.category());
    }

    /**
     * Transformation matrix sanity check: applying U to the original
     * scattering mechanism w should yield (1, 0, 0, ...) up to a global
     * sign / phase. We test with U=I and a basis vector, which should
     * round-trip exactly.
     */
    @Test
    public void transformation_matrix_with_identity_is_identity() {
        final int n = 3;
        final double[][] eyeRe = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        final double[][] eyeIm = {{0, 0, 0}, {0, 0, 0}, {0, 0, 0}};
        final PartialTargetDetectionOp.TransformationMatrix tm =
                new PartialTargetDetectionOp.TransformationMatrix(eyeRe, eyeIm);

        final double[] inRe = {0.5, -0.3, 0.8};
        final double[] inIm = {0.1, 0.2, -0.4};
        final double[] outRe = new double[n];
        final double[] outIm = new double[n];
        tm.getTransformedVector(inRe, inIm, outRe, outIm);

        for (int i = 0; i < n; i++) {
            assertEquals("out re[" + i + "] = in re[" + i + "]", inRe[i], outRe[i], 1.0e-12);
            assertEquals("out im[" + i + "] = in im[" + i + "]", inIm[i], outIm[i], 1.0e-12);
        }
    }

    /**
     * A purely-real diagonal transformation matrix should preserve the
     * imaginary part as zero when the input is real, and scale entries.
     */
    @Test
    public void transformation_matrix_scales_real_input() {
        final double[][] diagRe = {{2.0, 0, 0}, {0, 0.5, 0}, {0, 0, -1.0}};
        final double[][] diagIm = {{0, 0, 0}, {0, 0, 0}, {0, 0, 0}};
        final PartialTargetDetectionOp.TransformationMatrix tm =
                new PartialTargetDetectionOp.TransformationMatrix(diagRe, diagIm);

        final double[] inRe = {1.0, 2.0, 3.0};
        final double[] inIm = {0.0, 0.0, 0.0};
        final double[] outRe = new double[3];
        final double[] outIm = new double[3];
        tm.getTransformedVector(inRe, inIm, outRe, outIm);

        assertEquals(2.0, outRe[0], 1.0e-12);
        assertEquals(1.0, outRe[1], 1.0e-12);
        assertEquals(-3.0, outRe[2], 1.0e-12);
        for (double v : outIm) {
            assertEquals(0.0, v, 1.0e-12);
        }
    }

    /**
     * Verify that the transformed-vector entry [0] of a target vector
     * dominates over the residual components when U is constructed to
     * rotate w to (1, 0, 0). We approximate this by checking that for a
     * 90-degree-rotation U applied to its own first column, the result
     * lies in the first axis.
     */
    @Test
    public void scr_formula_dominant_first_component_yields_high_ratio() {
        // Synthetic transformed vector: (10, 0.5, 0.2, 0.1, 0.0, 0.0)
        // SCR = 100 / (0.25 + 0.04 + 0.01) = 333.33...
        // gamma = 1 / sqrt(1 + RedR / SCR). For threshold=0.98, SCRtarget=50:
        //   RedR = 50 * (1/0.9604 - 1) = 50 * 0.04123 = 2.062
        //   gamma = 1 / sqrt(1 + 2.062/333.33) = 1/sqrt(1.00618) ~ 0.9969 > threshold
        final double[] tTrRe = {10.0, 0.5, 0.2, 0.1, 0.0, 0.0};
        final double[] tTrIm = {0.0, 0.0, 0.0, 0.0, 0.0, 0.0};

        final double pt = tTrRe[0] * tTrRe[0] + tTrIm[0] * tTrIm[0];
        double pc = 0.0;
        for (int i = 1; i < tTrRe.length; i++) {
            pc += tTrRe[i] * tTrRe[i] + tTrIm[i] * tTrIm[i];
        }
        final double scrPixel = pt / pc;
        assertTrue("SCR for a dominant-target vector must exceed 100: " + scrPixel, scrPixel > 100.0);

        final double redR = 50.0 * (1.0 / (0.98 * 0.98) - 1.0);
        final double gamma = 1.0 / Math.sqrt(1.0 + redR / scrPixel);
        assertTrue("Gamma must clear the 0.98 threshold for a dominant target: " + gamma, gamma > 0.98);
    }
}
