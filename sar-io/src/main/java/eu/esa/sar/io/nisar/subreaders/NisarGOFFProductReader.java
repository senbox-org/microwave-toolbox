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
    protected Variable[] getRasterVariables(final Group groupFrequencyA) {
        List<Variable> rasterVariables = new ArrayList<>();
        final Group groupPixelOffsets = groupFrequencyA.findGroup("pixelOffsets");
        Group[] polGroups = getPolarizationGroups(groupPixelOffsets);

        for (Group polGroup : polGroups) {
            Variable offset = polGroup.findVariable("slantRangeOffset");
            if (offset == null) {
                final Group layer1 = polGroup.findGroup("layer1");
                offset = layer1.findVariable("slantRangeOffset");
            }
            rasterVariables.add(offset);
        }

        return rasterVariables.toArray(new Variable[0]);
    }

    @Override
    protected void addBandsToProduct() {

        final Group groupLSAR = getLSARGroup();
        final Group groupFrequencyA = getFrequencyAGroup(groupLSAR);

        final Group groupPixelOffsets = groupFrequencyA.findGroup("pixelOffsets");
        Group[] polGroups = getPolarizationGroups(groupPixelOffsets);
        for (Group polGroup : polGroups) {
            String polStr = polGroup.getShortName();

            final Group layer1 = polGroup.findGroup("layer1");
            final Variable slantRangeOffset = layer1.findVariable("slantRangeOffset");
            final int rasterHeight = slantRangeOffset.getDimension(0).getLength();
            final int rasterWidth = slantRangeOffset.getDimension(1).getLength();

            createBand("slantRangeOffset" + "_" + polStr, rasterWidth, rasterHeight, Unit.NANOSECONDS, slantRangeOffset);

        }
    }
}