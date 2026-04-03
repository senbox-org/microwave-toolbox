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
package eu.esa.sar.io.sentinel1;

import com.bc.ceres.annotation.STTM;
import org.esa.snap.core.dataio.geocoding.ComponentGeoCoding;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

@STTM("SNAP-3834")
public class TestSentinel1OCNReader {

    @Test
    public void findGeoCodingReturnsGeoCodingWhenLatLonBandsExist() throws IOException {
        Product product = TestUtils.createProduct("IMG001", 10, 10);
        Band latBand = TestUtils.createBand(product, "rviLat_IMG001", 10, 10);
        Band lonBand = TestUtils.createBand(product, "rviLon_IMG001", 10, 10);
        Band targetBand = TestUtils.createBand(product, "rviTargetBand_IMG001", 10, 10);
        Band[] bands = {latBand, lonBand, targetBand};

        Sentinel1OCNReader reader = new Sentinel1OCNReader(null);
        ComponentGeoCoding geoCoding = reader.findGeoCoding(bands, targetBand);

        assertNotNull(geoCoding);
    }

    @Test
    public void findGeoCodingReturnsNullWhenNoMatchingBandsExist() throws IOException {
        Product product = TestUtils.createProduct("IMG001", 10, 10);
        Band latBand = TestUtils.createBand(product, "rviLat_IMG001", 10, 10);
        Band lonBand = TestUtils.createBand(product, "rviLon_IMG001", 10, 10);
        Band targetBand = TestUtils.createBand(product, "rviTargetBand_IMG002", 10, 10);
        Band[] bands = {latBand, lonBand, targetBand};

        Sentinel1OCNReader reader = new Sentinel1OCNReader(null);
        ComponentGeoCoding geoCoding = reader.findGeoCoding(bands, targetBand);

        assertNull(geoCoding);
    }


    @Test
    public void getSwathReturnsCorrectSwathWhenSwathExists() {
        Sentinel1OCNReader reader = new Sentinel1OCNReader(null);
        String bandName = "band_Swath1_IMG001";

        String swath = reader.getSwath(bandName);

        assertEquals("_Swath1", swath);
    }

    @Test
    public void getSwathReturnsNullWhenSwathDoesNotExist() {
        Sentinel1OCNReader reader = new Sentinel1OCNReader(null);
        String bandName = "band_IMG001";

        String swath = reader.getSwath(bandName);

        assertNull(swath);
    }

    @Test
    public void getSwathNumberReturnsCorrectNumberWhenSwathExists() {
        Sentinel1OCNReader reader = new Sentinel1OCNReader(null);
        String bandName = "band_Swath2_IMG001";

        int swathNumber = reader.getSwathNumber(bandName);

        assertEquals(1, swathNumber);
    }

    @Test
    public void getSwathNumberReturnsZeroWhenSwathDoesNotExist() {
        Sentinel1OCNReader reader = new Sentinel1OCNReader(null);
        String bandName = "band_IMG001";

        int swathNumber = reader.getSwathNumber(bandName);

        assertEquals(0, swathNumber);
    }
}
