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

import au.com.bytecode.opencsv.CSVReader;
import eu.esa.sar.commons.OrbitStateVectors;
import eu.esa.sar.commons.SARGeocoding;
import eu.esa.sar.commons.SARUtils;
import eu.esa.sar.commons.test.ProcessorTest;
import eu.esa.sar.insar.gpf.support.SARPosition;
import eu.esa.sar.teststacks.corner_reflectors.utils.Plot;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.GeoCoding;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.PinDescriptor;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Placemark;
import org.esa.snap.core.datamodel.PlacemarkDescriptor;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.TiePointGrid;
import org.esa.snap.core.gpf.main.GPT;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.OrbitStateVector;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.eo.GeoUtils;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.junit.Assert;
import org.slf4j.LoggerFactory;

import javax.media.jai.JAI;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.logging.Logger;

public abstract class BaseCRTest extends ProcessorTest {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(BaseCRTest.class);
    private final PlacemarkDescriptor pinDescriptor = PinDescriptor.getInstance();
    private final File baseFolder = new File("/tmp/corner_reflectors");
    protected final File tempFolder;
    protected String testName = "";
    private static final Logger LOG = Logger.getLogger("test");

    public static class GeoPoint {
        public final String id;
        public final double lat;
        public final double lon;
        public final double alt;

        public GeoPoint(String id, double lat, double lon, double alt) {
            this.id = id;
            this.lat = lat;
            this.lon = lon;
            this.alt = alt;
        }
    }


    protected BaseCRTest(String folder) {
        this.tempFolder = new File(baseFolder, folder);
        tempFolder.mkdirs();

        debugEnvironment();
    }

    protected void setName(String name) {
        this.testName = "_"+name;
    }

    protected void addCornerReflectorPins(String crCSV, Product trgProduct) throws IOException {
        final GeoPoint[] crPoints = readCRGeoPoints(crCSV);

        for (GeoPoint crPoint : crPoints) {
            // add a placemark at each corner reflector
            addPin(trgProduct, crPoint.id, crPoint.lat, crPoint.lon);
        }
    }

    protected void addPin(Product product, String id, double lat, double lon) {
        Placemark pin = Placemark.createPointPlacemark(pinDescriptor, id, id, "",
                null, new GeoPos(lat, lon), product.getSceneGeoCoding());
        product.getPinGroup().add(pin);
    }

    protected List<String[]> readCSVFile(String fileName) throws IOException {
        URL url = this.getClass().getResource(fileName);
        Assert.assertNotNull(url);
        try (FileReader reader = new FileReader(url.getPath())) {
            final CSVReader cvsReader = new CSVReader(reader);
            return cvsReader.readAll();
        }
    }

    protected void write(Product product) throws IOException {
        ProductIO.writeProduct(product,
                tempFolder.getAbsolutePath() +"/"+ product.getName()+testName+".dim",
                "BEAM-DIMAP");
    }

    protected double getGroundPixelSpacingInMeters(Product product) throws Exception {

        final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(product);
        final double azimuthSpacing = AbstractMetadata.getAttributeDouble(abs, AbstractMetadata.azimuth_spacing);
        double rangeSpacing = AbstractMetadata.getAttributeDouble(abs, AbstractMetadata.range_spacing);
        return Math.min(azimuthSpacing, rangeSpacing);
    }

    public static void debugEnvironment() {
        SystemUtils.init3rdPartyLibs(GPT.class);

        LOG.info("ApplicationDataDir: " + SystemUtils.getApplicationDataDir());
        LOG.info("ApplicationHomeDir: " + SystemUtils.getApplicationHomeDir());
        LOG.info("AuxDataPath: " + SystemUtils.getAuxDataPath());
        LOG.info("CacheDir: " + SystemUtils.getCacheDir());

        final File etcFolder = new File(SystemUtils.getApplicationDataDir(), "etc");
        etcFolder.mkdirs();

        final StringBuilder debugStr = new StringBuilder("Runtime: ");
        final Runtime runtime = Runtime.getRuntime();
        debugStr.append(" Processors: ").append(runtime.availableProcessors());
        debugStr.append(" Max memory: ").append(fromBytes(runtime.maxMemory()));

        debugStr.append(" Cache size: ").append(fromBytes(JAI.getDefaultInstance().getTileCache().getMemoryCapacity()));
        debugStr.append(" Tile parallelism: ").append(JAI.getDefaultInstance().getTileScheduler().getParallelism());
        debugStr.append(" SNAP Tile size: ").append((int) JAI.getDefaultTileSize().getWidth()).append(" x ").append((int) JAI.getDefaultTileSize().getHeight()).append(" pixels");
        LOG.info(debugStr.toString());

    }

    protected void computeCRGeoLocationError(String crCSV, Product srcProduct) throws Exception {

        GeoPoint[] crPoints = readCRGeoPoints(crCSV);

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(srcProduct);
        if (absRoot.getAttributeInt(AbstractMetadata.is_terrain_corrected) == 1) {
            computeCRGeoLocationErrorTC(crPoints, srcProduct);
        } else {
            computeCRGeoLocationErrorNonTC(crPoints, srcProduct);
        }
    }

    protected abstract GeoPoint[] readCRGeoPoints(String crCSV) throws IOException;

    private void computeCRGeoLocationErrorTC(GeoPoint[] crPoints, Product srcProduct) throws Exception {

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(srcProduct);
        final double rgSpacing = absRoot.getAttributeDouble(AbstractMetadata.range_spacing);
        final double azSpacing = absRoot.getAttributeDouble(AbstractMetadata.azimuth_spacing);
        final GeoCoding geoCoding = srcProduct.getSceneGeoCoding();
        PixelPos expCRPos = new PixelPos();

        System.out.println("ID  CR_x  CR_y  exp_CR_x  exp_CR_y  xShift  yShift");
        double sumXShift = 0.0;
        double sumYShift = 0.0;
        int count = 0;
        List<Double> xShifts = new java.util.ArrayList<>();
        List<Double> yShifts = new java.util.ArrayList<>();

        for (GeoPoint crPoint : crPoints) {
            // find peak position in image in the neighbourhood of true CR position
            geoCoding.getPixelPos(new GeoPos(crPoint.lat, crPoint.lon), expCRPos);
            final PixelPos imgCRPos = FindCRPosition.findCRPosition(tempFolder, expCRPos.y, expCRPos.x, srcProduct);
            if (imgCRPos == null){
                continue;
            }

            // compute x and y shift in meters
            final double xShift = (expCRPos.x - imgCRPos.x) * rgSpacing;
            final double yShift = (expCRPos.y - imgCRPos.y) * azSpacing;
            sumXShift += xShift;
            sumYShift += yShift;
            count++;

            xShifts.add(xShift);
            yShifts.add(yShift);

            System.out.println(crPoint.id + "  " + imgCRPos.x + "  " + imgCRPos.y + "  " + expCRPos.x
                    + "  " + expCRPos.y + "  " + xShift + "  " + yShift);
        }
        final double meanXShift = sumXShift / count;
        final double meanYShift = sumYShift / count;

        System.out.println("--------------------------------------------------");
        System.out.println("# of CRs = " + count + ", meanXShift = " + meanXShift + ", meanYShift = " + meanYShift);

        Plot plot = new Plot("CR Geolocation Error " + testName);
        plot.addData(xShifts.stream().mapToDouble(Double::doubleValue).toArray(),
                yShifts.stream().mapToDouble(Double::doubleValue).toArray());
        plot.saveAsPng(tempFolder.getAbsolutePath() + "/CR_GeoLocation_Error" + testName + ".png");
    }

    private void computeCRGeoLocationErrorNonTC(GeoPoint[] crPoints, Product srcProduct) throws Exception {

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

        double sumXShift = 0.0;
        double sumYShift = 0.0;
        int count = 0;
        List<Double> xShifts = new java.util.ArrayList<>();
        List<Double> yShifts = new java.util.ArrayList<>();

        System.out.println("ID  CR_x  CR_y  exp_CR_x  exp_CR_y  xShift  yShift");
        for (GeoPoint crPoint : crPoints) {
            // compute the expected CR position in pixels
            GeoUtils.geo2xyzWGS84(crPoint.lat, crPoint.lon, crPoint.alt, posData.earthPoint);
            if (!sarPosition.getPosition(posData))
                continue;

            // find peak position in image in the neighbourhood of the expected CR position
            final PixelPos imgCRPos = FindCRPosition.findCRPosition(tempFolder, posData.azimuthIndex, posData.rangeIndex, srcProduct);
            if (imgCRPos == null){
                continue;
            }

            // compute x and y shift in meters
            final double xShift = (posData.rangeIndex - imgCRPos.x) * rangeSpacing;
            final double yShift = (posData.azimuthIndex - imgCRPos.y) * azimuthSpacing;
            sumXShift += xShift;
            sumYShift += yShift;
            count++;

            xShifts.add(xShift);
            yShifts.add(yShift);

            System.out.println(crPoint.id + "  " + imgCRPos.x + "  " + imgCRPos.y + "  " + posData.rangeIndex
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




    public static final int K = 1024;
    public static final int M = K * 1024;
    public static final int G = M * 1024;

    public static String fromBytes(final long bytes) {
        if (bytes > G) {
            return String.format("%.1f GB", (double) bytes / G);
        } else if (bytes > M) {
            return String.format("%.1f MB", (double) bytes / M);
        } else if (bytes > K) {
            return String.format("%.1f KB", (double) bytes / K);
        }
        return String.format("%d B", bytes);
    }
}
