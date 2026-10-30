/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.calibration.gpf;

import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.datamodel.metadata.AbstractMetadataIO;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Input guards of {@link Sentinel1RemoveThermalNoiseOp}: the noise annotation describes the original raster,
 * so resampled (multilooked) or debursted products must be rejected rather than silently mis-corrected.
 * Uses the committed synthetic TOPS SLC metadata only (no external test data).
 */
public class TestRemoveThermalNoiseOpGuards {

    @Test
    public void acceptsOriginalBurstProduct() throws Exception {
        assertNotNull(process(createTOPSSLCProduct(10, 148)));
    }

    @Test
    public void rejectsMultilookedProduct() throws Exception {
        final Product src = createTOPSSLCProduct(10, 148);
        final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(src);
        // give the annotation the spacings of the original raster (the fixture omits them)
        for (MetadataElement annotation : AbstractMetadata.getOriginalProductMetadata(src)
                .getElement("annotation").getElements()) {
            final MetadataElement info = annotation.getElement("product").getElement("imageAnnotation")
                    .getElement("imageInformation");
            info.setAttributeString("rangePixelSpacing", String.valueOf(abs.getAttributeDouble(AbstractMetadata.range_spacing)));
            info.setAttributeString("azimuthPixelSpacing", String.valueOf(abs.getAttributeDouble(AbstractMetadata.azimuth_spacing)));
        }
        assertNotNull("original spacing accepted", process(src));
        abs.setAttributeDouble(AbstractMetadata.range_spacing, abs.getAttributeDouble(AbstractMetadata.range_spacing) * 4);
        expectFailure(src, "multilooking");
    }

    @Test
    public void rejectsDeburstedTopsSlc() throws Exception {
        final Product src = createTOPSSLCProduct(10, 148);
        for (MetadataElement annotation : AbstractMetadata.getOriginalProductMetadata(src)
                .getElement("annotation").getElements()) {
            annotation.getElement("product").getElement("swathTiming").getElement("burstList")
                    .setAttributeString("count", "0");
        }
        expectFailure(src, "deburst");
    }

    @Test
    public void rejectsDeburstedDetectedTopsSlc() throws Exception {
        // calibrated to intensity, then debursted: no longer complex, but still a TOPS SLC
        final Product src = createTOPSSLCProduct(10, 148);
        src.removeBand(src.getBand("i_IW1_VH"));
        src.removeBand(src.getBand("q_IW1_VH"));
        TestUtils.createBand(src, "Intensity_IW1_VH", ProductData.TYPE_FLOAT32, Unit.INTENSITY, 10, 148, true);
        AbstractMetadata.getAbstractedMetadata(src).setAttributeString(AbstractMetadata.SAMPLE_TYPE, "DETECTED");
        for (MetadataElement annotation : AbstractMetadata.getOriginalProductMetadata(src)
                .getElement("annotation").getElements()) {
            annotation.getElement("product").getElement("swathTiming").getElement("burstList")
                    .setAttributeString("count", "0");
        }
        expectFailure(src, "deburst");
    }

    @Test
    public void rejectsMergedDeburstedTopsSlc() throws Exception {
        // TOPSAR-Deburst of a whole product merges the swaths: band names no longer carry IW1..IW3
        final Product src = createTOPSSLCProduct(10, 148);
        src.getBand("i_IW1_VH").setName("i_VH");
        src.getBand("q_IW1_VH").setName("q_VH");
        for (MetadataElement annotation : AbstractMetadata.getOriginalProductMetadata(src)
                .getElement("annotation").getElements()) {
            annotation.getElement("product").getElement("swathTiming").getElement("burstList")
                    .setAttributeString("count", "0");
        }
        AbstractMetadata.getAbstractedMetadata(src).setAttributeString(AbstractMetadata.ACQUISITION_MODE, "IW");
        expectFailure(src, "deburst");
    }

    @Test
    public void rejectsSliceAssembledTopsSlc() throws Exception {
        final Product src = createTOPSSLCProduct(10, 148);
        for (MetadataElement noise : AbstractMetadata.getOriginalProductMetadata(src).getElement("noise").getElements()) {
            final MetadataElement list = noise.getElement("noise").getElement("noiseAzimuthVectorList");
            final MetadataElement first = list.getNumElements() == 0 ? list : list.getElementAt(0);
            first.setAttributeString("slice", "1");
        }
        expectFailure(src, "slice assembly");
    }

    @Test
    public void rejectsSelectionWithoutMatchingBands() throws Exception {
        final Product src = createTOPSSLCProduct(10, 148);
        final Sentinel1RemoveThermalNoiseOp op = new Sentinel1RemoveThermalNoiseOp();
        op.setSourceProduct(src);
        op.setParameter("selectedPolarisations", new String[]{"HH"});
        try {
            op.getTargetProduct();
            fail("expected OperatorException");
        } catch (OperatorException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("No bands"));
        }
    }

    @Test
    public void rejectsInvalidClipFloor() throws Exception {
        final Product src = createTOPSSLCProduct(10, 148);
        final Sentinel1RemoveThermalNoiseOp op = new Sentinel1RemoveThermalNoiseOp();
        op.setSourceProduct(src);
        op.setParameter("clipFloorValue", Double.NaN);
        try {
            op.getTargetProduct();
            fail("expected OperatorException");
        } catch (OperatorException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("floor"));
        }
    }

    @Test
    public void rejectsNegativeAndInfiniteClipFloor() throws Exception {
        for (double floor : new double[]{-1e-5, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            final Sentinel1RemoveThermalNoiseOp op = new Sentinel1RemoveThermalNoiseOp();
            op.setSourceProduct(createTOPSSLCProduct(10, 148));
            op.setParameter("clipFloorValue", floor);
            try {
                op.getTargetProduct();
                fail("expected OperatorException for clip floor " + floor);
            } catch (OperatorException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("floor"));
                assertNotNull("cause kept", e.getCause());
            }
        }
    }

    private static void expectFailure(final Product src, final String messagePart) {
        try {
            process(src);
            fail("expected OperatorException");
        } catch (OperatorException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(messagePart));
        }
    }

    private static Product process(final Product src) {
        final Sentinel1RemoveThermalNoiseOp op = new Sentinel1RemoveThermalNoiseOp();
        op.setSourceProduct(src);
        return op.getTargetProduct();
    }

    private static Product createTOPSSLCProduct(final int w, final int h) throws Exception {
        final Product srcProduct = TestUtils.createProduct("SLC", w, h);
        TestUtils.createBand(srcProduct, "i_IW1_VH", ProductData.TYPE_INT16, Unit.REAL, w, h, true);
        TestUtils.createBand(srcProduct, "q_IW1_VH", ProductData.TYPE_INT16, Unit.IMAGINARY, w, h, true);
        AbstractMetadataIO.Load(srcProduct, srcProduct.getMetadataRoot(), new File("src/test/resources/metadata.xml"));
        return srcProduct;
    }
}
