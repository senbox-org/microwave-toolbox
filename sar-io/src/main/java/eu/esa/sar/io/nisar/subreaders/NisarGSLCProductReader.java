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
import org.esa.snap.core.datamodel.StxFactory;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import ucar.nc2.Attribute;
import ucar.nc2.Group;
import ucar.nc2.Variable;

import java.util.ArrayList;
import java.util.List;

public class NisarGSLCProductReader extends NisarSubReader {

    private static final String[] pols = {"HH", "HV", "VH", "VV"};

    public NisarGSLCProductReader() {
        productType = "GSLC";
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
        for (String pol : pols) {
            Variable variable = groupFrequency.findVariable(pol);
            if (variable != null) {

                Band i = newBand(variable, "i_" + pol, "real", Unit.REAL, 0);
                Band q = newBand(variable, "q_" + pol, "imag", Unit.IMAGINARY, 0);
                ReaderUtils.createVirtualIntensityBand(product, i, q, pol);
                ReaderUtils.createVirtualPhaseBand(product, i, q, pol);
            }
        }
    }

    private Band newBand(Variable var, String bandName, String cpxType, String bandUnit, float nodatavalue) {
        int rasterHeight = var.getDimension(0).getLength();
        int rasterWidth = var.getDimension(1).getLength();
        Band band = createBand(bandName, rasterWidth, rasterHeight, bandUnit, var);
        band.setNoDataValue(nodatavalue);
        band.setNoDataValueUsed(true);

        Attribute minAt = var.attributes().findAttribute("min_" + cpxType + "_value");
        Attribute maxAt = var.attributes().findAttribute("max_" + cpxType + "_value");
        if (minAt == null) minAt = var.attributes().findAttribute("min_value_" + cpxType);
        if (maxAt == null) maxAt = var.attributes().findAttribute("max_value_" + cpxType);
        if (minAt != null && maxAt != null) {
            try {
                double min = minAt.getNumericValue().doubleValue();
                double max = maxAt.getNumericValue().doubleValue();
                band.setStx(new StxFactory().withMinimum(min).withMaximum(max)
                        .withIntHistogram(false).withHistogramBins(new int[512]).create());
            } catch (Exception e) {
                // ignore
            }
        }
        return band;
    }
}
