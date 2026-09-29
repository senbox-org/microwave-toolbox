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
package eu.esa.sar.iogdal.qps;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.io.SARReader;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.dataio.ProductReaderPlugIn;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.VirtualBand;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.dataio.gdal.reader.plugins.GTiffDriverProductReaderPlugIn;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.text.DateFormat;

/**
 * Product reader for iQPS QPS-SAR GeoTIFF products.
 *
 * Supports all iQPS product levels:
 * - Level 1.1: SLC (complex), SLA (single-look amplitude), MLA (multi-look amplitude)
 * - Level 1.2: Ground range projected (SLA/MLA)
 * - Level 1.3: Orthorectified (SLA/MLA)
 *
 * Uses GDAL for raster data reading via GTiffDriverProductReaderPlugIn.
 */
public class QPSSARProductReader extends SARReader {

    private static final GTiffDriverProductReaderPlugIn gdalPlugIn = new GTiffDriverProductReaderPlugIn();
    private Product bandProduct = null;

    public QPSSARProductReader(final ProductReaderPlugIn readerPlugIn) {
        super(readerPlugIn);
    }

    @Override
    public void close() throws IOException {
        if (bandProduct != null) {
            bandProduct.dispose();
            bandProduct = null;
        }
        super.close();
    }

    @Override
    protected Product readProductNodesImpl() throws IOException {
        try {
            final Path inputPath = ReaderUtils.getPathFromInput(getInput());
            if (inputPath == null) {
                throw new IOException("Unable to read input: " + getInput());
            }

            File inputFile = inputPath.toFile();
            String fileName = inputFile.getName().toLowerCase();

            // If given XML or JSON metadata file, find the associated TIFF
            if (fileName.endsWith(".xml") || fileName.endsWith(".json")) {
                inputFile = findTiffFile(inputFile);
                if (inputFile == null) {
                    throw new IOException("No GeoTIFF file found for metadata: " + inputPath);
                }
                fileName = inputFile.getName().toLowerCase();
            }

            if (!fileName.endsWith(".tif") && !fileName.endsWith(".tiff")) {
                throw new IOException("Expected GeoTIFF file, got: " + fileName);
            }

            // Open via GDAL for raster data
            final ProductReader gdalReader = gdalPlugIn.createReaderInstance();
            bandProduct = gdalReader.readProductNodes(inputFile, null);

            if (bandProduct == null) {
                throw new IOException("Unable to read GeoTIFF: " + inputFile);
            }

            final int width = bandProduct.getSceneRasterWidth();
            final int height = bandProduct.getSceneRasterHeight();

            // Determine product type from filename
            final ProductInfo info = parseProductInfo(inputFile.getName());

            final Product product = new Product(info.productName, info.productType, width, height);
            product.setFileLocation(inputFile);
            product.setProductReader(this);
            product.setDescription("iQPS " + info.satellite + " " + info.productType + " " + info.mode);

            // Add metadata
            addMetadata(product, info, bandProduct);

            // Add bands based on product type
            addBands(product, bandProduct, info);

            // Copy geocoding from GDAL product
            if (bandProduct.getSceneGeoCoding() != null) {
                ProductUtils.copyGeoCoding(bandProduct, product);
            }

            // Match resolution levels for COG pyramid compatibility with virtual bands
            final Band firstProductBand = product.getBandAt(0);
            if (firstProductBand != null && firstProductBand.isSourceImageSet()) {
                product.setNumResolutionsMax(firstProductBand.getSourceImage().getModel().getLevelCount());
            }

            addCommonSARMetadata(product);
            product.setModified(false);

            return product;
        } catch (Throwable e) {
            handleReaderException(e);
        }
        return null;
    }

    /**
     * Find GeoTIFF file associated with a metadata file.
     */
    private File findTiffFile(File metadataFile) {
        File dir = metadataFile.getParentFile();
        String baseName = metadataFile.getName();
        baseName = baseName.substring(0, baseName.lastIndexOf('.'));

        // Try exact base name match
        for (String ext : new String[]{".tif", ".tiff"}) {
            File tif = new File(dir, baseName + ext);
            if (tif.exists()) return tif;
        }

        // Try finding any QPS-SAR TIFF in same directory
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String name = f.getName().toUpperCase();
                if (QPSSARProductReaderPlugIn.isQPSSAR(name) &&
                        (name.endsWith(".TIF") || name.endsWith(".TIFF"))) {
                    return f;
                }
            }
        }
        return null;
    }

    /**
     * Parse product information from the filename.
     */
    private ProductInfo parseProductInfo(String fileName) {
        ProductInfo info = new ProductInfo();
        String upper = fileName.toUpperCase();

        // Extract satellite ID
        if (upper.contains("QPSSAR1") || upper.contains("QPS-SAR-1") || upper.contains("QPS_SAR_1")) {
            info.satellite = "QPS-SAR-1";
        } else if (upper.contains("QPSSAR2") || upper.contains("QPS-SAR-2") || upper.contains("QPS_SAR_2")) {
            info.satellite = "QPS-SAR-2";
        } else if (upper.contains("QPSSAR3") || upper.contains("QPS-SAR-3") || upper.contains("QPS_SAR_3")) {
            info.satellite = "QPS-SAR-3";
        } else if (upper.contains("QPSSAR4") || upper.contains("QPS-SAR-4") || upper.contains("QPS_SAR_4")) {
            info.satellite = "QPS-SAR-4";
        } else if (upper.contains("QPSSAR5") || upper.contains("QPS-SAR-5") || upper.contains("QPS_SAR_5")) {
            info.satellite = "QPS-SAR-5";
        } else if (upper.contains("QPSSAR6") || upper.contains("QPS-SAR-6") || upper.contains("QPS_SAR_6")) {
            info.satellite = "QPS-SAR-6";
        } else {
            // Generic — extract number if present
            info.satellite = "QPS-SAR";
        }

        // Determine product type
        if (upper.contains("SLC")) {
            info.productType = "SLC";
            info.isComplex = true;
        } else if (upper.contains("SLA")) {
            info.productType = "SLA";
        } else if (upper.contains("MLA")) {
            info.productType = "MLA";
        } else if (upper.contains("GRD")) {
            info.productType = "GRD";
        } else {
            // Default to detected
            info.productType = "GRD";
        }

        // Determine processing level
        if (upper.contains("L1.3") || upper.contains("L13") || upper.contains("ORTHO")) {
            info.level = "1.3";
        } else if (upper.contains("L1.2") || upper.contains("L12") || upper.contains("GR")) {
            info.level = "1.2";
        } else {
            info.level = "1.1";
        }

        // Determine imaging mode
        if (upper.contains("SM") || upper.contains("STRIP")) {
            info.mode = "Stripmap";
        } else if (upper.contains("SL") || upper.contains("SPOT")) {
            info.mode = "Spotlight";
        } else {
            info.mode = "Stripmap";
        }

        info.productName = fileName.substring(0, fileName.lastIndexOf('.')).replace(" ", "_");

        return info;
    }

    private void addMetadata(Product product, ProductInfo info, Product gdalProduct) {
        final MetadataElement root = product.getMetadataRoot();
        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(root);
        final MetadataElement origProdRoot = AbstractMetadata.addOriginalProductMetadata(root);

        // Copy any metadata from GDAL product
        if (gdalProduct.getMetadataRoot() != null) {
            for (MetadataElement elem : gdalProduct.getMetadataRoot().getElements()) {
                origProdRoot.addElement(elem.createDeepClone());
            }
        }

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PRODUCT, info.productName);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PRODUCT_TYPE, info.productType);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.MISSION, "QPS-SAR");
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ACQUISITION_MODE, info.mode);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SAMPLE_TYPE, info.isComplex ? "COMPLEX" : "DETECTED");

        // X-band radar frequency (~9.65 GHz)
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.radar_frequency, 9650.0); // MHz

        // Image dimensions
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.num_samples_per_line, gdalProduct.getSceneRasterWidth());
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.num_output_lines, gdalProduct.getSceneRasterHeight());

        // Polarization — QPS-SAR is VV only
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.mds1_tx_rx_polar, "VV");
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.antenna_pointing, "right");

        // SRGR flag
        if (info.level.equals("1.1") && !info.isComplex) {
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.srgr_flag, 0);
        } else if (!info.level.equals("1.1")) {
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.srgr_flag, 1);
        }

        // Try to extract additional metadata from GDAL tags
        extractGdalMetadata(absRoot, gdalProduct);

        // Look for XML sidecar metadata
        File inputFile = product.getFileLocation();
        if (inputFile != null) {
            loadSidecarMetadata(origProdRoot, absRoot, inputFile);
        }
    }

    /**
     * Extract metadata from GDAL GeoTIFF tags if available.
     */
    private void extractGdalMetadata(MetadataElement absRoot, Product gdalProduct) {
        try {
            // GeoTIFF may contain GDAL metadata domain with SAR-specific tags
            MetadataElement gdalMeta = gdalProduct.getMetadataRoot();
            if (gdalMeta != null) {
                // Look for pixel spacing in GeoTIFF model pixel scale
                for (MetadataElement elem : gdalMeta.getElements()) {
                    if (elem.getName().contains("GeoTransform") || elem.getName().contains("ModelPixelScale")) {
                        // Try to extract pixel spacing
                        break;
                    }
                }
            }
        } catch (Exception e) {
            SystemUtils.LOG.fine("Unable to extract GDAL metadata: " + e.getMessage());
        }
    }

    /**
     * Look for XML or JSON sidecar metadata files alongside the TIFF.
     */
    private void loadSidecarMetadata(MetadataElement origProdRoot, MetadataElement absRoot, File tiffFile) {
        String baseName = tiffFile.getName();
        baseName = baseName.substring(0, baseName.lastIndexOf('.'));
        File dir = tiffFile.getParentFile();

        // Try XML sidecar
        File xmlFile = new File(dir, baseName + ".xml");
        if (xmlFile.exists()) {
            try {
                final org.jdom2.Document xmlDoc = org.esa.snap.core.dataop.downloadable.XMLSupport.LoadXML(xmlFile.getAbsolutePath());
                final org.jdom2.Element rootElement = xmlDoc.getRootElement();
                org.esa.snap.engine_utilities.datamodel.metadata.AbstractMetadataIO.AddXMLMetadata(rootElement, origProdRoot);
                SystemUtils.LOG.info("QPS-SAR: Loaded XML metadata from " + xmlFile.getName());
            } catch (Exception e) {
                SystemUtils.LOG.fine("Unable to read XML sidecar: " + e.getMessage());
            }
        }

        // JSON sidecar metadata could be added here when format is confirmed
    }

    private void addBands(Product product, Product gdalProduct, ProductInfo info) {
        final String pol = "VV";

        if (info.isComplex && gdalProduct.getNumBands() >= 2) {
            // SLC: two bands (I and Q)
            Band gdalBandI = gdalProduct.getBandAt(0);
            Band gdalBandQ = gdalProduct.getBandAt(1);

            final Band iBand = new Band("i_" + pol, gdalBandI.getDataType(),
                    product.getSceneRasterWidth(), product.getSceneRasterHeight());
            iBand.setUnit(Unit.REAL);
            iBand.setNoDataValue(0);
            iBand.setNoDataValueUsed(true);
            iBand.setSourceImage(gdalBandI.getSourceImage());
            product.addBand(iBand);

            final Band qBand = new Band("q_" + pol, gdalBandQ.getDataType(),
                    product.getSceneRasterWidth(), product.getSceneRasterHeight());
            qBand.setUnit(Unit.IMAGINARY);
            qBand.setNoDataValue(0);
            qBand.setNoDataValueUsed(true);
            qBand.setSourceImage(gdalBandQ.getSourceImage());
            product.addBand(qBand);

            ReaderUtils.createVirtualIntensityBand(product, iBand, qBand, "_" + pol);

        } else {
            // Detected products: single amplitude band
            Band gdalBand = gdalProduct.getBandAt(0);
            final Band ampBand = new Band("Amplitude_" + pol, gdalBand.getDataType(),
                    product.getSceneRasterWidth(), product.getSceneRasterHeight());
            ampBand.setUnit(Unit.AMPLITUDE);
            ampBand.setNoDataValue(0);
            ampBand.setNoDataValueUsed(true);
            ampBand.setSourceImage(gdalBand.getSourceImage());
            product.addBand(ampBand);

            SARReader.createVirtualIntensityBand(product, ampBand, "_" + pol);
        }
    }

    @Override
    protected void readBandRasterDataImpl(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight,
                                          int sourceStepX, int sourceStepY, Band destBand, int destOffsetX,
                                          int destOffsetY, int destWidth, int destHeight, ProductData destBuffer,
                                          ProgressMonitor pm) throws IOException {
        // Band data accessed via GDAL source images
    }

    static class ProductInfo {
        String satellite = "QPS-SAR";
        String productName = "";
        String productType = "GRD";
        String mode = "Stripmap";
        String level = "1.1";
        boolean isComplex = false;
    }
}
