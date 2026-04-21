/*
 * Copyright (C) 2019 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.iogdal.alos2;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.io.SARReader;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.dataio.ProductReaderPlugIn;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.dataio.gdal.reader.plugins.GTiffDriverProductReaderPlugIn;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;

import java.io.*;
import java.nio.file.Path;
import java.text.DateFormat;
import java.util.*;
import java.util.stream.Collectors;

public class Alos2GeoTiffProductReader extends SARReader {

    private static final GTiffDriverProductReaderPlugIn gdalPlugIn = new GTiffDriverProductReaderPlugIn();

    private Map<String, String> metadataSummary = null;
    private String imageFileName = null;
    private final List<Product> bandProducts = new ArrayList<>();
    private final DateFormat standardDateFormat = ProductData.UTC.createDateFormat("yyyyMMdd HH:mm:ss");

    public Alos2GeoTiffProductReader(ProductReaderPlugIn readerPlugIn) {
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

        final Path inputPath = getPathFromInput(getInput());
        File inputFile = inputPath.toFile();

        this.metadataSummary = readSummaryFile(inputPath.getParent().resolve("summary.txt").toFile());

        final List<File> imageFiles = findImageFiles(inputFile);
        if (imageFiles.isEmpty()) {
            throw new IOException("Cannot find any ALOS-2 GeoTIFF image files");
        }
        this.imageFileName = imageFiles.get(0).getName();

        // Read the first image file via GDAL to get dimensions and geocoding
        final File firstImageFile = imageFiles.get(0);
        final ProductReader firstReader = gdalPlugIn.createReaderInstance();
        final Product firstGdalProduct = firstReader.readProductNodes(firstImageFile, null);
        bandProducts.add(firstGdalProduct);

        final int width = firstGdalProduct.getSceneRasterWidth();
        final int height = firstGdalProduct.getSceneRasterHeight();

        final Product product = new Product(getProductName(), getProductType(), width, height);
        product.setFileLocation(firstImageFile);
        product.setProductReader(this);

        // Add bands for all polarizations
        addBands(product, imageFiles, firstGdalProduct, width, height);

        // Copy geocoding from GDAL product
        if (firstGdalProduct.getSceneGeoCoding() != null) {
            ProductUtils.copyGeoCoding(firstGdalProduct, product);
        }

        addAbstractedMetadata(product);
        addOriginalMetaData(product);
        addGeoCoding(product);
        addCommonSARMetadata(product);

        product.setModified(false);
        return product;
    }

    private void addBands(Product product, List<File> imageFiles, Product firstGdalProduct,
                          int width, int height) throws IOException {
        // First polarization
        String polarization = extractPolarization(imageFiles.get(0).getName());
        Band gdalBand = firstGdalProduct.getBandAt(0);

        Band ampBand = new Band("Amplitude_" + polarization, gdalBand.getDataType(), width, height);
        ampBand.setUnit("amplitude");
        ampBand.setNoDataValue(0);
        ampBand.setNoDataValueUsed(true);
        ampBand.setSourceImage(gdalBand.getSourceImage());
        product.addBand(ampBand);

        SARReader.createVirtualIntensityBand(product, ampBand, "_" + polarization);

        // Additional polarizations
        for (int i = 1; i < imageFiles.size(); i++) {
            File imageFile = imageFiles.get(i);
            ProductReader reader = gdalPlugIn.createReaderInstance();
            Product gdalProduct = reader.readProductNodes(imageFile, null);
            bandProducts.add(gdalProduct);

            String pol = extractPolarization(imageFile.getName());
            Band srcBand = gdalProduct.getBandAt(0);

            Band nextAmpBand = new Band("Amplitude_" + pol, srcBand.getDataType(), width, height);
            nextAmpBand.setUnit("amplitude");
            nextAmpBand.setNoDataValue(0);
            nextAmpBand.setNoDataValueUsed(true);
            nextAmpBand.setSourceImage(srcBand.getSourceImage());
            product.addBand(nextAmpBand);

            SARReader.createVirtualIntensityBand(product, nextAmpBand, "_" + pol);
        }
    }

    private List<File> findImageFiles(File inputFile) {
        if (inputFile.getName().toLowerCase().endsWith(".zip")) {
            return Collections.singletonList(inputFile);
        }

        List<File> imageFiles = new ArrayList<>();
        final File[] files = inputFile.getParentFile().listFiles();
        if (files != null) {
            for (File f : files) {
                String name = f.getName().toUpperCase();
                if (name.contains("ALOS2") && (name.endsWith(".TIF") || name.endsWith(".TIFF")) &&
                        name.contains("IMG-") &&
                        (name.contains("-HH-") || name.contains("-HV-") || name.contains("-VH-") || name.contains("-VV-"))) {
                    imageFiles.add(f);
                }
            }
        }
        imageFiles.sort(Comparator.comparing(File::getName));
        return imageFiles;
    }

    private String extractPolarization(String fileName) {
        String upper = fileName.toUpperCase();
        if (upper.contains("IMG-")) {
            int idx = upper.indexOf("IMG-") + 4;
            if (idx + 2 <= upper.length()) {
                return upper.substring(idx, idx + 2);
            }
        }
        return "HH";
    }

    private Map<String, String> readSummaryFile(File summaryFile) throws IOException {
        Map<String, String> metadata = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(summaryFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    metadata.put(parts[0].replace("\"", "").trim(),
                            parts[1].replace("\"", "").trim());
                }
            }
        }
        return metadata;
    }

    private String getProductName() {
        String firstSegment = this.imageFileName;
        String lastSegment = this.imageFileName;
        final String PREFIX = "ALOS2";
        final String ORBIT = "-ORBIT__";
        // Image file names start with IMG-{polarization - HH HV VH VV}, get rid of it
        if (lastSegment.startsWith("IMG-")) {
            lastSegment = lastSegment.substring(4, lastSegment.length() - 1);
            if (lastSegment.startsWith("H") || lastSegment.startsWith("V")) {
                lastSegment = lastSegment.substring(3, lastSegment.length() - 1);
            }
        }
        lastSegment = lastSegment.replace("ALOS-2", "ALOS2");
        String lastPart = this.imageFileName.substring(this.imageFileName.lastIndexOf("-") - 7, this.imageFileName.lastIndexOf("-"));
        lastSegment = lastSegment.substring(0, lastSegment.indexOf("-")) + lastPart;

        firstSegment = firstSegment.replace(".",
                "_").substring(firstSegment.lastIndexOf("-"),
                firstSegment.length() - 4);

        return PREFIX + firstSegment + ORBIT + lastSegment;
    }

    private String getProductType() {
        String product = this.imageFileName.substring(0, this.imageFileName.length() - 5);
        if (product.endsWith(".")) {
            product = product.substring(0, product.length() - 2);
        }
        if (product.endsWith("UA")) {
            product = product.substring(0, product.length() - 3);
        }
        product = product.substring(product.lastIndexOf('-') + 1, product.length() - 1);
        return "ALOS2-" + product;
    }

    private float getRangeSpacing() {
        return Float.parseFloat(metadataSummary.get("Pds_PixelSpacing"));
    }

    private float getAzimuthSpacing() {
        return Float.parseFloat(metadataSummary.get("Pds_PixelSpacing"));
    }

    private String[] getPolarizations() {
        Set<String> polarizations = metadataSummary.keySet().stream()
                .filter(s -> s.contains("ProductFileName"))
                .map(k -> {
                    String val = metadataSummary.get(k);
                    if (val != null && val.startsWith("IMG-")) {
                        return val.substring(4, 6).toUpperCase();
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return polarizations.toArray(new String[0]);
    }

    private void addAbstractedMetadata(final Product product) {
        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(product.getMetadataRoot());

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PRODUCT, getProductName());
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PRODUCT_TYPE, getProductType());
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.MISSION, "ALOS2");
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SPH_DESCRIPTOR, "STANDARD GEOCODED IMAGE");
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ProcessingSystemIdentifier,
                metadataSummary.get("Lbi_ProcessFacility"));

        // Polarizations
        String[] polarizations = getPolarizations();
        String[] polTags = AbstractMetadata.polarTags;
        for (int i = 0; i < Math.min(polarizations.length, polTags.length); i++) {
            AbstractMetadata.setAttribute(absRoot, polTags[i], polarizations[i]);
        }

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_spacing, getRangeSpacing());
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_spacing, getAzimuthSpacing());

        ProductData.UTC startTime = AbstractMetadata.parseUTC(
                metadataSummary.get("Img_SceneStartDateTime"), standardDateFormat);
        ProductData.UTC endTime = AbstractMetadata.parseUTC(
                metadataSummary.get("Img_SceneEndDateTime"), standardDateFormat);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_line_time, startTime);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_line_time, endTime);

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.num_output_lines,
                Integer.parseInt(metadataSummary.get("Pdi_NoOfLines_0")));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.num_samples_per_line,
                Integer.parseInt(metadataSummary.get("Pdi_NoOfPixels_0")));

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.radar_frequency, (float) 1236.4997597467545);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.antenna_pointing, "right");
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.pulse_repetition_frequency, 2122.318448518);
    }

    private void addOriginalMetaData(Product product) {
        final MetadataElement origRoot = AbstractMetadata.addOriginalProductMetadata(product.getMetadataRoot());
        for (Map.Entry<String, String> entry : this.metadataSummary.entrySet()) {
            AbstractMetadata.setAttribute(origRoot, entry.getKey(), entry.getValue());
        }
    }

    private void addGeoCoding(final Product product) {
        if (product.getSceneGeoCoding() != null) {
            return;
        }

        try {
            final float latUL = Float.parseFloat(metadataSummary.get("Img_ImageSceneLeftTopLatitude"));
            final float lonUL = Float.parseFloat(metadataSummary.get("Img_ImageSceneLeftTopLongitude"));
            final float latUR = Float.parseFloat(metadataSummary.get("Img_ImageSceneRightTopLatitude"));
            final float lonUR = Float.parseFloat(metadataSummary.get("Img_ImageSceneRightTopLongitude"));
            final float latLL = Float.parseFloat(metadataSummary.get("Img_ImageSceneLeftBottomLatitude"));
            final float lonLL = Float.parseFloat(metadataSummary.get("Img_ImageSceneLeftBottomLongitude"));
            final float latLR = Float.parseFloat(metadataSummary.get("Img_ImageSceneRightBottomLatitude"));
            final float lonLR = Float.parseFloat(metadataSummary.get("Img_ImageSceneRightBottomLongitude"));

            final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
            absRoot.setAttributeDouble(AbstractMetadata.first_near_lat, latUL);
            absRoot.setAttributeDouble(AbstractMetadata.first_near_long, lonUL);
            absRoot.setAttributeDouble(AbstractMetadata.first_far_lat, latUR);
            absRoot.setAttributeDouble(AbstractMetadata.first_far_long, lonUR);
            absRoot.setAttributeDouble(AbstractMetadata.last_near_lat, latLL);
            absRoot.setAttributeDouble(AbstractMetadata.last_near_long, lonLL);
            absRoot.setAttributeDouble(AbstractMetadata.last_far_lat, latLR);
            absRoot.setAttributeDouble(AbstractMetadata.last_far_long, lonLR);

            final float[] latCorners = new float[]{latUL, latUR, latLL, latLR};
            final float[] lonCorners = new float[]{lonUL, lonUR, lonLL, lonLR};

            ReaderUtils.addGeoCoding(product, latCorners, lonCorners);
        } catch (Exception e) {
            // Geocoding from GDAL should already be set
        }
    }

    @Override
    protected void readBandRasterDataImpl(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight,
                                          int sourceStepX, int sourceStepY, Band destBand, int destOffsetX,
                                          int destOffsetY, int destWidth, int destHeight, ProductData destBuffer,
                                          ProgressMonitor pm) throws IOException {
        // Band data accessed via GDAL source images
    }
}
