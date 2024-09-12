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
package eu.esa.sar.io.sentinel1;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.io.SARReader;
import org.esa.snap.core.dataio.ProductReaderPlugIn;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The product reader for Sentinel1 ETAD products.
 */
public class Sentinel1ETADProductReader extends SARReader {

    protected Sentinel1Directory dataDir = null;

    /**
     * Constructs a new abstract product reader.
     *
     * @param readerPlugIn the reader plug-in which created this reader, can be <code>null</code> for internal reader
     *                     implementations
     */
    public Sentinel1ETADProductReader(final ProductReaderPlugIn readerPlugIn) {
        super(readerPlugIn);
    }

    /**
     * Closes the access to all currently opened resources such as file input streams and all resources of this children
     * directly owned by this reader. Its primary use is to allow the garbage collector to perform a vanilla job.
     * <p>
     * <p>This method should be called only if it is for sure that this object instance will never be used again. The
     * results of referencing an instance of this class after a call to <code>close()</code> are undefined.
     * <p>
     * <p>Overrides of this method should always call <code>super.close();</code> after disposing this instance.
     *
     * @throws IOException if an I/O error occurs
     */
    @Override
    public void close() throws IOException {
        super.close();
        if (dataDir != null) {
            dataDir.close();
            dataDir = null;
        }
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
                inputPath = inputPath.resolve(Sentinel1ProductReaderPlugIn.PRODUCT_HEADER_NAME);
            }
            if(!Files.exists(inputPath)) {
                throw new IOException(inputPath + " not found");
            }

            dataDir = new Sentinel1ETADDirectory(inputPath.toFile());
            dataDir.readProductDirectory();
            final Product product = dataDir.createProduct();
            product.setFileLocation(inputPath.toFile());
            product.setProductReader(this);
            addCommonSARMetadata(product);

            setQuicklookBandName(product);
            setBandGrouping(product);

            product.setModified(false);

            return product;
        } catch (Exception e) {
            handleReaderException(e);
        }

        return null;
    }

    private void setBandGrouping(final Product product) {
        MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        String mode = absRoot.getAttributeString(AbstractMetadata.ACQUISITION_MODE);
        if (product.getProductType().equals("ETAD") && mode != null) {
            if (mode.equals("IW")) {
                product.setAutoGrouping("IW1:IW2:IW3");
            } else if (mode.equals("EW")) {
                product.setAutoGrouping("EW1:EW2:EW3:EW4:EW5");
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void readBandRasterDataImpl(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight,
                                          int sourceStepX, int sourceStepY, Band destBand, int destOffsetX,
                                          int destOffsetY, int destWidth, int destHeight, ProductData destBuffer,
                                          ProgressMonitor pm) throws IOException {

        final Sentinel1ETADDirectory s1L1Dir = (Sentinel1ETADDirectory) dataDir;
        s1L1Dir.getEtadReader().readData(sourceOffsetX, sourceOffsetY, sourceWidth, sourceHeight,
                                            sourceStepX, sourceStepY, destBand, destOffsetX,
                                            destOffsetY, destWidth, destHeight, destBuffer);
    }
}
