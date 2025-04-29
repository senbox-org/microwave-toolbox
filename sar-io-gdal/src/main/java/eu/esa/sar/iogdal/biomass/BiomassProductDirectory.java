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
package eu.esa.sar.iogdal.biomass;

import eu.esa.sar.commons.io.SARReader;
import eu.esa.sar.commons.io.XMLProductDirectory;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataAttribute;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.TiePointGeoCoding;
import org.esa.snap.core.datamodel.TiePointGrid;
import org.esa.snap.core.datamodel.VirtualBand;
import org.esa.snap.core.dataop.downloadable.XMLSupport;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.core.util.io.FileUtils;
import org.esa.snap.dataio.gdal.reader.plugins.GTiffDriverProductReaderPlugIn;
import org.esa.snap.dataio.geotiff.GeoTiffProductReaderPlugIn;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.datamodel.metadata.AbstractMetadataIO;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import org.jdom2.Document;
import org.jdom2.Element;
import ucar.ma2.Array;
import ucar.nc2.Group;
import ucar.nc2.NetcdfFile;
import ucar.nc2.Variable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.TreeMap;

import static org.esa.snap.engine_utilities.datamodel.AbstractMetadata.NO_METADATA_STRING;

/**
 * This class represents a product directory.
 */
public class BiomassProductDirectory extends XMLProductDirectory {

    //private static final GTiffDriverProductReaderPlugIn readerPlugin = new GTiffDriverProductReaderPlugIn();
    private static final GeoTiffProductReaderPlugIn readerPlugin = new GeoTiffProductReaderPlugIn();
    private final Map<String, ReaderData> bandProductMap = new TreeMap<>();

    private final Map<Band, TiePointGeoCoding> bandGeocodingMap = new TreeMap<>();
    private final transient Map<String, String> imgBandMetadataMap = new TreeMap<>();
    private String productName = "";
    private String productType = "";
    private File netCDFLUTFile;

    DateFormat biomassDateFormat = ProductData.UTC.createDateFormat("yyyy-MM-dd_HH:mm:ss");

    private final static Double NoDataValue = -9999.0;
    private final static String ABS = "ABS";
    private final static String PHASE = "PHASE";

    public static class ReaderData {
        ProductReader reader;
        Product bandProduct;
    }
    
    @Override
    public void close() throws IOException {
        super.close();
        for (ReaderData data : bandProductMap.values()) {
            if (data.bandProduct != null) {
                data.bandProduct.dispose();
            }
            data.reader.close();
        }
    }

    public BiomassProductDirectory(final File inputFile) {
        super(inputFile);
    }

    protected String getRelativePathToImageFolder() {
        return getRootFolder() + "measurement" + '/';
    }

    protected void addImageFile(final String imgPath, final MetadataElement newRoot) {
        final String name = getBandFileNameFromImage(imgPath);
        if ((name.endsWith("tiff"))) {
            try {
                final InputStream inStream = getInputStream(imgPath);
                if(inStream.available() > 0) {
                    ReaderData data = new ReaderData();
                    data.reader = readerPlugin.createReaderInstance();
                    data.bandProduct = data.reader.readProductNodes(productDir.getFile(imgPath), null);
                    bandProductMap.put(name.endsWith("phase.tiff") ? PHASE : ABS, data);

                } else {
                    inStream.close();
                }
            } catch (Exception e) {
                SystemUtils.LOG.severe(imgPath +" failed to open" + e.getMessage());
            }
        }
    }

    @Override
    protected void addBands(final Product product) {

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        int cnt = 0;
        for (String bandMetaName : imgBandMetadataMap.keySet()) {
            final MetadataElement bandMetadata = absRoot.getElement(bandMetaName);
            final String swath = bandMetadata.getAttributeString(AbstractMetadata.swath);
            final String pol = bandMetadata.getAttributeString(AbstractMetadata.polarization);
            final int width = bandMetadata.getAttributeInt(AbstractMetadata.num_samples_per_line);
            final int height = bandMetadata.getAttributeInt(AbstractMetadata.num_output_lines);

            String suffix = swath + '_' + pol;
            String bandName;

            if (isSLC()) {
                ReaderData absReaderData = bandProductMap.get(ABS);
                ReaderData phaseReaderData = bandProductMap.get(PHASE);
                Band absBand = absReaderData.bandProduct.getBandAt(cnt);
                Band phaseBand = phaseReaderData.bandProduct.getBandAt(cnt);

                bandName = "Amplitude" + '_' + suffix;
                final Band newAbsBand = new Band(bandName, absBand.getDataType(), width, height);
                newAbsBand.setUnit(Unit.AMPLITUDE);
                newAbsBand.setNoDataValueUsed(true);
                newAbsBand.setNoDataValue(NoDataValue);
                newAbsBand.setSourceImage(absBand.getSourceImage());

                product.addBand(newAbsBand);
                AbstractMetadata.addBandToBandMap(bandMetadata, bandName);

                bandName = "Phase" + '_' + suffix;
                final Band newPhaseBand = new Band(bandName, phaseBand.getDataType(), width, height);
                newPhaseBand.setUnit(Unit.PHASE);
                newPhaseBand.setNoDataValueUsed(true);
                newPhaseBand.setNoDataValue(NoDataValue);
                newPhaseBand.setSourceImage(phaseBand.getSourceImage());

                product.addBand(newPhaseBand);
                AbstractMetadata.addBandToBandMap(bandMetadata, bandName);

                createVirtualIBand(product, newAbsBand, newPhaseBand, '_' + suffix);
                createVirtualQBand(product, newAbsBand, newPhaseBand, '_' + suffix);
                SARReader.createVirtualIntensityBand(product, newAbsBand, '_' + suffix);

            } else {
                ReaderData absReaderData = bandProductMap.get(ABS);
                Band absBand = absReaderData.bandProduct.getBandAt(cnt);
                bandName = "Amplitude" + '_' + suffix;
                final Band newAbsBand = new Band(bandName, absBand.getDataType(), width, height);
                newAbsBand.setUnit(Unit.AMPLITUDE);
                newAbsBand.setNoDataValueUsed(true);
                newAbsBand.setNoDataValue(NoDataValue);
                newAbsBand.setSourceImage(absBand.getSourceImage());

                product.addBand(newAbsBand);
                AbstractMetadata.addBandToBandMap(bandMetadata, bandName);

                SARReader.createVirtualIntensityBand(product, newAbsBand, '_' + suffix);
            }
            ++cnt;
        }
    }

    public static Band createVirtualIBand(final Product product, final Band newAbsBand, final Band newPhaseBand, final String suffix) {
        final String absBandName = newAbsBand.getName();
        final String phaseBandName = newPhaseBand.getName();
        final double nodatavalue = newAbsBand.getNoDataValue();
        final String expression = absBandName +" == " + nodatavalue +" ? " + nodatavalue +" : " + absBandName + " * cos(" + phaseBandName +")";

        final VirtualBand virtBand = new VirtualBand("i" + suffix,
                ProductData.TYPE_FLOAT32,
                newAbsBand.getRasterWidth(),
                newAbsBand.getRasterHeight(),
                expression);
        virtBand.setUnit(Unit.REAL);
        virtBand.setDescription("Real from complex data");
        virtBand.setNoDataValueUsed(true);
        virtBand.setNoDataValue(nodatavalue);

        if (newAbsBand.getGeoCoding() != product.getSceneGeoCoding()) {
            virtBand.setGeoCoding(newAbsBand.getGeoCoding());
        }

        product.addBand(virtBand);
        return virtBand;
    }

    public static Band createVirtualQBand(final Product product, final Band newAbsBand, final Band newPhaseBand, final String suffix) {
        final String absBandName = newAbsBand.getName();
        final String phaseBandName = newPhaseBand.getName();
        final double nodatavalue = newAbsBand.getNoDataValue();
        final String expression = absBandName +" == " + nodatavalue +" ? " + nodatavalue +" : " + absBandName + " * sin(" + phaseBandName +")";

        final VirtualBand virtBand = new VirtualBand("q" + suffix,
                ProductData.TYPE_FLOAT32,
                newAbsBand.getRasterWidth(),
                newAbsBand.getRasterHeight(),
                expression);
        virtBand.setUnit(Unit.IMAGINARY);
        virtBand.setDescription("Imaginary from complex data");
        virtBand.setNoDataValueUsed(true);
        virtBand.setNoDataValue(nodatavalue);

        if (newAbsBand.getGeoCoding() != product.getSceneGeoCoding()) {
            virtBand.setGeoCoding(newAbsBand.getGeoCoding());
        }

        product.addBand(virtBand);
        return virtBand;
    }

    @Override
    protected void addAbstractedMetadataHeader(final MetadataElement root) throws IOException {

        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(root);
        final MetadataElement origProdRoot = AbstractMetadata.addOriginalProductMetadata(root);

        final MetadataElement EarthObservation = origProdRoot.getElement("EarthObservation");
        MetadataElement metaDataProperty = EarthObservation.getElement("metaDataProperty");
        MetadataElement EarthObservationMetaData = metaDataProperty.getElement("EarthObservationMetaData");

        productName = EarthObservationMetaData.getAttributeString("identifier");
        productType = EarthObservationMetaData.getAttributeString("productType");

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PRODUCT, productName);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PRODUCT_TYPE, productType);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.MISSION, "BIOMASS");

        MetadataElement procedure = EarthObservation.getElement("procedure");
        MetadataElement EarthObservationEquipment = procedure.getElement("EarthObservationEquipment");
        MetadataElement sensor = EarthObservationEquipment.getElement("sensor");
        MetadataElement Sensor = sensor.getElement("Sensor");
        MetadataElement operationalMode = Sensor.getElement("operationalMode");
        MetadataElement swathIdentifier = Sensor.getElement("swathIdentifier");

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ACQUISITION_MODE, operationalMode.getAttributeString("operationalMode"));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SWATH, swathIdentifier.getAttributeString("swathIdentifier"));

        setSLC(!productType.contains("DGM"));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SAMPLE_TYPE, isSLC() ? "COMPLEX" : "DETECTED");

        MetadataElement acquisitionParameters = EarthObservationEquipment.getElement("acquisitionParameters");
        MetadataElement Acquisition = acquisitionParameters.getElement("Acquisition");

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PASS, Acquisition.getAttributeString("orbitDirection"));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.antenna_pointing, Acquisition.getAttributeString("antennaLookDirection").toLowerCase());
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.REL_ORBIT, Acquisition.getAttributeInt("orbitNumber"));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SPH_DESCRIPTOR, Acquisition.getAttributeString("missionPhase"));

        String polarizations = Acquisition.getAttributeString("polarisationChannels");
        setPolarizations(absRoot, polarizations);

        // get metadata for each band
        addBandAbstractedMetadata(absRoot, origProdRoot);
    }

    private static void setPolarizations(MetadataElement absRoot, String polarizations) {
        StringTokenizer st = new StringTokenizer(polarizations, ",");
        int cnt = 1;
        while (st.hasMoreTokens()) {
            String pol = st.nextToken().trim();
            if (cnt == 1) {
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.mds1_tx_rx_polar, pol);
            } else if (cnt == 2) {
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.mds2_tx_rx_polar, pol);
            } else if (cnt == 3) {
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.mds3_tx_rx_polar, pol);
            } else if (cnt == 4) {
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.mds4_tx_rx_polar, pol);
            }
            ++cnt;
        }
    }

    private void addBandAbstractedMetadata(final MetadataElement absRoot,
                                           final MetadataElement origProdRoot) throws IOException {

        MetadataElement annotationElement = origProdRoot.getElement("annotation");
        if (annotationElement == null) {
            annotationElement = new MetadataElement("annotation");
            origProdRoot.addElement(annotationElement);
        }

        boolean commonMetadataRetrieved = false;

        double heightSum = 0.0;

        final String annotFolder = getRootFolder() + "annotation";
        final String[] filenames = listFiles(annotFolder);
        if (filenames != null) {
            for (String metadataFile : filenames) {
                if(!metadataFile.endsWith(".xml")) {
                    continue;
                }
                final Document xmlDoc;
                try (final InputStream is = getInputStream(annotFolder + '/' + metadataFile)) {
                    xmlDoc = XMLSupport.LoadXML(is);
                }
                final Element rootElement = xmlDoc.getRootElement();
                final MetadataElement nameElem = new MetadataElement(metadataFile);
                annotationElement.addElement(nameElem);
                AbstractMetadataIO.AddXMLMetadata(rootElement, nameElem);

                final MetadataElement mainAnnotation = nameElem.getElement("mainAnnotation");
                final MetadataElement acquisitionInformation = mainAnnotation.getElement("acquisitionInformation");
                final MetadataElement sarImage = mainAnnotation.getElement("sarImage");
                final MetadataElement processingParameters = mainAnnotation.getElement("processingParameters");
                final MetadataElement instrumentParameters = mainAnnotation.getElement("instrumentParameters");

                if (!commonMetadataRetrieved) {
                    // these should be the same for all swaths
                    // set to absRoot

                    // acquisitionInformation
                    productType = acquisitionInformation.getAttributeString("productType");
                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PRODUCT_TYPE, productType);

                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ABS_ORBIT,
                            acquisitionInformation.getAttributeInt("absoluteOrbitNumber"));
                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.REL_ORBIT,
                            acquisitionInformation.getAttributeInt("relativeOrbitNumber"));
                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.CYCLE,
                            acquisitionInformation.getAttributeInt("majorCycleId"));
                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.data_take_id,
                            acquisitionInformation.getAttributeInt("dataTakeId"));

                    // sarImage
                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.num_output_lines,
                            sarImage.getAttributeInt("numberOfLines"));
                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.num_samples_per_line,
                            sarImage.getAttributeInt("numberOfSamples"));

                    final MetadataElement firstSampleSlantRangeTime = sarImage.getElement("firstSampleSlantRangeTime");
                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.slant_range_to_first_pixel,
                            firstSampleSlantRangeTime.getAttributeDouble("firstSampleSlantRangeTime") * Constants.halfLightSpeed);

                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_line_time,
                            getTime(sarImage, "firstLineAzimuthTime", biomassDateFormat));
                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_line_time,
                            getTime(sarImage, "lastLineAzimuthTime", biomassDateFormat));

                    final MetadataElement rangePixelSpacing = sarImage.getElement("rangePixelSpacing");
                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_spacing,
                            rangePixelSpacing.getAttributeDouble("rangePixelSpacing"));
                    final MetadataElement azimuthPixelSpacing = sarImage.getElement("azimuthPixelSpacing");
                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_spacing,
                            azimuthPixelSpacing.getAttributeDouble("azimuthPixelSpacing"));

                    final MetadataElement azimuthTimeInterval = sarImage.getElement("azimuthTimeInterval");
                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.line_time_interval,
                            azimuthTimeInterval.getAttributeDouble("azimuthTimeInterval"));

                    // processingParameters
                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ProcessingSystemIdentifier,
                            processingParameters.getAttributeString("processorVersion"));
                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PROC_TIME,
                            getTime(processingParameters, "productGenerationTime", biomassDateFormat));
                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.VECTOR_SOURCE,
                            processingParameters.getAttributeString("orbitSource"));

                    final MetadataElement rangeProcessingParameters = processingParameters.getElement("rangeProcessingParameters");
                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_window_type,
                            rangeProcessingParameters.getAttributeString("windowType"));
                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_looks,
                            rangeProcessingParameters.getAttributeDouble("numberOfLooks"));
                    final MetadataElement processingBandwidth = rangeProcessingParameters.getElement("processingBandwidth");
                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_bandwidth,
                            processingBandwidth.getAttributeDouble("processingBandwidth") / Constants.oneMillion);

                    final MetadataElement azimuthProcessingParameters = processingParameters.getElement("azimuthProcessingParameters");
                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_looks,
                            azimuthProcessingParameters.getAttributeDouble("numberOfLooks"));
                    final MetadataElement azimuthProcessingBandwidth = azimuthProcessingParameters.getElement("processingBandwidth");
                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_bandwidth,
                            azimuthProcessingBandwidth.getAttributeDouble("processingBandwidth") / Constants.oneMillion);

                    // instrumentParameters
                    final MetadataElement radarCarrierFrequency = instrumentParameters.getElement("radarCarrierFrequency");
                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.radar_frequency,
                            radarCarrierFrequency.getAttributeDouble("radarCarrierFrequency") / Constants.oneMillion);

                    final MetadataElement prfList = instrumentParameters.getElement("prfList");
                    final MetadataElement prf = prfList.getElement("prf");
                    final MetadataElement value = prf.getElement("value");
                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.pulse_repetition_frequency,
                            value.getAttributeDouble("value"));

//
//                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_sampling_rate,
//                                                  productInformation.getAttributeDouble("rangeSamplingRate") / Constants.oneMillion);
//                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.line_time_interval,
//                                                  imageInformation.getAttributeDouble("azimuthTimeInterval"));


                    //addSRGRCoefficients(absRoot, prodElem.getElement("coordinateConversion"));

                    final MetadataElement dopplerParameters = mainAnnotation.getElement("dopplerParameters");
                    addDopplerCentroidCoefficients(absRoot, dopplerParameters);

                    commonMetadataRetrieved = true;
                }

                final MetadataElement polarisationList = acquisitionInformation.getElement("polarisationList");
                final String swath = acquisitionInformation.getAttributeString("swath");

                for(MetadataAttribute attrib : polarisationList.getAttributes()) {
                    if (!attrib.getName().equals("polarisation")) {
                        continue;
                    }

                    final String pol = attrib.getData().getElemString();
                    final ProductData.UTC startTime = getTime(acquisitionInformation, "startTime", biomassDateFormat);
                    final ProductData.UTC stopTime = getTime(acquisitionInformation, "stopTime", biomassDateFormat);

                    final String bandRootName = AbstractMetadata.BAND_PREFIX + swath + '_' + pol;
                    final MetadataElement bandAbsRoot = AbstractMetadata.addBandAbstractedMetadata(absRoot, bandRootName);
                    final String imgName = metadataFile.substring(0, metadataFile.lastIndexOf("_annot"));
                    imgBandMetadataMap.put(bandRootName, imgName);

                    AbstractMetadata.setAttribute(bandAbsRoot, AbstractMetadata.SWATH, swath);
                    AbstractMetadata.setAttribute(bandAbsRoot, AbstractMetadata.polarization, pol);
                    AbstractMetadata.setAttribute(bandAbsRoot, AbstractMetadata.annotation, metadataFile);
                    AbstractMetadata.setAttribute(bandAbsRoot, AbstractMetadata.first_line_time, startTime);
                    AbstractMetadata.setAttribute(bandAbsRoot, AbstractMetadata.last_line_time, stopTime);

                    AbstractMetadata.setAttribute(bandAbsRoot, AbstractMetadata.num_samples_per_line,
                            sarImage.getAttributeInt("numberOfSamples"));
                    AbstractMetadata.setAttribute(bandAbsRoot, AbstractMetadata.num_output_lines,
                            sarImage.getAttributeInt("numberOfLines"));

                    final MetadataElement radiometricCalibration = mainAnnotation.getElement("radiometricCalibration");
                    final MetadataElement absoluteCalibrationConstantList = radiometricCalibration.getElement("absoluteCalibrationConstantList");
                    for(MetadataElement absCalibConst : absoluteCalibrationConstantList.getElements()) {
                        final String polarisation = absCalibConst.getAttributeString("polarisation");
                        if(pol.equals(polarisation)) {
                            AbstractMetadata.setAttribute(bandAbsRoot, AbstractMetadata.calibration_factor,
                                    absCalibConst.getAttributeDouble("absoluteCalibrationConstant", AbstractMetadata.NO_METADATA));
                        }
                    }

                    //heightSum += getBandTerrainHeight(prodElem);

                    //addCalibrationAbstractedMetadata(origProdRoot);
                    //addNoiseAbstractedMetadata(origProdRoot);
                    //addRFIAbstractedMetadata(origProdRoot);
                }
            }

            // netcdf annotations
            for (String metadataFile : filenames) {
                if (!metadataFile.endsWith(".nc")) {
                    continue;
                }

                netCDFLUTFile = getFile(annotFolder + '/' + metadataFile);
            }
        }

        readOrbitStateVectors(absRoot);

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.avg_scene_height, heightSum / filenames.length);

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.bistatic_correction_applied, 1);
    }

    private List<Variable> readNetCDFLUT(final NetcdfFile netcdfFile) {
        final List<Variable> rasters = new ArrayList<>();
        if (netcdfFile != null) {

            List<Group> groups = netcdfFile.getRootGroup().getGroups();
            for(Group group : groups) {
                final List<Variable> variables = group.getVariables();
                for (Variable variable : variables) {
                    final int rank = variable.getRank();
                    if (rank >= 2) {
                        rasters.add(variable);
                    }
                }
            }
        }
        return rasters;
    }

    private void readOrbitStateVectors(final MetadataElement absRoot) throws IOException {
        final String navFolder = getRootFolder() + "annotation/navigation";
        final String[] filenames = listFiles(navFolder);
        if (filenames != null) {
            for (String metadataFile : filenames) {
                if (!metadataFile.endsWith("_orb.xml")) {
                    continue;
                }
                final Document xmlDoc;
                try (final InputStream is = getInputStream(navFolder + '/' + metadataFile)) {
                    xmlDoc = XMLSupport.LoadXML(is);
                }
                final Element rootElement = xmlDoc.getRootElement();
                final MetadataElement orbRoot = new MetadataElement(metadataFile);
                AbstractMetadataIO.AddXMLMetadata(rootElement, orbRoot);
                MetadataElement fileElem = orbRoot.getElement("Earth_Observation_File");
                MetadataElement dataBlock = fileElem.getElement("Data_Block");
                MetadataElement orbitList = dataBlock.getElement("List_of_OSVs");

                addOrbitStateVectors(absRoot, orbitList);
            }
        }
    }

    private void addOrbitStateVectors(final MetadataElement absRoot, final MetadataElement orbitList) {
        final MetadataElement orbitVectorListElem = absRoot.getElement(AbstractMetadata.orbit_state_vectors);

        final MetadataElement[] stateVectorElems = orbitList.getElements();
        for (int i = 1; i <= stateVectorElems.length; ++i) {
            addVector(AbstractMetadata.orbit_vector, orbitVectorListElem, stateVectorElems[i - 1], i);
        }

        // set state vector time
        if (absRoot.getAttributeUTC(AbstractMetadata.STATE_VECTOR_TIME, AbstractMetadata.NO_METADATA_UTC).
                equalElems(AbstractMetadata.NO_METADATA_UTC)) {

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.STATE_VECTOR_TIME,
                                          getTime(stateVectorElems[0], "time", biomassDateFormat));
        }
    }

    private void addVector(final String name, final MetadataElement orbitVectorListElem,
                                  final MetadataElement orbitElem, final int num) {
        final MetadataElement orbitVectorElem = new MetadataElement(name + num);

        MetadataElement xElem = orbitElem.getElement("X");
        MetadataElement yElem = orbitElem.getElement("Y");
        MetadataElement zElem = orbitElem.getElement("Z");
        MetadataElement vxElem = orbitElem.getElement("VX");
        MetadataElement vyElem = orbitElem.getElement("VY");
        MetadataElement vzElem = orbitElem.getElement("VZ");

        orbitVectorElem.setAttributeUTC(AbstractMetadata.orbit_vector_time,
                                        getTime(orbitElem, "UTC", biomassDateFormat));

        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_x_pos,
                xElem.getAttributeDouble("X", 0));
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_y_pos,
                yElem.getAttributeDouble("Y", 0));
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_z_pos,
                zElem.getAttributeDouble("Z", 0));
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_x_vel,
                vxElem.getAttributeDouble("VX", 0));
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_y_vel,
                vyElem.getAttributeDouble("VY", 0));
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_z_vel,
                vzElem.getAttributeDouble("VZ", 0));

        orbitVectorListElem.addElement(orbitVectorElem);
    }

    private void addSRGRCoefficients(final MetadataElement absRoot, final MetadataElement coordinateConversion) {
        if (coordinateConversion == null) return;
        final MetadataElement coordinateConversionList = coordinateConversion.getElement("coordinateConversionList");
        if (coordinateConversionList == null) return;

        final MetadataElement srgrCoefficientsElem = absRoot.getElement(AbstractMetadata.srgr_coefficients);

        int listCnt = 1;
        for (MetadataElement elem : coordinateConversionList.getElements()) {
            final MetadataElement srgrListElem = new MetadataElement(AbstractMetadata.srgr_coef_list + '.' + listCnt);
            srgrCoefficientsElem.addElement(srgrListElem);
            ++listCnt;

            final ProductData.UTC utcTime = ReaderUtils.getTime(elem, "azimuthTime", biomassDateFormat);
            srgrListElem.setAttributeUTC(AbstractMetadata.srgr_coef_time, utcTime);

            final double grOrigin = elem.getAttributeDouble("gr0", 0);
            AbstractMetadata.addAbstractedAttribute(srgrListElem, AbstractMetadata.ground_range_origin,
                                                    ProductData.TYPE_FLOAT64, "m", "Ground Range Origin");
            AbstractMetadata.setAttribute(srgrListElem, AbstractMetadata.ground_range_origin, grOrigin);

            final String coeffStr = elem.getElement("grsrCoefficients").getAttributeString("grsrCoefficients", "");
            if (!coeffStr.isEmpty()) {
                final StringTokenizer st = new StringTokenizer(coeffStr);
                int cnt = 1;
                while (st.hasMoreTokens()) {
                    final double coefValue = Double.parseDouble(st.nextToken());

                    final MetadataElement coefElem = new MetadataElement(AbstractMetadata.coefficient + '.' + cnt);
                    srgrListElem.addElement(coefElem);
                    ++cnt;
                    AbstractMetadata.addAbstractedAttribute(coefElem, AbstractMetadata.srgr_coef,
                                                            ProductData.TYPE_FLOAT64, "", "SRGR Coefficient");
                    AbstractMetadata.setAttribute(coefElem, AbstractMetadata.srgr_coef, coefValue);
                }
            }
        }
    }

    private void addDopplerCentroidCoefficients(final MetadataElement absRoot, final MetadataElement dopplerCentroid) {
        if (dopplerCentroid == null) return;
        final MetadataElement dcEstimateList = dopplerCentroid.getElement("dcEstimateList");
        if (dcEstimateList == null) return;

        final MetadataElement dopplerCentroidCoefficientsElem = absRoot.getElement(AbstractMetadata.dop_coefficients);

        int listCnt = 1;
        for (MetadataElement elem : dcEstimateList.getElements()) {
            final MetadataElement dopplerListElem = new MetadataElement(AbstractMetadata.dop_coef_list + '.' + listCnt);
            dopplerCentroidCoefficientsElem.addElement(dopplerListElem);
            ++listCnt;

            final ProductData.UTC utcTime = ReaderUtils.getTime(elem, "azimuthTime", biomassDateFormat);
            dopplerListElem.setAttributeUTC(AbstractMetadata.dop_coef_time, utcTime);

            final double refTime = elem.getAttributeDouble("t0", 0) * 1e9; // s to ns
            AbstractMetadata.addAbstractedAttribute(dopplerListElem, AbstractMetadata.slant_range_time,
                                                    ProductData.TYPE_FLOAT64, "ns", "Slant Range Time");
            AbstractMetadata.setAttribute(dopplerListElem, AbstractMetadata.slant_range_time, refTime);

            final String coeffStr = elem.getElement("geometryDcPolynomial").getAttributeString("geometryDcPolynomial", "");
            if (!coeffStr.isEmpty()) {
                final StringTokenizer st = new StringTokenizer(coeffStr);
                int cnt = 1;
                while (st.hasMoreTokens()) {
                    final double coefValue = Double.parseDouble(st.nextToken());

                    final MetadataElement coefElem = new MetadataElement(AbstractMetadata.coefficient + '.' + cnt);
                    dopplerListElem.addElement(coefElem);
                    ++cnt;
                    AbstractMetadata.addAbstractedAttribute(coefElem, AbstractMetadata.dop_coef,
                                                            ProductData.TYPE_FLOAT64, "", "Doppler Centroid Coefficient");
                    AbstractMetadata.setAttribute(coefElem, AbstractMetadata.dop_coef, coefValue);
                }
            }
        }
    }

    @Override
    protected void addGeoCoding(final Product product) {
        ProductUtils.copyGeoCoding(bandProductMap.values().iterator().next().bandProduct, product);
    }

    @Override
    protected void addTiePointGrids(final Product product) {
        try (final NetcdfFile netcdfFile = NetcdfFile.open(netCDFLUTFile.getAbsolutePath())) {
            final List<Variable> rasters = readNetCDFLUT(netcdfFile);
            for(Variable variable : rasters) {
                String name = variable.getShortName();
                int gridWidth = variable.getDimension(1).getLength();
                int gridHeight = variable.getDimension(0).getLength();

                final double subSamplingX = (double) product.getSceneRasterWidth() / (gridWidth - 1);
                final double subSamplingY = (double) product.getSceneRasterHeight() / (gridHeight - 1);

                Array dataArray = variable.read();
                Object storage = dataArray.copyTo1DJavaArray();

                float[] floatData;
                if (storage instanceof float[]) {
                    floatData = (float[]) storage;

                } else if (storage instanceof double[]) {
                    double[] doubleData = (double[]) storage;
                    floatData = new float[doubleData.length];
                    for (int i = 0; i < doubleData.length; ++i) {
                        floatData[i] = (float) doubleData[i];
                    }

                } else {
                    throw new Exception("  Warning: Could not cast data from variable '"
                            + variable.getFullName() + "' to float[]. Actual array type: "
                            + storage.getClass().getName());
                }

                if(name.equals(OperatorUtils.TPG_LATITUDE)) {

                    TiePointGrid latGrid = product.getTiePointGrid(OperatorUtils.TPG_LATITUDE);
                    if (latGrid == null) {
                        latGrid = new TiePointGrid(OperatorUtils.TPG_LATITUDE,
                                gridWidth, gridHeight, 0.5f, 0.5f, subSamplingX, subSamplingY, floatData);
                        latGrid.setUnit(Unit.DEGREES);
                        product.addTiePointGrid(latGrid);
                    }
                } else if(name.equals(OperatorUtils.TPG_LONGITUDE)) {

                    TiePointGrid lonGrid = product.getTiePointGrid(OperatorUtils.TPG_LONGITUDE);
                    if (lonGrid == null) {
                        lonGrid = new TiePointGrid(OperatorUtils.TPG_LONGITUDE,
                                gridWidth, gridHeight, 0.5f, 0.5f, subSamplingX, subSamplingY, floatData, TiePointGrid.DISCONT_AT_180);
                        lonGrid.setUnit(Unit.DEGREES);
                        product.addTiePointGrid(lonGrid);
                    }
                } else {
                    if(name.toLowerCase().equals("incidenceangle")) {
                        name = OperatorUtils.TPG_INCIDENT_ANGLE;
                    } else if(name.toLowerCase().equals("elevationangle")) {
                        name = OperatorUtils.TPG_ELEVATION_ANGLE;
                    } else if(name.toLowerCase().equals("terrainslope")) {
                        name = "terrain_slope";
                    }
                    final TiePointGrid incidentAngleGrid = new TiePointGrid(name,
                            gridWidth, gridHeight, 0.5f, 0.5f, subSamplingX, subSamplingY, floatData);
                    incidentAngleGrid.setUnit(Unit.DEGREES);
                    product.addTiePointGrid(incidentAngleGrid);
                }
            }
        } catch (Exception e) {
            System.out.println("Error reading netCDFLUT file: " + e.getMessage());
        }
    }

    private void addTiePointGrids(final Product product, final Band band, final String imgXMLName, final String tpgPrefix) {

        //System.out.println("S1L1Dir.addTiePointGrids: band = " + band.getName() + " imgXMLName = " + imgXMLName + " tpgPrefix = " + tpgPrefix);

        String pre = "";
        if (!tpgPrefix.isEmpty())
            pre = tpgPrefix + '_';

        final TiePointGrid existingLatTPG = product.getTiePointGrid(pre + OperatorUtils.TPG_LATITUDE);
        final TiePointGrid existingLonTPG = product.getTiePointGrid(pre + OperatorUtils.TPG_LONGITUDE);
        if (existingLatTPG != null && existingLonTPG != null) {
            if(band != null) {
                // reuse geocoding
                final TiePointGeoCoding tpGeoCoding = new TiePointGeoCoding(existingLatTPG, existingLonTPG);
                band.setGeoCoding(tpGeoCoding);
            }
            return;
        }
        //System.out.println("add new TPG for band = " + band.getName());
        final String annotation = FileUtils.exchangeExtension(imgXMLName, ".xml");
        final MetadataElement origProdRoot = AbstractMetadata.getOriginalProductMetadata(product);
        final MetadataElement annotationElem = origProdRoot.getElement("annotation");
        final MetadataElement imgElem = annotationElem.getElement(annotation);
        final MetadataElement productElem = imgElem.getElement("product");
        final MetadataElement geolocationGrid = productElem.getElement("geolocationGrid");
        final MetadataElement geolocationGridPointList = geolocationGrid.getElement("geolocationGridPointList");

        final MetadataElement[] geoGrid = geolocationGridPointList.getElements();

        //System.out.println("geoGrid.length = " + geoGrid.length);

        final double[] latList = new double[geoGrid.length];
        final double[] lonList = new double[geoGrid.length];
        final double[] incidenceAngleList = new double[geoGrid.length];
        final double[] elevAngleList = new double[geoGrid.length];
        final double[] rangeTimeList = new double[geoGrid.length];
        final int[] x = new int[geoGrid.length];
        final int[] y = new int[geoGrid.length];

        // Loop through the list of geolocation grid points, assuming that it represents a row-major rectangular grid.
        int gridWidth = 0, gridHeight = 0;
        int i = 0;
        for (MetadataElement ggPoint : geoGrid) {
            latList[i] = ggPoint.getAttributeDouble("latitude", 0);
            lonList[i] = ggPoint.getAttributeDouble("longitude", 0);
            incidenceAngleList[i] = ggPoint.getAttributeDouble("incidenceAngle", 0);
            elevAngleList[i] = ggPoint.getAttributeDouble("elevationAngle", 0);
            rangeTimeList[i] = ggPoint.getAttributeDouble("slantRangeTime", 0) * Constants.oneBillion; // s to ns

            x[i] = (int) ggPoint.getAttributeDouble("pixel", 0);
            y[i] = (int) ggPoint.getAttributeDouble("line", 0);
            if (x[i] == 0) {
                // This means we are at the start of a new line
                if (gridWidth == 0) // Here we are implicitly assuming that the pixel horizontal spacing is assumed to be the same from line to line.
                    gridWidth = i;
                ++gridHeight;
            }
            ++i;
        }

        if (crossAntimeridian(lonList)) {
            for (int j = 0; j < lonList.length; ++j) {
                if (lonList[j] < 0.0) lonList[j] += 360.0;
            }
        }

        //System.out.println("geoGrid w = " + gridWidth + "; h = " + gridHeight);

        final int newGridWidth = gridWidth;
        final int newGridHeight = gridHeight;
        final float[] newLatList = new float[newGridWidth * newGridHeight];
        final float[] newLonList = new float[newGridWidth * newGridHeight];
        final float[] newIncList = new float[newGridWidth * newGridHeight];
        final float[] newElevList = new float[newGridWidth * newGridHeight];
        final float[] newslrtList = new float[newGridWidth * newGridHeight];
        int sceneRasterWidth = product.getSceneRasterWidth();
        int sceneRasterHeight = product.getSceneRasterHeight();
        if(band != null) {
            sceneRasterWidth = band.getRasterWidth();
            sceneRasterHeight = band.getRasterHeight();
        }

        final double subSamplingX = (double) sceneRasterWidth / (newGridWidth - 1);
        final double subSamplingY = (double) sceneRasterHeight / (newGridHeight - 1);


        TiePointGrid latGrid = product.getTiePointGrid(pre + OperatorUtils.TPG_LATITUDE);
        if (latGrid == null) {
            latGrid = new TiePointGrid(pre + OperatorUtils.TPG_LATITUDE,
                                       newGridWidth, newGridHeight, 0.5f, 0.5f, subSamplingX, subSamplingY, newLatList);
            latGrid.setUnit(Unit.DEGREES);
            product.addTiePointGrid(latGrid);
        }

        TiePointGrid lonGrid = product.getTiePointGrid(pre + OperatorUtils.TPG_LONGITUDE);
        if (lonGrid == null) {
            lonGrid = new TiePointGrid(pre + OperatorUtils.TPG_LONGITUDE,
                                       newGridWidth, newGridHeight, 0.5f, 0.5f, subSamplingX, subSamplingY, newLonList, TiePointGrid.DISCONT_AT_180);
            lonGrid.setUnit(Unit.DEGREES);
            product.addTiePointGrid(lonGrid);
        }

        if (product.getTiePointGrid(pre + OperatorUtils.TPG_INCIDENT_ANGLE) == null) {
            final TiePointGrid incidentAngleGrid = new TiePointGrid(pre + OperatorUtils.TPG_INCIDENT_ANGLE,
                                                                    newGridWidth, newGridHeight, 0.5f, 0.5f, subSamplingX, subSamplingY, newIncList);
            incidentAngleGrid.setUnit(Unit.DEGREES);
            product.addTiePointGrid(incidentAngleGrid);
        }

        if (product.getTiePointGrid(pre + OperatorUtils.TPG_ELEVATION_ANGLE) == null) {
            final TiePointGrid elevAngleGrid = new TiePointGrid(pre + OperatorUtils.TPG_ELEVATION_ANGLE,
                                                                newGridWidth, newGridHeight, 0.5f, 0.5f, subSamplingX, subSamplingY, newElevList);
            elevAngleGrid.setUnit(Unit.DEGREES);
            product.addTiePointGrid(elevAngleGrid);
        }

        if (product.getTiePointGrid(pre + OperatorUtils.TPG_SLANT_RANGE_TIME) == null) {
            final TiePointGrid slantRangeGrid = new TiePointGrid(pre + OperatorUtils.TPG_SLANT_RANGE_TIME,
                                                                 newGridWidth, newGridHeight, 0.5f, 0.5f, subSamplingX, subSamplingY, newslrtList);
            slantRangeGrid.setUnit(Unit.NANOSECONDS);
            product.addTiePointGrid(slantRangeGrid);
        }

        final TiePointGeoCoding tpGeoCoding = new TiePointGeoCoding(latGrid, lonGrid);

        if(band != null) {
            bandGeocodingMap.put(band, tpGeoCoding);
        }
    }

    private boolean crossAntimeridian(final double[] lonList) {
        for (int i = 1; i < lonList.length; ++i) {
            if (Math.abs(lonList[i] - lonList[i - 1]) > 350.0 ) {
                return true;
            }
        }
        return false;
    }

    private static void setLatLongMetadata(Product product, TiePointGrid latGrid, TiePointGrid lonGrid) {
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);

        final int w = product.getSceneRasterWidth();
        final int h = product.getSceneRasterHeight();

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_near_lat, latGrid.getPixelDouble(0, 0));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_near_long, lonGrid.getPixelDouble(0, 0));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_far_lat, latGrid.getPixelDouble(w, 0));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_far_long, lonGrid.getPixelDouble(w, 0));

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_near_lat, latGrid.getPixelDouble(0, h));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_near_long, lonGrid.getPixelDouble(0, h));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_far_lat, latGrid.getPixelDouble(w, h));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_far_long, lonGrid.getPixelDouble(w, h));
    }

    @Override
    protected String getProductName() {
        return productName;
    }

    protected String getProductType() {
        return productType;
    }

    public static ProductData.UTC getTime(final MetadataElement elem, final String tag, final DateFormat sentinelDateFormat) {

        String start = elem.getAttributeString(tag, NO_METADATA_STRING);
        start = start.replace("UTC=", "").replace("T", "_");

        return AbstractMetadata.parseUTC(start, sentinelDateFormat);
    }

    @Override
    public Product createProduct() throws IOException {

        final MetadataElement newRoot = addMetaData();
        findImages(newRoot);

        final MetadataElement absRoot = newRoot.getElement(AbstractMetadata.ABSTRACT_METADATA_ROOT);

        final int sceneWidth = absRoot.getAttributeInt(AbstractMetadata.num_samples_per_line);
        final int sceneHeight = absRoot.getAttributeInt(AbstractMetadata.num_output_lines);

        final Product product = new Product(getProductName(), getProductType(), sceneWidth, sceneHeight);
        updateProduct(product, newRoot);

        addBands(product);
        addGeoCoding(product);
        addTiePointGrids(product);
        setLatLongMetadata(product);

        ReaderUtils.addMetadataIncidenceAngles(product);
        ReaderUtils.addMetadataProductSize(product);

        return product;
    }
}
