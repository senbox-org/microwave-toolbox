/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. http://www.skywatch.com
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

import org.esa.snap.engine_utilities.datamodel.Unit;
import ucar.nc2.Group;
import ucar.nc2.Variable;

import java.util.ArrayList;
import java.util.List;

public class NisarROFFProductReader extends NisarSubReader {

    public NisarROFFProductReader() {
        productType = "ROFF";
    }

    @Override
    protected Variable[] getRasterVariables(final Group groupFrequency) {
        List<Variable> rasterVariables = new ArrayList<>();
        final Group groupPixelOffsets = groupFrequency.findGroup("pixelOffsets");
        
        if (groupPixelOffsets != null) {
            for (Group polGroup : groupPixelOffsets.getGroups()) {
                Variable offset = polGroup.findVariable("slantRangeOffset");
                if (offset == null) {
                    final Group layer1 = polGroup.findGroup("layer1");
                    if (layer1 != null) {
                        offset = layer1.findVariable("slantRangeOffset");
                    }
                }
                if (offset != null) {
                    rasterVariables.add(offset);
                }
            }
        }

        return rasterVariables.toArray(new Variable[0]);
    }

    @Override
    protected void addBandsForFrequency(Group groupFrequency, String suffix) {
        final Group groupPixelOffsets = groupFrequency.findGroup("pixelOffsets");
        if (groupPixelOffsets != null) {

            for (Group polGroup : groupPixelOffsets.getGroups()) {
                String pol = "_" + polGroup.getShortName() + suffix;

                // Check for layers
                for (Group layerGroup : polGroup.getGroups()) {
                    String layerName = layerGroup.getShortName();
                    if (layerName.startsWith("layer")) {
                        String prefix = layerName.replace("layer", "L");
                        addLayerBands(layerGroup, prefix, pol);
                    }
                }

                // Check for variables directly in polGroup (if any)
                addLayerBands(polGroup, "", pol);
            }
        }
    }
    
    private void addLayerBands(Group group, String prefix, String pol) {

        newBand(group, "alongTrackOffset", prefix, pol, Unit.METERS, Float.NaN);
        newBand(group, "alongTrackOffsetVariance", prefix, pol, Unit.METERS, Float.NaN);
        newBand(group, "slantRangeOffset", prefix, pol, Unit.METERS, Float.NaN);
        newBand(group, "slantRangeOffsetVariance", prefix, pol, Unit.METERS, Float.NaN);
        newBand(group, "correlationSurfacePeak", prefix, pol, Unit.COHERENCE, Float.NaN);
        newBand(group, "crossOffsetVariance", prefix, pol, Unit.AMPLITUDE, Float.NaN);
        newBand(group, "snr", prefix, pol, Unit.AMPLITUDE, Float.NaN);
    }

    private void newBand(Group polGroup, String variableName, String prefix, String pol, String unit, float nodatavalue) {
        String bandName = (prefix.isEmpty() ? "" : prefix + "_") + variableName + pol;
        createBand(polGroup, variableName, bandName, unit, nodatavalue);
    }
}
