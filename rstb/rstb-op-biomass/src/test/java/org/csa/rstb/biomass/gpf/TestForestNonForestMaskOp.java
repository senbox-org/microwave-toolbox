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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class TestForestNonForestMaskOp {

    private static final OperatorSpi spi = new ForestNonForestMaskOp.Spi();

    @Test
    public void spi_creates_operator() {
        final ForestNonForestMaskOp op = (ForestNonForestMaskOp) spi.createOperator();
        assertNotNull(op);
    }

    @Test
    public void operator_metadata_alias_and_category() {
        final OperatorMetadata md = ForestNonForestMaskOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("Forest-NonForest-Mask", md.alias());
        assertEquals("Radar/Biomass", md.category());
    }

    /** Below cross-pol threshold => non-forest. */
    @Test
    public void below_threshold_is_non_forest() {
        final ForestNonForestMaskOp op = new ForestNonForestMaskOp();
        setField(op, "crossPolThresholdDb", -19.0);
        setField(op, "maxRatioDb", 12.0);
        setField(op, "maxCV", 0.0);
        // VH = -25 dB (typical water / bare soil), VV = -15 dB
        assertEquals(0, op.classify(-25.0, -15.0, Double.NaN));
    }

    /** Above cross-pol threshold and reasonable co/cross ratio => forest. */
    @Test
    public void typical_forest_classified_as_forest() {
        final ForestNonForestMaskOp op = new ForestNonForestMaskOp();
        setField(op, "crossPolThresholdDb", -19.0);
        setField(op, "maxRatioDb", 12.0);
        setField(op, "maxCV", 0.0);
        // VH = -15 dB, VV = -8 dB -> ratio = 7 dB (typical dense forest)
        assertEquals(1, op.classify(-15.0, -8.0, Double.NaN));
    }

    /** Above threshold but high VV/VH ratio (urban / dry soil) => non-forest. */
    @Test
    public void high_ratio_excludes_urban() {
        final ForestNonForestMaskOp op = new ForestNonForestMaskOp();
        setField(op, "crossPolThresholdDb", -19.0);
        setField(op, "maxRatioDb", 12.0);
        setField(op, "maxCV", 0.0);
        // VH = -17 dB, VV = -2 dB -> ratio = 15 dB (urban / dry bare)
        assertEquals(0, op.classify(-17.0, -2.0, Double.NaN));
    }

    /** Above threshold but high temporal CV (cropland) => non-forest. */
    @Test
    public void high_cv_excludes_cropland() {
        final ForestNonForestMaskOp op = new ForestNonForestMaskOp();
        setField(op, "crossPolThresholdDb", -19.0);
        setField(op, "maxRatioDb", 100.0);
        setField(op, "maxCV", 0.5);
        // VH backscatter looks like forest but CV is high (annual crop cycle)
        assertEquals(0, op.classify(-15.0, Double.NaN, 0.8));
        // Same backscatter, low CV (real forest) -> forest
        assertEquals(1, op.classify(-15.0, Double.NaN, 0.3));
    }

    private static void setField(final Object obj, final String name, final Object value) {
        try {
            final java.lang.reflect.Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(obj, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
