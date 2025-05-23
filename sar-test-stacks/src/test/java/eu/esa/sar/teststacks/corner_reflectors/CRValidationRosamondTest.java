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
import eu.esa.sar.sar.gpf.geometric.RangeDopplerGeocodingOp;
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

    public CRValidationRosamondTest() {
        super("Rosamond");
    }

    @Before
    public void setUp() {
        // If any of the file does not exist: the test will be ignored
        assumeTrue(S1_GRD_Rosamond + " not found", S1_GRD_Rosamond.exists());
        assumeTrue(S1_SLC_Rosamond + " not found", S1_SLC_Rosamond.exists());
    }

    @Test
    public void testJPL_Rosamond_GRD1() throws IOException {
        setName(new Throwable().getStackTrace()[0].getMethodName());

        Product product = ProductIO.readProduct(S1_GRD_Rosamond);
        Assert.assertNotNull(product);

        RangeDopplerGeocodingOp terrainCorrectionOp = new RangeDopplerGeocodingOp();
        terrainCorrectionOp.setSourceProduct(product);
        terrainCorrectionOp.setParameter("demName", "Copernicus 30m Global DEM");
        Product trgProduct = terrainCorrectionOp.getTargetProduct();

        addCornerReflectorPins(trgProduct);

        write(trgProduct);
    }

    @Test
    public void testJPL_Rosamond_GRD2() throws IOException {
        setName(new Throwable().getStackTrace()[0].getMethodName());

        Product product = ProductIO.readProduct(S1_GRD_Rosamond);
        Assert.assertNotNull(product);

        ApplyOrbitFileOp applyOrbitOp = new ApplyOrbitFileOp();
        applyOrbitOp.setSourceProduct(product);

        RangeDopplerGeocodingOp terrainCorrectionOp = new RangeDopplerGeocodingOp();
        terrainCorrectionOp.setSourceProduct(applyOrbitOp.getTargetProduct());
        terrainCorrectionOp.setParameter("demName", "Copernicus 30m Global DEM");
        Product trgProduct = terrainCorrectionOp.getTargetProduct();

        addCornerReflectorPins(trgProduct);

        write(trgProduct);
    }

    @Test
    public void testJPL_Rosamond_SLC1() throws IOException {
        setName(new Throwable().getStackTrace()[0].getMethodName());

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

        addCornerReflectorPins(trgProduct);

        write(trgProduct);
    }

    @Test
    public void testJPL_Rosamond_SLC2() throws IOException {
        setName(new Throwable().getStackTrace()[0].getMethodName());

        Product product = ProductIO.readProduct(S1_SLC_Rosamond);
        Assert.assertNotNull(product);

        TOPSARSplitOp splitOp = new TOPSARSplitOp();
        splitOp.setSourceProduct(product);
        splitOp.setParameter("subswath", "IW2");
        splitOp.setParameter("selectedPolarisations", "VV");

        ApplyOrbitFileOp applyOrbitOp = new ApplyOrbitFileOp();
        applyOrbitOp.setSourceProduct(splitOp.getTargetProduct());

        TOPSARDeburstOp deburstOp = new TOPSARDeburstOp();
        deburstOp.setSourceProduct(applyOrbitOp.getTargetProduct());
        Product trgProduct = deburstOp.getTargetProduct();

        addCornerReflectorPins(trgProduct);

        write(trgProduct);
    }

    @Test
    public void testJPL_Rosamond_SLC3() throws IOException {
        setName(new Throwable().getStackTrace()[0].getMethodName());

        Product product = ProductIO.readProduct(S1_SLC_Rosamond);
        Assert.assertNotNull(product);

        TOPSARSplitOp splitOp = new TOPSARSplitOp();
        splitOp.setSourceProduct(product);
        splitOp.setParameter("subswath", "IW2");
        splitOp.setParameter("selectedPolarisations", "VV");

        TOPSARDeburstOp deburstOp = new TOPSARDeburstOp();
        deburstOp.setSourceProduct(splitOp.getTargetProduct());

        ApplyOrbitFileOp applyOrbitOp = new ApplyOrbitFileOp();
        applyOrbitOp.setSourceProduct(deburstOp.getTargetProduct());
        Product trgProduct = applyOrbitOp.getTargetProduct();

        addCornerReflectorPins(trgProduct);

        write(trgProduct);
    }

    private void addCornerReflectorPins(Product trgProduct) throws IOException {
        final List<String[]> csv = readCSVFile(Rosamond_CSV);

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
    }

}
