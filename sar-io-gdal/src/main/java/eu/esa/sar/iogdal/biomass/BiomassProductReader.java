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
package eu.esa.sar.iogdal.biomass;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.io.SARProductReaderPlugIn;
import eu.esa.sar.commons.io.XMLProductDirectory;
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
 * The product reader for BIOMASS products. Routes to the appropriate product-directory
 * class based on the file prefix:
 * <ul>
 *     <li>{@code BIO_S} → L1 (SCS, DGM, STA) via {@link BiomassProductDirectory}</li>
 *     <li>{@code BIO_FP} → L2 (FH, FD, GN, AGB) via {@link BiomassL2ProductDirectory}</li>
 * </ul>
 */
public class BiomassProductReader extends SARReader {

    private XMLProductDirectory dataDir;
    private final SARProductReaderPlugIn readerPlugIn;

    /**
     * Constructs a new abstract product reader.
     *
     * @param readerPlugIn the reader plug-in which created this reader, can be <code>null</code> for internal reader
     *                     implementations
     */
    public BiomassProductReader(final SARProductReaderPlugIn readerPlugIn) {
        super(readerPlugIn);
        this.readerPlugIn = readerPlugIn;
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
                inputPath = readerPlugIn.findMetadataFile(inputPath.toAbsolutePath()).toPath();
            }
            if(!Files.exists(inputPath)) {
                throw new IOException(inputPath + " not found");
            }

            File metadataFile = inputPath.toFile();

            // Route to the correct directory class based on the product prefix.
            // BIO_FP = Level 2 geophysical (Forest Height / Disturbance / Ground Notch / AGB);
            // BIO_S  = Level 1 SAR (SCS, DGM, STA).
            final String fname = metadataFile.getName().toLowerCase();
            if (fname.startsWith("bio_fp_")) {
                dataDir = new BiomassL2ProductDirectory(metadataFile);
            } else {
                dataDir = new BiomassProductDirectory(metadataFile);
            }
            dataDir.readProductDirectory();
            final Product product = dataDir.createProduct();

            // Match resolution levels for COG pyramid compatibility with virtual bands
            if (product.getNumBands() > 0 && product.getBandAt(0).isSourceImageSet()) {
                product.setNumResolutionsMax(product.getBandAt(0).getSourceImage().getModel().getLevelCount());
            }

            addCommonSARMetadata(product);
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
            // Try common quicklook filenames in preview/ — L1 uses "quick-look.png", L2 uses *_ql.png.
            final String[] candidates = { "quick-look.png" };
            for (final String name : candidates) {
                final String path = dataDir.getRootFolder() + "preview/" + name;
                if (dataDir.exists(path)) {
                    return dataDir.getFile(path);
                }
            }
        } catch (IOException e) {
            SystemUtils.LOG.severe("Unable to load BIOMASS quicklook: " + e.getMessage());
        }
        return null;
    }

    /**
     * BIOMASS bands are wired directly to GDAL-backed source images in the directory
     * class ({@link BiomassProductDirectory#addBands}), so JAI tile reads come from the
     * source-image path and never enter this method. Anyone calling
     * {@code band.readRasterData()} on a band that lacks a wired source image is asking
     * for uninitialised data — fail loudly instead.
     */
    @Override
    protected void readBandRasterDataImpl(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight,
                                          int sourceStepX, int sourceStepY, Band destBand, int destOffsetX,
                                          int destOffsetY, int destWidth, int destHeight, ProductData destBuffer,
                                          ProgressMonitor pm) {
        throw new UnsupportedOperationException(
                "BiomassProductReader.readBandRasterDataImpl was invoked for band '" + destBand.getName() +
                "'. BIOMASS bands should be read via their wired source image; this code path indicates a " +
                "configuration problem (missing setSourceImage in the directory's addBands).");
    }
}
