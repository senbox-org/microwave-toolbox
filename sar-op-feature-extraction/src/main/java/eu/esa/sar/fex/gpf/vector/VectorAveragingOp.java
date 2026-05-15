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
import org.esa.snap.core.datamodel.GeoCoding;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.ProductNodeGroup;
import org.esa.snap.core.datamodel.VectorDataNode;
import org.esa.snap.core.datamodel.VirtualBand;
import org.esa.snap.core.dataop.downloadable.StatusProgressMonitor;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.core.util.ThreadExecutor;
import org.esa.snap.core.util.ThreadRunnable;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.util.VectorUtils;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.locationtech.jts.geom.MultiPolygon;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Vector Averaging (zonal-statistics) operator.
 *
 * For each pixel in a source band, if the pixel falls inside one of the
 * selected polygon vectors, replace its value with the aggregate (mean,
 * min, max, or count) of all in-polygon source values. Pixels outside
 * every polygon are written as the source band's no-data value.
 *
 * The polygon-containment test is delegated to the SNAP BandMaths engine
 * by emitting a virtual-band expression of the form
 *   '&lt;polygonName1&gt;' ? &lt;index1&gt; : ('&lt;polygonName2&gt;' ? &lt;index2&gt; : ... )
 * so the heavy geometry work is shared with the rest of SNAP's vector
 * handling.
 *
 * Algorithm: classic GIS zonal statistics (no paper). Implemented by
 * Cecilia Wong / Luis Veci, Array Systems 2016.
 */
@OperatorMetadata(alias = "VectorAveraging",
        category = "Vector",
        authors = "Cecilia Wong, Luis Veci",
        version = "1.0",
        copyright = "Copyright (C) 2016 by Array Systems Computing Inc.",
        description = "Aggregate band values inside polygon vectors (mean / min / max / count) and write the aggregate to every pixel of the polygon.")
public class VectorAveragingOp extends Operator {

    @SourceProduct(alias = "source")
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct = null;

    @Parameter
    private String[] selectedSourceBands;

    @Parameter
    private String[] selectedVectors;

    @Parameter
    private String selectedMethod;

    public static final String USE_MEAN = "Mean";
    public static final String USE_MAX = "Max";
    public static final String USE_MIN = "Min";
    public static final String USE_COUNT = "Count";

    public static final String tmpVirtBandName = "tmpVirtBand";

    private static final int NOT_IN_POLYGON = -1;

    private int virtBandCnt;
    private VectorDataNode[] polygonVectorDataNodes;
    private HashMap<VectorDataNode, Integer> polygonVectorDataNodeToIndex;
    private HashMap<String, HashMap<Integer, Double>> polygonToAverageVal;
    private boolean averagesComputed = false;

    private final ArrayList<Band> targetBands = new ArrayList<>();

    @Override
    public void initialize() throws OperatorException {
        try {
            virtBandCnt = 0;
            createTargetProduct();
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private static String getFirstPartOfExpression(final String polygonName, final int polygonIdx) {
        return "'" + polygonName + "' ? " + polygonIdx + " : ";
    }

    /**
     * Build a BandMaths expression that maps each pixel to the index of
     * the first polygon (in {@code polygons}) that contains it, or
     * {@value #NOT_IN_POLYGON} when no polygon contains the pixel.
     */
    public static String getExpression(final VectorDataNode[] polygons,
                                       final HashMap<VectorDataNode, Integer> indexMap) {
        if (polygons == null || indexMap == null) {
            return null;
        }
        final VectorDataNode firstNode = polygons[0];
        String expression = getFirstPartOfExpression(firstNode.getName(), indexMap.get(firstNode)) + NOT_IN_POLYGON;
        for (int i = 1; i < polygons.length; i++) {
            final VectorDataNode nextNode = polygons[i];
            expression = getFirstPartOfExpression(nextNode.getName(), indexMap.get(nextNode)) + '(' + expression + ')';
        }
        return expression;
    }

    private VectorDataNode[] getPolygonVectorDataNodes() {
        final String[] selectedPolygonVectors =
                (selectedVectors != null && selectedVectors.length > 0) ? selectedVectors : getVectorslist(sourceProduct);

        final ArrayList<VectorDataNode> list = new ArrayList<>();
        final ProductNodeGroup<VectorDataNode> vectorGroup = sourceProduct.getVectorDataGroup();
        for (String name : selectedPolygonVectors) {
            list.add(vectorGroup.get(name));
        }
        if (list.isEmpty()) {
            throw new OperatorException("No polygons");
        }
        final VectorDataNode[] nodes = list.toArray(new VectorDataNode[0]);

        polygonVectorDataNodeToIndex = new HashMap<>();
        for (int i = 0; i < nodes.length; i++) {
            polygonVectorDataNodeToIndex.put(nodes[i], i);
        }
        return nodes;
    }

    public static boolean isPolygonNode(final VectorDataNode node) {
        final FeatureCollection<SimpleFeatureType, SimpleFeature> featureCollection = node.getFeatureCollection();
        final FeatureIterator<SimpleFeature> featureIterator = featureCollection.features();
        while (featureIterator.hasNext()) {
            final SimpleFeature simpleFeature = featureIterator.next();
            final java.util.List<Object> attributes = simpleFeature.getAttributes();
            for (Object obj : attributes) {
                if (obj instanceof MultiPolygon || obj instanceof org.locationtech.jts.geom.Polygon) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String[] getVectorslist(final Product sourceProduct) {
        final ArrayList<String> vecNames = new ArrayList<>();
        final ProductNodeGroup<VectorDataNode> vectorGroup = sourceProduct.getVectorDataGroup();
        for (int i = 0; i < vectorGroup.getNodeCount(); i++) {
            final VectorDataNode node = vectorGroup.get(i);
            if (isPolygonNode(node)) {
                vecNames.add(node.getName());
            }
        }
        return vecNames.isEmpty() ? null : vecNames.toArray(new String[0]);
    }

    public static String[] getSourceBands(final Product sourceProduct) {
        final String[] allSrcBands = sourceProduct.getBandNames();
        final ArrayList<String> list = new ArrayList<>();
        for (String s : allSrcBands) {
            if (!s.contains(tmpVirtBandName)) {
                list.add(s);
            }
        }
        return list.toArray(new String[0]);
    }

    private String[] getSelectedSourceBands() {
        return (selectedSourceBands != null && selectedSourceBands.length > 0)
                ? selectedSourceBands : getSourceBands(sourceProduct);
    }

    private void createTargetProduct() {
        targetProduct = new Product(sourceProduct.getName(), sourceProduct.getProductType(),
                sourceProduct.getSceneRasterWidth(), sourceProduct.getSceneRasterHeight());
        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        final String[] selectedSrcBands = getSelectedSourceBands();
        for (String srcBandName : selectedSrcBands) {
            final Band srcBand = sourceProduct.getBand(srcBandName);
            final Band targetBand = new Band(srcBandName,
                    srcBand.getDataType(),
                    srcBand.getRasterWidth(),
                    srcBand.getRasterHeight());
            targetBand.setNoDataValue((srcBand.isNoDataValueUsed() && srcBand.isNoDataValueSet())
                    ? srcBand.getNoDataValue() : Double.NaN);
            targetBand.setNoDataValueUsed(true);
            targetProduct.addBand(targetBand);
            targetBands.add(targetBand);
        }

        polygonVectorDataNodes = getPolygonVectorDataNodes();
    }

    private synchronized void computeAveragesForAllTargetBands() {
        if (averagesComputed) {
            return;
        }
        final String[] selectedSrcBands = getSelectedSourceBands();

        double defaultVal = 0.0;
        switch (selectedMethod) {
            case USE_MAX:
                defaultVal = Double.MIN_VALUE;
                break;
            case USE_MIN:
                defaultVal = Double.MAX_VALUE;
                break;
            default:
                break;
        }

        final HashMap<String, HashMap<Integer, Double>> polygonToAverageValTmp = new HashMap<>();
        final HashMap<String, HashMap<Integer, Integer>> numValues = new HashMap<>();
        for (String bandName : selectedSrcBands) {
            final HashMap<Integer, Double> map = new HashMap<>();
            final HashMap<Integer, Integer> map1 = new HashMap<>();
            for (int i = 0; i < polygonVectorDataNodes.length; i++) {
                map.put(i, defaultVal);
                map1.put(i, 0);
            }
            polygonToAverageValTmp.put(bandName, map);
            numValues.put(bandName, map1);
        }

        final boolean[] checkNoDataVal = new boolean[selectedSrcBands.length];
        final double[] noDataVal = new double[selectedSrcBands.length];
        for (int i = 0; i < checkNoDataVal.length; i++) {
            final Band srcBand = sourceProduct.getBand(selectedSrcBands[i]);
            checkNoDataVal[i] = srcBand.isNoDataValueUsed() && srcBand.isNoDataValueSet();
            noDataVal[i] = srcBand.getNoDataValue();
        }

        final Dimension tileSize = new Dimension(1024, 1024);
        final Rectangle[] tileRectangles = OperatorUtils.getAllTileRectangles(sourceProduct, tileSize, 0);

        final StatusProgressMonitor status = new StatusProgressMonitor(StatusProgressMonitor.TYPE.SUBTASK);
        status.beginTask("Computing average values for polygons... ", tileRectangles.length);

        final ThreadExecutor executor = new ThreadExecutor();

        final Band firstSrcBand = sourceProduct.getBand(selectedSrcBands[0]);
        final GeoCoding srcGeocoding = firstSrcBand.getGeoCoding();
        if (srcGeocoding == null) {
            throw new OperatorException("Geocoding is null");
        }

        try {
            for (int i = 0; i < tileRectangles.length; i++) {
                checkForCancellation();
                final Rectangle rectangle = tileRectangles[i];
                final String recVirtBandName = tmpVirtBandName + i;

                final VectorDataNode[] polygons = VectorUtils.getPolygonsForOneRectangle(
                        rectangle, srcGeocoding, polygonVectorDataNodes);
                if (polygons.length == 0) {
                    continue;
                }

                final ThreadRunnable worker = new ThreadRunnable() {
                    @Override
                    public void process() {
                        final int x0 = rectangle.x, y0 = rectangle.y;
                        final int w = rectangle.width, h = rectangle.height;
                        final int xMax = x0 + w, yMax = y0 + h;

                        final Band recVirtBand = new VirtualBand(recVirtBandName,
                                ProductData.TYPE_INT16,
                                targetProduct.getSceneRasterWidth(),
                                targetProduct.getSceneRasterHeight(),
                                getExpression(polygons, polygonVectorDataNodeToIndex));
                        sourceProduct.addBand(recVirtBand);
                        final Tile virtBandTile = getSourceTile(recVirtBand, rectangle);
                        final ProductData virtBandData = virtBandTile.getDataBuffer();
                        final Tile[] selectedSourceBandTiles = new Tile[selectedSrcBands.length];
                        final ProductData[] selectedSourceBandData = new ProductData[selectedSrcBands.length];
                        for (int k = 0; k < selectedSourceBandTiles.length; k++) {
                            selectedSourceBandTiles[k] =
                                    getSourceTile(sourceProduct.getBand(selectedSrcBands[k]), rectangle);
                            selectedSourceBandData[k] = selectedSourceBandTiles[k].getDataBuffer();
                        }

                        for (int y = y0; y < yMax; ++y) {
                            for (int x = x0; x < xMax; ++x) {
                                final int idx = virtBandData.getElemIntAt(virtBandTile.getDataBufferIndex(x, y));
                                if (idx < 0) continue;
                                for (int k = 0; k < selectedSrcBands.length; k++) {
                                    final double val = selectedSourceBandData[k].getElemDoubleAt(
                                            selectedSourceBandTiles[k].getDataBufferIndex(x, y));
                                    if (Double.isNaN(val) || (checkNoDataVal[k] && val == noDataVal[k])) {
                                        continue;
                                    }
                                    synchronized (polygonToAverageValTmp) {
                                        final double oldVal = polygonToAverageValTmp.get(selectedSrcBands[k]).get(idx);
                                        double newVal = oldVal;
                                        switch (selectedMethod) {
                                            case USE_MEAN: {
                                                newVal = val + oldVal;
                                                final int oldCnt = numValues.get(selectedSrcBands[k]).get(idx);
                                                numValues.get(selectedSrcBands[k]).replace(idx, oldCnt + 1);
                                                break;
                                            }
                                            case USE_MAX:
                                                if (val > oldVal) {
                                                    newVal = val;
                                                    numValues.get(selectedSrcBands[k]).put(idx, 1);
                                                }
                                                break;
                                            case USE_MIN:
                                                if (val < oldVal) {
                                                    newVal = val;
                                                    numValues.get(selectedSrcBands[k]).put(idx, 1);
                                                }
                                                break;
                                            case USE_COUNT:
                                                newVal = oldVal + 1;
                                                numValues.get(selectedSrcBands[k]).put(idx, 1);
                                                break;
                                            default:
                                                break;
                                        }
                                        polygonToAverageValTmp.get(selectedSrcBands[k]).replace(idx, newVal);
                                    }
                                }
                            }
                        }
                        sourceProduct.removeBand(recVirtBand);
                    }
                };

                executor.execute(worker);
                status.worked(1);
            }
            executor.complete();
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId() + " computeAverage ", e);
        } finally {
            status.done();
        }

        polygonToAverageVal = new HashMap<>();
        for (String bandName : selectedSrcBands) {
            final Band band = sourceProduct.getBand(bandName);
            final HashMap<Integer, Double> polygonPixelVals = polygonToAverageValTmp.get(bandName);
            final HashMap<Integer, Integer> numValuesForBand = numValues.get(bandName);
            final HashMap<Integer, Double> map = new HashMap<>();
            for (int polygonIdx : polygonPixelVals.keySet()) {
                double averageVal = band.getNoDataValue();
                final int numVal = numValuesForBand.get(polygonIdx);
                if (numVal > 0) {
                    averageVal = polygonPixelVals.get(polygonIdx);
                    if (USE_MEAN.equals(selectedMethod)) {
                        averageVal /= numVal;
                    }
                }
                map.put(polygonIdx, averageVal);
            }
            polygonToAverageVal.put(bandName, map);
        }
        averagesComputed = true;
    }

    private String getNextVirtualBandName() {
        return tmpVirtBandName + '_' + virtBandCnt++;
    }

    @Override
    public void dispose() {
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
            if (!averagesComputed) {
                computeAveragesForAllTargetBands();
            }

            final String[] selectedSrcBands = getSelectedSourceBands();
            final Band srcBand = sourceProduct.getBand(selectedSrcBands[0]);
            final GeoCoding srcGeocoding = srcBand.getGeoCoding();
            final VectorDataNode[] polygons = VectorUtils.getPolygonsForOneRectangle(
                    targetRectangle, srcGeocoding, polygonVectorDataNodes);

            if (polygons.length == 0) {
                return;
            }

            final String expression = getExpression(polygons, polygonVectorDataNodeToIndex);

            final Band virtBand;
            synchronized (this) {
                virtBand = new VirtualBand(getNextVirtualBandName(),
                        ProductData.TYPE_INT16,
                        targetProduct.getSceneRasterWidth(),
                        targetProduct.getSceneRasterHeight(),
                        expression);
                sourceProduct.addBand(virtBand);
            }
            final Tile virtBandTile = getSourceTile(virtBand, targetRectangle);
            final ProductData virtBandData = virtBandTile.getDataBuffer();

            for (Band b : targetBands) {
                final HashMap<Integer, Double> map = polygonToAverageVal.get(b.getName());
                final double noDataVal = b.getNoDataValue();

                final Tile targetTile = targetTiles.get(b);
                for (int y = ty0; y < maxy; y++) {
                    for (int x = tx0; x < maxx; x++) {
                        final int idx = virtBandData.getElemIntAt(virtBandTile.getDataBufferIndex(x, y));
                        final double val = (idx >= 0 && map.containsKey(idx)) ? map.get(idx) : noDataVal;
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
            super(VectorAveragingOp.class);
        }
    }
}
