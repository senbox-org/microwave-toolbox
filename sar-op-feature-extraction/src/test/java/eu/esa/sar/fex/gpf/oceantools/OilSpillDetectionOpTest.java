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
package eu.esa.sar.fex.gpf.oceantools;

import com.bc.ceres.annotation.STTM;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;
import static org.junit.Assert.*;

public class OilSpillDetectionOpTest {


    // Initialize the operator with valid source and target products
    @Test
    public void test_initialize_with_valid_products() {
        Product srcProduct = createProduct();
        Band band = TestUtils.createBand(srcProduct, "band1", 10, 10);
        band.setUnit(Unit.INTENSITY);

        OilSpillDetectionOp op = new OilSpillDetectionOp();
        op.setSourceProduct(srcProduct);

        assertNotNull(op.getTargetProduct());
        assertEquals("name", op.getTargetProduct().getName());
    }

    // Add selected bands to the target product
    @Test
    public void test_add_selected_bands_to_target_product() {
        Product srcProduct = createProduct();
        Band band = TestUtils.createBand(srcProduct, "band1", 10, 10);
        band.setUnit(Unit.INTENSITY);

        OilSpillDetectionOp op = new OilSpillDetectionOp();
        op.setSourceProduct(srcProduct);

        assertNotNull(op.getTargetProduct().getBand("band1_oil_spill_bit_msk"));
    }

    // Add bitmasks to the target product for oil spill detection
    @Test
    public void test_add_bitmasks_to_target_product() {
        Product targetProduct = new Product("target", "type", 100, 100);
        Band band = new Band("band1_oil_spill_bit_msk", ProductData.TYPE_INT8, 100, 100);
        targetProduct.addBand(band);

        OilSpillDetectionOp.addBitmasks(targetProduct);

        assertNotNull(targetProduct.getMaskGroup().get("band1_oil_spill_bit_msk_detection"));
    }

    // Handle cases where the background window dimension is less than or equal to zero
    @Test()
    @STTM("SNAP-3821")
    public void test_handle_invalid_background_window_dimension() {
        Product srcProduct = createProduct();

        OilSpillDetectionOp op = new OilSpillDetectionOp();
        op.setSourceProduct(srcProduct);
        op.backgroundWindowDim = 0;

        Exception exception = assertThrows(OperatorException.class, ()->op.getTargetProduct());
        assertEquals("Background window dimension should be greater than 0", exception.getMessage());
    }

    // Handle cases where the source band is not found in the source product
    @Test()
    public void test_handle_source_band_not_found() {
        Product srcProduct = createProduct();

        OilSpillDetectionOp op = new OilSpillDetectionOp();
        op.setSourceProduct(srcProduct);
    
        op.sourceBandNames = new String[]{"nonexistent_band"};
        op.backgroundWindowDim = 0.5;

        Exception exception = assertThrows(OperatorException.class, ()->op.getTargetProduct());
        assertEquals("Source band not found: nonexistent_band", exception.getMessage());
    }

    // Handle cases where the source band does not have a unit
    @Test()
    public void test_handle_source_band_without_unit() {
        Product srcProduct = createProduct();
        Band band = TestUtils.createBand(srcProduct, "band1", 10, 10);
        band.setUnit(null);
    
        OilSpillDetectionOp op = new OilSpillDetectionOp();
        op.setSourceProduct(srcProduct);

        Exception exception = assertThrows(OperatorException.class, ()->op.getTargetProduct());
        assertEquals("No intensity bands selected", exception.getMessage());
    }

    // Handle cases where the source band unit is not intensity
    @Test()
    public void test_handle_source_band_unit_not_intensity() {
        Product srcProduct = createProduct();
        Band band = TestUtils.createBand(srcProduct, "band1", 10, 10);
        band.setUnit(Unit.AMPLITUDE);

        OilSpillDetectionOp op = new OilSpillDetectionOp();
        op.setSourceProduct(srcProduct);

        Exception exception = assertThrows(OperatorException.class, ()->op.getTargetProduct());
        assertEquals("No intensity bands selected", exception.getMessage());
    }

    private Product createProduct() {
        Product srcProduct = TestUtils.createProduct("GRD", 10, 10);
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(srcProduct);
        absRoot.setAttributeInt(AbstractMetadata.abs_calibration_flag, 1);
        return srcProduct;
    }
}