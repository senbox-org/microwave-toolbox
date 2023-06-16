/*
 * Copyright (C) 2023 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.io.cosmo;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.dataio.ProductReaderPlugIn;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * The product reader for CosmoSkymed products.
 */
public class CosmoSkymedGeoTiffReader implements CosmoSkymedReader.CosmoReader {

    private final ProductReaderPlugIn readerPlugIn;
    private final CosmoSkymedReader reader;

    private CosmoSkymedProductDirectory dataDir = null;

    public CosmoSkymedGeoTiffReader(final ProductReaderPlugIn readerPlugIn, final CosmoSkymedReader reader) {
        this.readerPlugIn = readerPlugIn;
        this.reader = reader;
    }

    @Override
    public void close() throws IOException {
        if(dataDir != null) {
            dataDir.close();
        }
    }

    @Override
    public Product createProduct(final Path inputPath) throws Exception {

        File file = inputPath.toFile();
        File imageFile = null, metadataFile = null;
        if(file.getName().endsWith(".tif")) {
            imageFile = file;
            metadataFile = new File(file.getParentFile(), file.getName().replace(".IMG.tif", ".attribs.xml"));
        } else if(file.getName().endsWith(".xml")) {
            metadataFile = file;
            imageFile = new File(file.getParentFile(), file.getName().replace(".attribs.xml", ".IMG.tif"));
        }

        CosmoSkymedProductDirectory dataDir = new CosmoSkymedProductDirectory(this, metadataFile);
        dataDir.readProductDirectory();
        final Product product = dataDir.createProduct();

        return product;
    }

    @Override
    public void readBandRasterDataImpl(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight,
                                          int sourceStepX, int sourceStepY, Band destBand, int destOffsetX,
                                          int destOffsetY, int destWidth, int destHeight, ProductData destBuffer,
                                          ProgressMonitor pm) throws IOException {

    }
}
