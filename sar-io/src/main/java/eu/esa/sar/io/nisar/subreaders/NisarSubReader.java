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
package eu.esa.sar.io.nisar.subreaders;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.product.Missions;
import eu.esa.sar.io.netcdf.NetCDFUtils;
import eu.esa.sar.io.netcdf.NetcdfConstants;
import eu.esa.sar.io.nisar.util.NisarXConstants;
import eu.esa.sar.io.pcidsk.UTM2LatLon;
import org.esa.snap.core.dataio.IllegalFileFormatException;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.TiePointGeoCoding;
import org.esa.snap.core.datamodel.TiePointGrid;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.core.util.geotiff.EPSGCodes;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import ucar.ma2.Array;
import ucar.ma2.ArrayStructure;
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
    protected final DateFormat standardDateFormat = ProductData.UTC.createDateFormat("yyyy-MM-dd HH:mm:ss");
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

    private void open(final File inputFile) throws IOException {
        this.netcdfFile = NetcdfFile.open(inputFile.getPath());
        if (netcdfFile == null) {
            close();
            throw new IllegalFileFormatException(inputFile.getName() +
                    " Could not be interpreted by the reader.");
        }

        if (netcdfFile.getRootGroup().getGroups().isEmpty()) {
            close();
            throw new IllegalFileFormatException("No netCDF groups found.");
        }
    }

    /**
     * Provides an implementation of the <code>readProductNodes</code> interface method. Clients implementing this
     * method can be sure that the input object and eventually the subset information has already been set.
     * <p/>
     * <p>This method is called as a last step in the <code>readProductNodes(input, subsetInfo)</code> method.
     */
    public Product readProduct(final ProductReader reader, final File inputFile) throws IOException {
        open(inputFile);

        try {
            final Group groupLSAR = getLSARGroup();
            final Group groupID = getIndenificationGroup(groupLSAR);
            final Group groupFrequencyA = getFrequencyAGroup(groupLSAR);

            Variable[] rasterVariables = getRasterVariables(groupFrequencyA);

            final int rasterHeight = rasterVariables[0].getDimension(0).getLength();
            final int rasterWidth = rasterVariables[0].getDimension(1).getLength();
            productType = getProductType(groupID);

            product = new Product(inputFile.getName(),
                    productType,
                    rasterWidth, rasterHeight,
                    reader);
            product.setFileLocation(inputFile);

            addMetadataToProduct();
            addBandsToProduct();
            addTiePointGridsToProduct();
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
        String description = filename + " - " + productType;
        if (missionID != null) {
            description += " - " + missionID.readScalarString();
        }
        return description;
    }

    protected abstract void addBandsToProduct();

    protected void addMetadataToProduct() throws Exception {

        final MetadataElement origMetadataRoot = AbstractMetadata.addOriginalProductMetadata(product.getMetadataRoot());
        NetCDFUtils.addAttributes(origMetadataRoot, NetcdfConstants.GLOBAL_ATTRIBUTES_NAME,
                netcdfFile.getGlobalAttributes());

        for (Variable variable : netcdfFile.getVariables()) {
            NetCDFUtils.addVariableMetadata(origMetadataRoot, variable, 5000);
        }

        addAbstractedMetadataHeader(product.getMetadataRoot());
    }

    protected void addAbstractedMetadataHeader(final MetadataElement root) throws Exception {

        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(root);
        final MetadataElement origMeta = AbstractMetadata.getOriginalProductMetadata(product);

        MetadataElement globals = origMeta.getElement("Global_Attributes");
        MetadataElement science = origMeta.getElement("science");
        MetadataElement lsar = science.getElement("LSAR");
        MetadataElement identification = lsar.getElement("identification");

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PRODUCT, product.getName());
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.MISSION, Missions.NISAR);

        String title = globals.getAttributeString("title");
        product.setDescription(title);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SPH_DESCRIPTOR, title);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PRODUCT_TYPE, productType);

        MetadataElement lookDirection = identification.getElement("lookDirection");
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.antenna_pointing,
                lookDirection.getAttributeString("lookDirection").toLowerCase());

        MetadataElement trackNumber = identification.getElement("trackNumber");
        if (trackNumber != null) {
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.REL_ORBIT,
                    trackNumber.getAttributeInt("trackNumber"));
        }

        MetadataElement absoluteOrbitNumber = identification.getElement("absoluteOrbitNumber");
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ABS_ORBIT,
                absoluteOrbitNumber.getAttributeInt("absoluteOrbitNumber"));

        MetadataElement orbitPassDirection = identification.getElement("orbitPassDirection");
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PASS,
                getOrbitPass(orbitPassDirection.getAttributeString("orbitPassDirection")));

        MetadataElement plannedDataTakeId = identification.getElement("plannedDataTakeId");
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.data_take_id,
                plannedDataTakeId.getAttributeInt("plannedDataTakeId"));

        MetadataElement processingDataTime = identification.getElement("processingDataTime");
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PROC_TIME,
                ReaderUtils.getTime(processingDataTime, "processingDataTime", standardDateFormat));

        MetadataElement zeroDopplerStartTime = identification.getElement("zeroDopplerStartTime");
        ProductData.UTC startTime = ReaderUtils.getTime(zeroDopplerStartTime, "zeroDopplerStartTime", standardDateFormat);
        product.setStartTime(startTime);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_line_time, startTime);

        MetadataElement zeroDopplerEndTime = identification.getElement("zeroDopplerEndTime");
        ProductData.UTC endTime = ReaderUtils.getTime(zeroDopplerEndTime, "zeroDopplerEndTime", standardDateFormat);
        product.setEndTime(endTime);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_line_time, endTime);

    }

    private TiePointGrid createTiePointGrid(final Variable var) throws IOException {
        final int rank = var.getRank();
        final int gridWidth = var.getDimension(rank - 1).getLength();
        int gridHeight = var.getDimension(rank - 2).getLength();
        if (rank >= 3 && gridHeight <= 1)
            gridHeight = var.getDimension(rank - 3).getLength();
        return NetCDFUtils.createTiePointGrid(var, gridWidth, gridHeight,
                product.getSceneRasterWidth(), product.getSceneRasterHeight());
    }

    private static class Grids {
        TiePointGrid latGrid;
        TiePointGrid lonGrid;
    }

    private Grids createTiePointGridFromUTM(final int epsg, final Variable varX, final Variable varY) throws IOException {
        final int rank = varX.getRank();
        final int gridWidth = varX.getDimension(rank - 1).getLength();
        int gridHeight = varX.getDimension(rank - 2).getLength();
        if (rank >= 3 && gridHeight <= 1)
            gridHeight = varX.getDimension(rank - 3).getLength();
        final int sceneWidth = product.getSceneRasterWidth();
        final int sceneHeight = product.getSceneRasterHeight();
        final double subSamplingX = (double) sceneWidth / (double) (gridWidth - 1);
        final double subSamplingY = (double) sceneHeight / (double) (gridHeight - 1);

        TiePointGrid eastingTPG = NetCDFUtils.createTiePointGrid(varX, gridWidth, gridHeight, sceneWidth, sceneHeight);
        TiePointGrid northingTPG = NetCDFUtils.createTiePointGrid(varY, gridWidth, gridHeight, sceneWidth, sceneHeight);

        final int length = gridWidth * gridHeight;
        float[] easting = eastingTPG.getTiePoints();
        float[] northing = northingTPG.getTiePoints();
        final float[] latTiePoints = new float[length];
        final float[] lonTiePoints = new float[length];

        String epsgName = EPSGCodes.getInstance().getName(epsg);
        String zone = epsgName.substring(epsgName.lastIndexOf("_") + 1);
        String row = "N";
        if (zone.endsWith("S")) {
            row = "A";  // southern rows
        }
        zone = zone.substring(0, zone.length() - 1);

        UTM2LatLon conv = new UTM2LatLon();
        for (int i = 0; i < length; ++i) {
            final String utmStr = zone + " " + row + " " + easting[i] + " " + northing[i];
            final double latlon[] = conv.convertUTMToLatLong(utmStr);
            latTiePoints[i] = (float) latlon[0];
            lonTiePoints[i] = (float) latlon[1];
        }

        Grids grids = new Grids();
        grids.latGrid = new TiePointGrid("latitude", gridWidth, gridHeight, 0.5f, 0.5f,
                subSamplingX, subSamplingY, latTiePoints);
        grids.latGrid.setUnit(Unit.DEGREES);

        grids.lonGrid = new TiePointGrid("longitude", gridWidth, gridHeight, 0.5f, 0.5f,
                subSamplingX, subSamplingY, lonTiePoints, TiePointGrid.DISCONT_AT_180);
        grids.lonGrid.setUnit(Unit.DEGREES);

        return grids;
    }

    protected void addTiePointGridsToProduct() throws IOException {

        Group metadataGroup = netcdfFile.findGroup("/science/LSAR/" + productType + "/metadata");
        Group gridGroup = findGridGroup(metadataGroup);
        Variable incidenceAngleVar = gridGroup.findVariable("incidenceAngle");
        if (incidenceAngleVar != null) {
            TiePointGrid incidenceAngleGrid = createTiePointGrid(incidenceAngleVar);
            incidenceAngleGrid.setName(OperatorUtils.TPG_INCIDENT_ANGLE);
            product.addTiePointGrid(incidenceAngleGrid);
        }

        Variable coordYVar = findVariable(gridGroup, "coordinateY", "yCoordinates");
        Variable coordXVar = findVariable(gridGroup, "coordinateX", "xCoordinates");
        if (coordYVar != null && coordXVar != null) {
            String unit = coordYVar.findAttribute("units").toString();

            TiePointGrid latGrid, lonGrid;
            if (unit.contains("meter") || unit.contains("\"m\"")) {
                Variable epsgVar = gridGroup.findVariable("epsg");
                Grids grids = createTiePointGridFromUTM(epsgVar.readScalarInt(), coordXVar, coordYVar);
                latGrid = grids.latGrid;
                lonGrid = grids.lonGrid;
            } else {
                latGrid = createTiePointGrid(coordYVar);
                latGrid.setName(OperatorUtils.TPG_LATITUDE);

                lonGrid = createTiePointGrid(coordXVar);
                lonGrid.setName(OperatorUtils.TPG_LONGITUDE);
            }

            product.addTiePointGrid(latGrid);
            product.addTiePointGrid(lonGrid);

            final TiePointGeoCoding tpGeoCoding = new TiePointGeoCoding(latGrid, lonGrid);
            product.setSceneGeoCoding(tpGeoCoding);
        }
    }

    private static String getOrbitPass(String pass) {
        return pass.toUpperCase().contains("ASC") ? "ASCENDING" : "DESCENDING";
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

    private Variable findVariable(Group group, String... names) {
        for (String name : names) {
            Variable var = group.findVariable(name);
            if (var != null) {
                return var;
            }
        }
        return null;
    }

    private Group findGridGroup(final Group group) {
        Group gridGroup = group.findGroup("geolocationGrid");
        if (gridGroup == null) {
            gridGroup = group.findGroup("radarGrid");
        }
        return gridGroup;
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
//        addDopplerCentroidCoefficients();
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
                    ArrayStructure arrayStruct = (ArrayStructure) array;

                    StructureData[] row = (StructureData[]) array.get1DJavaArray(STRUCTURE);
                    final float[] tempArray = new float[row.length];
                    for (int i = 0; i < row.length; ++i) {
                        StructureMembers members = row[i].getStructureMembers();
                        List<StructureMembers.Member> members1 = row[i].getMembers();

                        tempArray[i] = row[i].convertScalarFloat(complexMemberName);
                    }
                    System.arraycopy(tempArray, 0, destBuffer.getElems(), y * destWidth, destWidth);

//                    StructureData[] row = (StructureData[]) array.get1DJavaArray(STRUCTURE);
//                    final float[] realPartBuffer = new float[row.length];
//                    final float[] imaginaryPartBuffer = new float[row.length];
//
//                    find(arrayStruct, "real");
//                    find(arrayStruct, "r");
//                    find(arrayStruct, "i");
//                    find(arrayStruct, "HH_r");
//                    find(arrayStruct, "HH_i");
//                    find(arrayStruct, "HH");
//
//                    ByteBuffer bb =  arrayStruct.getDataAsByteBuffer();
//                    for (int i = 0; i < row.length; ++i) {
//                        realPartBuffer[i] = bb.getFloat(i * 8);     // assuming 4 bytes real + 4 bytes imag
//                        imaginaryPartBuffer[i] = bb.getFloat(i * 8 + 4);
//                    }

//                    for (int i = 0; i < row.length; ++i) {
//                        StructureMembers structmembers = row[i].getStructureMembers();
//
//                        List<StructureMembers.Member> members = row[i].getMembers();
//                        for (StructureMembers.Member member : members) {
//                            System.out.println("Member name: " + member.getName());
//                        }
//
//                        read(row[i], "real");
//                        read(row[i], "r");
//                        read(row[i], "i");
//                        read(row[i], "HH_r");
//                        read(row[i], "HH_i");
//                        read(row[i], "HH");
//
//
//                        // Get the data for the real and imaginary parts
//                        double realValue = row[i].convertScalarDouble("r");
//                        double imaginaryValue = row[i].convertScalarDouble("i");
//
//                        realPartBuffer[i] = (float) realValue;
//                        imaginaryPartBuffer[i] = (float) imaginaryValue;
//
//                        // If your ProductData can handle complex numbers directly,
//                        // you might want to store them as such instead of separate real/imaginary arrays.
//                        // For example, if destBuffer is of type ComplexDouble[], you would do:
//                        // ((ComplexDouble[]) destBuffer.getElems())[y * destWidth + i] = new ComplexDouble(realValue, imaginaryValue);
//                    }

                    // Assuming your destBuffer is designed to hold interleaved real and imaginary values
                    // (e.g., [real1, imag1, real2, imag2, ...])
//                    for (int i = 0; i < row.length; i++) {
//                        destBuffer.setElemFloatAt(y * destWidth * 2 + i * 2, realPartBuffer[i]);
//                        destBuffer.setElemFloatAt(y * destWidth * 2 + i * 2 + 1, imaginaryPartBuffer[i]);
//                    }

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

    private void read(StructureData row, String name) {
        try {
            Array memberArray = row.getArray(name);
            System.out.println("success " + name);
        } catch (Exception e) {
            System.out.println("failed " + name + " " + e.getMessage());
        }
    }

    private void find(ArrayStructure row, String name) {
        try {
            StructureMembers.Member memberArray = row.findMember(name);
            if (memberArray != null)
                System.out.println("success " + name);
            else
                System.out.println("not found " + name);
        } catch (Exception e) {
            System.out.println("failed " + name + " " + e.getMessage());
        }
    }
}
