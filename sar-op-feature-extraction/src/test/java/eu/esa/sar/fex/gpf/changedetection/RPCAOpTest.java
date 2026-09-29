/*
 * Copyright (C) 2025 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.fex.gpf.changedetection;

import com.bc.ceres.annotation.STTM;
import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class RPCAOpTest {

    @Test
    public void testSpiCreatesOperator() {
        final RPCAOp op = (RPCAOp) new RPCAOp.Spi().createOperator();
        assertNotNull(op);
    }

    @Test
    public void testOperatorMetadata() {
        final OperatorMetadata md = RPCAOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("RPCA-Change-Detection", md.alias());
    }

    @STTM("SRM-187")
    @Test
    public void test_RPCA_with_pseudo_S1() throws Exception {
        final Product srcProduct = createProduct();
        RPCAOp op = new RPCAOp();
        op.setSourceProduct(srcProduct);
        op.setParameter("maskThreshold", 0.05f);
        op.setParameter("lambda", 0.316f);

        // get targetProduct: execute initialize()
        final Product targetProduct = op.getTargetProduct();

        final Band refBand = targetProduct.getBand("Sigma0_VV_ref_03Feb2018_sparse");
        if (refBand == null) {
            throw new IOException(refBand + " not found");
        }

        final Band secBand = targetProduct.getBand("Sigma0_VV_sec1_15Feb2018_sparse");
        if (secBand == null) {
            throw new IOException(secBand + " not found");
        }

        final float refExpected = 0.0f, secExpected = 9.0f;
        final float[] refActual = new float[1];
        refBand.readPixels(4, 4, 1, 1, refActual, ProgressMonitor.NULL);

        final float[] secActual = new float[1];
        secBand.readPixels(4, 4, 1, 1, secActual, ProgressMonitor.NULL);

        Assert.assertEquals(refExpected, refActual[0], 1e-4);
        Assert.assertEquals(secExpected, secActual[0], 1e-4);
    }

    private Product createProduct() {

        Product srcProduct = TestUtils.createProduct("GRD", 10, 10);
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(srcProduct);
        absRoot.setAttributeString(AbstractMetadata.MISSION, "SENTINEL-1B");
        absRoot.setAttributeString(AbstractMetadata.ACQUISITION_MODE, "IW");
        absRoot.setAttributeString(AbstractMetadata.mds1_tx_rx_polar, "VH");
        absRoot.setAttributeString(AbstractMetadata.mds2_tx_rx_polar, "VV");
        absRoot.setAttributeDouble(AbstractMetadata.radar_frequency, 5405.000454334349);
        absRoot.setAttributeInt(AbstractMetadata.coregistered_stack, 1);

        createBand(srcProduct, "Sigma0_VV_ref_03Feb2018", 1.0f, 1.0f);
        createBand(srcProduct, "Sigma0_VV_sec1_15Feb2018", 1.0f,  10.0f);

        return srcProduct;
    }

    private void createBand(final Product product, final String bandName,
                            final float backgroundValue, final float targetValue) {

        final int w = product.getSceneRasterWidth();
        final int h = product.getSceneRasterHeight();
        final int size = w * h;
        final Band band = new Band(bandName, ProductData.TYPE_FLOAT32, w, h);
        product.addBand(band);
        band.setUnit(Unit.AMPLITUDE);
        final float[] floatValues = new float[size];
        final int xc = w/2;
        final int yc = h/2;
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                if ((x == xc - 1 || x == xc) && (y == yc - 1 || y == yc)){
                    floatValues[y * w + x] = targetValue;
                } else {
                    floatValues[y * w + x] = backgroundValue;
                }
            }
        }
        band.setData(ProductData.createInstance(floatValues));
    }
}