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
package eu.esa.sar.sentinel1.gpf;

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/**
 * Source-product validation of {@link TOPSARDeburstOp}, on a synthetic product so it runs without
 * the Sentinel-1 test data set.
 */
public class TestTOPSARDeburstValidation {

    /**
     * A C2 covariance product built from a debursted Sentinel-1 IW acquisition. It inherits
     * PRODUCT_TYPE "SLC" and ACQUISITION_MODE "IW" from its source, so the product-type and
     * acquisition-mode checks alone let it through.
     */
    private static Product createDeburstedC2Product() {
        final Product product = new Product("C2_product", "SLC", 4, 4);
        for (String bandName : new String[]{"C11", "C22"}) {
            final Band band = new Band(bandName, ProductData.TYPE_FLOAT32, 4, 4);
            band.setUnit(Unit.INTENSITY);
            product.addBand(band);
        }

        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(product.getMetadataRoot());
        absRoot.setAttributeString(AbstractMetadata.MISSION, "SENTINEL-1A");
        absRoot.setAttributeString(AbstractMetadata.PRODUCT_TYPE, "SLC");
        absRoot.setAttributeString(AbstractMetadata.ACQUISITION_MODE, "IW");
        absRoot.setAttributeString(AbstractMetadata.SAMPLE_TYPE, "COMPLEX");
        absRoot.setAttributeDouble(AbstractMetadata.radar_frequency, 5405.0);
        absRoot.setAttributeDouble(AbstractMetadata.range_spacing, 2.33);
        absRoot.setAttributeDouble(AbstractMetadata.azimuth_spacing, 13.9);
        absRoot.setAttributeDouble(AbstractMetadata.line_time_interval, 0.002);
        absRoot.setAttributeDouble(AbstractMetadata.slant_range_to_first_pixel, 800000.0);
        absRoot.setAttributeUTC(AbstractMetadata.first_line_time,
                AbstractMetadata.parseUTC("10-MAY-2008 20:30:46.890683"));
        absRoot.setAttributeUTC(AbstractMetadata.last_line_time,
                AbstractMetadata.parseUTC("10-MAY-2008 20:35:46.890683"));
        AbstractMetadata.addOriginalProductMetadata(product.getMetadataRoot());

        return product;
    }

    /**
     * A Sentinel-1 IW burst product as the readers and the split/orbit/calibration chain leave it:
     * the swath tag is still in the band names and the original annotation still reports a non-zero
     * burst count.
     */
    private static Product createBurstProduct(final int burstCount) {
        final Product product = new Product("burst_product", "SLC", 4, 4);
        final Band iBand = new Band("i_IW2_VV", ProductData.TYPE_FLOAT32, 4, 4);
        iBand.setUnit(Unit.REAL);
        product.addBand(iBand);
        final Band qBand = new Band("q_IW2_VV", ProductData.TYPE_FLOAT32, 4, 4);
        qBand.setUnit(Unit.IMAGINARY);
        product.addBand(qBand);

        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(product.getMetadataRoot());
        absRoot.setAttributeString(AbstractMetadata.MISSION, "SENTINEL-1A");
        absRoot.setAttributeString(AbstractMetadata.PRODUCT_TYPE, "SLC");
        absRoot.setAttributeString(AbstractMetadata.ACQUISITION_MODE, "IW");
        absRoot.setAttributeString(AbstractMetadata.SAMPLE_TYPE, "COMPLEX");
        absRoot.setAttributeDouble(AbstractMetadata.radar_frequency, 5405.0);

        final MetadataElement origRoot = AbstractMetadata.addOriginalProductMetadata(product.getMetadataRoot());
        final MetadataElement annotation = new MetadataElement("annotation");
        final MetadataElement swathElem = new MetadataElement("s1a-iw2-slc-vv.xml");
        final MetadataElement productElem = new MetadataElement("product");
        final MetadataElement swathTiming = new MetadataElement("swathTiming");
        final MetadataElement burstList = new MetadataElement("burstList");
        burstList.setAttributeString("count", String.valueOf(burstCount));
        swathTiming.addElement(burstList);
        productElem.addElement(swathTiming);
        swathElem.addElement(productElem);
        annotation.addElement(swathElem);
        origRoot.addElement(annotation);

        return product;
    }

    /**
     * The positive control. The gate keys on the swath tag surviving in the band names, so a change
     * anywhere in the reader / split / calibration chain that renames bands would start rejecting
     * every legitimate Deburst input - the direction that matters most and the one a "rejected as
     * expected" test can never catch.
     */
    @Test
    public void testBurstSourceProductIsAccepted() {
        new InputProductValidator(createBurstProduct(9)).checkIfTOPSARBurstProduct(true);
    }

    /**
     * The other arm of the gate: a product that still carries the swath tag but whose annotation
     * reports no bursts has already been debursted.
     */
    @Test
    public void testAlreadyDeburstSourceProductIsRejected() {
        try {
            new InputProductValidator(createBurstProduct(0)).checkIfTOPSARBurstProduct(true);
            fail("expected an OperatorException for the already-debursted product");
        } catch (OperatorException e) {
            assertEquals("Source product should NOT be a deburst product", e.getMessage());
        }
    }

    /**
     * Deburst reads subSwath[0].firstLineTime straight after building Sentinel1Utils, so a product
     * with no burst geometry left - a C2 matrix, or anything already debursted - must be refused
     * with a message instead of "Index 0 out of bounds for length 0".
     */
    @Test
    public void testNonBurstSourceProductIsRejected() {
        final TOPSARDeburstOp op = new TOPSARDeburstOp();
        op.setSourceProduct(createDeburstedC2Product());

        try {
            op.getTargetProduct();
            fail("expected an OperatorException for the non-burst source product");
        } catch (OperatorException e) {
            final String message = e.getMessage();
            assertNotNull(message);
            assertFalse("raw index error: " + message, message.contains("out of bounds"));
            assertEquals("Source product should be an SLC burst product", message);
        }
    }
}
