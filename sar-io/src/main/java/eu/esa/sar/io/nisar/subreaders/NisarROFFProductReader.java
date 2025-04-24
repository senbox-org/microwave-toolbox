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

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.util.SystemUtils;
import ucar.nc2.Group;
import ucar.nc2.Variable;

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