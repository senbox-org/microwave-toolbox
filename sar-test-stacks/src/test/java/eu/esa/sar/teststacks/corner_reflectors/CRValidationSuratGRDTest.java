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
import eu.esa.sar.calibration.gpf.CalibrationOp;
import eu.esa.sar.commons.test.TestData;
import eu.esa.sar.orbits.gpf.ApplyOrbitFileOp;
import eu.esa.sar.sar.gpf.MultilookOp;
import eu.esa.sar.sar.gpf.geometric.RangeDopplerGeocodingOp;
import eu.esa.sar.sar.gpf.geometric.TerrainFlatteningOp;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.Product;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assume.assumeTrue;

@RunWith(LongTestRunner.class)
public class CRValidationSuratGRDTest extends BaseCRTest {

    private final static File S1_GRD_Surat = new File(TestData.inputSAR + "S1/corner_reflectors/GA/Surat/S1A_IW_GRDH_1SSV_20231225T083316_20231225T083341_051808_064216_FBB8.SAFE.zip");
    private final static String Surat_CSV = "/eu/esa/sar/teststacks/corner_reflectors/GA/surat_basin_queensland_calibration_targets.csv";

    private File S1_GRD = S1_GRD_Surat;
    private String csvFile = Surat_CSV;

    public CRValidationSuratGRDTest() {
        super("Surat/GRD");
    }

    @Before
    public void setUp() {
        // If any of the file does not exist: the test will be ignored
        assumeTrue(S1_GRD_Surat + " not found", S1_GRD_Surat.exists());
    }

    @Test
    public void testGA() throws IOException {
        setName(new Throwable().getStackTrace()[0].getMethodName());

        Product product = ProductIO.readProduct(S1_GRD);
        Assert.assertNotNull(product);

        addCornerReflectorPins(csvFile, product);

        write(product);
    }

    @Test
    public void testGeolocationErrors_GRD() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());

        Product srcProduct = ProductIO.readProduct(S1_GRD);
        Assert.assertNotNull(srcProduct);

        computeCRGeoLocationError(csvFile, srcProduct);
    }

    @Test
    public void testGeolocationErrors_GRD_TC_Cop30() throws Exception {
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
    public void testGeolocationErrors_GRD_TC_SRTM() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());

        Product srcProduct = ProductIO.readProduct(S1_GRD);
        Assert.assertNotNull(srcProduct);

        RangeDopplerGeocodingOp terrainCorrectionOp = new RangeDopplerGeocodingOp();
        terrainCorrectionOp.setSourceProduct(srcProduct);
        terrainCorrectionOp.setParameter("demName", "SRTM 1Sec HGT");
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
    public void testGeolocationErrors_GRD_orbit_Cal_TF_TC() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());

        Product srcProduct = ProductIO.readProduct(S1_GRD);
        Assert.assertNotNull(srcProduct);

        ApplyOrbitFileOp applyOrbitOp = new ApplyOrbitFileOp();
        applyOrbitOp.setSourceProduct(srcProduct);

        CalibrationOp calibrationOp = new CalibrationOp();
        calibrationOp.setSourceProduct(applyOrbitOp.getTargetProduct());
        calibrationOp.setParameter("outputSigmaBand", false);
        calibrationOp.setParameter("outputBetaBand", true);
        calibrationOp.setParameter("outputGammaBand", false);

        TerrainFlatteningOp terrainFlatteningOp = new TerrainFlatteningOp();
        terrainFlatteningOp.setSourceProduct(calibrationOp.getTargetProduct());
        terrainFlatteningOp.setParameter("demName", "Copernicus 30m Global DEM");

        RangeDopplerGeocodingOp terrainCorrectionOp = new RangeDopplerGeocodingOp();
        terrainCorrectionOp.setSourceProduct(terrainFlatteningOp.getTargetProduct());
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

            String type = line[1];
            String description = line[2];
            double lat = Double.parseDouble(line[3]);
            double lon = Double.parseDouble(line[4]);
            double alt = Double.parseDouble(line[5]);

            crPoints.add(new GeoPoint(id, lat, lon, alt));
        }

        return crPoints.toArray(new GeoPoint[0]);
    }
}
