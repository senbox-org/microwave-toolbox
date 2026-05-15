/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. https://www.skywatch.com
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 */
package org.csa.rstb.polarimetric.gpf;

import com.bc.ceres.core.ProgressMonitor;
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

import java.awt.Rectangle;
import java.util.Map;

/**
 * Compact-Pol Non-Circularity Correction.
 *
 * Compensates for the residual phase imbalance delta between the H and V transmit
 * chains on a circular-hybrid (LCH/RCH) compact-pol acquisition. With delta != 0
 * the actual transmit polarization is slightly elliptical rather than circular,
 * which biases all downstream Stokes / m-chi / m-delta / H-alpha / CP-RVI
 * products. RCM CP SLC products carry a calibrated delta; CEOS-ARD CP products
 * have the correction already baked in but Level-1 SLCs do not.
 *
 * The correction is a phase rotation applied to the C2 cross-term:
 *   C12_corr = C12_obs * exp(j * delta)
 *   (C11, C22 are unchanged - intensities are invariant.)
 *
 * v1 takes delta as a user parameter (scene-constant). A future v2 will read
 * delta directly from RCM product metadata.
 *
 * Reference:
 *   Touzi R., Charbonneau F. (2014). Requirements on the calibration of
 *   Hybrid-Compact SAR. IGARSS 2014.
 *   Geldsetzer T., Arkett M., Charbonneau F. et al. (RCM CP calibration papers).
 */
@OperatorMetadata(alias = "CP-Non-Circularity-Correction",
        category = "Radar/Polarimetric/Compact Polarimetry",
        authors = "SkyWatch",
        version = "1.0",
        copyright = "Copyright (C) 2026 by SkyWatch Space Applications Inc.",
        description = "Correct compact-pol transmit phase imbalance (non-circular transmit) on a C2 covariance product.")
public final class CompactPolNonCircularityCorrectionOp extends Operator {

    @SourceProduct(alias = "source")
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(description = "Transmit phase imbalance delta between H and V transmit chains, in degrees. " +
            "Typical RCM values are a few degrees. Default 0 is a no-op.",
            defaultValue = "0.0",
            label = "Transmit Phase Imbalance (deg)")
    private double transmitPhaseImbalanceDeg = 0.0;

    private PolBandUtils.MATRIX sourceProductType = null;
    private PolBandUtils.PolSourceBand[] srcBandList;
    private double cosDelta = 1.0;
    private double sinDelta = 0.0;

    @Override
    public void initialize() throws OperatorException {
        try {
            final InputProductValidator validator = new InputProductValidator(sourceProduct);
            validator.checkIfSARProduct();

            sourceProductType = PolBandUtils.getSourceProductType(sourceProduct);
            if (sourceProductType != PolBandUtils.MATRIX.C2) {
                throw new OperatorException(
                        "Compact-pol C2 covariance matrix product is expected. " +
                                "If the input is a raw LCH/RCH SLC, first run Polarimetric-Matrices to build C2.");
            }

            srcBandList = PolBandUtils.getSourceBands(sourceProduct, sourceProductType);

            final double deltaRad = Math.toRadians(transmitPhaseImbalanceDeg);
            cosDelta = Math.cos(deltaRad);
            sinDelta = Math.sin(deltaRad);

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

        for (PolBandUtils.PolSourceBand bandList : srcBandList) {
            final Band[] targetBands = new Band[bandList.srcBands.length];
            for (int i = 0; i < bandList.srcBands.length; ++i) {
                final Band srcBand = bandList.srcBands[i];
                final String name = srcBand.getName();
                final Band tgtBand = new Band(name, ProductData.TYPE_FLOAT32,
                        sourceProduct.getSceneRasterWidth(),
                        sourceProduct.getSceneRasterHeight());
                tgtBand.setUnit(srcBand.getUnit());
                targetProduct.addBand(tgtBand);
                targetBands[i] = tgtBand;
            }
            bandList.addTargetBands(targetBands);
        }

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);
    }

    private void updateTargetProductMetadata() throws Exception {
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(targetProduct);
        if (absRoot != null) {
            absRoot.setAttributeInt(AbstractMetadata.polsarData, 1);
            absRoot.setAttributeDouble("transmit_phase_imbalance_deg", transmitPhaseImbalanceDeg);
        }
        PolBandUtils.saveNewBandNames(targetProduct, srcBandList);
    }

    @Override
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle targetRectangle, ProgressMonitor pm)
            throws OperatorException {

        final int x0 = targetRectangle.x;
        final int y0 = targetRectangle.y;
        final int maxY = y0 + targetRectangle.height;
        final int maxX = x0 + targetRectangle.width;

        for (final PolBandUtils.PolSourceBand bandList : srcBandList) {
            try {
                // Locate the four target/source bands by name. PolBandUtils orders them
                // [C11, C12_real, C12_imag, C22] but we resolve by name to be robust.
                Band c11Src = null, c12rSrc = null, c12iSrc = null, c22Src = null;
                Band c11Tgt = null, c12rTgt = null, c12iTgt = null, c22Tgt = null;
                for (int i = 0; i < bandList.srcBands.length; ++i) {
                    final String n = bandList.srcBands[i].getName();
                    if (n.startsWith("C11")) { c11Src = bandList.srcBands[i]; c11Tgt = bandList.targetBands[i]; }
                    else if (n.startsWith("C12_real")) { c12rSrc = bandList.srcBands[i]; c12rTgt = bandList.targetBands[i]; }
                    else if (n.startsWith("C12_imag")) { c12iSrc = bandList.srcBands[i]; c12iTgt = bandList.targetBands[i]; }
                    else if (n.startsWith("C22")) { c22Src = bandList.srcBands[i]; c22Tgt = bandList.targetBands[i]; }
                }
                if (c11Src == null || c12rSrc == null || c12iSrc == null || c22Src == null) {
                    throw new OperatorException("C2 covariance bands (C11, C12_real, C12_imag, C22) not found.");
                }

                final Tile c11SrcTile = getSourceTile(c11Src, targetRectangle);
                final Tile c12rSrcTile = getSourceTile(c12rSrc, targetRectangle);
                final Tile c12iSrcTile = getSourceTile(c12iSrc, targetRectangle);
                final Tile c22SrcTile = getSourceTile(c22Src, targetRectangle);

                final ProductData c11SrcBuf = c11SrcTile.getDataBuffer();
                final ProductData c12rSrcBuf = c12rSrcTile.getDataBuffer();
                final ProductData c12iSrcBuf = c12iSrcTile.getDataBuffer();
                final ProductData c22SrcBuf = c22SrcTile.getDataBuffer();

                final Tile c11TgtTile = targetTiles.get(c11Tgt);
                final Tile c12rTgtTile = targetTiles.get(c12rTgt);
                final Tile c12iTgtTile = targetTiles.get(c12iTgt);
                final Tile c22TgtTile = targetTiles.get(c22Tgt);

                final ProductData c11TgtBuf = c11TgtTile.getDataBuffer();
                final ProductData c12rTgtBuf = c12rTgtTile.getDataBuffer();
                final ProductData c12iTgtBuf = c12iTgtTile.getDataBuffer();
                final ProductData c22TgtBuf = c22TgtTile.getDataBuffer();

                final TileIndex srcIndex = new TileIndex(c11SrcTile);
                final TileIndex tgtIndex = new TileIndex(c11TgtTile);

                for (int y = y0; y < maxY; ++y) {
                    srcIndex.calculateStride(y);
                    tgtIndex.calculateStride(y);
                    for (int x = x0; x < maxX; ++x) {
                        final int si = srcIndex.getIndex(x);
                        final int ti = tgtIndex.getIndex(x);

                        // Intensities unchanged.
                        c11TgtBuf.setElemFloatAt(ti, c11SrcBuf.getElemFloatAt(si));
                        c22TgtBuf.setElemFloatAt(ti, c22SrcBuf.getElemFloatAt(si));

                        // Cross-term phase rotation: C12_corr = C12 * exp(j*delta)
                        final double cr = c12rSrcBuf.getElemDoubleAt(si);
                        final double ci = c12iSrcBuf.getElemDoubleAt(si);
                        c12rTgtBuf.setElemFloatAt(ti, (float) (cr * cosDelta - ci * sinDelta));
                        c12iTgtBuf.setElemFloatAt(ti, (float) (cr * sinDelta + ci * cosDelta));
                    }
                }
            } catch (Throwable e) {
                OperatorUtils.catchOperatorException(getId(), e);
            }
        }
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(CompactPolNonCircularityCorrectionOp.class);
        }
    }
}
