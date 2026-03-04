package eu.esa.sar.io.iceye;

import com.bc.ceres.core.ProgressMonitor;

import eu.esa.sar.commons.io.JSONProductDirectory;
import eu.esa.sar.commons.io.SARReader;
import eu.esa.sar.commons.product.Missions;
import eu.esa.sar.io.geotiffxml.GeoTiffUtils;
import eu.esa.sar.io.iceye.util.IceyeConstants;
import it.geosolutions.imageioimpl.plugins.tiff.TIFFIFD;
import it.geosolutions.imageioimpl.plugins.tiff.TIFFImageMetadata;
import it.geosolutions.imageioimpl.plugins.tiff.TIFFImageReader;

import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata.DopplerCentroidCoefficientList;
import org.esa.snap.engine_utilities.datamodel.metadata.AbstractMetadataIO;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.core.dataio.IllegalFileFormatException;
import org.esa.snap.core.dataio.ProductReaderPlugIn;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.ProductData.UTC;
import org.esa.snap.core.datamodel.TiePointGrid;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.esa.snap.core.datamodel.Product;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.stream.ImageInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.awt.Rectangle;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;



public abstract class IceyeAMLCPXProductReader extends SARReader {

    HashMap<Band, Integer> bandMap = new HashMap<>(4);
    int imageWidth, imageHeight;

    private boolean lookLeft;
    private ImageInputStream inputStream;
    private TIFFImageReader imageReader = null;
    private JSONObject metadataJSON = null;

    public IceyeAMLCPXProductReader(ProductReaderPlugIn readerPlugIn) {
        super(readerPlugIn);
    }

    @Override
    public synchronized Product readProductNodesImpl() {
        try {
            final Path inputPath = ReaderUtils.getPathFromInput(getInput());
            if (inputPath == null) {
                close();
                throw new IllegalFileFormatException("File could not be interpreted by the reader.");
            }

            final File inputFile = inputPath.toFile();

            inputStream = ImageIO.createImageInputStream(inputFile);
            imageReader = (TIFFImageReader) GeoTiffUtils.getTiffIIOReader(inputStream);
            imageReader.setInput(inputStream, false);

            TIFFImageMetadata tiffMetadata = (TIFFImageMetadata) imageReader.getImageMetadata(0);

            if (tiffMetadata == null) {
                close();
                throw new IllegalFileFormatException("File metadata could not be interpreted by the reader.");
            }

            TIFFIFD rootIFD = tiffMetadata.getRootIFD();

            String xmlString = rootIFD.getTIFFField(IceyeConstants.TIFFTagGDAL_METADATA).getAsString(0);
            Document xmlRoot = IceyeGRDProductReader.convertStringToXMLDocument(xmlString);

            if (xmlRoot == null || xmlRoot.getFirstChild() == null
                    || xmlRoot.getFirstChild().getChildNodes() == null) {
                close();
                throw new IllegalFileFormatException("No ICEYE metadata variables found.");
            }

            Node metadataXML = xmlRoot.getFirstChild();
            for (Node child = metadataXML.getFirstChild(); child != null; child = child.getNextSibling()) {
                NamedNodeMap attributes = child.getAttributes();
                if (attributes != null && attributes.item(0).getNodeValue().equals(IceyeConstants.METADATA_JSON)) {
                    metadataJSON = parseMetadataJSON(child.getTextContent());
                    break;
                }
            }

            if (metadataJSON == null) {
                close();
                throw new IllegalFileFormatException("Could not parse METADATA_JSON.");
            }

            // NOTE: ICEYE images are stored in shadows-down orientation
            imageHeight = rootIFD.getTIFFField(IceyeConstants.TIFFTagImageWidth).getAsInt(0);
            imageWidth = rootIFD.getTIFFField(IceyeConstants.TIFFTagImageLength).getAsInt(0);
            String productType = (String) getFromJSON(IceyeConstants.product_type);

            Product product = new Product(inputFile.getName(), productType, imageWidth, imageHeight, this);
            product.setFileLocation(inputFile);

            addMetadataToProduct(product);
            addBandsToProduct(product);
            addGeoCodingToProduct(product, tiffMetadata);
            addTiePointGridsToProduct(product);

            addCommonSARMetadata(product);
            addDopplerCentroidCoefficients(product);

            product.getGcpGroup(); // why is this called??
            product.setModified(false);
            File qlFile = getQuicklookFile(inputFile);
            if (qlFile != null)
                addQuicklook(product, qlFile.getName().endsWith(IceyeConstants.qlk_png) ? IceyeConstants.Quicklook
                        : IceyeConstants.Thumbnail, qlFile);

            return product;

        } catch (Exception e) {
            SystemUtils.LOG.severe("Error reading product nodes: " + e.getMessage());
        }

        return null;
    }

    private File getQuicklookFile(File inputFile) {
        File dir = inputFile.getParentFile();
        File[] files = dir.listFiles();
        for (String suffix : new String[] { IceyeConstants.qlk_png, IceyeConstants.thm_png }) {
            for (File f : files) {
                if (f.getName().toLowerCase().endsWith(suffix))
                    return f;
            }
        }
        return null;
    }

    private JSONObject parseMetadataJSON(String jsonString) throws Exception {
        jsonString = jsonString.replaceAll("&quot;", "\"");
        JSONParser parser = new JSONParser();
        try {
            return (JSONObject) parser.parse(jsonString);
        } catch (Exception e) {
            throw e;
        }
    }

    static UTC parseUTC(String input) {
        try {
            return UTC.parse(input.substring(0, input.length() - 1), IceyeConstants.standardDateFormat);
        } catch (Exception e) {
            SystemUtils.LOG.severe("Unable to parse date: " + e.getMessage());
            return null;
        }
    }

    public synchronized void close() throws IOException {
        super.close();
        inputStream.close();
    }

    void addMetadataToProduct(Product product) {
        MetadataElement root = product.getMetadataRoot();

        try {
            MetadataElement origMeta = AbstractMetadata.addOriginalProductMetadata(root);
            AbstractMetadataIO.AddXMLMetadata(
                    JSONProductDirectory.jsonToXML(IceyeConstants.ProductMetadata, metadataJSON),
                    origMeta);
        } catch (Exception e) {
            SystemUtils.LOG.severe("Error reading original metadata: " + e.getMessage());
        }

        MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(root);

        String productName = addMetaString(absRoot, AbstractMetadata.PRODUCT, IceyeConstants.product_name);
        String productType = addMetaString(absRoot, AbstractMetadata.PRODUCT_TYPE, IceyeConstants.product_type);

        product.setDescription(productName + " - " + productType + " - " + getFromJSON(IceyeConstants.platform));
        product.setStartTime(parseUTC((String) getFromJSON(IceyeConstants.acquisition_start_utc)));
        product.setEndTime(parseUTC((String) getFromJSON(IceyeConstants.acquisition_end_utc)));

        try {
            String acquisitionMode = (String) getFromJSON(IceyeConstants.acquisition_mode);
            if (acquisitionMode.equalsIgnoreCase(IceyeConstants.spot))
                acquisitionMode = IceyeConstants.spotlight;
            else if (acquisitionMode.equalsIgnoreCase(IceyeConstants.strip))
                acquisitionMode = IceyeConstants.stripmap;
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ACQUISITION_MODE, acquisitionMode);
        } catch (Exception e) {
            SystemUtils.LOG.severe("Unable to set acquisition mode: " + e.getMessage());
        }

        addMetaString(absRoot, AbstractMetadata.SPH_DESCRIPTOR, IceyeConstants.product_type);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.MISSION, Missions.ICEYE);
        addMetaLong(absRoot, AbstractMetadata.data_take_id, IceyeConstants.data_take_id);

        addMetaString(absRoot, AbstractMetadata.antenna_pointing, IceyeConstants.antenna_pointing);
        lookLeft = absRoot.getAttributeString(AbstractMetadata.antenna_pointing).equals(IceyeConstants.left);

        addMetaString(absRoot, AbstractMetadata.PASS, IceyeConstants.PASS);
        addMetaUTC(absRoot, AbstractMetadata.PROC_TIME, IceyeConstants.PROC_TIME);
        addMetaString(absRoot, AbstractMetadata.ProcessingSystemIdentifier, IceyeConstants.ProcessingSystemIdentifier);
        addMetaDouble(absRoot, AbstractMetadata.incidence_near, IceyeConstants.incidence_near);
        addMetaDouble(absRoot, AbstractMetadata.incidence_far, IceyeConstants.incidence_far);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.geo_ref_system, IceyeConstants.geo_ref_system_default);
        addMetaUTC(absRoot, AbstractMetadata.first_line_time, IceyeConstants.first_line_time);
        addMetaUTC(absRoot, AbstractMetadata.last_line_time, IceyeConstants.last_line_time);

        String polarization = (String) ((JSONArray) getFromJSON(IceyeConstants.polarization)).get(0);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.mds1_tx_rx_polar, polarization);

        addMetaLong(absRoot, AbstractMetadata.azimuth_looks, IceyeConstants.azimuth_looks);
        addMetaLong(absRoot, AbstractMetadata.range_looks, IceyeConstants.range_looks);
        addMetaDouble(absRoot, AbstractMetadata.azimuth_spacing, IceyeConstants.azimuth_spacing);
        addMetaDouble(absRoot, AbstractMetadata.range_spacing, IceyeConstants.range_spacing);

        double prf = addMetaDouble(absRoot, AbstractMetadata.pulse_repetition_frequency, IceyeConstants.pulse_repetition_frequency);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.line_time_interval, 1 / prf);
        addMetaMHz(absRoot, AbstractMetadata.radar_frequency, IceyeConstants.radar_frequency);

        double totalSize = product.getFileLocation().length() / (1024.0f * 1024.0f);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.TOT_SIZE, totalSize);

        addMetaLong(absRoot, AbstractMetadata.num_samples_per_line, IceyeConstants.num_samples_per_line);
        addMetaLong(absRoot, AbstractMetadata.num_output_lines, IceyeConstants.num_output_lines);
        // subset_offset_x and subset_offset_y set to zero by default
        String projection = (String) getFromJSON(IceyeConstants.projection_plane);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.srgr_flag,
                projection.equals(IceyeConstants.ground) ? 1 : 0);

        addMetaDouble(absRoot, AbstractMetadata.avg_scene_height, IceyeConstants.avg_scene_height);
        // lat_pixel_res and lon_pixel_res set to 99999.0 by default
        addMetaDouble(absRoot, AbstractMetadata.slant_range_to_first_pixel, IceyeConstants.slant_range_to_first_pixel);

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ant_elev_corr_flag,
                IceyeConstants.ANT_ELEV_CORR_FLAG_DEFAULT_VALUE);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_spread_comp_flag,
                IceyeConstants.RANGE_SPREAD_COMP_FLAG_DEFAULT_VALUE);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.replica_power_corr_flag,
                IceyeConstants.REPLICA_POWER_CORR_FLAG_DEFAULT_VALUE);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.abs_calibration_flag,
                IceyeConstants.ABS_CALIBRATION_FLAG_DEFAULT_VALUE);

        Double calibration_factor = addMetaDouble(absRoot, AbstractMetadata.calibration_factor,
                IceyeConstants.calibration_factor); // for SNAP version
        AbstractMetadata.setAttribute(AbstractMetadata.getOriginalProductMetadata(product),
                AbstractMetadata.calibration_factor,
                calibration_factor); // for SNAP version <= 9.0.4

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.inc_angle_comp_flag,
                IceyeConstants.INC_ANGLE_COMP_FLAG_DEFAULT_VALUE);
        // ref_inc_angle set to 99999.0 by default
        // ref_slant_range set to 99999.0 by default
        // ref_slant_range_exp set to 99999.0 by default
        // rescaling_factor set to 99999.0 by default
        addMetaMHz(absRoot, AbstractMetadata.range_sampling_rate, IceyeConstants.range_sampling_rate);
        addMetaMHz(absRoot, AbstractMetadata.range_bandwidth, IceyeConstants.range_bandwidth);
        addMetaDouble(absRoot, AbstractMetadata.azimuth_bandwidth, IceyeConstants.azimuth_bandwidth);

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.coregistered_stack,
                IceyeConstants.COREGISTERED_STACK_DEFAULT_VALUE);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.bistatic_correction_applied,
                IceyeConstants.BISTATIC_CORRECTION_APPLIED_DEFAULT);

        addOrbitStateVectors(absRoot);
        addProductSpecificMetadata(absRoot);
    }

    abstract void addProductSpecificMetadata(MetadataElement absRoot);

    private void addOrbitStateVectors(MetadataElement meta) {
        try {
            MetadataElement list = meta.getElement(AbstractMetadata.orbit_state_vectors);

            JSONArray states = (JSONArray) getFromJSON(IceyeConstants.orbit_states);
            for (int i = 0; i < states.size(); i++) {
                MetadataElement vector = new MetadataElement(AbstractMetadata.orbit_vector + (i + 1));
                JSONObject state = (JSONObject) states.get(i);

                UTC time = parseUTC((String) state.get(IceyeConstants.time));
                vector.setAttributeUTC(AbstractMetadata.orbit_vector_time, time);
                if (i == 0)
                    meta.setAttributeUTC(AbstractMetadata.STATE_VECTOR_TIME, time);

                JSONArray pos = (JSONArray) getFromJSON(state, IceyeConstants.position);
                vector.setAttributeDouble(AbstractMetadata.orbit_vector_x_pos, (Double) pos.get(0));
                vector.setAttributeDouble(AbstractMetadata.orbit_vector_y_pos, (Double) pos.get(1));
                vector.setAttributeDouble(AbstractMetadata.orbit_vector_z_pos, (Double) pos.get(2));

                JSONArray vel = (JSONArray) getFromJSON(state, IceyeConstants.velocity);
                vector.setAttributeDouble(AbstractMetadata.orbit_vector_x_vel, (Double) vel.get(0));
                vector.setAttributeDouble(AbstractMetadata.orbit_vector_y_vel, (Double) vel.get(1));
                vector.setAttributeDouble(AbstractMetadata.orbit_vector_z_vel, (Double) vel.get(2));

                list.addElement(vector);
            }
        } catch (Exception e) {
            SystemUtils.LOG.severe("Error adding orbit state vectors: " + e.getMessage());
        }
    }

    private void addMetaUTC(MetadataElement meta, String tag, String keyString) {
        try {
            AbstractMetadata.setAttribute(meta, tag, parseUTC((String) getFromJSON(keyString)));
        } catch (Exception e) {
            SystemUtils.LOG.severe("Unable to parse UTC from metadata :: tag: " + tag);
        }
    }

    private String addMetaString(MetadataElement meta, String tag, String keyString) {
        try {
            String value = (String) getFromJSON(keyString);
            AbstractMetadata.setAttribute(meta, tag, value);
            return value;
        } catch (Exception e) {
            SystemUtils.LOG.severe("Unable to parse UTC from metadata :: tag: " + tag);
        }
        return null;
    }

    private void addMetaLong(MetadataElement meta, String tag, String keyString) {
        try {
            AbstractMetadata.setAttribute(meta, tag, (Long) getFromJSON(keyString));
        } catch (Exception e) {
            SystemUtils.LOG.severe("Unable to parse UTC from metadata :: tag: " + tag);
        }
    }

    private Double addMetaDouble(MetadataElement meta, String tag, String keyString) {
        try {
            Double value = (Double) getFromJSON(keyString);
            AbstractMetadata.setAttribute(meta, tag, value);
            return value;
        } catch (Exception e) {
            SystemUtils.LOG.severe("Unable to parse doube from metadata :: tag: " + tag);
        }
        return null;
    }

    private void addMetaMHz(MetadataElement meta, String tag, String keyString) {
        Object value = getFromJSON(keyString);
        if (value instanceof Long)
            AbstractMetadata.setAttribute(meta, tag, ((Long) value) / Constants.oneMillion);
        else if (value instanceof Double)
            AbstractMetadata.setAttribute(meta, tag, ((Double) value) / Constants.oneMillion);
    }

    Object getFromJSON(String keyString) {
        return getFromJSON(metadataJSON, keyString);
    }

    static Object getFromJSON(JSONObject obj, String keyString) {
        String[] keys = keyString.split(IceyeConstants.SEP);
        int i = 0;
        while (i < keys.length - 1) {
            if (obj == null) {
                SystemUtils.LOG.severe("JSON error --- Key not found in metadata: " + keys[i]);
            } else
                obj = (JSONObject) obj.get(keys[i++]);
        }
        if (obj == null) {
            SystemUtils.LOG.severe("JSON error --- Key not found in metadata: " + keys[i]);
            return null;
        }
        return obj.get(keys[i]);
    }

    private void addGeoCodingToProduct(Product product, TIFFImageMetadata tiffMetadata) {
        double[] tr = tiffMetadata.getRootIFD().getTIFFField(IceyeConstants.TIFFTagModelTransformation).getAsDoubles();
        double lonUL, latUL, lonUR, latUR, lonLL, latLL, lonLR, latLR;

        // compute corner coordinates using affine transformation from geotiff
        if (lookLeft) {
            lonUL = tr[0] * imageHeight + tr[3];
            latUL = tr[4] * imageHeight + tr[7];
            lonUR = tr[0] * imageHeight + tr[1] * imageWidth + tr[3];
            latUR = tr[4] * imageHeight + tr[5] * imageWidth + tr[7];
            lonLL = tr[3];
            latLL = tr[7];
            lonLR = tr[1] * imageWidth + tr[3];
            latLR = tr[5] * imageWidth + tr[7];
        } else {
            lonUL = tr[3];
            latUL = tr[7];
            lonUR = tr[1] * imageWidth + tr[3];
            latUR = tr[5] * imageWidth + tr[7];
            lonLL = tr[0] * imageHeight + tr[3];
            latLL = tr[4] * imageHeight + tr[7];
            lonLR = tr[0] * imageHeight + tr[1] * imageWidth + tr[3];
            latLR = tr[4] * imageHeight + tr[5] * imageWidth + tr[7];
        }

        double[] latCorners = new double[] { latUL, latUR, latLL, latLR };
        double[] lonCorners = new double[] { lonUL, lonUR, lonLL, lonLR };

        ReaderUtils.addGeoCoding(product, latCorners, lonCorners);

        MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        absRoot.setAttributeDouble(AbstractMetadata.first_near_lat, latUL);
        absRoot.setAttributeDouble(AbstractMetadata.first_near_long, lonUL);
        absRoot.setAttributeDouble(AbstractMetadata.first_far_lat, latUR);
        absRoot.setAttributeDouble(AbstractMetadata.first_far_long, lonUR);
        absRoot.setAttributeDouble(AbstractMetadata.last_near_lat, latLL);
        absRoot.setAttributeDouble(AbstractMetadata.last_near_long, lonLL);
        absRoot.setAttributeDouble(AbstractMetadata.last_far_lat, latLR);
        absRoot.setAttributeDouble(AbstractMetadata.last_far_long, lonLR);
    }

    void addBandsToProduct(Product product) {
        String polarization = (String) ((JSONArray) getFromJSON(IceyeConstants.polarization)).get(0);
        String bandName = IceyeConstants.amplitude_band_prefix + polarization;

        final Band ampBand = new Band(bandName, ProductData.TYPE_FLOAT32, imageWidth, imageHeight);
        ampBand.setUnit(Unit.AMPLITUDE);
        ampBand.setNoDataValue(0);
        ampBand.setNoDataValueUsed(true);
        product.addBand(ampBand);
        bandMap.put(ampBand, IceyeConstants.AMPLITUDE_BAND_INDEX);

        addProductSpecificBands(product, polarization);
    }

    abstract void addProductSpecificBands(Product product, String polarization);

    void addTiePointGridsToProduct(Product product) {

        final int gridWidth = 11;
        final int gridHeight = 11;
        final int subSamplingX = (int) ((float) imageWidth / (float) (gridWidth - 1));
        final int subSamplingY = (int) ((float) imageHeight / (float) (gridHeight - 1));
        float[] incidenceAngleList = new float[gridWidth * gridHeight];

        try {
            double[] coeffs = getDoublesFromJSON(IceyeConstants.inc_angle_coeffs);
            for (int i = 0; i < gridHeight; i++) {
                for (int j = 0; j < gridWidth; j++) {
                    incidenceAngleList[i * gridWidth + j] = (float) applyPolynomial(coeffs, j * subSamplingX);
                }
            }

            final TiePointGrid incidentAngleGrid = new TiePointGrid(
                    OperatorUtils.TPG_INCIDENT_ANGLE, gridWidth, gridHeight, 0, 0,
                    subSamplingX, subSamplingY, incidenceAngleList);
            incidentAngleGrid.setUnit(Unit.DEGREES);
            product.addTiePointGrid(incidentAngleGrid);
        } catch (Exception e) {
            SystemUtils.LOG.severe("Error adding incidence angle TPG: " + e.getMessage());
        }

        try {
            float[] slantRangeTimeList = getSlantRangeTimeList(gridWidth, gridHeight, subSamplingX);
            final TiePointGrid slantRangeTimeGrid = new TiePointGrid(
                    OperatorUtils.TPG_SLANT_RANGE_TIME, gridWidth, gridHeight, 0, 0,
                    subSamplingX, subSamplingY, slantRangeTimeList);
            slantRangeTimeGrid.setUnit(Unit.NANOSECONDS);
            product.addTiePointGrid(slantRangeTimeGrid);
        } catch (Exception e) {
            SystemUtils.LOG.severe("Error adding slant range time TPG: " + e.getMessage());
        }
    }

    abstract float[] getSlantRangeTimeList(int gridWidth, int gridHeight, int subSamplingX);

    double[] getDoublesFromJSON(String keyString) {
        return getDoublesFromJSON(metadataJSON, keyString);
    }

    private static double[] getDoublesFromJSON(JSONObject json, String keyString) {
        JSONArray jsonarray = (JSONArray) getFromJSON(json, keyString);
        if (jsonarray == null) {
            SystemUtils.LOG.severe("Unable to parse double array :: keystring: " + keyString);
            return new double[0];
        }
        double[] coeffs = new double[jsonarray.size()];
        for (int i = 0; i < jsonarray.size(); i++)
            if (jsonarray.get(i) instanceof Double)
                coeffs[i] = (double) jsonarray.get(i);
        return coeffs;
    }

    static double applyPolynomial(double[] coeffs, double t) {
        double sum = coeffs[coeffs.length - 1];
        for (int i = coeffs.length-1; i > 0 ; i--) {
            sum = sum * t + coeffs[i-1];
        }
        return sum;
    }

    private void addDopplerCentroidCoefficients(Product product) {
        try {
            JSONArray centroid_estimates = (JSONArray) getFromJSON(IceyeConstants.centroid_estimates);
            int size = centroid_estimates.size();

            DopplerCentroidCoefficientList[] dopList = new DopplerCentroidCoefficientList[size];
            for (int i = 0; i < size; i++) {
                DopplerCentroidCoefficientList dop = new DopplerCentroidCoefficientList();
                dopList[i] = dop;
                JSONObject estimate = (JSONObject) centroid_estimates.get(i);
                dop.time = parseUTC((String) estimate.get(IceyeConstants.time));
                dop.coefficients = getDoublesFromJSON(estimate, IceyeConstants.coeffs);
            }

            MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);

            AbstractMetadata.setDopplerCentroidCoefficients(absRoot, dopList);

            if (absRoot.getAttributeString(AbstractMetadata.ACQUISITION_MODE)
                    .equalsIgnoreCase(IceyeConstants.spotlight)) {
                final MetadataElement dopplerSpotlightElem = new MetadataElement("dopplerSpotlight");
                absRoot.addElement(dopplerSpotlightElem);
                addDopplerRateAndCentroidSpotlight(dopplerSpotlightElem, dopList);
                addAzimuthTimeZpSpotlight(absRoot, dopplerSpotlightElem);
            }
        } catch (Exception e) {
            SystemUtils.LOG.severe("Error adding Doppler centroid coefficients: " + e.getMessage());
        }
    }

    private void addDopplerRateAndCentroidSpotlight(MetadataElement elem, DopplerCentroidCoefficientList[] dopList) {
        try {
            double[] dopplerRateCoeffs = getDoublesFromJSON(IceyeConstants.doppler_rate_coffs);
            String dopplerRateSpotlightStr = Arrays.toString(dopplerRateCoeffs).replace("]", "").replace("[", "");

            AbstractMetadata.addAbstractedAttribute(elem, "dopplerRateSpotlight",
                    ProductData.TYPE_ASCII, "", "Doppler Rate Spotlight");
            AbstractMetadata.setAttribute(elem, "dopplerRateSpotlight", dopplerRateSpotlightStr);
        } catch (Exception e) {
            SystemUtils.LOG.severe("Error adding Doppler rate coefficients: " + e.getMessage());
        }
        try {
            String dopStr = "";
            for (DopplerCentroidCoefficientList dop : dopList)
                dopStr += dop.coefficients[0] + ",";

            dopStr = dopStr.substring(0, dopStr.length() - 1);

            AbstractMetadata.addAbstractedAttribute(elem, "dopplerCentroidSpotlight",
                    ProductData.TYPE_ASCII, "", "Doppler Centroid Spotlight");
            AbstractMetadata.setAttribute(elem, "dopplerCentroidSpotlight", dopStr);
        } catch (Exception e) {
            SystemUtils.LOG.severe("Error adding Doppler centroid string: " + e.getMessage());
        }
    }

    private void addAzimuthTimeZpSpotlight(MetadataElement absRoot, MetadataElement elem) {
        try {
            // Compute azimuth time
            final double flt = absRoot.getAttributeUTC(AbstractMetadata.first_line_time).getMJD() * 24.0 * 3600.0;
            final double llt = absRoot.getAttributeUTC(AbstractMetadata.last_line_time).getMJD() * 24.0 * 3600.0;
            final double AzimuthTimeZdOffset = 0.5 * (llt - flt);

            // Save in metadata
            final MetadataElement azimuthTimeZd = new MetadataElement("azimuthTimeZdSpotlight");
            elem.addElement(azimuthTimeZd);
            AbstractMetadata.addAbstractedAttribute(azimuthTimeZd, "AzimuthTimeZdOffset",
                    ProductData.TYPE_FLOAT64, "s", "Azimuth Time Zero Doppler Offset");
            AbstractMetadata.setAttribute(azimuthTimeZd, "AzimuthTimeZdOffset", AzimuthTimeZdOffset);
        } catch (Exception e) {
            SystemUtils.LOG.severe("Error adding AzimuthTimeZdOffset: " + e.getMessage());
        }
    }

    public void readBandRasterDataImpl(int sourceOffsetX, int sourceOffsetY,
            int sourceWidth, int sourceHeight,
            int sourceStepX, int sourceStepY,
            Band destBand,
            int destOffsetX, int destOffsetY,
            int destWidth, int destHeight,
            ProductData destBuffer, ProgressMonitor pm) throws IOException {

        pm.beginTask("Reading util from band " + destBand.getName(), destHeight);

        final ImageReadParam param = imageReader.getDefaultReadParam();
        param.setSourceSubsampling(sourceStepY, sourceStepX, sourceOffsetY % sourceStepY, sourceOffsetX % sourceStepX);
        Rectangle rect = lookLeft
                ? new Rectangle(imageHeight - destHeight - destOffsetY, destOffsetX, destHeight, destWidth)
                : new Rectangle(destOffsetY, destOffsetX, destHeight, destWidth);
        Raster data = readRect(param, rect, 0);
        DataBuffer dataBuffer = data.getDataBuffer();

        final int bandIndex = bandMap.get(destBand);

        for (int i = 0; i < destHeight; i++) {
            for (int j = 0; j < destWidth; j++) {
                int srcIndex = lookLeft
                        ? destHeight * (j + 1) - 1 - i
                        : j * destHeight + i;
                destBuffer.setElemFloatAt(i * destWidth + j, getRasterValue(srcIndex, bandIndex, dataBuffer));
            }
            pm.worked(1);
        }
        pm.done();
    }

    private synchronized Raster readRect(ImageReadParam param, Rectangle rect, int id) throws IOException {
        RenderedImage im = imageReader.readAsRenderedImage(id, param);
        return im.getData(rect);
    }

    abstract float getRasterValue(int srcIndex, int bandIndex, DataBuffer dataBuffer);
}
