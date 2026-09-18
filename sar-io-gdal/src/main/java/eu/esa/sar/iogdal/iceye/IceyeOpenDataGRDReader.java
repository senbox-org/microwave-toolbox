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

import eu.esa.sar.commons.io.SARReader;
import org.esa.snap.core.dataio.ProductReaderPlugIn;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;

/**
 * The detected, ground-range half of the ICEYE Open Data delivery: a single
 * unsigned 16-bit amplitude band, with the DN-to-amplitude scale carried in the
 * COG's GDAL SCALE item.
 *
 * @author Luis Veci
 */
public class IceyeOpenDataGRDReader extends IceyeOpenDataProductReader {

    public IceyeOpenDataGRDReader(final ProductReaderPlugIn readerPlugIn) {
        super(readerPlugIn);
    }

    @Override
    protected void addProductSpecificBands(final Product product, final String polarization,
                                           final String suffix) {
        final Band amplitudeBand = product.getBand(IceyeConstants.amplitude_band_prefix + polarization);
        SARReader.createVirtualIntensityBand(product, amplitudeBand, suffix);
    }
}
