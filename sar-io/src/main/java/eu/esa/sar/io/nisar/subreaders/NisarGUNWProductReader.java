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
    protected void addBandsForFrequency(Group groupFrequency, String suffix) {
        Group groupInterferogram = groupFrequency.findGroup("unwrappedInterferogram");
        if (groupInterferogram != null) {

            for (Group polGroup : groupInterferogram.getGroups()) {
                String pol = "_" + polGroup.getShortName() + suffix;
                createBand(polGroup, "coherenceMagnitude", "coherenceMagnitude" + pol, Unit.COHERENCE, Float.NaN);
                createBand(polGroup, "connectedComponents", "connectedComponents" + pol, Unit.AMPLITUDE, 255);
                createBand(polGroup, "ionospherePhaseScreen", "ionospherePhaseScreen" + pol, Unit.PHASE, Float.NaN);
                createBand(polGroup, "ionospherePhaseScreenUncertainty", "ionospherePhaseScreenUncertainty" + pol, Unit.PHASE, Float.NaN);
                createBand(polGroup, "losDeformation", "losDeformation" + pol, Unit.METERS, Float.NaN);
                createBand(polGroup, "unwrappedPhase", "unwrappedPhase" + pol, Unit.PHASE, Float.NaN);
            }
        }

        groupInterferogram = groupFrequency.findGroup("wrappedInterferogram");
        if (groupInterferogram != null) {

            for (Group polGroup : groupInterferogram.getGroups()) {
                String pol = "_" + polGroup.getShortName() + suffix;
                createBand(polGroup, "wrappedInterferogram", "wrappedInterferogram" + pol, Unit.PHASE, Float.NaN);
            }
        }

        Group groupPixelOffsets = groupFrequency.findGroup("pixelOffsets");
        if (groupPixelOffsets != null) {

            for (Group polGroup : groupPixelOffsets.getGroups()) {
                String pol = "_" + polGroup.getShortName() + suffix;
                createBand(polGroup, "alongTrackOffset", "alongTrackOffset" + pol, Unit.METERS, Float.NaN);
                createBand(polGroup, "slantRangeOffset", "slantRangeOffset" + pol, Unit.METERS, Float.NaN);
                createBand(polGroup, "correlationSurfacePeak", "correlationSurfacePeak" + pol, Unit.COHERENCE, Float.NaN);
            }
        }
    }
}
