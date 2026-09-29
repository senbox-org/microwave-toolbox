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
import org.esa.snap.core.datamodel.CrsGeoCoding;
import org.esa.snap.core.datamodel.MetadataAttribute;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.dataop.downloadable.XMLSupport;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.dataio.gdal.reader.plugins.GTiffDriverProductReaderPlugIn;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.datamodel.metadata.AbstractMetadataIO;
import org.esa.snap.engine_utilities.eo.Constants;
import org.jdom2.Document;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import ucar.ma2.DataType;
import ucar.nc2.Group;
import ucar.nc2.NetcdfFile;
import ucar.nc2.Variable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 * It opens L2 product directories / ZIPs, loads measurement COGs as bands with geocoding,
 * and reads both the MPH and the per-product {@code mainAnnotation} (acquisition time, orbit,
 * polarisations, processor version, pixel spacing). Still pending: exposing the
 * {@code annotation/*_lut.nc} layers (e.g. the {@code /FNF} Forest-Non-Forest mask) as bands,
 * and Phase-3 L2b extras (heatmap, acquisition-ID) — marked with TODOs.
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

        // The COG carries its own CrsGeoCoding (EPSG:4326 + geotransform). setSourceImage does
        // NOT transfer it, so copy it explicitly onto the new band. Doing it per band (not just
        // once at scene level) keeps geocoding correct for FD, whose cfm/fd/probability planes
        // can have different resolutions/extents.
        if (srcBand.getGeoCoding() != null) {
            ProductUtils.copyGeoCoding(srcBand, band);
        }
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

        // Enrich from the per-product mainAnnotation (acquisition times, orbit, polarisations,
        // processor version, pixel spacing). Raster dimensions are intentionally NOT taken from
        // here — the annotation's numberOfSamples/numberOfLines follow a lat/lon DGG convention
        // that is transposed relative to the COG raster; the COG remains authoritative for size.
        addAnnotationMetadata(absRoot, origProdRoot);

        // TODO Phase 2/3: expose annotation/*_lut.nc layers (e.g. /FNF Forest-Non-Forest mask) as bands.
    }

    private static final DateFormat L2_DATE_FORMAT = ProductData.UTC.createDateFormat("yyyy-MM-dd_HH:mm:ss");

    /**
     * Read the per-product {@code annotation/*_annot.xml} ({@code mainAnnotation}), attach it under
     * {@code Original_Product_Metadata/annotation}, and lift the useful fields into the abstracted
     * metadata. The MPH only carries a subset (and no acquisition time), so without this an L2
     * product opens with blank start/stop times.
     */
    private void addAnnotationMetadata(final MetadataElement absRoot, final MetadataElement origProdRoot) {
        final String annotFolder = getRootFolder() + "annotation";
        String[] files;
        try {
            files = listFiles(annotFolder);
        } catch (IOException e) {
            return; // no annotation folder in this layout; nothing to add
        }
        if (files == null) {
            return;
        }

        MetadataElement annotationElement = origProdRoot.getElement("annotation");
        if (annotationElement == null) {
            annotationElement = new MetadataElement("annotation");
            origProdRoot.addElement(annotationElement);
        }

        for (final String fileName : files) {
            if (!fileName.toLowerCase().endsWith("_annot.xml")) {
                continue;
            }
            final MetadataElement nameElem = new MetadataElement(fileName);
            try (final InputStream is = getInputStream(annotFolder + '/' + fileName)) {
                final Document doc = XMLSupport.LoadXML(is);
                AbstractMetadataIO.AddXMLMetadata(doc.getRootElement(), nameElem);
            } catch (Exception e) {
                SystemUtils.LOG.warning("BIOMASS L2: failed to parse annotation '" + fileName + "': " + e.getMessage());
                continue;
            }
            annotationElement.addElement(nameElem);

            final MetadataElement mainAnnotation = nameElem.getElement("mainAnnotation");
            if (mainAnnotation != null) {
                populateAbstractedFromAnnotation(absRoot, mainAnnotation);
            }
            return; // a single *_annot.xml describes the whole product
        }
    }

    private void populateAbstractedFromAnnotation(final MetadataElement absRoot, final MetadataElement mainAnnotation) {
        final MetadataElement product = mainAnnotation.getElement("product");
        if (product != null) {
            final ProductData.UTC start = parseTime(product, "startTime");
            final ProductData.UTC stop = parseTime(product, "stopTime");
            if (!start.equalElems(AbstractMetadata.NO_METADATA_UTC)) {
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_line_time, start);
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.STATE_VECTOR_TIME, start);
            }
            if (!stop.equalElems(AbstractMetadata.NO_METADATA_UTC)) {
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_line_time, stop);
            }
            setStringIfPresent(absRoot, AbstractMetadata.PASS, product, "orbitPass");
            setStringIfPresent(absRoot, AbstractMetadata.SPH_DESCRIPTOR, product, "missionPhaseID");
            setStringIfPresent(absRoot, AbstractMetadata.SWATH, product, "swath");
            setIntIfPresent(absRoot, AbstractMetadata.REL_ORBIT, product, "relativeOrbitNumber");
            setIntIfPresent(absRoot, AbstractMetadata.CYCLE, product, "majorCycleID");

            final String freq = childValue(product, "radarCarrierFrequency");
            if (freq != null) {
                try {
                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.radar_frequency,
                            Double.parseDouble(freq.trim()) / Constants.oneMillion);
                } catch (NumberFormatException ignore) { }
            }
            // absoluteOrbitNumber is a list of <val>; the first one is representative.
            setIntIfPresent(absRoot, AbstractMetadata.ABS_ORBIT, product.getElement("absoluteOrbitNumber"), "val");
        }

        final MetadataElement input = mainAnnotation.getElement("inputInformation");
        setPolarisations(absRoot, input == null ? null : input.getElement("polarisationList"));

        final MetadataElement processingParameters = mainAnnotation.getElement("processingParameters");
        if (processingParameters != null) {
            setStringIfPresent(absRoot, AbstractMetadata.ProcessingSystemIdentifier,
                    processingParameters, "processorVersion");
            final ProductData.UTC procTime = parseTime(processingParameters, "productGenerationTime");
            if (!procTime.equalElems(AbstractMetadata.NO_METADATA_UTC)) {
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PROC_TIME, procTime);
            }
        }

        // Approximate metric pixel spacing from the lat/lon grid step (L2 is geocoded in degrees).
        final MetadataElement rasterImage = mainAnnotation.getElement("rasterImage");
        if (rasterImage != null) {
            final String latSp = childValue(rasterImage, "latitudeSpacing");
            final String lonSp = childValue(rasterImage, "longitudeSpacing");
            final String firstLat = childValue(rasterImage, "firstLatitudeValue");
            if (latSp != null && lonSp != null) {
                try {
                    final double degToM = Constants.semiMajorAxis * Constants.DTOR; // ~111.3 km per degree
                    final double centreLatRad = firstLat != null ? Double.parseDouble(firstLat.trim()) * Constants.DTOR : 0.0;
                    final double azSpacing = Math.abs(Double.parseDouble(latSp.trim())) * degToM;
                    final double rgSpacing = Math.abs(Double.parseDouble(lonSp.trim())) * degToM * Math.cos(centreLatRad);
                    if (azSpacing > 0) AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_spacing, azSpacing);
                    if (rgSpacing > 0) AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_spacing, rgSpacing);
                } catch (NumberFormatException ignore) { }
            }
        }
    }

    private static void setPolarisations(final MetadataElement absRoot, final MetadataElement polList) {
        if (polList == null) {
            return;
        }
        final List<String> pols = new ArrayList<>();
        // Repeated leaf elements surface as repeated attributes on the parent...
        for (final MetadataAttribute attr : polList.getAttributes()) {
            if ("polarisation".equals(attr.getName())) {
                final String p = attr.getData().getElemString().trim();
                if (!p.isEmpty() && !pols.contains(p)) pols.add(p);
            }
        }
        // ...or as child elements, depending on AddXMLMetadata. Handle both.
        for (final MetadataElement child : polList.getElements()) {
            if ("polarisation".equals(child.getName())) {
                final String p = child.getAttributeString("polarisation", "").trim();
                if (!p.isEmpty() && !pols.contains(p)) pols.add(p);
            }
        }
        final String[] keys = {AbstractMetadata.mds1_tx_rx_polar, AbstractMetadata.mds2_tx_rx_polar,
                AbstractMetadata.mds3_tx_rx_polar, AbstractMetadata.mds4_tx_rx_polar};
        for (int i = 0; i < pols.size() && i < keys.length; i++) {
            AbstractMetadata.setAttribute(absRoot, keys[i], pols.get(i));
        }
    }

    /**
     * A leaf XML value may surface either as an attribute on {@code parent} or as a same-named
     * attribute on a {@code parent}-child element of the same name (depending on whether the XML
     * element carried attributes of its own). Check both; return {@code null} when absent.
     */
    private static String childValue(final MetadataElement parent, final String name) {
        if (parent == null) {
            return null;
        }
        String v = parent.getAttributeString(name, "");
        if (!v.isEmpty()) {
            return v;
        }
        final MetadataElement child = parent.getElement(name);
        if (child != null) {
            v = child.getAttributeString(name, "");
            if (!v.isEmpty()) {
                return v;
            }
        }
        return null;
    }

    private static void setStringIfPresent(final MetadataElement absRoot, final String absKey,
                                           final MetadataElement parent, final String name) {
        final String v = childValue(parent, name);
        if (v != null) {
            AbstractMetadata.setAttribute(absRoot, absKey, v);
        }
    }

    private static void setIntIfPresent(final MetadataElement absRoot, final String absKey,
                                        final MetadataElement parent, final String name) {
        final String v = childValue(parent, name);
        if (v != null) {
            try {
                AbstractMetadata.setAttribute(absRoot, absKey, Integer.parseInt(v.trim()));
            } catch (NumberFormatException ignore) { }
        }
    }

    private static ProductData.UTC parseTime(final MetadataElement parent, final String name) {
        final String s = childValue(parent, name);
        if (s == null) {
            return AbstractMetadata.NO_METADATA_UTC;
        }
        try {
            final ProductData.UTC utc = AbstractMetadata.parseUTC(s.replace("UTC=", "").replace("T", "_"), L2_DATE_FORMAT);
            return utc == null ? AbstractMetadata.NO_METADATA_UTC : utc;
        } catch (Exception e) {
            return AbstractMetadata.NO_METADATA_UTC;
        }
    }

    @Override
    protected void addGeoCoding(final Product product) {
        if (product.getSceneGeoCoding() != null) {
            return;
        }
        // Prefer copying the scene geocoding straight from a source COG whose raster matches
        // the product scene (the band used to size the product is guaranteed to match).
        for (final ReaderData data : bandProductMap.values()) {
            final Product bp = data.bandProduct;
            if (bp != null && bp.getSceneGeoCoding() != null
                    && bp.getSceneRasterWidth() == product.getSceneRasterWidth()
                    && bp.getSceneRasterHeight() == product.getSceneRasterHeight()) {
                ProductUtils.copyGeoCoding(bp, product);
                break;
            }
        }
        // Fall back to the first band's (per-band) geocoding set in addBandFromCog.
        if (product.getSceneGeoCoding() == null && product.getNumBands() > 0
                && product.getBandAt(0).getGeoCoding() != null) {
            product.setSceneGeoCoding(product.getBandAt(0).getGeoCoding());
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

        // Scene dimensions come from the first loaded measurement COG. The annotation reports
        // numberOfSamples/numberOfLines in a lat/lon DGG convention that is transposed relative
        // to the raster, so the COG (not the annotation) is the authoritative size source.
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
        addFNFMaskBand(product);

        return product;
    }

    /**
     * Expose the Forest/Non-Forest mask carried in {@code annotation/*_lut.nc} (group {@code /FNF})
     * as a band. The FNF is an external C3S land-cover aggregate cropped to the scene; it lives on
     * its own lat/lon grid (potentially a different resolution than the measurement COGs), so it is
     * added as a self-sized band with its own {@link CrsGeoCoding} rather than resampled — resampling
     * a categorical mask would be meaningless. Absent/unreadable LUTs are a silent no-op.
     */
    private void addFNFMaskBand(final Product product) {
        final String annotFolder = getRootFolder() + "annotation";
        final String ncName = findLutNetCDF(annotFolder);
        if (ncName == null) {
            return;
        }
        final File ncFile;
        try {
            ncFile = getFile(annotFolder + '/' + ncName); // extracts from the zip when needed
        } catch (IOException e) {
            SystemUtils.LOG.warning("BIOMASS L2: cannot access LUT '" + ncName + "': " + e.getMessage());
            return;
        }

        try (final NetcdfFile nc = NetcdfFile.open(ncFile.getAbsolutePath())) {
            Variable fnfVar = null, latVar = null, lonVar = null;
            for (final Variable v : collectVariables(nc)) {
                final String n = v.getShortName().toLowerCase();
                if (fnfVar == null && v.getRank() >= 2 && n.contains("fnf")) {
                    fnfVar = v;
                } else if (latVar == null && v.getRank() == 1 && n.startsWith("lat")) {
                    latVar = v;
                } else if (lonVar == null && v.getRank() == 1 && n.startsWith("lon")) {
                    lonVar = v;
                }
            }
            if (fnfVar == null) {
                return; // no FNF layer in this product's LUT
            }

            final int[] shape = fnfVar.getShape();
            final int height = shape[shape.length - 2];
            final int width = shape[shape.length - 1];
            final byte[] raw = (byte[]) fnfVar.read().get1DJavaArray(DataType.BYTE);

            final double[] lats = latVar != null ? (double[]) latVar.read().get1DJavaArray(DataType.DOUBLE) : null;
            final double[] lons = lonVar != null ? (double[]) lonVar.read().get1DJavaArray(DataType.DOUBLE) : null;

            // Reorder to north-up / west-east per the axis ordering so the raster aligns with the
            // geocoding (CrsGeoCoding maps row 0 to the northern edge, column 0 to the western edge).
            final boolean latDescending = lats == null || lats.length < 2 || lats[1] < lats[0];
            final boolean lonAscending = lons == null || lons.length < 2 || lons[1] > lons[0];
            final byte[] dest = new byte[width * height];
            for (int r = 0; r < height; r++) {
                final int sr = latDescending ? r : (height - 1 - r);
                for (int c = 0; c < width; c++) {
                    final int sc = lonAscending ? c : (width - 1 - c);
                    dest[r * width + c] = raw[sr * width + sc];
                }
            }

            final Band fnf = new Band("Forest_Non_Forest_Mask", ProductData.TYPE_UINT8, width, height);
            final ucar.nc2.Attribute descAttr = fnfVar.findAttribute("description");
            fnf.setDescription(descAttr != null && descAttr.getStringValue() != null
                    ? descAttr.getStringValue()
                    : "Forest/Non-Forest mask (external C3S land-cover aggregate)");
            fnf.setUnit("flag");
            fnf.setNoDataValue(255);
            fnf.setNoDataValueUsed(true);
            fnf.setRasterData(ProductData.createUnsignedInstance(dest));

            if (lats != null && lons != null && lats.length >= 2 && lons.length >= 2
                    && product.getSceneGeoCoding() != null) {
                try {
                    final CoordinateReferenceSystem crs = product.getSceneGeoCoding().getMapCRS();
                    final double pixelSizeX = Math.abs(lons[1] - lons[0]);
                    final double pixelSizeY = Math.abs(lats[1] - lats[0]);
                    final double easting = Math.min(lons[0], lons[lons.length - 1]);   // west-most pixel centre
                    final double northing = Math.max(lats[0], lats[lats.length - 1]);  // north-most pixel centre
                    fnf.setGeoCoding(new CrsGeoCoding(crs, width, height, easting, northing, pixelSizeX, pixelSizeY));
                } catch (Exception e) {
                    SystemUtils.LOG.warning("BIOMASS L2: could not geocode FNF mask: " + e.getMessage());
                }
            }

            product.addBand(fnf);
        } catch (Exception e) {
            SystemUtils.LOG.warning("BIOMASS L2: failed to read FNF mask from '" + ncName + "': " + e.getMessage());
        }
    }

    private String findLutNetCDF(final String annotFolder) {
        try {
            final String[] files = listFiles(annotFolder);
            if (files != null) {
                for (final String f : files) {
                    if (f.toLowerCase().endsWith(".nc")) {
                        return f;
                    }
                }
            }
        } catch (IOException ignore) {
            // no annotation folder / not listable
        }
        return null;
    }

    private static List<Variable> collectVariables(final NetcdfFile nc) {
        final List<Variable> vars = new ArrayList<>(nc.getRootGroup().getVariables());
        for (final Group g : nc.getRootGroup().getGroups()) {
            vars.addAll(g.getVariables());
        }
        return vars;
    }
}
