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

import eu.esa.sar.commons.OrbitStateVectors;
import eu.esa.sar.commons.SARGeocoding;
import eu.esa.sar.commons.SARUtils;
import eu.esa.sar.commons.test.TestData;
import eu.esa.sar.insar.gpf.support.SARPosition;
import eu.esa.sar.orbits.gpf.ApplyOrbitFileOp;
import eu.esa.sar.sar.gpf.geometric.RangeDopplerGeocodingOp;
import eu.esa.sar.sentinel1.gpf.TOPSARDeburstOp;
import eu.esa.sar.sentinel1.gpf.TOPSARSplitOp;
import eu.esa.sar.teststacks.corner_reflectors.utils.Plot;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.TiePointGrid;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.OrbitStateVector;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.eo.GeoUtils;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
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

    @Test
    public void testJPL_GeolocationErrors_GRD_TC() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());

        Product srcProduct = ProductIO.readProduct(S1_GRD_Rosamond);
        Assert.assertNotNull(srcProduct);

        RangeDopplerGeocodingOp terrainCorrectionOp = new RangeDopplerGeocodingOp();
        terrainCorrectionOp.setSourceProduct(srcProduct);
        terrainCorrectionOp.setParameter("demName", "Copernicus 30m Global DEM");
        Product trgProduct = terrainCorrectionOp.getTargetProduct();

        computeCRGeoLocationError(Rosamond_CSV, trgProduct);
    }

    @Test
    public void testJPL_GeolocationErrors_SLC_TC() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());

        Product srcProduct = ProductIO.readProduct(S1_SLC_Rosamond);
        Assert.assertNotNull(srcProduct);

        ApplyOrbitFileOp applyOrbitOp = new ApplyOrbitFileOp();
        applyOrbitOp.setSourceProduct(srcProduct);

        TOPSARDeburstOp deburstOp = new TOPSARDeburstOp();
        deburstOp.setSourceProduct(applyOrbitOp.getTargetProduct());
        Product trgProduct = deburstOp.getTargetProduct();

//        RangeDopplerGeocodingOp terrainCorrectionOp = new RangeDopplerGeocodingOp();
//        terrainCorrectionOp.setSourceProduct(deburstOp.getTargetProduct());
//        terrainCorrectionOp.setParameter("demName", "Copernicus 30m Global DEM");
//        Product trgProduct = terrainCorrectionOp.getTargetProduct();

        computeCRGeoLocationError(Rosamond_CSV, trgProduct);
    }

    private void computeCRGeoLocationError(String crCSV, Product srcProduct) throws Exception {

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(srcProduct);
        final double rangeSpacing = absRoot.getAttributeDouble(AbstractMetadata.range_spacing);
        final double azimuthSpacing = absRoot.getAttributeDouble(AbstractMetadata.azimuth_spacing);
        final double firstLineUTC = AbstractMetadata.parseUTC(absRoot.getAttributeString(AbstractMetadata.first_line_time)).getMJD(); // in days
        final double lastLineUTC = AbstractMetadata.parseUTC(absRoot.getAttributeString(AbstractMetadata.last_line_time)).getMJD(); // in days
        final double lineTimeInterval = absRoot.getAttributeDouble(AbstractMetadata.line_time_interval) / Constants.secondsInDay; // s to day
        final double wavelength = SARUtils.getRadarWavelength(absRoot);
        final boolean srgrFlag = AbstractMetadata.getAttributeBoolean(absRoot, AbstractMetadata.srgr_flag);
        final int sourceImageWidth = srcProduct.getSceneRasterWidth();
        final int sourceImageHeight = srcProduct.getSceneRasterHeight();
        final OrbitStateVector[] orbitStateVectors = AbstractMetadata.getOrbitStateVectors(absRoot);
        double nearEdgeSlantRange = 0.0;
        AbstractMetadata.SRGRCoefficientList[] srgrConvParams = null;
        if (srgrFlag) {
            srgrConvParams = AbstractMetadata.getSRGRCoefficients(absRoot);
        } else {
            nearEdgeSlantRange = AbstractMetadata.getAttributeDouble(absRoot, AbstractMetadata.slant_range_to_first_pixel);
        }
        final TiePointGrid incidenceAngle = OperatorUtils.getIncidenceAngle(srcProduct);
        final boolean nearRangeOnLeft = SARGeocoding.isNearRangeOnLeft(incidenceAngle, sourceImageWidth);
        final OrbitStateVectors orbit = new OrbitStateVectors(orbitStateVectors, firstLineUTC, lineTimeInterval, sourceImageHeight);

        final SARPosition sarPosition = new SARPosition(
                firstLineUTC,
                lastLineUTC,
                lineTimeInterval,
                wavelength,
                rangeSpacing,
                sourceImageWidth,
                srgrFlag,
                nearEdgeSlantRange,
                nearRangeOnLeft,
                orbit,
                srgrConvParams
        );
        sarPosition.setTileConstraints(0, 0, sourceImageWidth, sourceImageHeight);
        final SARPosition.PositionData posData = new SARPosition.PositionData();

        final List<String[]> csv = readCSVFile(crCSV);
        double sumXShift = 0.0;
        double sumYShift = 0.0;
        int count = 0;
        List<Double> xShifts = new java.util.ArrayList<>();
        List<Double> yShifts = new java.util.ArrayList<>();

        System.out.println("ID  CR_x  CR_y  exp_CR_x  exp_CR_y  xShift  yShift");
        for (String[] line : csv) {
            String id = line[0];
            // skip the header
            if (id.contains("ID")) {
                continue;
            }

            double lat = Double.parseDouble(line[1]);
            double lon = Double.parseDouble(line[2]);
            double alt = Double.parseDouble(line[3]);

            // compute the expected CR position in pixels
            GeoUtils.geo2xyzWGS84(lat, lon, alt, posData.earthPoint);
            if (!sarPosition.getPosition(posData))
                continue;

            // find peak position in image in the neighbourhood of the expected CR position
            final PixelPos imgCRPos = FindCRPosition.findCRPosition(posData.azimuthIndex, posData.rangeIndex, srcProduct);
            if (imgCRPos == null){
                continue;
            }

            // compute x and y shift in meters
            final double xShift = (posData.rangeIndex - imgCRPos.x) * rangeSpacing;
            final double yShift = (posData.azimuthIndex - imgCRPos.y) * azimuthSpacing;
//            final double xShift = (posData.rangeIndex - imgCRPos.x);
//            final double yShift = (posData.azimuthIndex - imgCRPos.y);
            sumXShift += xShift;
            sumYShift += yShift;
            count++;

            xShifts.add(xShift);
            yShifts.add(yShift);

            System.out.println(id + "  " + imgCRPos.x + "  " + imgCRPos.y + "  " + posData.rangeIndex
                    + "  " + posData.azimuthIndex + "  " + xShift + "  " + yShift);
        }
        final double meanXShift = sumXShift / count;
        final double meanYShift = sumYShift / count;
        System.out.println("# of CRs = " + count + ", meanXShift = " + meanXShift + ", meanYShift = " + meanYShift);

        Plot plot = new Plot("CR Geolocation Error " + testName);
        plot.addData(xShifts.stream().mapToDouble(Double::doubleValue).toArray(),
                yShifts.stream().mapToDouble(Double::doubleValue).toArray());
        plot.saveAsPng(tempFolder.getAbsolutePath() + "/CR_GeoLocation_Error" + testName + ".png");

    }
}
