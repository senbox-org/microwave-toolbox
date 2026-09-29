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
package eu.esa.sar.commons;

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link Sentinel1Utils}.
 */
public class TestSentinel1Utils {

    /**
     * A Sentinel-1 product that carries no sub-swath information at all: TOPSAR-Deburst removes
     * the {@code Band_*} elements that hold the swath and polarisation of each band, and a
     * polarimetric matrix product (C11, C12_real, C12_imag, C22) has no IW tag left in its band
     * names for the fallback scan to pick up either.
     */
    private static Product createProductWithoutSwathMetadata() {
        final Product product = new Product("C2_product", "SLC", 4, 4);
        for (String name : new String[]{"C11", "C22"}) {
            final Band band = new Band(name, ProductData.TYPE_FLOAT32, 4, 4);
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

    @Test
    public void testNoSwathMetadataYieldsNoSubSwath() throws Exception {
        final Sentinel1Utils su = new Sentinel1Utils(createProductWithoutSwathMetadata());

        assertEquals(0, su.getSubSwathNames().length);
        assertEquals(0, su.getPolarizations().length);
        assertEquals(0, su.getSubSwath().length);
    }

    /**
     * With no sub-swath the Doppler rate cannot be computed. It must say so, rather than index
     * {@code subSwath[0]} and surface as "Index 0 out of bounds for length 0" from whichever
     * operator happened to construct the utils.
     */
    @Test
    public void testComputeDopplerRateWithoutSubSwathIsReported() throws Exception {
        final Sentinel1Utils su = new Sentinel1Utils(createProductWithoutSwathMetadata());

        try {
            su.computeDopplerRate();
            fail("expected an IOException naming the missing sub-swath metadata");
        } catch (IOException e) {
            assertTrue("unexpected message: " + e.getMessage(),
                    e.getMessage().contains("sub-swath"));
        }
    }

    /**
     * computeReferenceTime() loops over numOfSubSwath, so with no sub-swath it used to return
     * having computed nothing at all - and left isDopplerCentroidAvailable true, poisoning any
     * later call. Silently doing nothing is worse than failing.
     */
    @Test
    public void testComputeReferenceTimeWithoutSubSwathIsReported() throws Exception {
        final Sentinel1Utils su = new Sentinel1Utils(createProductWithoutSwathMetadata());

        try {
            su.computeReferenceTime();
            fail("expected an IOException naming the missing sub-swath metadata");
        } catch (IOException e) {
            assertTrue("unexpected message: " + e.getMessage(), e.getMessage().contains("sub-swath"));
        }
    }

    /**
     * getSubSwathIndex returns 0 when no sub-swath matches, and the geolocation getters feed that
     * straight back as subSwath[subSwathIndex - 1]. On a product with no sub-swath that is
     * subSwath[-1] - the same crash class, one method away.
     */
    @Test
    public void testGetLatitudeWithoutSubSwathIsReported() throws Exception {
        final Sentinel1Utils su = new Sentinel1Utils(createProductWithoutSwathMetadata());

        try {
            su.getLatitude(0.0, 0.0);
            fail("expected a reported failure rather than a negative array index");
        } catch (IllegalArgumentException e) {
            assertNotNull(e.getMessage());
            assertTrue("message should name the product: " + e.getMessage(),
                    e.getMessage().contains("C2_product"));
        }
    }

    /**
     * getSubSwathIndex returns its 0 sentinel both for a time before the first sub-swath and for one
     * past the last. computeIndex extrapolates at either edge by design (it clamps j0/j1 and i0/i1),
     * and TOPSARDeburst samples the far-range column of a multi-swath grid whose width is rounded up
     * and so can overshoot slrTimeToLastPixel by a fraction of a pixel. Clamp to the nearest
     * sub-swath rather than refuse a valid product.
     */
    @Test
    public void testClampSubSwathIndexPicksTheNearestEdge() {
        assertEquals(1, Sentinel1Utils.clampSubSwathIndex(0.004, 0.005, 3));
        assertEquals(3, Sentinel1Utils.clampSubSwathIndex(0.009, 0.005, 3));
        assertEquals(1, Sentinel1Utils.clampSubSwathIndex(0.009, 0.005, 1));
    }
}
