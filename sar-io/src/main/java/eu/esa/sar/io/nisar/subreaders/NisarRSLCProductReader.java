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

public class NisarRSLCProductReader extends NisarSubReader {

    private static final String[] pols = {"HH", "HV", "VH", "VV"};

    public NisarRSLCProductReader() {
        productType = "RSLC";
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

        // Fallback: check for polarization variables inside subgroups
        if (rasterVariables.isEmpty()) {
            for (Group subGroup : groupFrequency.getGroups()) {
                String name = subGroup.getShortName();
                for (String pol : pols) {
                    if (name.equals(pol)) {
                        // Look for the SLC dataset inside the polarization group
                        Variable v = subGroup.findVariable(pol);
                        if (v == null) {
                            // Try common variable names inside the polarization subgroup
                            List<Variable> vars = subGroup.getVariables();
                            for (Variable var : vars) {
                                if (var.getRank() >= 2) {
                                    v = var;
                                    break;
                                }
                            }
                        }
                        if (v != null) {
                            rasterVariables.add(v);
                        }
                    }
                }
            }
        }

        return rasterVariables.toArray(new Variable[0]);
    }

    @Override
    protected void addBandsForFrequency(Group groupFrequency, String suffix) {
        for (String polarization : pols) {
            Variable variable = findPolarizationVariable(groupFrequency, polarization);
            if (variable != null) {
                String pol = '_'+polarization+suffix;

                Band i = newBand(variable, "i" + pol, "real", Unit.REAL, 0);
                Band q = newBand(variable, "q" + pol, "imag", Unit.IMAGINARY, 0);
                ReaderUtils.createVirtualIntensityBand(product, i, q, pol);
                ReaderUtils.createVirtualPhaseBand(product, i, q, pol);
            }
        }
    }

    private Variable findPolarizationVariable(Group groupFrequency, String polarization) {
        // First try direct variable
        Variable v = groupFrequency.findVariable(polarization);
        if (v != null) {
            return v;
        }
        // Fallback: look inside a polarization subgroup
        Group polGroup = groupFrequency.findGroup(polarization);
        if (polGroup != null) {
            v = polGroup.findVariable(polarization);
            if (v != null) {
                return v;
            }
            // Try first 2D+ variable in the group
            for (Variable var : polGroup.getVariables()) {
                if (var.getRank() >= 2) {
                    return var;
                }
            }
        }
        return null;
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
