/*
 * Copyright (C) 2024 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.fex.gpf;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;

public class REACTIVChangeDetectionOpTest {


    @Test
    public void test_initialize_with_valid_products() throws Exception {
        final Product srcProduct = createProduct();
        ReactivOp op = new ReactivOp();
        op.setSourceProduct(srcProduct);

        // get targetProduct: execute initialize()
        final Product targetProduct = op.getTargetProduct();

        final Band hueBand = targetProduct.getBand("hue");
        if (hueBand == null) {
            throw new IOException(hueBand + " not found");
        }

        final Band satBand = targetProduct.getBand("saturation");
        if (satBand == null) {
            throw new IOException(satBand + " not found");
        }

        final Band valBand = targetProduct.getBand("value");
        if (valBand == null) {
            throw new IOException(valBand + " not found");
        }

        final float hueExpected = 0.22408f, satExpected = 0.55691f, valExpected = 5.3600f;
        final float[] hueActual = new float[1];
        hueBand.readPixels(0, 0, 1, 1, hueActual, ProgressMonitor.NULL);

        final float[] satActual = new float[1];
        satBand.readPixels(0, 0, 1, 1, satActual, ProgressMonitor.NULL);

        final float[] valActual = new float[1];
        valBand.readPixels(0, 0, 1, 1, valActual, ProgressMonitor.NULL);

        Assert.assertEquals(hueExpected, hueActual[0], 1e-4);
        Assert.assertEquals(satExpected, satActual[0], 1e-4);
        Assert.assertEquals(valExpected, valActual[0], 1e-4);
    }

    private Product createProduct() {

        Product srcProduct = TestUtils.createProduct("GRD", 10, 10);
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(srcProduct);
        absRoot.setAttributeString(AbstractMetadata.MISSION, "SENTINEL-1A");
        absRoot.setAttributeString(AbstractMetadata.ACQUISITION_MODE, "IW");
        absRoot.setAttributeString(AbstractMetadata.mds1_tx_rx_polar, "VH");
        absRoot.setAttributeString(AbstractMetadata.mds2_tx_rx_polar, "VV");
        absRoot.setAttributeDouble(AbstractMetadata.radar_frequency, 5405.000454334349);
        absRoot.setAttributeInt(AbstractMetadata.coregistered_stack, 1);

        createBand(srcProduct, "Amplitude_VH_mst_01Mar2024", 3.0f);
        createBand(srcProduct, "Amplitude_VV_mst_01Mar2024", 2.0f);
        createBand(srcProduct, "Amplitude_VH_slv1_01May2024", 1.0f);
        createBand(srcProduct, "Amplitude_VV_slv2_01May2024", 7.0f);
        createBand(srcProduct, "Amplitude_VH_slv3_01Jul2024", 4.0f);
        createBand(srcProduct, "Amplitude_VV_slv4_01Jul2024", 1.0f);
        createBand(srcProduct, "Amplitude_VH_slv5_01Sep2024", 1.0f);
        createBand(srcProduct, "Amplitude_VV_slv6_01Sep2024", 8.0f);
        createBand(srcProduct, "Amplitude_VH_slv7_01Nov2024", 5.0f);
        createBand(srcProduct, "Amplitude_VV_slv8_01Nov2024", 2.0f);

        return srcProduct;
    }

    private void createBand(final Product product, final String bandName, final float value) {

        final int w = product.getSceneRasterWidth();
        final int h = product.getSceneRasterHeight();
        final int size = w * h;
        final Band band = new Band(bandName, ProductData.TYPE_FLOAT32, w, h);
        product.addBand(band);
        band.setUnit(Unit.AMPLITUDE);
        final float[] floatValues = new float[size];
        floatValues[0] = value;
        band.setData(ProductData.createInstance(floatValues));
    }
}