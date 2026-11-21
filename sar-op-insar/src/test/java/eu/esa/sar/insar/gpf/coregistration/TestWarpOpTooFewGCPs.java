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
package eu.esa.sar.insar.gpf.coregistration;

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.GcpDescriptor;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Placemark;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.ProductNodeGroup;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A Secondary with too few GCPs for the complex polynomial model (CPM): with exactly as many GCPs as the
 * degree-2 polynomial has unknowns (6) the CPM has no redundancy, so that band falls back to the plain
 * polynomial fit; with fewer than 3 there is no warp at all and the band must be no-data, flagged in metadata.
 */
public class TestWarpOpTooFewGCPs {

    static {
        TestUtils.initTestEnvironment();
    }

    private static final int W = 64, H = 64;
    private static final double NO_DATA = -9999.0;
    private static final double DX = 1.5, DY = -0.5;
    private static final String REF_I = "i_VV_ref_01Jan2020", REF_Q = "q_VV_ref_01Jan2020";
    private static final String SEC_I = "i_VV_sec1_13Jan2020", SEC_Q = "q_VV_sec1_13Jan2020";

    @After
    public void clearGcps() {
        GCPManager.instance().removeAllGcpGroups();
    }

    private static Band addBand(final Product p, final String name, final String unit, final double shiftX,
                                final double shiftY) {
        final float[] data = new float[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                data[y * W + x] = (float) (100 + (x - shiftX) + 2 * (y - shiftY));
            }
        }
        final Band b = new Band(name, ProductData.TYPE_FLOAT32, W, H);
        b.setUnit(unit);
        b.setNoDataValue(NO_DATA);
        b.setNoDataValueUsed(true);
        b.setRasterData(ProductData.createInstance(data));
        p.addBand(b);
        return b;
    }

    private static Product stack(final int numGcps) {
        final Product p = new Product("stack", "SLC", W, H);
        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(p.getMetadataRoot());
        absRoot.setAttributeInt(AbstractMetadata.coregistered_stack, 1);
        absRoot.setAttributeString("processed_slave", SEC_I);
        final Band refI = addBand(p, REF_I, Unit.REAL, 0, 0);
        addBand(p, REF_Q, Unit.IMAGINARY, 0, 0);
        final Band secI = addBand(p, SEC_I, Unit.REAL, DX, DY);
        addBand(p, SEC_Q, Unit.IMAGINARY, DX, DY);

        final ProductNodeGroup<Placemark> refGcps = GCPManager.instance().getGcpGroup(refI);
        final ProductNodeGroup<Placemark> secGcps = GCPManager.instance().getGcpGroup(secI);
        final double[][] pos = {{10, 10}, {50, 12}, {30, 30}, {12, 52}, {52, 50}, {31, 8}};
        for (int k = 0; k < numGcps; k++) {
            refGcps.add(Placemark.createPointPlacemark(GcpDescriptor.getInstance(), "gcp_" + k, "gcp_" + k, "",
                    new PixelPos(pos[k][0], pos[k][1]), null, null));
            secGcps.add(Placemark.createPointPlacemark(GcpDescriptor.getInstance(), "gcp_" + k, "gcp_" + k, "",
                    new PixelPos(pos[k][0] + DX, pos[k][1] + DY), null, null));
        }
        return p;
    }

    private static Product warp(final Product source) {
        final WarpOp op = (WarpOp) new WarpOp.Spi().createOperator();
        op.setSourceProduct(source);
        return op.getTargetProduct();
    }

    private static int warpValid(final Product target, final String band) {
        final MetadataElement bandElem = AbstractMetadata.getBandAbsMetadata(
                AbstractMetadata.getAbstractedMetadata(target), band, false);
        return bandElem.getElement("WarpData").getAttributeInt("warpValid");
    }

    /** 6 GCPs at degree 2: no CPM redundancy, so this band uses the plain polynomial fit (not zeros). */
    @Test
    public void testExactlyDeterminedSecondaryFallsBackToPlainFit() throws Exception {
        final Product target = warp(stack(6));
        final float[] out = new float[W * H];
        target.getBand(SEC_I).readPixels(0, 0, W, H, out);
        final int k = 32 * W + 32;
        assertEquals("warped back onto the reference grid", 100 + 32 + 2 * 32, out[k], 0.05);
        assertEquals(1, warpValid(target, SEC_I));
    }

    /** 2 GCPs: no warp is possible; the band must be no-data, not a valid-looking zero image. */
    @Test
    public void testSecondaryWithoutWarpIsNoData() throws Exception {
        final Product target = warp(stack(2));
        final float[] out = new float[W * H];
        target.getBand(SEC_I).readPixels(0, 0, W, H, out);
        for (float v : out) {
            assertEquals(NO_DATA, v, 0.0);
        }
        assertTrue(target.getBand(SEC_I).isNoDataValueUsed());
        assertEquals(0, warpValid(target, SEC_I));
    }
}
