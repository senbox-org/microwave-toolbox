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
package eu.esa.sar.utilities.gpf;

import com.bc.ceres.annotation.STTM;
import org.esa.snap.core.datamodel.*;
import org.junit.Test;

import java.awt.*;

import static org.junit.Assert.*;

/**
 * @author Marco Peters
 */
public class BandSelectOpTest {

    @Test
    public void basicTest() {
        BandSelectOp selectOp = new BandSelectOp();
        selectOp.setParameterDefaultValues();
        selectOp.setSourceProduct("source", createSourceProduct());
        selectOp.setParameter("sourceBands", "b1");
        selectOp.setParameter("sourceMasks", "m1");
        Product targetProduct = selectOp.getTargetProduct();
        assertTrue(targetProduct.containsBand("b1"));
        assertFalse(targetProduct.containsBand("v1"));
        assertTrue(targetProduct.getMaskGroup().contains("m1"));
        assertTrue(targetProduct.getFlagCodingGroup().contains("fcoding"));
        assertTrue(targetProduct.containsBand("fband"));
    }

    @Test
    public void testFlagBandSpecified() {
        BandSelectOp selectOp = new BandSelectOp();
        selectOp.setParameterDefaultValues();
        selectOp.setSourceProduct("source", createSourceProduct());
        selectOp.setParameter("sourceBands", "fband,v1");
        Product targetProduct = selectOp.getTargetProduct();
        assertFalse(targetProduct.containsBand("b1"));
        assertTrue(targetProduct.containsBand("v1"));
        assertFalse(targetProduct.getMaskGroup().contains("m1"));
        assertTrue(targetProduct.getFlagCodingGroup().contains("fcoding"));
        assertTrue(targetProduct.containsBand("fband"));
    }

    private Product createSourceProduct() {
        Product product = new Product("test", "type", 10, 10);
        product.addBand("b1", ProductData.TYPE_INT16);
        product.addBand(new VirtualBand("v1", ProductData.TYPE_FLOAT32, 10, 10, "x+y"));
        product.addMask("m1", "b1 == 1", "descr", Color.CYAN, 0.5);
        FlagCoding fcoding = new FlagCoding("fcoding");
        fcoding.addFlag("flag1", 0x01, "description");
        fcoding.addFlag("flag2", 0x02, "description");
        product.getFlagCodingGroup().add(fcoding);
        Band fband = product.addBand("fband", ProductData.TYPE_INT8);
        fband.setSampleCoding(fcoding);
        return product;
    }

    @Test
    @STTM("SNAP-3901")
    public void testGetSubImages() {
        String[] bandNames = {
                "WV1_IMG_VH", "WV1_IMG_VV", "WV2_IMG_VH", "WV2_IMG_VV", "IW1_VH", "IW2_VV"
        };
        String[] expectedSubImages = {"WV1_IMG", "WV2_IMG"};

        String[] actualSubImages = BandSelectOp.getSubImages(bandNames);

        assertArrayEquals(expectedSubImages, actualSubImages);
    }

    @Test
    public void testGetSubImagesWithNoMatches() {
        String[] bandNames = {
                "IW1_VH", "IW2_VV", "IW3_VH"
        };
        String[] expectedSubImages = {};

        String[] actualSubImages = BandSelectOp.getSubImages(bandNames);

        assertArrayEquals(expectedSubImages, actualSubImages);
    }

    @Test
    public void testGetSubImagesWithEmptyArray() {
        String[] bandNames = {};
        String[] expectedSubImages = {};

        String[] actualSubImages = BandSelectOp.getSubImages(bandNames);

        assertArrayEquals(expectedSubImages, actualSubImages);
    }

    @Test
    @STTM("SNAP-3901")
    public void getSubImagesReturnsCorrectSubImages() {
        String[] bandNames = {"WV1_IMG_VH", "WV1_IMG_VV", "WV2_IMG_VH", "WV2_IMG_VV"};
        String[] expectedSubImages = {"WV1_IMG", "WV2_IMG"};

        String[] actualSubImages = BandSelectOp.getSubImages(bandNames);

        assertArrayEquals(expectedSubImages, actualSubImages);
    }

    @Test
    public void getSubImagesReturnsEmptyArrayWhenNoSubImages() {
        String[] bandNames = {"IW1_VH", "IW2_VV", "IW3_VH"};
        String[] expectedSubImages = {};

        String[] actualSubImages = BandSelectOp.getSubImages(bandNames);

        assertArrayEquals(expectedSubImages, actualSubImages);
    }

    @Test
    public void getSubImagesHandlesEmptyBandNamesArray() {
        String[] bandNames = {};
        String[] expectedSubImages = {};

        String[] actualSubImages = BandSelectOp.getSubImages(bandNames);

        assertArrayEquals(expectedSubImages, actualSubImages);
    }

    @Test
    @STTM("SNAP-3984")
    public void testMultipleMasksSelection() {
        BandSelectOp selectOp = new BandSelectOp();
        selectOp.setParameterDefaultValues();
        Product src = createSourceProduct();
        src.addMask("m2", "b1 == 2", "descr", Color.MAGENTA, 0.5);
        selectOp.setSourceProduct("source", src);
        selectOp.setParameter("sourceBands", "b1");
        selectOp.setParameter("sourceMasks", "m1,m2");
        Product tgt = selectOp.getTargetProduct();
        assertTrue(tgt.getMaskGroup().contains("m1"));
        assertTrue(tgt.getMaskGroup().contains("m2"));
    }

    @Test
    @STTM("SNAP-3984")
    public void testEmptyMaskSelection() {
        BandSelectOp selectOp = new BandSelectOp();
        selectOp.setParameterDefaultValues();
        selectOp.setSourceProduct("source", createSourceProduct());
        selectOp.setParameter("sourceBands", "b1");
        selectOp.setParameter("sourceMasks", new String[0]);
        Product tgt = selectOp.getTargetProduct();
        assertEquals(0, tgt.getMaskGroup().getNodeNames().length);
    }

    @Test
    @STTM("SNAP-3984")
    public void testIncludeReferencesTrue() {
        BandSelectOp op = new BandSelectOp();
        op.setParameterDefaultValues();
        Product src = createSourceProduct();

        src.addBand("b5", ProductData.TYPE_INT16);
        src.addBand("b6", ProductData.TYPE_INT16);
        src.addMask("m2", "b5 == 0", "descr", Color.BLUE, 0.5);
        VirtualBand vb = new VirtualBand("v2", ProductData.TYPE_FLOAT32, 10,10, "b5 + b6");
        src.addBand(vb);
        op.setSourceProduct("source", src);
        op.setParameter("sourceBands", "v2");
        op.setParameter("includeReferences", true);
        Product tgt = op.getTargetProduct();

        assertTrue(tgt.containsBand("v2"));
        assertTrue(tgt.containsBand("b5"));
        assertTrue(tgt.containsBand("b6"));
        assertFalse(tgt.getMaskGroup().contains("m2"));
    }

    @Test
    @STTM("SNAP-3984")
    public void testIncludeReferencesFalse() {
        BandSelectOp op = new BandSelectOp();
        op.setParameterDefaultValues();
        Product src = createSourceProduct();

        src.addBand("b5", ProductData.TYPE_INT16);
        src.addBand("b6", ProductData.TYPE_INT16);
        src.addMask("m2", "b5 == 0", "descr", Color.BLUE, 0.5);
        VirtualBand vb = new VirtualBand("v2", ProductData.TYPE_FLOAT32, 10,10, "b5 + b6");
        src.addBand(vb);
        op.setSourceProduct("source", src);
        op.setParameter("sourceBands", "v2");
        op.setParameter("includeReferences", false);
        Product tgt = op.getTargetProduct();

        assertTrue(tgt.containsBand("v2"));
        assertFalse(tgt.containsBand("b5"));
        assertFalse(tgt.containsBand("b6"));
        assertFalse(tgt.getMaskGroup().contains("m2"));
    }
}