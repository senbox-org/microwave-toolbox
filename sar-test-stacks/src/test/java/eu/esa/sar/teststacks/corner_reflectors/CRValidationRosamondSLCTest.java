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

import com.bc.ceres.test.LongTestRunner;
import eu.esa.sar.commons.test.TestData;
import eu.esa.sar.orbits.gpf.ApplyOrbitFileOp;
import eu.esa.sar.sar.gpf.MultilookOp;
import eu.esa.sar.sar.gpf.geometric.RangeDopplerGeocodingOp;
import eu.esa.sar.sentinel1.gpf.TOPSARDeburstOp;
import eu.esa.sar.sentinel1.gpf.TOPSARSplitOp;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.Product;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assume.assumeTrue;

@RunWith(LongTestRunner.class)
public class CRValidationRosamondSLCTest extends BaseCRTest {

    private final static File S1_SLC_Rosamond = new File(TestData.inputSAR + "S1/corner_reflectors/JPL/Rosamond/S1A_IW_SLC__1SDV_20250415T135221_20250415T135248_058768_0747CA_07E0.SAFE.zip");
    private final static String Rosamond_CSV = "/eu/esa/sar/teststacks/corner_reflectors/JPL/2025-05-22_0000_Rosamond-corner-reflectors_with_plate_motion.csv";

    private File S1_SLC = S1_SLC_Rosamond;
    private String csvFile = Rosamond_CSV;

    public CRValidationRosamondSLCTest() {
        super("Rosamond");
    }

    @Before
    public void setUp() {
        // If any of the file does not exist: the test will be ignored
        assumeTrue(S1_SLC_Rosamond + " not found", S1_SLC_Rosamond.exists());
    }

    @Test
    @Ignore
    public void testJPL_Rosamond_SLC1() throws IOException {
        setName(new Throwable().getStackTrace()[0].getMethodName());

        Product product = ProductIO.readProduct(S1_SLC);
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

        addCornerReflectorPins(csvFile, trgProduct);

        write(trgProduct);
    }

    @Test
    @Ignore
    public void testJPL_Rosamond_SLC2() throws IOException {
        setName(new Throwable().getStackTrace()[0].getMethodName());

        Product product = ProductIO.readProduct(S1_SLC);
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

        addCornerReflectorPins(csvFile, trgProduct);

        write(trgProduct);
    }

    @Test
    @Ignore
    public void testJPL_Rosamond_SLC3() throws IOException {
        setName(new Throwable().getStackTrace()[0].getMethodName());

        Product product = ProductIO.readProduct(S1_SLC);
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

        addCornerReflectorPins(csvFile, trgProduct);

        write(trgProduct);
    }

    @Test
    public void testGeolocationErrors_SLC() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());

        Product srcProduct = ProductIO.readProduct(S1_SLC);
        Assert.assertNotNull(srcProduct);

        TOPSARDeburstOp deburstOp = new TOPSARDeburstOp();
        deburstOp.setSourceProduct(srcProduct);
        Product trgProduct = deburstOp.getTargetProduct();

        computeCRGeoLocationError(csvFile, trgProduct);
    }

    @Test
    public void testGeolocationErrors_SLC_TC() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());

        Product srcProduct = ProductIO.readProduct(S1_SLC);
        Assert.assertNotNull(srcProduct);

        TOPSARDeburstOp deburstOp = new TOPSARDeburstOp();
        deburstOp.setSourceProduct(srcProduct);

        RangeDopplerGeocodingOp terrainCorrectionOp = new RangeDopplerGeocodingOp();
        terrainCorrectionOp.setSourceProduct(deburstOp.getTargetProduct());
        terrainCorrectionOp.setParameter("demName", "Copernicus 30m Global DEM");
        Product trgProduct = terrainCorrectionOp.getTargetProduct();

        computeCRGeoLocationError(csvFile, trgProduct);
    }

    @Test
    public void testGeolocationErrors_SLC_Orbit() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());

        Product srcProduct = ProductIO.readProduct(S1_SLC);
        Assert.assertNotNull(srcProduct);

        ApplyOrbitFileOp applyOrbitOp = new ApplyOrbitFileOp();
        applyOrbitOp.setSourceProduct(srcProduct);

        TOPSARDeburstOp deburstOp = new TOPSARDeburstOp();
        deburstOp.setSourceProduct(applyOrbitOp.getTargetProduct());
        Product trgProduct = deburstOp.getTargetProduct();

        computeCRGeoLocationError(csvFile, trgProduct);
    }

    @Test
    public void testGeolocationErrors_SLC_Orbit_TC() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());

        Product srcProduct = ProductIO.readProduct(S1_SLC);
        Assert.assertNotNull(srcProduct);

        ApplyOrbitFileOp applyOrbitOp = new ApplyOrbitFileOp();
        applyOrbitOp.setSourceProduct(srcProduct);

        TOPSARDeburstOp deburstOp = new TOPSARDeburstOp();
        deburstOp.setSourceProduct(applyOrbitOp.getTargetProduct());

        RangeDopplerGeocodingOp terrainCorrectionOp = new RangeDopplerGeocodingOp();
        terrainCorrectionOp.setSourceProduct(deburstOp.getTargetProduct());
        terrainCorrectionOp.setParameter("demName", "Copernicus 30m Global DEM");
        Product trgProduct = terrainCorrectionOp.getTargetProduct();

        computeCRGeoLocationError(csvFile, trgProduct);
    }

    @Test
    public void testGeolocationErrors_SLC_Orbit_ML_TC() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());

        Product srcProduct = ProductIO.readProduct(S1_SLC);
        Assert.assertNotNull(srcProduct);

        ApplyOrbitFileOp applyOrbitOp = new ApplyOrbitFileOp();
        applyOrbitOp.setSourceProduct(srcProduct);

        TOPSARDeburstOp deburstOp = new TOPSARDeburstOp();
        deburstOp.setSourceProduct(applyOrbitOp.getTargetProduct());

        MultilookOp multilookOp = new MultilookOp();
        multilookOp.setSourceProduct(deburstOp.getTargetProduct());

        RangeDopplerGeocodingOp terrainCorrectionOp = new RangeDopplerGeocodingOp();
        terrainCorrectionOp.setSourceProduct(multilookOp.getTargetProduct());
        terrainCorrectionOp.setParameter("demName", "Copernicus 30m Global DEM");
        Product trgProduct = terrainCorrectionOp.getTargetProduct();

        computeCRGeoLocationError(csvFile, trgProduct);
    }

    @Override
    protected GeoPoint[] readCRGeoPoints(String crCSV) throws IOException {
        final List<String[]> csv = readCSVFile(crCSV);
        final List<GeoPoint> crPoints = new ArrayList<>();

        for (String[] line : csv) {
            String id = line[0];
            // skip the header
            if (id.contains("ID")) {
                continue;
            }

            double lat = Double.parseDouble(line[1]);
            double lon = Double.parseDouble(line[2]);
            double alt = Double.parseDouble(line[3]);

            crPoints.add(new GeoPoint(id, lat, lon, alt));
        }

        return crPoints.toArray(new GeoPoint[0]);
    }
}
