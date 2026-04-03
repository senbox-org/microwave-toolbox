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
package eu.esa.sar.fex.gpf.oceantools;

import com.bc.ceres.annotation.STTM;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;
import static org.junit.Assert.*;

public class AdaptiveThresholdingOpTest {

    // Initialize the operator with valid source and target products
    @Test
    @STTM("SNAP-4018")
    public void test_initialize_with_valid_products() {
        final int w = 10;
        final int h = 10;
        Product srcProduct = createProduct(w, h);
        Band band = TestUtils.createBand(srcProduct, "Sigma0_VH", w, h);
        band.setUnit(Unit.INTENSITY);

        AdaptiveThresholdingOp op = new AdaptiveThresholdingOp();
        op.setSourceProduct(srcProduct);

        assertNotNull(op.getTargetProduct());
        assertNotNull(op.getTargetProduct().getBand("Sigma0_VH_ship_bit_msk"));
    }

    private Product createProduct(final int w, final int h) {

        Product srcProduct = TestUtils.createProduct("GRD", w, h);
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(srcProduct);
        absRoot.setAttributeInt(AbstractMetadata.abs_calibration_flag, 1);
        absRoot.setAttributeInt(AbstractMetadata.azimuth_spacing, 10);
        absRoot.setAttributeInt(AbstractMetadata.range_spacing, 10);

        final TiePointGrid incGrid = new TiePointGrid(OperatorUtils.TPG_INCIDENT_ANGLE,
                3, 4, 0.5f, 0.5f, w, h,
                new float[]{40.52037f, 41.27510f, 42.01653f, 42.74485f,
                            40.51316f, 41.26797f, 42.00948f, 42.73790f,
                            40.50904f, 41.26291f, 42.00448f, 42.73296f});

        srcProduct.addTiePointGrid(incGrid);

        return srcProduct;
    }
}