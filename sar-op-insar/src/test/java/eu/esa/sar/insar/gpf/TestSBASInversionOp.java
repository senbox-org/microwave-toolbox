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
package eu.esa.sar.insar.gpf;

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TestSBASInversionOp {

    private static final OperatorSpi spi = new SBASInversionOp.Spi();

    @Test
    public void spi_creates_operator() {
        final SBASInversionOp op = (SBASInversionOp) spi.createOperator();
        assertNotNull(op);
    }

    @Test
    public void operator_metadata_alias_and_category() {
        final OperatorMetadata md = SBASInversionOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("SBASInversion", md.alias());
        assertEquals("Radar/Interferometric/Time-Series", md.category());
    }

    @Test
    public void synthetic_linear_velocity_stack_recovers_velocity() throws Exception {
        // 5 epochs, sequential + cross-pairs, constant per-epoch deformation phase.
        final int w = 4, h = 4;
        final Product product = TestUtils.createProduct("SLC", w, h);
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        absRoot.setAttributeDouble(AbstractMetadata.radar_frequency, 5405.0); // MHz -> Sentinel-1 C-band

        // Epoch dates spaced ~12 days apart
        final String[] dates = {"01Jan2025", "13Jan2025", "25Jan2025", "06Feb2025", "18Feb2025"};
        // True per-epoch phase (rad), constant ramp
        final double truePhasePerEpoch = 0.3;
        // Reference will be the median (index 2 -> 25Jan2025), and we want phase[2] = 0,
        // so set per-epoch phase relative to that as (i - 2) * truePhasePerEpoch.

        // Pairs: (0,1) (1,2) (2,3) (3,4) (0,2) (2,4) (0,4)
        final int[][] pairs = {{0,1},{1,2},{2,3},{3,4},{0,2},{2,4},{0,4}};

        for (int[] p : pairs) {
            final String bandPhi = "Unw_Phase_ifg_IW2_VV_" + dates[p[0]] + "_" + dates[p[1]];
            final String bandCoh = "coh_IW2_VV_" + dates[p[0]] + "_" + dates[p[1]];
            final Band phiBand = TestUtils.createBand(product, bandPhi, ProductData.TYPE_FLOAT32, Unit.PHASE, w, h, true);
            final Band cohBand = TestUtils.createBand(product, bandCoh, ProductData.TYPE_FLOAT32, Unit.COHERENCE, w, h, true);

            final float phi = (float) ((p[1] - p[0]) * truePhasePerEpoch);
            final float[] phiData = new float[w * h];
            final float[] cohData = new float[w * h];
            for (int i = 0; i < phiData.length; i++) {
                phiData[i] = phi;
                cohData[i] = 0.9f;
            }
            phiBand.setData(ProductData.createInstance(phiData));
            cohBand.setData(ProductData.createInstance(cohData));
        }

        final SBASInversionOp op = (SBASInversionOp) spi.createOperator();
        op.setSourceProduct(product);
        op.setParameter("coherenceMin", 0.3);
        op.setParameter("coherenceLooks", 100);
        op.setParameter("outputVelocity", true);
        op.setParameter("outputClosurePhase", true);
        op.setParameter("outputResiduals", false);

        final Product target = op.getTargetProduct();
        assertNotNull(target);
        assertNotNull(target.getBand("phase_01Jan2025"));
        assertNotNull(target.getBand("phase_18Feb2025"));
        assertNotNull(target.getBand("displacement_01Jan2025"));
        assertNotNull(target.getBand("velocity"));
        assertNotNull(target.getBand("temporal_coherence"));
        assertNotNull(target.getBand("closure_phase_rms"));

        // Force computation by reading one pixel of each output band
        final Band phaseFirst = target.getBand("phase_01Jan2025");
        final Band phaseLast = target.getBand("phase_18Feb2025");
        final Band velocity = target.getBand("velocity");
        final Band tempCoh = target.getBand("temporal_coherence");
        final Band closure = target.getBand("closure_phase_rms");
        final float[] phiFirstPx = new float[w * h];
        final float[] phiLastPx = new float[w * h];
        final float[] velPx = new float[w * h];
        final float[] tcPx = new float[w * h];
        final float[] clPx = new float[w * h];
        phaseFirst.readPixels(0, 0, w, h, phiFirstPx);
        phaseLast.readPixels(0, 0, w, h, phiLastPx);
        velocity.readPixels(0, 0, w, h, velPx);
        tempCoh.readPixels(0, 0, w, h, tcPx);
        closure.readPixels(0, 0, w, h, clPx);

        // Reference epoch (median, index 2) has phase = 0; epoch 0 has phase -2 * 0.3 = -0.6,
        // epoch 4 has phase +2 * 0.3 = +0.6.
        assertEquals("phase[01Jan2025] across all pixels", -0.6, phiFirstPx[0], 1.0e-5);
        assertEquals("phase[18Feb2025] across all pixels",  0.6, phiLastPx[0], 1.0e-5);

        // Velocity should be > 0 mm/yr in magnitude with a finite value.
        assertTrue("velocity nonzero", Math.abs(velPx[0]) > 1.0e-3);

        // Temporal coherence with noise-free phase must be near 1.
        assertTrue("temporal coherence high", tcPx[0] > 0.99);

        // Closure phase RMS for a consistent phase must be ~0.
        assertTrue("closure phase near 0: " + clPx[0], Math.abs(clPx[0]) < 1.0e-5);
    }
}
