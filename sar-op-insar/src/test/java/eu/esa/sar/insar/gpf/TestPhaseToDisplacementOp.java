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
package eu.esa.sar.insar.gpf;

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link PhaseToDisplacementOp}.
 */
public class TestPhaseToDisplacementOp {

    @Test
    public void testSpiCreatesOperator() {
        final PhaseToDisplacementOp op = (PhaseToDisplacementOp) new PhaseToDisplacementOp.Spi().createOperator();
        assertNotNull(op);
    }

    @Test
    public void testOperatorMetadata() {
        final OperatorMetadata md = PhaseToDisplacementOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("PhaseToDisplacement", md.alias());
    }

    /** Source no-data (0-filled or NaN) must come out as NaN no-data, not as a finite 0 m displacement. */
    @Test
    public void testNoDataPropagates() {
        final int w = 4, h = 1;
        final Product src = new Product("unw", "SLC", w, h);
        final MetadataElement abs = AbstractMetadata.addAbstractedMetadataHeader(src.getMetadataRoot());
        abs.setAttributeDouble(AbstractMetadata.radar_frequency, 5405.0);
        final Band unw = src.addBand("Unw_Phase", ProductData.TYPE_FLOAT32);
        unw.setNoDataValue(0.0);
        unw.setNoDataValueUsed(true);
        unw.setRasterData(ProductData.createInstance(new float[]{(float) Math.PI, 0.0f, Float.NaN, -1.0f}));

        final PhaseToDisplacementOp op = new PhaseToDisplacementOp();
        op.setSourceProduct(src);
        final Band disp = op.getTargetProduct().getBand("displacement");
        assertTrue(disp.isNoDataValueUsed());
        assertTrue(Double.isNaN(disp.getNoDataValue()));

        final float[] out = new float[w];
        disp.getSourceImage().getData().getPixels(0, 0, w, h, out);
        final double lambda = 299792458.0 / 5405.0e6;
        assertEquals(-lambda / 4.0, out[0], 1e-6);
        assertTrue("source no-data value", Float.isNaN(out[1]));
        assertTrue("source NaN", Float.isNaN(out[2]));
        assertEquals(lambda / (4.0 * Math.PI), out[3], 1e-6);
    }
}
