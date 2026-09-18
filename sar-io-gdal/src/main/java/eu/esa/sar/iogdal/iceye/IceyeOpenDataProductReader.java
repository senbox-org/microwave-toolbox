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
package eu.esa.sar.iogdal.iceye;

import com.bc.ceres.core.ProgressMonitor;
import com.bc.ceres.multilevel.support.DefaultMultiLevelImage;
import eu.esa.sar.commons.io.JSONProductDirectory;
import eu.esa.sar.commons.io.SARReader;
import it.geosolutions.imageio.plugins.tiff.TIFFField;
import it.geosolutions.imageioimpl.plugins.tiff.TIFFIFD;
import it.geosolutions.imageioimpl.plugins.tiff.TIFFImageMetadata;
import it.geosolutions.imageioimpl.plugins.tiff.TIFFImageReader;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.dataio.ProductReaderPlugIn;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.TiePointGrid;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.core.util.io.FileUtils;
import org.esa.snap.dataio.gdal.reader.plugins.GTiffDriverProductReaderPlugIn;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.datamodel.metadata.AbstractMetadataIO;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads an ICEYE Open Data Cloud-Optimized GeoTIFF.
 * <p>
 * The delivery is a COG plus a side-car STAC item. The same STAC
 * {@code properties} object is also embedded in the TIFF as the
 * {@code ICEYE_PROPERTIES} GDAL metadata item, so the COG on its own is
 * self-describing and the side-car is only needed when it is not.
 * <p>
 * Three things separate this from the AML/CPX reader next door, which is why it
 * is a reader of its own rather than a branch in that one:
 * <ul>
 *   <li>the metadata item is {@code ICEYE_PROPERTIES}, not {@code METADATA_JSON};</li>
 *   <li>the schema is flat STAC, sharing no key with the nested one;</li>
 *   <li>there is no ModelTransformation tag (34264) to geocode from - the
 *       delivery carries a ModelTiepoint grid (33922), whose {@code proj:transform}
 *       equivalent in the STAC properties reproduces it exactly.</li>
 * </ul>
 * Raster access goes through GDAL so the COG's tiling and overviews are used;
 * the image is rotated into SNAP's SAR convention by
 * {@link IceyeTransposedMultiLevelSource}.
 *
 * @author Luis Veci
 */
public abstract class IceyeOpenDataProductReader extends SARReader {

    private static final GTiffDriverProductReaderPlugIn gdalReaderPlugIn = new GTiffDriverProductReaderPlugIn();

    private static final int TIFF_TAG_IMAGE_WIDTH = 256;
    private static final int TIFF_TAG_IMAGE_LENGTH = 257;
    private static final int TIFF_TAG_GDAL_METADATA = 42112;

    private static final int TIE_POINT_GRID_SIZE = 11;

    /** Phase is valid over the whole of [-pi, pi], so its no-data has to sit outside it. */
    private static final double PHASE_NO_DATA = 99999.0;

    protected IceyeOpenDataMetadata metadata;
    protected Product bandProduct;
    /** SNAP raster width - the number of range samples. */
    protected int rangeSamples;
    /** SNAP raster height - the number of azimuth lines. */
    protected int azimuthLines;
    /** Left-looking acquisitions store azimuth the other way round; see {@link IceyeTransposedMultiLevelSource}. */
    protected boolean lookLeft;

    private final Map<String, String> gdalItems = new LinkedHashMap<>();
    private ImageInputStream inputStream;
    private ProductReader gdalReader;

    protected IceyeOpenDataProductReader(final ProductReaderPlugIn readerPlugIn) {
        super(readerPlugIn);
    }

    @Override
    protected synchronized Product readProductNodesImpl() throws IOException {
        final Path inputPath = ReaderUtils.getPathFromInput(getInput());
        if (inputPath == null) {
            throw new IOException("Unable to interpret " + getInput() + " as a file path");
        }
        final File inputFile = inputPath.toFile();

        final TIFFImageMetadata tiffMetadata = readTiffMetadata(inputFile);
        final TIFFIFD rootIFD = tiffMetadata.getRootIFD();

        readGdalItems(rootIFD, inputFile);
        metadata = readStacProperties(inputFile);

        // ICEYE stores azimuth along the TIFF columns and range along the rows.
        azimuthLines = intField(rootIFD, TIFF_TAG_IMAGE_WIDTH, metadata.getAzimuthLines());
        rangeSamples = intField(rootIFD, TIFF_TAG_IMAGE_LENGTH, metadata.getRangeSamples());
        lookLeft = metadata.isLookLeft();
        if (rangeSamples <= 0 || azimuthLines <= 0) {
            throw new IOException("Raster dimensions not found in " + inputFile.getName());
        }

        final String productName = FileUtils.getFilenameWithoutExtension(inputFile);
        final Product product = new Product(productName, metadata.getProductType(),
                rangeSamples, azimuthLines, this);
        product.setFileLocation(inputFile);
        product.setDescription(productName + " - " + metadata.getProductType()
                + " - " + metadata.getPlatform());
        product.setStartTime(metadata.getFirstLineTime());
        product.setEndTime(metadata.getLastLineTime());

        gdalReader = gdalReaderPlugIn.createReaderInstance();
        bandProduct = gdalReader.readProductNodes(inputFile, null);
        if (bandProduct == null) {
            throw new IOException("GDAL could not open " + inputFile.getName());
        }

        addMetadataToProduct(product, inputFile);
        addBandsToProduct(product);
        addGeoCodingToProduct(product);
        addTiePointGridsToProduct(product);

        final Band firstBand = product.getBandAt(0);
        if (firstBand != null && firstBand.isSourceImageSet()) {
            product.setNumResolutionsMax(firstBand.getSourceImage().getModel().getLevelCount());
        }

        addCommonSARMetadata(product);
        setQuicklookBandName(product);
        product.setModified(false);
        return product;
    }

    // ------------------------------------------------------------------
    // TIFF header
    // ------------------------------------------------------------------

    private TIFFImageMetadata readTiffMetadata(final File inputFile) throws IOException {
        inputStream = ImageIO.createImageInputStream(inputFile);
        if (inputStream == null) {
            throw new IOException("Unable to open " + inputFile.getName());
        }
        final Iterator<ImageReader> readers = ImageIO.getImageReaders(inputStream);
        while (readers.hasNext()) {
            final ImageReader reader = readers.next();
            if (reader instanceof TIFFImageReader) {
                final TIFFImageReader tiffReader = (TIFFImageReader) reader;
                tiffReader.setInput(inputStream, false);
                final TIFFImageMetadata tiffMetadata = (TIFFImageMetadata) tiffReader.getImageMetadata(0);
                if (tiffMetadata == null) {
                    throw new IOException("No TIFF header in " + inputFile.getName());
                }
                return tiffMetadata;
            }
        }
        throw new IOException("No TIFF reader available for " + inputFile.getName());
    }

    /** Flattens the {@code <GDALMetadata>} document in tag 42112 into name/value pairs. */
    private void readGdalItems(final TIFFIFD rootIFD, final File inputFile) throws IOException {
        final TIFFField field = rootIFD.getTIFFField(TIFF_TAG_GDAL_METADATA);
        if (field == null) {
            return;
        }
        final Document document = parseXML(field.getAsString(0));
        if (document == null || document.getFirstChild() == null) {
            SystemUtils.LOG.warning("ICEYE: unreadable GDAL metadata in " + inputFile.getName());
            return;
        }
        final NodeList children = document.getFirstChild().getChildNodes();
        for (int i = 0; i < children.getLength(); ++i) {
            final Node child = children.item(i);
            final NamedNodeMap attributes = child.getAttributes();
            if (attributes == null || attributes.getNamedItem("name") == null) {
                continue;
            }
            final String name = attributes.getNamedItem("name").getNodeValue();
            // per-sample items repeat the same name; the first is sample 0
            gdalItems.putIfAbsent(name, unescapeXML(child.getTextContent()));
        }
    }

    /**
     * ICEYE escapes the embedded JSON twice, so the XML parser leaves a layer
     * of {@code &quot;} behind and the blob will not parse as JSON.
     */
    static String unescapeXML(final String text) {
        if (text == null || text.indexOf('&') < 0) {
            return text;
        }
        return text.replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    static Document parseXML(final String xml) {
        try {
            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            SystemUtils.LOG.severe("ICEYE: " + e.getMessage());
            return null;
        }
    }

    protected String getGdalItem(final String name) {
        return gdalItems.get(name);
    }

    protected Double getGdalItemAsDouble(final String name) {
        final String value = gdalItems.get(name);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Double.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Prefers the copy embedded in the COG so a product opens without its
     * side-car, and falls back to the side-car STAC item when the TIFF was
     * written without one.
     */
    private IceyeOpenDataMetadata readStacProperties(final File inputFile) throws IOException {
        final String embedded = gdalItems.get(IceyeOpenDataConstants.ICEYE_PROPERTIES);
        if (embedded != null && !embedded.trim().isEmpty()) {
            return IceyeOpenDataMetadata.fromPropertiesJSON(embedded);
        }
        final File stacItem = FileUtils.exchangeExtension(inputFile, IceyeOpenDataConstants.JSON_EXTENSION);
        if (stacItem.exists()) {
            return IceyeOpenDataMetadata.fromStacItem(stacItem);
        }
        throw new IOException("No ICEYE metadata in " + inputFile.getName()
                + ": the TIFF carries no " + IceyeOpenDataConstants.ICEYE_PROPERTIES
                + " item and " + stacItem.getName() + " is not beside it");
    }

    private static int intField(final TIFFIFD ifd, final int tag, final int fallback) {
        final TIFFField field = ifd.getTIFFField(tag);
        return field == null ? fallback : field.getAsInt(0);
    }

    // ------------------------------------------------------------------
    // metadata
    // ------------------------------------------------------------------

    private void addMetadataToProduct(final Product product, final File inputFile) {
        final MetadataElement root = product.getMetadataRoot();
        try {
            final MetadataElement origMeta = AbstractMetadata.addOriginalProductMetadata(root);
            AbstractMetadataIO.AddXMLMetadata(JSONProductDirectory.jsonToXML(
                    IceyeOpenDataConstants.PRODUCT_METADATA, metadata.getProperties()), origMeta);
        } catch (Exception e) {
            SystemUtils.LOG.severe("ICEYE: unable to add original metadata: " + e.getMessage());
        }

        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(root);
        metadata.populateAbstractedMetadata(absRoot);

        // the raster is authoritative over proj:shape, which can lag a re-tiling
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.num_samples_per_line, rangeSamples);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.num_output_lines, azimuthLines);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.TOT_SIZE,
                inputFile.length() / (1024.0 * 1024.0));

        final String productName = getGdalItem(IceyeOpenDataConstants.PRODUCT_NAME_ITEM);
        if (productName != null) {
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PRODUCT, productName);
        }
    }

    // ------------------------------------------------------------------
    // bands
    // ------------------------------------------------------------------

    private void addBandsToProduct(final Product product) throws IOException {
        final String polarization = metadata.getPolarization();
        final String suffix = polarization == null ? "" : '_' + polarization;

        final Band amplitudeBand = addTransposedBand(product,
                IceyeConstants.amplitude_band_prefix + polarization,
                IceyeConstants.AMPLITUDE_BAND_INDEX, Unit.AMPLITUDE);

        // the GRD stores DN; the delivery's GDAL SCALE/OFFSET items convert to amplitude
        final Double scale = getGdalItemAsDouble(IceyeOpenDataConstants.SCALE_ITEM);
        if (scale != null && scale != 0) {
            amplitudeBand.setScalingFactor(scale);
        }
        final Double offset = getGdalItemAsDouble(IceyeOpenDataConstants.OFFSET_ITEM);
        if (offset != null) {
            amplitudeBand.setScalingOffset(offset);
        }

        addProductSpecificBands(product, polarization, suffix);
    }

    /**
     * Wraps a GDAL band in the transpose that takes it from the delivery's
     * azimuth-by-range layout into SNAP's range-by-azimuth one.
     */
    protected Band addTransposedBand(final Product product, final String name,
                                     final int gdalBandIndex, final String unit) throws IOException {
        if (gdalBandIndex >= bandProduct.getNumBands()) {
            throw new IOException("Expected at least " + (gdalBandIndex + 1)
                    + " raster band(s) in " + product.getName() + ", found " + bandProduct.getNumBands());
        }
        final Band gdalBand = bandProduct.getBandAt(gdalBandIndex);

        final Band band = new Band(name, gdalBand.getDataType(), rangeSamples, azimuthLines);
        band.setUnit(unit);
        // The COG declares a no-data of 0, which marks fill outside the swath.
        // That holds for amplitude but not for phase, where 0 is a real value,
        // so phase gets the out-of-range sentinel the other ICEYE readers use.
        band.setNoDataValue(Unit.PHASE.equals(unit) ? PHASE_NO_DATA : gdalBand.getNoDataValue());
        band.setNoDataValueUsed(true);
        band.setSourceImage(new DefaultMultiLevelImage(new IceyeTransposedMultiLevelSource(
                gdalBand.getSourceImage(), rangeSamples, azimuthLines, lookLeft)));
        product.addBand(band);
        return band;
    }

    protected abstract void addProductSpecificBands(Product product, String polarization, String suffix)
            throws IOException;

    // ------------------------------------------------------------------
    // geocoding and tie point grids
    // ------------------------------------------------------------------

    /**
     * The corners come from {@code proj:transform}, which reproduces the
     * delivered ModelTiepoint grid exactly. Which TIFF column is the first
     * azimuth line depends on the look side, so the corners follow the same
     * rotation as the raster.
     */
    private void addGeoCodingToProduct(final Product product) {
        if (metadata.getGeoTransform().length < 6) {
            SystemUtils.LOG.warning("ICEYE: no proj:transform, product will have no geocoding");
            return;
        }
        ReaderUtils.addGeoCoding(product,
                metadata.getCornerLatitudes(rangeSamples, azimuthLines),
                metadata.getCornerLongitudes(rangeSamples, azimuthLines));
    }

    private void addTiePointGridsToProduct(final Product product) {
        final int gridWidth = TIE_POINT_GRID_SIZE;
        final int gridHeight = TIE_POINT_GRID_SIZE;
        final double subSamplingX = (double) rangeSamples / (gridWidth - 1);
        final double subSamplingY = (double) azimuthLines / (gridHeight - 1);

        final float[] incidenceAngles = new float[gridWidth * gridHeight];
        final float[] slantRangeTimes = new float[gridWidth * gridHeight];
        for (int j = 0; j < gridWidth; ++j) {
            final double rangeSample = j * subSamplingX;
            final float incidence = (float) metadata.getIncidenceAngle(rangeSample);
            final float slantRangeTime = (float) metadata.getSlantRangeTime(rangeSample);
            for (int i = 0; i < gridHeight; ++i) {
                incidenceAngles[i * gridWidth + j] = incidence;
                slantRangeTimes[i * gridWidth + j] = slantRangeTime;
            }
        }

        final TiePointGrid incidenceGrid = new TiePointGrid(OperatorUtils.TPG_INCIDENT_ANGLE,
                gridWidth, gridHeight, 0, 0, subSamplingX, subSamplingY, incidenceAngles);
        incidenceGrid.setUnit(Unit.DEGREES);
        product.addTiePointGrid(incidenceGrid);

        final TiePointGrid slantRangeTimeGrid = new TiePointGrid(OperatorUtils.TPG_SLANT_RANGE_TIME,
                gridWidth, gridHeight, 0, 0, subSamplingX, subSamplingY, slantRangeTimes);
        slantRangeTimeGrid.setUnit(Unit.NANOSECONDS);
        product.addTiePointGrid(slantRangeTimeGrid);
    }

    // ------------------------------------------------------------------

    @Override
    public synchronized void close() throws IOException {
        if (bandProduct != null) {
            bandProduct.dispose();
            bandProduct = null;
        }
        if (gdalReader != null) {
            gdalReader.close();
            gdalReader = null;
        }
        if (inputStream != null) {
            inputStream.close();
            inputStream = null;
        }
        super.close();
    }

    /** Band data is served by the source images set above. */
    @Override
    protected void readBandRasterDataImpl(int sourceOffsetX, int sourceOffsetY,
                                          int sourceWidth, int sourceHeight,
                                          int sourceStepX, int sourceStepY,
                                          Band destBand, int destOffsetX, int destOffsetY,
                                          int destWidth, int destHeight,
                                          ProductData destBuffer, ProgressMonitor pm) {
    }
}
