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
package eu.esa.sar.iogdal.iceye;

import org.esa.snap.core.dataio.ProductReaderPlugIn;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.VirtualBand;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;

import java.io.IOException;

/**
 * The complex half of the ICEYE Open Data delivery.
 * <p>
 * The SLC COG holds two 32-bit float samples per pixel, named {@code AMPLITUDE}
 * and {@code PHASE} by the COG's own GDAL band metadata - not real and
 * imaginary. i and q are therefore virtual bands derived from them, which is
 * what the rest of SNAP's interferometric chain expects to find.
 *
 * @author Luis Veci
 */
public class IceyeOpenDataSLCReader extends IceyeOpenDataProductReader {

    public IceyeOpenDataSLCReader(final ProductReaderPlugIn readerPlugIn) {
        super(readerPlugIn);
    }

    @Override
    protected void addProductSpecificBands(final Product product, final String polarization,
                                           final String suffix) throws IOException {
        final String amplitudeName = IceyeConstants.amplitude_band_prefix + polarization;
        final String phaseName = IceyeConstants.phase_band_prefix + polarization;

        addTransposedBand(product, phaseName, IceyeConstants.PHASE_BAND_INDEX, Unit.PHASE);

        final Band iBand = new VirtualBand(IceyeConstants.i_band_prefix + polarization,
                ProductData.TYPE_FLOAT32, rangeSamples, azimuthLines,
                amplitudeName + " * cos(" + phaseName + ')');
        iBand.setUnit(Unit.REAL);
        iBand.setNoDataValue(0);
        iBand.setNoDataValueUsed(true);
        product.addBand(iBand);

        final Band qBand = new VirtualBand(IceyeConstants.q_band_prefix + polarization,
                ProductData.TYPE_FLOAT32, rangeSamples, azimuthLines,
                amplitudeName + " * sin(" + phaseName + ')');
        qBand.setUnit(Unit.IMAGINARY);
        qBand.setNoDataValue(0);
        qBand.setNoDataValueUsed(true);
        product.addBand(qBand);

        ReaderUtils.createVirtualIntensityBand(product, iBand, qBand, suffix);
    }
}
