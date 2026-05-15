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

public class TestCoherenceOptimizationOp {

    private static final OperatorSpi spi = new CoherenceOptimizationOp.Spi();

    @Test
    public void spi_creates_operator() {
        final CoherenceOptimizationOp op = (CoherenceOptimizationOp) spi.createOperator();
        assertNotNull(op);
    }

    @Test
    public void operator_metadata_alias_and_category() {
        final OperatorMetadata md = CoherenceOptimizationOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("CoherenceOptimization", md.alias());
        assertEquals("Radar/Polarimetric", md.category());
    }

    /**
     * Sanity check the CohData inner record's data-roundtrip.
     */
    @Test
    public void cohdata_records_six_components() {
        final CoherenceOptimizationOp.CohData d = new CoherenceOptimizationOp.CohData(
                0.91, 0.12, 0.5, 0.0, 0.1, -0.05);
        assertEquals(0.91, d.i_coh_opt_1, 0.0);
        assertEquals(0.12, d.q_coh_opt_1, 0.0);
        assertEquals(0.5, d.i_coh_opt_2, 0.0);
        assertEquals(0.0, d.q_coh_opt_2, 0.0);
        assertEquals(0.1, d.i_coh_opt_3, 0.0);
        assertEquals(-0.05, d.q_coh_opt_3, 0.0);
        // magnitude squared bounded
        final double mag1Sq = d.i_coh_opt_1 * d.i_coh_opt_1 + d.q_coh_opt_1 * d.q_coh_opt_1;
        assertTrue("|gamma_1|^2 should be in [0, 2]", mag1Sq >= 0.0 && mag1Sq < 2.0);
    }
}
