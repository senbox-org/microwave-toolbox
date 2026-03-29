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

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.StxFactory;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.datamodel.Unit;
import ucar.nc2.Group;
import ucar.nc2.Variable;

import java.util.ArrayList;
import java.util.List;

public class NisarGCOVProductReader extends NisarSubReader {

    public NisarGCOVProductReader() {
        productType = "GCOV";
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
    protected void addBandsForFrequency(Group groupFrequency, String suffix) {
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
            band.setUnit(Unit.INTENSITY);
            band.setNoDataValue(0);
            band.setNoDataValueUsed(true);
            product.addBand(band);
            bandMap.put(band, variable);
            setStxFromAttributes(band, variable);
            if (!band.isStxSet()) {
                band.setStx(new StxFactory().withMinimum(0).withMaximum(1)
                        .withIntHistogram(false).withHistogramBins(new int[512]).create());
            }

        } catch (Exception e) {
            SystemUtils.LOG.severe(e.getMessage());
        }
    }
}
