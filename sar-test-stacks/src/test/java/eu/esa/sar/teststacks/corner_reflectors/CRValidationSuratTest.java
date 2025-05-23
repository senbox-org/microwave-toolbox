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
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.Product;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.junit.Assume.assumeTrue;

public class CRValidationSuratTest extends BaseCRTest {

    private final static File S1_GRD_Surat = new File(TestData.inputSAR + "S1/corner_reflectors/GA/Surat/S1A_IW_GRDH_1SDV_20250413T192217_20250413T192242_058742_0746BD_0527.SAFE.zip");
    private final static String Surat_CSV = "/eu/esa/sar/teststacks/corner_reflectors/GA/surat_basin_queensland_calibration_targets.csv";

    private File tempFolder = new File("/tmp/corner_reflectors/GA");

    @Before
    public void setUp() {
        // If any of the file does not exist: the test will be ignored
        assumeTrue(S1_GRD_Surat + " not found", S1_GRD_Surat.exists());
        tempFolder.mkdirs();
    }

    @Test
    public void testGA() throws IOException {
        List<String[]> csv = readCSVFile(Surat_CSV);

        Product product = ProductIO.readProduct(S1_GRD_Surat);
        Assert.assertNotNull(product);

        for (String[] line : csv) {
            String id = line[0];
            // skip the header
            if (id.contains("ID")) {
                continue;
            }

            String type = line[1];
            String description = line[2];
            double lat = Double.parseDouble(line[3]);
            double lon = Double.parseDouble(line[4]);
            double alt = Double.parseDouble(line[5]);

            // add a placemark at each corner reflector
            addPin(product, id, lat, lon);
        }

        ProductIO.writeProduct(product, tempFolder.getAbsolutePath() +"/"+ product.getName()+".dim", "BEAM-DIMAP");
    }

}
