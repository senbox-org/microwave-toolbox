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
import eu.esa.sar.cloud.opendata.DataSpaces;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.util.io.FileUtils;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

@STTM("SNAP-3707")
public class TestETADSearch {

    private final File S1_Pre_ETAD = new File(TestData.inputSAR + "S1/GRD/Hawaii_slices/S1A_IW_GRDH_1SDV_20180514T043029_20180514T043054_021896_025D31_BBDA.zip");
    private final File S1_GRD = new File(TestData.inputSAR + "S1/GRD/S1A_IW_GRDH_1SDV_20240508T062559_20240508T062624_053776_0688DB_1A13.SAFE.zip");

    @Before
    public void setUp() {
        // If any of the file does not exist: the test will be ignored
        assumeTrue(S1_Pre_ETAD + " not found", S1_Pre_ETAD.exists());
        assumeTrue(S1_GRD + " not found", S1_GRD.exists());

        final DataSpaces dataSpaces = new DataSpaces();
        assumeTrue("DataSpaces credentials not found", dataSpaces.hasToken());
    }

    @Test
    public void testGetTime() throws Exception {
        try(Product s1GRD = TestUtils.readSourceProduct(S1_GRD)) {
            ETADSearch etadSearch = new ETADSearch();
            String startTime = etadSearch.getTime(s1GRD.getStartTime());
            assertEquals("2024-05-08T06:25:59.059Z", startTime);
        }
    }

    @Test
    public void testETADProductType() throws Exception {
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

    @Test
    public void testETADNotFound() throws Exception {
        try(Product s1PreEtad = TestUtils.readSourceProduct(S1_Pre_ETAD)) {

            ETADSearch etadSearch = new ETADSearch();
            DataSpaces.Result[] results = etadSearch.search(s1PreEtad);

            assumeTrue("ETAD not found", results.length == 0);

            s1PreEtad.dispose();
        }
    }

    @Test
    public void testGRDProduct() throws Exception {
        try(Product s1GRD = TestUtils.readSourceProduct(S1_GRD)) {

            ETADSearch etadSearch = new ETADSearch();
            DataSpaces.Result[] results = etadSearch.search(s1GRD);

            assumeTrue("One ETAD file found", results.length == 1);

            File outputFolder = Files.createTempDirectory("etad").toFile();
            File file = etadSearch.download(results[0], outputFolder);
            assert file.exists();

            s1GRD.dispose();
            FileUtils.deleteTree(outputFolder);
        }
    }
}
