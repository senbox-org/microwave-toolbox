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

import eu.esa.sar.commons.product.Missions;
import eu.esa.sar.io.nisar.util.NisarXConstants;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import ucar.nc2.Group;
import ucar.nc2.Variable;

import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NisarRIFGProductReader extends NisarSubReader {

    public NisarRIFGProductReader() {
        productType = "RIFG";
    }

    @Override
    protected Variable[] getRasterVariables(final Group groupFrequencyA) {
        List<Variable> rasterVariables = new ArrayList<>();
        final Group groupInterferogram = groupFrequencyA.findGroup("interferogram");
        final Group[] polGroups = getPolarizationGroups(groupInterferogram);

        for (Group polGroup : polGroups) {
            final Variable coh = polGroup.findVariable("coherenceMagnitude");

            rasterVariables.add(coh);
        }


        return rasterVariables.toArray(new Variable[0]);
    }

    @Override
    protected void addSubReaderMetadata(final MetadataElement absRoot, final MetadataElement lsar) throws Exception {

    }

    @Override
    protected void addBandsToProduct() {

        int cnt = 1;
        Map<String, Variable> variables = new HashMap<>();
        final Group groupScience = this.netcdfFile.getRootGroup().findGroup("science");
        final Group groupLSAR = groupScience.findGroup("LSAR");
        final Group groupRIFG = groupLSAR.findGroup("RIFG");
        final Group groupSwaths = groupRIFG.findGroup("swaths");
        final Group groupFrequencyA = groupSwaths.findGroup("frequencyA");
        final Group groupInterferogram = groupFrequencyA.findGroup("interferogram");
        final Group groupPixelOffsets = groupFrequencyA.findGroup("pixelOffsets");
        final Group groupInterferogramHH = groupInterferogram.findGroup("HH");
        final Group groupPixelOffsetsHH = groupPixelOffsets.findGroup("HH");

        final Variable coherenceMagnitude = groupInterferogramHH.findVariable("coherenceMagnitude");
        final Variable wrappedInterferogram = groupInterferogramHH.findVariable("wrappedInterferogram");
        variables.put("coherenceMagnitude", coherenceMagnitude);
        variables.put("wrappedInterferogram", wrappedInterferogram);
        final int rasterHeight1 = coherenceMagnitude.getDimension(0).getLength();
        final int rasterWidth1 = coherenceMagnitude.getDimension(1).getLength();

        final Variable alongTrackOffset = groupPixelOffsetsHH.findVariable("alongTrackOffset");
        final Variable correlationSurfacePeak = groupPixelOffsetsHH.findVariable("correlationSurfacePeak");
        final Variable slantRangeOffset = groupPixelOffsetsHH.findVariable("slantRangeOffset");
        variables.put("alongTrackOffset", alongTrackOffset);
        variables.put("correlationSurfacePeak", correlationSurfacePeak);
        variables.put("slantRangeOffset", slantRangeOffset);
        final int rasterHeight2 = alongTrackOffset.getDimension(0).getLength();
        final int rasterWidth2 = alongTrackOffset.getDimension(1).getLength();

        String polStr = "HH";

        try {
            for (String key : variables.keySet()) {
                final Variable var = variables.get(key);
                String unit = var.getUnitsString();
                if (key.equals("wrappedInterferogram")) {
                    createBand("i_ifg_" + polStr, rasterWidth1, rasterHeight1, Unit.REAL, var);
                    createBand("q_ifg_" + polStr, rasterWidth1, rasterHeight1, Unit.IMAGINARY, var);
                } else if (key.equals("coherenceMagnitude")) {
                    createBand(key + "_" + polStr, rasterWidth1, rasterHeight1, Unit.COHERENCE, var);
                } else {
                    createBand(key + "_" + polStr, rasterWidth2, rasterHeight2, unit, var);
                }
            }

        } catch (Exception e) {
            SystemUtils.LOG.severe(e.getMessage());
        }
    }
}