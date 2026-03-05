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

import eu.esa.sar.io.nisar.util.NisarXConstants;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.util.SystemUtils;
import ucar.nc2.Group;
import ucar.nc2.Variable;

import java.util.ArrayList;
import java.util.List;

public class NisarGCOVProductReader extends NisarSubReader {

    public NisarGCOVProductReader() {
        productType = "GCOV";
    }

    @Override
    protected Variable[] getRasterVariables(final Group groupFrequency) {
        List<Variable> rasterVariables = new ArrayList<>();
        String[] pols = {"HH", "HV", "VH", "VV", "HHHH", "HVHV", "VHVH", "VVVV", "HHHV", "HHVH", "HHVV", "HVVH", "HVVV", "VHVV"};
        
        for (String pol : pols) {
            Variable v = groupFrequency.findVariable(pol);
            if (v != null) {
                rasterVariables.add(v);
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
        String[] pols = {"HH", "HV", "VH", "VV", "HHHH", "HVHV", "VHVH", "VVVV", "HHHV", "HHVH", "HHVV", "HVVH", "HVVV", "VHVV"};
        
        for (String pol : pols) {
            Variable variable = groupFrequency.findVariable(pol);
            if (variable != null) {
                addBand(variable, pol + suffix);
            }
        }
    }
    
    private void addBand(Variable variable, String bandName) {
        int height = variable.getDimension(0).getLength();
        int width = variable.getDimension(1).getLength();
        
        try {
            final Band band = new Band(bandName, ProductData.TYPE_FLOAT32, width, height);
            band.setDescription("Band " + bandName);
            // band.setUnit("CFloat16"); // Assuming complex float? Or just float?
            // GCOV variables are usually complex for cross-terms and real for auto-terms?
            // Or maybe they are all complex?
            // The previous code had "CFloat16" and "i_q" prefix.
            // Let's assume they are handled by readBandRasterDataImpl which handles complex structures.
            
            band.setNoDataValue(0);
            band.setNoDataValueUsed(true);
            product.addBand(band);
            bandMap.put(band, variable);
            
        } catch (Exception e) {
            SystemUtils.LOG.severe(e.getMessage());
        }
    }
}
