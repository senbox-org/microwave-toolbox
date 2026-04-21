/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. http://www.skywatch.com
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

import eu.esa.sar.commons.product.Missions;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.TiePointGeoCoding;
import org.esa.snap.core.datamodel.TiePointGrid;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import ucar.ma2.DataType;
import ucar.nc2.Group;
import ucar.nc2.Variable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class NisarSMEProductReader extends NisarSubReader {

    public NisarSMEProductReader() {
        productType = "SME2";
    }

    @Override
    protected String getProductType(Group groupID) throws Exception {
        return productType;
    }

    @Override
    protected Group getSARGroup() {
        return this.netcdfFile.getRootGroup();
    }

    @Override
    protected Group getFrequencyAGroup(final Group groupLSAR) {
        return this.netcdfFile.getRootGroup();
    }

    @Override
    protected Group getFrequencyBGroup(final Group groupLSAR) {
        return null;
    }

    @Override
    protected Variable[] getRasterVariables(final Group groupFrequencyA) {
        List<Variable> rasterVariables = new ArrayList<>();

        final Variable Waterbody_fraction = groupFrequencyA.findVariable("Waterbody_fraction");
        final Variable landcover = groupFrequencyA.findVariable("Landcover");

        rasterVariables.add(Waterbody_fraction);
        rasterVariables.add(landcover);

        return rasterVariables.toArray(new Variable[0]);
    }

    @Override
    protected void addBandsForFrequency(Group groupFrequency, String suffix) {
        final Variable Waterbody_fraction = groupFrequency.findVariable("Waterbody_fraction");
        final Variable landcover = groupFrequency.findVariable("Landcover");

        final int rasterHeight = Waterbody_fraction.getDimension(0).getLength();
        final int rasterWidth = Waterbody_fraction.getDimension(1).getLength();

        createBand("Waterbody_fraction", rasterWidth, rasterHeight, Unit.SOIL_MOISTURE, Waterbody_fraction);

        createBand("landcover", rasterWidth, rasterHeight, Unit.CLASS, landcover);
    }

    @Override
    protected void addAbstractedMetadataHeader(final MetadataElement root) throws Exception {
        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(root);

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PRODUCT, product.getName());
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.MISSION, Missions.NISAR);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PRODUCT_TYPE, productType);
        product.setDescription("NISAR SME2 Soil Moisture Product");
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SPH_DESCRIPTOR, "NISAR SME2 Soil Moisture Product");
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ACQUISITION_MODE, "Stripmap");
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PASS, "DESCENDING");
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.antenna_pointing, "right");
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SAMPLE_TYPE, "DETECTED");
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.num_output_lines, product.getSceneRasterHeight());
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.num_samples_per_line, product.getSceneRasterWidth());
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.radar_frequency, 1270.0);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.line_time_interval, 1.0);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.pulse_repetition_frequency, 1.0);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_spacing, 1.0);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_spacing, 1.0);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_looks, 1);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_looks, 1);

        // Parse start/end times from filename (e.g. ...20190829T180759_20190829T180809...)
        try {
            String name = product.getName();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "(\\d{8}T\\d{6})_(\\d{8}T\\d{6})").matcher(name);
            if (m.find()) {
                java.text.DateFormat fmt = ProductData.UTC.createDateFormat("yyyyMMdd HHmmss");
                ProductData.UTC startTime = ProductData.UTC.parse(m.group(1).replace("T", " "), fmt);
                ProductData.UTC endTime = ProductData.UTC.parse(m.group(2).replace("T", " "), fmt);
                product.setStartTime(startTime);
                product.setEndTime(endTime);
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_line_time, startTime);
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_line_time, endTime);
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.STATE_VECTOR_TIME, startTime);
            }
        } catch (Exception e) {
            org.esa.snap.core.util.SystemUtils.LOG.warning("Failed to parse SME2 times: " + e.getMessage());
        }

        // Add a dummy orbit state vector to pass validation
        final MetadataElement orbitVectorListElem = absRoot.getElement(AbstractMetadata.orbit_state_vectors);
        final MetadataElement orbitVectorElem = new MetadataElement(AbstractMetadata.orbit_vector + "1");
        orbitVectorElem.setAttributeUTC(AbstractMetadata.orbit_vector_time,
                absRoot.getAttributeUTC(AbstractMetadata.first_line_time, AbstractMetadata.NO_METADATA_UTC));
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_x_pos, 1.0);
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_y_pos, 1.0);
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_z_pos, 1.0);
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_x_vel, 1.0);
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_y_vel, 1.0);
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_z_vel, 1.0);
        orbitVectorListElem.addElement(orbitVectorElem);
    }

    @Override
    protected void addTiePointGridsToProduct() throws IOException {
        try {
            Variable lonVar = netcdfFile.getRootGroup().findVariable("longitude");
            Variable latVar = netcdfFile.getRootGroup().findVariable("latitude");
            if (lonVar == null || latVar == null) return;

            float[] lonValues = (float[]) lonVar.read().get1DJavaArray(DataType.FLOAT);
            float[] latValues = (float[]) latVar.read().get1DJavaArray(DataType.FLOAT);
            final int gridWidth = lonValues.length;
            final int gridHeight = latValues.length;
            final int sceneWidth = product.getSceneRasterWidth();
            final int sceneHeight = product.getSceneRasterHeight();
            final double subSamplingX = (double) sceneWidth / (double) (gridWidth - 1);
            final double subSamplingY = (double) sceneHeight / (double) (gridHeight - 1);

            final int length = gridWidth * gridHeight;
            final float[] latTiePoints = new float[length];
            final float[] lonTiePoints = new float[length];

            for (int j = 0; j < gridHeight; j++) {
                for (int i = 0; i < gridWidth; i++) {
                    latTiePoints[j * gridWidth + i] = latValues[j];
                    lonTiePoints[j * gridWidth + i] = lonValues[i];
                }
            }

            TiePointGrid latGrid = new TiePointGrid("latitude", gridWidth, gridHeight, 0.5f, 0.5f,
                    subSamplingX, subSamplingY, latTiePoints);
            latGrid.setUnit(Unit.DEGREES);

            TiePointGrid lonGrid = new TiePointGrid("longitude", gridWidth, gridHeight, 0.5f, 0.5f,
                    subSamplingX, subSamplingY, lonTiePoints, TiePointGrid.DISCONT_AT_180);
            lonGrid.setUnit(Unit.DEGREES);

            product.addTiePointGrid(latGrid);
            product.addTiePointGrid(lonGrid);
            product.setSceneGeoCoding(new TiePointGeoCoding(latGrid, lonGrid));
        } catch (Exception e) {
            org.esa.snap.core.util.SystemUtils.LOG.warning("Error creating SME2 geocoding: " + e.getMessage());
        }
    }
}