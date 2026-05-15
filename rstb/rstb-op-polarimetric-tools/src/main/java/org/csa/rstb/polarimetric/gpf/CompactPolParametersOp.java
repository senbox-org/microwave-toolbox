/*
 * Copyright (C) 2018 SkyWatch Space Applications Inc. https://www.skywatch.com
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
import eu.esa.sar.commons.polsar.PolBandUtils;
import org.csa.rstb.polarimetric.gpf.support.CompactPolProcessor;
import org.csa.rstb.polarimetric.gpf.support.PolarimetricParameters;
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

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Compact-pol polarimetric parameter extractor.
 *
 * <p>Computes pixel-wise polarimetric parameters from a compact-pol
 * SLC or its precomputed 2x2 covariance (C2) product. v1 emits Span
 * only; Pedestal Height and RVI are not yet implemented for compact-pol
 * (the parameter extraction is in
 * {@link org.csa.rstb.polarimetric.gpf.support.PolarimetricParameters}).</p>
 *
 * <p>Reference: Raney 2007 (m/delta and m/chi decomposition framework),
 * Cloude 2007 (compact-pol theory). Span is the standard backscatter
 * total-power definition C11 + C22.</p>
 */
@OperatorMetadata(alias = "CP-Parameters",
        category = "Radar/Polarimetric/Compact Polarimetry",
        authors = "Jun Lu, Luis Veci",
        version = "1.0",
        copyright = "Copyright (C) 2018 SkyWatch Space Applications Inc.",
        description = "Compute compact-pol polarimetric parameters (Span; PedestalHeight / RVI reserved).")
public final class CompactPolParametersOp extends Operator implements CompactPolProcessor {

    private static final String PRODUCT_SUFFIX = "_CPP";

    @SourceProduct(alias = "source")
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(description = "Use mean coherency or covariance matrix", defaultValue = "true", label = "Use Mean Matrix")
    private boolean useMeanMatrix = true;

    @Parameter(valueSet = {"3", "5", "7", "9", "11", "13", "15", "17", "19"}, defaultValue = "5", label = "Window Size X")
    private String windowSizeXStr = "5";

    @Parameter(valueSet = {"3", "5", "7", "9", "11", "13", "15", "17", "19"}, defaultValue = "5", label = "Window Size Y")
    private String windowSizeYStr = "5";

    @Parameter(description = "Output Span", defaultValue = "true", label = "Span")
    private boolean outputSpan = true;

    // PedestalHeight and RVI are reserved for v2; the underlying helper
    // returns zero. Wired up but disabled to avoid emitting misleading
    // all-zero bands. To enable, also wire the @Parameter and resolve the
    // helper TODOs in PolarimetricParameters.
    private boolean outputPedestalHeight = false;
    private boolean outputRVI = false;

    private int windowSizeX = 0;
    private int windowSizeY = 0;
    private int sourceImageWidth = 0;
    private int sourceImageHeight = 0;
    private String compactMode = null;
    private boolean useRCMConvention = false;
    private PolBandUtils.MATRIX sourceProductType = null;
    private PolBandUtils.PolSourceBand[] srcBandList;

    @Override
    public void initialize() throws OperatorException {
        try {
            final InputProductValidator validator = new InputProductValidator(sourceProduct);
            validator.checkIfSARProduct();

            sourceProductType = PolBandUtils.getSourceProductType(sourceProduct);

            final boolean isCompactPol = sourceProductType == PolBandUtils.MATRIX.LCHCP
                    || sourceProductType == PolBandUtils.MATRIX.RCHCP
                    || sourceProductType == PolBandUtils.MATRIX.C2;
            if (!isCompactPol) {
                throw new OperatorException("A compact-pol product is expected.");
            }

            if (outputPedestalHeight || outputRVI) {
                throw new OperatorException("Only Span is currently available for compact-pol products.");
            }
            if (!outputSpan && !outputPedestalHeight && !outputRVI) {
                throw new OperatorException("Please select parameters to output.");
            }

            srcBandList = PolBandUtils.getSourceBands(sourceProduct, sourceProductType);

            windowSizeX = Integer.parseInt(windowSizeXStr);
            windowSizeY = Integer.parseInt(windowSizeYStr);
            sourceImageWidth = sourceProduct.getSceneRasterWidth();
            sourceImageHeight = sourceProduct.getSceneRasterHeight();

            getCompactMode();
            createTargetProduct();
            updateTargetProductMetadata();
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private void getCompactMode() {
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);
        if (absRoot != null) {
            compactMode = absRoot.getAttributeString(AbstractMetadata.compact_mode);
            if (!compactMode.equals(CompactPolProcessor.rch) && !compactMode.equals(CompactPolProcessor.lch)) {
                throw new OperatorException("Right/Left Circular Hybrid Mode is expected.");
            }
        }
        // RCM polarisation-convention flag — not surfaced by the new PolBandUtils API.
        // v1 PolarimetricParameters does not consume this flag (only Span is computed);
        // restore plumbing when PedestalHeight / RVI extraction is implemented.
        useRCMConvention = false;
    }

    private void createTargetProduct() {
        targetProduct = new Product(sourceProduct.getName() + PRODUCT_SUFFIX,
                sourceProduct.getProductType(),
                sourceProduct.getSceneRasterWidth(),
                sourceProduct.getSceneRasterHeight());
        addSelectedBands();
        ProductUtils.copyProductNodes(sourceProduct, targetProduct);
    }

    private void addSelectedBands() {
        final String[] targetBandNames = getTargetBandNames();
        for (PolBandUtils.PolSourceBand bandList : srcBandList) {
            final Band[] targetBands = OperatorUtils.addBands(targetProduct, targetBandNames, bandList.suffix);
            bandList.addTargetBands(targetBands);
        }
    }

    private String[] getTargetBandNames() {
        final List<String> targetBandNameList = new ArrayList<>(3);
        if (outputSpan) targetBandNameList.add("Span");
        if (outputPedestalHeight) targetBandNameList.add("PedestalHeight");
        if (outputRVI) targetBandNameList.add("RVI");
        return targetBandNameList.toArray(new String[0]);
    }

    private void updateTargetProductMetadata() {
        try {
            final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(targetProduct);
            if (absRoot != null) {
                absRoot.setAttributeInt(AbstractMetadata.polsarData, 1);
            }
            PolBandUtils.saveNewBandNames(targetProduct, srcBandList);
        } catch (Exception e) {
            throw new OperatorException(e);
        }
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

        final TileIndex trgIndex = new TileIndex(targetTiles.get(targetProduct.getBandAt(0)));

        final double[][] Cr = new double[2][2];
        final double[][] Ci = new double[2][2];

        final Rectangle sourceRectangle = getSourceTileRectangle(x0, y0, w, h, windowSizeX, windowSizeY);
        final int halfWindowSizeX = windowSizeX / 2;
        final int halfWindowSizeY = windowSizeY / 2;

        for (final PolBandUtils.PolSourceBand bandList : srcBandList) {
            try {
                final TileData[] tileDataList = new TileData[bandList.targetBands.length];
                int i = 0;
                for (Band targetBand : bandList.targetBands) {
                    final Tile targetTile = targetTiles.get(targetBand);
                    tileDataList[i++] = new TileData(targetTile, targetBand.getName());
                }

                final Tile[] sourceTiles = new Tile[bandList.srcBands.length];
                final ProductData[] dataBuffers = new ProductData[bandList.srcBands.length];
                for (int j = 0; j < bandList.srcBands.length; j++) {
                    sourceTiles[j] = getSourceTile(bandList.srcBands[j], sourceRectangle);
                    dataBuffers[j] = sourceTiles[j].getDataBuffer();
                }
                final TileIndex srcIndex = new TileIndex(sourceTiles[0]);

                for (int y = y0; y < maxY; ++y) {
                    trgIndex.calculateStride(y);
                    srcIndex.calculateStride(y);
                    for (int x = x0; x < maxX; ++x) {
                        final int tgtIdx = trgIndex.getIndex(x);

                        if (useMeanMatrix) {
                            getMeanCovarianceMatrixC2(x, y, halfWindowSizeX, halfWindowSizeY, sourceImageWidth,
                                    sourceImageHeight, sourceProductType, sourceTiles, dataBuffers, Cr, Ci);
                        } else {
                            getCovarianceMatrixC2(srcIndex.getIndex(x), sourceProductType, dataBuffers, Cr, Ci);
                        }

                        final PolarimetricParameters cp =
                                PolarimetricParameters.computePolarimetricParameters(Cr, Ci, compactMode, useRCMConvention);

                        for (final TileData tileData : tileDataList) {
                            if (outputSpan && tileData.bandName.equals("Span")) {
                                tileData.dataBuffer.setElemFloatAt(tgtIdx, (float) cp.Span);
                            }
                            if (outputPedestalHeight && tileData.bandName.equals("PedestalHeight")) {
                                tileData.dataBuffer.setElemFloatAt(tgtIdx, (float) cp.PedestalHeight);
                            }
                            if (outputRVI && tileData.bandName.equals("RVI")) {
                                tileData.dataBuffer.setElemFloatAt(tgtIdx, (float) cp.RVI);
                            }
                        }
                    }
                }
            } catch (Throwable e) {
                OperatorUtils.catchOperatorException(getId(), e);
            }
        }
    }

    private Rectangle getSourceTileRectangle(final int x0, final int y0, final int w, final int h,
                                             final int wx, final int wy) {
        int sx0 = x0;
        int sy0 = y0;
        int sw = w;
        int sh = h;
        final int halfWindowSizeX = wx / 2;
        final int halfWindowSizeY = wy / 2;

        if (x0 >= halfWindowSizeX) { sx0 -= halfWindowSizeX; sw += halfWindowSizeX; }
        if (y0 >= halfWindowSizeY) { sy0 -= halfWindowSizeY; sh += halfWindowSizeY; }
        if (x0 + w + halfWindowSizeX <= sourceImageWidth) sw += halfWindowSizeX;
        if (y0 + h + halfWindowSizeY <= sourceImageHeight) sh += halfWindowSizeY;

        return new Rectangle(sx0, sy0, sw, sh);
    }

    private static class TileData {
        final ProductData dataBuffer;
        final String bandName;
        TileData(final Tile tile, final String bandName) {
            this.dataBuffer = tile.getDataBuffer();
            this.bandName = bandName;
        }
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(CompactPolParametersOp.class);
        }
    }
}
