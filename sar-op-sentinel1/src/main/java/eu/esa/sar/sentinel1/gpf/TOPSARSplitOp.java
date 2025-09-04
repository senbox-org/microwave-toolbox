/*
 * Copyright (C) 2014 by Array Systems Computing Inc. http://www.array.ca
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
package eu.esa.sar.sentinel1.gpf;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.subset.PixelSubsetRegion;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import eu.esa.sar.commons.Sentinel1Utils;
import org.esa.snap.core.dataio.ProductSubsetBuilder;
import org.esa.snap.core.dataio.ProductSubsetDef;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;

import java.awt.*;
import java.io.IOException;
import java.text.DateFormat;
import java.util.*;
import java.util.List;

/**
 * Creates a new product with only selected sub-swath and bursts
 */

@OperatorMetadata(alias = "TOPSAR-Split",
        category = "Radar/Sentinel-1 TOPS",
        authors = "Jun Lu, Luis Veci",
        version = "1.0",
        copyright = "Copyright (C) 2016 by Array Systems Computing Inc.",
        description = "Creates a new product with only the selected subswath")
public final class TOPSARSplitOp extends Operator {

    @SourceProduct(alias = "source")
    private Product sourceProduct;
    @TargetProduct
    private Product targetProduct;

    @Parameter(description = "The list of source bands.", label = "Subswath")
    private String subswath = null;

    @Parameter(description = "The list of polarisations", label = "Polarisations")
    private String[] selectedPolarisations;

    @Parameter(description = "The first burst index", interval = "[1, *)", defaultValue = "1", label = "First Burst Index")
    private Integer firstBurstIndex = 1;

    @Parameter(description = "The last burst index", interval = "[1, *)", defaultValue = "9999", label = "Last Burst Index")
    private Integer lastBurstIndex = 9999;

    @Parameter(description = "WKT polygon to be used for selecting bursts", label = "WKT Area of Interest")
    private String wktAoi = null;

    private Sentinel1Utils.SubSwathInfo[] subSwathInfo = null;
    private int subSwathIndex = 0;
    private ProductSubsetBuilder subsetBuilder = null;

    /**
     * Initializes this operator and sets the one and only target product.
     * <p>The target product can be either defined by a field of type {@link Product} annotated with the
     * {@link TargetProduct TargetProduct} annotation or
     * by calling {@link #setTargetProduct} method.</p>
     * <p>The framework calls this method after it has created this operator.
     * Any client code that must be performed before computation of tile data
     * should be placed here.</p>
     *
     * @throws OperatorException If an error occurs during operator initialisation.
     * @see #getTargetProduct()
     */
    @Override
    public void initialize() throws OperatorException {

        try {
            final InputProductValidator validator = new InputProductValidator(sourceProduct);
            validator.checkIfSARProduct();
            validator.checkIfSentinel1Product();
            validator.isTOPSARProduct();
            validator.checkProductType(new String[]{"SLC"});
            validator.checkAcquisitionMode(new String[]{"IW", "EW"});

            final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);
            if (subswath == null) {
                final String acquisitionMode = absRoot.getAttributeString(AbstractMetadata.ACQUISITION_MODE);
                subswath = acquisitionMode + '1';
            }

            final Sentinel1Utils su = new Sentinel1Utils(sourceProduct);
            subSwathInfo = su.getSubSwath();
            for (int i = 0; i < subSwathInfo.length; i++) {
                if (subSwathInfo[i].subSwathName.contains(subswath)) {
                    subSwathIndex = i + 1;
                    break;
                }
            }

            if (selectedPolarisations == null || selectedPolarisations.length == 0) {
                selectedPolarisations = Sentinel1Utils.getProductPolarizations(absRoot);
            }

            final List<Band> selectedBands = new ArrayList<>();
            for (Band srcBand : sourceProduct.getBands()) {
                if (srcBand.getName().contains(subswath)) {
                    for (String pol : selectedPolarisations) {
                        if (srcBand.getName().contains(pol)) {
                            selectedBands.add(srcBand);
                        }
                    }
                }
            }

            if (selectedBands.size() < 1) {
                // try again
                selectedPolarisations = Sentinel1Utils.getProductPolarizations(absRoot);

                for (Band srcBand : sourceProduct.getBands()) {
                    if (srcBand.getName().contains(subswath)) {
                        for (String pol : selectedPolarisations) {
                            if (srcBand.getName().contains(pol)) {
                                selectedBands.add(srcBand);
                            }
                        }
                    }
                }
            }

            int maxBursts = su.getNumOfBursts(subswath);
            if (lastBurstIndex > maxBursts) {
                lastBurstIndex = maxBursts;
            }

            if(wktAoi != null) {
                findValidBurstsBasedOnWkt();
            }

            subsetBuilder = new ProductSubsetBuilder();
            final ProductSubsetDef subsetDef = new ProductSubsetDef();

            final List<String> selectedTPGList = new ArrayList<>();
            for (TiePointGrid srcTPG : sourceProduct.getTiePointGrids()) {
                if (srcTPG.getName().contains(subswath)) {
                    selectedTPGList.add(srcTPG.getName());
                }
            }

            final boolean foundSwathTPG = !selectedTPGList.isEmpty();
            if (!foundSwathTPG) {
                for (TiePointGrid srcTPG : sourceProduct.getTiePointGrids()) {
                    selectedTPGList.add(srcTPG.getName());
                }
            }

            subsetDef.addNodeNames(selectedTPGList.toArray(new String[0]));

            final int x = 0;
            final int y = (firstBurstIndex - 1) * subSwathInfo[subSwathIndex - 1].linesPerBurst;
            final int w = selectedBands.get(0).getRasterWidth();
            final int h = (lastBurstIndex - firstBurstIndex + 1) * subSwathInfo[subSwathIndex - 1].linesPerBurst;
            subsetDef.setSubsetRegion(new PixelSubsetRegion(x, y, w, h, 0));

            subsetDef.setSubSampling(1, 1);
            subsetDef.setIgnoreMetadata(false);

            final String[] selectedBandNames = new String[selectedBands.size()];
            for (int i = 0; i < selectedBandNames.length; i++) {
                selectedBandNames[i] = selectedBands.get(i).getName();
            }
            subsetDef.addNodeNames(selectedBandNames);

            targetProduct = subsetBuilder.readProductNodes(sourceProduct, subsetDef);

            if (foundSwathTPG) {
                targetProduct.removeTiePointGrid(targetProduct.getTiePointGrid("latitude"));
                targetProduct.removeTiePointGrid(targetProduct.getTiePointGrid("longitude"));

                for (TiePointGrid tpg : targetProduct.getTiePointGrids()) {
                    tpg.setName(tpg.getName().replace(subswath + "_", ""));
                }
            }

            GeoCoding geoCoding = new TiePointGeoCoding(targetProduct.getTiePointGrid("latitude"),
                    targetProduct.getTiePointGrid("longitude"));
            targetProduct.setSceneGeoCoding(geoCoding);

            updateTargetProductMetadata();
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    /**
     * Called by the framework in order to compute a tile for the given target band.
     * <p>The default implementation throws a runtime exception with the message "not implemented".</p>
     *
     * @param targetBand The target band.
     * @param targetTile The current tile associated with the target band to be computed.
     * @param pm         A progress monitor which should be used to determine computation cancelation requests.
     * @throws OperatorException If an error occurs during computation of the target raster.
     */
    @Override
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) throws OperatorException {

        ProductData destBuffer = targetTile.getRawSamples();
        Rectangle rectangle = targetTile.getRectangle();
        try {
            subsetBuilder.readBandRasterData(targetBand,
                    rectangle.x,
                    rectangle.y,
                    rectangle.width,
                    rectangle.height,
                    destBuffer, pm);
            targetTile.setRawSamples(destBuffer);
        } catch (IOException e) {
            throw new OperatorException(e);
        }
    }

    /**
     * Find bursts (i.e. firstBurstIndex and lastBurstIndex) that overlap AOI WKT
     */
    private void findValidBurstsBasedOnWkt() throws Exception {
        // Read AOI polygon
        Geometry aoi = null;
        try {
            aoi = new WKTReader().read(wktAoi);
        } catch (ParseException e) {
            throw new Exception("Unable to parse wktAoi", e);
        }

        // Read burst polygons and check if it intersects AOI
        int numBursts = lastBurstIndex - firstBurstIndex + 1;
        GeoPos[][] geoBound = new GeoPos[numBursts][4];
        Sentinel1Utils.SubSwathInfo swath = subSwathInfo[subSwathIndex - 1];
        GeometryFactory gf = new GeometryFactory();
        Coordinate[] coordinates;
        LinearRing linearRing;
        Geometry burst;
        int numPoints = swath.latitude[0].length - 1;
        List<Integer> validSelBursts = new ArrayList<>();

        int burst_i;
        for (int i = 0; i < numBursts; ++i) {
            burst_i = firstBurstIndex - 1 + i;
            geoBound[i][0] = new GeoPos(swath.latitude[burst_i][0], swath.longitude[burst_i][0]);
            geoBound[i][1] = new GeoPos(swath.latitude[burst_i][numPoints], swath.longitude[burst_i][numPoints]);
            geoBound[i][2] = new GeoPos(swath.latitude[burst_i + 1][numPoints], swath.longitude[burst_i + 1][numPoints]);
            geoBound[i][3] = new GeoPos(swath.latitude[burst_i + 1][0], swath.longitude[burst_i + 1][0]);
            coordinates = new Coordinate[5];
            for (int c_i = 0; c_i < 4; ++c_i) {
                coordinates[c_i] = new Coordinate(geoBound[i][c_i].getLon(), geoBound[i][c_i].getLat());
            }
            coordinates[4] = new Coordinate(geoBound[i][0].getLon(), geoBound[i][0].getLat());
            linearRing = gf.createLinearRing(coordinates);
            burst = gf.createPolygon(linearRing, null);
            if (aoi.intersects(burst)) {
                validSelBursts.add(burst_i + 1);
            }
        }

        if(validSelBursts.isEmpty()) {
            throw new Exception("wktAOI does not overlap any burst");
        }

        firstBurstIndex = Collections.min(validSelBursts);
        lastBurstIndex = Collections.max(validSelBursts);
    }

    /**
     * Update target product metadata.
     */
    private void updateTargetProductMetadata() {

        updateAbstractedMetadata();
        updateOriginalMetadata();
    }

    /**
     * Update the abstracted metadata in the target product.
     */
    private void updateAbstractedMetadata() {

        final MetadataElement absSrc = AbstractMetadata.getAbstractedMetadata(sourceProduct);
        final MetadataElement absTgt = AbstractMetadata.getAbstractedMetadata(targetProduct);

        absTgt.setAttributeUTC(AbstractMetadata.first_line_time, new ProductData.UTC(
                subSwathInfo[subSwathIndex - 1].burstFirstLineTime[firstBurstIndex - 1] / Constants.secondsInDay));

        absTgt.setAttributeUTC(AbstractMetadata.last_line_time, new ProductData.UTC(
                subSwathInfo[subSwathIndex - 1].burstLastLineTime[lastBurstIndex - 1] / Constants.secondsInDay));

        absTgt.setAttributeDouble(AbstractMetadata.line_time_interval,
                subSwathInfo[subSwathIndex - 1].azimuthTimeInterval);

        absTgt.setAttributeDouble(AbstractMetadata.slant_range_to_first_pixel,
                subSwathInfo[subSwathIndex - 1].slrTimeToFirstPixel * Constants.lightSpeed);

        absTgt.setAttributeDouble(AbstractMetadata.range_spacing,
                subSwathInfo[subSwathIndex - 1].rangePixelSpacing);

        absTgt.setAttributeDouble(AbstractMetadata.azimuth_spacing,
                subSwathInfo[subSwathIndex - 1].azimuthPixelSpacing);

        absTgt.setAttributeInt(AbstractMetadata.num_output_lines,
                subSwathInfo[subSwathIndex - 1].linesPerBurst * (lastBurstIndex - firstBurstIndex + 1));

        absTgt.setAttributeInt(AbstractMetadata.num_samples_per_line,
                subSwathInfo[subSwathIndex - 1].numOfSamples);

        final int cols = subSwathInfo[subSwathIndex - 1].latitude[0].length;

        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.first_near_lat,
                subSwathInfo[subSwathIndex - 1].latitude[firstBurstIndex - 1][0]);

        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.first_near_long,
                subSwathInfo[subSwathIndex - 1].longitude[firstBurstIndex - 1][0]);

        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.first_far_lat,
                subSwathInfo[subSwathIndex - 1].latitude[firstBurstIndex - 1][cols - 1]);

        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.first_far_long,
                subSwathInfo[subSwathIndex - 1].longitude[firstBurstIndex - 1][cols - 1]);

        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.last_near_lat,
                subSwathInfo[subSwathIndex - 1].latitude[lastBurstIndex][0]);

        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.last_near_long,
                subSwathInfo[subSwathIndex - 1].longitude[lastBurstIndex][0]);

        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.last_far_lat,
                subSwathInfo[subSwathIndex - 1].latitude[lastBurstIndex][cols - 1]);

        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.last_far_long,
                subSwathInfo[subSwathIndex - 1].longitude[lastBurstIndex][cols - 1]);

        final double incidenceNear = OperatorUtils.getIncidenceAngle(targetProduct).getPixelDouble(
                0, targetProduct.getSceneRasterHeight() / 2);

        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.incidence_near, incidenceNear);

        final double incidenceFar = OperatorUtils.getIncidenceAngle(targetProduct).getPixelDouble(
                targetProduct.getSceneRasterWidth() - 1, targetProduct.getSceneRasterHeight() / 2);

        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.incidence_far, incidenceFar);

        absTgt.setAttributeString(AbstractMetadata.swath, subswath);

        for (int i = 0; i < selectedPolarisations.length; i++) {
            if (i == 0) {
                absTgt.setAttributeString(AbstractMetadata.mds1_tx_rx_polar, selectedPolarisations[i]);
            } else if (i == 1) {
                absTgt.setAttributeString(AbstractMetadata.mds2_tx_rx_polar, selectedPolarisations[i]);
            } else if (i == 2) {
                absTgt.setAttributeString(AbstractMetadata.mds3_tx_rx_polar, selectedPolarisations[i]);
            } else {
                absTgt.setAttributeString(AbstractMetadata.mds4_tx_rx_polar, selectedPolarisations[i]);
            }
        }

        final MetadataElement[] bandMetadataList = AbstractMetadata.getBandAbsMetadataList(absTgt);
        for (MetadataElement bandMeta : bandMetadataList) {
            boolean include = false;

            if (bandMeta.getName().contains(subswath)) {

                for (String pol : selectedPolarisations) {
                    if (bandMeta.getName().contains(pol)) {
                        include = true;
                        break;
                    }
                }
            }
            if (!include) {
                // remove band metadata if polarization or subswath is not included
                absTgt.removeElement(bandMeta);
            }
        }

        // Do not delete the following lines because the orbit state vector time in the target product could be wrong.
        final MetadataElement tgtOrbitVectorsElem = absTgt.getElement(AbstractMetadata.orbit_state_vectors);
        final MetadataElement srcOrbitVectorsElem = absSrc.getElement(AbstractMetadata.orbit_state_vectors);
        final int numOrbitVectors = srcOrbitVectorsElem.getNumElements();
        for (int i = 1; i <= numOrbitVectors; ++i) {
            final MetadataElement orbElem = srcOrbitVectorsElem.getElement(AbstractMetadata.orbit_vector + i);
            final ProductData.UTC time = orbElem.getAttributeUTC(AbstractMetadata.orbit_vector_time);
            tgtOrbitVectorsElem.getElement(AbstractMetadata.orbit_vector + i).setAttributeUTC(AbstractMetadata.orbit_vector_time, time);
        }
    }

    private void updateOriginalMetadata() {

        final MetadataElement origMeta = AbstractMetadata.getOriginalProductMetadata(targetProduct);
        removeElements(origMeta, "annotation");
        removeElements(origMeta, "calibration");
        removeElements(origMeta, "noise");
        removeElements(origMeta, "rfi");
        removeBursts(origMeta);
        updateImageInformation(origMeta);
    }

    private void removeElements(final MetadataElement origMeta, final String parent) {
        final MetadataElement parentElem = origMeta.getElement(parent);
        if (parentElem != null) {
            final MetadataElement[] elemList = parentElem.getElements();
            for (MetadataElement elem : elemList) {
                if (!elem.getName().toUpperCase().contains(subswath)) {
                    parentElem.removeElement(elem);
                } else {
                    boolean isSelected = false;
                    for (String pol : selectedPolarisations) {
                        if (elem.getName().toUpperCase().contains(pol)) {
                            isSelected = true;
                            break;
                        }
                    }
                    if (!isSelected) {
                        parentElem.removeElement(elem);
                    }
                }
            }
        }
    }

    private void removeBursts(final MetadataElement origMeta) {

        final MetadataElement annotation = origMeta.getElement("annotation");
        if (annotation == null) {
            throw new OperatorException("Annotation Metadata not found");
        }

        final MetadataElement[] elems = annotation.getElements();
        for (MetadataElement elem : elems) {
            if(elem.containsElement("product")) {
                final MetadataElement product = elem.getElement("product");
                if(product.containsElement("swathTiming")) {
                    final MetadataElement swathTiming = product.getElement("swathTiming");
                    if(swathTiming.containsElement("burstList")) {
                        final MetadataElement burstList = swathTiming.getElement("burstList");
                        burstList.setAttributeString("count", Integer.toString(lastBurstIndex - firstBurstIndex + 1));
                        final MetadataElement[] burstListElem = burstList.getElements();
                        for (int i = 0; i < burstListElem.length; i++) {
                            if (i < firstBurstIndex - 1 || i > lastBurstIndex - 1) {
                                burstList.removeElement(burstListElem[i]);
                            }
                        }
                    }
                }
            }
        }
    }

    private void updateImageInformation(final MetadataElement origMeta) {

        MetadataElement annotation = origMeta.getElement("annotation");
        if (annotation == null) {
            throw new OperatorException("Annotation Metadata not found");
        }

        final MetadataElement[] elems = annotation.getElements();
        for (MetadataElement elem : elems) {
            final MetadataElement product = elem.getElement("product");
            final MetadataElement imageAnnotation = product.getElement("imageAnnotation");
            final MetadataElement imageInformation = imageAnnotation.getElement("imageInformation");

            imageInformation.setAttributeString("numberOfLines", Integer.toString(
                    subSwathInfo[subSwathIndex - 1].linesPerBurst * (lastBurstIndex - firstBurstIndex + 1)));

            final ProductData.UTC firstLineTimeUTC = new ProductData.UTC(
                    subSwathInfo[subSwathIndex - 1].burstFirstLineTime[firstBurstIndex - 1] / Constants.secondsInDay);

            imageInformation.setAttributeString("productFirstLineUtcTime", format(firstLineTimeUTC));

            final ProductData.UTC lastLineTimeUTC = new ProductData.UTC(
                    subSwathInfo[subSwathIndex - 1].burstLastLineTime[lastBurstIndex - 1] / Constants.secondsInDay);

            imageInformation.setAttributeString("productLastLineUtcTime", format(lastLineTimeUTC));
        }
    }

    public String format(final ProductData.UTC utc) {
        final Calendar calendar = ProductData.UTC.createCalendar();
        calendar.add(Calendar.DATE, utc.getDaysFraction());
        calendar.add(Calendar.SECOND, (int) utc.getSecondsFraction());
        final DateFormat dateFormat = ProductData.UTC.createDateFormat("yyyy-MM-dd_HH:mm:ss");  // 2015-02-05T20:25:19.830824
        final Date time = calendar.getTime();
        final String dateString = dateFormat.format(time);
        final String microsString = String.valueOf(utc.getMicroSecondsFraction());
        StringBuilder sb = new StringBuilder(dateString.toUpperCase());
        sb.append('.');
        for (int i = microsString.length(); i < 6; i++) {
            sb.append('0');
        }
        sb.append(microsString);
        return sb.toString().replace("_", "T");
    }

    /**
     * The SPI is used to register this operator in the graph processing framework
     * via the SPI configuration file
     * {@code META-INF/services/org.esa.snap.core.gpf.OperatorSpi}.
     * This class may also serve as a factory for new operator instances.
     *
     * @see OperatorSpi#createOperator()
     * @see OperatorSpi#createOperator(java.util.Map, java.util.Map)
     */
    public static class Spi extends OperatorSpi {
        public Spi() {
            super(TOPSARSplitOp.class);
        }
    }
}
