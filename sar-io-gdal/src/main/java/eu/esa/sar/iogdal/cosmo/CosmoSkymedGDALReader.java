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
package eu.esa.sar.iogdal.cosmo;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.io.AbstractProductDirectory;
import eu.esa.sar.commons.io.SARProductReaderPlugIn;
import eu.esa.sar.commons.io.SARReader;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Reader for COSMO-SkyMed GeoTIFF products using the GDAL GTiff driver. Handles BigTIFF.
 */
public class CosmoSkymedGDALReader extends SARReader {

    private CosmoSkymedGDALProductDirectory dataDir;

    public CosmoSkymedGDALReader(final SARProductReaderPlugIn readerPlugIn) {
        super(readerPlugIn);
    }

    @Override
    protected Product readProductNodesImpl() throws IOException {
        try {
            final Path inputPath = getPathFromInput(getInput());
            final File inputFile = inputPath.toFile();

            final File metadataFile;
            if (inputFile.getName().toLowerCase().endsWith(".tif")) {
                metadataFile = inputPath.getParent()
                        .resolve(inputFile.getName().replace(".IMG.tif", ".attribs.xml"))
                        .toFile();
            } else {
                metadataFile = inputFile;
            }

            dataDir = new CosmoSkymedGDALProductDirectory(metadataFile);
            dataDir.readProductDirectory();
            final Product product = dataDir.createProduct();

            setQuicklookBandName(product);
            product.getGcpGroup();
            product.setModified(false);

            AbstractProductDirectory.updateProduct(product, null);

            return product;
        } catch (Exception e) {
            handleReaderException(e);
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        if (dataDir != null) {
            dataDir.close();
            dataDir = null;
        }
        super.close();
    }

    @Override
    protected void readBandRasterDataImpl(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight,
                                          int sourceStepX, int sourceStepY, Band destBand, int destOffsetX,
                                          int destOffsetY, int destWidth, int destHeight, ProductData destBuffer,
                                          ProgressMonitor pm) throws IOException {
        // band source images are wired directly via ProductUtils.copyBand(..., copyImage=true);
        // raster reads are served by the underlying GDAL band products.
    }
}
