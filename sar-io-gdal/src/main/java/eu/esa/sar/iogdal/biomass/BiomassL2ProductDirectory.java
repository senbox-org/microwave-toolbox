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
 */
package eu.esa.sar.iogdal.biomass;

import eu.esa.sar.commons.io.XMLProductDirectory;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.dataio.gdal.reader.plugins.GTiffDriverProductReaderPlugIn;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reader directory for BIOMASS Level-2 products (FH, FD, GN, AGB).
 * <p>
 * L2 differs from L1 in important ways:
 * <ul>
 *     <li>Measurement bands are Cloud Optimized GeoTIFFs already in WGS84 (EPSG:4326).
 *         Geocoding comes from the COG's geotransform — no NetCDF tie-point grids.</li>
 *     <li>No SAR-specific metadata: no orbit vectors, SRGR, Doppler, PRF, etc.</li>
 *     <li>No calibration — L2 values are already geophysical (height, biomass, probability).</li>
 *     <li>Bands are geophysical quantities (forest height, AGB density, etc.), not SAR i/q.</li>
 * </ul>
 * <p>
 * This is a Phase-1 scaffold per {@code docs/BIOMASS-L2-Reader-Spec.md}. It can open
 * L2 product directories, load measurement COGs as bands, and expose basic MPH
 * metadata. Phase 2 (annotation LUT NetCDF reading) and Phase 3 (L2b extras: FNF
 * mask, heatmap, acquisition-ID) are not yet implemented — clearly marked with TODOs.
 */
public class BiomassL2ProductDirectory extends XMLProductDirectory {

    /**
     * Maps measurement-file suffix → [band-name, unit]. Suffix is the lowercased filename
     * fragment immediately before {@code .tiff}, after the last underscore.
     */
    private static final Map<String, String[]> L2_BAND_MAP = new LinkedHashMap<>();
    static {
        L2_BAND_MAP.put("fh",          new String[]{"Forest_Height",          Unit.METERS});
        L2_BAND_MAP.put("quality",     new String[]{"Forest_Height_Quality",  "%"});
        L2_BAND_MAP.put("fd",          new String[]{"Forest_Disturbance",     "flag"});
        L2_BAND_MAP.put("probability", new String[]{"Probability_of_Change",  ""});
        L2_BAND_MAP.put("cfm",         new String[]{"Computed_Forest_Mask",   "flag"});
        L2_BAND_MAP.put("agb",         new String[]{"AGB",                    "t/ha"});
        L2_BAND_MAP.put("agb_std_dev", new String[]{"AGB_Std_Dev",            "t/ha"});
        // Ground Notch is a multi-band COG; handled specially in addBands().
        L2_BAND_MAP.put("gn",          new String[]{"Ground_Notch",           ""});
    }

    /** Pol-channel names for the 3-band Ground Notch COG, in band order. */
    private static final String[] GN_POL_ORDER = {"HH", "VH", "VV"};

    private static final GTiffDriverProductReaderPlugIn READER_PLUGIN = new GTiffDriverProductReaderPlugIn();

    /** Loaded band-source products keyed by suffix ("fh", "agb", ...). */
    private final Map<String, ReaderData> bandProductMap = new TreeMap<>();

    private String productName = "";
    private String productType = "";

    private static final Double NO_DATA_FLOAT = -9999.0;
    private static final double NO_DATA_BYTE  = 255.0;

    public static class ReaderData {
        ProductReader reader;
        Product bandProduct;
        String suffix;
    }

    public BiomassL2ProductDirectory(final File inputFile) {
        super(inputFile);
    }

    @Override
    public void close() throws IOException {
        super.close();
        for (final ReaderData data : bandProductMap.values()) {
            if (data.bandProduct != null) data.bandProduct.dispose();
            if (data.reader != null) data.reader.close();
        }
    }

    @Override
    protected String getHeaderFileName() {
        if (productInputFile != null && productInputFile.getName().toLowerCase().endsWith(".zip")) {
            try (final ZipFile productZip = new ZipFile(productInputFile, ZipFile.OPEN_READ)) {
                final Optional<? extends ZipEntry> match = productZip.stream()
                        .filter(ze -> !ze.isDirectory())
                        .filter(ze -> ze.getName().toLowerCase().startsWith("bio_fp_"))
                        .filter(ze -> ze.getName().toLowerCase().endsWith(".xml"))
                        .filter(ze -> ze.getName().toLowerCase().contains("annot") == false)
                        .findFirst();
                if (match.isPresent()) {
                    final String name = match.get().getName();
                    return name.contains("/") ? name.substring(name.indexOf('/') + 1) : name;
                }
            } catch (Exception e) {
                SystemUtils.LOG.warning("unable to read BIOMASS L2 zip " + productInputFile + ": " + e.getMessage());
            }
        }
        return productInputFile == null ? "" : productInputFile.getName();
    }

    @Override
    protected String getRelativePathToImageFolder() {
        return getRootFolder() + "measurement" + '/';
    }

    /** Pull the band-key (suffix) out of a measurement filename like
     *  {@code bio_fp_fh__l2a_..._i_fh.tiff} → {@code "fh"}. */
    private static String suffixFor(final String fileName) {
        final String lower = fileName.toLowerCase();
        if (!lower.endsWith(".tiff")) return null;
        final String stem = lower.substring(0, lower.length() - 5); // strip .tiff
        // Each known suffix is the trailing token of the stem (preceded by "_i_" or "_").
        // Try longest-match first so "agb_std_dev" wins over "agb".
        String best = null;
        for (final String key : L2_BAND_MAP.keySet()) {
            if (stem.endsWith("_" + key) && (best == null || key.length() > best.length())) {
                best = key;
            }
        }
        return best;
    }

    @Override
    protected void addImageFile(final String imgPath, final MetadataElement newRoot) throws IOException {
        final String fileName = getBandFileNameFromImage(imgPath);
        if (!fileName.toLowerCase().endsWith(".tiff")) return;
        final String suffix = suffixFor(fileName);
        if (suffix == null) {
            SystemUtils.LOG.fine("BIOMASS L2: unrecognised measurement file '" + fileName + "' — skipping.");
            return;
        }
        try {
            final ReaderData data = new ReaderData();
            data.reader = READER_PLUGIN.createReaderInstance();
            data.suffix = suffix;

            if (isCompressed()) {
                final String zipPath = getBaseDir().getAbsolutePath().replace("\\", "/");
                String entryPath = imgPath.startsWith(zipPath) ? imgPath.substring(zipPath.length()) : imgPath;
                if (entryPath.startsWith("/")) entryPath = entryPath.substring(1);
                final String vsizipPath = "/vsizip/" + zipPath + "/" + entryPath;
                try {
                    data.bandProduct = data.reader.readProductNodes(vsizipPath, null);
                } catch (Exception ignore) {
                    // Fallback: extract to temp file
                    data.bandProduct = data.reader.readProductNodes(productDir.getFile(imgPath), null);
                }
            } else {
                data.bandProduct = data.reader.readProductNodes(productDir.getFile(imgPath), null);
            }

            if (data.bandProduct != null) {
                bandProductMap.put(suffix, data);
            } else {
                SystemUtils.LOG.warning("BIOMASS L2: failed to load measurement '" + imgPath +
                        "' — band will be missing from the product.");
            }
        } catch (Exception e) {
            throw new IOException(imgPath + " failed to open: " + e.getMessage(), e);
        }
    }

    @Override
    protected void addBands(final Product product) {
        for (final Map.Entry<String, ReaderData> entry : bandProductMap.entrySet()) {
            final String suffix = entry.getKey();
            final ReaderData data = entry.getValue();
            if (data.bandProduct == null || data.bandProduct.getNumBands() == 0) continue;

            if ("gn".equals(suffix)) {
                // Ground Notch is a 3-band COG (HH, VH, VV); name each plane explicitly.
                final int n = Math.min(GN_POL_ORDER.length, data.bandProduct.getNumBands());
                for (int i = 0; i < n; i++) {
                    addBandFromCog(product, data.bandProduct.getBandAt(i),
                            "Ground_Notch_" + GN_POL_ORDER[i], "");
                }
                continue;
            }

            final String[] meta = L2_BAND_MAP.get(suffix);
            if (meta == null) continue;
            addBandFromCog(product, data.bandProduct.getBandAt(0), meta[0], meta[1]);
        }
    }

    private void addBandFromCog(final Product product, final Band srcBand,
                                final String name, final String unit) {
        final Band band = new Band(name,
                srcBand.getDataType(),
                srcBand.getRasterWidth(),
                srcBand.getRasterHeight());
        band.setUnit(unit);
        band.setSourceImage(srcBand.getSourceImage());

        // L2 no-data: integer mask bands use 255; float bands use -9999.0.
        // Inherit from the source band if it has one declared; otherwise infer from data type.
        if (srcBand.isNoDataValueUsed()) {
            band.setNoDataValueUsed(true);
            band.setNoDataValue(srcBand.getNoDataValue());
        } else {
            band.setNoDataValueUsed(true);
            band.setNoDataValue(srcBand.getDataType() <= org.esa.snap.core.datamodel.ProductData.TYPE_INT32
                    ? NO_DATA_BYTE : NO_DATA_FLOAT);
        }

        product.addBand(band);
    }

    @Override
    protected void addAbstractedMetadataHeader(final MetadataElement root) throws IOException {
        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(root);
        final MetadataElement origProdRoot = AbstractMetadata.addOriginalProductMetadata(root);

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.MISSION, "BIOMASS");

        // Pull what we can from the MPH XML; structure mirrors L1 (bio:EarthObservation).
        final MetadataElement earthObs = origProdRoot.getElement("EarthObservation");
        if (earthObs == null) {
            SystemUtils.LOG.warning("BIOMASS L2: MPH missing EarthObservation root — metadata incomplete.");
            return;
        }
        final MetadataElement metaDataProperty = earthObs.getElement("metaDataProperty");
        final MetadataElement metaData = metaDataProperty == null ? null
                : metaDataProperty.getElement("EarthObservationMetaData");
        if (metaData != null) {
            productName = metaData.getAttributeString("identifier", "");
            productType = metaData.getAttributeString("productType", "");
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PRODUCT, productName);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PRODUCT_TYPE, productType);

            final MetadataElement processing = metaData.getElement("processing");
            final MetadataElement procInfo = processing == null ? null
                    : processing.getElement("ProcessingInformation");
            if (procInfo != null) {
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ProcessingSystemIdentifier,
                        procInfo.getAttributeString("processingLevel", AbstractMetadata.NO_METADATA_STRING));
            }
        }

        final MetadataElement procedure = earthObs.getElement("procedure");
        final MetadataElement equipment = procedure == null ? null
                : procedure.getElement("EarthObservationEquipment");
        if (equipment != null) {
            final MetadataElement sensor = equipment.getElement("sensor");
            final MetadataElement sensorInner = sensor == null ? null : sensor.getElement("Sensor");
            if (sensorInner != null) {
                final MetadataElement operationalMode = sensorInner.getElement("operationalMode");
                if (operationalMode != null) {
                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ACQUISITION_MODE,
                            operationalMode.getAttributeString("operationalMode", AbstractMetadata.NO_METADATA_STRING));
                }
                final MetadataElement swath = sensorInner.getElement("swathIdentifier");
                if (swath != null) {
                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SWATH,
                            swath.getAttributeString("swathIdentifier", AbstractMetadata.NO_METADATA_STRING));
                }
            }
            final MetadataElement acqParams = equipment.getElement("acquisitionParameters");
            final MetadataElement acquisition = acqParams == null ? null
                    : acqParams.getElement("Acquisition");
            if (acquisition != null) {
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PASS,
                        acquisition.getAttributeString("orbitDirection", AbstractMetadata.NO_METADATA_STRING));
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SPH_DESCRIPTOR,
                        acquisition.getAttributeString("missionPhase", AbstractMetadata.NO_METADATA_STRING));
            }
        }

        // L2 is never SLC and never SRGR; mark as detected/geocoded amplitude product.
        setSLC(false);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SAMPLE_TYPE, "DETECTED");
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.srgr_flag, 1);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.abs_calibration_flag, 1);

        // TODO Phase 2: read annotation/*_annot.xml for raster dims, pixel spacing, processing parameters.
        // TODO Phase 2: read annotation/*.nc LUTs (incidence angle, FNF mask) and expose as tie-point grids.
    }

    @Override
    protected void addGeoCoding(final Product product) {
        // The COG already carries CrsGeoCoding (EPSG:4326 + geotransform). The first band
        // brings it in via setSourceImage; product geocoding follows when no other is set.
        if (product.getSceneGeoCoding() == null && product.getNumBands() > 0) {
            final Band first = product.getBandAt(0);
            if (first.getGeoCoding() != null) {
                product.setSceneGeoCoding(first.getGeoCoding());
            }
        }
    }

    @Override
    protected void addTiePointGrids(final Product product) {
        // L2 products are already geocoded via the COG. No SAR tie-point grids needed.
        // TODO Phase 2: optionally expose annotation LUTs (FNF mask, local incidence angle)
        // as tie-point grids when the annotation NetCDF is available.
    }

    @Override
    protected String getProductName() {
        return productName == null || productName.isEmpty()
                ? (productInputFile == null ? "BIOMASS_L2" : productInputFile.getName())
                : productName;
    }

    @Override
    protected String getProductType() {
        return productType == null || productType.isEmpty() ? "BIOMASS_L2" : productType;
    }

    @Override
    public Product createProduct() throws IOException {
        final MetadataElement newRoot = addMetaData();
        findImages(newRoot);

        // Scene dimensions come from the first loaded measurement band. We don't have
        // them from MPH metadata yet (Phase 2 will pull them from annotation XML).
        int sceneWidth = 0, sceneHeight = 0;
        for (final ReaderData data : bandProductMap.values()) {
            if (data.bandProduct != null) {
                sceneWidth = data.bandProduct.getSceneRasterWidth();
                sceneHeight = data.bandProduct.getSceneRasterHeight();
                break;
            }
        }
        if (sceneWidth == 0 || sceneHeight == 0) {
            throw new IOException("BIOMASS L2: no measurement bands found in product '" + getProductName() + "'.");
        }

        final Product product = new Product(getProductName(), getProductType(), sceneWidth, sceneHeight);
        updateProduct(product, newRoot);

        addBands(product);
        addTiePointGrids(product);
        addGeoCoding(product);

        return product;
    }
}
