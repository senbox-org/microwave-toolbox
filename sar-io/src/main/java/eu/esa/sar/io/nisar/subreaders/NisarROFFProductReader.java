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
            
            // Check for layers
            for (Group layerGroup : polGroup.getGroups()) {
                String layerName = layerGroup.getShortName();
                if (layerName.startsWith("layer")) {
                    String prefix = layerName.replace("layer", "L");
                    addLayerBands(layerGroup, prefix, polStr, suffix);
                }
            }
            
            // Check for variables directly in polGroup (if any)
            addLayerBands(polGroup, "", polStr, suffix);
        }
    }
    
    private void addLayerBands(Group group, String prefix, String polStr, String suffix) {
        String[] varNames = {
            "alongTrackOffset", "alongTrackOffsetVariance", 
            "correlationSurfacePeak", "crossOffsetVariance", 
            "slantRangeOffset", "slantRangeOffsetVariance", "snr"
        };
        
        for (String varName : varNames) {
            Variable var = group.findVariable(varName);
            if (var != null) {
                int h = var.getDimension(0).getLength();
                int w = var.getDimension(1).getLength();
                
                String bandName = (prefix.isEmpty() ? "" : prefix + "_") + varName + "_" + polStr + suffix;
                
                try {
                    final Band band = new Band(bandName, ProductData.TYPE_FLOAT32, w, h);
                    band.setDescription(var.getDescription());
                    band.setUnit(var.getUnitsString());
                    band.setNoDataValue(0);
                    band.setNoDataValueUsed(true);
                    product.addBand(band);
                    bandMap.put(band, var);
                } catch (Exception e) {
                    SystemUtils.LOG.severe(e.getMessage());
                }
            }
        }
    }
}
