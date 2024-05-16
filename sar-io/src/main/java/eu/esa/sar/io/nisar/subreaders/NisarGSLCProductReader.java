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
package eu.esa.sar.io.nisar.subreaders;

import eu.esa.sar.commons.product.Missions;
import eu.esa.sar.io.nisar.util.NisarXConstants;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import ucar.nc2.Group;
import ucar.nc2.Variable;

import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NisarGSLCProductReader extends NisarSubReader {

    public NisarGSLCProductReader() {
        productType = "GSLC";
    }

    @Override
    protected Variable[] getRasterVariables(final Group groupFrequencyA) {
        List<Variable> rasterVariables = new ArrayList<>();

        final Variable hh = groupFrequencyA.findVariable("HH");
        final Variable hv = groupFrequencyA.findVariable("HV");
        final Variable vh = groupFrequencyA.findVariable("VH");
        final Variable vv = groupFrequencyA.findVariable("VV");

        if (hh != null) {
            rasterVariables.add(hh);
        } else if (hv != null) {
            rasterVariables.add(hv);
        } else if (vh != null) {
            rasterVariables.add(vh);
        } else if (vv != null) {
            rasterVariables.add(vv);
        }

        return rasterVariables.toArray(new Variable[0]);
    }

    @Override
    protected void addAbstractedMetadataHeader(MetadataElement root) {

        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(root);

        try {
            final Group groupLSAR = getLSARGroup();
            final Group groupID = getIndenificationGroup(groupLSAR);
            final Group groupFrequencyA = getFrequencyAGroup(groupLSAR);

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
                    groupID.findVariable(NisarXConstants.PROC_TIME_UTC).readScalarString().substring(0, 22),
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
                    groupID.findVariable(NisarXConstants.ACQUISITION_START_UTC).readScalarString().substring(0, 22),
                    standardDateFormat));

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_line_time, ProductData.UTC.parse(
                    groupID.findVariable(NisarXConstants.ACQUISITION_END_UTC).readScalarString().substring(0, 22),
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

    @Override
    protected void addBandsToProduct() {

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
}