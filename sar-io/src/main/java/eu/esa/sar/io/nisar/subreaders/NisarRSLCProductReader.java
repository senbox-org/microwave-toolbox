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
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
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

        return rasterVariables.toArray(new Variable[0]);
    }

    @Override
    protected void addBandsForFrequency(Group groupFrequency, String suffix) {
        for (String pol : pols) {
            Variable variable = groupFrequency.findVariable(pol);
            if (variable != null) {
                addBand(variable, pol, suffix);
            }
        }
    }
    
    private void addBand(Variable variable, String polStr, String suffix) {
        int height = variable.getDimension(0).getLength();
        int width = variable.getDimension(1).getLength();

        try {
            final Band bandI = new Band("i_" + polStr + suffix, ProductData.TYPE_FLOAT32, width, height);
            bandI.setDescription("I band of the focused SLC image (" + polStr + ")");
            bandI.setUnit(Unit.REAL);
            bandI.setNoDataValue(0);
            bandI.setNoDataValueUsed(true);
            // Pre-set statistics to prevent SNAP from scanning all tiles on first display.
            bandI.setStx(new StxFactory().withMinimum(-1000.0).withMaximum(1000.0)
                    .withIntHistogram(false).withHistogramBins(new int[512]).create());
            product.addBand(bandI);
            bandMap.put(bandI, variable);

            final Band bandQ = new Band("q_" + polStr + suffix, ProductData.TYPE_FLOAT32, width, height);
            bandQ.setDescription("Q band of the focused SLC image (" + polStr + ")");
            bandQ.setUnit(Unit.IMAGINARY);
            bandQ.setNoDataValue(0);
            bandQ.setNoDataValueUsed(true);
            bandQ.setStx(new StxFactory().withMinimum(-1000.0).withMaximum(1000.0)
                    .withIntHistogram(false).withHistogramBins(new int[512]).create());
            product.addBand(bandQ);
            bandMap.put(bandQ, variable);

            ReaderUtils.createVirtualIntensityBand(product, bandI, bandQ, polStr + suffix);

        } catch (Exception e) {
            SystemUtils.LOG.severe(e.getMessage());
        }
    }
}
