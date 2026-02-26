package eu.esa.sar.io.iceye;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.io.ImageIOFile;
import eu.esa.sar.commons.io.SARReader;
import eu.esa.sar.commons.product.Missions;
import eu.esa.sar.io.geotiffxml.GeoTiffUtils;
import eu.esa.sar.io.iceye.util.IceyeConstants;
import it.geosolutions.imageio.plugins.tiff.TIFFField;
import it.geosolutions.imageioimpl.plugins.tiff.TIFFImageMetadata;
import it.geosolutions.imageioimpl.plugins.tiff.TIFFImageReader;
import org.apache.commons.math3.util.FastMath;
import org.esa.snap.core.dataio.IllegalFileFormatException;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.dataio.ProductReaderPlugIn;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataAttribute;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.TiePointGrid;
import org.esa.snap.core.datamodel.quicklooks.Quicklook;
import org.esa.snap.core.util.ImageUtils;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.dataio.geotiff.GeoTiffProductReaderPlugIn;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.*;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.SampleModel;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * @author Ahmad Hamouda
 */
public class IceyeGRDProductReader extends SARReader {

    private static final GeoTiffProductReaderPlugIn geoTiffPlugIn = new GeoTiffProductReaderPlugIn();
    private Product bandProduct;

    protected transient final Map<String, ImageIOFile> bandImageFileMap = new TreeMap<>();
    protected transient final Map<Band, ImageIOFile.BandInfo> bandMap = new HashMap<>();

    private final DateFormat standardDateFormat = ProductData.UTC.createDateFormat("yyyy-MM-dd'T'HH:mm:ss");
    private Map<String, String> tiffFields = null;
    private Product product = null;
    private boolean isComplex = false;

    /**
     * Constructs a new abstract product reader.
     *
     * @param readerPlugIn the reader plug-in which created this reader, can be
     *                     <code>null</code> for internal reader
     *                     implementations
     */
    public IceyeGRDProductReader(final ProductReaderPlugIn readerPlugIn) {
        super(readerPlugIn);
    }

    @Override
    public void close() throws IOException {
        if (product != null) {
            product = null;
            tiffFields = null;
        }
        if(bandProduct != null) {
            bandProduct.dispose();
        }
        final Set<String> keys = bandImageFileMap.keySet();                           // The set of keys in the map.
        for (String key : keys) {
            final ImageIOFile img = bandImageFileMap.get(key);
            img.close();
        }
        super.close();
    }


    private static void addAttribute(MetadataElement meta, String name, String value) {
        MetadataAttribute attribute = new MetadataAttribute(name, 41, 1);
        if (value.isEmpty()) {
            value = " ";
        }
        attribute.getData().setElems(value);
        meta.addAttribute(attribute);
    }

    public static Document convertStringToXMLDocument(String xmlString) {
        // Parser that produces DOM object trees from XML content
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

        // API to obtain DOM Document instance
        DocumentBuilder builder = null;
        try {
            // Create DocumentBuilder with default configuration
            builder = factory.newDocumentBuilder();

            // Parse the content to Document object
            return builder.parse(new InputSource(new StringReader(xmlString)));
        } catch (Exception e) {
            SystemUtils.LOG.severe(e.getMessage());
        }
        return null;
    }

    private static double[] convertStringToDoubleArray(String string) {
        // Remove the brackets and trim any leading/trailing whitespace
        String cleanedString = string.substring(1, string.length() - 1).trim();

        // Split the string by one or more spaces (\\s+) OR a comma (,)
        // | acts as an OR operator in regex
        // We also handle optional spaces around the comma using \\s*
        String[] stringNumbers = cleanedString.split("\\s*,\\s*|\\s+");

        return Arrays.stream(stringNumbers)
                .mapToDouble(Double::parseDouble)
                .toArray();
    }

    private static String[] convertDateStringToStringArray(String string) {
        return string.substring(1, string.length() - 1).replace("'", "").trim().split(",");
    }

    private static String[] convertDateStringToStringArrayBySpace(String string) {
        return string.replace("\n", " ").replaceAll("\\s+", " ").replace("  ", " ").replace("[", "").replace("]", "")
                .trim().split(" ");
    }

    /**
     * Provides an implementation of the <code>readProductNodes</code> interface
     * method. Clients implementing this
     * method can be sure that the input object and eventually the subset
     * information has already been set.
     * <p/>
     * <p>
     * This method is called as a last step in the
     * <code>readProductNodes(input, subsetInfo)</code> method.
     */
    @Override
    protected Product readProductNodesImpl() {
        product = null;
        tiffFields = null;
        try {

            final Path inputPath = ReaderUtils.getPathFromInput(getInput());
            if (inputPath == null) {
                close();
                throw new IllegalFileFormatException("File Could not be interpreted by the reader.");
            }

            final File inputFile = inputPath.toFile();
            TIFFImageMetadata tempFile = getTiffMetadata(inputFile);
            if (tempFile == null) {
                close();
                throw new IllegalFileFormatException("File metadata Could not be interpreted by the reader.");
            }

            Document document = null;
            for (int i = 0; i < tempFile.getRootIFD().getTIFFFields().length; i++) {
                TIFFField tiffFields = tempFile.getRootIFD().getTIFFFields()[i];
                if (tiffFields.getType() == 2 && tiffFields.getData() != null
                        && tiffFields.getData() instanceof String[] && ((String[]) tiffFields.getData()).length > 0
                        && ((String[]) tiffFields.getData())[0].startsWith(IceyeConstants.GDALMETADATA)) {
                    document = convertStringToXMLDocument(((String[]) tiffFields.getData())[0]);
                }
            }
            if (document == null || document.getFirstChild() == null
                    || document.getFirstChild().getChildNodes() == null) {
                close();
                throw new IllegalFileFormatException("No ICEYE metadata variables found which could\n" +
                        "be interpreted as remote sensing bands."); /* I18N */
            }
            NodeList childNodes = document.getFirstChild().getChildNodes();
            this.tiffFields = new HashMap<>();
            for (int i = 1; i < childNodes.getLength(); i += 2) {
                this.tiffFields.put(childNodes.item(i).getAttributes().item(0).getNodeValue(),
                        childNodes.item(i).getTextContent());
            }
            final String productType = get(IceyeConstants.PRODUCT_TYPE);
            final int rasterWidth = Integer
                    .parseInt(get(IceyeConstants.NUM_SAMPLES_PER_LINE));
            final int rasterHeight = Integer
                    .parseInt(get(IceyeConstants.NUM_OUTPUT_LINES));

            product = new Product(inputFile.getName().replace(".tif", ""),
                    productType,
                    rasterWidth, rasterHeight,
                    this);
            product.setFileLocation(inputFile);
            String description = get(IceyeConstants.PRODUCT) + " - " +
                    get(IceyeConstants.PRODUCT_TYPE) + " - " +
                    get(IceyeConstants.SPH_DESCRIPTOR) + " - " +
                    get(IceyeConstants.MISSION);
            product.setDescription(description);
            product.setStartTime(ProductData.UTC.parse(
                    get(IceyeConstants.ACQUISITION_START_UTC), standardDateFormat));
            product.setEndTime(ProductData.UTC
                    .parse(get(IceyeConstants.ACQUISITION_END_UTC), standardDateFormat));

            addMetadataToProduct();
            addBandsToProduct();
            addGeoCodingToProduct();
            addTiePointGridsToProduct();
            addCommonSARMetadata(product);
            addDopplerCentroidCoefficients();

            product.getGcpGroup();
            product.setModified(false);
            setQuicklookBandName(product);
            addQuicklook(product, Quicklook.DEFAULT_QUICKLOOK_NAME, inputFile);

            return product;
        } catch (Exception e) {
            SystemUtils.LOG.severe(e.getMessage());
        }
        return product;
    }

    private TIFFImageMetadata getTiffMetadata(File inputFile) {
        TIFFImageMetadata iioMetadata = null;

        try (ImageInputStream iis = ImageUtils.getImageInputStream(inputFile)) {
            Iterator<ImageReader> imageReaders = ImageIO.getImageReaders(iis);
            TIFFImageReader imageReader = null;

            while (imageReaders.hasNext()) {
                final ImageReader reader = imageReaders.next();
                if (reader instanceof TIFFImageReader) {
                    imageReader = (TIFFImageReader) reader;
                    imageReader.setInput(iis);
                    break;
                }
            }
            if (imageReader == null) {
                close();
                throw new IllegalFileFormatException("Image reader could not be found.");
            }
            iioMetadata = (TIFFImageMetadata) imageReader.getImageMetadata(0);
        } catch (IOException e) {
            SystemUtils.LOG.severe(e.getMessage());
        }
        return iioMetadata;
    }

    private void addMetadataToProduct() {
        final MetadataElement origMetadataRoot = AbstractMetadata.addOriginalProductMetadata(product.getMetadataRoot());
        for (Map.Entry<String, String> variable : tiffFields.entrySet()) {
            addAttribute(origMetadataRoot, variable.getKey(), variable.getValue());
        }
        addAbstractedMetadataHeader(product.getMetadataRoot());
    }

    private String get(final String tag) {
        if (tiffFields != null && tiffFields.containsKey(tag.toUpperCase())) {
            return tiffFields.get(tag.toUpperCase());
        }
        SystemUtils.LOG.severe("Tag '" + tag + "' not found in TIFF metadata.");
        return null;
    }

    private void addAbstractedMetadataHeader(MetadataElement root) {

        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(root);

        try {
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PRODUCT,
                    get(IceyeConstants.PRODUCT));
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PRODUCT_TYPE,
                    get(IceyeConstants.PRODUCT_TYPE));
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SPH_DESCRIPTOR,
                    get(IceyeConstants.SPH_DESCRIPTOR));
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.MISSION, Missions.ICEYE);

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ACQUISITION_MODE,
                    get(IceyeConstants.ACQUISITION_MODE));
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.antenna_pointing,
                    get(IceyeConstants.ANTENNA_POINTING));
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.BEAMS, IceyeConstants.BEAMS_DEFAULT_VALUE);

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PROC_TIME, ProductData.UTC
                    .parse(get(IceyeConstants.PROC_TIME_UTC), standardDateFormat));

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ProcessingSystemIdentifier,
                    get(IceyeConstants.PROCESSING_SYSTEM_IDENTIFIER));
            String cycle = get(IceyeConstants.CYCLE);
            if(cycle != null) {
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.CYCLE, Integer.parseInt(cycle));
            }
            String relOrbit = get(IceyeConstants.REL_ORBIT);
            if(relOrbit != null) {
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.REL_ORBIT, Integer.parseInt(relOrbit));
            }
            String absOrbit = get(IceyeConstants.ABS_ORBIT);
            if(absOrbit != null) {
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ABS_ORBIT, Integer.parseInt(absOrbit));
            }
            String incNear = get(IceyeConstants.INCIDENCE_NEAR);
            if (incNear != null) {
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.incidence_near, Double.valueOf(incNear));
            }
            String incFar = get(IceyeConstants.INCIDENCE_FAR);
            if (incFar != null) {
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.incidence_far, Double.valueOf(incFar));
            }

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.slice_num, IceyeConstants.SLICE_NUM_DEFAULT_VALUE);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.data_take_id,
                    IceyeConstants.DATA_TAKE_ID_DEFAULT_VALUE);

            String geoRefSystem = IceyeConstants.GEO_REFERENCE_SYSTEM_DEFAULT_VALUE;
            if (get(IceyeConstants.GEO_REFERENCE_SYSTEM) != null) {
                geoRefSystem = get(IceyeConstants.GEO_REFERENCE_SYSTEM);
            }
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.geo_ref_system, geoRefSystem);

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_line_time, ProductData.UTC
                    .parse(get(IceyeConstants.FIRST_LINE_TIME), standardDateFormat));
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_line_time, ProductData.UTC
                    .parse(get(IceyeConstants.LAST_LINE_TIME), standardDateFormat));

            double[] firstNear = convertStringToDoubleArray(get(IceyeConstants.FIRST_NEAR));
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_near_lat, firstNear[2]);

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_near_long, firstNear[3]);
            double[] firstFar = convertStringToDoubleArray(get(IceyeConstants.FIRST_FAR));
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_far_lat, firstFar[2]);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_far_long, firstFar[3]);
            double[] lastNear = convertStringToDoubleArray(get(IceyeConstants.LAST_NEAR));
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_near_lat, lastNear[2]);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_near_long, lastNear[3]);
            double[] lastFar = convertStringToDoubleArray(get(IceyeConstants.LAST_FAR));
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_far_lat, lastFar[2]);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_far_long, lastFar[3]);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PASS, get(IceyeConstants.PASS));

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SAMPLE_TYPE, getSampleType());
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.mds1_tx_rx_polar,
                    get(IceyeConstants.MDS1_TX_RX_POLAR));

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_looks,
                    Float.parseFloat(get(IceyeConstants.AZIMUTH_LOOKS)));
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_looks,
                    Float.parseFloat(get(IceyeConstants.RANGE_LOOKS)));

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_spacing,
                    Float.parseFloat(get(IceyeConstants.RANGE_SPACING)));

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_spacing,
                    Float.parseFloat(get(IceyeConstants.AZIMUTH_SPACING)));
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.pulse_repetition_frequency,
                    Float.parseFloat(get(IceyeConstants.PULSE_REPETITION_FREQUENCY)));
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.radar_frequency,
                    Double.parseDouble(get(IceyeConstants.RADAR_FREQUENCY))
                            / Constants.oneMillion);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.line_time_interval,
                    Double.valueOf(get(IceyeConstants.LINE_TIME_INTERVAL)));
            final int rasterWidth = Integer
                    .parseInt(get(IceyeConstants.NUM_SAMPLES_PER_LINE));
            final int rasterHeight = Integer.parseInt(get(IceyeConstants.NUM_OUTPUT_LINES));
            double totalSize = (rasterHeight * rasterWidth * 2 * 2) / (1024.0f * 1024.0f);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.TOT_SIZE, totalSize);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.num_output_lines,
                    Integer.parseInt(get(IceyeConstants.NUM_OUTPUT_LINES)));

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.num_samples_per_line,
                    Integer.parseInt(get(IceyeConstants.NUM_SAMPLES_PER_LINE)));
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.subset_offset_x,
                    IceyeConstants.SUBSET_OFFSET_X_DEFAULT_VALUE);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.subset_offset_y,
                    IceyeConstants.SUBSET_OFFSET_Y_DEFAULT_VALUE);
            if (isComplex) {
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.srgr_flag, 0);
            } else {
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.srgr_flag, 1);
            }
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.avg_scene_height,
                    Double.valueOf(get(IceyeConstants.AVG_SCENE_HEIGHT)));

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.lat_pixel_res,
                    IceyeConstants.LAT_PIXEL_RES_DEFAULT_VALUE);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.lon_pixel_res,
                    IceyeConstants.LON_PIXEL_RES_DEFAULT_VALUE);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.slant_range_to_first_pixel,
                    Double.valueOf(get(IceyeConstants.SLANT_RANGE_TO_FIRST_PIXEL)));

            int antElevCorrFlag = IceyeConstants.ANT_ELEV_CORR_FLAG_DEFAULT_VALUE;
            if (get(IceyeConstants.ANT_ELEV_CORR_FLAG) != null) {
                antElevCorrFlag = Integer.parseInt(get(IceyeConstants.ANT_ELEV_CORR_FLAG));
            }
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ant_elev_corr_flag, antElevCorrFlag);

            int rangeSpreadCompFlag = IceyeConstants.RANGE_SPREAD_COMP_FLAG_DEFAULT_VALUE;
            if (get(IceyeConstants.RANGE_SPREAD_COMP_FLAG) != null) {
                rangeSpreadCompFlag = Integer
                        .parseInt(get(IceyeConstants.RANGE_SPREAD_COMP_FLAG));
            }
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_spread_comp_flag, rangeSpreadCompFlag);

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.replica_power_corr_flag,
                    IceyeConstants.REPLICA_POWER_CORR_FLAG_DEFAULT_VALUE);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.abs_calibration_flag,
                    IceyeConstants.ABS_CALIBRATION_FLAG_DEFAULT_VALUE);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.calibration_factor,
                    Double.valueOf(get(IceyeConstants.CALIBRATION_FACTOR)));
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.inc_angle_comp_flag,
                    IceyeConstants.INC_ANGLE_COMP_FLAG_DEFAULT_VALUE);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ref_inc_angle,
                    IceyeConstants.REF_INC_ANGLE_DEFAULT_VALUE);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ref_slant_range,
                    IceyeConstants.REF_SLANT_RANGE_DEFAULT_VALUE);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ref_slant_range_exp,
                    IceyeConstants.REF_SLANT_RANGE_EXP_DEFAULT_VALUE);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.rescaling_factor,
                    IceyeConstants.RESCALING_FACTOR_DEFAULT_VALUE);

            String rangeSamplingRate = get(IceyeConstants.RANGE_SAMPLING_RATE);
            if(rangeSamplingRate != null) {
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_sampling_rate,
                        Double.parseDouble(rangeSamplingRate) / 1e6);
            }

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_bandwidth,
                    Double.parseDouble(get(IceyeConstants.RANGE_BANDWIDTH)) / 1e6);

            String azimuthBandwidth = get(IceyeConstants.AZIMUTH_BANDWIDTH);
            if(azimuthBandwidth != null) {
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_bandwidth,
                        Double.parseDouble(azimuthBandwidth) / 1e6);
            }
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.multilook_flag,
                    IceyeConstants.MULTI_LOOK_FLAG_DEFAULT_VALUE);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.coregistered_stack,
                    IceyeConstants.COREGISTERED_STACK_DEFAULT_VALUE);

            addOrbitStateVectors(absRoot);
            addSRGRCoefficients(absRoot);

        } catch (Exception e) {
            SystemUtils.LOG.severe(e.getMessage());
        }
    }

    private void addSRGRCoefficients(final MetadataElement absRoot) throws Exception {

        String zeroDopplerTime = get(IceyeConstants.GRSR_ZERO_DOPPLER_TIME);
        String groundRangeOrigin = get(IceyeConstants.GRSR_GROUND_RANGE_ORIGIN);
        if(zeroDopplerTime != null && groundRangeOrigin != null) {
            final MetadataElement srgrCoefficientsElem = absRoot.getElement(AbstractMetadata.srgr_coefficients);
            final MetadataElement srgrListElem = new MetadataElement(AbstractMetadata.srgr_coef_list + ".1");
            srgrCoefficientsElem.addElement(srgrListElem);

            final ProductData.UTC utcTime = ProductData.UTC.parse(zeroDopplerTime, standardDateFormat);
            srgrListElem.setAttributeUTC(AbstractMetadata.srgr_coef_time, utcTime);

            AbstractMetadata.addAbstractedAttribute(srgrListElem, AbstractMetadata.ground_range_origin,
                    ProductData.TYPE_FLOAT64, "m", "Ground Range Origin");
            AbstractMetadata.setAttribute(srgrListElem, AbstractMetadata.ground_range_origin,
                    Double.valueOf(groundRangeOrigin));

            final String[] coeffStrArray = convertDateStringToStringArrayBySpace(get(IceyeConstants.GRSR_COEFFICIENTS));
            int cnt = 1;
            for (String coeffStr : coeffStrArray) {
                final MetadataElement coefElem = new MetadataElement(AbstractMetadata.coefficient + '.' + cnt);
                srgrListElem.addElement(coefElem);
                ++cnt;
                AbstractMetadata.addAbstractedAttribute(coefElem, AbstractMetadata.srgr_coef,
                        ProductData.TYPE_FLOAT64, "", "SRGR Coefficient");
                AbstractMetadata.setAttribute(coefElem, AbstractMetadata.srgr_coef, Double.parseDouble(coeffStr));
            }
        } else {
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.srgr_flag, 0);
        }
    }

    private void addOrbitStateVectors(final MetadataElement absRoot) {

        try {
            final MetadataElement orbitVectorListElem = absRoot.getElement(AbstractMetadata.orbit_state_vectors);

            String[] stateVectorTime = convertDateStringToStringArray(
                    get(IceyeConstants.STATE_VECTOR_TIME));
            final int numPoints = stateVectorTime.length;
            final double[] satellitePositionX = convertStringToDoubleArray(
                    get(IceyeConstants.ORBIT_VECTOR_N_X_POS));
            final double[] satellitePositionY = convertStringToDoubleArray(
                    get(IceyeConstants.ORBIT_VECTOR_N_Y_POS));
            final double[] satellitePositionZ = convertStringToDoubleArray(
                    get(IceyeConstants.ORBIT_VECTOR_N_Z_POS));
            final double[] satelliteVelocityX = convertStringToDoubleArray(
                    get(IceyeConstants.ORBIT_VECTOR_N_X_VEL));
            final double[] satelliteVelocityY = convertStringToDoubleArray(
                    get(IceyeConstants.ORBIT_VECTOR_N_Y_VEL));
            final double[] satelliteVelocityZ = convertStringToDoubleArray(
                    get(IceyeConstants.ORBIT_VECTOR_N_Z_VEL));
            ProductData.UTC stateVectorUTC = ProductData.UTC.parse(stateVectorTime[0], standardDateFormat);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.STATE_VECTOR_TIME, stateVectorUTC);
            for (int i = 0; i < numPoints; i++) {
                ProductData.UTC vectorUTC = ProductData.UTC.parse(stateVectorTime[i], standardDateFormat);

                final MetadataElement orbitVectorElem = new MetadataElement(AbstractMetadata.orbit_vector + (i + 1));
                orbitVectorElem.setAttributeUTC(AbstractMetadata.orbit_vector_time, vectorUTC);

                orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_x_pos, satellitePositionX[i]);
                orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_y_pos, satellitePositionY[i]);
                orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_z_pos, satellitePositionZ[i]);
                orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_x_vel, satelliteVelocityX[i]);
                orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_y_vel, satelliteVelocityY[i]);
                orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_z_vel, satelliteVelocityZ[i]);

                orbitVectorListElem.addElement(orbitVectorElem);
            }
        } catch (Exception e) {
            SystemUtils.LOG.severe(e.getMessage());
        }
    }

    private void addDopplerCentroidCoefficients() {

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);

        final MetadataElement dopplerCentroidCoefficientsElem = absRoot.getElement(AbstractMetadata.dop_coefficients);
        final MetadataElement dopplerListElem = new MetadataElement(AbstractMetadata.dop_coef_list + ".1");
        dopplerCentroidCoefficientsElem.addElement(dopplerListElem);

        final ProductData.UTC utcTime;
        try {
            utcTime = ProductData.UTC.parse(convertDateStringToStringArray(
                    get(IceyeConstants.DC_ESTIMATE_TIME_UTC))[0], standardDateFormat);
            dopplerListElem.setAttributeUTC(AbstractMetadata.dop_coef_time, utcTime);
        } catch (Exception e) {
            SystemUtils.LOG.severe(e.getMessage());
        }

        try {
            AbstractMetadata.addAbstractedAttribute(dopplerListElem, AbstractMetadata.slant_range_time,
                    ProductData.TYPE_FLOAT64, "ns", "Slant Range Time");
            AbstractMetadata.setAttribute(dopplerListElem, AbstractMetadata.slant_range_time,
                    Double.parseDouble(get(IceyeConstants.DC_REFERENCE_PIXEL_TIME)) * 1e9);

            int dimensionColumn = Integer.parseInt(get(IceyeConstants.DC_ESTIMATE_POLY_ORDER)) + 1;

            String[] coefValueS = convertDateStringToStringArrayBySpace(
                    get(IceyeConstants.DC_ESTIMATE_COEFFS));

            for (int i = 0; i < dimensionColumn; i++) {
                final double coefValue = Double.parseDouble(coefValueS[i]);
                final MetadataElement coefElem = new MetadataElement(AbstractMetadata.coefficient + '.' + (i + 1));
                dopplerListElem.addElement(coefElem);
                AbstractMetadata.addAbstractedAttribute(coefElem, AbstractMetadata.dop_coef,
                        ProductData.TYPE_FLOAT64, "", "Doppler Centroid Coefficient");
                AbstractMetadata.setAttribute(coefElem, AbstractMetadata.dop_coef, coefValue);
            }
        } catch (Exception e) {
            SystemUtils.LOG.severe(e.getMessage());
        }
    }

    private String getSampleType() {
        if (IceyeConstants.SLC.equalsIgnoreCase(get(IceyeConstants.SPH_DESCRIPTOR))) {
            isComplex = true;
            return IceyeConstants.COMPLEX;
        }
        isComplex = false;
        return IceyeConstants.DETECTED;
    }

    private void addBandsToProduct() {
        try {
            final File inputFile = getPathFromInput(getInput()).toFile();
            String imgPath = inputFile.getPath();
            final String name = imgPath.substring(imgPath.lastIndexOf('/') + 1).toLowerCase();
            final int rasterWidth = Integer.parseInt(get(IceyeConstants.NUM_SAMPLES_PER_LINE));
            final int rasterHeight = Integer.parseInt(get(IceyeConstants.NUM_OUTPUT_LINES));
            final Dimension bandDimensions = new Dimension(rasterWidth, rasterHeight);

            final InputStream inStream = new BufferedInputStream(ProductUtils.getProductInputStream(inputFile));
            if(inStream.available() > 0) {
                final ImageInputStream imgStream = ImageIOFile.createImageInputStream(inStream, bandDimensions);

                ProductReader reader = geoTiffPlugIn.createReaderInstance();
                bandProduct = reader.readProductNodes(inputFile, null);

                final ImageIOFile img = new ImageIOFile(name, imgStream, GeoTiffUtils.getTiffIIOReader(imgStream), inputFile);
                bandImageFileMap.put(img.getName(), img);

                int cnt = 1;
                boolean multiband = bandProduct.getNumBands() > 1;
                for(Band tifBand : bandProduct.getBands()) {
                    String polarization = get(IceyeConstants.MDS1_TX_RX_POLAR);
                    String suffix = '_' + polarization;
                    if(multiband) {
                        suffix += cnt;
                    }
                    String trgBandName = "Amplitude" + suffix;

                    Band trgBand = ProductUtils.copyBand(tifBand.getName(), bandProduct, trgBandName, product, true);
                    trgBand.setUnit(Unit.AMPLITUDE);
                    trgBand.setNoDataValue(0);
                    trgBand.setNoDataValueUsed(true);

                    SARReader.createVirtualIntensityBand(product, trgBand, suffix);
                    ++cnt;
                }
            }

        } catch (IOException e) {
            SystemUtils.LOG.severe(e.getMessage());
        }
    }

    private void addTiePointGridsToProduct() {

        try {
            final int sourceImageWidth = product.getSceneRasterWidth();
            final int sourceImageHeight = product.getSceneRasterHeight();
            final int gridWidth = 11;
            final int gridHeight = 11;
            final int subSamplingX = (int) ((float) sourceImageWidth / (float) (gridWidth - 1));
            final int subSamplingY = (int) ((float) sourceImageHeight / (float) (gridHeight - 1));

            double a = Constants.semiMajorAxis; // WGS 84: equatorial Earth radius in m
            double b = Constants.semiMinorAxis; // WGS 84: polar Earth radius in m

            // get slant range to first pixel and pixel spacing
            final double slantRangeToFirstPixel = Double.parseDouble(get(IceyeConstants.SLANT_RANGE_TO_FIRST_PIXEL)); // in m
            final double rangeSpacing = Double.parseDouble(get(IceyeConstants.RANGE_SPACING)); // in m

            // get scene center latitude
            String coordCenter = get(IceyeConstants.COORD_CENTER);
            double[] centerCoords = convertStringToDoubleArray(coordCenter);
            double sceneCenterLatitude = centerCoords[2]; // in deg
            final double nearRangeIncidenceAngle = Double.parseDouble(get(IceyeConstants.INCIDENCE_NEAR));

            final double alpha1 = nearRangeIncidenceAngle * Constants.DTOR;
            final double lambda = sceneCenterLatitude * Constants.DTOR;
            final double cos2 = FastMath.cos(lambda) * FastMath.cos(lambda);
            final double sin2 = FastMath.sin(lambda) * FastMath.sin(lambda);
            final double e2 = (b * b) / (a * a);
            final double rt = a * Math.sqrt((cos2 + e2 * e2 * sin2) / (cos2 + e2 * sin2));
            final double rt2 = rt * rt;

            double groundRangeSpacing;
            if (!isComplex) { // detected
                groundRangeSpacing = rangeSpacing;
            } else {
                groundRangeSpacing = rangeSpacing / FastMath.sin(alpha1);
            }

            double deltaPsi = groundRangeSpacing / rt; // in radian
            final double r1 = slantRangeToFirstPixel;
            final double rtPlusH = Math.sqrt(rt2 + r1 * r1 + 2.0 * rt * r1 * FastMath.cos(alpha1));
            final double rtPlusH2 = rtPlusH * rtPlusH;
            final double theta1 = FastMath.acos((r1 + rt * FastMath.cos(alpha1)) / rtPlusH);
            final double psi1 = alpha1 - theta1;
            double psi = psi1;
            float[] incidenceAngles = new float[gridWidth];
            final int n = gridWidth * subSamplingX;
            int k = 0;
            for (int i = 0; i < n; i++) {
                final double ri = Math.sqrt(rt2 + rtPlusH2 - 2.0 * rt * rtPlusH * FastMath.cos(psi));
                final double alpha = FastMath.acos((rtPlusH2 - ri * ri - rt2) / (2.0 * ri * rt));
                if (i % subSamplingX == 0) {
                    int index = k++;
                    incidenceAngles[index] = (float) (alpha * Constants.RTOD);
                }

                if (isComplex) { // complex
                    groundRangeSpacing = rangeSpacing / FastMath.sin(alpha);
                    deltaPsi = groundRangeSpacing / rt;
                }
                psi = psi + deltaPsi;
            }

            float[] incidenceAngleList = new float[gridWidth * gridHeight];
            for (int j = 0; j < gridHeight; j++) {
                System.arraycopy(incidenceAngles, 0, incidenceAngleList, j * gridWidth, gridWidth);
            }

            final TiePointGrid incidentAngleGrid = new TiePointGrid(
                    OperatorUtils.TPG_INCIDENT_ANGLE, gridWidth, gridHeight, 0, 0,
                    subSamplingX, subSamplingY, incidenceAngleList);

            incidentAngleGrid.setUnit(Unit.DEGREES);

            product.addTiePointGrid(incidentAngleGrid);

            addSlantRangeTime(product);
        } catch (Exception e) {
            SystemUtils.LOG.severe(e.getMessage());
        }
    }

    private void addSlantRangeTime(final Product product) {

        final List<CoefList> segmentsArray = new ArrayList<>();

        String grsr = get(IceyeConstants.GRSR_COEFFICIENTS);
        if(grsr == null) {
            return;
        }
        String[] coeffArray = convertDateStringToStringArrayBySpace(grsr);
        final CoefList coefList = new CoefList();

        try {
            coefList.utcSeconds = ProductData.UTC
                    .parse(get(IceyeConstants.GRSR_ZERO_DOPPLER_TIME),
                            standardDateFormat)
                    .getMJD() * 24 * 3600;

            coefList.grOrigin = Double.parseDouble(get(IceyeConstants.GRSR_GROUND_RANGE_ORIGIN));
            segmentsArray.add(coefList);
            for (String coefString : coeffArray) {
                coefList.coefficients.add(Double.parseDouble(coefString));
            }

            final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);

            final int gridWidth = 11;
            final int gridHeight = 11;
            final int sceneWidth = product.getSceneRasterWidth();
            final int sceneHeight = product.getSceneRasterHeight();
            final int subSamplingX = sceneWidth / (gridWidth - 1);
            final int subSamplingY = sceneHeight / (gridHeight - 1);
            final float[] rangeDist = new float[gridWidth * gridHeight];
            final float[] rangeTime = new float[gridWidth * gridHeight];

            setRangeDist(absRoot, segmentsArray, gridWidth, gridHeight, subSamplingX, rangeDist);
            // get slant range time in nanoseconds from range distance in meters
            setRangeTime(rangeDist, rangeTime);

            final TiePointGrid slantRangeGrid = new TiePointGrid(OperatorUtils.TPG_SLANT_RANGE_TIME,
                    gridWidth, gridHeight, 0, 0, subSamplingX, subSamplingY, rangeTime);

            product.addTiePointGrid(slantRangeGrid);
            slantRangeGrid.setUnit(Unit.NANOSECONDS);
        } catch (ParseException e) {
            SystemUtils.LOG.severe(e.getMessage());
        }
    }

    private void setRangeTime(float[] rangeDist, float[] rangeTime) {
        for (int i = 0; i < rangeDist.length; i++) {
            rangeTime[i] = (float) (rangeDist[i] / Constants.halfLightSpeed * Constants.oneBillion); // in ns
        }
    }

    private void setRangeDist(MetadataElement absRoot, List<CoefList> segmentsArray, int gridWidth, int gridHeight,
            int subSamplingX, float[] rangeDist) throws ParseException {
        final double lineTimeInterval = Double.parseDouble(get(IceyeConstants.LINE_TIME_INTERVAL));
        final double startSeconds = ProductData.UTC.parse(get(IceyeConstants.FIRST_LINE_TIME), standardDateFormat).getMJD()
                * 24 * 3600;
        final double pixelSpacing = absRoot.getAttributeDouble(AbstractMetadata.range_spacing, 0);

        final CoefList[] segments = segmentsArray.toArray(new CoefList[0]);

        int k = 0;
        int c = 0;
        for (int j = 0; j < gridHeight; j++) {
            final double time = startSeconds + (j * lineTimeInterval);
            while (c < segments.length && segments[c].utcSeconds < time)
                ++c;
            if (c >= segments.length)
                c = segments.length - 1;

            final CoefList coef = segments[c];
            final double GR0 = coef.grOrigin;
            final double s0 = coef.coefficients.get(0);
            final double s1 = coef.coefficients.get(1);
            final double s2 = coef.coefficients.get(2);
            final double s3 = coef.coefficients.get(3);
            final double s4 = coef.coefficients.get(4);

            for (int i = 0; i < gridWidth; i++) {
                int x = i * subSamplingX;
                final double GR = x * pixelSpacing;
                final double g = GR - GR0;
                final double g2 = g * g;

                // SlantRange = s0 + s1(GR - GR0) + s2(GR-GR0)^2 + s3(GRGR0)^3 + s4(GR-GR0)^4;
                rangeDist[k++] = (float) (s0 + s1 * g + s2 * g2 + s3 * g2 * g + s4 * g2 * g2);
            }
        }
    }

    private void addGeoCodingToProduct() {

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        double[] firstNear = convertStringToDoubleArray(get(IceyeConstants.FIRST_NEAR));
        double[] firstFar = convertStringToDoubleArray(get(IceyeConstants.FIRST_FAR));
        double[] lastNear = convertStringToDoubleArray(get(IceyeConstants.LAST_NEAR));
        double[] lastFar = convertStringToDoubleArray(get(IceyeConstants.LAST_FAR));
        final double latUL = firstNear[2];
        final double lonUL = firstNear[3];
        final double latUR = firstFar[2];
        final double lonUR = firstFar[3];
        final double latLL = lastNear[2];
        final double lonLL = lastNear[3];
        final double latLR = lastFar[2];
        final double lonLR = lastFar[3];

        absRoot.setAttributeDouble(AbstractMetadata.first_near_lat, latUL);
        absRoot.setAttributeDouble(AbstractMetadata.first_near_long, lonUL);
        absRoot.setAttributeDouble(AbstractMetadata.first_far_lat, latUR);
        absRoot.setAttributeDouble(AbstractMetadata.first_far_long, lonUR);
        absRoot.setAttributeDouble(AbstractMetadata.last_near_lat, latLL);
        absRoot.setAttributeDouble(AbstractMetadata.last_near_long, lonLL);
        absRoot.setAttributeDouble(AbstractMetadata.last_far_lat, latLR);
        absRoot.setAttributeDouble(AbstractMetadata.last_far_long, lonLR);

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_spacing,
                Double.valueOf(get(IceyeConstants.RANGE_SPACING)));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_spacing,
                Double.valueOf(get(IceyeConstants.AZIMUTH_SPACING)));

        final double[] latCorners = new double[] { latUL, latUR, latLL, latLR };
        final double[] lonCorners = new double[] { lonUL, lonUR, lonLL, lonLR };

        ReaderUtils.addGeoCoding(product, latCorners, lonCorners);
    }

    void callReadBandRasterData(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight,
            int sourceStepX, int sourceStepY, Band destBand, int destOffsetX,
            int destOffsetY, int destWidth, int destHeight, ProductData destBuffer,
            ProgressMonitor pm) throws IOException {
        readBandRasterDataImpl(sourceOffsetX, sourceOffsetY, sourceWidth, sourceHeight,
                sourceStepX, sourceStepY, destBand, destOffsetX, destOffsetY, destWidth, destHeight, destBuffer, pm);
    }

    @Override
    protected void readBandRasterDataImpl(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight,
            int sourceStepX, int sourceStepY, Band destBand, int destOffsetX,
            int destOffsetY, int destWidth, int destHeight, ProductData destBuffer,
            ProgressMonitor pm) throws IOException {
        final ImageIOFile.BandInfo bandInfo = bandMap.get(destBand);
        if (bandInfo != null && bandInfo.img != null) {
            if (get(IceyeConstants.PASS).equalsIgnoreCase(IceyeConstants.ASCENDING)) {
                readAscendingRasterBand(sourceOffsetX, sourceOffsetY, sourceStepX, sourceStepY,
                        destBuffer, destOffsetX, destOffsetY, destWidth, destHeight,
                        0, bandInfo.img, bandInfo.bandSampleOffset);
            } else {
                readDescendingRasterBand(sourceOffsetX, sourceOffsetY, sourceStepX, sourceStepY,
                        destBuffer, destOffsetX, destOffsetY, destWidth, destHeight,
                        0, bandInfo.img, bandInfo.bandSampleOffset);
            }
        }
    }

    private void readAscendingRasterBand(final int sourceOffsetX, final int sourceOffsetY,
            final int sourceStepX, final int sourceStepY,
            final ProductData destBuffer,
            final int destOffsetX, final int destOffsetY,
            final int destWidth, final int destHeight,
            final int imageID, final ImageIOFile img,
            final int bandSampleOffset) throws IOException {
        final Raster data;

        synchronized (tiffFields) {
            final ImageReader reader = img.getReader();
            final ImageReadParam param = reader.getDefaultReadParam();
            param.setSourceSubsampling(sourceStepX, sourceStepY,
                    sourceOffsetX % sourceStepX,
                    sourceOffsetY % sourceStepY);
            final RenderedImage image = reader.readAsRenderedImage(0, param);
            data = image.getData(new Rectangle(destOffsetX, destOffsetY, destWidth, destHeight));
        }

        final int w = data.getWidth();
        final int h = data.getHeight();
        final DataBuffer dataBuffer = data.getDataBuffer();
        final SampleModel sampleModel = data.getSampleModel();
        final int sampleOffset = imageID + bandSampleOffset;
        sampleModel.getSamples(0, 0, w, h, sampleOffset, (int[]) destBuffer.getElems(), dataBuffer);
    }

    private void readDescendingRasterBand(final int sourceOffsetX, final int sourceOffsetY,
            final int sourceStepX, final int sourceStepY,
            final ProductData destBuffer,
            final int destOffsetX, final int destOffsetY,
            final int destWidth, final int destHeight,
            final int imageID, final ImageIOFile img,
            final int bandSampleOffset) throws IOException {

        final Raster data;
        synchronized (tiffFields) {
            final ImageReader reader = img.getReader();
            final ImageReadParam param = reader.getDefaultReadParam();
            param.setSourceSubsampling(sourceStepX, sourceStepY,
                    sourceOffsetX % sourceStepX,
                    sourceOffsetY % sourceStepY);

            final RenderedImage image = reader.readAsRenderedImage(0, param);
            data = image.getData(new Rectangle(destOffsetX, destOffsetY, destWidth, destHeight));
        }

        final int w = data.getWidth();
        final int h = data.getHeight();
        final DataBuffer dataBuffer = data.getDataBuffer();
        final SampleModel sampleModel = data.getSampleModel();
        final int sampleOffset = imageID + bandSampleOffset;

        if (destBuffer.getType() == ProductData.TYPE_FLOAT32) {
            sampleModel.getSamples(0, 0, w, h, sampleOffset, (float[]) destBuffer.getElems(), dataBuffer);
        } else {
            sampleModel.getSamples(0, 0, w, h, sampleOffset, (int[]) destBuffer.getElems(), dataBuffer);
        }
    }

    static class CoefList {
        final List<Double> coefficients = new ArrayList<>();
        double utcSeconds = 0.0;
        double grOrigin = 0.0;
    }

}
