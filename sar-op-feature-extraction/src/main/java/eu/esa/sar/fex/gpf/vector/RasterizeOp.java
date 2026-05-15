/*
 * Copyright (C) 2016 by Array Systems Computing Inc. http://www.array.ca
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. https://www.skywatch.com
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
package eu.esa.sar.fex.gpf.vector;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.ProductNodeGroup;
import org.esa.snap.core.datamodel.VectorDataNode;
import org.esa.snap.core.datamodel.VirtualBand;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.util.VectorUtils;
import org.geotools.feature.FeatureCollection;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Rasterize polygon vectors into pixel-wise attribute bands.
 *
 * For each selected attribute on the source polygon vectors, emit a
 * target band where every pixel inside polygon <i>i</i> carries the
 * polygon's value for that attribute (or 1 when the operator is in
 * {@link #BINARIZE} mode and the attribute matches {@link #targetValue},
 * 0 otherwise). Pixels outside every polygon are written as
 * {@code -1} (no-data).
 *
 * Algorithm: classic GIS polygon rasterization (no paper). Implemented
 * via SNAP BandMaths virtual-band polygon-containment expressions, the
 * same approach used by {@link VectorAveragingOp}.
 */
@OperatorMetadata(alias = "Rasterize",
        category = "Vector",
        authors = "Cecilia Wong, Luis Veci",
        version = "1.0",
        copyright = "Copyright (C) 2016 by Array Systems Computing Inc.",
        description = "Convert polygon vectors into one or more attribute raster bands.")
public class RasterizeOp extends Operator {

    @SourceProduct(alias = "source")
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct = null;

    @Parameter
    private String[] selectedSourceBands;

    @Parameter
    private String[] selectedAttributes;

    @Parameter(valueSet = {SET_TO_VALUE, BINARIZE}, defaultValue = SET_TO_VALUE, label = "Method")
    private String method = SET_TO_VALUE;

    @Parameter(label = "Target value")
    private String targetValue;

    public static final String SET_TO_VALUE = "Set to attribute value";
    public static final String BINARIZE = "Binarize";

    public static final String tmpVirtBandName = "tmpVirtBand";

    private static final double NO_DATA_VALUE = -1;
    private static final double VALID = 1;
    private static final double INVALID = 0;

    private HashMap<String, Double[]> attributeToValuesArrayMap;
    private final ArrayList<Band> targetBands = new ArrayList<>();
    private VectorDataNode[] polygonVectorDataNodes;
    private HashMap<VectorDataNode, Integer> polygonIndexMap;

    private int virtBandCnt;
    private boolean binarizeMode = false;

    @Override
    public void initialize() throws OperatorException {
        try {
            virtBandCnt = 0;
            binarizeMode = method.equals(BINARIZE);
            createTargetProduct();
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private void createTargetProduct() {
        targetProduct = new Product(sourceProduct.getName(), sourceProduct.getProductType(),
                sourceProduct.getSceneRasterWidth(), sourceProduct.getSceneRasterHeight());
        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        final HashMap<VectorDataNode, SimpleFeature> map = new HashMap<>();
        polygonVectorDataNodes = getPolygonVectorDataNodes(map);

        polygonIndexMap = new HashMap<>();
        final SimpleFeature[] polygonSimpleFeatures = new SimpleFeature[polygonVectorDataNodes.length];
        for (int i = 0; i < polygonVectorDataNodes.length; i++) {
            polygonIndexMap.put(polygonVectorDataNodes[i], i);
            polygonSimpleFeatures[i] = map.get(polygonVectorDataNodes[i]);
        }

        attributeToValuesArrayMap = new HashMap<>();

        if (selectedAttributes != null && selectedAttributes.length > 0) {
            for (String attribute : selectedAttributes) {
                final Band targetBand = new Band(attribute,
                        ProductData.TYPE_FLOAT64,
                        targetProduct.getSceneRasterWidth(),
                        targetProduct.getSceneRasterHeight());
                targetBand.setNoDataValue(NO_DATA_VALUE);
                targetBand.setNoDataValueUsed(true);
                targetProduct.addBand(targetBand);
                targetBands.add(targetBand);

                final Double[] valArray = new Double[polygonSimpleFeatures.length];
                for (int i = 0; i < polygonSimpleFeatures.length; i++) {
                    valArray[i] = getAttributeValue(attribute, polygonSimpleFeatures[i]);
                }
                attributeToValuesArrayMap.put(attribute, valArray);
            }
        }
    }

    public static boolean hasFeatures(final VectorDataNode node) {
        final FeatureCollection<SimpleFeatureType, SimpleFeature> featureCollection = node.getFeatureCollection();
        return featureCollection != null && !featureCollection.isEmpty();
    }

    private VectorDataNode[] getPolygonVectorDataNodes(final HashMap<VectorDataNode, SimpleFeature> map) {
        final ArrayList<VectorDataNode> list = new ArrayList<>();
        final ProductNodeGroup<VectorDataNode> vectorGroup = sourceProduct.getVectorDataGroup();
        final int numNodes = vectorGroup.getNodeCount();
        for (int i = 0; i < numNodes; i++) {
            final VectorDataNode vectorDataNode = vectorGroup.get(i);
            if (hasFeatures(vectorDataNode)) {
                final FeatureCollection<SimpleFeatureType, SimpleFeature> featureCollection =
                        vectorDataNode.getFeatureCollection();
                final SimpleFeature simpleFeature = featureCollection.features().next();
                list.add(vectorDataNode);
                map.put(vectorDataNode, simpleFeature);
            }
        }
        if (list.isEmpty()) {
            throw new OperatorException("No vector polygons found");
        }
        return list.toArray(new VectorDataNode[0]);
    }

    private double getAttributeValue(final String attribute, final SimpleFeature simpleFeature) {
        if (simpleFeature.getAttribute(attribute) != null) {
            final String str = simpleFeature.getAttribute(attribute).toString();
            if (binarizeMode) {
                return targetValue.toLowerCase().contains(str.toLowerCase()) ? VALID : INVALID;
            }
            try {
                return str.trim().isEmpty() ? INVALID : Double.parseDouble(str);
            } catch (NumberFormatException e) {
                return INVALID;
            }
        }
        return binarizeMode && targetValue.isEmpty() ? VALID : INVALID;
    }

    private String getNextVirtualBandName() {
        return tmpVirtBandName + "_" + virtBandCnt++;
    }

    @Override
    public synchronized void dispose() {
        if (sourceProduct == null) return;
        final Band[] sourceBands = sourceProduct.getBands();
        for (Band srcBand : sourceBands) {
            if (srcBand.getName().contains(tmpVirtBandName)) {
                sourceProduct.removeBand(srcBand);
            }
        }
    }

    @Override
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle targetRectangle, ProgressMonitor pm)
            throws OperatorException {

        final int tx0 = targetRectangle.x;
        final int ty0 = targetRectangle.y;
        final int tw = targetRectangle.width;
        final int th = targetRectangle.height;
        final int maxy = ty0 + th;
        final int maxx = tx0 + tw;

        try {
            final Band srcBand = sourceProduct.getBands()[0];
            final VectorDataNode[] polygons = VectorUtils.getPolygonsForOneRectangle(
                    targetRectangle, srcBand.getGeoCoding(), polygonVectorDataNodes);

            if (polygons.length == 0) {
                for (Band b : targetBands) {
                    final Tile targetTile = targetTiles.get(b);
                    for (int y = ty0; y < maxy; y++) {
                        for (int x = tx0; x < maxx; x++) {
                            targetTile.getDataBuffer().setElemDoubleAt(targetTile.getDataBufferIndex(x, y), NO_DATA_VALUE);
                        }
                    }
                }
                return;
            }

            final String expression = VectorAveragingOp.getExpression(polygons, polygonIndexMap);

            final Band virtBand = new VirtualBand(getNextVirtualBandName(),
                    ProductData.TYPE_INT16,
                    targetProduct.getSceneRasterWidth(),
                    targetProduct.getSceneRasterHeight(),
                    expression);
            sourceProduct.addBand(virtBand);
            final Tile srcTile = getSourceTile(virtBand, targetRectangle);

            for (Band b : targetBands) {
                final Double[] values = attributeToValuesArrayMap.get(b.getName());
                final Tile targetTile = targetTiles.get(b);
                for (int y = ty0; y < maxy; y++) {
                    for (int x = tx0; x < maxx; x++) {
                        final int idx = srcTile.getDataBuffer().getElemIntAt(srcTile.getDataBufferIndex(x, y));
                        final double val = (idx >= 0) ? values[idx] : NO_DATA_VALUE;
                        targetTile.getDataBuffer().setElemDoubleAt(targetTile.getDataBufferIndex(x, y), val);
                    }
                }
            }

            sourceProduct.removeBand(virtBand);
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        } finally {
            pm.done();
        }
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(RasterizeOp.class);
        }
    }
}
