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
package eu.esa.sar.io.nisar;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.io.SARReader;
import eu.esa.sar.io.nisar.subreaders.NisarGCOVProductReader;
import eu.esa.sar.io.nisar.subreaders.NisarGOFFProductReader;
import eu.esa.sar.io.nisar.subreaders.NisarGSLCProductReader;
import eu.esa.sar.io.nisar.subreaders.NisarGUNWProductReader;
import eu.esa.sar.io.nisar.subreaders.NisarRIFGProductReader;
import eu.esa.sar.io.nisar.subreaders.NisarROFFProductReader;
import eu.esa.sar.io.nisar.subreaders.NisarRSLCProductReader;
import eu.esa.sar.io.nisar.subreaders.NisarRUNWProductReader;
import eu.esa.sar.io.nisar.subreaders.NisarSMEProductReader;
import eu.esa.sar.io.nisar.subreaders.NisarSubReader;
import eu.esa.sar.io.nisar.util.NisarXConstants;
import org.esa.snap.core.dataio.ProductReaderPlugIn;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.util.io.FileUtils;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

public class NisarProductReader extends SARReader {

    private NisarSubReader subReader;

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
    protected Product readProductNodesImpl() throws IOException {
        try {
            final Path inputPath = ReaderUtils.getPathFromInput(getInput());
            if(inputPath == null) {
                throw new Exception("Unable to read " + getInput());
            }
            File inputFile = inputPath.toFile();
            String fileName = inputFile.getName().toLowerCase();

            for(final String prefix : NisarXConstants.NISAR_FILE_PREFIXES) {
                if (fileName.startsWith(prefix.toLowerCase())) {
                    if (fileName.endsWith(".xml")) {
                        inputFile = FileUtils.exchangeExtension(inputFile, ".h5");
                        if (!inputFile.exists()) {
                            inputFile = FileUtils.exchangeExtension(inputFile, ".tif");
                        }
                        fileName = inputFile.getName().toLowerCase();
                    }

                    subReader = createSubReader(fileName);
                    break;
                }
            }

            Product product = subReader.readProduct(this, inputFile);

            addCommonSARMetadata(product);
            setQuicklookBandName(product);

            product.getGcpGroup();
            product.setModified(false);

            return product;
        } catch (Exception e) {
            handleReaderException(e);
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        if (subReader != null) {
            subReader.close();
        }
        super.close();
    }

    private NisarSubReader createSubReader(final String fileName) throws Exception {
        if (fileName.contains("_rslc_")) {
            return new NisarRSLCProductReader();
        } else if (fileName.contains("_roff_")) {
            return new NisarROFFProductReader();
        } else if (fileName.contains("_rifg_")) {
            return new NisarRIFGProductReader();
        } else if (fileName.contains("_runw_")) {
            return new NisarRUNWProductReader();
        } else if (fileName.contains("_goff_")) {
            return new NisarGOFFProductReader();
        } else if (fileName.contains("_gslc_")) {
            return new NisarGSLCProductReader();
        } else if (fileName.contains("_gcov_")) {
            return new NisarGCOVProductReader();
        } else if (fileName.contains("_gunw_")) {
            return new NisarGUNWProductReader();
        } else if (fileName.contains("_sme2_")) {
            return new NisarSMEProductReader();
        }
        throw new Exception("NISAR product type not supported: " + fileName);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void readBandRasterDataImpl(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight,
                                          int sourceStepX, int sourceStepY, Band destBand, int destOffsetX,
                                          int destOffsetY, int destWidth, int destHeight, ProductData destBuffer,
                                          ProgressMonitor pm) throws IOException {

        subReader.readBandRasterDataImpl(sourceOffsetX, sourceOffsetY, sourceWidth, sourceHeight,
                    sourceStepX, sourceStepY, destBand, destOffsetX, destOffsetY, destWidth, destHeight, destBuffer, pm);
    }
}
