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
import eu.esa.sar.sar.gpf.MultilookOp;
import eu.esa.sar.sar.gpf.geometric.RangeDopplerGeocodingOp;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.Product;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assume.assumeTrue;

public class CRValidationRosamondGRDTest extends BaseCRTest {

    private final static File S1_GRD_Rosamond = new File(TestData.inputSAR + "S1/corner_reflectors/JPL/Rosamond/S1A_IW_GRDH_1SDV_20250427T135223_20250427T135248_058943_074EF3_3768.SAFE.zip");
    private final static String Rosamond_CSV = "/eu/esa/sar/teststacks/corner_reflectors/JPL/2025-05-22_0000_Rosamond-corner-reflectors_with_plate_motion.csv";

    private File S1_GRD = S1_GRD_Rosamond;
    private String csvFile = Rosamond_CSV;

    public CRValidationRosamondGRDTest() {
        super("Rosamond");
    }

    @Before
    public void setUp() {
        // If any of the file does not exist: the test will be ignored
        assumeTrue(S1_GRD_Rosamond + " not found", S1_GRD_Rosamond.exists());
    }

    @Test
    @Ignore
    public void testJPL_Rosamond_GRD1() throws IOException {
        setName(new Throwable().getStackTrace()[0].getMethodName());

        Product product = ProductIO.readProduct(S1_GRD);
        Assert.assertNotNull(product);

        RangeDopplerGeocodingOp terrainCorrectionOp = new RangeDopplerGeocodingOp();
        terrainCorrectionOp.setSourceProduct(product);
        terrainCorrectionOp.setParameter("demName", "Copernicus 30m Global DEM");
        Product trgProduct = terrainCorrectionOp.getTargetProduct();

        addCornerReflectorPins(csvFile, trgProduct);

        write(trgProduct);
    }

    @Test
    @Ignore
    public void testJPL_Rosamond_GRD2() throws IOException {
        setName(new Throwable().getStackTrace()[0].getMethodName());

        Product product = ProductIO.readProduct(S1_GRD);
        Assert.assertNotNull(product);

        ApplyOrbitFileOp applyOrbitOp = new ApplyOrbitFileOp();
        applyOrbitOp.setSourceProduct(product);

        RangeDopplerGeocodingOp terrainCorrectionOp = new RangeDopplerGeocodingOp();
        terrainCorrectionOp.setSourceProduct(applyOrbitOp.getTargetProduct());
        terrainCorrectionOp.setParameter("demName", "Copernicus 30m Global DEM");
        Product trgProduct = terrainCorrectionOp.getTargetProduct();

        addCornerReflectorPins(csvFile, trgProduct);

        write(trgProduct);
    }

    @Test
    public void testGeolocationErrors_GRD_TC() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());

        Product srcProduct = ProductIO.readProduct(S1_GRD);
        Assert.assertNotNull(srcProduct);

        RangeDopplerGeocodingOp terrainCorrectionOp = new RangeDopplerGeocodingOp();
        terrainCorrectionOp.setSourceProduct(srcProduct);
        terrainCorrectionOp.setParameter("demName", "Copernicus 30m Global DEM");
        Product trgProduct = terrainCorrectionOp.getTargetProduct();

        computeCRGeoLocationError(csvFile, trgProduct);
    }

    @Test
    public void testGeolocationErrors_GRD_orbit_TC() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());

        Product srcProduct = ProductIO.readProduct(S1_GRD);
        Assert.assertNotNull(srcProduct);

        ApplyOrbitFileOp applyOrbitOp = new ApplyOrbitFileOp();
        applyOrbitOp.setSourceProduct(srcProduct);

        RangeDopplerGeocodingOp terrainCorrectionOp = new RangeDopplerGeocodingOp();
        terrainCorrectionOp.setSourceProduct(applyOrbitOp.getTargetProduct());
        terrainCorrectionOp.setParameter("demName", "Copernicus 30m Global DEM");
        Product trgProduct = terrainCorrectionOp.getTargetProduct();

        computeCRGeoLocationError(csvFile, trgProduct);
    }

    @Test
    public void testGeolocationErrors_GRD_orbit_ML_TC() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());

        Product srcProduct = ProductIO.readProduct(S1_GRD);
        Assert.assertNotNull(srcProduct);

        ApplyOrbitFileOp applyOrbitOp = new ApplyOrbitFileOp();
        applyOrbitOp.setSourceProduct(srcProduct);

        MultilookOp multilookOp = new MultilookOp();
        multilookOp.setSourceProduct(applyOrbitOp.getTargetProduct());

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
