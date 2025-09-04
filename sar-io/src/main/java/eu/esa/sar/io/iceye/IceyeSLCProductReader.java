package eu.esa.sar.io.iceye;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.io.SARReader;
import eu.esa.sar.commons.product.Missions;
import eu.esa.sar.io.iceye.util.IceyeConstants;
import eu.esa.sar.io.netcdf.NetCDFReader;
import eu.esa.sar.io.netcdf.NetCDFUtils;
import eu.esa.sar.io.netcdf.NetcdfConstants;
import org.esa.snap.core.dataio.IllegalFileFormatException;
import org.esa.snap.core.dataio.ProductReaderPlugIn;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.TiePointGrid;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import ucar.ma2.Array;
import ucar.nc2.NetcdfFile;
import ucar.nc2.Variable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.ParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Ahmad Hamouda, Carlos Hernandez, Esteban Aguilera
 */
public class IceyeSLCProductReader extends SARReader {

    private final Map<Band, Variable> bandMap = new HashMap<>(10);
    private final DateFormat standardDateFormat = ProductData.UTC.createDateFormat("yyyy-MM-dd'T'HH:mm:ss");
    private NetcdfFile netcdfFile = null;
    private Product product = null;
    private boolean isComplex = false;

    /**
     * Constructs a new abstract product reader.
     *
     * @param readerPlugIn the reader plug-in which created this reader, can be <code>null</code> for internal reader
     *                     implementations
     */
    public IceyeSLCProductReader(final ProductReaderPlugIn readerPlugIn) {
        super(readerPlugIn);
    }

    private static String getPolarization(final Product product, NetcdfFile netcdfFile) {

        final MetadataElement globalElem = AbstractMetadata.getOriginalProductMetadata(product).getElement(NetcdfConstants.GLOBAL_ATTRIBUTES_NAME);
        try {
            if (globalElem != null) {
                final String polStr = netcdfFile.getRootGroup().findVariable(IceyeConstants.MDS1_TX_RX_POLAR).readScalarString();
                if (!polStr.isEmpty())
                    return polStr;
            }
        } catch (IOException e) {
            SystemUtils.LOG.severe(e.getMessage());

        }
        return null;
    }

    private static void createUniqueBandName(final Product product, final Band band, final String origName) {
        int cnt = 1;
        band.setName(origName);
        while (product.getBand(band.getName()) != null) {
            band.setName(origName + cnt);
            ++cnt;
        }
    }

    private static void addIncidenceAnglesSlantRangeTime(final Product product, final MetadataElement bandElem, NetcdfFile netcdfFile) {
        try {
            if (bandElem == null) return;

            final int gridWidth = 11;
            final int gridHeight = 11;
            final float subSamplingX = product.getSceneRasterWidth() / (float) (gridWidth - 1);
            final float subSamplingY = product.getSceneRasterHeight() / (float) (gridHeight - 1);

            final double[] incidenceAngles = (double[]) netcdfFile.getRootGroup().findVariable(IceyeConstants.INCIDENCE_ANGLES).read().getStorage();

            final double nearRangeAngle = incidenceAngles[0];
            final double farRangeAngle = incidenceAngles[incidenceAngles.length - 1];

            final double firstRangeTime = netcdfFile.getRootGroup().findVariable(IceyeConstants.FIRST_PIXEL_TIME).readScalarDouble() * Constants.sTOns;
            final double samplesPerLine = netcdfFile.getRootGroup().findVariable(IceyeConstants.NUM_SAMPLES_PER_LINE).readScalarDouble();
            final double rangeSamplingRate = netcdfFile.getRootGroup().findVariable(IceyeConstants.RANGE_SAMPLING_RATE).readScalarDouble();
            final double lastRangeTime = firstRangeTime + samplesPerLine / rangeSamplingRate * Constants.sTOns;

            final float[] incidenceCorners = new float[]{(float) nearRangeAngle, (float) farRangeAngle, (float) nearRangeAngle, (float) farRangeAngle};
            final float[] slantRange = new float[]{(float) firstRangeTime, (float) lastRangeTime, (float) firstRangeTime, (float) lastRangeTime};

            final float[] fineAngles = new float[gridWidth * gridHeight];
            final float[] fineTimes = new float[gridWidth * gridHeight];

            ReaderUtils.createFineTiePointGrid(2, 2, gridWidth, gridHeight, incidenceCorners, fineAngles);
            ReaderUtils.createFineTiePointGrid(2, 2, gridWidth, gridHeight, slantRange, fineTimes);

            final TiePointGrid incidentAngleGrid = new TiePointGrid(OperatorUtils.TPG_INCIDENT_ANGLE, gridWidth, gridHeight, 0, 0,
                    subSamplingX, subSamplingY, fineAngles);
            incidentAngleGrid.setUnit(Unit.DEGREES);
            product.addTiePointGrid(incidentAngleGrid);

            final TiePointGrid slantRangeGrid = new TiePointGrid(OperatorUtils.TPG_SLANT_RANGE_TIME, gridWidth, gridHeight, 0, 0,
                    subSamplingX, subSamplingY, fineTimes);
            slantRangeGrid.setUnit(Unit.NANOSECONDS);
            product.addTiePointGrid(slantRangeGrid);
        } catch (IOException e) {
            SystemUtils.LOG.severe(e.getMessage());

        }
    }

    private static void addGeocodingFromMetadata(final Product product, final MetadataElement bandElem, NetcdfFile netcdfFile) {
        if (bandElem == null) return;

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);

        try {
            double[] firstNear = (double[]) netcdfFile.getRootGroup().findVariable(IceyeConstants.FIRST_NEAR).read().getStorage();
            double[] firstFar = (double[]) netcdfFile.getRootGroup().findVariable(IceyeConstants.FIRST_FAR).read().getStorage();
            double[] lastNear = (double[]) netcdfFile.getRootGroup().findVariable(IceyeConstants.LAST_NEAR).read().getStorage();
            double[] lastFar = (double[]) netcdfFile.getRootGroup().findVariable(IceyeConstants.LAST_FAR).read().getStorage();


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
                    netcdfFile.getRootGroup().findVariable(IceyeConstants.SLANT_RANGE_SPACING).readScalarDouble());
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_spacing,
                    netcdfFile.getRootGroup().findVariable(IceyeConstants.AZIMUTH_GROUND_SPACING).readScalarDouble());

            final double[] latCorners = new double[]{latUL, latUR, latLL, latLR};
            final double[] lonCorners = new double[]{lonUL, lonUR, lonLL, lonLR};

            ReaderUtils.addGeoCoding(product, latCorners, lonCorners);
        } catch (Exception e) {
            SystemUtils.LOG.severe(e.getMessage());
        }
    }

    private void initReader() {
        product = null;
        netcdfFile = null;
    }

    /**
     * Provides an implementation of the <code>readProductNodes</code> interface method. Clients implementing this
     * method can be sure that the input object and eventually the subset information has already been set.
     * <p/>
     * <p>This method is called as a last step in the <code>readProductNodes(input, subsetInfo)</code> method.
     */
    @Override
    protected Product readProductNodesImpl() {
        try {

            final Path inputPath = ReaderUtils.getPathFromInput(getInput());
            final File inputFile = inputPath.toFile();
            initReader();

            final NetcdfFile tempNetcdfFile = NetcdfFile.open(inputFile.getPath());
            if (tempNetcdfFile == null) {
                close();
                throw new IllegalFileFormatException(inputFile.getName() +
                        " Could not be interpreted by the reader.");
            }
            if (tempNetcdfFile.getRootGroup().getVariables().isEmpty()) {
                close();
                throw new IllegalFileFormatException("No netCDF variables found which could\n" +
                        "be interpreted as remote sensing bands.");  /*I18N*/
            }
            this.netcdfFile = tempNetcdfFile;

            final String productType = this.netcdfFile.getRootGroup().findVariable(IceyeConstants.PRODUCT_TYPE).readScalarString();
            final int rasterWidth = this.netcdfFile.getRootGroup().findVariable(IceyeConstants.NUM_SAMPLES_PER_LINE).readScalarInt();
            final int rasterHeight = this.netcdfFile.getRootGroup().findVariable(IceyeConstants.NUM_OUTPUT_LINES).readScalarInt();

            product = new Product(inputFile.getName(),
                    productType,
                    rasterWidth, rasterHeight,
                    this);
            product.setFileLocation(inputFile);
            StringBuilder description = new StringBuilder();
            description.append(this.netcdfFile.getRootGroup().findVariable(IceyeConstants.PRODUCT).readScalarString()).append(" - ");
            description.append(this.netcdfFile.getRootGroup().findVariable(IceyeConstants.PRODUCT_TYPE).readScalarString()).append(" - ");
            description.append(this.netcdfFile.getRootGroup().findVariable(IceyeConstants.SPH_DESCRIPTOR).readScalarString()).append(" - ");
            description.append(this.netcdfFile.getRootGroup().findVariable(IceyeConstants.MISSION).readScalarString());
            product.setDescription(description.toString());
            product.setStartTime(ProductData.UTC.parse(this.netcdfFile.getRootGroup().findVariable(IceyeConstants.ACQUISITION_START_UTC).readScalarString(), standardDateFormat));
            product.setEndTime(ProductData.UTC.parse(this.netcdfFile.getRootGroup().findVariable(IceyeConstants.ACQUISITION_END_UTC).readScalarString(), standardDateFormat));
            addMetadataToProduct();
            addBandsToProduct();
            addTiePointGridsToProduct();
            addGeoCodingToProduct();
            addCommonSARMetadata(product);
            addDopplerMetadata();
            setQuicklookBandName(product);

            product.getGcpGroup();
            product.setModified(false);

            return product;
        } catch (Exception e) {
            SystemUtils.LOG.severe(e.getMessage());
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        if (product != null) {
            product = null;
            netcdfFile.close();
            netcdfFile = null;
        }
        super.close();
    }

    private void addMetadataToProduct() {

        final MetadataElement origMetadataRoot = AbstractMetadata.addOriginalProductMetadata(product.getMetadataRoot());
        NetCDFUtils.addAttributes(origMetadataRoot, NetcdfConstants.GLOBAL_ATTRIBUTES_NAME,
                netcdfFile.getGlobalAttributes());

        for (Variable variable : netcdfFile.getVariables()) {
            NetCDFUtils.addVariableMetadata(origMetadataRoot, variable, 5000);
        }

        addAbstractedMetadataHeader(product.getMetadataRoot());
    }

    private void addAbstractedMetadataHeader(MetadataElement root) {

        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(root);

        try {
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PRODUCT, netcdfFile.getRootGroup().findVariable(IceyeConstants.PRODUCT).readScalarString());
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PRODUCT_TYPE, netcdfFile.getRootGroup().findVariable(IceyeConstants.PRODUCT_TYPE).readScalarString());
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SPH_DESCRIPTOR, netcdfFile.getRootGroup().findVariable(IceyeConstants.SPH_DESCRIPTOR).readScalarString());
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.MISSION, Missions.ICEYE);

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ACQUISITION_MODE, netcdfFile.getRootGroup().findVariable(IceyeConstants.ACQUISITION_MODE).readScalarString());
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.antenna_pointing, netcdfFile.getRootGroup().findVariable(IceyeConstants.ANTENNA_POINTING).readScalarString());
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.BEAMS, IceyeConstants.BEAMS_DEFAULT_VALUE);

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PROC_TIME, ProductData.UTC.parse(netcdfFile.getRootGroup().findVariable(IceyeConstants.PROC_TIME_UTC).readScalarString(), standardDateFormat));


            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ProcessingSystemIdentifier, IceyeConstants.ICEYE_PROCESSOR_NAME_PREFIX + netcdfFile.getRootGroup().findVariable(IceyeConstants.PROCESSING_SYSTEM_IDENTIFIER).readScalarFloat());
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.CYCLE, netcdfFile.getRootGroup().findVariable(IceyeConstants.CYCLE).readScalarInt());
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.REL_ORBIT, netcdfFile.getRootGroup().findVariable(IceyeConstants.REL_ORBIT).readScalarInt());
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ABS_ORBIT, netcdfFile.getRootGroup().findVariable(IceyeConstants.ABS_ORBIT).readScalarInt());

            double[] localIncidenceAngles = (double[]) netcdfFile.getRootGroup().findVariable(IceyeConstants.INCIDENCE_ANGLES).read().getStorage();
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.incidence_near, localIncidenceAngles[0]);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.incidence_far, localIncidenceAngles[localIncidenceAngles.length - 1]);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.slice_num, IceyeConstants.SLICE_NUM_DEFAULT_VALUE);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.data_take_id, IceyeConstants.DATA_TAKE_ID_DEFAULT_VALUE);
            String geoRefSystem = IceyeConstants.GEO_REFERENCE_SYSTEM_DEFAULT_VALUE;
            if (netcdfFile.getRootGroup().findVariable(IceyeConstants.GEO_REFERENCE_SYSTEM) != null) {
                geoRefSystem = netcdfFile.getRootGroup().findVariable(IceyeConstants.GEO_REFERENCE_SYSTEM).readScalarString();
            }
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.geo_ref_system, geoRefSystem);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_line_time, ProductData.UTC.parse(netcdfFile.getRootGroup().findVariable(IceyeConstants.FIRST_LINE_TIME).readScalarString(), standardDateFormat));
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_line_time, ProductData.UTC.parse(netcdfFile.getRootGroup().findVariable(IceyeConstants.LAST_LINE_TIME).readScalarString(), standardDateFormat));
            double[] firstNear = (double[]) netcdfFile.getRootGroup().findVariable(IceyeConstants.FIRST_NEAR).read().getStorage();
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_near_lat, firstNear[2]);

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_near_long, firstNear[3]);
            double[] firstFar = (double[]) netcdfFile.getRootGroup().findVariable(IceyeConstants.FIRST_FAR).read().getStorage();
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_far_lat, firstFar[2]);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_far_long, firstFar[3]);
            double[] lastNear = (double[]) netcdfFile.getRootGroup().findVariable(IceyeConstants.LAST_NEAR).read().getStorage();
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_near_lat, lastNear[2]);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_near_long, lastNear[3]);
            double[] lastFar = (double[]) netcdfFile.getRootGroup().findVariable(IceyeConstants.LAST_FAR).read().getStorage();
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_far_lat, lastFar[2]);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_far_long, lastFar[3]);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PASS, netcdfFile.getRootGroup().findVariable(IceyeConstants.PASS).readScalarString());

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SAMPLE_TYPE, getSampleType());
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.mds1_tx_rx_polar, netcdfFile.getRootGroup().findVariable(IceyeConstants.MDS1_TX_RX_POLAR).readScalarString());

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_looks, netcdfFile.getRootGroup().findVariable(IceyeConstants.AZIMUTH_LOOKS).readScalarFloat());
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_looks, netcdfFile.getRootGroup().findVariable(IceyeConstants.RANGE_LOOKS).readScalarFloat());
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_spacing, netcdfFile.getRootGroup().findVariable(IceyeConstants.SLANT_RANGE_SPACING).readScalarFloat());

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_spacing, netcdfFile.getRootGroup().findVariable(IceyeConstants.AZIMUTH_GROUND_SPACING).readScalarFloat());
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.pulse_repetition_frequency, netcdfFile.getRootGroup().findVariable(IceyeConstants.PULSE_REPETITION_FREQUENCY).readScalarFloat());
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.radar_frequency, netcdfFile.getRootGroup().findVariable(IceyeConstants.RADAR_FREQUENCY).readScalarDouble() / Constants.oneMillion);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.line_time_interval, netcdfFile.getRootGroup().findVariable(IceyeConstants.LINE_TIME_INTERVAL).readScalarDouble());
            final int rasterWidth = netcdfFile.getRootGroup().findVariable(IceyeConstants.NUM_SAMPLES_PER_LINE).readScalarInt();
            final int rasterHeight = netcdfFile.getRootGroup().findVariable(IceyeConstants.NUM_OUTPUT_LINES).readScalarInt();
            double totalSize = (rasterHeight * rasterWidth * 2 * 2) / (1024.0f * 1024.0f);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.TOT_SIZE, totalSize);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.num_output_lines, netcdfFile.getRootGroup().findVariable(IceyeConstants.NUM_OUTPUT_LINES).readScalarInt());

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.num_samples_per_line, netcdfFile.getRootGroup().findVariable(IceyeConstants.NUM_SAMPLES_PER_LINE).readScalarInt());
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.subset_offset_x, IceyeConstants.SUBSET_OFFSET_X_DEFAULT_VALUE);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.subset_offset_y, IceyeConstants.SUBSET_OFFSET_Y_DEFAULT_VALUE);
            if (isComplex) {
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.srgr_flag, 0);
            } else {
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.srgr_flag, 1);
            }
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.avg_scene_height, netcdfFile.getRootGroup().findVariable(IceyeConstants.AVG_SCENE_HEIGHT).readScalarDouble());

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.lat_pixel_res, IceyeConstants.LAT_PIXEL_RES_DEFAULT_VALUE);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.lon_pixel_res, IceyeConstants.LON_PIXEL_RES_DEFAULT_VALUE);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.slant_range_to_first_pixel, (netcdfFile.getRootGroup().findVariable(IceyeConstants.FIRST_PIXEL_TIME).readScalarDouble() / 2) * 299792458.0);

            int antElevCorrFlag = IceyeConstants.ANT_ELEV_CORR_FLAG_DEFAULT_VALUE;
            if (netcdfFile.getRootGroup().findVariable(IceyeConstants.ANT_ELEV_CORR_FLAG) != null) {
                antElevCorrFlag = netcdfFile.getRootGroup().findVariable(IceyeConstants.ANT_ELEV_CORR_FLAG).readScalarInt();
            }
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ant_elev_corr_flag, antElevCorrFlag);

            int rangeSpreadCompFlag = IceyeConstants.RANGE_SPREAD_COMP_FLAG_DEFAULT_VALUE;
            if (netcdfFile.getRootGroup().findVariable(IceyeConstants.RANGE_SPREAD_COMP_FLAG) != null) {
                rangeSpreadCompFlag = netcdfFile.getRootGroup().findVariable(IceyeConstants.RANGE_SPREAD_COMP_FLAG).readScalarInt();
            }
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_spread_comp_flag, rangeSpreadCompFlag);

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.replica_power_corr_flag, IceyeConstants.REPLICA_POWER_CORR_FLAG_DEFAULT_VALUE);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.abs_calibration_flag, IceyeConstants.ABS_CALIBRATION_FLAG_DEFAULT_VALUE);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.calibration_factor, netcdfFile.getRootGroup().findVariable(IceyeConstants.CALIBRATION_FACTOR).readScalarDouble());
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.inc_angle_comp_flag, IceyeConstants.INC_ANGLE_COMP_FLAG_DEFAULT_VALUE);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ref_inc_angle, IceyeConstants.REF_INC_ANGLE_DEFAULT_VALUE);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ref_slant_range, IceyeConstants.REF_SLANT_RANGE_DEFAULT_VALUE);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ref_slant_range_exp, IceyeConstants.REF_SLANT_RANGE_EXP_DEFAULT_VALUE);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.rescaling_factor, IceyeConstants.RESCALING_FACTOR_DEFAULT_VALUE);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_sampling_rate, netcdfFile.getRootGroup().findVariable(IceyeConstants.RANGE_SAMPLING_RATE).readScalarDouble() / 1e6);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_bandwidth, netcdfFile.getRootGroup().findVariable(IceyeConstants.RANGE_BANDWIDTH).readScalarDouble() / 1e6);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_bandwidth, netcdfFile.getRootGroup().findVariable(IceyeConstants.AZIMUTH_BANDWIDTH).readScalarDouble());
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.multilook_flag, IceyeConstants.MULTI_LOOK_FLAG_DEFAULT_VALUE);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.coregistered_stack, IceyeConstants.COREGISTERED_STACK_DEFAULT_VALUE);


            addOrbitStateVectors(absRoot);
        } catch (ParseException | IOException e) {
            SystemUtils.LOG.severe(e.getMessage());

        }
    }

    private void addOrbitStateVectors(final MetadataElement absRoot) {

        try {
            final MetadataElement orbitVectorListElem = absRoot.getElement(AbstractMetadata.orbit_state_vectors);


            final int numPoints = netcdfFile.getRootGroup().findVariable(IceyeConstants.NUMBER_OF_STATE_VECTORS).readScalarInt();
            char[] stateVectorTime = (char[]) netcdfFile.getRootGroup().findVariable(IceyeConstants.STATE_VECTOR_TIME).read().getStorage();
            int utcDimension = netcdfFile.getRootGroup().findVariable(IceyeConstants.STATE_VECTOR_TIME).getDimension(2).getLength();
            final double[] satellitePositionX = (double[]) netcdfFile.getRootGroup().findVariable(IceyeConstants.ORBIT_VECTOR_N_X_POS).read().getStorage();
            final double[] satellitePositionY = (double[]) netcdfFile.getRootGroup().findVariable(IceyeConstants.ORBIT_VECTOR_N_Y_POS).read().getStorage();
            final double[] satellitePositionZ = (double[]) netcdfFile.getRootGroup().findVariable(IceyeConstants.ORBIT_VECTOR_N_Z_POS).read().getStorage();
            final double[] satelliteVelocityX = (double[]) netcdfFile.getRootGroup().findVariable(IceyeConstants.ORBIT_VECTOR_N_X_VEL).read().getStorage();
            final double[] satelliteVelocityY = (double[]) netcdfFile.getRootGroup().findVariable(IceyeConstants.ORBIT_VECTOR_N_Y_VEL).read().getStorage();
            final double[] satelliteVelocityZ = (double[]) netcdfFile.getRootGroup().findVariable(IceyeConstants.ORBIT_VECTOR_N_Z_VEL).read().getStorage();
            int start = 0;
            String utc = new String(Arrays.copyOfRange(stateVectorTime, 0, utcDimension - 1));
            ProductData.UTC stateVectorUTC = ProductData.UTC.parse(utc, standardDateFormat);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.STATE_VECTOR_TIME, stateVectorUTC);
            for (int i = 0; i < numPoints; i++) {
                utc = new String(Arrays.copyOfRange(stateVectorTime, start, start + utcDimension - 1));
                ProductData.UTC vectorUTC = ProductData.UTC.parse(utc, standardDateFormat);

                final MetadataElement orbitVectorElem = new MetadataElement(AbstractMetadata.orbit_vector + (i + 1));
                orbitVectorElem.setAttributeUTC(AbstractMetadata.orbit_vector_time, vectorUTC);

                orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_x_pos, satellitePositionX[i]);
                orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_y_pos, satellitePositionY[i]);
                orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_z_pos, satellitePositionZ[i]);
                orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_x_vel, satelliteVelocityX[i]);
                orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_y_vel, satelliteVelocityY[i]);
                orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_z_vel, satelliteVelocityZ[i]);

                orbitVectorListElem.addElement(orbitVectorElem);
                start += utcDimension;
            }
        } catch (IOException | ParseException e) {
            SystemUtils.LOG.severe(e.getMessage());

        }
    }

    private void addDopplerCentroidCoefficients() {

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);

        final MetadataElement dopplerCentroidCoefficientsElem = absRoot.getElement(AbstractMetadata.dop_coefficients);
        final MetadataElement dopplerListElem = new MetadataElement(AbstractMetadata.dop_coef_list + ".1");
        dopplerCentroidCoefficientsElem.addElement(dopplerListElem);

        final ProductData.UTC utcTime = absRoot.getAttributeUTC(AbstractMetadata.first_line_time, AbstractMetadata.NO_METADATA_UTC);
        dopplerListElem.setAttributeUTC(AbstractMetadata.dop_coef_time, utcTime);

        AbstractMetadata.addAbstractedAttribute(dopplerListElem, AbstractMetadata.slant_range_time,
                ProductData.TYPE_FLOAT64, "ns", "Slant Range Time");
        AbstractMetadata.setAttribute(dopplerListElem, AbstractMetadata.slant_range_time, 0.0);
        try {

            int dimensionColumn = netcdfFile.getRootGroup().findVariable(IceyeConstants.DC_ESTIMATE_COEFFS).getDimension(1).getLength();
            double[] coefValueS = (double[]) netcdfFile.getRootGroup().findVariable(IceyeConstants.DC_ESTIMATE_COEFFS).read().getStorage();

            for (int i = 0; i < dimensionColumn; i++) {
                final double coefValue = coefValueS[i];
                final MetadataElement coefElem = new MetadataElement(AbstractMetadata.coefficient + '.' + (i + 1));
                dopplerListElem.addElement(coefElem);
                AbstractMetadata.addAbstractedAttribute(coefElem, AbstractMetadata.dop_coef,
                        ProductData.TYPE_FLOAT64, "", "Doppler Centroid Coefficient");
                AbstractMetadata.setAttribute(coefElem, AbstractMetadata.dop_coef, coefValue);
            }
        } catch (IOException e) {
            SystemUtils.LOG.severe(e.getMessage());

        }
    }

    private void addDopplerMetadata() throws IOException {
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        final String imagingMode = absRoot.getAttributeString("ACQUISITION_MODE");

        if (imagingMode.equalsIgnoreCase("spotlight"))  {
            final MetadataElement dopplerSpotlightElem = new MetadataElement("dopplerSpotlight");
            absRoot.addElement(dopplerSpotlightElem);
            addDopplerRateAndCentroidSpotlight(dopplerSpotlightElem);
            addAzimuthTimeZpSpotlight(dopplerSpotlightElem);
        }
        addDopplerCentroidCoefficients();
    }

    private void addDopplerRateAndCentroidSpotlight(MetadataElement elem) {
        // Compute doppler rate and centroid
        MetadataElement origProdRoot = AbstractMetadata.getOriginalProductMetadata(product);
        MetadataElement dopplerRateCoeffs = origProdRoot.getElement(IceyeConstants.DR_COEFFS);
        String dopplerRate = dopplerRateCoeffs.getAttributeString("data").split(",")[0]; // take first coefficient
        final double fmRate = Double.parseDouble(dopplerRate);
        final double dopplerCentroid = 0.0; // TODO: load from original metadata once it's accurate

        final int rasterWidth = product.getSceneRasterWidth();
        final double[] dopplerRateSpotlight = new double[rasterWidth];
        final double[] dopplerCentroidSpotlight = new double[rasterWidth];

        for(int i = 0; i < rasterWidth; i++) {
            dopplerRateSpotlight[i] = fmRate;
            dopplerCentroidSpotlight[i] = dopplerCentroid;
        }

        // Save in metadata
        String dopplerRateSpotlightStr = Arrays.toString(dopplerRateSpotlight).replace("]", "").replace("[", "");
        String dopplerCentroidSpotlightStr = Arrays.toString(dopplerCentroidSpotlight).replace("]", "").replace("[", "");

        AbstractMetadata.addAbstractedAttribute(elem, "dopplerRateSpotlight",
               ProductData.TYPE_ASCII, "", "Doppler Rate Spotlight");
        AbstractMetadata.setAttribute(elem, "dopplerRateSpotlight", dopplerRateSpotlightStr);

        AbstractMetadata.addAbstractedAttribute(elem, "dopplerCentroidSpotlight",
                ProductData.TYPE_ASCII, "", "Doppler Centroid Spotlight");
        AbstractMetadata.setAttribute(elem, "dopplerCentroidSpotlight", dopplerCentroidSpotlightStr);
    }

    private void addAzimuthTimeZpSpotlight(MetadataElement elem) throws IOException {
        // Compute azimuth time
        final double firstAzimuthTimeZp = timeUTCtoSecs(netcdfFile.getRootGroup().findVariable(IceyeConstants.FIRST_LINE_TIME).readScalarString());
        final double lastAzimuthTimeZp = timeUTCtoSecs(netcdfFile.getRootGroup().findVariable(IceyeConstants.LAST_LINE_TIME).readScalarString());
        final double AzimuthTimeZpOffset = firstAzimuthTimeZp - 0.5 * (firstAzimuthTimeZp + lastAzimuthTimeZp);

        // Save in metadata
        final MetadataElement azimuthTimeZd = new MetadataElement("azimuthTimeZdSpotlight");
        elem.addElement(azimuthTimeZd);
        AbstractMetadata.addAbstractedAttribute(azimuthTimeZd, "AzimuthTimeZdOffset",
                                                ProductData.TYPE_FLOAT64, "", "Azimuth Time Zero Doppler Offset");
        AbstractMetadata.setAttribute(azimuthTimeZd, "AzimuthTimeZdOffset", AzimuthTimeZpOffset);
    }

    private double timeUTCtoSecs(String myDate) {
        ProductData.UTC localDateTime = null;
        try {
            localDateTime = ProductData.UTC.parse(myDate, standardDateFormat);
        } catch (ParseException e) {
            SystemUtils.LOG.severe(e.getMessage());
        }
        return localDateTime.getMJD() * 24.0 * 3600.0;
    }

    private String getSampleType() {
        try {
            if (IceyeConstants.SLC.equalsIgnoreCase(netcdfFile.getRootGroup().findVariable(IceyeConstants.SPH_DESCRIPTOR).readScalarString())) {
                isComplex = true;
                return IceyeConstants.COMPLEX;
            }
        } catch (IOException e) {
            SystemUtils.LOG.severe(e.getMessage());
        }
        isComplex = false;
        return IceyeConstants.DETECTED;
    }

    private void addBandsToProduct() {
        int cnt = 1;
        Map<String, Variable> variables = new HashMap<>();
        Variable siBand = netcdfFile.getRootGroup().findVariable(IceyeConstants.S_I);
        Variable sqBand = netcdfFile.getRootGroup().findVariable(IceyeConstants.S_Q);
        Variable amplitudeBand = netcdfFile.getRootGroup().findVariable(IceyeConstants.S_AMPLITUDE);
        if (siBand != null) {
            variables.put(IceyeConstants.S_I, siBand);
        }
        if (sqBand != null) {
            variables.put(IceyeConstants.S_Q, sqBand);
        }
        if (amplitudeBand != null) {
            variables.put(IceyeConstants.S_AMPLITUDE, amplitudeBand);
        }
        try {

            final int width = netcdfFile.getRootGroup().findVariable(IceyeConstants.NUM_SAMPLES_PER_LINE).readScalarInt();
            final int height = netcdfFile.getRootGroup().findVariable(IceyeConstants.NUM_OUTPUT_LINES).readScalarInt();

            String cntStr = "";
            if (variables.size() > 1) {
                final String polStr = getPolarization(product, netcdfFile);
                if (polStr != null) {
                    cntStr = "_" + polStr;
                } else {
                    cntStr = "_" + cnt;
                }
            }

            if (isComplex) {
                final Band bandI = NetCDFUtils.createBand(variables.get(IceyeConstants.S_I), width, height);
                createUniqueBandName(product, bandI, "i" + cntStr);
                bandI.setUnit(Unit.REAL);
                bandI.setNoDataValue(0);
                bandI.setNoDataValueUsed(true);
                product.addBand(bandI);
                bandMap.put(bandI, variables.get(IceyeConstants.S_I));

                final Band bandQ = NetCDFUtils.createBand(variables.get(IceyeConstants.S_Q), width, height);
                createUniqueBandName(product, bandQ, "q" + cntStr);
                bandQ.setUnit(Unit.IMAGINARY);
                bandQ.setNoDataValue(0);
                bandQ.setNoDataValueUsed(true);
                product.addBand(bandQ);
                bandMap.put(bandQ, variables.get(IceyeConstants.S_Q));

                ReaderUtils.createVirtualIntensityBand(product, bandI, bandQ, cntStr);
            } else {
                final Band band = NetCDFUtils.createBand(variables.get(IceyeConstants.S_AMPLITUDE), width, height);
                createUniqueBandName(product, band, "Amplitude" + cntStr);
                band.setUnit(Unit.AMPLITUDE);
                band.setNoDataValue(0);
                band.setNoDataValueUsed(true);
                product.addBand(band);
                bandMap.put(band, variables.get(IceyeConstants.S_AMPLITUDE));

                createVirtualIntensityBand(product, band, cntStr);
            }
        } catch (IOException e) {
            SystemUtils.LOG.severe(e.getMessage());

        }
    }

    private void addTiePointGridsToProduct() {

        final MetadataElement bandElem = getBandElement(product.getBandAt(0));
        addIncidenceAnglesSlantRangeTime(product, bandElem, netcdfFile);
        addGeocodingFromMetadata(product, bandElem, netcdfFile);
    }

    private MetadataElement getBandElement(final Band band) {
        final MetadataElement root = AbstractMetadata.getOriginalProductMetadata(product);
        final Variable variable = bandMap.get(band);
        final String varName = variable.getShortName();
        MetadataElement bandElem = null;
        for (MetadataElement elem : root.getElements()) {
            if (elem.getName().equalsIgnoreCase(varName)) {
                bandElem = elem;
                break;
            }
        }
        return bandElem;
    }

    private void addGeoCodingToProduct() throws IOException {
        if (product.getSceneGeoCoding() == null) {
            NetCDFReader.setTiePointGeoCoding(product);
        }
        if (product.getSceneGeoCoding() == null) {
            NetCDFReader.setPixelGeoCoding(product);
        }

    }

    void callReadBandRasterData(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight,
                                int sourceStepX, int sourceStepY, Band destBand, int destOffsetX,
                                int destOffsetY, int destWidth, int destHeight, ProductData destBuffer,
                                ProgressMonitor pm) throws IOException {
        readBandRasterDataImpl(sourceOffsetX, sourceOffsetY, sourceWidth, sourceHeight,
                sourceStepX, sourceStepY, destBand, destOffsetX, destOffsetY, destWidth, destHeight, destBuffer, pm);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void readBandRasterDataImpl(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight,
                                          int sourceStepX, int sourceStepY, Band destBand, int destOffsetX,
                                          int destOffsetY, int destWidth, int destHeight, ProductData destBuffer,
                                          ProgressMonitor pm) throws IOException {

        final int sceneHeight = product.getSceneRasterHeight();
        final int sceneWidth = product.getSceneRasterWidth();

        final Variable variable = bandMap.get(destBand);

        destHeight = Math.min(destHeight, sceneHeight - sourceOffsetY);
        sourceWidth = Math.min(sourceWidth, sceneWidth - sourceOffsetX);
        destWidth = Math.min(destWidth, sceneWidth - destOffsetX);
        final int[] origin = {sourceOffsetY, sourceOffsetX};
        final int[] shape = {1, sourceWidth};
        pm.beginTask("Reading util from band " + destBand.getName(), destHeight);
        try {
            for (int y = 0; y < destHeight; y++) {
                origin[0] = sourceOffsetY + y;
                final Array array;
                synchronized (netcdfFile) {
                    array = variable.read(origin, shape);
                }
                System.arraycopy(array.getStorage(), 0, destBuffer.getElems(), y * destWidth, destWidth);
                pm.worked(1);
            }
        } catch (Exception e) {
            final IOException ioException = new IOException(e);
            ioException.initCause(e);
            throw ioException;
        } finally {
            pm.done();
        }
    }

}