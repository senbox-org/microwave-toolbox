/*
 * Copyright (C) 2025 SkyWatch. https://www.skywatch.com
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
package eu.esa.sar.teststacks.corner_reflectors;

import eu.esa.sar.commons.test.TestData;
import eu.esa.sar.orbits.gpf.ApplyOrbitFileOp;
import eu.esa.sar.sentinel1.gpf.TOPSARDeburstOp;
import eu.esa.sar.sentinel1.gpf.TOPSARSplitOp;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.Product;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.junit.Assume.assumeTrue;

public class CRValidationRosamondTest extends BaseCRTest {

    private final static File S1_GRD_Rosamond = new File(TestData.inputSAR + "S1/corner_reflectors/JPL/Rosamond/S1A_IW_GRDH_1SDV_20250427T135223_20250427T135248_058943_074EF3_3768.SAFE.zip");
    private final static File S1_SLC_Rosamond = new File(TestData.inputSAR + "S1/corner_reflectors/JPL/Rosamond/S1A_IW_SLC__1SDV_20250415T135221_20250415T135248_058768_0747CA_07E0.SAFE.zip");
    private final static String Rosamond_CSV = "/eu/esa/sar/teststacks/corner_reflectors/JPL/2025-05-22_0000_Rosamond-corner-reflectors_with_plate_motion.csv";

    private File tempFolder = new File("/tmp/corner_reflectors/JPL");

    @Before
    public void setUp() {
        // If any of the file does not exist: the test will be ignored
        assumeTrue(S1_GRD_Rosamond + " not found", S1_GRD_Rosamond.exists());
        assumeTrue(S1_SLC_Rosamond + " not found", S1_SLC_Rosamond.exists());
        tempFolder.mkdirs();
    }

    @Test
    public void testJPL_Rosamond_GRD() throws IOException {
        List<String[]> csv = readCSVFile(Rosamond_CSV);

        Product product = ProductIO.readProduct(S1_GRD_Rosamond);
        Assert.assertNotNull(product);

        for (String[] line : csv) {
            String id = line[0];
            // skip the header
            if (id.contains("ID")) {
                continue;
            }

            double lat = Double.parseDouble(line[1]);
            double lon = Double.parseDouble(line[2]);
            double alt = Double.parseDouble(line[3]);

            // add a placemark at each corner reflector
            addPin(product, id, lat, lon);
        }

        ProductIO.writeProduct(product, tempFolder.getAbsolutePath() +"/"+ product.getName()+".dim", "BEAM-DIMAP");
    }

    @Test
    public void testJPL_Rosamond_SLC() throws IOException {
        List<String[]> csv = readCSVFile(Rosamond_CSV);

        Product product = ProductIO.readProduct(S1_SLC_Rosamond);
        Assert.assertNotNull(product);

        ApplyOrbitFileOp applyOrbitOp = new ApplyOrbitFileOp();
        applyOrbitOp.setSourceProduct(product);

        TOPSARSplitOp splitOp = new TOPSARSplitOp();
        splitOp.setSourceProduct(applyOrbitOp.getTargetProduct());
        splitOp.setParameter("subswath", "IW2");
        splitOp.setParameter("selectedPolarisations", "VV");

        TOPSARDeburstOp deburstOp = new TOPSARDeburstOp();
        deburstOp.setSourceProduct(splitOp.getTargetProduct());
        Product trgProduct = deburstOp.getTargetProduct();

        for (String[] line : csv) {
            String id = line[0];
            // skip the header
            if (id.contains("ID")) {
                continue;
            }

            double lat = Double.parseDouble(line[1]);
            double lon = Double.parseDouble(line[2]);
            double alt = Double.parseDouble(line[3]);

            // add a placemark at each corner reflector
            addPin(trgProduct, id, lat, lon);
        }

        ProductIO.writeProduct(trgProduct, tempFolder.getAbsolutePath() +"/"+ trgProduct.getName()+".dim", "BEAM-DIMAP");
    }

}
