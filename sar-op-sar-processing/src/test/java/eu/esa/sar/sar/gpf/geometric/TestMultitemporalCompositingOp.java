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
package eu.esa.sar.sar.gpf.geometric;

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link MultitemporalCompositingOp}.
 */
public class TestMultitemporalCompositingOp {

    @Test
    public void testSpiCreatesOperator() {
        final MultitemporalCompositingOp op = (MultitemporalCompositingOp) new MultitemporalCompositingOp.Spi().createOperator();
        assertNotNull(op);
    }

    @Test
    public void testOperatorMetadata() {
        final OperatorMetadata md = MultitemporalCompositingOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("Multitemporal-Compositing", md.alias());
    }

    private static final int W = 3;
    private static final int H = 2;

    private static void addBand(Product p, String name, float value) {
        final Band b = p.addBand(name, ProductData.TYPE_FLOAT32);
        final float[] data = new float[W * H];
        Arrays.fill(data, value);
        b.setRasterData(ProductData.createInstance(data));
    }

    private static Product createStack() {
        final Product p = new Product("stack", "GRD", W, H);
        AbstractMetadata.addAbstractedMetadataHeader(p.getMetadataRoot());
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(p);
        absRoot.setAttributeDouble(AbstractMetadata.radar_frequency, 5405.0);
        absRoot.setAttributeInt(AbstractMetadata.coregistered_stack, 1);
        absRoot.setAttributeInt(AbstractMetadata.abs_calibration_flag, 1);
        absRoot.setAttributeString(AbstractMetadata.map_projection, "WGS84(DD)");
        return p;
    }

    private static float[] composite(Product src, String bandName) {
        final MultitemporalCompositingOp op = (MultitemporalCompositingOp) new MultitemporalCompositingOp.Spi().createOperator();
        op.setSourceProduct(src);
        final Band composite = op.getTargetProduct().getBand(bandName);
        assertNotNull(bandName, composite);
        final float[] out = new float[W * H];
        composite.getSourceImage().getData().getSamples(0, 0, W, H, 0, out);
        return out;
    }

    private static String expectError(Product src) {
        final MultitemporalCompositingOp op = (MultitemporalCompositingOp) new MultitemporalCompositingOp.Spi().createOperator();
        op.setSourceProduct(src);
        try {
            op.getTargetProduct();
            fail("expected OperatorException");
        } catch (OperatorException e) {
            return e.getMessage();
        }
        return null;
    }

    /**
     * Local-resolution weighting (Small 2012): composite = sum(g_i / A_i) / sum(1 / A_i).
     * Reference: gamma0 2, area 1; Secondary: gamma0 4, area 4 -> (2 + 1) / (1 + 0.25) = 2.4.
     * Pairing by band order would weight each gamma0 with the other date's area: (0.5 + 4) / 1.25 = 3.6.
     * Band names are as CreateStack writes them: the secondary counter is per band, so the
     * backscatter band is _sec1_ and its simulated image _sec2_ (CreateStackOp suffix SEC + cnt++).
     */
    @Test
    public void testSimulatedImagePairedByDateNotByOrder() {
        final Product src = createStack();
        addBand(src, "Gamma0_VV_ref_01Jan2020", 2.0f);
        addBand(src, "Gamma0_VV_sec1_13Jan2020", 4.0f);
        // simulated-image bands in the opposite date order
        addBand(src, "simulatedImage_sec2_13Jan2020", 4.0f);
        addBand(src, "simulatedImage_ref_01Jan2020", 1.0f);

        for (float v : composite(src, "Gamma0_VV")) {
            assertEquals(2.4f, v, 1e-6f);
        }
    }

    /**
     * Dual-pol stack: VH ref 1 / sec 3, VV ref 2 / sec 4, areas 1 (ref) and 4 (sec).
     * VH: (1 + 0.75) / 1.25 = 1.4; VV: (2 + 1) / 1.25 = 2.4.
     */
    @Test
    public void testDualPolStack() {
        final Product src = createStack();
        addBand(src, "Gamma0_VH_ref_01Jan2020", 1.0f);
        addBand(src, "Gamma0_VV_ref_01Jan2020", 2.0f);
        addBand(src, "simulatedImage_ref_01Jan2020", 1.0f);
        addBand(src, "Gamma0_VH_sec1_13Jan2020", 3.0f);
        addBand(src, "Gamma0_VV_sec2_13Jan2020", 4.0f);
        addBand(src, "simulatedImage_sec3_13Jan2020", 4.0f);

        for (float v : composite(src, "Gamma0_VH")) {
            assertEquals(1.4f, v, 1e-6f);
        }
        for (float v : composite(src, "Gamma0_VV")) {
            assertEquals(2.4f, v, 1e-6f);
        }
    }

    @Test
    public void testBandWithoutStackTagGivesClearError() {
        final Product src = createStack();
        addBand(src, "Gamma0_VV", 2.0f);
        addBand(src, "simulatedImage", 1.0f);

        final String msg = expectError(src);
        assertTrue(msg, msg.contains("Gamma0_VV") && msg.contains("_ref"));
    }

    @Test
    public void testMissingSimulatedImageGivesClearError() {
        final Product src = createStack();
        addBand(src, "Gamma0_VV_ref_01Jan2020", 2.0f);
        addBand(src, "Gamma0_VV_sec1_13Jan2020", 4.0f);
        addBand(src, "simulatedImage_ref_01Jan2020", 1.0f);

        final String msg = expectError(src);
        assertTrue(msg, msg.contains("Gamma0_VV_sec1_13Jan2020"));
    }

    /** Two secondaries acquired on the same date: the simulated image cannot be told apart by date. */
    @Test
    public void testSameDateSecondariesAreAmbiguous() {
        final Product src = createStack();
        addBand(src, "Gamma0_VV_ref_01Jan2020", 2.0f);
        addBand(src, "simulatedImage_ref_01Jan2020", 1.0f);
        addBand(src, "Gamma0_VV_sec1_13Jan2020", 4.0f);
        addBand(src, "simulatedImage_sec2_13Jan2020", 4.0f);
        addBand(src, "Gamma0_VV_sec3_13Jan2020", 5.0f);
        addBand(src, "simulatedImage_sec4_13Jan2020", 2.0f);

        final String msg = expectError(src);
        assertTrue(msg, msg.contains("simulatedImage_sec2_13Jan2020") && msg.contains("simulatedImage_sec4_13Jan2020"));
    }
}
