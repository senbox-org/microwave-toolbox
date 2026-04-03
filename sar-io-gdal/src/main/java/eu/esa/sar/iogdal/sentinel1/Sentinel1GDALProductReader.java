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
package eu.esa.sar.iogdal.sentinel1;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.io.SARProductReaderPlugIn;
import eu.esa.sar.commons.io.SARReader;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.quicklooks.Quicklook;
import org.esa.snap.core.util.SystemUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;


/**
 * The product reader for Sentinel1 products.
 */
public class Sentinel1GDALProductReader extends SARReader {

    private Sentinel1GDALProductDirectory dataDir;

    /**
     * Constructs a new abstract product reader.
     *
     * @param readerPlugIn the reader plug-in which created this reader, can be <code>null</code> for internal reader
     *                     implementations
     */
    public Sentinel1GDALProductReader(final SARProductReaderPlugIn readerPlugIn) {
        super(readerPlugIn);
    }

    @Override
    public void close() throws IOException {
        if (dataDir != null) {
            dataDir.close();
            dataDir = null;
        }
        super.close();
    }

    /**
     * Provides an implementation of the <code>readProductNodes</code> interface method. Clients implementing this
     * method can be sure that the input object and eventually the subset information has already been set.
     * <p>
     * <p>This method is called as a last step in the <code>readProductNodes(input, subsetInfo)</code> method.
     *
     * @throws IOException if an I/O error occurs
     */
    @Override
    protected Product readProductNodesImpl() throws IOException {
        try {
            Path inputPath = getPathFromInput(getInput());
            if(Files.isDirectory(inputPath)) {
                inputPath = inputPath.resolve(Sentinel1GDALProductReaderPlugIn.PRODUCT_HEADER_NAME);
            }
            if(!Files.exists(inputPath)) {
                throw new IOException(inputPath + " not found");
            }

            File metadataFile = inputPath.toFile();

            dataDir = new Sentinel1GDALProductDirectory(metadataFile);
            dataDir.readProductDirectory();
            final Product product = dataDir.createProduct();

            addCommonSARMetadata(product);
            product.getGcpGroup();
            product.setFileLocation(metadataFile);
            product.setProductReader(this);

            setQuicklookBandName(product);
            addQuicklook(product, Quicklook.DEFAULT_QUICKLOOK_NAME, getQuicklookFile());
            product.setModified(false);

            return product;
        } catch (Throwable e) {
            handleReaderException(e);
        }
        return null;
    }

    private File getQuicklookFile() {
        try {
            if (dataDir.exists(dataDir.getRootFolder() + "preview/quick-look.png")) {
                return dataDir.getFile(dataDir.getRootFolder() + "preview/quick-look.png");
            }
        } catch (IOException e) {
            SystemUtils.LOG.severe("Unable to load quicklook " + dataDir.getProductName());
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void readBandRasterDataImpl(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight,
                                          int sourceStepX, int sourceStepY, Band destBand, int destOffsetX,
                                          int destOffsetY, int destWidth, int destHeight, ProductData destBuffer,
                                          ProgressMonitor pm) {
    }
}
