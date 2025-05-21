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

import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.engine_utilities.datamodel.Unit;
import ucar.nc2.Group;
import ucar.nc2.Variable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class NisarSMEProductReader extends NisarSubReader {

    public NisarSMEProductReader() {
        productType = "SME2";
    }

    @Override
    protected String getProductType(Group groupID) throws Exception {
        return productType;
    }

    @Override
    protected Group getLSARGroup() {
        return this.netcdfFile.getRootGroup();
    }

    @Override
    protected Group getFrequencyAGroup(final Group groupLSAR) {
        return this.netcdfFile.getRootGroup();
    }

    @Override
    protected Variable[] getRasterVariables(final Group groupFrequencyA) {
        List<Variable> rasterVariables = new ArrayList<>();

        final Variable Waterbody_fraction = groupFrequencyA.findVariable("Waterbody_fraction");
        final Variable landcover = groupFrequencyA.findVariable("Landcover");

        rasterVariables.add(Waterbody_fraction);
        rasterVariables.add(landcover);

        return rasterVariables.toArray(new Variable[0]);
    }

    @Override
    protected void addBandsToProduct() {

        final Group groupLSAR = getLSARGroup();
        final Group groupFrequencyA = getFrequencyAGroup(groupLSAR);

        final Variable Waterbody_fraction = groupFrequencyA.findVariable("Waterbody_fraction");
        final Variable landcover = groupFrequencyA.findVariable("Landcover");

        final int rasterHeight = Waterbody_fraction.getDimension(0).getLength();
        final int rasterWidth = Waterbody_fraction.getDimension(1).getLength();

        createBand("Waterbody_fraction", rasterWidth, rasterHeight, Unit.SOIL_MOISTURE, Waterbody_fraction);

        createBand("landcover", rasterWidth, rasterHeight, Unit.CLASS, landcover);
    }

    @Override
    protected void addAbstractedMetadataHeader(final MetadataElement root) throws Exception {

    }

    @Override
    protected void addTiePointGridsToProduct() throws IOException {

    }
}