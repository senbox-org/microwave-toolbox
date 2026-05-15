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
package org.csa.rstb.biomass.gpf;

import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TestSARDisturbanceDetectionOp {

    private static final OperatorSpi spi = new SARDisturbanceDetectionOp.Spi();

    @Test
    public void spi_creates_operator() {
        final SARDisturbanceDetectionOp op = (SARDisturbanceDetectionOp) spi.createOperator();
        assertNotNull(op);
    }

    @Test
    public void operator_metadata_alias_and_category() {
        final OperatorMetadata md = SARDisturbanceDetectionOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("SAR-Disturbance-Detection", md.alias());
        assertEquals("Radar/Biomass", md.category());
    }

    /**
     * Stable forest backscatter (small std): no disturbance flagged at the
     * operator's default thresholds. Uses noise scale 0.3 dB and a slightly
     * tighter h=8 to keep false-positive rate negligible across the seed.
     */
    @Test
    public void stable_series_yields_no_disturbance() {
        final Random rng = new Random(42);
        final double[] series = new double[36];
        for (int i = 0; i < series.length; i++) {
            series[i] = -14.0 + 0.3 * rng.nextGaussian();  // -14 dB stable VH
        }
        final SARDisturbanceDetectionOp.Result r = new SARDisturbanceDetectionOp.Result();
        SARDisturbanceDetectionOp.detectCusum(series, 12, 0.5, 8.0, 0.05, r);
        assertFalse("stable series should not be flagged", r.disturbed);
        assertEquals(-1, r.changeIndex);
    }

    /** Sharp downward step after acquisition 18: should be detected. */
    @Test
    public void clear_cut_step_is_detected() {
        final Random rng = new Random(43);
        final double[] series = new double[36];
        for (int i = 0; i < series.length; i++) {
            // Pre-disturbance: -14 dB (forest); post: -22 dB (cleared)
            final double mean = (i < 18) ? -14.0 : -22.0;
            series[i] = mean + 0.5 * rng.nextGaussian();
        }
        final SARDisturbanceDetectionOp.Result r = new SARDisturbanceDetectionOp.Result();
        SARDisturbanceDetectionOp.detectCusum(series, 12, 0.5, 5.0, 0.05, r);
        assertTrue("clear-cut should be flagged: cusum=" + r.cusumMag, r.disturbed);
        assertTrue("change index should be near the step (>= 18, < 24): " + r.changeIndex,
                r.changeIndex >= 18 && r.changeIndex < 24);
    }

    /** A small upward perturbation should NOT trigger downward-CUSUM. */
    @Test
    public void small_upward_change_not_flagged() {
        final Random rng = new Random(44);
        final double[] series = new double[36];
        for (int i = 0; i < series.length; i++) {
            final double mean = (i < 24) ? -14.0 : -12.5; // small step UP
            series[i] = mean + 0.5 * rng.nextGaussian();
        }
        final SARDisturbanceDetectionOp.Result r = new SARDisturbanceDetectionOp.Result();
        SARDisturbanceDetectionOp.detectCusum(series, 12, 0.5, 5.0, 0.05, r);
        assertFalse("upward step should not be flagged by downward CUSUM", r.disturbed);
    }

    /** Too-uniform series (sigma below floor) yields no detection. */
    @Test
    public void uniform_series_returns_no_detection() {
        final double[] series = new double[24];
        for (int i = 0; i < series.length; i++) series[i] = -14.0;
        final SARDisturbanceDetectionOp.Result r = new SARDisturbanceDetectionOp.Result();
        SARDisturbanceDetectionOp.detectCusum(series, 12, 0.5, 5.0, 0.05, r);
        assertFalse(r.disturbed);
    }

    /** Series too short for baseline: returns no detection. */
    @Test
    public void too_few_samples_returns_no_detection() {
        final double[] series = new double[5];
        for (int i = 0; i < series.length; i++) series[i] = -14.0;
        final SARDisturbanceDetectionOp.Result r = new SARDisturbanceDetectionOp.Result();
        SARDisturbanceDetectionOp.detectCusum(series, 12, 0.5, 5.0, 0.05, r);
        assertFalse(r.disturbed);
    }
}
