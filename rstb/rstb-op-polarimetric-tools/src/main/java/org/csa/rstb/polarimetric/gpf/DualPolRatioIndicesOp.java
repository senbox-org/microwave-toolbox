/*
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
package org.csa.rstb.polarimetric.gpf;

import com.bc.ceres.core.ProgressMonitor;
import org.csa.rstb.polarimetric.gpf.support.DualPolProcessor;
import eu.esa.sar.commons.polsar.PolBandUtils;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
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
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Compute dual-pol ratio-based indices on a C2 input product.
 *
 * Outputs (selectable):
 *   RFDI  = (c11 - c22) / (c11 + c22)               — Mitchard et al. 2012
 *   CR    = c22 / c11                               — cross-ratio (HV/HH or VH/VV)
 *   CRdB  = 10·log10(c22 / c11)                     — cross-ratio in decibels
 *   span  = c11 + c22                               — total intensity
 *
 * Sign convention follows the C2 layout produced by PolarimetricMatricesOp:
 *   For HH+HV input,  c11 = |HH|², c22 = |HV|² → RFDI follows the original definition.
 *   For VV+VH input,  c11 = |VV|², c22 = |VH|² → the same formula yields a co/cross
 *                     intensity contrast index, widely used with Sentinel-1.
 *
 * Reference:
 * Mitchard, E.T.A., Saatchi, S.S., White, L.J.T., et al., 2012. Mapping tropical
 *   forest biomass with radar and spaceborne LiDAR in Lopé National Park, Gabon:
 *   overcoming problems of high biomass and persistent cloud. Carbon Balance Manag. 7, 1.
 */
@OperatorMetadata(alias = "Dual-Pol-Ratio-Indices",
        category = "Radar/Polarimetric/Dual Polarimetry",
        authors = "SkyWatch",
        version = "1.0",
        copyright = "Copyright (C) 2026 by SkyWatch Space Applications Inc.",
        description = "Dual-pol ratio-based indices (RFDI, Cross-Ratio, span)")
public final class DualPolRatioIndicesOp extends Operator implements DualPolProcessor {

    @SourceProduct(alias = "source")
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(description = "The sliding window size", interval = "[1, 100]", defaultValue = "1", label = "Window Size")
    private int windowSize = 1;

    @Parameter(description = "Output RFDI = (c11-c22)/(c11+c22)", defaultValue = "true", label = "RFDI")
    private boolean outputRFDI = true;

    @Parameter(description = "Output Cross-Ratio = c22/c11", defaultValue = "true", label = "Cross-Ratio")
    private boolean outputCR = true;

    @Parameter(description = "Output Cross-Ratio in dB = 10·log10(c22/c11)", defaultValue = "false", label = "Cross-Ratio (dB)")
    private boolean outputCRdB = false;

    @Parameter(description = "Output span = c11 + c22", defaultValue = "false", label = "Span")
    private boolean outputSpan = false;

    private int halfWindowSize;
    private int sourceImageWidth;
    private int sourceImageHeight;

    private PolBandUtils.MATRIX sourceProductType;
    private PolBandUtils.PolSourceBand[] srcBandList;

    private static final String RFDI = "RFDI";
    private static final String CR = "CR";
    private static final String CR_DB = "CR_dB";
    private static final String SPAN = "Span";

    @Override
    public void initialize() throws OperatorException {
        try {
            final InputProductValidator validator = new InputProductValidator(sourceProduct);
            validator.checkIfSARProduct();

            sourceProductType = PolBandUtils.getSourceProductType(sourceProduct);
            if (sourceProductType != PolBandUtils.MATRIX.C2) {
                throw new OperatorException("Dual-pol C2 matrix source product is expected.");
            }

            if (!outputRFDI && !outputCR && !outputCRdB && !outputSpan) {
                throw new OperatorException("At least one output index must be selected.");
            }

            srcBandList = PolBandUtils.getSourceBands(sourceProduct, sourceProductType);
            halfWindowSize = windowSize / 2;
            sourceImageWidth = sourceProduct.getSceneRasterWidth();
            sourceImageHeight = sourceProduct.getSceneRasterHeight();

            createTargetProduct();
            updateTargetProductMetadata();
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private void createTargetProduct() {
        targetProduct = new Product(sourceProduct.getName(),
                sourceProduct.getProductType(),
                sourceProduct.getSceneRasterWidth(),
                sourceProduct.getSceneRasterHeight());

        addSelectedBands();
        ProductUtils.copyProductNodes(sourceProduct, targetProduct);
    }

    private String[] getSelectedBandNames() {
        final List<String> names = new ArrayList<>(4);
        if (outputRFDI) names.add(RFDI);
        if (outputCR) names.add(CR);
        if (outputCRdB) names.add(CR_DB);
        if (outputSpan) names.add(SPAN);
        return names.toArray(new String[0]);
    }

    private void addSelectedBands() {
        final String[] targetBandNames = getSelectedBandNames();
        for (PolBandUtils.PolSourceBand bandList : srcBandList) {
            final Band[] targetBands = OperatorUtils.addBands(targetProduct, targetBandNames, bandList.suffix);
            bandList.addTargetBands(targetBands);
        }
    }

    private void updateTargetProductMetadata() throws Exception {
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(targetProduct);
        if (absRoot != null) {
            absRoot.setAttributeInt(AbstractMetadata.polsarData, 1);
        }
        PolBandUtils.saveNewBandNames(targetProduct, srcBandList);
    }

    @Override
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle targetRectangle, ProgressMonitor pm)
            throws OperatorException {

        final int x0 = targetRectangle.x;
        final int y0 = targetRectangle.y;
        final int w = targetRectangle.width;
        final int h = targetRectangle.height;
        final int maxY = y0 + h;
        final int maxX = x0 + w;

        final double[][] Cr = new double[2][2];
        final double[][] Ci = new double[2][2];

        for (final PolBandUtils.PolSourceBand bandList : srcBandList) {
            try {
                final TileData[] tileDataList = new TileData[bandList.targetBands.length];
                int i = 0;
                for (Band targetBand : bandList.targetBands) {
                    final Tile targetTile = targetTiles.get(targetBand);
                    tileDataList[i++] = new TileData(targetTile, targetBand.getName());
                }
                final TileIndex tgtIndex = new TileIndex(tileDataList[0].tile);

                final Tile[] sourceTiles = new Tile[bandList.srcBands.length];
                final ProductData[] dataBuffers = new ProductData[bandList.srcBands.length];
                final Rectangle sourceRectangle = getSourceRectangle(x0, y0, w, h);

                for (int j = 0; j < bandList.srcBands.length; j++) {
                    sourceTiles[j] = getSourceTile(bandList.srcBands[j], sourceRectangle);
                    dataBuffers[j] = sourceTiles[j].getDataBuffer();
                }

                for (int y = y0; y < maxY; ++y) {
                    tgtIndex.calculateStride(y);
                    for (int x = x0; x < maxX; ++x) {
                        final int tgtIdx = tgtIndex.getIndex(x);

                        if (windowSize <= 1) {
                            final TileIndex srcIndex = new TileIndex(sourceTiles[0]);
                            srcIndex.calculateStride(y);
                            getCovarianceMatrixC2(srcIndex.getIndex(x), dataBuffers, Cr, Ci);
                        } else {
                            getMeanCovarianceMatrixC2(x, y, halfWindowSize, halfWindowSize, sourceImageWidth,
                                    sourceImageHeight, sourceProductType, sourceTiles, dataBuffers, Cr, Ci);
                        }

                        final double c11 = Cr[0][0];
                        final double c22 = Cr[1][1];
                        final double sum = c11 + c22;

                        for (final TileData tileData : tileDataList) {
                            final float v;
                            switch (tileData.bandName.contains(RFDI) ? 0
                                    : tileData.bandName.contains(CR_DB) ? 2
                                    : tileData.bandName.contains(CR) ? 1
                                    : tileData.bandName.contains(SPAN) ? 3
                                    : -1) {
                                case 0: v = sum > 0 ? (float) ((c11 - c22) / sum) : Float.NaN; break;
                                case 1: v = c11 > 0 ? (float) (c22 / c11) : Float.NaN; break;
                                case 2: v = (c11 > 0 && c22 > 0) ? (float) (10.0 * Math.log10(c22 / c11)) : Float.NaN; break;
                                case 3: v = (float) sum; break;
                                default: continue;
                            }
                            tileData.dataBuffer.setElemFloatAt(tgtIdx, v);
                        }
                    }
                }
            } catch (Throwable e) {
                OperatorUtils.catchOperatorException(getId(), e);
            }
        }
    }

    private Rectangle getSourceRectangle(final int tx0, final int ty0, final int tw, final int th) {
        final int x0 = Math.max(0, tx0 - halfWindowSize);
        final int y0 = Math.max(0, ty0 - halfWindowSize);
        final int xMax = Math.min(tx0 + tw - 1 + halfWindowSize, sourceImageWidth - 1);
        final int yMax = Math.min(ty0 + th - 1 + halfWindowSize, sourceImageHeight - 1);
        return new Rectangle(x0, y0, xMax - x0 + 1, yMax - y0 + 1);
    }

    private static class TileData {
        final Tile tile;
        final ProductData dataBuffer;
        final String bandName;

        TileData(final Tile tile, final String bandName) {
            this.tile = tile;
            this.dataBuffer = tile.getDataBuffer();
            this.bandName = bandName;
        }
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(DualPolRatioIndicesOp.class);
        }
    }
}
