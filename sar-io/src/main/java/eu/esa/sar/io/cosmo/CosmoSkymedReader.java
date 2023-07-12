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
import eu.esa.sar.commons.io.AbstractProductDirectory;
import eu.esa.sar.commons.io.SARReader;
import org.esa.snap.core.dataio.ProductReaderPlugIn;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.dataop.downloadable.XMLSupport;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.metadata.AbstractMetadataIO;
import org.jdom2.Document;
import org.jdom2.Element;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * The product reader for CosmoSkymed products.
 */
public class CosmoSkymedReader extends SARReader {

    private CosmoReader reader;
    private Product product = null;
    private final ProductReaderPlugIn readerPlugIn;

    /**
     * Constructs a new abstract product reader.
     *
     * @param readerPlugIn the reader plug-in which created this reader, can be <code>null</code> for internal reader
     *                     implementations
     */
    public CosmoSkymedReader(final ProductReaderPlugIn readerPlugIn) {
        super(readerPlugIn);
        this.readerPlugIn = readerPlugIn;
    }

    /**
     * Provides an implementation of the <code>readProductNodes</code> interface method. Clients implementing this
     * method can be sure that the input object and eventually the subset information has already been set.
     * <p/>
     * <p>This method is called as a last step in the <code>readProductNodes(input, subsetInfo)</code> method.
     *
     * @throws java.io.IOException if an I/O error occurs
     */
    @Override
    protected Product readProductNodesImpl() throws IOException {
        try {
            final Path inputPath = getPathFromInput(getInput());

            if(inputPath.toFile().getName().endsWith(".h5")) {
                reader = new CosmoSkymedNetCDFReader(readerPlugIn, this);
            } else {
                reader = new CosmoSkymedGeoTiffReader(readerPlugIn, this);
            }

            product = reader.createProduct(inputPath);

            setQuicklookBandName(product);

            product.getGcpGroup();
            product.setModified(false);

            AbstractProductDirectory.updateProduct(product, null);

            return product;
        } catch(Exception e) {
            handleReaderException(e);
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

        reader.readBandRasterDataImpl(sourceOffsetX, sourceOffsetY, sourceWidth, sourceHeight,
                sourceStepX, sourceStepY, destBand, destOffsetX, destOffsetY, destWidth, destHeight, destBuffer, pm);
    }

    public interface CosmoReader {

        Product createProduct(final Path inputPath) throws Exception;

        void close() throws IOException;

        void readBandRasterDataImpl(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight,
                                    int sourceStepX, int sourceStepY, Band destBand, int destOffsetX,
                                    int destOffsetY, int destWidth, int destHeight, ProductData destBuffer,
                                    ProgressMonitor pm) throws IOException;

        default void addDeliveryNote(final MetadataElement origMeta, final File folder) {
            try {
                File dnFile = null;
                final File[] files = folder.listFiles();
                if(files != null) {
                    for (File f : files) {
                        final String name = f.getName().toLowerCase();
                        if (name.startsWith("dfdn") && name.endsWith("xml")) {
                            dnFile = f;
                            break;
                        }
                    }
                }
                if (dnFile != null) {
                    final Document xmlDoc = XMLSupport.LoadXML(dnFile.getAbsolutePath());
                    final Element rootElement = xmlDoc.getRootElement();

                    AbstractMetadataIO.AddXMLMetadata(rootElement, origMeta);
                }
            } catch (IOException e) {
                //System.out.println("Unable to read Delivery Note for "+product.getName());
            }
        }
    }
}
