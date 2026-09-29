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
package eu.esa.sar.iogdal.eos4;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.io.SARReader;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.dataio.ProductReaderPlugIn;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.dataio.gdal.reader.plugins.GTiffDriverProductReaderPlugIn;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Product reader for ISRO EOS-04 (RISAT-1A) COG/GeoTIFF products.
 *
 * Reads BAND_META.txt metadata and discovers GeoTIFF/COG imagery files
 * in scene_POL/ subdirectories. Uses GDAL GTiffDriverProductReaderPlugIn
 * for efficient raster data access via source images.
 */
public class EOS4CogProductReader extends SARReader {

    private static final GTiffDriverProductReaderPlugIn gdalPlugIn = new GTiffDriverProductReaderPlugIn();
    private final List<Product> bandProducts = new ArrayList<>();
    private boolean compactPolMode = false;

    private final DateFormat standardDateFormat = ProductData.UTC.createDateFormat("dd-MMM-yyyy HH:mm:ss");

    public EOS4CogProductReader(final ProductReaderPlugIn readerPlugIn) {
        super(readerPlugIn);
    }

    @Override
    public void close() throws IOException {
        for (Product bp : bandProducts) {
            if (bp != null) bp.dispose();
        }
        bandProducts.clear();
        super.close();
    }

    @Override
    protected Product readProductNodesImpl() throws IOException {
        try {
            final Path inputPath = getPathFromInput(getInput());
            File inputFile = inputPath.toFile();

            // Find BAND_META.txt
            File metadataFile;
            if (inputFile.isDirectory()) {
                metadataFile = new File(inputFile, EOS4CogProductReaderPlugIn.BAND_HEADER_NAME);
            } else if (inputFile.getName().equals(EOS4CogProductReaderPlugIn.BAND_HEADER_NAME)) {
                metadataFile = inputFile;
            } else {
                metadataFile = new File(inputFile.getParentFile(), EOS4CogProductReaderPlugIn.BAND_HEADER_NAME);
            }

            if (!metadataFile.exists()) {
                throw new IOException("BAND_META.txt not found: " + metadataFile);
            }

            final File productDir = metadataFile.getParentFile();

            // Read BAND_META.txt as property file
            final Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(metadataFile)) {
                props.load(fis);
            }
            final MetadataElement productMetadata = new MetadataElement("ProductMetadata");
            for (String key : props.stringPropertyNames()) {
                productMetadata.setAttributeString(key, props.getProperty(key));
            }

            // Determine product properties
            final String productType = productMetadata.getAttributeString("ProductType", "");
            final boolean isSLC = productType.contains("SLANT");

            // Discover GeoTIFF imagery in scene_POL/ subdirectories
            final List<BandFileInfo> bandFiles = discoverImagery(productDir);
            if (bandFiles.isEmpty()) {
                throw new IOException("No GeoTIFF imagery found in product directory: " + productDir);
            }

            // Open first image via GDAL to get dimensions
            final BandFileInfo firstBand = bandFiles.get(0);
            final ProductReader gdalReader = gdalPlugIn.createReaderInstance();
            final Product firstGdalProduct = gdalReader.readProductNodes(firstBand.file, null);
            bandProducts.add(firstGdalProduct);

            final int width = firstGdalProduct.getSceneRasterWidth();
            final int height = firstGdalProduct.getSceneRasterHeight();

            // Build product name
            final String defStr = AbstractMetadata.NO_METADATA_STRING;
            final String pass = productMetadata.getAttributeString("Node", defStr).toUpperCase();
            final String beamMode = productMetadata.getAttributeString("beamModeMnemonic", defStr);
            final String productId = productMetadata.getAttributeString("productId", defStr);
            final String passStr = pass.equals("ASCENDING") ? "ASC" : "DSC";

            final ProductData.UTC startTime = parseTime(productMetadata, "SceneStartTime");
            final DateFormat dateFormat = ProductData.UTC.createDateFormat("dd-MMM-yyyy_HH.mm");
            final String dateString = startTime != null ? dateFormat.format(startTime.getAsDate()) : "unknown";
            final String productName = "EOS04-" + productType + "-" + beamMode + "-" + passStr + "-" + dateString + "-" + productId;

            final Product product = new Product(productName, productType, width, height);
            product.setFileLocation(metadataFile);
            product.setProductReader(this);

            // Add metadata
            addMetadata(product, productMetadata, isSLC);

            // Add bands from GDAL
            addBands(product, bandFiles, isSLC, width, height);

            // Copy geocoding from first GDAL product
            if (firstGdalProduct.getSceneGeoCoding() != null) {
                ProductUtils.copyGeoCoding(firstGdalProduct, product);
            }

            // Transfer tile size and multi-level model from GDAL product to ensure
            // virtual bands (Intensity) use the same image pyramid as the source bands.
            // Without this, COG overviews cause level mismatch errors.
            java.awt.Dimension tileSize = firstGdalProduct.getPreferredTileSize();
            if (tileSize == null) {
                tileSize = org.esa.snap.core.image.ImageManager.getPreferredTileSize(firstGdalProduct);
            }
            product.setPreferredTileSize(tileSize);

            // Match the product's resolution level count to the GDAL source image.
            // COG files have overview pyramids that define the multi-level model.
            // Without this, virtual bands (Intensity) get a different level count
            // than the source bands, causing "level=N < 1" errors.
            final Band srcBand = product.getBandAt(0);
            if (srcBand != null && srcBand.isSourceImageSet()) {
                final int numLevels = srcBand.getSourceImage().getModel().getLevelCount();
                product.setNumResolutionsMax(numLevels);
            }

            addCommonSARMetadata(product);
            product.setModified(false);

            return product;
        } catch (Throwable e) {
            handleReaderException(e);
        }
        return null;
    }

    private List<BandFileInfo> discoverImagery(File productDir) {
        final List<BandFileInfo> result = new ArrayList<>();
        final String[] polDirs = {"scene_HH", "scene_HV", "scene_VV", "scene_VH", "scene_RH", "scene_RV"};

        for (String polDir : polDirs) {
            File dir = new File(productDir, polDir);
            if (!dir.exists() || !dir.isDirectory()) continue;

            File[] files = dir.listFiles();
            if (files == null) continue;

            for (File f : files) {
                String name = f.getName().toLowerCase();
                if (name.contains("imagery") && (name.endsWith(".tif") || name.endsWith(".tiff"))) {
                    String pol = getPol(polDir);
                    result.add(new BandFileInfo(f, pol));
                }
            }
        }
        return result;
    }

    private String getPol(String dirOrName) {
        String upper = dirOrName.toUpperCase();
        if (upper.contains("RH")) {
            compactPolMode = true;
            return "RCH";
        } else if (upper.contains("RV")) {
            compactPolMode = true;
            return "RCV";
        } else if (upper.contains("HH")) return "HH";
        else if (upper.contains("HV")) return "HV";
        else if (upper.contains("VH")) return "VH";
        else if (upper.contains("VV")) return "VV";
        return "unknown";
    }

    private void addMetadata(Product product, MetadataElement productMetadata, boolean isSLC) {
        final MetadataElement root = product.getMetadataRoot();
        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(root);
        final MetadataElement origProdRoot = AbstractMetadata.addOriginalProductMetadata(root);

        origProdRoot.addElement(productMetadata.createDeepClone());

        final String defStr = AbstractMetadata.NO_METADATA_STRING;
        final int defInt = AbstractMetadata.NO_METADATA;

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PRODUCT, product.getName());
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PRODUCT_TYPE,
                productMetadata.getAttributeString("ProductType", defStr));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.MISSION, "EOS-04");
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ACQUISITION_MODE,
                productMetadata.getAttributeString("ImagingMode", defStr));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.antenna_pointing,
                productMetadata.getAttributeString("SensorOrientation", defStr).toLowerCase());
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.BEAMS,
                productMetadata.getAttributeString("NumberOfBeams", defStr));

        final String pass = productMetadata.getAttributeString("Node", defStr).toUpperCase();
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PASS, pass);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ABS_ORBIT,
                productMetadata.getAttributeDouble("ImagingOrbitNo", defInt));

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SAMPLE_TYPE, isSLC ? "COMPLEX" : "DETECTED");
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.srgr_flag, isSLC ? 0 : 1);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.radar_frequency, 5350); // C-band

        final ProductData.UTC startTime = parseTime(productMetadata, "SceneStartTime");
        final ProductData.UTC stopTime = parseTime(productMetadata, "SceneEndTime");
        if (startTime != null) AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_line_time, startTime);
        if (stopTime != null) AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_line_time, stopTime);

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.num_samples_per_line,
                productMetadata.getAttributeInt("NoPixels", defInt));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.num_output_lines,
                productMetadata.getAttributeInt("NoScans", defInt));

        if (startTime != null && stopTime != null) {
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.line_time_interval,
                    ReaderUtils.getLineTimeInterval(startTime, stopTime,
                            absRoot.getAttributeInt(AbstractMetadata.num_output_lines)));
        }

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_spacing,
                productMetadata.getAttributeDouble("OutputPixelSpacing", defInt));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_spacing,
                productMetadata.getAttributeDouble("OutputLineSpacing", defInt));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_looks,
                productMetadata.getAttributeDouble("RangeLooks", defInt));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_looks,
                productMetadata.getAttributeDouble("AzimuthLooks", defInt));

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ProcessingSystemIdentifier,
                productMetadata.getAttributeString("processingFacility", defStr) + "-" +
                        productMetadata.getAttributeString("softwareVersion", defStr));

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ant_elev_corr_flag,
                getFlag(productMetadata, "elevationPatternCorrection"));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_spread_comp_flag,
                getFlag(productMetadata, "rangeSpreadingLossCorrection"));

        final String pol1 = productMetadata.getAttributeString("TxRxPol1", defStr);
        if (!pol1.equals(defStr)) {
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.mds1_tx_rx_polar, pol1);
        }
        final String pol2 = productMetadata.getAttributeString("TxRxPol2", defStr);
        if (!pol2.equals(defStr)) {
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.mds2_tx_rx_polar, pol2);
        }

        if (compactPolMode) {
            absRoot.setAttributeInt(AbstractMetadata.polsarData, 1);
            absRoot.setAttributeString(AbstractMetadata.compact_mode, "Right Circular Hybrid Mode");
        }
    }

    private void addBands(Product product, List<BandFileInfo> bandFiles, boolean isSLC,
                          int width, int height) throws IOException {
        for (BandFileInfo bandInfo : bandFiles) {
            Product gdalProduct = null;
            for (Product bp : bandProducts) {
                if (bp.getFileLocation().equals(bandInfo.file)) {
                    gdalProduct = bp;
                    break;
                }
            }
            if (gdalProduct == null) {
                final ProductReader reader = gdalPlugIn.createReaderInstance();
                gdalProduct = reader.readProductNodes(bandInfo.file, null);
                bandProducts.add(gdalProduct);
            }

            if (isSLC && gdalProduct.getNumBands() >= 2) {
                Band gdalI = gdalProduct.getBandAt(0);
                Band gdalQ = gdalProduct.getBandAt(1);

                Band iBand = new Band("i_" + bandInfo.pol, gdalI.getDataType(), width, height);
                iBand.setUnit(Unit.REAL);
                iBand.setNoDataValue(0);
                iBand.setNoDataValueUsed(true);
                iBand.setSourceImage(gdalI.getSourceImage());
                product.addBand(iBand);

                Band qBand = new Band("q_" + bandInfo.pol, gdalQ.getDataType(), width, height);
                qBand.setUnit(Unit.IMAGINARY);
                qBand.setNoDataValue(0);
                qBand.setNoDataValueUsed(true);
                qBand.setSourceImage(gdalQ.getSourceImage());
                product.addBand(qBand);

                ReaderUtils.createVirtualIntensityBand(product, iBand, qBand, "_" + bandInfo.pol);
            } else {
                Band gdalBand = gdalProduct.getBandAt(0);

                Band ampBand = new Band("Amplitude_" + bandInfo.pol, gdalBand.getDataType(), width, height);
                ampBand.setUnit(Unit.AMPLITUDE);
                ampBand.setNoDataValue(0);
                ampBand.setNoDataValueUsed(true);
                ampBand.setSourceImage(gdalBand.getSourceImage());
                product.addBand(ampBand);

                SARReader.createVirtualIntensityBand(product, ampBand, "_" + bandInfo.pol);
            }
        }
    }

    private ProductData.UTC parseTime(MetadataElement elem, String tag) {
        if (elem == null) return null;
        final String timeStr = elem.getAttributeString(tag, "").toUpperCase().trim();
        if (timeStr.isEmpty()) return null;
        return AbstractMetadata.parseUTC(timeStr, standardDateFormat);
    }

    private static int getFlag(MetadataElement elem, String tag) {
        String val = elem.getAttributeString(tag, "").toUpperCase().trim();
        if (val.equals("TRUE") || val.equals("1")) return 1;
        return 0;
    }

    @Override
    protected void readBandRasterDataImpl(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight,
                                          int sourceStepX, int sourceStepY, Band destBand, int destOffsetX,
                                          int destOffsetY, int destWidth, int destHeight, ProductData destBuffer,
                                          ProgressMonitor pm) throws IOException {
        // Band data accessed via GDAL source images
    }

    static class BandFileInfo {
        final File file;
        final String pol;

        BandFileInfo(File file, String pol) {
            this.file = file;
            this.pol = pol;
        }
    }
}
