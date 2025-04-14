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
package eu.esa.sar.fex.gpf.changedetection;

import com.bc.ceres.annotation.STTM;
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

import static org.junit.Assert.assertNotNull;

public class REACTIVChangeDetectionOpTest {

    @STTM("SNAP-3900")
    @Test
    public void test_REACTIV_with_pseudo_S1() throws Exception {
        final Product srcProduct = createProduct("SENTINEL-1A", "IW", "GRD", "VH", "VV");
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

        final float hueExpected = 0.67591834f, satExpected = 0.55691f, valExpected = 1.0f;
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

    @STTM("SNAP-3927")
    @Test
    public void test_REACTIV_RS2() throws Exception {
        final Product srcProduct = createProduct("RS2", "Stripmap", "GRD", "HH", "VV");
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

        final float hueExpected = 0.67591834f, satExpected = 0.55691f, valExpected = 1.0f;
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

    @STTM("SNAP-3927")
    @Test
    public void test_REACTIV_Umbra() throws Exception {
        final Product srcProduct = createProduct("Umbra", "Spotlight", "GRD", "VV", null);
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

        final float hueExpected = 0.9f, satExpected = 0.4621464f, valExpected = 1.0f;
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

    @STTM("SNAP-3906")
    @Test
    public void test_mask_in_output() {
        final Product srcProduct = createProduct("SENTINEL-1A", "IW", "GRD", "VH", "VV");
        ReactivOp op = new ReactivOp();
        op.setSourceProduct(srcProduct);

        // get targetProduct: execute initialize()
        final Product targetProduct = op.getTargetProduct();
        assertNotNull(targetProduct.getMaskGroup().get("change"));
    }

    @STTM("SNAP-3980")
    @Test
    public void test_REACTIV_subset_S1() {
        final Product srcProduct = createProduct("SENTINEL-1A", "IW", "GRD", "VH", null);
        ReactivOp op = new ReactivOp();
        op.setSourceProduct(srcProduct);

        // get targetProduct: execute initialize()
        final Product targetProduct = op.getTargetProduct();
        final Band hueBand = targetProduct.getBand("hue");
        final Band satBand = targetProduct.getBand("saturation");
        final Band valBand = targetProduct.getBand("value");
        assertNotNull(hueBand);
        assertNotNull(satBand);
        assertNotNull(valBand);
    }

    private Product createProduct(String mission, String acquisitionMode, String productType, String txRxPolar, String txRxPolar2) {

        Product srcProduct = TestUtils.createProduct("GRD", 10, 10);
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(srcProduct);
        absRoot.setAttributeString(AbstractMetadata.MISSION, mission);
        absRoot.setAttributeString(AbstractMetadata.ACQUISITION_MODE, acquisitionMode);
        absRoot.setAttributeString(AbstractMetadata.mds1_tx_rx_polar, txRxPolar);
        absRoot.setAttributeDouble(AbstractMetadata.radar_frequency, 5405.000454334349);
        absRoot.setAttributeInt(AbstractMetadata.coregistered_stack, 1);

        createBand(srcProduct, "Amplitude_POL_mst_01Mar2024", txRxPolar, 3.0f);
        createBand(srcProduct, "Amplitude_POL_slv1_01May2024", txRxPolar,  1.0f);
        createBand(srcProduct, "Amplitude_POL_slv3_01Jul2024", txRxPolar, 4.0f);
        createBand(srcProduct, "Amplitude_POL_slv5_01Sep2024", txRxPolar, 1.0f);
        createBand(srcProduct, "Amplitude_POL_slv7_01Nov2024", txRxPolar, 5.0f);

        if(txRxPolar2 != null) {
            absRoot.setAttributeString(AbstractMetadata.mds2_tx_rx_polar, txRxPolar2);

            createBand(srcProduct, "Amplitude_POL_mst_01Mar2024", txRxPolar2, 2.0f);
            createBand(srcProduct, "Amplitude_POL_slv2_01May2024", txRxPolar2,7.0f);
            createBand(srcProduct, "Amplitude_POL_slv4_01Jul2024", txRxPolar2,1.0f);
            createBand(srcProduct, "Amplitude_POL_slv6_01Sep2024", txRxPolar2, 8.0f);
            createBand(srcProduct, "Amplitude_POL_slv8_01Nov2024", txRxPolar2,2.0f);
        }

        return srcProduct;
    }

    private void createBand(final Product product, final String bandName, final String pol, final float value) {
        final String name = bandName.replace("POL", pol);
        final int w = product.getSceneRasterWidth();
        final int h = product.getSceneRasterHeight();
        final int size = w * h;
        final Band band = new Band(name, ProductData.TYPE_FLOAT32, w, h);
        product.addBand(band);
        band.setUnit(Unit.AMPLITUDE);
        final float[] floatValues = new float[size];
        floatValues[0] = value;
        band.setData(ProductData.createInstance(floatValues));
    }
}