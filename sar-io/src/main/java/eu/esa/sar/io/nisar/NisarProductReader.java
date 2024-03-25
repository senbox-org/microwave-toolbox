/*
 * Copyright (C) 2024 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.io.nisar;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.io.SARReader;
import eu.esa.sar.io.nisar.util.NisarXConstants;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.dataio.ProductReaderPlugIn;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.core.util.io.FileUtils;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

public class NisarProductReader extends SARReader {

    private String productType = null;
    private ProductReader reader;
    private boolean isSLC = false;

    /**
     * Constructs a new abstract product reader.
     *
     * @param readerPlugIn the reader plug-in which created this reader, can be <code>null</code> for internal reader
     *                     implementations
     */
    public NisarProductReader(final ProductReaderPlugIn readerPlugIn) {
        super(readerPlugIn);
    }

    /**
     * Provides an implementation of the <code>readProductNodes</code> interface method. Clients implementing this
     * method can be sure that the input object and eventually the subset information has already been set.
     * <p/>
     * <p>This method is called as a last step in the <code>readProductNodes(input, subsetInfo)</code> method.
     */
    @Override
    protected Product readProductNodesImpl() {
        try {
            final Path inputPath = ReaderUtils.getPathFromInput(getInput());
            if(inputPath == null) {
                throw new Exception("Unable to read " + getInput());
            }
            File inputFile = inputPath.toFile();
            String fileName = inputFile.getName().toLowerCase();

            if(fileName.startsWith(NisarXConstants.NISAR_FILE_PREFIX.toLowerCase())) {
                if (fileName.endsWith(".xml")) {
                    inputFile = FileUtils.exchangeExtension(inputFile, ".h5");
                    if(!inputFile.exists()) {
                        inputFile = FileUtils.exchangeExtension(inputFile, ".tif");
                    }
                    fileName = inputFile.getName().toLowerCase();
                }

                if (fileName.contains("_rslc_")) {
                    productType = "RSLC";
                    isSLC = true;
                    reader = new NisarRSLCProductReader(getReaderPlugIn());
                } else if (fileName.contains("_roff_")) {
                    productType = "ROFF";
                    reader = new NisarROFFProductReader(getReaderPlugIn());
                } else if (fileName.contains("_rifg_")) {
                    productType = "RIFG";
                    reader = new NisarRIFGProductReader(getReaderPlugIn());
                } else if (fileName.contains("_gslc_")) {
                    productType = "GSLC";
                    reader = new NisarGSLCProductReader(getReaderPlugIn());
                } else if (fileName.contains("_gcov_")) {
                    productType = "GCOV";
                    reader = new NisarGCOVProductReader(getReaderPlugIn());
                }
            }
            if(reader == null) {
                throw new Exception("NISAR product type not supported: " + fileName);
            }
            return reader.readProductNodes(inputFile, getSubsetDef());
        } catch (Exception e) {
            SystemUtils.LOG.severe(e.getMessage());
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        if (reader != null) {
            reader.close();
        }
        super.close();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void readBandRasterDataImpl(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight,
                                          int sourceStepX, int sourceStepY, Band destBand, int destOffsetX,
                                          int destOffsetY, int destWidth, int destHeight, ProductData destBuffer,
                                          ProgressMonitor pm) throws IOException {
        if(isSLC) {
            ((NisarRSLCProductReader)reader).callReadBandRasterData(sourceOffsetX, sourceOffsetY, sourceWidth, sourceHeight,
                    sourceStepX, sourceStepY, destBand, destOffsetX, destOffsetY, destWidth, destHeight, destBuffer, pm);
        } else if(productType.equals("ROFF")) {
            ((NisarROFFProductReader)reader).callReadBandRasterData(sourceOffsetX, sourceOffsetY, sourceWidth, sourceHeight,
                    sourceStepX, sourceStepY, destBand, destOffsetX, destOffsetY, destWidth, destHeight, destBuffer, pm);
        }
    }
}
