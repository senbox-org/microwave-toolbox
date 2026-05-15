/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. https://www.skywatch.com
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
package eu.esa.sar.fex.gpf.vector;

import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TestVectorOps {

    @Test
    public void rasterize_spi_and_metadata() {
        final RasterizeOp.Spi spi = new RasterizeOp.Spi();
        assertNotNull(spi.createOperator());

        final OperatorMetadata md = RasterizeOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("Rasterize", md.alias());
        assertEquals("Vector", md.category());
    }

    @Test
    public void vector_averaging_spi_and_metadata() {
        final VectorAveragingOp.Spi spi = new VectorAveragingOp.Spi();
        assertNotNull(spi.createOperator());

        final OperatorMetadata md = VectorAveragingOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("VectorAveraging", md.alias());
        assertEquals("Vector", md.category());
    }

    @Test
    public void expression_null_on_null_inputs() {
        assertNull(VectorAveragingOp.getExpression(null, null));
    }

    @Test
    public void method_constants_match_old_tree() {
        // Old tree spelled these exactly; downstream graphs may pass them via setParameter().
        assertEquals("Mean", VectorAveragingOp.USE_MEAN);
        assertEquals("Max", VectorAveragingOp.USE_MAX);
        assertEquals("Min", VectorAveragingOp.USE_MIN);
        assertEquals("Count", VectorAveragingOp.USE_COUNT);

        assertEquals("Set to attribute value", RasterizeOp.SET_TO_VALUE);
        assertEquals("Binarize", RasterizeOp.BINARIZE);
    }

    @Test
    public void source_bands_list_filters_temp_virt_bands() {
        // Smoke test the static helper used by the GraphBuilder UI.
        final org.esa.snap.core.datamodel.Product p =
                org.esa.snap.engine_utilities.util.TestUtils.createProduct("SLC", 4, 4);
        org.esa.snap.engine_utilities.util.TestUtils.createBand(
                p, "Sigma0_VV", org.esa.snap.core.datamodel.ProductData.TYPE_FLOAT32,
                org.esa.snap.engine_utilities.datamodel.Unit.AMPLITUDE, 4, 4, true);
        org.esa.snap.engine_utilities.util.TestUtils.createBand(
                p, VectorAveragingOp.tmpVirtBandName + "_42",
                org.esa.snap.core.datamodel.ProductData.TYPE_FLOAT32,
                org.esa.snap.engine_utilities.datamodel.Unit.AMPLITUDE, 4, 4, true);

        final String[] names = VectorAveragingOp.getSourceBands(p);
        assertTrue("Sigma0_VV must be listed", java.util.Arrays.asList(names).contains("Sigma0_VV"));
        assertTrue("tmpVirtBand_* must be filtered out",
                names.length == 1
                        || java.util.Arrays.stream(names).noneMatch(s -> s.contains(VectorAveragingOp.tmpVirtBandName)));
    }
}
