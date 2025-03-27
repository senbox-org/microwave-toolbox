/*
 * Copyright (C) 2015 by Array Systems Computing Inc. http://www.array.ca
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
package eu.esa.sar.fex.gpf.oceantools;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * The oil spill clustering and discrimination operator. The pixels detected as oil spill area are first
 * clustered and then discriminated based on the size of the cluster.
 */
@OperatorMetadata(alias = "Oil-Spill-Clustering",
        category = "Radar/SAR Applications/Ocean/Oil Spill Detection",
        authors = "Jun Lu, Luis Veci",
        version = "1.0",
        copyright = "Copyright (C) 2015 by Array Systems Computing Inc.",
        description = "Remove small clusters from detected area.")
public class OilSpillClusteringOp extends Operator {

    @SourceProduct(alias = "source")
    private Product sourceProduct;
    @TargetProduct
    private Product targetProduct = null;

    @Parameter(description = "Minimum cluster size", defaultValue = "0.1", label = "Minimum Cluster Size (sq km)")
    private double minClusterSizeInKm2 = 0.1;

    private int minClusterSizeInPixels = 0;

    private MetadataElement absRoot = null;

    @Override
    public void initialize() throws OperatorException {
        try {
            absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);

            getPixelSpacings();

            createTargetProduct();

            OilSpillDetectionOp.addBitmasks(targetProduct);

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    /**
     * Get the range and azimuth spacings (in meter).
     *
     * @throws Exception when metadata is missing or equal to default no data value
     */
    private void getPixelSpacings() throws Exception {
        final double rangeSpacing = AbstractMetadata.getAttributeDouble(absRoot, AbstractMetadata.range_spacing);
        final double azimuthSpacing = AbstractMetadata.getAttributeDouble(absRoot, AbstractMetadata.azimuth_spacing);
        minClusterSizeInPixels = (int) (minClusterSizeInKm2 * Constants.oneMillion / (rangeSpacing * azimuthSpacing)) + 1;
    }

    /**
     * Create target product.
     */
    private void createTargetProduct() {

        targetProduct = new Product(sourceProduct.getName(),
                sourceProduct.getProductType(),
                sourceProduct.getSceneRasterWidth(),
                sourceProduct.getSceneRasterHeight());

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        addSelectedBands();
    }

    /**
     * Add the user selected bands to target product.
     *
     * @throws OperatorException The exceptions.
     */
    private void addSelectedBands() throws OperatorException {

        final Band[] bands = sourceProduct.getBands();
        final List<String> bandNameList = new ArrayList<>(sourceProduct.getNumBands());
        for (Band band : bands) {
            bandNameList.add(band.getName());
        }
        final String[] sourceBandNames = bandNameList.toArray(new String[0]);

        final Band[] sourceBands = OperatorUtils.getSourceBands(sourceProduct, sourceBandNames, false);

        for (Band srcBand : sourceBands) {

            final String srcBandName = srcBand.getName();
            if (!srcBandName.contains(OilSpillDetectionOp.OILSPILLMASK_NAME)) {

                final Band targetBand = ProductUtils.copyBand(srcBandName, sourceProduct, targetProduct, true);

            } else {
                ProductUtils.copyBand(srcBandName, sourceProduct, targetProduct, false);
            }
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
        try {
            final Rectangle targetTileRectangle = targetTile.getRectangle();
            final int tx0 = targetTileRectangle.x;
            final int ty0 = targetTileRectangle.y;
            final int tw = targetTileRectangle.width;
            final int th = targetTileRectangle.height;
            final ProductData trgData = targetTile.getDataBuffer();
            //System.out.println("tx0 = " + tx0 + ", ty0 = " + ty0 + ", tw = " + tw + ", th = " + th);

            final int x0 = Math.max(tx0 - minClusterSizeInPixels, 0);
            final int y0 = Math.max(ty0 - minClusterSizeInPixels, 0);
            final int w = Math.min(tw + 2 * minClusterSizeInPixels, targetBand.getRasterWidth());
            final int h = Math.min(th + 2 * minClusterSizeInPixels, targetBand.getRasterHeight());
            final Rectangle sourceTileRectangle = new Rectangle(x0, y0, w, h);
            //System.out.println("x0 = " + x0 + ", y0 = " + y0 + ", w = " + w + ", h = " + h);

            final Band sourceBand = sourceProduct.getBand(targetBand.getName());
            final Tile sourceTile = getSourceTile(sourceBand, sourceTileRectangle);
            final ProductData srcData = sourceTile.getDataBuffer();
            final int[][] pixelsScaned = new int[h][w];

            final TileIndex srcIndex = new TileIndex(sourceTile);

            final int maxy = ty0 + th;
            final int maxx = tx0 + tw;
            for (int ty = ty0; ty < maxy; ty++) {

                srcIndex.calculateStride(ty);
                for (int tx = tx0; tx < maxx; tx++) {

                    if (pixelsScaned[ty - y0][tx - x0] == 0 && srcData.getElemIntAt(srcIndex.getIndex(tx)) == 1) {

                        final List<PixelPos> clusterPixels = new ArrayList<>();

                        clustering(tx, ty, x0, y0, w, h, srcData, sourceTile, pixelsScaned, clusterPixels);

                        if (clusterPixels.size() >= minClusterSizeInPixels) {
                            for (PixelPos pixel : clusterPixels) {
                                final int x = (int) pixel.x;
                                final int y = (int) pixel.y;
                                if (x >= tx0 && x < tx0 + tw && y >= ty0 && y < ty0 + th) {
                                    trgData.setElemIntAt(targetTile.getDataBufferIndex(x, y), 1);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    /**
     * Find pixels detected as target in a 3x3 window centered at a given point.
     *
     * @param xc            The x coordinate of the given point.
     * @param yc            The y coordinate of the given point.
     * @param x0            The x coordinate for the upper left corner point of the source rectangle.
     * @param y0            The y coordinate for the upper left corner point of the source rectangle.
     * @param w             The width of the source rectangle.
     * @param h             The height of the source rectangle.
     * @param bitMaskData   The bit maks band data.
     * @param bitMaskTile   The bit mask band tile.
     * @param pixelsScaned  The binary array indicating which pixel in the tile has been scaned.
     * @param clusterPixels The list of pixels in the cluster.
     */
    private static void clustering(final int xc, final int yc, final int x0, final int y0, final int w, final int h,
                                   final ProductData bitMaskData, final Tile bitMaskTile,
                                   final int[][] pixelsScaned, final List<PixelPos> clusterPixels) {

        final List<PixelPos> seeds = new ArrayList<>();
        seeds.add(new PixelPos(xc, yc));
        pixelsScaned[yc - y0][xc - x0] = 1;
        clusterPixels.add(new PixelPos(xc, yc));

        while (seeds.size() > 0) {
            final List<PixelPos> newSeeds = new ArrayList<>();
            for (PixelPos pixel : seeds) {
                searchNeighbourhood(pixel, x0, y0, w, h, bitMaskData, bitMaskTile, pixelsScaned, clusterPixels, newSeeds);
            }
            seeds.clear();
            seeds.addAll(newSeeds);
        }
    }

    private static void searchNeighbourhood(final PixelPos pixel, final int x0, final int y0, final int w, final int h,
                                            final ProductData bitMaskData, final Tile bitMaskTile,
                                            final int[][] pixelsScaned, final List<PixelPos> clusterPixels,
                                            final List<PixelPos> newSeeds) {

        final int xc = (int) pixel.x;
        final int yc = (int) pixel.y;
        final int[] x = {xc - 1, xc, xc + 1, xc - 1, xc + 1, xc - 1, xc, xc + 1};
        final int[] y = {yc - 1, yc - 1, yc - 1, yc, yc, yc + 1, yc + 1, yc + 1};

        for (int i = 0; i < 8; i++) {
            if (x[i] >= x0 && x[i] < x0 + w && y[i] >= y0 && y[i] < y0 + h &&
                    pixelsScaned[y[i] - y0][x[i] - x0] == 0 &&
                    bitMaskData.getElemIntAt(bitMaskTile.getDataBufferIndex(x[i], y[i])) == 1) {

                pixelsScaned[y[i] - y0][x[i] - x0] = 1;
                clusterPixels.add(new PixelPos(x[i], y[i]));
                newSeeds.add(new PixelPos(x[i], y[i]));
            }
        }
    }

    /**
     * Operator SPI.
     */
    public static class Spi extends OperatorSpi {

        public Spi() {
            super(OilSpillClusteringOp.class);
        }
    }
}
