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
package eu.esa.sar.fex.gpf.decisiontree;

import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Unit test for DecisionTreeOp.
 */
public class TestDescisionTreeOp {

    private OperatorSpi spi;

    /**
     * Creates a 4-by-16 test product as shown below:
     * 1  2  3  4  5  6  7  8  9 10 11 12 13 14 15 16
     * 17 18 19 20 21 22 23 24 25 26 27 28 29 30 31 32
     * 33 34 35 36 37 38 39 40 41 42 43 44 45 46 47 48
     * 49 50 51 52 53 54 55 56 57 58 59 60 61 62 63 64
     *
     * @param w width
     * @param h height
     * @return the created product
     */
    private static Product createTestProduct(final int w, final int h) {

        final Product testProduct = TestUtils.createProduct("ASA_APG_1P", w, h);

        // create a Band: band1
        final Band band1 = testProduct.addBand("band1", ProductData.TYPE_INT32);
        band1.setUnit(Unit.AMPLITUDE);
        final int[] intValues = new int[w * h];
        for (int i = 0; i < w * h; i++) {
            intValues[i] = i + 1;
        }
        band1.setData(ProductData.createInstance(intValues));

        // create abstracted metadata
        final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(testProduct);

        AbstractMetadata.setAttribute(abs, AbstractMetadata.SAMPLE_TYPE, "DETECTED");
        AbstractMetadata.setAttribute(abs, AbstractMetadata.MISSION, "ENVISAT");
        AbstractMetadata.setAttribute(abs, AbstractMetadata.srgr_flag, 0);
        AbstractMetadata.setAttribute(abs, AbstractMetadata.range_spacing, 0.5F);
        AbstractMetadata.setAttribute(abs, AbstractMetadata.azimuth_spacing, 2.0F);
        AbstractMetadata.setAttribute(abs, AbstractMetadata.azimuth_looks, 1);
        AbstractMetadata.setAttribute(abs, AbstractMetadata.range_looks, 1);
        AbstractMetadata.setAttribute(abs, AbstractMetadata.line_time_interval, 0.01F);
        AbstractMetadata.setAttribute(abs, AbstractMetadata.first_line_time,
                AbstractMetadata.parseUTC("10-MAY-2008 20:32:46.885684"));

        final float[] incidence_angle = new float[64];
        Arrays.fill(incidence_angle, 30.0f);
        testProduct.addTiePointGrid(new TiePointGrid(OperatorUtils.TPG_INCIDENT_ANGLE, 16, 4, 0, 0, 1, 1, incidence_angle));

        return testProduct;
    }

    @Before
    public void setUp() {
        spi = new DecisionTreeOp.Spi();
    }

    @Test
    public void testSpiCreatesOperator() {
        final Product sourceProduct = createTestProduct(16, 4);

        final DecisionTreeOp op = (DecisionTreeOp) spi.createOperator();
        assertNotNull(op);
        op.setSourceProduct(sourceProduct);
    }

    @Test
    public void testOperatorMetadata() {
        final OperatorMetadata md = DecisionTreeOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull("DecisionTreeOp must declare @OperatorMetadata", md);
        assertEquals("DecisionTree", md.alias());
        assertEquals("Raster/Classification", md.category());
    }
}
