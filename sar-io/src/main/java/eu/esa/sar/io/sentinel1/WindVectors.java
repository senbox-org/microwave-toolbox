/*
 * Copyright (C) 2024 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.io.sentinel1;

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.PlainFeatureFactory;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.ProductNodeGroup;
import org.esa.snap.core.datamodel.VectorDataNode;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.util.VectorUtils;
import org.geotools.feature.DefaultFeatureCollection;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.AttributeDescriptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WindVectors {

    // These are for exporting wind data to ESRI Shape -----------------------------------------------------------------
    private static final String WIND_VECTOR_DATA_NODE_NAME = "wind_data";

    // Map a band name to a shapefile field name. Shapefile field names are limited to 10 characters.
    // One VectorDataNode for OSW and one for OWI.
    private final static Map<String, String> oswWindBandNameShpFieldNameMap;
    static {
        Map<String, String> aMap = new HashMap<>(6);
        aMap.put("oswLon", "oswLon");
        aMap.put("oswLat", "oswLat");
        aMap.put("oswWindSpeed", "oswWdSpd");
        aMap.put("oswWindDirection", "oswWdDir");
        aMap.put("oswWindSeaHs", "oswWdSeaHs");
        aMap.put("oswWaveAge", "oswWaveAge");
        oswWindBandNameShpFieldNameMap = Collections.unmodifiableMap(aMap);
    }

    private final static Map<String, String> owiWindBandNameShpFieldNameMap;
    static {
        Map<String, String> aMap = new HashMap<>(8);
        aMap.put("owiLon", "owiLon");
        aMap.put("owiLat", "owiLat");
        aMap.put("owiWindSpeed", "owiWdSpd");
        aMap.put("owiWindDirection", "owiWdDir");
        aMap.put("owiWindQuality", "owiWdQulty");
        aMap.put("owiEcmwfWindSpeed", "owiEcmwfWS");
        aMap.put("owiEcmwfWindDirection", "owiEcmwfWD");
        aMap.put("owiWindSeaHs", "owiWdSeaHs");
        owiWindBandNameShpFieldNameMap = Collections.unmodifiableMap(aMap);
    }

    private final Map<Band, String> bandToAttributeName =
            new HashMap<>(oswWindBandNameShpFieldNameMap.size() + owiWindBandNameShpFieldNameMap.size());

    private final int shapeSideLen = 25; // Number of points will be approximately square of this number
    private final Sentinel1OCNReader OCNReader;

    public WindVectors(final Sentinel1OCNReader OCNReader) {
        this.OCNReader = OCNReader;
    }

    public void addWindDataToVectorNodes(final Product product){

        // This is not expected to do anything but leave it anyhow.
        // osw data will be handled in addOSWDataToVectorNode()
        addOneWindDataToVectorNode(product, "osw");

        addOneWindDataToVectorNode(product, "owi");
    }

    public void addOneWindDataToVectorNode(final Product product, final String componentName){

        List<Band> windBands = new ArrayList<>();
        SimpleFeatureType windFeatureType = createWindSimpleFeatureType(product, componentName , windBands);

        if (windBands.isEmpty()) {
            SystemUtils.LOG.info("No " + componentName + " wind data bands");
            return;
        }

        //System.out.println("Sentinel1OCNReader.addWindToVectorNodes: total " + componentName + " wind bands = " + windBands.size());

        final int rasterH = windBands.get(0).getRasterHeight();
        final int rasterW = windBands.get(0).getRasterWidth();

        final int productRasterH = product.getSceneRasterHeight();
        final int productRasterW = product.getSceneRasterWidth();

        for (Band b : windBands) {
            //System.out.println("  " + componentName + " wind data band: " + b.getName());
            if (rasterH != b.getRasterHeight()) {
                SystemUtils.LOG.warning(componentName + " wind data bands have different raster height");
                return;
            } else if (rasterW != b.getRasterWidth()) {
                SystemUtils.LOG.warning(componentName + " wind data bands have different raster width");
                return;
            }
        }

        VectorDataNode windNode = new VectorDataNode(componentName + "_" + WIND_VECTOR_DATA_NODE_NAME, windFeatureType);

        //final FeatureCollection<SimpleFeatureType, SimpleFeature> collection = windNode.getFeatureCollection();
        final DefaultFeatureCollection collection = windNode.getFeatureCollection();

        final GeometryFactory geometryFactory = new GeometryFactory();

        final int xStepSize = Math.max(1, rasterW / shapeSideLen);
        final int yStepSize = Math.max(1, rasterH / shapeSideLen);
        //System.out.println("Sentinel1OCNReader.addWindToVectorNodes: xStepSize = " + xStepSize + " yStepSize = " + yStepSize);
        int i = 0;
        for (int x = 0; x < rasterW; x += xStepSize) {
            for (int y = 0; y < rasterH; y += yStepSize) {

                SimpleFeatureBuilder sfb = new SimpleFeatureBuilder(windFeatureType);

                // TODO The problem is that productRasterW and productRasterH can be wrong and equal to 99999 which messes
                // TODO up the geocoding. getScaledValue() will decide if scaling is needed.

                // org.locationtech.jts.geom.Point p = geometryFactory.createPoint(new Coordinate(x, y));
                final int x1 = getScaledValue(x, productRasterW, rasterW);
                final int y1 = getScaledValue(y, productRasterH, rasterH);
                org.locationtech.jts.geom.Point p = geometryFactory.createPoint(new Coordinate(x1, y1));

                //System.out.println("Sentinel1OCNReader.addWindToVectorNodes: (" + x + ", " + y + ") -> (" + x1 + ", " + y1 + ")");

                sfb.set(PlainFeatureFactory.ATTRIB_NAME_GEOMETRY, p);
                final SimpleFeature feature = sfb.buildFeature( componentName + "_wind_data_pt_" + i++);

                for (Band b : windBands) {

                    ProductData productData = b.createCompatibleProductData(1);
                    OCNReader.readData(x, y, 1, 1, 1, 1, b,
                            0, 0, 1, 1, productData);
                    double val = productData.getElemDoubleAt(0);

                    feature.setAttribute(bandToAttributeName.get(b), val);
                }

                collection.add(feature);
            }
        }

        //System.out.println("Sentinel1OCNReader.addWindToVectorNodes: total " + componentName + " wind data points = " + i);

        final ProductNodeGroup<VectorDataNode> vectorGroup = product.getVectorDataGroup();
        vectorGroup.add(windNode);
    }

    private int getScaledValue(final int val, final int w1, final int w2) {
        if (OCNReader.getSceneHeight() > 0) return val; // No need to scale if sceneHeight is available
        return (int) ( ( ((double) val) * ((double) w1 ) / ((double) w2) ) + 0.5);
    }

    private SimpleFeatureType createWindSimpleFeatureType(final Product product,
                                                          final String componentName, // osw or owi
                                                          final List<Band> windBands) {

        Map<String, String> map =
                componentName.equals("osw") ? oswWindBandNameShpFieldNameMap : owiWindBandNameShpFieldNameMap;

        final List<AttributeDescriptor> attributeDescriptors = new ArrayList<>();

        Band[] bands = product.getBands();
        for (Band band : bands) {
            for (String s : map.keySet()) {
                if (band.getName().contains(s)) {
                    final String attributeName = map.get(s);
                    attributeDescriptors.add(VectorUtils.createAttribute(attributeName, Double.class));
                    windBands.add(band);
                    bandToAttributeName.put(band, attributeName);
                }
            }
        }

        return VectorUtils.createFeatureType(product.getSceneGeoCoding(),
                componentName + " " + WIND_VECTOR_DATA_NODE_NAME, attributeDescriptors);
    }

    public void addOSWDataToVectorNode(final Product product) {

        // TODO
        // oswLon
        // oswLat
        // oswHs_1, oswHs_2, osw_Hs_n where n = oswPartitions
        // oswWI_1,
        // oswDirmet_1
        // oswWindSpeed
        // oswWindDirection
        // oswEcmwfWindSpeed
        // oswEcmwfWindDirection
        // oswWaveAge
        // oswWindSeaHs

        MetadataElement root = product.getMetadataRoot();
        //dumpElems("root metadata", root);

        MetadataElement originalProductMetadata = root.getElement(AbstractMetadata.ORIGINAL_PRODUCT_METADATA);
        //dumpElems(AbstractMetadata.ORIGINAL_PRODUCT_METADATA, originalProductMetadata);

    }

    private void dumpElems(final String name, final MetadataElement metadataElement) {
        if (metadataElement == null) {
            System.out.println(name + " is null");
            return;
        }
        String[] elementNames = metadataElement.getElementNames();
        for (String a : elementNames) {
            System.out.println(metadataElement.getName() + " elem = " + a);
        }
    }
}
