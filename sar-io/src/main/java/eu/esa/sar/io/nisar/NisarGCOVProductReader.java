/*
 * Copyright (C) 2024 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.io.nisar;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.io.SARReader;
import eu.esa.sar.commons.product.Missions;
import eu.esa.sar.io.netcdf.NcAttributeMap;
import eu.esa.sar.io.nisar.util.NisarXConstants;
import eu.esa.sar.io.netcdf.NetCDFReader;
import eu.esa.sar.io.netcdf.NetCDFUtils;
import eu.esa.sar.io.netcdf.NetcdfConstants;
import org.esa.snap.core.dataio.IllegalFileFormatException;
import org.esa.snap.core.dataio.ProductReaderPlugIn;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.TiePointGrid;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import ucar.ma2.Array;
import ucar.ma2.IndexIterator;
import ucar.ma2.StructureData;
import ucar.ma2.StructureDataIterator;
import ucar.nc2.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.ParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ucar.ma2.DataType.STRUCTURE;

public class NisarGCOVProductReader extends SARReader {

    private final Map<Band, Variable> bandMap = new HashMap<>(10);
    private final DateFormat standardDateFormat = ProductData.UTC.createDateFormat("yyyy-MM-dd'T'HH:mm:ss");
    private NetcdfFile netcdfFile = null;
    private Product product = null;
    private boolean isComplex = true;

    /**
     * Constructs a new abstract product reader.
     *
     * @param readerPlugIn the reader plug-in which created this reader, can be <code>null</code> for internal reader
     *                     implementations
     */
    public NisarGCOVProductReader(final ProductReaderPlugIn readerPlugIn) {
        super(readerPlugIn);
    }

    private static String getPolarization(final Product product, NetcdfFile netcdfFile) {

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

    private static void createUniqueBandName(final Product product, final Band band, final String origName) {

        int cnt = 1;
        band.setName(origName);
        while (product.getBand(band.getName()) != null) {
            band.setName(origName + cnt);
            ++cnt;
        }
    }

    private static void addIncidenceAnglesSlantRangeTime(final Product product, final MetadataElement bandElem,
                                                         NetcdfFile netcdfFile) {

        try {
            if (bandElem == null) return;

            final int gridWidth = 11;
            final int gridHeight = 11;
            final float subSamplingX = product.getSceneRasterWidth() / (float) (gridWidth - 1);
            final float subSamplingY = product.getSceneRasterHeight() / (float) (gridHeight - 1);

            final double[] incidenceAngles = (double[]) netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.INCIDENCE_ANGLES).read().getStorage();

            final double nearRangeAngle = incidenceAngles[0];
            final double farRangeAngle = incidenceAngles[incidenceAngles.length - 1];

            final double firstRangeTime = netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.FIRST_PIXEL_TIME).readScalarDouble() * Constants.sTOns;

            final double samplesPerLine = netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.NUM_SAMPLES_PER_LINE).readScalarDouble();

            final double rangeSamplingRate = netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.RANGE_SAMPLING_RATE).readScalarDouble();

            final double lastRangeTime = firstRangeTime + samplesPerLine / rangeSamplingRate * Constants.sTOns;

            final float[] incidenceCorners = new float[]{(float) nearRangeAngle, (float) farRangeAngle,
                    (float) nearRangeAngle, (float) farRangeAngle};

            final float[] slantRange = new float[]{(float) firstRangeTime, (float) lastRangeTime,
                    (float) firstRangeTime, (float) lastRangeTime};

            final float[] fineAngles = new float[gridWidth * gridHeight];
            final float[] fineTimes = new float[gridWidth * gridHeight];

            ReaderUtils.createFineTiePointGrid(2, 2, gridWidth, gridHeight,
                    incidenceCorners, fineAngles);

            ReaderUtils.createFineTiePointGrid(2, 2, gridWidth, gridHeight,
                    slantRange, fineTimes);

            final TiePointGrid incidentAngleGrid = new TiePointGrid(OperatorUtils.TPG_INCIDENT_ANGLE,
                    gridWidth, gridHeight, 0, 0, subSamplingX, subSamplingY, fineAngles);
            incidentAngleGrid.setUnit(Unit.DEGREES);
            product.addTiePointGrid(incidentAngleGrid);

            final TiePointGrid slantRangeGrid = new TiePointGrid(OperatorUtils.TPG_SLANT_RANGE_TIME,
                    gridWidth, gridHeight, 0, 0, subSamplingX, subSamplingY, fineTimes);
            slantRangeGrid.setUnit(Unit.NANOSECONDS);
            product.addTiePointGrid(slantRangeGrid);

        } catch (IOException e) {
            SystemUtils.LOG.severe(e.getMessage());

        }
    }

    private static void addGeocodingFromMetadata(
            final Product product, final MetadataElement bandElem, NetcdfFile netcdfFile) {

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

    private void initReader() {
        product = null;
        netcdfFile = null;
    }

    /**
     * Provides an implementation of the <code>readProductNodes</code> interface method. Clients implementing this
     * method can be sure that the input object and eventually the subset information has already been set.
     * <p/>
     * <p>This method is called as a last step in the <code>readProductNodes(input, subsetInfo)</code> method.
     */
    @Override
    protected Product readProductNodesImpl() {

        try {
            final Path inputPath = ReaderUtils.getPathFromInput(getInput());
            final File inputFile = inputPath.toFile();
            initReader();

            final NetcdfFile tempNetcdfFile = NetcdfFile.open(inputFile.getPath());
            if (tempNetcdfFile == null) {
                close();
                throw new IllegalFileFormatException(inputFile.getName() +
                        " Could not be interpreted by the reader.");
            }

            if (tempNetcdfFile.getRootGroup().getGroups().isEmpty()) {
                close();
                throw new IllegalFileFormatException("No netCDF groups found.");
            }
            this.netcdfFile = tempNetcdfFile;

            final Group groupScience = this.netcdfFile.getRootGroup().findGroup("science");
            final Group groupLSAR = groupScience.findGroup("LSAR");
            final Group groupID = groupLSAR.findGroup("identification");
            final Group groupRSLC = groupLSAR.findGroup("RSLC");
            final Group groupMetadata = groupRSLC.findGroup("metadata");
            final Group groupSwaths = groupRSLC.findGroup("swaths");
            final Group groupFrequencyA = groupSwaths.findGroup("frequencyA");

            final Variable hh = groupFrequencyA.findVariable("HH");
            final int rasterHeight = hh.getDimension(0).getLength();
            final int rasterWidth = hh.getDimension(1).getLength();
            final String productType = groupID.findVariable(NisarXConstants.PRODUCT_TYPE).readScalarString();
            final String missionID = groupID.findVariable(NisarXConstants.MISSION).readScalarString();
            final String startTime = groupID.findVariable(NisarXConstants.ACQUISITION_START_UTC).readScalarString().substring(0,22);
            final String stopTime = groupID.findVariable(NisarXConstants.ACQUISITION_END_UTC).readScalarString().substring(0,22);

            product = new Product(inputFile.getName(),
                    productType,
                    rasterWidth, rasterHeight,
                    this);
            product.setFileLocation(inputFile);

            StringBuilder description = new StringBuilder();
            description.append(inputFile.getName()).append(" - ");
            description.append(productType).append(" - ");
            description.append(missionID);
            product.setDescription(description.toString());
            product.setStartTime(ProductData.UTC.parse(startTime, standardDateFormat));
            product.setEndTime(ProductData.UTC.parse(stopTime, standardDateFormat));

            addMetadataToProduct();
            addBandsToProduct();
            addTiePointGridsToProduct();
            addGeoCodingToProduct();
            addCommonSARMetadata(product);
            addDopplerMetadata();
            setQuicklookBandName(product);

            product.getGcpGroup();
            product.setModified(false);

            return product;
        } catch (Exception e) {
            SystemUtils.LOG.severe(e.getMessage());
        }
        return null;
    }

    private Group findGroup(final String groupName, final List<Group> groups) {

        for (Group group : groups) {
            final String name = group.getName();
            if (group.getName().equals(groupName)) {
                return group;
            }
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        if (product != null) {
            product = null;
            netcdfFile.close();
            netcdfFile = null;
        }
        super.close();
    }

    private void addMetadataToProduct() {

        final MetadataElement origMetadataRoot = AbstractMetadata.addOriginalProductMetadata(product.getMetadataRoot());
        NetCDFUtils.addAttributes(origMetadataRoot, NetcdfConstants.GLOBAL_ATTRIBUTES_NAME,
                netcdfFile.getGlobalAttributes());

        for (Variable variable : netcdfFile.getVariables()) {
            NetCDFUtils.addVariableMetadata(origMetadataRoot, variable, 5000);
        }

        addAbstractedMetadataHeader(product.getMetadataRoot());
    }

    private void addAbstractedMetadataHeader(MetadataElement root) {

        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(root);

        try {
            final Group groupScience = this.netcdfFile.getRootGroup().findGroup("science");
            final Group groupLSAR = groupScience.findGroup("LSAR");
            final Group groupID = groupLSAR.findGroup("identification");
            final Group groupRSLC = groupLSAR.findGroup("RSLC");
            final Group groupMetadata = groupRSLC.findGroup("metadata");
            final Group groupSwaths = groupRSLC.findGroup("swaths");
            final Group groupFrequencyA = groupSwaths.findGroup("frequencyA");
            final Group groupGeolocationGrid = groupMetadata.findGroup("geolocationGrid");

            final String title = this.netcdfFile.getRootGroup().findAttValueIgnoreCase("title", null);
            final String mission = this.netcdfFile.getRootGroup().findAttValueIgnoreCase("mission_name", null);
            final String institution = this.netcdfFile.getRootGroup().findAttValueIgnoreCase("institution", null);

//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PRODUCT,
//                    );

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PRODUCT_TYPE,
                    groupID.findVariable(NisarXConstants.PRODUCT_TYPE).readScalarString());

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SPH_DESCRIPTOR, "SLC");

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.MISSION, Missions.NISAR);

//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ACQUISITION_MODE,
//                    netcdfFile.getRootGroup().findVariable(NisarXConstants.ACQUISITION_MODE).readScalarString());

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.antenna_pointing,
                    groupID.findVariable(NisarXConstants.ANTENNA_POINTING).readScalarString());

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.BEAMS, NisarXConstants.BEAMS_DEFAULT_VALUE);

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PROC_TIME, ProductData.UTC.parse(
                    groupID.findVariable(NisarXConstants.PROC_TIME_UTC).readScalarString().substring(0,22),
                    standardDateFormat));

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ProcessingSystemIdentifier,
                    NisarXConstants.NISAR_PROCESSOR_NAME_PREFIX +
                            groupID.findVariable(NisarXConstants.PROCESSING_SYSTEM_IDENTIFIER).readScalarString());

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.CYCLE, 99999);

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.REL_ORBIT, 99999);

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ABS_ORBIT,
                    groupID.findVariable(NisarXConstants.ABS_ORBIT).readScalarInt());

////            double localIncidenceAngle = groupGeolocationGrid.findVariable("incidenceAngle").readScalarFloat();
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.incidence_near, localIncidenceAngle);
//
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.incidence_far,  localIncidenceAngle);

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.slice_num,
                    NisarXConstants.SLICE_NUM_DEFAULT_VALUE);

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.data_take_id,
                    NisarXConstants.DATA_TAKE_ID_DEFAULT_VALUE);

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.geo_ref_system,
                    NisarXConstants.GEO_REFERENCE_SYSTEM_DEFAULT_VALUE);

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_line_time, ProductData.UTC.parse(
                    groupID.findVariable(NisarXConstants.ACQUISITION_START_UTC).readScalarString().substring(0,22),
                    standardDateFormat));

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_line_time, ProductData.UTC.parse(
                    groupID.findVariable(NisarXConstants.ACQUISITION_END_UTC).readScalarString().substring(0,22),
                    standardDateFormat));

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
//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PASS,
//                    netcdfFile.getRootGroup().findVariable(NisarXConstants.PASS).readScalarString());
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

        } catch (ParseException | IOException e) {
            SystemUtils.LOG.severe(e.getMessage());

        }
    }

    private void addOrbitStateVectors(final MetadataElement absRoot) {

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

    private void addDopplerCentroidCoefficients() {

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

    private void addDopplerMetadata() {

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        final String imagingMode = absRoot.getAttributeString("ACQUISITION_MODE");

        if (imagingMode.equalsIgnoreCase("spotlight"))  {
            final MetadataElement dopplerSpotlightElem = new MetadataElement("dopplerSpotlight");
            absRoot.addElement(dopplerSpotlightElem);
            addDopplerRateAndCentroidSpotlight(dopplerSpotlightElem);
            addAzimuthTimeZpSpotlight(dopplerSpotlightElem);
        }
//        addDopplerCentroidCoefficients();
    }

    private void addDopplerRateAndCentroidSpotlight(MetadataElement elem) {

        // Compute doppler rate and centroid
        MetadataElement origProdRoot = AbstractMetadata.getOriginalProductMetadata(product);
        MetadataElement dopplerRateCoeffs = origProdRoot.getElement(NisarXConstants.DR_COEFFS);
        String dopplerRate = dopplerRateCoeffs.getAttributeString("data").split(",")[0]; // take first coefficient
        final double fmRate = Double.parseDouble(dopplerRate);
        final double dopplerCentroid = 0.0; // TODO: load from original metadata once it's accurate

        final int rasterWidth = product.getSceneRasterWidth();
        final double[] dopplerRateSpotlight = new double[rasterWidth];
        final double[] dopplerCentroidSpotlight = new double[rasterWidth];

        for(int i = 0; i < rasterWidth; i++) {
            dopplerRateSpotlight[i] = fmRate;
            dopplerCentroidSpotlight[i] = dopplerCentroid;
        }

        // Save in metadata
        String dopplerRateSpotlightStr =
                Arrays.toString(dopplerRateSpotlight).replace("]", "").replace("[", "");

        String dopplerCentroidSpotlightStr =
                Arrays.toString(dopplerCentroidSpotlight).replace("]", "").replace("[", "");

        AbstractMetadata.addAbstractedAttribute(elem, "dopplerRateSpotlight",
               ProductData.TYPE_ASCII, "", "Doppler Rate Spotlight");

        AbstractMetadata.setAttribute(elem, "dopplerRateSpotlight", dopplerRateSpotlightStr);

        AbstractMetadata.addAbstractedAttribute(elem, "dopplerCentroidSpotlight",
                ProductData.TYPE_ASCII, "", "Doppler Centroid Spotlight");

        AbstractMetadata.setAttribute(elem, "dopplerCentroidSpotlight", dopplerCentroidSpotlightStr);
    }

    private void addAzimuthTimeZpSpotlight(MetadataElement elem) {
        // Compute azimuth time
        MetadataElement origProdRoot = AbstractMetadata.getOriginalProductMetadata(product);

        final double firstAzimuthTimeZp = timeUTCtoSecs(origProdRoot.getAttributeString(
                NisarXConstants.FIRST_LINE_TIME));

        final double lastAzimuthTimeZp = timeUTCtoSecs(origProdRoot.getAttributeString(
                NisarXConstants.LAST_LINE_TIME));

        final double AzimuthTimeZpOffset = firstAzimuthTimeZp - 0.5 * (firstAzimuthTimeZp + lastAzimuthTimeZp);

        // Save in metadata
        final MetadataElement azimuthTimeZd = new MetadataElement("azimuthTimeZdSpotlight");

        elem.addElement(azimuthTimeZd);

        AbstractMetadata.addAbstractedAttribute(azimuthTimeZd, "AzimuthTimeZdOffset", ProductData.TYPE_FLOAT64,
                "", "Azimuth Time Zero Doppler Offset");

        AbstractMetadata.setAttribute(azimuthTimeZd, "AzimuthTimeZdOffset", AzimuthTimeZpOffset);
    }

    private double timeUTCtoSecs(String myDate) {

        ProductData.UTC localDateTime = null;
        try {
            localDateTime = ProductData.UTC.parse(myDate, standardDateFormat);
        } catch (ParseException e) {
            SystemUtils.LOG.severe(e.getMessage());
        }
        return localDateTime.getMJD() * 24.0 * 3600.0;
    }

    private String getSampleType() {

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

    private void addBandsToProduct() {

        int cnt = 1;
        Map<String, Variable> variables = new HashMap<>();
        final Group groupScience = this.netcdfFile.getRootGroup().findGroup("science");
        final Group groupLSAR = groupScience.findGroup("LSAR");
        final Group groupRSLC = groupLSAR.findGroup("RSLC");
        final Group groupSwaths = groupRSLC.findGroup("swaths");
        final Group groupFrequencyA = groupSwaths.findGroup("frequencyA");

        final Variable hh = groupFrequencyA.findVariable("HH");
        final Variable hv = groupFrequencyA.findVariable("HV");
        final Variable vh = groupFrequencyA.findVariable("VH");
        final Variable vv = groupFrequencyA.findVariable("VV");

        String polStr = "";
        int width = 0, height = 0;
        if (hh != null) {
            variables.put(NisarXConstants.I_Q, hh);
            polStr = "HH";
            height = hh.getDimension(0).getLength();
            width = hh.getDimension(1).getLength();
        } else if (hv != null) {
            variables.put(NisarXConstants.I_Q, hv);
            polStr = "HV";
            height = hv.getDimension(0).getLength();
            width = hv.getDimension(1).getLength();
        } else if (vh != null) {
            variables.put(NisarXConstants.I_Q, vh);
            polStr = "VH";
            height = vh.getDimension(0).getLength();
            width = vh.getDimension(1).getLength();
        } else if (vv != null) {
            variables.put(NisarXConstants.I_Q, vv);
            polStr = "VV";
            height = vv.getDimension(0).getLength();
            width = vv.getDimension(1).getLength();
        }

//        final NcAttributeMap attMap = NcAttributeMap.create(variables.get(NisarXConstants.I_Q));

        try {
            final Band bandIQ = new Band("i_q" + polStr, ProductData.TYPE_FLOAT32, width, height);
            bandIQ.setDescription("I-Q band of the focused SLC image (HH)");
            bandIQ.setUnit("CFloat16");
            bandIQ.setNoDataValue(0);
            bandIQ.setNoDataValueUsed(true);
            product.addBand(bandIQ);
            bandMap.put(bandIQ, variables.get(NisarXConstants.I_Q));

//            final Band bandI = new Band("i_" + polStr, ProductData.TYPE_FLOAT32, width, height);
//            bandI.setDescription("I band of the focused SLC image (HH)");
//            bandI.setUnit(Unit.REAL);
//            bandI.setNoDataValue(0);
//            bandI.setNoDataValueUsed(true);
//            product.addBand(bandI);
//            bandMap.put(bandI, variables.get(NisarXConstants.I_Q));
//
//            final Band bandQ = new Band("q_" + polStr, ProductData.TYPE_FLOAT32, width, height);
//            bandI.setDescription("Q band of the focused SLC image (HH)");
//            bandQ.setUnit(Unit.IMAGINARY);
//            bandQ.setNoDataValue(0);
//            bandQ.setNoDataValueUsed(true);
//            product.addBand(bandQ);
//            bandMap.put(bandQ, variables.get(NisarXConstants.I_Q));
//
//            ReaderUtils.createVirtualIntensityBand(product, bandI, bandQ, polStr);

        } catch (Exception e) {
            SystemUtils.LOG.severe(e.getMessage());

        }
    }

    private void addTiePointGridsToProduct() {

        final MetadataElement bandElem = getBandElement(product.getBandAt(0));
        addIncidenceAnglesSlantRangeTime(product, bandElem, netcdfFile);
        addGeocodingFromMetadata(product, bandElem, netcdfFile);
    }

    private MetadataElement getBandElement(final Band band) {

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

    private void addGeoCodingToProduct() throws IOException {

        if (product.getSceneGeoCoding() == null) {
            NetCDFReader.setTiePointGeoCoding(product);
        }

        if (product.getSceneGeoCoding() == null) {
            NetCDFReader.setPixelGeoCoding(product);
        }

    }

    void callReadBandRasterData(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight,
                                int sourceStepX, int sourceStepY, Band destBand, int destOffsetX,
                                int destOffsetY, int destWidth, int destHeight, ProductData destBuffer,
                                ProgressMonitor pm) throws IOException {

        readBandRasterDataImpl(sourceOffsetX, sourceOffsetY, sourceWidth, sourceHeight,
                sourceStepX, sourceStepY, destBand, destOffsetX, destOffsetY, destWidth, destHeight, destBuffer, pm);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void readBandRasterDataImpl(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight,
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
            for (int y = 0; y < destHeight; y++) {
                origin[0] = sourceOffsetY + y;
                final Array array;
                synchronized (netcdfFile) {
                    array = variable.read(origin, shape);
                }

                StructureData[] row = (StructureData[])array.get1DJavaArray(STRUCTURE);
                int r = row.length;
                float[] hh = row[0].getJavaArrayFloat("HH");

                System.arraycopy(array.getStorage(), 0, destBuffer.getElems(), y * destWidth, destWidth);
                pm.worked(1);
            }
        } catch (Exception e) {
            final IOException ioException = new IOException(e);
            ioException.initCause(e);
            throw ioException;
        } finally {
            pm.done();
        }
    }

}