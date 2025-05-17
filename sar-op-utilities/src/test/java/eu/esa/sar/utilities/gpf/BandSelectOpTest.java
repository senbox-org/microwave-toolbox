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
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.FlagCoding;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.VirtualBand;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
        Product targetProduct = selectOp.getTargetProduct();
        assertTrue(targetProduct.containsBand("b1"));
        assertFalse(targetProduct.containsBand("v1"));
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
        assertTrue(targetProduct.getFlagCodingGroup().contains("fcoding"));
        assertTrue(targetProduct.containsBand("fband"));
    }

    private Product createSourceProduct() {
        Product product = new Product("test", "type", 10, 10);
        product.addBand("b1", ProductData.TYPE_INT16);
        product.addBand(new VirtualBand("v1", ProductData.TYPE_FLOAT32, 10, 10, "x+y"));
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
}