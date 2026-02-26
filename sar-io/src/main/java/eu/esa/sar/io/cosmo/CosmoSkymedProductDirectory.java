/*
 * Copyright (C) 2023 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.io.cosmo;

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
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.core.util.StringUtils;
import org.esa.snap.dataio.geotiff.GeoTiffProductReaderPlugIn;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * This class represents a product directory.
 */
public class CosmoSkymedProductDirectory extends XMLProductDirectory {

    private String productName = "CosmoSkymed";
    private String productType = "CosmoSkymed";
    private final String productDescription = "";
    private boolean isComplex = false;

    private final DateFormat standardDateFormat = ProductData.UTC.createDateFormat("yyyy-MM-dd_HH:mm:ss");

    private static final GeoTiffProductReaderPlugIn geoTiffPlugIn = new GeoTiffProductReaderPlugIn();
    private final List<Product> bandProducts = new ArrayList<>();
    private final CosmoSkymedReader.CosmoReader reader;

    public CosmoSkymedProductDirectory(final CosmoSkymedReader.CosmoReader reader, final File headerFile) {
        super(headerFile);
        this.reader = reader;
    }

    @Override
    public void close() throws IOException {
        for (Product bandProduct : bandProducts) {
            bandProduct.closeIO();
            bandProduct.dispose();
        }
        bandProducts.clear();
        super.close();
    }

    protected void addImageFile(final String imgPath, final MetadataElement newRoot) throws IOException {
        final String name = getBandFileNameFromImage(imgPath).toLowerCase();
        if ((name.endsWith("tif") || name.endsWith("tiff")) && !name.contains("_browse")) {
            final File file = getBaseDir().toPath().resolve(imgPath).toFile();
            if (file.exists() && file.length() > 0) {
                final ProductReader geoTiffReader = geoTiffPlugIn.createReaderInstance();
                final Product bandProduct = geoTiffReader.readProductNodes(file, null);
                bandProduct.setName(name);
                bandProducts.add(bandProduct);
            }
        }
    }

    @Override
    protected Dimension getProductDimensions(final MetadataElement newRoot) {
        if(bandProducts.size() > 0) {
            final int sceneWidth = bandProducts.get(0).getSceneRasterWidth();
            final int sceneHeight = bandProducts.get(0).getSceneRasterHeight();
            final MetadataElement absRoot = newRoot.getElement(AbstractMetadata.ABSTRACT_METADATA_ROOT);
            absRoot.setAttributeInt(AbstractMetadata.num_samples_per_line, sceneWidth);
            absRoot.setAttributeInt(AbstractMetadata.num_output_lines, sceneHeight);
            return new Dimension(sceneWidth, sceneHeight);
        }
        return super.getProductDimensions(newRoot);
    }

    @Override
    protected void addBands(final Product trgProduct) {

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(trgProduct);
        final String pol = absRoot.getAttributeString(AbstractMetadata.mds1_tx_rx_polar);

        for (Product bandProduct : bandProducts) {

            for (Band band : bandProduct.getBands()) {
                String trgBandName = "Amplitude_" + pol;
                Band trgBand = ProductUtils.copyBand(band.getName(), bandProduct, trgBandName, trgProduct, true);
                trgBand.setUnit(Unit.AMPLITUDE);
                trgBand.setNoDataValue(0);
                trgBand.setNoDataValueUsed(true);

                SARReader.createVirtualIntensityBand(trgProduct, band, '_' + pol);
            }
        }
    }

    @Override
    protected void addAbstractedMetadataHeader(final MetadataElement root) throws IOException {

        final String defStr = AbstractMetadata.NO_METADATA_STRING;
        final int defInt = AbstractMetadata.NO_METADATA;
        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(root);
        final MetadataElement origProdRoot = AbstractMetadata.addOriginalProductMetadata(root);

        reader.addDeliveryNote(origProdRoot, baseDir);

        final MetadataElement hdf5Attributes = origProdRoot.getElement("HDF5Attributes");
        final MetadataElement rootElem = hdf5Attributes.getElement("_Root_");
        final MetadataElement s01 = hdf5Attributes.getElement("S01");
        final MetadataElement img = hdf5Attributes.getElement("IMG");
        final MetadataElement lrhm = hdf5Attributes.getElement("LRHM");

        convert(rootElem);
        convert(s01);
        convert(img);
        convert(lrhm);

        productName = productInputFile.getName().substring(0, productInputFile.getName().lastIndexOf('.'));
        productType = rootElem.getAttributeString("Product Type");
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PRODUCT, productName);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SPH_DESCRIPTOR, rootElem.getAttributeString("Product Type"));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ACQUISITION_MODE, rootElem.getAttributeString("Acquisition Mode"));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PASS, rootElem.getAttributeString("Orbit Direction"));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ABS_ORBIT, getInt(rootElem, "Orbit Number"));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.REL_ORBIT, getInt(rootElem, "Track Number"));

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.MISSION, "CSK");
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PRODUCT_TYPE, productType);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SAMPLE_TYPE, getSampleType(productType));

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.incidence_near, getDouble(rootElem, "Near Incidence Angle"));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.incidence_far, getDouble(rootElem, "Far Incidence Angle"));

        //AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ProcessingSystemIdentifier, rootElem.getAttributeString("L1B Software Version"));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.algorithm, rootElem.getAttributeString("Focusing Algorithm ID"));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.antenna_pointing, rootElem.getAttributeString("Look Side").toLowerCase());

        //AbstractMetadata.setAttribute(absRoot, AbstractMetadata.satellite, rootElem.getAttributeString("Satellite ID", defStr));

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.radar_frequency,
                getDouble(rootElem, "Radar Frequency") / Constants.oneMillion);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.geo_ref_system,
                rootElem.getAttributeString("Ellipsoid_Designator", defStr));

        if (rootElem.containsAttribute("Projection ID")) {
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.map_projection,
                    rootElem.getAttributeString("Projection ID", defStr));
        }

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_spacing, getDouble(img,"Column Spacing"));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_spacing, getDouble(img,"Line Spacing"));

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_looks,
                getDouble(rootElem, "Range Processing Number of Looks"));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_looks,
                getDouble(rootElem, "Azimuth Processing Number of Looks"));

        final String rngSpreadComp = rootElem.getAttributeString("Range Spreading Loss Compensation Geometry", defStr);
        if (rngSpreadComp.equals("NONE"))
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_spread_comp_flag, 0);
        else
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_spread_comp_flag, 1);

        final String incAngComp = rootElem.getAttributeString("Incidence Angle Compensation Geometry", defStr);
        if (incAngComp.equals("NONE"))
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.inc_angle_comp_flag, 0);
        else
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.inc_angle_comp_flag, 1);

        final String antElevComp = rootElem.getAttributeString("Range Antenna Pattern Compensation Geometry", defStr);
        if (antElevComp.equals("NONE"))
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ant_elev_corr_flag, 0);
        else
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ant_elev_corr_flag, 1);

//        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ref_inc_angle,
//                getDouble(rootElem, "Reference Incidence Angle"));
//        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ref_slant_range,
//                getDouble(rootElem, "Reference Slant Range"));
//        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ref_slant_range_exp,
//                getDouble(rootElem, "Reference Slant Range Exponent"));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.rescaling_factor,
                getDouble(img, "Rescaling Factor"));

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.mds1_tx_rx_polar, rootElem.getAttributeString("Polarization"));

        ProductData.UTC startTime = getTime(rootElem, "Scene Sensing Start UTC");
        ProductData.UTC stopTime = getTime(rootElem, "Scene Sensing Stop UTC");

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_line_time, startTime);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_line_time, stopTime);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.line_time_interval,
                ReaderUtils.getLineTimeInterval(startTime, stopTime,
                        absRoot.getAttributeInt(AbstractMetadata.num_output_lines)));

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.pulse_repetition_frequency, getDouble(s01, "PRF"));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_sampling_rate,
                getDouble(s01, "Sampling Rate") / Constants.oneMillion);

        // add Range and Azimuth bandwidth
        final double rangeBW = getDouble(s01, "Range Focusing Bandwidth"); // Hz
        final double azimuthBW = getDouble(s01, "Azimuth Focusing Bandwidth"); // Hz
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_bandwidth, rangeBW / Constants.oneMillion);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_bandwidth, azimuthBW);

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.bistatic_correction_applied, 1);

        addOrbitStateVectors(absRoot, rootElem);
    }

    private static int getInt(MetadataElement elem, String tag) {
        return Integer.parseInt(elem.getAttribute(tag).getData().getElemString().trim());
    }

    private static double getDouble(MetadataElement elem, String tag) {
        return Double.parseDouble(elem.getAttribute(tag).getData().getElemString().trim());
    }

    private static void convert(final MetadataElement rootElem) {
        MetadataElement[] elems = rootElem.getElements();
        for(MetadataElement elem : elems) {
            if(elem.containsAttribute("Name")) {
                String name = elem.getAttributeString("Name");
                MetadataAttribute attrib = elem.getAttribute("Attribute");
                if (attrib != null) {
                    attrib.setName(name);
                    rootElem.addAttribute(attrib);
                    rootElem.removeElement(elem);
                }
            } else {
                convert(elem);
            }
        }
    }

    private String getSampleType(final String productType) {
        if (productType.toUpperCase().contains("SCS")) {
            isComplex = true;
            return "COMPLEX";
        }
        isComplex = false;
        return "DETECTED";
    }

    private ProductData.UTC getTime(final MetadataElement elem, final String tag) {

        String start = elem.getAttributeString(tag, AbstractMetadata.NO_METADATA_STRING);
        start = start.replace("T", "_").replace(" ", "_");

        return AbstractMetadata.parseUTC(start, standardDateFormat);
    }

    private void addOrbitStateVectors(final MetadataElement absRoot, final MetadataElement globalElem) {

        final MetadataElement orbitVectorListElem = absRoot.getElement(AbstractMetadata.orbit_state_vectors);
        final ProductData.UTC referenceUTC = getTime(globalElem, "Reference UTC");

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.STATE_VECTOR_TIME, referenceUTC);

        final String stateVectorTimesStr = globalElem.getAttributeString("State Vectors Times").trim();
        final String posStr = globalElem.getAttributeString("ECEF Satellite Position").trim();
        final String velStr = globalElem.getAttributeString("ECEF Satellite Velocity").trim();

        double[] stateVectorTimes = StringUtils.toDoubleArray(stateVectorTimesStr, " ");
        double[] pos = StringUtils.toDoubleArray(posStr, " ");
        double[] vel = StringUtils.toDoubleArray(velStr, " ");

        for (int i = 0; i < stateVectorTimes.length; i++) {
            final double stateVectorTime = stateVectorTimes[i];
            final ProductData.UTC orbitTime =
                    new ProductData.UTC(referenceUTC.getMJD() + stateVectorTime / Constants.secondsInDay);

            final double satellitePositionX = pos[3 * i];
            final double satellitePositionY = pos[3 * i + 1];
            final double satellitePositionZ = pos[3 * i + 2];
            final double satelliteVelocityX = vel[3 * i];
            final double satelliteVelocityY = vel[3 * i + 1];
            final double satelliteVelocityZ = vel[3 * i + 2];

            final MetadataElement orbitVectorElem = new MetadataElement(AbstractMetadata.orbit_vector + (i + 1));

            orbitVectorElem.setAttributeUTC(AbstractMetadata.orbit_vector_time, orbitTime);
            orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_x_pos, satellitePositionX);
            orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_y_pos, satellitePositionY);
            orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_z_pos, satellitePositionZ);
            orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_x_vel, satelliteVelocityX);
            orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_y_vel, satelliteVelocityY);
            orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_z_vel, satelliteVelocityZ);

            orbitVectorListElem.addElement(orbitVectorElem);
        }
    }

    @Override
    protected void addGeoCoding(final Product product) {

        if (product.getSceneGeoCoding() != null) {
            return;
        }

        final MetadataElement origProdRoot = AbstractMetadata.getOriginalProductMetadata(product);
        final MetadataElement hdf5Attributes = origProdRoot.getElement("HDF5Attributes");
        final MetadataElement rootElem = hdf5Attributes.getElement("_Root_");

        addGeocodingFromMetadata(product, rootElem);
    }

    @Override
    protected void addTiePointGrids(Product product) {

    }

    private static void addGeocodingFromMetadata(final Product product, final MetadataElement rootElem) {
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);

        try {
            String nearCoordStr = rootElem.getAttributeString("Scene Near Edge Geodetic Coordinates").trim();
            String farCoordStr = rootElem.getAttributeString("Scene Far Edge Geodetic Coordinates").trim();

            float[] nearCoord = StringUtils.toFloatArray(nearCoordStr, " ");
            float[] farCoord = StringUtils.toFloatArray(farCoordStr, " ");

            final int numRows = nearCoord.length/3;
            final int size = numRows * 2;
            float[] lats = new float[size];
            float[] lons = new float[size];
            int cnt = 0;
            for(int i=0; i< nearCoord.length; i=i+3) {
                lats[cnt] = nearCoord[i];
                lons[cnt] = nearCoord[i+1];
                cnt++;
                lats[cnt] = farCoord[i];
                lons[cnt] = farCoord[i+1];
                cnt++;
            }

            addGeoCoding(product, 2, numRows, lats, lons);

        } catch (Exception e) {
            System.out.println(e.getMessage());
            // continue
        }
    }

    private static void addGeoCoding(final Product product,
                                     final int coarseGridWidth, final int coarseGridHeight,
                                     final float[] latCorners, final float[] lonCorners) {

        if (latCorners == null || lonCorners == null) return;

        final int gridWidth = Math.min(Math.max(10, coarseGridWidth), Math.max(2, product.getSceneRasterWidth()));
        final int gridHeight = Math.min(Math.max(10, coarseGridHeight), Math.max(2, product.getSceneRasterHeight()));

        final float[] fineLatTiePoints = new float[gridWidth * gridHeight];
        ReaderUtils.createFineTiePointGrid(coarseGridWidth, coarseGridHeight, gridWidth, gridHeight, latCorners, fineLatTiePoints);

        double subSamplingX = product.getSceneRasterWidth() / (double)(gridWidth - 1);
        double subSamplingY = product.getSceneRasterHeight() / (double)(gridHeight - 1);
        if (subSamplingX == 0 || subSamplingY == 0)
            return;

        final TiePointGrid latGrid = new TiePointGrid(OperatorUtils.TPG_LATITUDE, gridWidth, gridHeight, 0.5f, 0.5f,
                subSamplingX, subSamplingY, fineLatTiePoints);
        latGrid.setUnit(Unit.DEGREES);

        final float[] fineLonTiePoints = new float[gridWidth * gridHeight];
        ReaderUtils.createFineTiePointGrid(coarseGridWidth, coarseGridHeight, gridWidth, gridHeight, lonCorners, fineLonTiePoints);

        final TiePointGrid lonGrid = new TiePointGrid(OperatorUtils.TPG_LONGITUDE, gridWidth, gridHeight, 0.5f, 0.5f,
                subSamplingX, subSamplingY, fineLonTiePoints, TiePointGrid.DISCONT_AT_180);
        lonGrid.setUnit(Unit.DEGREES);

        final TiePointGeoCoding tpGeoCoding = new TiePointGeoCoding(latGrid, lonGrid);

        product.addTiePointGrid(latGrid);
        product.addTiePointGrid(lonGrid);
        product.setSceneGeoCoding(tpGeoCoding);
    }

    @Override
    protected String getProductName() {
        return productName;
    }

    @Override
    protected String getProductDescription() {
        return productDescription;
    }

    @Override
    protected String getProductType() {
        return productType;
    }
}
