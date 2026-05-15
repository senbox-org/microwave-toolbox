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

import org.csa.rstb.biomass.gpf.treeheight.DualPolFlatEarthTopoPhaseRemovalOp;
import org.csa.rstb.biomass.gpf.treeheight.DualPolForestHeightEstimationOp;
import org.csa.rstb.biomass.gpf.treeheight.DualPolPolarimetricCoherenceOp;
import org.csa.rstb.biomass.gpf.treeheight.SinglePolCoherenceCompensationOp;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TestBiomassOps {

    @Test
    public void biomasar_spi_and_metadata() {
        final BIOMASAROp.Spi spi = new BIOMASAROp.Spi();
        assertNotNull(spi.createOperator());
        final OperatorMetadata md = BIOMASAROp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("BIOMASAR", md.alias());
        assertEquals("Radar/Biomass", md.category());
    }

    @Test
    public void dual_pol_forest_height_spi_and_metadata() {
        final DualPolForestHeightEstimationOp.Spi spi = new DualPolForestHeightEstimationOp.Spi();
        assertNotNull(spi.createOperator());
        final OperatorMetadata md = DualPolForestHeightEstimationOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("DualPolForestHeightEstimation", md.alias());
    }

    @Test
    public void dual_pol_polarimetric_coherence_spi_and_metadata() {
        final DualPolPolarimetricCoherenceOp.Spi spi = new DualPolPolarimetricCoherenceOp.Spi();
        assertNotNull(spi.createOperator());
        final OperatorMetadata md = DualPolPolarimetricCoherenceOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("DualPolPolarimetricCoherence", md.alias());
    }

    @Test
    public void single_pol_coherence_compensation_spi_and_metadata() {
        final SinglePolCoherenceCompensationOp.Spi spi = new SinglePolCoherenceCompensationOp.Spi();
        assertNotNull(spi.createOperator());
        final OperatorMetadata md = SinglePolCoherenceCompensationOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("SinglePolCoherenceCompensation", md.alias());
    }

    @Test
    public void dual_pol_flat_earth_topo_phase_removal_spi_and_metadata() {
        final DualPolFlatEarthTopoPhaseRemovalOp.Spi spi = new DualPolFlatEarthTopoPhaseRemovalOp.Spi();
        assertNotNull(spi.createOperator());
        final OperatorMetadata md = DualPolFlatEarthTopoPhaseRemovalOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("DualPolFlatEarthTopoPhaseRemoval", md.alias());
    }
}
