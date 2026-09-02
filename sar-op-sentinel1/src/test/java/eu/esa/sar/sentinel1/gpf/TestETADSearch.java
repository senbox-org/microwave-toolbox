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
package eu.esa.sar.sentinel1.gpf;

import com.bc.ceres.annotation.STTM;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

/**
 * Fast, offline ETADSearch unit tests. The live Copernicus Data Space search + download
 * integration tests live in {@link TestETADSearchLongTest} (LongTestRunner-gated): they need
 * credentials, network, and tens of seconds each.
 */
@STTM("SNAP-3707")
public class TestETADSearch {

    private final File S1_GRD = new File(TestData.inputSAR + "S1/GRD/S1A_IW_GRDH_1SDV_20240508T062559_20240508T062624_053776_0688DB_1A13.SAFE.zip");

    @Test
    public void testGetTime() throws Exception {
        assumeTrue(S1_GRD + " not found", S1_GRD.exists());
        try(Product s1GRD = TestUtils.readSourceProduct(S1_GRD)) {
            ETADSearch etadSearch = new ETADSearch();
            String startTime = etadSearch.getTime(s1GRD.getStartTime());
            assertEquals("2024-05-08T06:25:59.776Z", startTime);
        }
    }

    @Test
    public void testETADProductType() {
        ETADSearch etadSearch = new ETADSearch();
        String productType = etadSearch.getETADProductType("IW");
        assertEquals("IW_ETA__AX", productType);

        productType = etadSearch.getETADProductType("EW");
        assertEquals("EW_ETA__AX", productType);

        productType = etadSearch.getETADProductType("SM");
        assertEquals("SM_ETA__AX", productType);

        productType = etadSearch.getETADProductType("WV");
        assertEquals("WV_ETA__AX", productType);

        productType = etadSearch.getETADProductType("XX");
        assertEquals("IW_ETA__AX", productType);
    }
}
