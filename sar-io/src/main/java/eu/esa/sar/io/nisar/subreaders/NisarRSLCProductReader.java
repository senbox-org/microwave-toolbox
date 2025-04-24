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

import eu.esa.sar.io.netcdf.NcRasterDim;
import eu.esa.sar.io.netcdf.NetCDFUtils;
import eu.esa.sar.io.nisar.util.NisarXConstants;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import ucar.nc2.Group;
import ucar.nc2.Variable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NisarRSLCProductReader extends NisarSubReader {

    public NisarRSLCProductReader() {
        productType = "RSLC";
    }

    @Override
    protected Variable[] getRasterVariables(final Group groupFrequencyA) {
        List<Variable> rasterVariables = new ArrayList<>();

        final Variable hh = groupFrequencyA.findVariable("HH");
        final Variable hv = groupFrequencyA.findVariable("HV");
        final Variable vh = groupFrequencyA.findVariable("VH");
        final Variable vv = groupFrequencyA.findVariable("VV");

        if (hh != null) {
            rasterVariables.add(hh);
        } else if (hv != null) {
            rasterVariables.add(hv);
        } else if (vh != null) {
            rasterVariables.add(vh);
        } else if (vv != null) {
            rasterVariables.add(vv);
        }

        return rasterVariables.toArray(new Variable[0]);
    }

    @Override
    protected void addBandsToProduct() {

        int cnt = 1;
        Map<String, Variable> variables = new HashMap<>();
        final Group groupScience = this.netcdfFile.getRootGroup().findGroup("science");
        final Group groupLSAR = groupScience.findGroup("LSAR");
        final Group groupRSLC = groupLSAR.findGroup("RSLC");
        final Group groupSwaths = groupRSLC.findGroup("swaths");
        final Group groupFrequencyA = groupSwaths.findGroup("frequencyA");

        int width = 0, height = 0;
        final Map<NcRasterDim, List<Variable>> groupFrequencyAVariableListMap = NetCDFUtils.getVariableListMap(groupFrequencyA);
        final NcRasterDim rasterDim2 = NetCDFUtils.getBestRasterDim(groupFrequencyAVariableListMap);
        final Variable[] rasterVariables2 = NetCDFUtils.getRasterVariables(groupFrequencyAVariableListMap, rasterDim2);

        final Map<NcRasterDim, List<Variable>> variableListMap = NetCDFUtils.getVariableListMap(netcdfFile.getRootGroup());
        final NcRasterDim rasterDim = NetCDFUtils.getBestRasterDim(variableListMap);
        final Variable[] rasterVariables = NetCDFUtils.getRasterVariables(variableListMap, rasterDim);


//        Variable variable = rasterVariables2[0];
//        width = variable.getDimension(0).getLength();
//        height = variable.getDimension(1).getLength();
//        Band band = NetCDFUtils.createBand(variable, width, height, ProductData.TYPE_FLOAT32);
//
//        band.setUnit(Unit.REAL);
//        band.setNoDataValue(0);
//        band.setNoDataValueUsed(true);
//        product.addBand(band);
//        bandMap.put(band, variable);

        final Variable hh = groupFrequencyA.findVariable("HH");
        final Variable hv = groupFrequencyA.findVariable("HV");
        final Variable vh = groupFrequencyA.findVariable("VH");
        final Variable vv = groupFrequencyA.findVariable("VV");

        String polStr = "";
        Variable rasterVariable = null;
        if (hh != null) {
            rasterVariable = hh;
            polStr = "HH";
        } else if (hv != null) {
            rasterVariable = hv;
            polStr = "HV";
        } else if (vh != null) {
            rasterVariable = vh;
            polStr = "VH";
        } else if (vv != null) {
            rasterVariable = vv;
            polStr = "VV";
        }

        variables.put(NisarXConstants.I_Q, rasterVariable);
        height = rasterVariable.getDimension(0).getLength();
        width = rasterVariable.getDimension(1).getLength();

//        final NcAttributeMap attMap = NcAttributeMap.create(variables.get(NisarXConstants.I_Q));

        final Variable coordinateX = groupFrequencyA.findVariable("coordinateX");

        try {
            final Band bandI = new Band("i_" + polStr, ProductData.TYPE_FLOAT32, width, height);
            bandI.setDescription("I band of the focused SLC image (HH)");
            bandI.setUnit(Unit.REAL);
            bandI.setNoDataValue(0);
            bandI.setNoDataValueUsed(true);
            product.addBand(bandI);
            bandMap.put(bandI, variables.get(NisarXConstants.I_Q));

            final Band bandQ = new Band("q_" + polStr, ProductData.TYPE_FLOAT32, width, height);
            bandI.setDescription("Q band of the focused SLC image (HH)");
            bandQ.setUnit(Unit.IMAGINARY);
            bandQ.setNoDataValue(0);
            bandQ.setNoDataValueUsed(true);
            product.addBand(bandQ);
            bandMap.put(bandQ, variables.get(NisarXConstants.I_Q));

            ReaderUtils.createVirtualIntensityBand(product, bandI, bandQ, polStr);

        } catch (Exception e) {
            SystemUtils.LOG.severe(e.getMessage());

        }
    }
}