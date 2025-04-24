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

public class NisarGUNWProductReader extends NisarSubReader {

    public NisarGUNWProductReader() {
        productType = "GUNW";
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
        final Group groupUnwInterferogram = groupFrequencyA.findGroup("unwrappedInterferogram");
        final Group[] polGroups = getPolarizationGroups(groupUnwInterferogram);

        for (Group polGroup : polGroups) {
            final Variable coh = polGroup.findVariable("coherenceMagnitude");
            final Variable phase = polGroup.findVariable("unwrappedPhase");

            rasterVariables.add(coh);
            rasterVariables.add(phase);
        }

        return rasterVariables.toArray(new Variable[0]);
    }

    @Override
    protected void addBandsToProduct() {

        final Group groupLSAR = getLSARGroup();
        final Group groupFrequencyA = getFrequencyAGroup(groupLSAR);

        final Group groupInterferogram = groupFrequencyA.findGroup("unwrappedInterferogram");
        Group[] polGroups = getPolarizationGroups(groupInterferogram);
        for (Group polGroup : polGroups) {
            String polStr = polGroup.getShortName();

            final Variable coherenceMagnitude = polGroup.findVariable("coherenceMagnitude");
            final int rasterHeight = coherenceMagnitude.getDimension(0).getLength();
            final int rasterWidth = coherenceMagnitude.getDimension(1).getLength();

            createBand("coherenceMagnitude" + "_" + polStr, rasterWidth, rasterHeight, Unit.COHERENCE, coherenceMagnitude);

            final Variable unwrappedPhase = polGroup.findVariable("unwrappedPhase");
            createBand("unwrappedPhase" + "_" + polStr, rasterWidth, rasterHeight, Unit.PHASE, unwrappedPhase);
        }
    }
}