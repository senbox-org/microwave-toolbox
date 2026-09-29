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
            final Path inputPath = getPathFromInput(getInput());
            final boolean biopalEnabled = Boolean.getBoolean(BiomassProductReaderPlugIn.BIOPAL_READER_PROPERTY);

            final File metadataFile;
            if (Files.isDirectory(inputPath)) {
                final File found = readerPlugIn.findMetadataFile(inputPath.toAbsolutePath());
                if (found != null) {
                    metadataFile = found;
                } else if (biopalEnabled) {
                    // BioPAL output has no ESA header; the directory itself is the entry point.
                    metadataFile = inputPath.toFile();
                } else {
                    throw new IOException("No BIOMASS product metadata file found in " + inputPath);
                }
            } else {
                if (!Files.exists(inputPath)) {
                    throw new IOException(inputPath + " not found");
                }
                metadataFile = inputPath.toFile();
            }

            // Route to the correct directory class based on the product prefix.
            // BIO_FP = Level 2 geophysical (Forest Height / Disturbance / Ground Notch / AGB);
            // BIO_S  = Level 1 SAR (SCS, DGM, STA); anything else (only when opted in) = BioPAL.
            final String fname = metadataFile.getName().toLowerCase();
            if (fname.startsWith("bio_fp_")) {
                dataDir = new BiomassL2ProductDirectory(metadataFile);
            } else if (biopalEnabled && !fname.startsWith("bio_s")) {
                dataDir = new BiomassBioPALProductDirectory(metadataFile);
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
            final String previewFolder = dataDir.getRootFolder() + "preview/";

            // L1 ships a single fixed-name quicklook.
            final String l1 = previewFolder + "quick-look.png";
            if (dataDir.exists(l1)) {
                return dataDir.getFile(l1);
            }

            // L2 ships per-layer quicklooks named *_<layer>_ql.png. Prefer the primary
            // geophysical layer over the secondary quality / mask / probability overlays.
            String[] files = null;
            try {
                files = dataDir.listFiles(previewFolder);
            } catch (IOException ignore) {
                // no preview/ folder in this layout
            }
            if (files != null) {
                String fallback = null;
                for (final String name : files) {
                    final String lower = name.toLowerCase();
                    if (!lower.endsWith("_ql.png")) {
                        continue;
                    }
                    if (fallback == null) {
                        fallback = name;
                    }
                    if (!lower.contains("quality") && !lower.contains("cfm") && !lower.contains("probability")) {
                        return dataDir.getFile(previewFolder + name);
                    }
                }
                if (fallback != null) {
                    return dataDir.getFile(previewFolder + fallback);
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
