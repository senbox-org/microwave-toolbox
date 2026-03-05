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

import org.esa.snap.engine_utilities.datamodel.Unit;
import ucar.nc2.Group;
import ucar.nc2.Variable;

import java.util.ArrayList;
import java.util.List;

public class NisarGOFFProductReader extends NisarSubReader {

    public NisarGOFFProductReader() {
        productType = "GOFF";
    }

    @Override
    protected Group getFrequencyAGroup(final Group groupLSAR) {
        final Group groupProductType = groupLSAR.findGroup(productType);
        final Group groupGrids = groupProductType.findGroup("grids");
        return groupGrids.findGroup("frequencyA");
    }

    @Override
    protected Group getFrequencyBGroup(final Group groupLSAR) {
        final Group groupProductType = groupLSAR.findGroup(productType);
        final Group groupGrids = groupProductType.findGroup("grids");
        return groupGrids.findGroup("frequencyB");
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
    protected void addBandsToProduct() {
        final Group groupSAR = getSARGroup();
        
        Group groupFreqA = getFrequencyAGroup(groupSAR);
        if (groupFreqA != null) {
            addBandsForFrequency(groupFreqA, "");
        }
        
        Group groupFreqB = getFrequencyBGroup(groupSAR);
        if (groupFreqB != null) {
            addBandsForFrequency(groupFreqB, "_S");
        }
    }
    
    private void addBandsForFrequency(Group groupFrequency, String suffix) {
        final Group groupPixelOffsets = groupFrequency.findGroup("pixelOffsets");
        if (groupPixelOffsets == null) return;
        
        for (Group polGroup : groupPixelOffsets.getGroups()) {
            String polStr = polGroup.getShortName();

            // Check for direct variable or layered structure
            Variable slantRangeOffset = polGroup.findVariable("slantRangeOffset");
            Variable azimuthOffset = polGroup.findVariable("azimuthOffset");
            Variable correlationSurfacePeak = polGroup.findVariable("correlationSurfacePeak");
            Variable snr = polGroup.findVariable("snr");
            
            if (slantRangeOffset == null) {
                final Group layer1 = polGroup.findGroup("layer1");
                if (layer1 != null) {
                    slantRangeOffset = layer1.findVariable("slantRangeOffset");
                    azimuthOffset = layer1.findVariable("azimuthOffset");
                    correlationSurfacePeak = layer1.findVariable("correlationSurfacePeak");
                    snr = layer1.findVariable("snr");
                }
            }

            if (slantRangeOffset != null) {
                final int rasterHeight = slantRangeOffset.getDimension(0).getLength();
                final int rasterWidth = slantRangeOffset.getDimension(1).getLength();
                createBand("slantRangeOffset" + "_" + polStr + suffix, rasterWidth, rasterHeight, Unit.NANOSECONDS, slantRangeOffset);
            }
            
            if (azimuthOffset != null) {
                final int rasterHeight = azimuthOffset.getDimension(0).getLength();
                final int rasterWidth = azimuthOffset.getDimension(1).getLength();
                createBand("azimuthOffset" + "_" + polStr + suffix, rasterWidth, rasterHeight, "S", azimuthOffset);
            }
            
            if (correlationSurfacePeak != null) {
                final int rasterHeight = correlationSurfacePeak.getDimension(0).getLength();
                final int rasterWidth = correlationSurfacePeak.getDimension(1).getLength();
                createBand("correlationSurfacePeak" + "_" + polStr + suffix, rasterWidth, rasterHeight, null, correlationSurfacePeak);
            }
            
            if (snr != null) {
                final int rasterHeight = snr.getDimension(0).getLength();
                final int rasterWidth = snr.getDimension(1).getLength();
                createBand("snr" + "_" + polStr + suffix, rasterWidth, rasterHeight, Unit.DB, snr);
            }
        }
    }
}
