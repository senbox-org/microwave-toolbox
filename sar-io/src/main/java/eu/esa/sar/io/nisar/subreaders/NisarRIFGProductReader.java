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
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import ucar.nc2.Group;
import ucar.nc2.Variable;

import java.util.ArrayList;
import java.util.List;

public class NisarRIFGProductReader extends NisarSubReader {

    public NisarRIFGProductReader() {
        productType = "RIFG";
    }

    @Override
    protected Variable[] getRasterVariables(final Group groupFrequency) {
        List<Variable> rasterVariables = new ArrayList<>();
        final Group groupInterferogram = groupFrequency.findGroup("interferogram");
        
        if (groupInterferogram != null) {
            for (Group polGroup : groupInterferogram.getGroups()) {
                final Variable coh = polGroup.findVariable("coherenceMagnitude");
                if (coh != null) {
                    rasterVariables.add(coh);
                }
            }
        }

        return rasterVariables.toArray(new Variable[0]);
    }

    @Override
    protected void addBandsForFrequency(Group groupFrequency, String suffix) {
        final Group groupInterferogram = groupFrequency.findGroup("interferogram");
        final Group groupPixelOffsets = groupFrequency.findGroup("pixelOffsets");
        
        if (groupInterferogram != null) {
            for (Group polGroup : groupInterferogram.getGroups()) {
                String pol = "_" + polGroup.getShortName() + suffix;

                Band i = createBand(polGroup, "wrappedInterferogram", "i_ifg" + pol, Unit.REAL, 0);
                Band q = createBand(polGroup, "wrappedInterferogram", "q_ifg" + pol, Unit.IMAGINARY, 0);
                ReaderUtils.createVirtualIntensityBand(product, i, q, pol);
                ReaderUtils.createVirtualPhaseBand(product, i, q, pol);

                createBand(polGroup, "coherenceMagnitude", "coherenceMagnitude" + pol, Unit.COHERENCE, 0);
            }
        }
        
        if (groupPixelOffsets != null) {
            for (Group polGroup : groupPixelOffsets.getGroups()) {
                String pol = "_" + polGroup.getShortName() + suffix;
                createBand(polGroup, "digitalElevationModel", "digitalElevationModel" + pol, Unit.METERS, Float.NaN);
                createBand(polGroup, "alongTrackOffset", "alongTrackOffset" + pol, Unit.METERS, Float.NaN);
                createBand(polGroup, "slantRangeOffset", "slantRangeOffset" + pol, Unit.METERS, Float.NaN);
                createBand(polGroup, "correlationSurfacePeak", "correlationSurfacePeak" + pol, Unit.COHERENCE, Float.NaN);
            }
        }
    }
}
