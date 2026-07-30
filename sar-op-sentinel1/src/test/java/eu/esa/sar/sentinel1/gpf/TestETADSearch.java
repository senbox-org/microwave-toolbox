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
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.util.io.FileUtils;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

@STTM("SNAP-3707")
public class TestETADSearch {

    private final File S1_Pre_ETAD = new File(TestData.inputSAR + "S1/GRD/Hawaii_slices/S1A_IW_GRDH_1SDV_20180514T043029_20180514T043054_021896_025D31_BBDA.zip");
    private final File S1_GRD = new File(TestData.inputSAR + "S1/GRD/S1A_IW_GRDH_1SDV_20240508T062559_20240508T062624_053776_0688DB_1A13.SAFE.zip");
    private final File S1_SLC_IW2 = new File(TestData.inputSAR + "S1/ETAD/IW/Etna/S1A_IW_SLC__1SDV_20240717T050507_20240717T050534_054796_06AC2D_DE30_split.dim");

    @Before
    public void setUp() {
        // If any of the file does not exist: the test will be ignored
        assumeTrue(S1_Pre_ETAD + " not found", S1_Pre_ETAD.exists());
        assumeTrue(S1_GRD + " not found", S1_GRD.exists());
        assumeTrue(S1_SLC_IW2 + " not found", S1_SLC_IW2.exists());

        final DataSpaces dataSpaces = new DataSpaces();
        assumeTrue("DataSpaces credentials not found", dataSpaces.hasToken());
    }

    @Test
    public void testGetTime() throws Exception {
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

            // The overlap query legitimately returns the matching slice PLUS the abutting slices of
            // the same datatake (the ±5 s search pad overlaps their boundaries); the operator picks
            // the maximum-overlap candidate. Exercise that same selection here, and require the
            // chosen product to actually cover the scene — downloading results[0] blindly was
            // exactly the wrong-slice bug the selection fixed.
            assertTrue("At least one ETAD candidate", results.length >= 1);
            final DataSpaces.Result best = S1ETADCorrectionOp.selectBestOverlap(s1GRD, results);
            assertNotNull(best);
            assertSelectedCoversScene(s1GRD, best);

            File outputFolder = Files.createTempDirectory("etad").toFile();
            File file = etadSearch.download(best, outputFolder);
            assert file.exists();

            s1GRD.dispose();
            FileUtils.deleteTree(outputFolder);
        }
    }

    @Test
    public void testSLCProduct() throws Exception {
        try(Product s1GRD = TestUtils.readSourceProduct(S1_SLC_IW2)) {

            ETADSearch etadSearch = new ETADSearch();
            DataSpaces.Result[] results = etadSearch.search(s1GRD);

            assertTrue("At least one ETAD candidate", results.length >= 1);
            final DataSpaces.Result best = S1ETADCorrectionOp.selectBestOverlap(s1GRD, results);
            assertNotNull(best);
            assertSelectedCoversScene(s1GRD, best);

            File outputFolder = Files.createTempDirectory("etad").toFile();
            File file = etadSearch.download(best, outputFolder);
            assert file.exists();

            s1GRD.dispose();
            FileUtils.deleteTree(outputFolder);
        }
    }

    /** The selected ETAD must cover the scene's sensing window (2 s tolerance, matching the
     *  operator's validation) — an abutting slice overlaps by seconds and must never win. */
    private static void assertSelectedCoversScene(final Product product, final DataSpaces.Result best)
            throws Exception {
        final double tolDays = 2.0 / 86400.0;
        final double etadStart = ProductData.UTC.parse(
                best.getStartTime().replace("Z", ""), "yyyy-MM-dd'T'HH:mm:ss").getMJD();
        final double etadEnd = ProductData.UTC.parse(
                best.getEndTime().replace("Z", ""), "yyyy-MM-dd'T'HH:mm:ss").getMJD();
        assertTrue("selected ETAD '" + best.getName() + "' starts after the scene",
                etadStart <= product.getStartTime().getMJD() + tolDays);
        assertTrue("selected ETAD '" + best.getName() + "' ends before the scene",
                etadEnd >= product.getEndTime().getMJD() - tolDays);
    }
}
