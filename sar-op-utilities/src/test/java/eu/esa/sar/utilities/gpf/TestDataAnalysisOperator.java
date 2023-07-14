/*
 * Copyright (C) 2015 by Array Systems Computing Inc. http://www.array.ca
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

import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Unit test for SingleTileOperator.
 */
public class TestDataAnalysisOperator {

    private final static OperatorSpi spi = new DataAnalysisOp.Spi();

//    @Test
//    public void testDataAnalysis() throws Exception {
//
//        final Product sourceProduct = TestUtils.createProduct("type", 10, 10);
//        TestUtils.createBand(sourceProduct, "band", 10, 10);
//
//        final DataAnalysisOp op = (DataAnalysisOp) spi.createOperator();
//        assertNotNull(op);
//        op.setSourceProduct(sourceProduct);
//        op.setParameter("noDataValue", 5.0);
//
//        // get targetProduct: execute initialize()
//        final Product targetProduct = op.getTargetProduct();
//        TestUtils.verifyProduct(targetProduct, true, true, true);
//
//        final Band band = targetProduct.getBandAt(0);
//        assertNotNull(band);
//
//        // readPixels gets computeTiles to be executed
//        final float[] floatValues = new float[4];
//        band.readPixels(0, 0, 2, 2, floatValues, ProgressMonitor.NULL);
//
//        // compare with expected outputs:
//        final float[] expected = new float[] { 1.0f, 2.0f, 11.0f, 12.0f };
//        assertArrayEquals(Arrays.toString(floatValues), expected, floatValues, 0.0001f);
//    }

    @Test
    public void testSampleOperator() throws Exception {

        Product sourceProduct = createTestProduct(4, 4);

        DataAnalysisOp op = (DataAnalysisOp) spi.createOperator();
        assertNotNull(op);
        op.setSourceProduct(sourceProduct);

        // get targetProduct gets initialize to be executed
        Product targetProduct = op.getTargetProduct();
        assertNotNull(targetProduct);

        Band band = targetProduct.getBandAt(0);
        assertNotNull(band);

        // readPixels gets computeTiles to be executed
        float[] floatValues = new float[16];
        band.readPixels(0, 0, 4, 4, floatValues, ProgressMonitor.NULL);

        op.dispose();

        // get statistics from metadata
        System.out.println();
        System.out.println("# of bands = " + op.getNumOfBands());
        System.out.println("min = " + op.getMin(0));
        System.out.println("max = " + op.getMax(0));
        System.out.println("mean = " + op.getMean(0));
        System.out.println("std = " + op.getStd(0));
        System.out.println("var = " + op.getVarCoef(0));
        System.out.println("enl = " + op.getENL(0));
        assertEquals(1, op.getNumOfBands());
        assertEquals(0, Double.compare(op.getMin(0), 1.0));
        assertEquals(0, Double.compare(op.getMax(0), 16.0));
        assertEquals(0, Double.compare(op.getMean(0), 8.5));
        assertEquals(0, Double.compare(op.getStd(0), 4.6097722286464435));
        assertEquals(0, Double.compare(op.getVarCoef(0), 0.8621574728675674));
        assertEquals(0, Double.compare(op.getENL(0), 1.3453237410071943));
    }

    private static Product createTestProduct(int w, int h) {

        Product testProduct = new Product("p", "t", w, h);

        // create a Band: band1
        Band band1 = testProduct.addBand("band1", ProductData.TYPE_INT32);
        int[] intValues = new int[w * h];
        for (int i = 0; i < w * h; i++) {
            intValues[i] = i + 1;
        }
        band1.setData(ProductData.createInstance(intValues));

        // create SPH MetadataElement with attributes: sample_type and mds1_tx_rx_polar
        MetadataElement sph = new MetadataElement("SPH");
        sph.addAttribute(new MetadataAttribute("sample_type",
                ProductData.createInstance("DETECTED"), false));
        AbstractMetadata.getOriginalProductMetadata(testProduct).addElement(sph);
        return testProduct;
    }
}
