/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc.
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
package eu.esa.sar.sar.gpf.geometric;

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Terrain-Correction must recognise an InSAR result so its default band selection geocodes the
 * complex pair as complex (carrying the Phase band) instead of collapsing it to Intensity — the
 * collapse silently discards the interferometric phase, which is the very content being
 * terrain-corrected. Detection: complex bands present AND (a coherence-unit band OR the
 * coregistered-stack flag).
 */
public class TestTerrainCorrectionInSARDetection {

    private static Product baseProduct() {
        final Product p = new Product("ifg", "type", 10, 10);
        AbstractMetadata.addAbstractedMetadataHeader(p.getMetadataRoot());
        return p;
    }

    private static void addComplexPair(final Product p) {
        final Band i = p.addBand("i_ifg_IW3_VV", ProductData.TYPE_FLOAT32);
        i.setUnit(Unit.REAL);
        final Band q = p.addBand("q_ifg_IW3_VV", ProductData.TYPE_FLOAT32);
        q.setUnit(Unit.IMAGINARY);
    }

    @Test
    public void testInterferogramWithCoherenceIsDetected() {
        final Product p = baseProduct();
        addComplexPair(p);
        final Band coh = p.addBand("coh_IW3_VV", ProductData.TYPE_FLOAT32);
        coh.setUnit(Unit.COHERENCE);
        assertTrue(RangeDopplerGeocodingOp.isInSARProduct(
                AbstractMetadata.getAbstractedMetadata(p), p));
    }

    @Test
    public void testCoregisteredComplexStackIsDetected() {
        final Product p = baseProduct();
        addComplexPair(p);
        final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(p);
        abs.setAttributeInt(AbstractMetadata.coregistered_stack, 1);
        assertTrue(RangeDopplerGeocodingOp.isInSARProduct(abs, p));
    }

    @Test
    public void testPlainSlcIsNotDetected() {
        // an ordinary SLC is complex too, but has neither coherence nor the stack flag:
        // the intensity collapse remains the right default there
        final Product p = baseProduct();
        addComplexPair(p);
        assertFalse(RangeDopplerGeocodingOp.isInSARProduct(
                AbstractMetadata.getAbstractedMetadata(p), p));
    }

    @Test
    public void testDetectedAmplitudeCoherenceProductIsNotDetected() {
        // coherence alongside real-valued amplitude (no complex pair): nothing complex to
        // preserve, the default band handling is already correct
        final Product p = baseProduct();
        final Band amp = p.addBand("Amplitude_VV", ProductData.TYPE_FLOAT32);
        amp.setUnit(Unit.AMPLITUDE);
        final Band coh = p.addBand("coh_IW3_VV", ProductData.TYPE_FLOAT32);
        coh.setUnit(Unit.COHERENCE);
        assertFalse(RangeDopplerGeocodingOp.isInSARProduct(
                AbstractMetadata.getAbstractedMetadata(p), p));
    }
}
