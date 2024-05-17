package eu.esa.sar.io.nisar.subreaders;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.io.netcdf.NetCDFReader;
import eu.esa.sar.io.netcdf.NetCDFUtils;
import eu.esa.sar.io.netcdf.NetcdfConstants;
import eu.esa.sar.io.nisar.util.NisarXConstants;
import org.esa.snap.core.dataio.ProductReader;
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
import ucar.ma2.DataType;
import ucar.ma2.StructureData;
import ucar.ma2.StructureMembers;
import ucar.nc2.Group;
import ucar.nc2.NetcdfFile;
import ucar.nc2.Variable;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ucar.ma2.DataType.STRUCTURE;

public abstract class NisarSubReader {

    protected final Map<Band, Variable> bandMap = new HashMap<>();
    protected final DateFormat standardDateFormat = ProductData.UTC.createDateFormat("yyyy-MM-dd'T'HH:mm:ss");
    protected NetcdfFile netcdfFile = null;
    protected Product product = null;
    protected String productType;
    protected boolean isComplex = true;

    public void close() throws IOException {
        if (netcdfFile != null) {
            netcdfFile.close();
            netcdfFile = null;
        }
    }

    /**
     * Provides an implementation of the <code>readProductNodes</code> interface method. Clients implementing this
     * method can be sure that the input object and eventually the subset information has already been set.
     * <p/>
     * <p>This method is called as a last step in the <code>readProductNodes(input, subsetInfo)</code> method.
     */
    public Product readProduct(final ProductReader reader, final NetcdfFile netcdfFile, final File inputFile) {
        this.netcdfFile = netcdfFile;

        try {
            final Group groupLSAR = getLSARGroup();
            final Group groupID = getIndenificationGroup(groupLSAR);
            final Group groupFrequencyA = getFrequencyAGroup(groupLSAR);

            Variable[] rasterVariables = getRasterVariables(groupFrequencyA);

            final int rasterHeight = rasterVariables[0].getDimension(0).getLength();
            final int rasterWidth = rasterVariables[0].getDimension(1).getLength();
            final String productType = getProductType(groupID);

            product = new Product(inputFile.getName(),
                    productType,
                    rasterWidth, rasterHeight,
                    reader);
            product.setFileLocation(inputFile);

            product.setDescription(getDescription(inputFile.getName(), groupID));
            product.setStartTime(ProductData.UTC.parse(getStartTime(groupID), standardDateFormat));
            product.setEndTime(ProductData.UTC.parse(getStopTime(groupID), standardDateFormat));

            addMetadataToProduct();
            addBandsToProduct();
            addTiePointGridsToProduct();
            addGeoCodingToProduct();
            addDopplerMetadata();

            return product;
        } catch (Exception e) {
            SystemUtils.LOG.severe(e.getMessage());
            return null;
        }
    }

    protected Group getLSARGroup() {
        final Group groupScience = this.netcdfFile.getRootGroup().findGroup("science");
        return groupScience.findGroup("LSAR");
    }

    protected Group getIndenificationGroup(final Group groupLSAR) {
        return groupLSAR.findGroup("identification");
    }

    protected Group getFrequencyAGroup(final Group groupLSAR) {
        final Group groupProductType = groupLSAR.findGroup(productType);
        final Group groupSwaths = groupProductType.findGroup("swaths");
        return groupSwaths.findGroup("frequencyA");
    }

    protected Group getMetadataGroup(final Group groupLSAR) {
        final Group groupProductType = groupLSAR.findGroup(productType);
        return groupProductType.findGroup("metadata");
    }

    protected Group[] getPolarizationGroups(final Group group) {
        List<Group> polGroups = new ArrayList<>();
        final Group groupHH = group.findGroup("HH");
        polGroups.add(groupHH);

        return polGroups.toArray(new Group[0]);
    }

    protected abstract Variable[] getRasterVariables(final Group group);

    protected String getProductType(Group groupID) throws Exception {
        return groupID.findVariable(NisarXConstants.PRODUCT_TYPE).readScalarString();
    }

    protected String getDescription(String filename, Group groupID) throws Exception {
        final String productType = getProductType(groupID);
        final Variable missionID = groupID.findVariable(NisarXConstants.MISSION);
        String description =  filename + " - " + productType;
        if(missionID != null) {
            description += " - " + missionID.readScalarString();
        }
        return description;
    }

    protected String getStartTime(final Group groupID) throws IOException {
        Variable var = groupID.findVariable(NisarXConstants.ACQUISITION_START_UTC);
        if (var == null) {
            var = groupID.findVariable("referenceZeroDopplerStartTime");
        }
        return var.readScalarString().substring(0, 22);
    }

    protected String getStopTime(final Group groupID) throws IOException {
        Variable var = groupID.findVariable(NisarXConstants.ACQUISITION_END_UTC);
        if (var == null) {
            var = groupID.findVariable("referenceZeroDopplerEndTime");
        }
        return var.readScalarString().substring(0, 22);
    }

    protected abstract void addBandsToProduct();

    protected void addMetadataToProduct() throws Exception{

        final MetadataElement origMetadataRoot = AbstractMetadata.addOriginalProductMetadata(product.getMetadataRoot());
        NetCDFUtils.addAttributes(origMetadataRoot, NetcdfConstants.GLOBAL_ATTRIBUTES_NAME,
                netcdfFile.getGlobalAttributes());

        for (Variable variable : netcdfFile.getVariables()) {
            NetCDFUtils.addVariableMetadata(origMetadataRoot, variable, 5000);
        }

        addAbstractedMetadataHeader(product.getMetadataRoot());
    }

    protected abstract void addAbstractedMetadataHeader(MetadataElement root) throws Exception;

    protected void addTiePointGridsToProduct() {

        final MetadataElement bandElem = getBandElement(product.getBandAt(0));
        addIncidenceAnglesSlantRangeTime(product, bandElem);
        addGeocodingFromMetadata(product, bandElem);
    }

    protected void createBand(final String bandName, final int width, final int height, final String unit, final Variable var) {
        final Band band = new Band(bandName, ProductData.TYPE_FLOAT32, width, height);
        band.setDescription(var.getDescription());
        band.setUnit(unit);
        band.setNoDataValue(0);
        band.setNoDataValueUsed(true);
        product.addBand(band);
        bandMap.put(band, var);
    }

    protected void addIncidenceAnglesSlantRangeTime(final Product product, final MetadataElement bandElem) {

        try {
            final Group groupLSAR = getLSARGroup();
            final Group groupMetadata = getMetadataGroup(groupLSAR);
            final Group metadata = groupMetadata.findGroup("geolocationGrid");

            Variable incidenceAngleVar = metadata.findVariable("incidenceAngle");
            Array incidenceArray = incidenceAngleVar.read();
            float[] incidenceAngles = (float[]) incidenceArray.get1DJavaArray(DataType.FLOAT);

            //if (bandElem == null) return;

            final int gridWidth = 11;
            final int gridHeight = 11;
            final float subSamplingX = product.getSceneRasterWidth() / (float) (gridWidth - 1);
            final float subSamplingY = product.getSceneRasterHeight() / (float) (gridHeight - 1);

//            final double[] incidenceAngles = (double[]) netcdfFile.getRootGroup().findVariable(
//                    NisarXConstants.INCIDENCE_ANGLES).read().getStorage();
//
//            final double nearRangeAngle = incidenceAngles[0];
//            final double farRangeAngle = incidenceAngles[incidenceAngles.length - 1];
//
//            final double firstRangeTime = netcdfFile.getRootGroup().findVariable(
//                    NisarXConstants.FIRST_PIXEL_TIME).readScalarDouble() * Constants.sTOns;
//
//            final double samplesPerLine = netcdfFile.getRootGroup().findVariable(
//                    NisarXConstants.NUM_SAMPLES_PER_LINE).readScalarDouble();
//
//            final double rangeSamplingRate = netcdfFile.getRootGroup().findVariable(
//                    NisarXConstants.RANGE_SAMPLING_RATE).readScalarDouble();
//
//            final double lastRangeTime = firstRangeTime + samplesPerLine / rangeSamplingRate * Constants.sTOns;
//
//            final float[] incidenceCorners = new float[]{(float) nearRangeAngle, (float) farRangeAngle,
//                    (float) nearRangeAngle, (float) farRangeAngle};
//
//            final float[] slantRange = new float[]{(float) firstRangeTime, (float) lastRangeTime,
//                    (float) firstRangeTime, (float) lastRangeTime};
//
//            final float[] fineAngles = new float[gridWidth * gridHeight];
//            final float[] fineTimes = new float[gridWidth * gridHeight];
//
//            ReaderUtils.createFineTiePointGrid(2, 2, gridWidth, gridHeight,
//                    incidenceCorners, fineAngles);
//
//            ReaderUtils.createFineTiePointGrid(2, 2, gridWidth, gridHeight,
//                    slantRange, fineTimes);
//
//            final TiePointGrid incidentAngleGrid = new TiePointGrid(OperatorUtils.TPG_INCIDENT_ANGLE,
//                    gridWidth, gridHeight, 0, 0, subSamplingX, subSamplingY, fineAngles);
//            incidentAngleGrid.setUnit(Unit.DEGREES);
//            product.addTiePointGrid(incidentAngleGrid);
//
//            final TiePointGrid slantRangeGrid = new TiePointGrid(OperatorUtils.TPG_SLANT_RANGE_TIME,
//                    gridWidth, gridHeight, 0, 0, subSamplingX, subSamplingY, fineTimes);
//            slantRangeGrid.setUnit(Unit.NANOSECONDS);
//            product.addTiePointGrid(slantRangeGrid);

        } catch (IOException e) {
            SystemUtils.LOG.severe(e.getMessage());

        }
    }

    protected void addGeocodingFromMetadata(
            final Product product, final MetadataElement bandElem) {

        if (bandElem == null) return;

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);

        try {
            double[] firstNear = (double[]) netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.FIRST_NEAR).read().getStorage();

            double[] firstFar = (double[]) netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.FIRST_FAR).read().getStorage();

            double[] lastNear = (double[]) netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.LAST_NEAR).read().getStorage();

            double[] lastFar = (double[]) netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.LAST_FAR).read().getStorage();

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
                    netcdfFile.getRootGroup().findVariable(NisarXConstants.SLANT_RANGE_SPACING).readScalarDouble());

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_spacing,
                    netcdfFile.getRootGroup().findVariable(NisarXConstants.AZIMUTH_GROUND_SPACING).readScalarDouble());

            final double[] latCorners = new double[]{latUL, latUR, latLL, latLR};
            final double[] lonCorners = new double[]{lonUL, lonUR, lonLL, lonLR};

            ReaderUtils.addGeoCoding(product, latCorners, lonCorners);

        } catch (Exception e) {
            SystemUtils.LOG.severe(e.getMessage());
        }
    }

    protected static String getPolarization(final Product product, NetcdfFile netcdfFile) {

        final MetadataElement globalElem = AbstractMetadata.getOriginalProductMetadata(product).getElement(
                NetcdfConstants.GLOBAL_ATTRIBUTES_NAME);

        try {
            if (globalElem != null) {
                final String polStr = netcdfFile.getRootGroup().findVariable(NisarXConstants.MDS1_TX_RX_POLAR).
                        readScalarString();

                if (!polStr.isEmpty())
                    return polStr;
            }
        } catch (IOException e) {
            SystemUtils.LOG.severe(e.getMessage());

        }
        return null;
    }

    protected String getSampleType() {

        try {
            if (NisarXConstants.SLC.equalsIgnoreCase(netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.SPH_DESCRIPTOR).readScalarString())) {

                isComplex = true;
                return NisarXConstants.COMPLEX;
            }
        } catch (IOException e) {
            SystemUtils.LOG.severe(e.getMessage());
        }
        isComplex = false;
        return NisarXConstants.DETECTED;
    }

    protected double timeUTCtoSecs(String myDate) {

        ProductData.UTC localDateTime = null;
        try {
            localDateTime = ProductData.UTC.parse(myDate, standardDateFormat);
        } catch (ParseException e) {
            SystemUtils.LOG.severe(e.getMessage());
        }
        return localDateTime.getMJD() * 24.0 * 3600.0;
    }

    protected void addOrbitStateVectors(final MetadataElement absRoot) {

        try {
            final MetadataElement orbitVectorListElem = absRoot.getElement(AbstractMetadata.orbit_state_vectors);

            final int numPoints = netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.NUMBER_OF_STATE_VECTORS).readScalarInt();

            char[] stateVectorTime = (char[]) netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.STATE_VECTOR_TIME).read().getStorage();

            int utcDimension = netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.STATE_VECTOR_TIME).getDimension(2).getLength();

            final double[] satellitePositionX = (double[]) netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.ORBIT_VECTOR_N_X_POS).read().getStorage();

            final double[] satellitePositionY = (double[]) netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.ORBIT_VECTOR_N_Y_POS).read().getStorage();

            final double[] satellitePositionZ = (double[]) netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.ORBIT_VECTOR_N_Z_POS).read().getStorage();

            final double[] satelliteVelocityX = (double[]) netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.ORBIT_VECTOR_N_X_VEL).read().getStorage();

            final double[] satelliteVelocityY = (double[]) netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.ORBIT_VECTOR_N_Y_VEL).read().getStorage();

            final double[] satelliteVelocityZ = (double[]) netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.ORBIT_VECTOR_N_Z_VEL).read().getStorage();

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

    protected MetadataElement getBandElement(final Band band) {

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

    protected void addGeoCodingToProduct() throws IOException {

        if (product.getSceneGeoCoding() == null) {
            NetCDFReader.setTiePointGeoCoding(product);
        }

        if (product.getSceneGeoCoding() == null) {
            NetCDFReader.setPixelGeoCoding(product);
        }

    }

    protected void addDopplerCentroidCoefficients() {

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);

        final MetadataElement dopplerCentroidCoefficientsElem = absRoot.getElement(AbstractMetadata.dop_coefficients);
        final MetadataElement dopplerListElem = new MetadataElement(AbstractMetadata.dop_coef_list + ".1");
        dopplerCentroidCoefficientsElem.addElement(dopplerListElem);

        final ProductData.UTC utcTime = absRoot.getAttributeUTC(AbstractMetadata.first_line_time,
                AbstractMetadata.NO_METADATA_UTC);

        dopplerListElem.setAttributeUTC(AbstractMetadata.dop_coef_time, utcTime);

        AbstractMetadata.addAbstractedAttribute(dopplerListElem, AbstractMetadata.slant_range_time,
                ProductData.TYPE_FLOAT64, "ns", "Slant Range Time");

        AbstractMetadata.setAttribute(dopplerListElem, AbstractMetadata.slant_range_time, 0.0);

        try {

            int dimensionColumn = netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.DC_ESTIMATE_COEFFS).getDimension(1).getLength();

            double[] coefValueS = (double[]) netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.DC_ESTIMATE_COEFFS).read().getStorage();

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

    protected void addDopplerMetadata() {

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        final String imagingMode = absRoot.getAttributeString("ACQUISITION_MODE");

        if (imagingMode.equalsIgnoreCase("spotlight")) {
            final MetadataElement dopplerSpotlightElem = new MetadataElement("dopplerSpotlight");
            absRoot.addElement(dopplerSpotlightElem);
            addDopplerRateAndCentroidSpotlight(dopplerSpotlightElem);
            addAzimuthTimeZpSpotlight(dopplerSpotlightElem);
        }
//        addDopplerCentroidCoefficients();
    }

    protected void addDopplerRateAndCentroidSpotlight(MetadataElement elem) {

        // Compute doppler rate and centroid
        MetadataElement origProdRoot = AbstractMetadata.getOriginalProductMetadata(product);
        MetadataElement dopplerRateCoeffs = origProdRoot.getElement(NisarXConstants.DR_COEFFS);
        String dopplerRate = dopplerRateCoeffs.getAttributeString("data").split(",")[0]; // take first coefficient
        final double fmRate = Double.parseDouble(dopplerRate);
        final double dopplerCentroid = 0.0; // TODO: load from original metadata once it's accurate

        final int rasterWidth = product.getSceneRasterWidth();
        final double[] dopplerRateSpotlight = new double[rasterWidth];
        final double[] dopplerCentroidSpotlight = new double[rasterWidth];

        for (int i = 0; i < rasterWidth; i++) {
            dopplerRateSpotlight[i] = fmRate;
            dopplerCentroidSpotlight[i] = dopplerCentroid;
        }

        // Save in metadata
        String dopplerRateSpotlightStr =
                Arrays.toString(dopplerRateSpotlight).replace("]", "").replace("[", "");

        String dopplerCentroidSpotlightStr =
                Arrays.toString(dopplerCentroidSpotlight).replace("]", "").replace("[", "");

        AbstractMetadata.addAbstractedAttribute(elem, "dopplerRateSpotlight",
                ProductData.TYPE_ASCII, "", "Doppler Rate Spotlight");

        AbstractMetadata.setAttribute(elem, "dopplerRateSpotlight", dopplerRateSpotlightStr);

        AbstractMetadata.addAbstractedAttribute(elem, "dopplerCentroidSpotlight",
                ProductData.TYPE_ASCII, "", "Doppler Centroid Spotlight");

        AbstractMetadata.setAttribute(elem, "dopplerCentroidSpotlight", dopplerCentroidSpotlightStr);
    }

    protected void addAzimuthTimeZpSpotlight(MetadataElement elem) {
        // Compute azimuth time
        MetadataElement origProdRoot = AbstractMetadata.getOriginalProductMetadata(product);

        final double firstAzimuthTimeZp = timeUTCtoSecs(origProdRoot.getAttributeString(
                NisarXConstants.FIRST_LINE_TIME));

        final double lastAzimuthTimeZp = timeUTCtoSecs(origProdRoot.getAttributeString(
                NisarXConstants.LAST_LINE_TIME));

        final double AzimuthTimeZpOffset = firstAzimuthTimeZp - 0.5 * (firstAzimuthTimeZp + lastAzimuthTimeZp);

        // Save in metadata
        final MetadataElement azimuthTimeZd = new MetadataElement("azimuthTimeZdSpotlight");

        elem.addElement(azimuthTimeZd);

        AbstractMetadata.addAbstractedAttribute(azimuthTimeZd, "AzimuthTimeZdOffset", ProductData.TYPE_FLOAT64,
                "", "Azimuth Time Zero Doppler Offset");

        AbstractMetadata.setAttribute(azimuthTimeZd, "AzimuthTimeZdOffset", AzimuthTimeZpOffset);
    }

    /**
     * {@inheritDoc}
     */
    //Todo remove synchronized
    public synchronized void readBandRasterDataImpl(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight,
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
            boolean isComplexData = variable.getDataType() == DataType.STRUCTURE;
            String complexMemberName = "HH";//destBand.getName().contains("i_") ? "r" : "i";

            for (int y = 0; y < destHeight; y++) {
                origin[0] = sourceOffsetY + y;
                final Array array;
                synchronized (netcdfFile) {
                    array = variable.read(origin, shape);
                }

                if (isComplexData) {
                    StructureData[] row = (StructureData[]) array.get1DJavaArray(STRUCTURE);
                    final float[] tempArray = new float[row.length];
                    for (int i = 0; i < row.length; ++i) {
                        StructureMembers members = row[i].getStructureMembers();
                        List<StructureMembers.Member> members1 = row[i].getMembers();

                        tempArray[i] = row[i].convertScalarFloat(complexMemberName);
                    }
                    System.arraycopy(tempArray, 0, destBuffer.getElems(), y * destWidth, destWidth);
                } else {
                    float[] tempArray = (float[]) array.get1DJavaArray(Float.TYPE);
                    System.arraycopy(tempArray, 0, destBuffer.getElems(), y * destWidth, destWidth);
                }

                pm.worked(1);
            }
        } catch (Exception e) {
            //final IOException ioException = new IOException(e);
            //ioException.initCause(e);
            //throw ioException;
            System.out.println(e.getMessage());
        } finally {
            pm.done();
        }
    }
}
