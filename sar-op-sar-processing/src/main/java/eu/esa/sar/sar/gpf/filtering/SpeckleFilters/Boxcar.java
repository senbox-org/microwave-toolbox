/*
 * Copyright (C) 2016 by Array Systems Computing Inc. http://www.array.ca
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
package eu.esa.sar.sar.gpf.filtering.SpeckleFilters;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;

import java.awt.*;
import java.util.Map;

/**
 * Boxcar Speckle Filter
 */
public class Boxcar implements SpeckleFilter {

    private final Operator operator;
    private final Product sourceProduct;
    private final Product targetProduct;
    private final int windowSizeX;
    private final int windowSizeY;
    private final int halfWindowSizeX;
    private final int halfWindowSizeY;
    private Map<String, String[]> targetBandNameToSourceBandName;

    public Boxcar(final Operator op, final Product srcProduct, final Product trgProduct, final int windowSizeX,
                final int windowSizeY, final Map<String, String[]> targetBandNameToSourceBandName) {

        this.operator = op;
        this.sourceProduct = srcProduct;
        this.targetProduct = trgProduct;
        this.windowSizeX = windowSizeX;
        this.windowSizeY = windowSizeY;
        this.halfWindowSizeX = windowSizeX / 2;
        this.halfWindowSizeY = windowSizeY / 2;
        this.targetBandNameToSourceBandName = targetBandNameToSourceBandName;
    }

    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) {

        try {
            final Rectangle targetTileRectangle = targetTile.getRectangle();
            final int x0 = targetTileRectangle.x;
            final int y0 = targetTileRectangle.y;
            final int w = targetTileRectangle.width;
            final int h = targetTileRectangle.height;
            final int xMax = x0 + w;
            final int yMax = y0 + h;
            //System.out.println("x0 = " + x0 + ", y0 = " + y0 + ", w = " + w + ", h = " + h);

            final String[] srcBandNames = targetBandNameToSourceBandName.get(targetBand.getName());
            final double[][] filteredTile = performFiltering(x0, y0, w, h, srcBandNames);

            final ProductData tgtData = targetTile.getDataBuffer();
            final TileIndex tgtIndex = new TileIndex(targetTile);

            for (int y = y0; y < yMax; ++y) {
                tgtIndex.calculateStride(y);
                final int yy = y - y0;
                for (int x = x0; x < xMax; ++x) {
                    tgtData.setElemDoubleAt(tgtIndex.getIndex(x), filteredTile[yy][x - x0]);
                }
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException("Boxcar", e);
        } finally {
            pm.done();
        }
    }

    public double[][] performFiltering(
            final int x0, final int y0, final int w, final int h, final String[] srcBandNames) {

        final double[][] filteredTile = new double[h][w];
        Band sourceBand1 = sourceProduct.getBand(srcBandNames[0]);
        int sourceImageWidth = sourceBand1.getRasterWidth();
        int sourceImageHeight = sourceBand1.getRasterHeight();

        final Rectangle sourceTileRectangle = getSourceTileRectangle(
                x0, y0, w, h, halfWindowSizeX, halfWindowSizeY, sourceImageWidth, sourceImageHeight);

        Tile sourceTile1 = operator.getSourceTile(sourceBand1, sourceTileRectangle);
        ProductData sourceData1 = sourceTile1.getDataBuffer();

        Band sourceBand2;
        Tile sourceTile2;
        ProductData sourceData2 = null;
        if (srcBandNames.length > 1) {
            sourceBand2 = sourceProduct.getBand(srcBandNames[1]);
            sourceTile2 = operator.getSourceTile(sourceBand2, sourceTileRectangle);
            sourceData2 = sourceTile2.getDataBuffer();
        }
        final Unit.UnitType bandUnit = Unit.getUnitType(sourceBand1);
        final double noDataValue = sourceBand1.getNoDataValue();
        final TileIndex srcIndex = new TileIndex(sourceTile1);
        final double[] neighborValues = new double[windowSizeX * windowSizeY];
        final boolean isComplex = bandUnit == Unit.UnitType.REAL || bandUnit == Unit.UnitType.IMAGINARY;

        final int xMax = x0 + w;
        final int yMax = y0 + h;
        for (int y = y0; y < yMax; ++y) {
            final int yy = y - y0;
            for (int x = x0; x < xMax; ++x) {
                final int xx = x - x0;

                final int numSamples = getNeighborValues(
                        x, y, sourceData1, sourceData2, srcIndex, noDataValue, isComplex,
                        windowSizeX, windowSizeY, sourceImageWidth, sourceImageHeight, neighborValues);

                if (numSamples > 0) {
                    filteredTile[yy][xx] = getMeanValue(neighborValues, numSamples, noDataValue);
                } else {
                    filteredTile[yy][xx] = noDataValue;
                }
            }
        }

        return filteredTile;
    }
}
