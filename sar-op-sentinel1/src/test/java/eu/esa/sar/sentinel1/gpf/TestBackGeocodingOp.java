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
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link BackGeocodingOp}.
 */
public class TestBackGeocodingOp {

    @Test
    public void testSpiCreatesOperator() {
        final BackGeocodingOp op = (BackGeocodingOp) new BackGeocodingOp.Spi().createOperator();
        assertNotNull(op);
    }

    @Test
    public void testOperatorMetadata() {
        final OperatorMetadata md = BackGeocodingOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("Back-Geocoding", md.alias());
    }

    /**
     * A C2 covariance product built from a debursted Sentinel-1 IW acquisition. It still declares
     * SAMPLE_TYPE COMPLEX - Polarimetric-Matrices copies the metadata across unchanged - so the
     * SLC check alone lets it through.
     */
    private static Product createDeburstedC2Product(final String name) {
        final Product product = new Product(name, "SLC", 4, 4);
        for (String bandName : new String[]{"C11", "C22"}) {
            final Band band = new Band(bandName, ProductData.TYPE_FLOAT32, 4, 4);
            band.setUnit(Unit.INTENSITY);
            product.addBand(band);
        }

        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(product.getMetadataRoot());
        absRoot.setAttributeString(AbstractMetadata.MISSION, "SENTINEL-1A");
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
     * Back-Geocoding works in the burst domain - it walks numOfBursts and deramps each burst - so
     * it must refuse anything that is not a TOPSAR split product. Without the check the operator
     * ran on to computeDopplerRate and died with "Index 0 out of bounds for length 0".
     */
    @Test
    public void testNonBurstSourceProductIsRejected() {
        final BackGeocodingOp op = (BackGeocodingOp) new BackGeocodingOp.Spi().createOperator();
        op.setSourceProducts(createDeburstedC2Product("date1"), createDeburstedC2Product("date2"));

        try {
            op.getTargetProduct();
            fail("expected an OperatorException for the non-burst source product");
        } catch (OperatorException e) {
            assertEquals("Source product should be an SLC burst product", e.getMessage());
        }
    }
}
