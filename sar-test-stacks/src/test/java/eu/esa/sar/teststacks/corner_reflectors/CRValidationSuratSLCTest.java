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
import eu.esa.sar.sentinel1.gpf.TOPSARDeburstOp;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.*;
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
public class CRValidationSuratSLCTest extends BaseCRTest {

    private final static File S1_SLC_Surat = new File(TestData.inputSAR + "S1/corner_reflectors/GA/Surat/S1A_IW_SLC__1SSV_20231225T083315_20231225T083343_051808_064216_090C.SAFE.zip");
    private final static File S1_SLC_NRB_Surat = new File(TestData.inputSAR + "S1/corner_reflectors/GA/Surat/S1A_IW_SLC__1SSV_20231225T083315_20231225T083343_051808_064216_090C_Cal_NR_Deb_Orb_ML_TF.dim");
    private final static String Surat_CSV = "/eu/esa/sar/teststacks/corner_reflectors/GA/surat_basin_queensland_calibration_targets.csv";

    private File S1_SLC = S1_SLC_Surat;
    private String csvFile = Surat_CSV;

    public CRValidationSuratSLCTest() {
        super("Surat/SLC");
    }

    @Before
    public void setUp() {
        // If any of the file does not exist: the test will be ignored
        assumeTrue(S1_SLC_Surat + " not found", S1_SLC_Surat.exists());
        assumeTrue(S1_SLC_NRB_Surat + " not found", S1_SLC_NRB_Surat.exists());
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
    public void testGeolocationErrors_SLC_Orbit_TC_Cop30() throws Exception {
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
    public void testGeolocationErrors_SLC_Orbit_TC_SRTM() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());

        Product srcProduct = ProductIO.readProduct(S1_SLC);
        Assert.assertNotNull(srcProduct);

        ApplyOrbitFileOp applyOrbitOp = new ApplyOrbitFileOp();
        applyOrbitOp.setSourceProduct(srcProduct);

        TOPSARDeburstOp deburstOp = new TOPSARDeburstOp();
        deburstOp.setSourceProduct(applyOrbitOp.getTargetProduct());

        RangeDopplerGeocodingOp terrainCorrectionOp = new RangeDopplerGeocodingOp();
        terrainCorrectionOp.setSourceProduct(deburstOp.getTargetProduct());
        terrainCorrectionOp.setParameter("demName", "SRTM 3sec");
        Product trgProduct = terrainCorrectionOp.getTargetProduct();

        computeCRGeoLocationError(csvFile, trgProduct);
    }

    @Test
    public void testGeolocationErrors_SLC_Orbit_TF_TC_Cop30() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());

        Product srcProduct = ProductIO.readProduct(S1_SLC);
        Assert.assertNotNull(srcProduct);

        ApplyOrbitFileOp applyOrbitOp = new ApplyOrbitFileOp();
        applyOrbitOp.setSourceProduct(srcProduct);

        TOPSARDeburstOp deburstOp = new TOPSARDeburstOp();
        deburstOp.setSourceProduct(applyOrbitOp.getTargetProduct());

        CalibrationOp calibrationOp = new CalibrationOp();
        calibrationOp.setSourceProduct(deburstOp.getTargetProduct());
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

    @Test
    public void testGeolocationErrors_SLC_NRB() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());

        Product srcProduct = ProductIO.readProduct(S1_SLC_NRB_Surat);
        Assert.assertNotNull(srcProduct);

        RangeDopplerGeocodingOp terrainCorrectionOp = new RangeDopplerGeocodingOp();
        terrainCorrectionOp.setSourceProduct(srcProduct);
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
