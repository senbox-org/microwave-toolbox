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

public class NisarROFFProductReader extends NisarSubReader {

    public NisarROFFProductReader() {
        productType = "ROFF";
    }

    @Override
    protected Variable[] getRasterVariables(final Group groupFrequencyA) {
        List<Variable> rasterVariables = new ArrayList<>();
        final Group groupPixelOffsets = groupFrequencyA.findGroup("pixelOffsets");
        Group[] polGroups = getPolarizationGroups(groupPixelOffsets);

        for (Group polGroup : polGroups) {
            Variable offset = polGroup.findVariable("slantRangeOffset");
            if (offset == null) {
                final Group layer1 = polGroup.findGroup("layer1");
                offset = layer1.findVariable("slantRangeOffset");
            }
            rasterVariables.add(offset);
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

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SPH_DESCRIPTOR, "ROFF");

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.MISSION, Missions.NISAR);

//            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ACQUISITION_MODE,
//                    netcdfFile.getRootGroup().findVariable(NisarXConstants.ACQUISITION_MODE).readScalarString());

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.antenna_pointing,
                    groupID.findVariable(NisarXConstants.ANTENNA_POINTING).readScalarString());

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.BEAMS, NisarXConstants.BEAMS_DEFAULT_VALUE);

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PROC_TIME, ProductData.UTC.parse(
                    groupID.findVariable("processingDateTime").readScalarString(),
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
                    groupID.findVariable("referenceZeroDopplerStartTime").readScalarString().substring(0, 22),
                    standardDateFormat));

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_line_time, ProductData.UTC.parse(
                    groupID.findVariable("referenceZeroDopplerEndTime").readScalarString().substring(0, 22),
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
        final Group groupROFF = groupLSAR.findGroup("ROFF");
        final Group groupSwaths = groupROFF.findGroup("swaths");
        final Group groupFrequencyA = groupSwaths.findGroup("frequencyA");
        final Group groupPixelOffsets = groupFrequencyA.findGroup("pixelOffsets");
        final Group groupHH = groupPixelOffsets.findGroup("HH");
        final Group groupLayer1 = groupHH.findGroup("layer1");
        final Group groupLayer2 = groupHH.findGroup("layer2");
        final Group groupLayer3 = groupHH.findGroup("layer3");

        final Variable alongTrackOffsetL1 = groupLayer1.findVariable("alongTrackOffset");
        final Variable alongTrackOffsetVarianceL1 = groupLayer1.findVariable("alongTrackOffsetVariance");
        final Variable correlationSurfacePeakL1 = groupLayer1.findVariable("correlationSurfacePeak");
        final Variable crossOffsetVarianceL1 = groupLayer1.findVariable("crossOffsetVariance");
        final Variable slantRangeOffsetL1 = groupLayer1.findVariable("slantRangeOffset");
        final Variable slantRangeOffsetVarianceL1 = groupLayer1.findVariable("slantRangeOffsetVariance");
        final Variable snrL1 = groupLayer1.findVariable("snr");

        variables.put("L1_alongTrackOffset", alongTrackOffsetL1);
        variables.put("L1_alongTrackOffsetVariance", alongTrackOffsetVarianceL1);
        variables.put("L1_correlationSurfacePeak", correlationSurfacePeakL1);
        variables.put("L1_crossOffsetVariance", crossOffsetVarianceL1);
        variables.put("L1_slantRangeOffset", slantRangeOffsetL1);
        variables.put("L1_slantRangeOffsetVariance", slantRangeOffsetVarianceL1);
        variables.put("L1_snr", snrL1);

        final Variable alongTrackOffsetL2 = groupLayer2.findVariable("alongTrackOffset");
        final Variable alongTrackOffsetVarianceL2 = groupLayer2.findVariable("alongTrackOffsetVariance");
        final Variable correlationSurfacePeakL2 = groupLayer2.findVariable("correlationSurfacePeak");
        final Variable crossOffsetVarianceL2 = groupLayer2.findVariable("crossOffsetVariance");
        final Variable slantRangeOffsetL2 = groupLayer2.findVariable("slantRangeOffset");
        final Variable slantRangeOffsetVarianceL2 = groupLayer2.findVariable("slantRangeOffsetVariance");
        final Variable snrL2 = groupLayer2.findVariable("snr");

        variables.put("L2_alongTrackOffset", alongTrackOffsetL2);
        variables.put("L2_alongTrackOffsetVariance", alongTrackOffsetVarianceL2);
        variables.put("L2_correlationSurfacePeak", correlationSurfacePeakL2);
        variables.put("L2_crossOffsetVariance", crossOffsetVarianceL2);
        variables.put("L2_slantRangeOffset", slantRangeOffsetL2);
        variables.put("L2_slantRangeOffsetVariance", slantRangeOffsetVarianceL2);
        variables.put("L2_snr", snrL2);

        final Variable alongTrackOffsetL3 = groupLayer3.findVariable("alongTrackOffset");
        final Variable alongTrackOffsetVarianceL3 = groupLayer3.findVariable("alongTrackOffsetVariance");
        final Variable correlationSurfacePeakL3 = groupLayer3.findVariable("correlationSurfacePeak");
        final Variable crossOffsetVarianceL3 = groupLayer3.findVariable("crossOffsetVariance");
        final Variable slantRangeOffsetL3 = groupLayer3.findVariable("slantRangeOffset");
        final Variable slantRangeOffsetVarianceL3 = groupLayer3.findVariable("slantRangeOffsetVariance");
        final Variable snrL3 = groupLayer3.findVariable("snr");

        variables.put("L3_alongTrackOffset", alongTrackOffsetL3);
        variables.put("L3_alongTrackOffsetVariance", alongTrackOffsetVarianceL3);
        variables.put("L3_correlationSurfacePeak", correlationSurfacePeakL3);
        variables.put("L3_crossOffsetVariance", crossOffsetVarianceL3);
        variables.put("L3_slantRangeOffset", slantRangeOffsetL3);
        variables.put("L3_slantRangeOffsetVariance", slantRangeOffsetVarianceL3);
        variables.put("L3_snr", snrL3);

        final Variable slantRange = groupPixelOffsets.findVariable("slantRange");
        final Variable zeroDopplerTime = groupPixelOffsets.findVariable("zeroDopplerTime");
        final int width = slantRange.getDimension(0).getLength();
        final int height = zeroDopplerTime.getDimension(0).getLength();

        String polStr = "HH";

        try {
            for (String key : variables.keySet()) {
                final Variable var = variables.get(key);
                final Band band = new Band(key + "_" + polStr, ProductData.TYPE_FLOAT32, width, height);
                band.setDescription(var.getDescription());
                band.setUnit(var.getUnitsString());
                band.setNoDataValue(0);
                band.setNoDataValueUsed(true);
                product.addBand(band);
                bandMap.put(band, var);
            }

        } catch (Exception e) {
            SystemUtils.LOG.severe(e.getMessage());

        }
    }
}