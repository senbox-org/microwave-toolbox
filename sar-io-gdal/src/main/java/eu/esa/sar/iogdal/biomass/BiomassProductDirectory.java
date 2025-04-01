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
import org.esa.snap.core.datamodel.GeoCoding;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.TiePointGeoCoding;
import org.esa.snap.core.datamodel.TiePointGrid;
import org.esa.snap.core.dataop.downloadable.XMLSupport;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.core.util.io.FileUtils;
import org.esa.snap.dataio.gdal.reader.plugins.GTiffDriverProductReaderPlugIn;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.datamodel.metadata.AbstractMetadataIO;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import org.jdom2.Document;
import org.jdom2.Element;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;

import static org.esa.snap.engine_utilities.datamodel.AbstractMetadata.NO_METADATA_STRING;

/**
 * This class represents a product directory.
 */
public class BiomassProductDirectory extends XMLProductDirectory {

    private static final GTiffDriverProductReaderPlugIn readerPlugin = new GTiffDriverProductReaderPlugIn();
    private final Map<String, ReaderData> bandProductMap = new HashMap<>();
    private final Map<String, ReaderData> bandNameReaderDataMap = new HashMap<>();

    private final Map<Band, TiePointGeoCoding> bandGeocodingMap = new HashMap<>(5);
    private final transient Map<String, String> imgBandMetadataMap = new HashMap<>(4);
    private String productName = "";
    private String acqMode = "";
    private String productType = "";

    DateFormat sentinelDateFormat = ProductData.UTC.createDateFormat("yyyy-MM-dd HH:mm:ss");

    private final static Double NoDataValue = 0.0;//-9999.0;

    public static class ReaderData {
        ProductReader reader;
        Product bandProduct;
        Dimension bandDimensions;
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
                    data.bandDimensions = getBandDimensions(newRoot, getBandMetadataKey(name));
                    bandProductMap.put(name, data);

                } else {
                    inStream.close();
                }
            } catch (Exception e) {
                SystemUtils.LOG.severe(imgPath +" failed to open" + e.getMessage());
            }
        }
    }

    private String getBandMetadataKey(final String imgName) {
        for(String key : imgBandMetadataMap.keySet()) {
            if(imgName.startsWith(key)) {
                return imgBandMetadataMap.get(key);
            }
        }
        throw new IllegalArgumentException("No metadata found for image: " + imgName);
    }

    @Override
    protected void addBands(final Product product) {

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        int cnt = 1;
        for (String imgName : bandProductMap.keySet()) {
            final ReaderData img = bandProductMap.get(imgName);
            final MetadataElement bandMetadata = absRoot.getElement(getBandMetadataKey(imgName));
            final String swath = bandMetadata.getAttributeString(AbstractMetadata.swath);
            final String pol = bandMetadata.getAttributeString(AbstractMetadata.polarization);
            final int width = bandMetadata.getAttributeInt(AbstractMetadata.num_samples_per_line);
            final int height = bandMetadata.getAttributeInt(AbstractMetadata.num_output_lines);
            int numImages = 1;

            String tpgPrefix = "";
            String suffix = pol;
            if (isSLC()) {
                numImages *= 2; // real + imaginary
//                if(isTOPSAR()) {
//                    suffix = swath + '_' + pol;
//                    tpgPrefix = swath;
//                } else if(acqMode.equals("WV")) {
//                    suffix = suffix + '_' + cnt;
//                    ++cnt;
//                }
            }

            String bandName;
            boolean real = true;
            Band lastRealBand = null;
            for (int i = 0; i < numImages; ++i) {

                if (isSLC()) {
                    String unit;

                    for(Band band : img.bandProduct.getBands()) {
                        if (real) {
                            bandName = "i" + '_' + suffix;
                            unit = Unit.REAL;
                        } else {
                            bandName = "q" + '_' + suffix;
                            unit = Unit.IMAGINARY;
                        }

                        final Band newBand = new Band(bandName, ProductData.TYPE_INT16, width, height);
                        newBand.setUnit(unit);
                        newBand.setNoDataValueUsed(true);
                        newBand.setNoDataValue(NoDataValue);
                        //newBand.setSourceImage(band.getSourceImage());

                        bandNameReaderDataMap.put(bandName, img);

                        product.addBand(newBand);
                        AbstractMetadata.addBandToBandMap(bandMetadata, bandName);

                        if (real) {
                            lastRealBand = newBand;
                        } else {
                            ReaderUtils.createVirtualIntensityBand(product, lastRealBand, newBand, '_' + suffix);
                        }
                        real = !real;

                        // add tiepointgrids and geocoding for band
                        addTiePointGrids(product, newBand, imgName, tpgPrefix);

                        // reset to null so it doesn't adopt a geocoding from the bands
                        product.setSceneGeoCoding(null);
                    }
                } else {
                    for(Band band : img.bandProduct.getBands()) {
                        bandName = "Amplitude" + '_' + suffix;
                        final Band newBand = new Band(bandName, band.getDataType(), width, height);
                        newBand.setUnit(Unit.AMPLITUDE);
                        newBand.setNoDataValueUsed(true);
                        newBand.setNoDataValue(NoDataValue);
                        newBand.setSourceImage(band.getSourceImage());

                        product.addBand(newBand);
                        AbstractMetadata.addBandToBandMap(bandMetadata, bandName);

                        SARReader.createVirtualIntensityBand(product, newBand, '_' + suffix);

                        // add tiepointgrids and geocoding for band
                        //addTiePointGrids(product, newBand, imgName, tpgPrefix);
                    }
                }
            }
        }
    }

    @Override
    protected void addAbstractedMetadataHeader(final MetadataElement root) throws IOException {

        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(root);
        final MetadataElement origProdRoot = AbstractMetadata.addOriginalProductMetadata(root);

        final String defStr = AbstractMetadata.NO_METADATA_STRING;
        final int defInt = AbstractMetadata.NO_METADATA;

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
        MetadataElement acquisitionParameters = EarthObservationEquipment.getElement("acquisitionParameters");
        MetadataElement Acquisition = acquisitionParameters.getElement("Acquisition");

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PASS, Acquisition.getAttributeString("orbitDirection"));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.antenna_pointing, Acquisition.getAttributeString("antennaLookDirection"));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.REL_ORBIT, Acquisition.getAttributeInt("orbitNumber"));

//        final MetadataElement imageAttributes = features.getElement("imageAttributes");
//        final MetadataElement bands = imageAttributes.getElement("bands");
//        final MetadataElement[] bandElems = bands.getElements();
//        final MetadataElement bandElem = bandElems[0];
//
//        width = bandElem.getAttributeInt("nCols", defInt);
//        height = bandElem.getAttributeInt("nRows", defInt);
//        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.num_samples_per_line, width);
//        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.num_output_lines, height);
//
//        final MetadataElement acquisition = features.getElement("acquisition");
//        final MetadataElement parameters = acquisition.getElement("parameters");
//        mode = getAcquisitionMode(parameters.getAttributeString("acqMode"));
//        polMode = parameters.getAttributeString("polMode");
//        beamId = parameters.getAttributeString("beamID").replace("QP","").replace("DP","").replace("SD","");
//        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.BEAMS, beamId);
//        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ACQUISITION_MODE, mode);
//        final String antennaPointing = parameters.getAttributeString("sideLooking").toLowerCase();
//        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.antenna_pointing, antennaPointing);
//
//        String desc = features.getAttributeString("abstract");
//        productDescription = desc.replace("XML Annotated ", "");
//        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SPH_DESCRIPTOR, productDescription);
//
//        final MetadataElement production = features.getElement("production");
//        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ProcessingSystemIdentifier, production.getAttributeString("facilityID"));
//        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SAMPLE_TYPE, getSampleType());
//
//        final MetadataElement StateVectorData = features.getElement("StateVectorData");
//        final String pass = StateVectorData.getAttributeString("OrbitDirection");
//        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PASS, pass);final String defStr = AbstractMetadata.NO_METADATA_STRING;
//        final int defInt = AbstractMetadata.NO_METADATA;
//
//        final MetadataElement product = origProdRoot.getElement("product");
//        final MetadataElement features = product.getElement("features");
//
//        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PRODUCT, productName);
//        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PRODUCT_TYPE, productType);
//        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.MISSION, getMission());
//        absRoot.getAttribute(AbstractMetadata.abs_calibration_flag).getData().setElemBoolean(true);
//
//        final MetadataElement imageAttributes = features.getElement("imageAttributes");
//        final MetadataElement bands = imageAttributes.getElement("bands");
//        final MetadataElement[] bandElems = bands.getElements();
//        final MetadataElement bandElem = bandElems[0];
//
//        width = bandElem.getAttributeInt("nCols", defInt);
//        height = bandElem.getAttributeInt("nRows", defInt);
//        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.num_samples_per_line, width);
//        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.num_output_lines, height);
//
//        final MetadataElement acquisition = features.getElement("acquisition");
//        final MetadataElement parameters = acquisition.getElement("parameters");
//        mode = getAcquisitionMode(parameters.getAttributeString("acqMode"));
//        polMode = parameters.getAttributeString("polMode");
//        beamId = parameters.getAttributeString("beamID").replace("QP","").replace("DP","").replace("SD","");
//        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.BEAMS, beamId);
//        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ACQUISITION_MODE, mode);
//        final String antennaPointing = parameters.getAttributeString("sideLooking").toLowerCase();
//        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.antenna_pointing, antennaPointing);
//
//        String desc = features.getAttributeString("abstract");
//        productDescription = desc.replace("XML Annotated ", "");
//        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SPH_DESCRIPTOR, productDescription);
//
//        final MetadataElement production = features.getElement("production");
//        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ProcessingSystemIdentifier, production.getAttributeString("facilityID"));
//        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SAMPLE_TYPE, getSampleType());
//
//        final MetadataElement StateVectorData = features.getElement("StateVectorData");
//        final String pass = StateVectorData.getAttributeString("OrbitDirection");
//        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PASS, pass);
//

        // get metadata for each band
        addBandAbstractedMetadata(absRoot, origProdRoot);
        //addCalibrationAbstractedMetadata(origProdRoot);
        //addNoiseAbstractedMetadata(origProdRoot);
        //addRFIAbstractedMetadata(origProdRoot);
    }

    private void addBandAbstractedMetadata(final MetadataElement absRoot,
                                           final MetadataElement origProdRoot) throws IOException {

        MetadataElement annotationElement = origProdRoot.getElement("annotation");
        if (annotationElement == null) {
            annotationElement = new MetadataElement("annotation");
            origProdRoot.addElement(annotationElement);
        }

        // collect range and azimuth spacing
        double rangeSpacingTotal = 0;
        double azimuthSpacingTotal = 0;
        boolean commonMetadataRetrieved = false;

        double heightSum = 0.0;

        int numBands = 0;
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

                final String swath = acquisitionInformation.getAttributeString("swath");
                //final String pol = adsHeader.getAttributeString("polarisation");

                final ProductData.UTC startTime = getTime(acquisitionInformation, "startTime", sentinelDateFormat);
                final ProductData.UTC stopTime = getTime(acquisitionInformation, "stopTime", sentinelDateFormat);

                final String bandRootName = AbstractMetadata.BAND_PREFIX + swath;// + '_' + pol;
                final MetadataElement bandAbsRoot = AbstractMetadata.addBandAbstractedMetadata(absRoot, bandRootName);
                final String imgName = metadataFile.substring(0, metadataFile.lastIndexOf("_annot"));
                imgBandMetadataMap.put(imgName, bandRootName);

                AbstractMetadata.setAttribute(bandAbsRoot, AbstractMetadata.SWATH, swath);
                //AbstractMetadata.setAttribute(bandAbsRoot, AbstractMetadata.polarization, pol);
                AbstractMetadata.setAttribute(bandAbsRoot, AbstractMetadata.annotation, metadataFile);
                AbstractMetadata.setAttribute(bandAbsRoot, AbstractMetadata.first_line_time, startTime);
                AbstractMetadata.setAttribute(bandAbsRoot, AbstractMetadata.last_line_time, stopTime);

//                if (AbstractMetadata.isNoData(absRoot, AbstractMetadata.mds1_tx_rx_polar)) {
//                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.mds1_tx_rx_polar, pol);
//                } else if(!absRoot.getAttributeString(AbstractMetadata.mds1_tx_rx_polar, NO_METADATA_STRING).equals(pol)){
//                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.mds2_tx_rx_polar, pol);
//                }

                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.data_take_id,
                                              Integer.parseInt(acquisitionInformation.getAttributeString("dataTakeId")));

                //rangeSpacingTotal += imageInformation.getAttributeDouble("rangePixelSpacing");
                //azimuthSpacingTotal += imageInformation.getAttributeDouble("azimuthPixelSpacing");

                //AbstractMetadata.setAttribute(bandAbsRoot, AbstractMetadata.line_time_interval,
                //                              imageInformation.getAttributeDouble("azimuthTimeInterval"));
                AbstractMetadata.setAttribute(bandAbsRoot, AbstractMetadata.num_samples_per_line,
                        sarImage.getAttributeInt("numberOfSamples"));
                AbstractMetadata.setAttribute(bandAbsRoot, AbstractMetadata.num_output_lines,
                        sarImage.getAttributeInt("numberOfLines"));
                //AbstractMetadata.setAttribute(bandAbsRoot, AbstractMetadata.sample_type,
                //                              imageInformation.getAttributeString("pixelValue").toUpperCase());

                //heightSum += getBandTerrainHeight(prodElem);

                if (!commonMetadataRetrieved) {
                    // these should be the same for all swaths
                    // set to absRoot

//                    final MetadataElement generalAnnotation = prodElem.getElement("generalAnnotation");
//                    final MetadataElement productInformation = generalAnnotation.getElement("productInformation");
//                    final MetadataElement processingInformation = imageAnnotation.getElement("processingInformation");
//                    final MetadataElement swathProcParamsList = processingInformation.getElement("swathProcParamsList");
//                    final MetadataElement swathProcParams = swathProcParamsList.getElement("swathProcParams");
//                    final MetadataElement rangeProcessing = swathProcParams.getElement("rangeProcessing");
//                    final MetadataElement azimuthProcessing = swathProcParams.getElement("azimuthProcessing");
//
//                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_sampling_rate,
//                                                  productInformation.getAttributeDouble("rangeSamplingRate") / Constants.oneMillion);
//                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.radar_frequency,
//                                                  productInformation.getAttributeDouble("radarFrequency") / Constants.oneMillion);
//                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.line_time_interval,
//                                                  imageInformation.getAttributeDouble("azimuthTimeInterval"));
//
//                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.slant_range_to_first_pixel,
//                                                  imageInformation.getAttributeDouble("slantRangeTime") * Constants.halfLightSpeed);
//
//                    final MetadataElement downlinkInformationList = generalAnnotation.getElement("downlinkInformationList");
//                    final MetadataElement downlinkInformation = downlinkInformationList.getElement("downlinkInformation");
//
//                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.pulse_repetition_frequency,
//                                                  downlinkInformation.getAttributeDouble("prf"));
//
//                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_bandwidth,
//                                                  rangeProcessing.getAttributeDouble("processingBandwidth") / Constants.oneMillion);
//                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_bandwidth,
//                                                  azimuthProcessing.getAttributeDouble("processingBandwidth"));
//
//                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_looks,
//                                                  rangeProcessing.getAttributeDouble("numberOfLooks"));
//                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_looks,
//                                                  azimuthProcessing.getAttributeDouble("numberOfLooks"));
//
//                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_window_type,
//                                                  rangeProcessing.getAttributeString("windowType"));
//                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_window_coefficient,
//                                                  rangeProcessing.getAttributeDouble("windowCoefficient"));

//                    if (!isTOPSAR() || !isSLC()) {
//                        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.num_output_lines,
//                                                      imageInformation.getAttributeInt("numberOfLines"));
//                        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.num_samples_per_line,
//                                                      imageInformation.getAttributeInt("numberOfSamples"));
//                    }

                    //addOrbitStateVectors(absRoot, generalAnnotation.getElement("orbitList"));
                    //addSRGRCoefficients(absRoot, prodElem.getElement("coordinateConversion"));
                    //addDopplerCentroidCoefficients(absRoot, prodElem.getElement("dopplerCentroid"));

                    commonMetadataRetrieved = true;
                }

                ++numBands;
            }
        }

        // set average to absRoot
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_spacing,
                                      rangeSpacingTotal / (double) numBands);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_spacing,
                                      azimuthSpacingTotal / (double) numBands);

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.avg_scene_height, heightSum / filenames.length);

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.bistatic_correction_applied, 1);
    }

    private double getBandTerrainHeight(final MetadataElement prodElem) {
        final MetadataElement generalAnnotation = prodElem.getElement("generalAnnotation");
        final MetadataElement terrainHeightList = generalAnnotation.getElement("terrainHeightList");

        double heightSum = 0.0;

        final MetadataElement[] heightList = terrainHeightList.getElements();
        int cnt = 0;
        for (MetadataElement terrainHeight : heightList) {
            heightSum += terrainHeight.getAttributeDouble("value");
            ++cnt;
        }
        return heightSum / cnt;
    }

    private void addCalibrationAbstractedMetadata(final MetadataElement origProdRoot) throws IOException {

        final String annotfolder = getRootFolder() + "annotation" + '/' + "calibration";
        addMetadataFiles(origProdRoot, annotfolder, "calibration");
    }

    private void addNoiseAbstractedMetadata(final MetadataElement origProdRoot) throws IOException {

        final String annotfolder = getRootFolder() + "annotation" + '/' + "calibration";
        addMetadataFiles(origProdRoot, annotfolder, "noise");
    }

    private void addRFIAbstractedMetadata(final MetadataElement origProdRoot) throws IOException {

        final String annotfolder = getRootFolder() + "annotation" + '/' + "rfi";
        addMetadataFiles(origProdRoot, annotfolder, "rfi");
    }

    private void addMetadataFiles(final MetadataElement origProdRoot, final String folder, final String name) throws IOException {

        final String[] filenames = listFiles(folder);

        if (filenames != null && filenames.length > 0) {
            MetadataElement metaElement = origProdRoot.getElement(name);
            if (metaElement == null) {
                metaElement = new MetadataElement(name);
                origProdRoot.addElement(metaElement);
            }

            for (String metadataFile : filenames) {
                if (metadataFile.startsWith(name)) {

                    final Document xmlDoc;
                    try (final InputStream is = getInputStream(folder + '/' + metadataFile)) {
                        xmlDoc = XMLSupport.LoadXML(is);
                    }
                    final Element rootElement = xmlDoc.getRootElement();
                    final String newName = metadataFile.replace(name+"-", "");
                    final MetadataElement nameElem = new MetadataElement(newName);
                    metaElement.addElement(nameElem);
                    AbstractMetadataIO.AddXMLMetadata(rootElement, nameElem);
                }
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
                                          ReaderUtils.getTime(stateVectorElems[0], "time", sentinelDateFormat));
        }
    }

    private void addVector(final String name, final MetadataElement orbitVectorListElem,
                                  final MetadataElement orbitElem, final int num) {
        final MetadataElement orbitVectorElem = new MetadataElement(name + num);

        final MetadataElement positionElem = orbitElem.getElement("position");
        final MetadataElement velocityElem = orbitElem.getElement("velocity");

        orbitVectorElem.setAttributeUTC(AbstractMetadata.orbit_vector_time,
                                        ReaderUtils.getTime(orbitElem, "time", sentinelDateFormat));

        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_x_pos,
                                           positionElem.getAttributeDouble("x", 0));
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_y_pos,
                                           positionElem.getAttributeDouble("y", 0));
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_z_pos,
                                           positionElem.getAttributeDouble("z", 0));
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_x_vel,
                                           velocityElem.getAttributeDouble("x", 0));
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_y_vel,
                                           velocityElem.getAttributeDouble("y", 0));
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_z_vel,
                                           velocityElem.getAttributeDouble("z", 0));

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

            final ProductData.UTC utcTime = ReaderUtils.getTime(elem, "azimuthTime", sentinelDateFormat);
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

    private void addDopplerCentroidCoefficients(
            final MetadataElement absRoot, final MetadataElement dopplerCentroid) {
        if (dopplerCentroid == null) return;
        final MetadataElement dcEstimateList = dopplerCentroid.getElement("dcEstimateList");
        if (dcEstimateList == null) return;

        final MetadataElement dopplerCentroidCoefficientsElem = absRoot.getElement(AbstractMetadata.dop_coefficients);

        int listCnt = 1;
        for (MetadataElement elem : dcEstimateList.getElements()) {
            final MetadataElement dopplerListElem = new MetadataElement(AbstractMetadata.dop_coef_list + '.' + listCnt);
            dopplerCentroidCoefficientsElem.addElement(dopplerListElem);
            ++listCnt;

            final ProductData.UTC utcTime = ReaderUtils.getTime(elem, "azimuthTime", sentinelDateFormat);
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

        TiePointGrid latGrid = product.getTiePointGrid(OperatorUtils.TPG_LATITUDE);
        TiePointGrid lonGrid = product.getTiePointGrid(OperatorUtils.TPG_LONGITUDE);
        if (latGrid != null && lonGrid != null) {
            setLatLongMetadata(product, latGrid, lonGrid);

            final TiePointGeoCoding tpGeoCoding = new TiePointGeoCoding(latGrid, lonGrid);
            product.setSceneGeoCoding(tpGeoCoding);
            return;
        }

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        final String acquisitionMode = absRoot.getAttributeString(AbstractMetadata.ACQUISITION_MODE);
        int numOfSubSwath;
        switch (acquisitionMode) {
            case "IW":
                numOfSubSwath = 3;
                break;
            case "EW":
                numOfSubSwath = 5;
                break;
            default:
                numOfSubSwath = 1;
        }

        String[] bandNames = product.getBandNames();
        Band firstSWBand = null, lastSWBand = null;
        boolean firstSWBandFound = false, lastSWBandFound = false;
        for (String bandName : bandNames) {
            if (!firstSWBandFound && bandName.contains(acquisitionMode + 1)) {
                firstSWBand = product.getBand(bandName);
                firstSWBandFound = true;
            }

            if (!lastSWBandFound && bandName.contains(acquisitionMode + numOfSubSwath)) {
                lastSWBand = product.getBand(bandName);
                lastSWBandFound = true;
            }
        }
        if (firstSWBand != null && lastSWBand != null) {

            final GeoCoding firstSWBandGeoCoding = bandGeocodingMap.get(firstSWBand);
            final int firstSWBandHeight = firstSWBand.getRasterHeight();

            final GeoCoding lastSWBandGeoCoding = bandGeocodingMap.get(lastSWBand);
            final int lastSWBandWidth = lastSWBand.getRasterWidth();
            final int lastSWBandHeight = lastSWBand.getRasterHeight();

            final PixelPos ulPix = new PixelPos(0, 0);
            final PixelPos llPix = new PixelPos(0, firstSWBandHeight - 1);
            final GeoPos ulGeo = new GeoPos();
            final GeoPos llGeo = new GeoPos();
            firstSWBandGeoCoding.getGeoPos(ulPix, ulGeo);
            firstSWBandGeoCoding.getGeoPos(llPix, llGeo);

            final PixelPos urPix = new PixelPos(lastSWBandWidth - 1, 0);
            final PixelPos lrPix = new PixelPos(lastSWBandWidth - 1, lastSWBandHeight - 1);
            final GeoPos urGeo = new GeoPos();
            final GeoPos lrGeo = new GeoPos();
            lastSWBandGeoCoding.getGeoPos(urPix, urGeo);
            lastSWBandGeoCoding.getGeoPos(lrPix, lrGeo);

            final float[] latCorners = {(float) ulGeo.getLat(), (float) urGeo.getLat(), (float) llGeo.getLat(), (float) lrGeo.getLat()};
            final float[] lonCorners = {(float) ulGeo.getLon(), (float) urGeo.getLon(), (float) llGeo.getLon(), (float) lrGeo.getLon()};

            ReaderUtils.addGeoCoding(product, latCorners, lonCorners);

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_near_lat, ulGeo.getLat());
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_near_long, ulGeo.getLon());
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_far_lat, urGeo.getLat());
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_far_long, urGeo.getLon());

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_near_lat, llGeo.getLat());
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_near_long, llGeo.getLon());
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_far_lat, lrGeo.getLat());
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_far_long, lrGeo.getLon());

            // add band geocoding
            final Band[] bands = product.getBands();
            for (Band band : bands) {
                band.setGeoCoding(bandGeocodingMap.get(band));
            }
        } else {
            try {
                final String annotFolder = getRootFolder() + "annotation";
                final String[] filenames = listFiles(annotFolder);

                //addTiePointGrids(product, null, filenames[0], "");

                latGrid = product.getTiePointGrid(OperatorUtils.TPG_LATITUDE);
                lonGrid = product.getTiePointGrid(OperatorUtils.TPG_LONGITUDE);
                if (latGrid != null && lonGrid != null) {
                    setLatLongMetadata(product, latGrid, lonGrid);

                    final TiePointGeoCoding tpGeoCoding = new TiePointGeoCoding(latGrid, lonGrid);
                    product.setSceneGeoCoding(tpGeoCoding);
                }
            } catch (IOException e) {
                SystemUtils.LOG.severe("Unable to add tpg geocoding " + e.getMessage());
            }
        }
    }

    @Override
    protected void addTiePointGrids(final Product product) {
        // replaced by call to addTiePointGrids(band)
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
        start = start.replace("T", "_");

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

        //addBands(product);
        addGeoCoding(product);

        ReaderUtils.addMetadataIncidenceAngles(product);
        ReaderUtils.addMetadataProductSize(product);

        return product;
    }
}
