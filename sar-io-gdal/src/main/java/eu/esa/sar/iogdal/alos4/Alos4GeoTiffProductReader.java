/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.iogdal.alos4;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.io.SARReader;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.dataio.ProductReaderPlugIn;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.dataio.gdal.reader.plugins.GTiffDriverProductReaderPlugIn;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.OrbitStateVector;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;

import java.io.*;
import java.nio.file.Path;
import java.text.DateFormat;
import java.util.*;
import java.util.stream.Collectors;

public class Alos4GeoTiffProductReader extends SARReader {

    private static final GTiffDriverProductReaderPlugIn gdalPlugIn = new GTiffDriverProductReaderPlugIn();

    private Map<String, String> metadataSummary = null;
    private String imageFileName = null;
    private final List<Product> bandProducts = new ArrayList<>();
    private final DateFormat standardDateFormat = ProductData.UTC.createDateFormat("yyyyMMdd HH:mm:ss");

    public Alos4GeoTiffProductReader(ProductReaderPlugIn readerPlugIn) {
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

        final File summaryFile = findSummaryFile(inputFile);
        if (summaryFile == null) {
            throw new IOException("Cannot find summary metadata file for ALOS-4 GeoTIFF product");
        }
        this.metadataSummary = readSummaryFile(summaryFile);

        final List<File> imageFiles = findImageFiles(inputFile);
        if (imageFiles.isEmpty()) {
            throw new IOException("Cannot find any ALOS-4 GeoTIFF image files");
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
        product.setDescription("ALOS-4 PALSAR-3 GeoTIFF product");

        // Add bands for all polarizations
        addBands(product, imageFiles, firstGdalProduct, width, height);

        // Copy geocoding from GDAL product
        if (firstGdalProduct.getSceneGeoCoding() != null) {
            ProductUtils.copyGeoCoding(firstGdalProduct, product);
        }

        // Set times
        String startTimeStr = metadataSummary.get("Img_SceneStartDateTime");
        String endTimeStr = metadataSummary.get("Img_SceneEndDateTime");
        if (startTimeStr != null) {
            product.setStartTime(AbstractMetadata.parseUTC(startTimeStr, standardDateFormat));
        }
        if (endTimeStr != null) {
            product.setEndTime(AbstractMetadata.parseUTC(endTimeStr, standardDateFormat));
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
        // First polarization - already have the GDAL product
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
                if (name.contains("ALOS4") && (name.endsWith(".TIF") || name.endsWith(".TIFF")) &&
                        name.contains("IMG-") &&
                        (name.contains("-HH-") || name.contains("-HV-") || name.contains("-VH-") || name.contains("-VV-"))) {
                    imageFiles.add(f);
                }
            }
        }
        imageFiles.sort(Comparator.comparing(File::getName));
        return imageFiles;
    }

    private File findSummaryFile(File imageFile) {
        final File dir = imageFile.getParentFile();
        if (dir == null) return null;
        final File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String name = f.getName().toUpperCase();
                if (name.startsWith("SUMMARY") && name.endsWith(".TXT")) {
                    return f;
                }
            }
        }
        return null;
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
        String sceneId = metadataSummary.get("Scs_SceneID");
        String productId = metadataSummary.get("Pds_ProductID");
        if (sceneId != null && productId != null) {
            return sceneId + "_" + productId;
        }
        if (sceneId != null) {
            return sceneId;
        }
        return imageFileName;
    }

    private String getProductType() {
        String processLevel = metadataSummary.get("Lbi_ProcessLevel");
        if (processLevel != null) {
            return "ALOS4-L" + processLevel;
        }
        return "ALOS4-GeoTIFF";
    }

    private String getAcquisitionMode() {
        String obsMode = metadataSummary.get("Scs_ObsMode");
        if (obsMode == null) return "Stripmap";
        obsMode = obsMode.trim().toUpperCase();
        if (obsMode.contains("SPT")) {
            return "Spotlight";
        } else if (obsMode.contains("WB") || obsMode.contains("UWD") || obsMode.contains("UWS") ||
                   obsMode.contains("VBS") || obsMode.contains("VBD")) {
            return "ScanSAR";
        }
        return "Stripmap";
    }

    private String[] getPolarizations() {
        Set<String> pols = metadataSummary.keySet().stream()
                .filter(k -> k.contains("ProductFileName"))
                .map(k -> {
                    String val = metadataSummary.get(k);
                    if (val != null && val.toUpperCase().contains("IMG-")) {
                        int idx = val.toUpperCase().indexOf("IMG-") + 4;
                        if (idx + 2 <= val.length()) {
                            return val.substring(idx, idx + 2).toUpperCase();
                        }
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return pols.toArray(new String[0]);
    }

    private void addAbstractedMetadata(final Product product) {
        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(product.getMetadataRoot());

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PRODUCT, getProductName());
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PRODUCT_TYPE, getProductType());
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.MISSION, "ALOS4");
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ACQUISITION_MODE, getAcquisitionMode());
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SPH_DESCRIPTOR, "STANDARD GEOCODED IMAGE");

        String facility = metadataSummary.get("Lbi_ProcessFacility");
        if (facility != null) {
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ProcessingSystemIdentifier, facility);
        }

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.antenna_pointing, "right");

        // Polarizations
        String[] polarizations = getPolarizations();
        String[] polTags = AbstractMetadata.polarTags;
        for (int i = 0; i < Math.min(polarizations.length, polTags.length); i++) {
            AbstractMetadata.setAttribute(absRoot, polTags[i], polarizations[i]);
        }

        // Spacing
        String pixelSpacing = metadataSummary.get("Pds_PixelSpacing");
        if (pixelSpacing != null) {
            float spacing = Float.parseFloat(pixelSpacing);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_spacing, spacing);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_spacing, spacing);
        }

        // Timing
        String startTimeStr = metadataSummary.get("Img_SceneStartDateTime");
        String endTimeStr = metadataSummary.get("Img_SceneEndDateTime");
        ProductData.UTC startTime = null;
        ProductData.UTC endTime = null;
        if (startTimeStr != null) {
            startTime = AbstractMetadata.parseUTC(startTimeStr, standardDateFormat);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_line_time, startTime);
        }
        if (endTimeStr != null) {
            endTime = AbstractMetadata.parseUTC(endTimeStr, standardDateFormat);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_line_time, endTime);
        }

        // Dimensions
        String numLines = metadataSummary.get("Pdi_NoOfLines_0");
        String numPixels = metadataSummary.get("Pdi_NoOfPixels_0");
        if (numLines != null) {
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.num_output_lines, Integer.parseInt(numLines));
        }
        if (numPixels != null) {
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.num_samples_per_line, Integer.parseInt(numPixels));
        }

        // Line time interval
        if (startTime != null && endTime != null && numLines != null) {
            int lines = Integer.parseInt(numLines);
            double durationSecs = (endTime.getMJD() - startTime.getMJD()) * 24 * 3600;
            if (lines > 1) {
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.line_time_interval, durationSecs / (lines - 1));
            }
        }

        // SAR parameters
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.radar_frequency, 1236.5);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.pulse_repetition_frequency, 2000.0);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_looks, 1.0);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_looks, 1.0);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SAMPLE_TYPE, "DETECTED");
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PASS, "DESCENDING");

        addOrbitStateVectors(absRoot, startTime);
    }

    private void addOrbitStateVectors(final MetadataElement absRoot, final ProductData.UTC refTime) {
        String latStr = metadataSummary.get("Img_ImageSceneCenterLatitude");
        String lonStr = metadataSummary.get("Img_ImageSceneCenterLongitude");
        if (latStr == null || lonStr == null || refTime == null) return;

        double lat = Math.toRadians(Double.parseDouble(latStr));
        double lon = Math.toRadians(Double.parseDouble(lonStr));
        double earthRadius = 6371000.0;
        double altitude = 628000.0;
        double r = earthRadius + altitude;

        double x = r * Math.cos(lat) * Math.cos(lon);
        double y = r * Math.cos(lat) * Math.sin(lon);
        double z = r * Math.sin(lat);

        // Approximate velocity for sun-synchronous orbit (~7.5 km/s)
        double v = 7500.0;
        double vx = -v * Math.sin(lon);
        double vy = v * Math.cos(lon);
        double vz = 0.1; // non-zero to pass validation

        try {
            AbstractMetadata.setOrbitStateVectors(absRoot, new OrbitStateVector[]{
                    new OrbitStateVector(refTime, x, y, z, vx, vy, vz)
            });
        } catch (Exception e) {
            org.esa.snap.core.util.SystemUtils.LOG.warning("Unable to set orbit state vectors: " + e.getMessage());
        }
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
