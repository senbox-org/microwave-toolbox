/*
 * Copyright (C) 2025 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.io.nisar.subreaders;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.product.Missions;
import eu.esa.sar.io.netcdf.NcRasterDim;
import eu.esa.sar.io.netcdf.NetCDFReader;
import eu.esa.sar.io.netcdf.NetCDFUtils;
import eu.esa.sar.io.netcdf.NetcdfConstants;
import eu.esa.sar.io.nisar.util.NisarXConstants;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import ucar.ma2.Array;
import ucar.ma2.DataType;
import ucar.ma2.StructureData;
import ucar.ma2.StructureMembers;
import ucar.nc2.Group;
import ucar.nc2.NetcdfFile;
import ucar.nc2.Variable;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ucar.ma2.DataType.STRUCTURE;

public abstract class NisarSubReader {

    protected final Map<Band, Variable> bandMap = new HashMap<>();
    protected final DateFormat standardDateFormat = ProductData.UTC.createDateFormat("yyyy-MM-dd HH:mm:ss");
    protected NetcdfFile netcdfFile = null;
    protected Product product = null;
    protected String productType;
    protected boolean isComplex = true;

    public void close() throws IOException {
        if (netcdfFile != null) {
            netcdfFile.close();
            netcdfFile = null;
        }
    }

    /**
     * Provides an implementation of the <code>readProductNodes</code> interface method. Clients implementing this
     * method can be sure that the input object and eventually the subset information has already been set.
     * <p/>
     * <p>This method is called as a last step in the <code>readProductNodes(input, subsetInfo)</code> method.
     */
    public Product readProduct(final ProductReader reader, final NetcdfFile netcdfFile, final File inputFile) {
        this.netcdfFile = netcdfFile;

        try {
            final Group groupLSAR = getLSARGroup();
            final Group groupID = getIndenificationGroup(groupLSAR);
            final Group groupFrequencyA = getFrequencyAGroup(groupLSAR);

            Variable[] rasterVariables = getRasterVariables(groupFrequencyA);

            final int rasterHeight = rasterVariables[0].getDimension(0).getLength();
            final int rasterWidth = rasterVariables[0].getDimension(1).getLength();
            productType = getProductType(groupID);

            product = new Product(inputFile.getName(),
                    productType,
                    rasterWidth, rasterHeight,
                    reader);
            product.setFileLocation(inputFile);

            addMetadataToProduct();
            addBandsToProduct();
            addTiePointGridsToProduct(groupFrequencyA);
            addGeoCodingToProduct(rasterVariables);
            addDopplerMetadata();

            return product;
        } catch (Exception e) {
            SystemUtils.LOG.severe(e.getMessage());
            return null;
        }
    }

    protected Group getLSARGroup() {
        final Group groupScience = this.netcdfFile.getRootGroup().findGroup("science");
        return groupScience.findGroup("LSAR");
    }

    protected Group getIndenificationGroup(final Group groupLSAR) {
        return groupLSAR.findGroup("identification");
    }

    protected Group getFrequencyAGroup(final Group groupLSAR) {
        final Group groupProductType = groupLSAR.findGroup(productType);
        final Group groupSwaths = groupProductType.findGroup("swaths");
        return groupSwaths.findGroup("frequencyA");
    }

    protected Group getMetadataGroup(final Group groupLSAR) {
        final Group groupProductType = groupLSAR.findGroup(productType);
        return groupProductType.findGroup("metadata");
    }

    protected Group[] getPolarizationGroups(final Group group) {
        List<Group> polGroups = new ArrayList<>();
        final Group groupHH = group.findGroup("HH");
        polGroups.add(groupHH);

        return polGroups.toArray(new Group[0]);
    }

    protected abstract Variable[] getRasterVariables(final Group group);

    protected String getProductType(Group groupID) throws Exception {
        return groupID.findVariable(NisarXConstants.PRODUCT_TYPE).readScalarString();
    }

    protected String getDescription(String filename, Group groupID) throws Exception {
        final String productType = getProductType(groupID);
        final Variable missionID = groupID.findVariable(NisarXConstants.MISSION);
        String description =  filename + " - " + productType;
        if(missionID != null) {
            description += " - " + missionID.readScalarString();
        }
        return description;
    }

    protected abstract void addBandsToProduct();

    protected void addMetadataToProduct() throws Exception{

        final MetadataElement origMetadataRoot = AbstractMetadata.addOriginalProductMetadata(product.getMetadataRoot());
        NetCDFUtils.addAttributes(origMetadataRoot, NetcdfConstants.GLOBAL_ATTRIBUTES_NAME,
                netcdfFile.getGlobalAttributes());

        for (Variable variable : netcdfFile.getVariables()) {
            NetCDFUtils.addVariableMetadata(origMetadataRoot, variable, 5000);
        }

        addAbstractedMetadataHeader(product.getMetadataRoot());
    }

    protected void addAbstractedMetadataHeader(final MetadataElement root) throws Exception {

        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(root);
        final MetadataElement origMeta = AbstractMetadata.getOriginalProductMetadata(product);

        MetadataElement globals = origMeta.getElement("Global_Attributes");
        MetadataElement science = origMeta.getElement("science");
        MetadataElement lsar = science.getElement("LSAR");
        MetadataElement identification = lsar.getElement("identification");

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PRODUCT, product.getName());
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.MISSION, Missions.NISAR);

        String title = globals.getAttributeString("title");
        product.setDescription(title);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SPH_DESCRIPTOR, title);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PRODUCT_TYPE, productType);

        MetadataElement lookDirection = identification.getElement("lookDirection");
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.antenna_pointing,
                lookDirection.getAttributeString("lookDirection").toLowerCase());

        MetadataElement trackNumber = identification.getElement("trackNumber");
        if(trackNumber != null) {
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.REL_ORBIT,
                    trackNumber.getAttributeInt("trackNumber"));
        }

        MetadataElement absoluteOrbitNumber = identification.getElement("absoluteOrbitNumber");
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ABS_ORBIT,
            absoluteOrbitNumber.getAttributeInt("absoluteOrbitNumber"));

        MetadataElement orbitPassDirection = identification.getElement("orbitPassDirection");
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PASS,
                getOrbitPass(orbitPassDirection.getAttributeString("orbitPassDirection")));

        MetadataElement plannedDataTakeId = identification.getElement("plannedDataTakeId");
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.data_take_id,
                plannedDataTakeId.getAttributeInt("plannedDataTakeId"));

        MetadataElement processingDataTime = identification.getElement("processingDataTime");
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PROC_TIME,
                ReaderUtils.getTime(processingDataTime, "processingDataTime", standardDateFormat));

        MetadataElement zeroDopplerStartTime = identification.getElement("zeroDopplerStartTime");
        ProductData.UTC startTime = ReaderUtils.getTime(zeroDopplerStartTime, "zeroDopplerStartTime", standardDateFormat);
        product.setStartTime(startTime);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_line_time, startTime);

        MetadataElement zeroDopplerEndTime = identification.getElement("zeroDopplerEndTime");
        ProductData.UTC endTime = ReaderUtils.getTime(zeroDopplerEndTime, "zeroDopplerEndTime", standardDateFormat);
        product.setEndTime(endTime);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_line_time, endTime);

        addSubReaderMetadata(absRoot, lsar);


    }

    protected void addSubReaderMetadata(final MetadataElement absRoot, final MetadataElement lsar) throws Exception {
        MetadataElement productMeta = lsar.getElement(productType);
        MetadataElement metadata = productMeta.getElement("metadata");
        MetadataElement swaths = productMeta.getElement("swaths");

        ////            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ACQUISITION_MODE,
        ////                    netcdfFile.getRootGroup().findVariable(NisarXConstants.ACQUISITION_MODE).readScalarString());
//
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.BEAMS, NisarXConstants.BEAMS_DEFAULT_VALUE);
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PROC_TIME, ProductData.UTC.parse(
//                    groupID.findVariable(NisarXConstants.PROC_TIME_UTC).readScalarString().substring(0, 22),
//                    standardDateFormat));
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ProcessingSystemIdentifier,
//                    NisarXConstants.NISAR_PROCESSOR_NAME_PREFIX +
//                            groupID.findVariable(NisarXConstants.PROCESSING_SYSTEM_IDENTIFIER).readScalarString());
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.CYCLE, 99999);
//
////            double localIncidenceAngle = groupGeolocationGrid.findVariable("incidenceAngle").readScalarFloat();
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.incidence_near, localIncidenceAngle);
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.incidence_far,  localIncidenceAngle);

//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.slice_num,
//                    NisarXConstants.SLICE_NUM_DEFAULT_VALUE);
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.geo_ref_system,
//                    NisarXConstants.GEO_REFERENCE_SYSTEM_DEFAULT_VALUE);
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_line_time, ProductData.UTC.parse(
//                    groupID.findVariable(NisarXConstants.ACQUISITION_START_UTC).readScalarString().substring(0, 22),
//                    standardDateFormat));
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_line_time, ProductData.UTC.parse(
//                    groupID.findVariable(NisarXConstants.ACQUISITION_END_UTC).readScalarString().substring(0, 22),
//                    standardDateFormat));

//            double[] firstNear = (double[]) netcdfFile.getRootGroup().findVariable(
//                    NisarXConstants.FIRST_NEAR).read().getStorage();
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_near_lat, firstNear[2]);
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_near_long, firstNear[3]);
//            double[] firstFar = (double[]) netcdfFile.getRootGroup().findVariable(
//                    NisarXConstants.FIRST_FAR).read().getStorage();
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_far_lat, firstFar[2]);
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_far_long, firstFar[3]);
//
//            double[] lastNear = (double[]) netcdfFile.getRootGroup().findVariable(
//                    NisarXConstants.LAST_NEAR).read().getStorage();
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_near_lat, lastNear[2]);
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_near_long, lastNear[3]);
//
//            double[] lastFar = (double[]) netcdfFile.getRootGroup().findVariable(
//                    NisarXConstants.LAST_FAR).read().getStorage();
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_far_lat, lastFar[2]);
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_far_long, lastFar[3]);
//

//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SAMPLE_TYPE, getSampleType());
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.mds1_tx_rx_polar,
//                    netcdfFile.getRootGroup().findVariable(NisarXConstants.MDS1_TX_RX_POLAR).readScalarString());
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_looks,
//                    netcdfFile.getRootGroup().findVariable(NisarXConstants.AZIMUTH_LOOKS).readScalarFloat());
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_looks,
//                    netcdfFile.getRootGroup().findVariable(NisarXConstants.RANGE_LOOKS).readScalarFloat());
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_spacing,
//                    netcdfFile.getRootGroup().findVariable(NisarXConstants.SLANT_RANGE_SPACING).readScalarFloat());
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_spacing,
//                    netcdfFile.getRootGroup().findVariable(NisarXConstants.AZIMUTH_GROUND_SPACING).readScalarFloat());
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.pulse_repetition_frequency,
//                    netcdfFile.getRootGroup().findVariable(NisarXConstants.PULSE_REPETITION_FREQUENCY).readScalarFloat());
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.radar_frequency,
//                    netcdfFile.getRootGroup().findVariable(NisarXConstants.RADAR_FREQUENCY).readScalarDouble()
//                            / Constants.oneMillion);
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.line_time_interval,
//                    netcdfFile.getRootGroup().findVariable(NisarXConstants.LINE_TIME_INTERVAL).readScalarDouble());
//
//            final int rasterWidth = netcdfFile.getRootGroup().findVariable(
//                    NisarXConstants.NUM_SAMPLES_PER_LINE).readScalarInt();
//
//            final int rasterHeight = netcdfFile.getRootGroup().findVariable(
//                    NisarXConstants.NUM_OUTPUT_LINES).readScalarInt();
//
//            double totalSize = (rasterHeight * rasterWidth * 2 * 2) / (1024.0f * 1024.0f);
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.TOT_SIZE, totalSize);
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.num_output_lines,
//                    netcdfFile.getRootGroup().findVariable(NisarXConstants.NUM_OUTPUT_LINES).readScalarInt());
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.num_samples_per_line,
//                    netcdfFile.getRootGroup().findVariable(NisarXConstants.NUM_SAMPLES_PER_LINE).readScalarInt());
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.subset_offset_x,
//                    NisarXConstants.SUBSET_OFFSET_X_DEFAULT_VALUE);
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.subset_offset_y,
//                    NisarXConstants.SUBSET_OFFSET_Y_DEFAULT_VALUE);
//
//            if (isComplex) {
//                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.srgr_flag, 0);
//            } else {
//                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.srgr_flag, 1);
//            }
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.avg_scene_height,
//                    netcdfFile.getRootGroup().findVariable(NisarXConstants.AVG_SCENE_HEIGHT).readScalarDouble());
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.lat_pixel_res,
//                    NisarXConstants.LAT_PIXEL_RES_DEFAULT_VALUE);
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.lon_pixel_res,
//                    NisarXConstants.LON_PIXEL_RES_DEFAULT_VALUE);
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.slant_range_to_first_pixel,
//                    (netcdfFile.getRootGroup().findVariable(
//                            NisarXConstants.FIRST_PIXEL_TIME).readScalarDouble() / 2) * 299792458.0);
//
//            int antElevCorrFlag = NisarXConstants.ANT_ELEV_CORR_FLAG_DEFAULT_VALUE;
//            if (netcdfFile.getRootGroup().findVariable(NisarXConstants.ANT_ELEV_CORR_FLAG) != null) {
//                antElevCorrFlag = netcdfFile.getRootGroup().findVariable(
//                        NisarXConstants.ANT_ELEV_CORR_FLAG).readScalarInt();
//            }
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ant_elev_corr_flag, antElevCorrFlag);
//
//            int rangeSpreadCompFlag = NisarXConstants.RANGE_SPREAD_COMP_FLAG_DEFAULT_VALUE;
//            if (netcdfFile.getRootGroup().findVariable(NisarXConstants.RANGE_SPREAD_COMP_FLAG) != null) {
//                rangeSpreadCompFlag = netcdfFile.getRootGroup().findVariable(
//                        NisarXConstants.RANGE_SPREAD_COMP_FLAG).readScalarInt();
//            }
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_spread_comp_flag, rangeSpreadCompFlag);
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.replica_power_corr_flag,
//                    NisarXConstants.REPLICA_POWER_CORR_FLAG_DEFAULT_VALUE);
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.abs_calibration_flag,
//                    NisarXConstants.ABS_CALIBRATION_FLAG_DEFAULT_VALUE);
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.calibration_factor,
//                    netcdfFile.getRootGroup().findVariable(NisarXConstants.CALIBRATION_FACTOR).readScalarDouble());
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.inc_angle_comp_flag,
//                    NisarXConstants.INC_ANGLE_COMP_FLAG_DEFAULT_VALUE);
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ref_inc_angle,
//                    NisarXConstants.REF_INC_ANGLE_DEFAULT_VALUE);
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ref_slant_range,
//                    NisarXConstants.REF_SLANT_RANGE_DEFAULT_VALUE);
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ref_slant_range_exp,
//                    NisarXConstants.REF_SLANT_RANGE_EXP_DEFAULT_VALUE);
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.rescaling_factor,
//                    NisarXConstants.RESCALING_FACTOR_DEFAULT_VALUE);
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_sampling_rate,
//                    netcdfFile.getRootGroup().findVariable(NisarXConstants.RANGE_SAMPLING_RATE).readScalarDouble() / 1e6);
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_bandwidth,
//                    netcdfFile.getRootGroup().findVariable(NisarXConstants.RANGE_BANDWIDTH).readScalarDouble() / 1e6);
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_bandwidth,
//                    netcdfFile.getRootGroup().findVariable(NisarXConstants.AZIMUTH_BANDWIDTH).readScalarDouble());
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.multilook_flag,
//                    NisarXConstants.MULTI_LOOK_FLAG_DEFAULT_VALUE);
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.coregistered_stack,
//                    NisarXConstants.CO_REGISTERED_STACK_DEFAULT_VALUE);
//
//            addOrbitStateVectors(absRoot);
    }


    protected void addTiePointGridsToProduct(final Group groupFrequencyA) throws IOException {

//        final int rank = variable.getRank();
//        final int gridWidth = variable.getDimension(rank - 1).getLength();
//        int gridHeight = variable.getDimension(rank - 2).getLength();
//        if (rank >= 3 && gridHeight <= 1)
//            gridHeight = variable.getDimension(rank - 3).getLength();
//        final TiePointGrid tpg = NetCDFUtils.createTiePointGrid(variable, gridWidth, gridHeight,
//                product.getSceneRasterWidth(), product.getSceneRasterHeight());

//        product.addTiePointGrid(tpg);

        final MetadataElement bandElem = getBandElement(product.getBandAt(0));
        addIncidenceAnglesSlantRangeTime(product, bandElem);
        addGeocodingFromMetadata(product, bandElem);


        Variable coordXVar = netcdfFile.findVariable("/science/LSAR/RSLC/metadata/geolocationGrid/coordinateX");
        Variable coordYVar = netcdfFile.findVariable("science/LSAR/RSLC/metadata/geolocationGrid/coordinateY");

        // Check if the variables were found
        if (coordXVar == null) {
            System.err.println("Error: Could not find variable '" + "'");
            System.err.println("Check the file path and the product specification.");
            // Optional: Print file structure to help debug
            // System.out.println("\nFile Structure:\n" + ncfile);
            return;
        }
        if (coordYVar == null) {
            System.err.println("Error: Could not find variable '" + "'");
            System.err.println("Check the file path and the product specification.");
            // Optional: Print file structure to help debug
            // System.out.println("\nFile Structure:\n" + ncfile);
            return;
        }

        System.out.println("Found variable: " + coordXVar.getFullName() + " (Type: " + coordXVar.getDataType() + ")");
        System.out.println("Found variable: " + coordYVar.getFullName() + " (Type: " + coordYVar.getDataType() + ")");

        // Read the entire 3D array data [cite: 283]
        // The specification indicates Float64, which corresponds to double in Java [cite: 283]
        System.out.println("Reading coordinateX data...");
        Array coordinateXData = coordXVar.read();
        System.out.println("Reading coordinateY data...");
        Array coordinateYData = coordYVar.read();

        // --- Access and Use the Data ---

        // Get the shape of the arrays (should be 3 dimensions: height, time/northing, range/easting)
        int[] shapeX = coordinateXData.getShape();
        int[] shapeY = coordinateYData.getShape();
        System.out.println("coordinateX shape: " + Arrays.toString(shapeX));
        System.out.println("coordinateY shape: " + Arrays.toString(shapeY));

        // You can work directly with the ucar.ma2.Array object, which is efficient
        // Example: Get the value at the first index (0, 0, 0)
        if (coordinateXData.getRank() == 3 && coordinateYData.getRank() == 3) {
            double firstX = coordinateXData.getDouble(coordinateXData.getIndex().set(0, 0, 0));
            double firstY = coordinateYData.getDouble(coordinateYData.getIndex().set(0, 0, 0));
            System.out.printf("Value at index [0,0,0]: X=%.3f, Y=%.3f%n", firstX, firstY);
        }

        // Optional: Convert to a primitive Java array if needed for other libraries/processing
        // Note: This copies all the data into memory, which can be large.
        // double[][][] coordXJavaArray = (double[][][]) coordinateXData.copyToNDJavaArray();
        // double[][][] coordYJavaArray = (double[][][]) coordinateYData.copyToNDJavaArray();
        // System.out.println("Copied data to primitive Java arrays.");
        // Now you can use coordXJavaArray[heightIndex][timeIndex][rangeIndex]

    }

    private static String getOrbitPass(String pass) {
        return pass.toUpperCase().contains("ASC") ? "ASCENDING" : "DESCENDING";
    }

    protected void createBand(final String bandName, final int width, final int height, final String unit, final Variable var) {
        final Band band = new Band(bandName, ProductData.TYPE_FLOAT32, width, height);
        band.setDescription(var.getDescription());
        band.setUnit(unit);
        band.setNoDataValue(0);
        band.setNoDataValueUsed(true);
        product.addBand(band);
        bandMap.put(band, var);
    }

    protected void addIncidenceAnglesSlantRangeTime(final Product product, final MetadataElement bandElem) {

        try {
            final Group groupLSAR = getLSARGroup();
            final Group groupMetadata = getMetadataGroup(groupLSAR);
            final Group metadata = groupMetadata.findGroup("geolocationGrid");

            Variable incidenceAngleVar = metadata.findVariable("incidenceAngle");
            Array incidenceArray = incidenceAngleVar.read();
            float[] incidenceAngles = (float[]) incidenceArray.get1DJavaArray(DataType.FLOAT);

            //if (bandElem == null) return;

            final int gridWidth = 11;
            final int gridHeight = 11;
            final float subSamplingX = product.getSceneRasterWidth() / (float) (gridWidth - 1);
            final float subSamplingY = product.getSceneRasterHeight() / (float) (gridHeight - 1);

//            final double[] incidenceAngles = (double[]) netcdfFile.getRootGroup().findVariable(
//                    NisarXConstants.INCIDENCE_ANGLES).read().getStorage();
//
//            final double nearRangeAngle = incidenceAngles[0];
//            final double farRangeAngle = incidenceAngles[incidenceAngles.length - 1];
//
//            final double firstRangeTime = netcdfFile.getRootGroup().findVariable(
//                    NisarXConstants.FIRST_PIXEL_TIME).readScalarDouble() * Constants.sTOns;
//
//            final double samplesPerLine = netcdfFile.getRootGroup().findVariable(
//                    NisarXConstants.NUM_SAMPLES_PER_LINE).readScalarDouble();
//
//            final double rangeSamplingRate = netcdfFile.getRootGroup().findVariable(
//                    NisarXConstants.RANGE_SAMPLING_RATE).readScalarDouble();
//
//            final double lastRangeTime = firstRangeTime + samplesPerLine / rangeSamplingRate * Constants.sTOns;
//
//            final float[] incidenceCorners = new float[]{(float) nearRangeAngle, (float) farRangeAngle,
//                    (float) nearRangeAngle, (float) farRangeAngle};
//
//            final float[] slantRange = new float[]{(float) firstRangeTime, (float) lastRangeTime,
//                    (float) firstRangeTime, (float) lastRangeTime};
//
//            final float[] fineAngles = new float[gridWidth * gridHeight];
//            final float[] fineTimes = new float[gridWidth * gridHeight];
//
//            ReaderUtils.createFineTiePointGrid(2, 2, gridWidth, gridHeight,
//                    incidenceCorners, fineAngles);
//
//            ReaderUtils.createFineTiePointGrid(2, 2, gridWidth, gridHeight,
//                    slantRange, fineTimes);
//
//            final TiePointGrid incidentAngleGrid = new TiePointGrid(OperatorUtils.TPG_INCIDENT_ANGLE,
//                    gridWidth, gridHeight, 0, 0, subSamplingX, subSamplingY, fineAngles);
//            incidentAngleGrid.setUnit(Unit.DEGREES);
//            product.addTiePointGrid(incidentAngleGrid);
//
//            final TiePointGrid slantRangeGrid = new TiePointGrid(OperatorUtils.TPG_SLANT_RANGE_TIME,
//                    gridWidth, gridHeight, 0, 0, subSamplingX, subSamplingY, fineTimes);
//            slantRangeGrid.setUnit(Unit.NANOSECONDS);
//            product.addTiePointGrid(slantRangeGrid);

        } catch (IOException e) {
            SystemUtils.LOG.severe(e.getMessage());

        }
    }

    protected void addGeocodingFromMetadata(
            final Product product, final MetadataElement bandElem) {

        if (bandElem == null) return;

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);

        try {
            double[] firstNear = (double[]) netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.FIRST_NEAR).read().getStorage();

            double[] firstFar = (double[]) netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.FIRST_FAR).read().getStorage();

            double[] lastNear = (double[]) netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.LAST_NEAR).read().getStorage();

            double[] lastFar = (double[]) netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.LAST_FAR).read().getStorage();

            final double latUL = firstNear[2];
            final double lonUL = firstNear[3];
            final double latUR = firstFar[2];
            final double lonUR = firstFar[3];
            final double latLL = lastNear[2];
            final double lonLL = lastNear[3];
            final double latLR = lastFar[2];
            final double lonLR = lastFar[3];

            absRoot.setAttributeDouble(AbstractMetadata.first_near_lat, latUL);
            absRoot.setAttributeDouble(AbstractMetadata.first_near_long, lonUL);
            absRoot.setAttributeDouble(AbstractMetadata.first_far_lat, latUR);
            absRoot.setAttributeDouble(AbstractMetadata.first_far_long, lonUR);
            absRoot.setAttributeDouble(AbstractMetadata.last_near_lat, latLL);
            absRoot.setAttributeDouble(AbstractMetadata.last_near_long, lonLL);
            absRoot.setAttributeDouble(AbstractMetadata.last_far_lat, latLR);
            absRoot.setAttributeDouble(AbstractMetadata.last_far_long, lonLR);

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_spacing,
                    netcdfFile.getRootGroup().findVariable(NisarXConstants.SLANT_RANGE_SPACING).readScalarDouble());

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_spacing,
                    netcdfFile.getRootGroup().findVariable(NisarXConstants.AZIMUTH_GROUND_SPACING).readScalarDouble());

            final double[] latCorners = new double[]{latUL, latUR, latLL, latLR};
            final double[] lonCorners = new double[]{lonUL, lonUR, lonLL, lonLR};

            ReaderUtils.addGeoCoding(product, latCorners, lonCorners);

        } catch (Exception e) {
            SystemUtils.LOG.severe(e.getMessage());
        }
    }

    protected static String getPolarization(final Product product, NetcdfFile netcdfFile) {

        final MetadataElement globalElem = AbstractMetadata.getOriginalProductMetadata(product).getElement(
                NetcdfConstants.GLOBAL_ATTRIBUTES_NAME);

        try {
            if (globalElem != null) {
                final String polStr = netcdfFile.getRootGroup().findVariable(NisarXConstants.MDS1_TX_RX_POLAR).
                        readScalarString();

                if (!polStr.isEmpty())
                    return polStr;
            }
        } catch (IOException e) {
            SystemUtils.LOG.severe(e.getMessage());

        }
        return null;
    }

    protected String getSampleType() {

        try {
            if (NisarXConstants.SLC.equalsIgnoreCase(netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.SPH_DESCRIPTOR).readScalarString())) {

                isComplex = true;
                return NisarXConstants.COMPLEX;
            }
        } catch (IOException e) {
            SystemUtils.LOG.severe(e.getMessage());
        }
        isComplex = false;
        return NisarXConstants.DETECTED;
    }

    protected double timeUTCtoSecs(String myDate) {

        ProductData.UTC localDateTime = null;
        try {
            localDateTime = ProductData.UTC.parse(myDate, standardDateFormat);
        } catch (ParseException e) {
            SystemUtils.LOG.severe(e.getMessage());
        }
        return localDateTime.getMJD() * 24.0 * 3600.0;
    }

    protected void addOrbitStateVectors(final MetadataElement absRoot) {

        try {
            final MetadataElement orbitVectorListElem = absRoot.getElement(AbstractMetadata.orbit_state_vectors);

            final int numPoints = netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.NUMBER_OF_STATE_VECTORS).readScalarInt();

            char[] stateVectorTime = (char[]) netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.STATE_VECTOR_TIME).read().getStorage();

            int utcDimension = netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.STATE_VECTOR_TIME).getDimension(2).getLength();

            final double[] satellitePositionX = (double[]) netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.ORBIT_VECTOR_N_X_POS).read().getStorage();

            final double[] satellitePositionY = (double[]) netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.ORBIT_VECTOR_N_Y_POS).read().getStorage();

            final double[] satellitePositionZ = (double[]) netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.ORBIT_VECTOR_N_Z_POS).read().getStorage();

            final double[] satelliteVelocityX = (double[]) netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.ORBIT_VECTOR_N_X_VEL).read().getStorage();

            final double[] satelliteVelocityY = (double[]) netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.ORBIT_VECTOR_N_Y_VEL).read().getStorage();

            final double[] satelliteVelocityZ = (double[]) netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.ORBIT_VECTOR_N_Z_VEL).read().getStorage();

            int start = 0;
            String utc = new String(Arrays.copyOfRange(stateVectorTime, 0, utcDimension - 1));
            ProductData.UTC stateVectorUTC = ProductData.UTC.parse(utc, standardDateFormat);

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.STATE_VECTOR_TIME, stateVectorUTC);

            for (int i = 0; i < numPoints; i++) {
                utc = new String(Arrays.copyOfRange(stateVectorTime, start, start + utcDimension - 1));
                ProductData.UTC vectorUTC = ProductData.UTC.parse(utc, standardDateFormat);

                final MetadataElement orbitVectorElem = new MetadataElement(AbstractMetadata.orbit_vector + (i + 1));
                orbitVectorElem.setAttributeUTC(AbstractMetadata.orbit_vector_time, vectorUTC);

                orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_x_pos, satellitePositionX[i]);
                orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_y_pos, satellitePositionY[i]);
                orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_z_pos, satellitePositionZ[i]);
                orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_x_vel, satelliteVelocityX[i]);
                orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_y_vel, satelliteVelocityY[i]);
                orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_z_vel, satelliteVelocityZ[i]);

                orbitVectorListElem.addElement(orbitVectorElem);
                start += utcDimension;
            }
        } catch (IOException | ParseException e) {
            SystemUtils.LOG.severe(e.getMessage());

        }
    }

    protected MetadataElement getBandElement(final Band band) {

        final MetadataElement root = AbstractMetadata.getOriginalProductMetadata(product);
        final Variable variable = bandMap.get(band);
        final String varName = variable.getShortName();
        MetadataElement bandElem = null;
        for (MetadataElement elem : root.getElements()) {
            if (elem.getName().equalsIgnoreCase(varName)) {
                bandElem = elem;
                break;
            }
        }
        return bandElem;
    }

    protected void addGeoCodingToProduct(Variable[] rasterVariables) throws IOException {

        if (product.getSceneGeoCoding() == null) {
            NetCDFReader.setTiePointGeoCoding(product);
        }
        if (product.getSceneGeoCoding() == null) {
            NetCDFReader.setPixelGeoCoding(product);
        }
        if (product.getSceneGeoCoding() == null) {
            NcRasterDim rasterDim = new NcRasterDim(
                    rasterVariables[0].getDimension(0), rasterVariables[0].getDimension(1));
            NetCDFReader.setMapGeoCoding(rasterDim, product, netcdfFile, false);
        }
    }

    protected void addDopplerCentroidCoefficients() {

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);

        final MetadataElement dopplerCentroidCoefficientsElem = absRoot.getElement(AbstractMetadata.dop_coefficients);
        final MetadataElement dopplerListElem = new MetadataElement(AbstractMetadata.dop_coef_list + ".1");
        dopplerCentroidCoefficientsElem.addElement(dopplerListElem);

        final ProductData.UTC utcTime = absRoot.getAttributeUTC(AbstractMetadata.first_line_time,
                AbstractMetadata.NO_METADATA_UTC);

        dopplerListElem.setAttributeUTC(AbstractMetadata.dop_coef_time, utcTime);

        AbstractMetadata.addAbstractedAttribute(dopplerListElem, AbstractMetadata.slant_range_time,
                ProductData.TYPE_FLOAT64, "ns", "Slant Range Time");

        AbstractMetadata.setAttribute(dopplerListElem, AbstractMetadata.slant_range_time, 0.0);

        try {

            int dimensionColumn = netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.DC_ESTIMATE_COEFFS).getDimension(1).getLength();

            double[] coefValueS = (double[]) netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.DC_ESTIMATE_COEFFS).read().getStorage();

            for (int i = 0; i < dimensionColumn; i++) {
                final double coefValue = coefValueS[i];

                final MetadataElement coefElem = new MetadataElement(AbstractMetadata.coefficient + '.' + (i + 1));
                dopplerListElem.addElement(coefElem);

                AbstractMetadata.addAbstractedAttribute(coefElem, AbstractMetadata.dop_coef,
                        ProductData.TYPE_FLOAT64, "", "Doppler Centroid Coefficient");

                AbstractMetadata.setAttribute(coefElem, AbstractMetadata.dop_coef, coefValue);
            }
        } catch (IOException e) {
            SystemUtils.LOG.severe(e.getMessage());

        }
    }

    protected void addDopplerMetadata() {

//        addDopplerCentroidCoefficients();
    }



    /**
     * {@inheritDoc}
     */
    //Todo remove synchronized
    public synchronized void readBandRasterDataImpl(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight,
                                                    int sourceStepX, int sourceStepY, Band destBand, int destOffsetX,
                                                    int destOffsetY, int destWidth, int destHeight, ProductData destBuffer,
                                                    ProgressMonitor pm) throws IOException {

        final int sceneHeight = product.getSceneRasterHeight();
        final int sceneWidth = product.getSceneRasterWidth();

        final Variable variable = bandMap.get(destBand);

        destHeight = Math.min(destHeight, sceneHeight - sourceOffsetY);
        sourceWidth = Math.min(sourceWidth, sceneWidth - sourceOffsetX);
        destWidth = Math.min(destWidth, sceneWidth - destOffsetX);
        final int[] origin = {sourceOffsetY, sourceOffsetX};
        final int[] shape = {1, sourceWidth};
        pm.beginTask("Reading util from band " + destBand.getName(), destHeight);

        try {
            boolean isComplexData = variable.getDataType() == DataType.STRUCTURE;
            String complexMemberName = "HH";//destBand.getName().contains("i_") ? "r" : "i";

            for (int y = 0; y < destHeight; y++) {
                origin[0] = sourceOffsetY + y;
                final Array array;
                synchronized (netcdfFile) {
                    array = variable.read(origin, shape);
                }

                if (isComplexData) {
                    StructureData[] row = (StructureData[]) array.get1DJavaArray(STRUCTURE);
                    final float[] tempArray = new float[row.length];
                    for (int i = 0; i < row.length; ++i) {
                        StructureMembers members = row[i].getStructureMembers();
                        List<StructureMembers.Member> members1 = row[i].getMembers();

                        tempArray[i] = row[i].convertScalarFloat(complexMemberName);
                    }
                    System.arraycopy(tempArray, 0, destBuffer.getElems(), y * destWidth, destWidth);
                } else {
                    float[] tempArray = (float[]) array.get1DJavaArray(Float.TYPE);
                    System.arraycopy(tempArray, 0, destBuffer.getElems(), y * destWidth, destWidth);
                }

                pm.worked(1);
            }
        } catch (Exception e) {
            //final IOException ioException = new IOException(e);
            //ioException.initCause(e);
            //throw ioException;
            System.out.println(e.getMessage());
        } finally {
            pm.done();
        }
    }
}
