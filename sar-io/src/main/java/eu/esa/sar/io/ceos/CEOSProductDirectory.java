/*
 * Copyright (C) 2015 by Array Systems Computing Inc. http://www.array.ca
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
package eu.esa.sar.io.ceos;

import com.bc.ceres.core.VirtualDir;
import eu.esa.sar.commons.io.FileImageInputStreamExtImpl;
import eu.esa.sar.io.binary.BinaryFileReader;
import eu.esa.sar.io.binary.BinaryRecord;
import eu.esa.sar.io.binary.IllegalBinaryFormatException;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.util.Guardian;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;

import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.*;
import java.text.DateFormat;
import java.text.ParseException;
import java.util.*;

/**
 *
 */
public abstract class CEOSProductDirectory {

    protected CEOSConstants constants = null;
    protected VirtualDir productDir;
    protected CEOSVolumeDirectoryFile volumeDirectoryFile = null;
    protected boolean isProductSLC = false;
    protected String productType = null;
    protected int sceneWidth = 0;
    protected int sceneHeight = 0;

    public final DateFormat dateFormat = ProductData.UTC.createDateFormat("yyyy-DDD-HH:mm:ss");
    private final DateFormat standardDateFormat = ProductData.UTC.createDateFormat("yyyy-MM-dd HH:mm:ss");

    protected abstract void readProductDirectory() throws IOException, IllegalBinaryFormatException;

    public abstract Product createProduct() throws IOException;

    public abstract CEOSImageFile getImageFile(final Band band);

    public abstract void close() throws IOException;

    protected final void readVolumeDirectoryFileStream() throws IOException {
        Guardian.assertNotNull("productDir", productDir);
        Guardian.assertNotNull("constants", constants);

        final CeosFile[] volumeFile = getCEOSFile(constants.getVolumeFilePrefix());
        final BinaryFileReader binaryReader = new BinaryFileReader(volumeFile[0].imgInputStream);
        final String mission = constants.getMission();

        volumeDirectoryFile = createVolumeDirectoryFile(binaryReader, mission);
        volumeDirectoryFile.readFilePointersAndTextRecords(binaryReader, mission);

        binaryReader.close();

        productType = volumeDirectoryFile.getProductType();
        if (null == productType || productType.equals("unknown"))
            throw new IOException("Unable to read level 0 product");

        isProductSLC = productType.contains("SLC") || productType.contains("COMPLEX") || productType.contains("SLANT") || productType.contains("1.1");
    }

    private void readVolumeDiscriptor() throws IOException {
        final CeosFile[] volumeFile = getCEOSFile(constants.getVolumeFilePrefix());
        final BinaryFileReader binaryReader = new BinaryFileReader(volumeFile[0].imgInputStream);
        final String mission = constants.getMission();

        if (volumeDirectoryFile == null) {
            volumeDirectoryFile = createVolumeDirectoryFile(binaryReader, mission);
        }
        binaryReader.close();
    }

    protected CEOSVolumeDirectoryFile createVolumeDirectoryFile(final BinaryFileReader binaryReader, final String mission) throws IOException {
        return new CEOSVolumeDirectoryFile(binaryReader, mission);
    }

    public boolean isSLC() {
        return isProductSLC;
    }

    protected String getSampleType() {
        if (isProductSLC)
            return "COMPLEX";
        else
            return "DETECTED";
    }

    protected final String getVolumeId() throws IOException {
        if (volumeDirectoryFile == null)
            readVolumeDiscriptor();
        return volumeDirectoryFile.getVolumeDescriptorRecord().getAttributeString("Volume set ID");
    }

    protected final String getLogicalVolumeId() throws IOException {
        if (volumeDirectoryFile == null)
            readVolumeDiscriptor();
        return volumeDirectoryFile.getVolumeDescriptorRecord().getAttributeString("Logical volume ID");
    }

    protected String getProductType() {
        return productType;
    }

    protected static void addTiePointGrids(final Product product, final BinaryRecord facility, final BinaryRecord scene) {
        try {
            final int gridWidth = 11;
            final int gridHeight = 11;

            final float subSamplingX = (float) product.getSceneRasterWidth() / (float) (gridWidth - 1);
            final float subSamplingY = (float) product.getSceneRasterHeight() / (float) (gridHeight - 1);

            // add incidence angle tie point grid
            if (facility != null) {

                final double angle1 = facility.getAttributeDouble("Incidence angle at first range pixel");
                final double angle2 = facility.getAttributeDouble("Incidence angle at centre range pixel");
                final double angle3 = facility.getAttributeDouble("Incidence angle at last valid range pixel");

                final float[] angles = new float[]{(float) angle1, (float) angle2, (float) angle3};
                final float[] fineAngles = new float[gridWidth * gridHeight];

                ReaderUtils.createFineTiePointGrid(3, 1, gridWidth, gridHeight, angles, fineAngles);

                final TiePointGrid incidentAngleGrid = new TiePointGrid(OperatorUtils.TPG_INCIDENT_ANGLE, gridWidth, gridHeight, 0, 0,
                        subSamplingX, subSamplingY, fineAngles);
                incidentAngleGrid.setUnit(Unit.DEGREES);

                product.addTiePointGrid(incidentAngleGrid);
            }
            // add slant range time tie point grid
            if (scene != null) {

                final double time1 = scene.getAttributeDouble("Zero-doppler range time of first range pixel") * Constants.oneMillion; // ms to ns
                final double time2 = scene.getAttributeDouble("Zero-doppler range time of centre range pixel") * Constants.oneMillion; // ms to ns
                final double time3 = scene.getAttributeDouble("Zero-doppler range time of last range pixel") * Constants.oneMillion; // ms to ns

                final float[] times = new float[]{(float) time1, (float) time2, (float) time3};
                final float[] fineTimes = new float[gridWidth * gridHeight];

                ReaderUtils.createFineTiePointGrid(3, 1, gridWidth, gridHeight, times, fineTimes);

                final TiePointGrid slantRangeTimeGrid = new TiePointGrid(OperatorUtils.TPG_SLANT_RANGE_TIME, gridWidth, gridHeight, 0, 0,
                        subSamplingX, subSamplingY, fineTimes);
                slantRangeTimeGrid.setUnit(Unit.NANOSECONDS);

                product.addTiePointGrid(slantRangeTimeGrid);
            }
        } catch (Exception e) {
            //continue
        }
    }

    protected Band createBand(final Product product, String name, final String unit, final int bitsPerSample) {

        int dataType = ProductData.TYPE_UINT16;
        if (bitsPerSample == 16) {
            dataType = isProductSLC ? ProductData.TYPE_INT16 : ProductData.TYPE_UINT16;
        } else if (bitsPerSample == 32) {
            dataType = ProductData.TYPE_FLOAT32;
        } else if (bitsPerSample == 8) {
            dataType = isProductSLC ? ProductData.TYPE_INT8 : ProductData.TYPE_UINT8;
        }
        if (product.getBand(name) != null) {
            int cnt = 0;
            for (String bandName : product.getBandNames()) {
                if (bandName.startsWith(name)) {
                    ++cnt;
                }
            }
            name += "_" + cnt;
        }

        final Band band = new Band(name, dataType, sceneWidth, sceneHeight);
        band.setDescription(name);
        band.setUnit(unit);
        band.setNoDataValue(0);
        band.setNoDataValueUsed(true);
        product.addBand(band);

        return band;
    }

    protected static ProductData.UTC getProcTime(BinaryRecord volDescRec) {
        try {
            final String procDate = volDescRec.getAttributeString("Logical volume preparation date").trim();
            final String procTime = volDescRec.getAttributeString("Logical volume preparation time").trim();

            return ProductData.UTC.parse(procDate + procTime, "yyyyMMddHHmmss");
        } catch (ParseException e) {
            System.out.println(e.toString());
            return AbstractMetadata.NO_METADATA_UTC;
        }
    }

    protected static String getPass(final BinaryRecord mapProjRec, final BinaryRecord sceneRec) {
        if (mapProjRec != null) {
            Double heading = mapProjRec.getAttributeDouble("Platform heading at nadir corresponding to scene centre");
            if (heading != null) {
                if (heading < 0.0)
                    heading += 360.0;

                if (heading > 90 && heading < 270)
                    return "DESCENDING";
                else
                    return "ASCENDING";
            }
        }
        if (sceneRec != null) {
            String pass = sceneRec.getAttributeString("Ascending or Descending flag");
            if (pass == null) {
                pass = sceneRec.getAttributeString("Time direction indicator along line direction");
            }
            if (pass != null) {
                if (pass.toUpperCase().trim().startsWith("DESC"))
                    return "DESCENDING";
                else
                    return "ASCENDING";
            }
        }
        return " ";
    }

    protected ProductData.UTC getUTCScanStartTime(final BinaryRecord sceneRec, final BinaryRecord detailProcRec) {
        if (sceneRec != null) {
            final String startTime = sceneRec.getAttributeString("Zero-doppler azimuth time of first azimuth pixel");
            if (startTime != null && !startTime.trim().isEmpty()) {
                return AbstractMetadata.parseUTC(startTime);
            }
        }
        if (detailProcRec != null) {
            final String startTime = detailProcRec.getAttributeString("Processing start time");
            if (startTime != null && !startTime.trim().isEmpty()) {
                return AbstractMetadata.parseUTC(startTime, dateFormat);
            }
        }
        return AbstractMetadata.NO_METADATA_UTC;
    }

    protected ProductData.UTC getUTCScanStopTime(final BinaryRecord sceneRec, final BinaryRecord detailProcRec) {
        if (sceneRec != null) {
            final String endTime = sceneRec.getAttributeString("Zero-doppler azimuth time of last azimuth pixel");
            if (endTime != null)
                return AbstractMetadata.parseUTC(endTime);
        }
        if (detailProcRec != null) {
            final String endTime = detailProcRec.getAttributeString("Processing stop time");
            if (endTime != null)
                return AbstractMetadata.parseUTC(endTime, dateFormat);
        }
        return AbstractMetadata.NO_METADATA_UTC;
    }

    protected static void addSummaryMetadata(final InputStream summaryStream, final String name, final MetadataElement parent)
            throws IOException {
        if (summaryStream == null)
            return;

        final MetadataElement summaryMetadata = new MetadataElement(name);
        final Properties properties = new Properties();

        properties.load(summaryStream);
        final Set unsortedEntries = properties.entrySet();
        final TreeSet sortedEntries = new TreeSet(new Comparator() {
            public int compare(final Object a, final Object b) {
                final Map.Entry entryA = (Map.Entry) a;
                final Map.Entry entryB = (Map.Entry) b;
                return ((String) entryA.getKey()).compareTo((String) entryB.getKey());
            }
        });
        sortedEntries.addAll(unsortedEntries);
        for (Object sortedEntry : sortedEntries) {
            final Map.Entry entry = (Map.Entry) sortedEntry;
            final String data = ((String) entry.getValue()).trim();
            // strip of double quotes
            String strippedData = "";
            if (data.length() > 2)
                strippedData = data.substring(1, data.length() - 1);
            final MetadataAttribute attribute = new MetadataAttribute((String) entry.getKey(),
                    new ProductData.ASCII(strippedData), true);
            summaryMetadata.addAttribute(attribute);
        }

        parent.addElement(summaryMetadata);
    }

    protected static void assertSameWidthAndHeightForAllImages(final CEOSImageFile[] imageFiles,
                                                               final int width, final int height) {
        for (int i = 0; i < imageFiles.length; i++) {
            final CEOSImageFile imageFile = imageFiles[i];
            Guardian.assertTrue("_sceneWidth == imageFile[" + i + "].getRasterWidth()",
                    width == imageFile.getRasterWidth());
            Guardian.assertTrue("_sceneHeight == imageFile[" + i + "].getRasterHeight()",
                    height == imageFile.getRasterHeight());
        }
    }

    protected static double getRadarFrequency(BinaryRecord sceneRec) {
        final double wavelength = sceneRec.getAttributeDouble("Radar wavelength");
        return (Constants.lightSpeed / wavelength) / Constants.oneMillion; // MHz
    }

    protected int isGroundRange(final BinaryRecord mapProjRec) {
        if (mapProjRec != null) {
            final String projDesc = mapProjRec.getAttributeString("Map projection descriptor").toLowerCase();
            if (projDesc.contains("slant"))
                return 0;
            return 1;
        }
        return isProductSLC ? 0 : 1;
    }

    protected void addOrbitStateVectors(final MetadataElement absRoot, final BinaryRecord platformPosRec) {
        if (platformPosRec == null) return;

        final MetadataElement orbitVectorListElem = absRoot.getElement(AbstractMetadata.orbit_state_vectors);
        final int numPoints = platformPosRec.getAttributeInt("Number of data points");

        for (int i = 1; i <= numPoints; ++i) {
            addVector(AbstractMetadata.orbit_vector, orbitVectorListElem, platformPosRec, i);
        }

        if (absRoot.getAttributeUTC(AbstractMetadata.STATE_VECTOR_TIME, AbstractMetadata.NO_METADATA_UTC).
                equalElems(AbstractMetadata.NO_METADATA_UTC)) {

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.STATE_VECTOR_TIME,
                    getOrbitTime(platformPosRec, 1));
        }
    }

    private void addVector(String name, MetadataElement orbitVectorListElem,
                           BinaryRecord platformPosRec, int num) {
        final MetadataElement orbitVectorElem = new MetadataElement(name + num);

        orbitVectorElem.setAttributeUTC(AbstractMetadata.orbit_vector_time, getOrbitTime(platformPosRec, num));
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_x_pos,
                platformPosRec.getAttributeDouble("Position vector X " + num));
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_y_pos,
                platformPosRec.getAttributeDouble("Position vector Y " + num));
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_z_pos,
                platformPosRec.getAttributeDouble("Position vector Z " + num));
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_x_vel,
                platformPosRec.getAttributeDouble("Velocity vector X' " + num));
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_y_vel,
                platformPosRec.getAttributeDouble("Velocity vector Y' " + num));
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_z_vel,
                platformPosRec.getAttributeDouble("Velocity vector Z' " + num));

        orbitVectorListElem.addElement(orbitVectorElem);
    }

    protected ProductData.UTC getOrbitTime(BinaryRecord platformPosRec, int num) {
        final int year = platformPosRec.getAttributeInt("Year of data point");
        final int month = platformPosRec.getAttributeInt("Month of data point");
        final int day = platformPosRec.getAttributeInt("Day of data point");
        Double secondsOfDay = platformPosRec.getAttributeDouble("Seconds of day");
        if (secondsOfDay == null)
            secondsOfDay = 0.0;
        final double hoursf = secondsOfDay / 3600f;
        final int hour = (int) hoursf;
        final double minutesf = (hoursf - hour) * 60f;
        final int minute = (int) minutesf;
        float second = ((float) minutesf - minute) * 60f;

        Double interval = platformPosRec.getAttributeDouble("Time interval between DATA points");
        if (interval == null || interval <= 0.0) {
            SystemUtils.LOG.info("CEOSProductDirectory: Time interval between DATA points in Platform Position Data is " + interval);
            interval = 0.0;
        }
        second += interval * (num - 1);

        return AbstractMetadata.parseUTC(String.valueOf(year) + '-' + month + '-' + day + ' ' +
                hour + ':' + minute + ':' + second, standardDateFormat);
    }

    protected static void addSRGRCoefficients(final MetadataElement absRoot, final BinaryRecord facilityRec) {
        if (facilityRec == null) return;

        final MetadataElement srgrCoefficientsElem = absRoot.getElement(AbstractMetadata.srgr_coefficients);

        final MetadataElement srgrListElem = new MetadataElement(AbstractMetadata.srgr_coef_list);
        srgrCoefficientsElem.addElement(srgrListElem);

        final ProductData.UTC utcTime = absRoot.getAttributeUTC(AbstractMetadata.first_line_time, AbstractMetadata.NO_METADATA_UTC);
        srgrListElem.setAttributeUTC(AbstractMetadata.srgr_coef_time, utcTime);
        AbstractMetadata.addAbstractedAttribute(srgrListElem, AbstractMetadata.ground_range_origin,
                ProductData.TYPE_FLOAT64, "m", "Ground Range Origin");
        AbstractMetadata.setAttribute(srgrListElem, AbstractMetadata.ground_range_origin, 0.0);

        addSRGRCoef(srgrListElem, facilityRec,
                "coefficients of the ground range to slant range conversion polynomial 1", 1);
        addSRGRCoef(srgrListElem, facilityRec,
                "coefficients of the ground range to slant range conversion polynomial 2", 2);
        addSRGRCoef(srgrListElem, facilityRec,
                "coefficients of the ground range to slant range conversion polynomial 3", 3);
        addSRGRCoef(srgrListElem, facilityRec,
                "coefficients of the ground range to slant range conversion polynomial 4", 4);
    }

    protected static void addSRGRCoef(final MetadataElement srgrListElem, final BinaryRecord rec, final String tag, int cnt) {
        final MetadataElement coefElem = new MetadataElement(AbstractMetadata.coefficient + '.' + cnt);
        srgrListElem.addElement(coefElem);

        AbstractMetadata.addAbstractedAttribute(coefElem, AbstractMetadata.srgr_coef,
                ProductData.TYPE_FLOAT64, "", "SRGR Coefficient");
        AbstractMetadata.setAttribute(coefElem, AbstractMetadata.srgr_coef, rec.getAttributeDouble(tag));
    }

    protected static void addDopplerCentroidCoefficients(final MetadataElement absRoot, final BinaryRecord sceneRec) {
        if (sceneRec == null) return;

        final MetadataElement dopCoefficientsElem = absRoot.getElement(AbstractMetadata.dop_coefficients);

        final MetadataElement dopListElem = new MetadataElement(AbstractMetadata.dop_coef_list);
        dopCoefficientsElem.addElement(dopListElem);

        final ProductData.UTC utcTime = absRoot.getAttributeUTC(AbstractMetadata.first_line_time, AbstractMetadata.NO_METADATA_UTC);
        dopListElem.setAttributeUTC(AbstractMetadata.dop_coef_time, utcTime);
        AbstractMetadata.addAbstractedAttribute(dopListElem, AbstractMetadata.slant_range_time,
                ProductData.TYPE_FLOAT64, "ns", "Slant Range Time");
        AbstractMetadata.setAttribute(dopListElem, AbstractMetadata.slant_range_time, 0.0);

        addDopCoef(dopListElem, sceneRec, "Cross track Doppler frequency centroid constant term", 1);
        addDopCoef(dopListElem, sceneRec, "Cross track Doppler frequency centroid linear term", 2);
        addDopCoef(dopListElem, sceneRec, "Cross track Doppler frequency centroid quadratic term", 3);
    }

    private static void addDopCoef(final MetadataElement dopListElem, final BinaryRecord rec, final String tag, int cnt) {
        final MetadataElement coefElem = new MetadataElement(AbstractMetadata.coefficient + '.' + cnt);
        dopListElem.addElement(coefElem);

        AbstractMetadata.addAbstractedAttribute(coefElem, AbstractMetadata.dop_coef, ProductData.TYPE_FLOAT64, "", tag);
        AbstractMetadata.setAttribute(coefElem, AbstractMetadata.dop_coef, rec.getAttributeDouble(tag));
    }

    protected InputStream findFile(final String fileName) throws IOException {
        try {
            if (productDir.isCompressed()) {
                String folder = "";
                String[] fileList = productDir.list("");
                while (fileList.length > 0 && fileList.length <= 3) {
                    folder += fileList[0] + '/';
                    fileList = productDir.list(folder);
                }
                if (!folder.isEmpty() && !folder.endsWith("/")) {
                    folder += "/";
                }
                return productDir.getInputStream(folder + fileName);
            } else {
                return new FileInputStream(new File(productDir.getBasePath(), fileName));
            }
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    protected CeosFile[] getCEOSFile(final String[] prefixList) throws IOException {
        final List<CeosFile> list = new ArrayList<>(4);
        String folder = "";
        String[] fileList = productDir.list("");
        while (fileList.length > 0 && fileList.length <= 3) {
            folder += fileList[0] + '/';
            fileList = productDir.list(folder);
        }
        if (!folder.isEmpty() && !folder.endsWith("/")) {
            folder += "/";
        }
        for (int i = 0; i < fileList.length; i++) {
            String name = fileList[i].toUpperCase();
            if ((name.startsWith("AL1_")) && name.endsWith(".CEOS")) {
                folder = fileList[i] + '/';
                fileList = productDir.list(folder);
                break;
            }
        }
        for (String name : fileList) {
            String nameUp = name.toUpperCase();
            for (String prefix : prefixList) {
                if (nameUp.startsWith(prefix) || nameUp.endsWith('.' + prefix)) {
                    try {
                        ImageInputStream stream;
                        if (productDir.isCompressed()) {
                            stream = new MemoryCacheImageInputStream(productDir.getInputStream(folder + name));
                        } else {
                            stream = new FileImageInputStreamExtImpl(productDir.getFile(folder + name));
                        }
                        list.add(new CeosFile(stream, name));
                    } catch (Exception e) {
                        SystemUtils.LOG.info(folder + name + " not found");
                        return null;
                    }
                }
            }
        }
        return list.toArray(new CeosFile[0]);
    }

    public static class CeosFile {
        public ImageInputStream imgInputStream;
        public String fileName;

        public CeosFile(ImageInputStream imgInputStream, String fileName) {
            this.imgInputStream = imgInputStream;
            this.fileName = fileName;
        }
    }
}
