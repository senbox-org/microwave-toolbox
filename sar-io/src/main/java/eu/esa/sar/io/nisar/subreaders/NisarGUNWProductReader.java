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
    protected Group getFrequencyBGroup(final Group groupLSAR) {
        final Group groupProductType = groupLSAR.findGroup(productType);
        final Group groupGrids = groupProductType.findGroup("grids");
        return groupGrids.findGroup("frequencyB");
    }

    @Override
    protected Variable[] getRasterVariables(final Group groupFrequency) {
        List<Variable> rasterVariables = new ArrayList<>();
        final Group groupUnwInterferogram = groupFrequency.findGroup("unwrappedInterferogram");
        
        if (groupUnwInterferogram != null) {
            for (Group polGroup : groupUnwInterferogram.getGroups()) {
                final Variable coh = polGroup.findVariable("coherenceMagnitude");
                final Variable phase = polGroup.findVariable("unwrappedPhase");

                if (coh != null) rasterVariables.add(coh);
                if (phase != null) rasterVariables.add(phase);
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
        final Group groupInterferogram = groupFrequency.findGroup("unwrappedInterferogram");
        if (groupInterferogram == null) return;
        
        for (Group polGroup : groupInterferogram.getGroups()) {
            String polStr = polGroup.getShortName();

            final Variable coherenceMagnitude = polGroup.findVariable("coherenceMagnitude");
            if (coherenceMagnitude != null) {
                final int rasterHeight = coherenceMagnitude.getDimension(0).getLength();
                final int rasterWidth = coherenceMagnitude.getDimension(1).getLength();
                createBand("coherenceMagnitude" + "_" + polStr + suffix, rasterWidth, rasterHeight, Unit.COHERENCE, coherenceMagnitude);
            }

            final Variable unwrappedPhase = polGroup.findVariable("unwrappedPhase");
            if (unwrappedPhase != null) {
                final int rasterHeight = unwrappedPhase.getDimension(0).getLength();
                final int rasterWidth = unwrappedPhase.getDimension(1).getLength();
                createBand("unwrappedPhase" + "_" + polStr + suffix, rasterWidth, rasterHeight, Unit.PHASE, unwrappedPhase);
            }
            
            final Variable connectedComponents = polGroup.findVariable("connectedComponents");
            if (connectedComponents != null) {
                final int rasterHeight = connectedComponents.getDimension(0).getLength();
                final int rasterWidth = connectedComponents.getDimension(1).getLength();
                createBand("connectedComponents" + "_" + polStr + suffix, rasterWidth, rasterHeight, Unit.AMPLITUDE, connectedComponents);
            }
        }
    }
}
