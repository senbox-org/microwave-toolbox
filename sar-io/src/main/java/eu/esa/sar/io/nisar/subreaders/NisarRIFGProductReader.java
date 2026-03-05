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

public class NisarRIFGProductReader extends NisarSubReader {

    public NisarRIFGProductReader() {
        productType = "RIFG";
    }

    @Override
    protected Variable[] getRasterVariables(final Group groupFrequency) {
        List<Variable> rasterVariables = new ArrayList<>();
        final Group groupInterferogram = groupFrequency.findGroup("interferogram");
        
        if (groupInterferogram != null) {
            for (Group polGroup : groupInterferogram.getGroups()) {
                final Variable coh = polGroup.findVariable("coherenceMagnitude");
                if (coh != null) {
                    rasterVariables.add(coh);
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
        final Group groupInterferogram = groupFrequency.findGroup("interferogram");
        final Group groupPixelOffsets = groupFrequency.findGroup("pixelOffsets");
        
        if (groupInterferogram != null) {
            for (Group polGroup : groupInterferogram.getGroups()) {
                String polStr = polGroup.getShortName();
                
                Variable coherenceMagnitude = polGroup.findVariable("coherenceMagnitude");
                if (coherenceMagnitude != null) {
                    int h = coherenceMagnitude.getDimension(0).getLength();
                    int w = coherenceMagnitude.getDimension(1).getLength();
                    createBand("coherenceMagnitude_" + polStr + suffix, w, h, Unit.COHERENCE, coherenceMagnitude);
                }
                
                Variable wrappedInterferogram = polGroup.findVariable("wrappedInterferogram");
                if (wrappedInterferogram != null) {
                    int h = wrappedInterferogram.getDimension(0).getLength();
                    int w = wrappedInterferogram.getDimension(1).getLength();
                    createBand("i_ifg_" + polStr + suffix, w, h, Unit.REAL, wrappedInterferogram);
                    createBand("q_ifg_" + polStr + suffix, w, h, Unit.IMAGINARY, wrappedInterferogram);
                }
            }
        }
        
        if (groupPixelOffsets != null) {
            for (Group polGroup : groupPixelOffsets.getGroups()) {
                String polStr = polGroup.getShortName();
                
                addOffsetBand(polGroup, "alongTrackOffset", polStr, suffix);
                addOffsetBand(polGroup, "slantRangeOffset", polStr, suffix);
                addOffsetBand(polGroup, "correlationSurfacePeak", polStr, suffix);
            }
        }
    }
    
    private void addOffsetBand(Group group, String varName, String polStr, String suffix) {
        Variable var = group.findVariable(varName);
        if (var != null) {
            int h = var.getDimension(0).getLength();
            int w = var.getDimension(1).getLength();
            String unit = var.getUnitsString();
            createBand(varName + "_" + polStr + suffix, w, h, unit, var);
        }
    }
}
