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
package eu.esa.sar.ocean.worldwind.layers;

import gov.nasa.worldwind.awt.WorldWindowGLCanvas;
import gov.nasa.worldwind.event.SelectEvent;
import gov.nasa.worldwind.geom.Angle;
import gov.nasa.worldwind.geom.Position;
import gov.nasa.worldwind.render.Path;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.worldwind.layers.BaseLayer;
import org.esa.snap.worldwind.layers.WWLayer;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * ETAD visualization
 */
public class ETADProductLayer extends BaseLayer implements WWLayer {

    // keyed by the product itself: its name changes when it is renamed, and its reference number is reset
    // as soon as it is removed from the product manager, before the removal reaches this layer
    private final Map<Product, Path[]> outlineTable = Collections.synchronizedMap(new IdentityHashMap<>());

    public ETADProductLayer() {
        this.setName("S-1 ETAD");
    }

    @Override
    public void setSelectedProduct(final Product product) {
        super.setSelectedProduct(product);

        // without a selected product nothing is highlighted
        synchronized (outlineTable) {
            for (Map.Entry<Product, Path[]> entry : outlineTable.entrySet()) {
                final boolean highlight = entry.getKey() == selectedProduct;
                for (Path line : entry.getValue()) {
                    line.setHighlighted(highlight);
                }
            }
        }
    }

    @Override
    public Suitability getSuitability(Product product) {
        if(product.getProductType().equals("ETAD")) {
            return Suitability.INTENDED;
        }
        return Suitability.UNSUITABLE;
    }

    @Override
    public void addProduct(final Product product, WorldWindowGLCanvas wwd) {

        if(product.getProductType().equals("ETAD")) {
            if (this.outlineTable.containsKey(product))
                return;

            addOutline(product);
        }
    }

    private void addOutline(final Product product) {

        final MetadataElement etadBurstList = getElement(AbstractMetadata.getOriginalProductMetadata(product),
                                                         "annotation", "etadProduct", "etadBurstList");
        if (etadBurstList == null) {
            SystemUtils.LOG.warning("ETAD product " + product.getName() + " has no burst list");
            // remembered, so that the metadata is not parsed again every time the product is re-added
            outlineTable.put(product, new Path[0]);
            return;
        }

        final List<Path> polyLineList = new ArrayList<>();
        final MetadataElement[] burstElems = etadBurstList.getElements();
        for(MetadataElement burstElem : burstElems) {
            final MetadataElement spatialCoverage = getElement(burstElem, "burstCoverage", "spatialCoverage");
            if (spatialCoverage == null) {
                continue;
            }
            final MetadataElement[] coordinates = spatialCoverage.getElements();
            if (coordinates.length < 4) {
                continue;
            }

            final List<Position> positions = new ArrayList<>(5);
            positions.add(getPosition(coordinates[1]));
            positions.add(getPosition(coordinates[0]));
            positions.add(getPosition(coordinates[2]));
            positions.add(getPosition(coordinates[3]));
            positions.add(getPosition(coordinates[1]));
            if (positions.contains(null)) {
                continue;
            }

            Path polyLine = createPath(positions, WHITE_MATERIAL, GREEN_MATERIAL);
            polyLineList.add(polyLine);

            addRenderable(polyLine);
        }

        outlineTable.put(product, polyLineList.toArray(new Path[0]));
    }

    private static MetadataElement getElement(MetadataElement element, final String... path) {
        for (String name : path) {
            if (element == null) {
                return null;
            }
            element = element.getElement(name);
        }
        return element;
    }

    private Position getPosition(final MetadataElement coordinate) {
        final MetadataElement latitude = coordinate.getElement("latitude");
        final MetadataElement longitude = coordinate.getElement("longitude");
        if (latitude == null || longitude == null) {
            return null;
        }
        double lat = latitude.getAttributeDouble("latitude", Double.NaN);
        double lon = longitude.getAttributeDouble("longitude", Double.NaN);
        if (Double.isNaN(lat) || Double.isNaN(lon)) {
            return null;
        }

        return new Position(Angle.fromDegreesLatitude(lat), Angle.fromDegreesLongitude(lon), 0.0);
    }

    @Override
    public void removeProduct(final Product product) {
        final Path[] lineList = this.outlineTable.remove(product);
        if (lineList != null) {
            for (Path line : lineList) {
                this.removeRenderable(line);
            }
        }
    }

    @Override
    public JPanel getControlPanel(WorldWindowGLCanvas wwd) {
        return null;
    }
}