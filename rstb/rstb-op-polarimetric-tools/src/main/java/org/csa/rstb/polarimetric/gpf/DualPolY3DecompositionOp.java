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
 * Three-component dual-pol model-based decomposition (Y3-DP / iDPSV) on a C2 product.
 *
 * <p>Decomposes the dual-pol covariance matrix into surface (Ps), double-bounce (Pd) and
 * volume (Pv) scattering powers. The volume term is parameterised by a normalised matrix
 * V = [[a, 0], [0, b]] whose amplitude f_v is fixed by matching the cross-pol intensity:</p>
 * <pre>
 *   f_v = c22 / b
 *   Pv  = f_v · (a + b)        (= 2·c22 for the uniform random-volume model)
 * </pre>
 *
 * <p>After volume removal the residual matrix Cr = C2 − f_v·V has the property Cr[1][1] = 0
 * (rank-1 residual). The branch decision between surface and double-bounce follows the
 * Freeman–Durden convention applied to dual-pol:</p>
 * <ul>
 *   <li>Re(c12) ≥ 0 → surface dominant: Ps = trace(Cr), Pd = 0.</li>
 *   <li>Re(c12) &lt; 0 → double-bounce dominant: Pd = trace(Cr), Ps = 0.</li>
 * </ul>
 *
 * <p>If the residual diagonal trace is negative (volume over-estimated), it is clipped to
 * zero and the cross-pol-derived volume is reduced accordingly.</p>
 *
 * <p>Volume models offered:</p>
 * <table border="1">
 *   <tr><th>Model</th><th>(a, b)</th><th>Use</th></tr>
 *   <tr><td>Uniform random</td><td>(½, ½)</td><td>Default; symmetric scatterers (S1 IW VV+VH)</td></tr>
 *   <tr><td>Bragg-like</td><td>(2/3, 1/3)</td><td>Vegetation-over-surface</td></tr>
 *   <tr><td>Dipole cloud</td><td>(3/8, 1/8)</td><td>Oriented-dipole canopy (HH+HV)</td></tr>
 * </table>
 *
 * <p>References:</p>
 * <p>Mascolo, L., Cloude, S.R., Lopez-Martinez, C., 2022.
 *    Model-based decomposition of dual-pol SAR data: Application to Sentinel-1.
 *    IEEE TGRS, 60.</p>
 * <p>Mascolo, L., Lopez-Martinez, C., et al., 2024.
 *    Scattering power components from dual-pol Sentinel-1 SLC and GRD SAR data.
 *    ISPRS J. Photogramm. Remote Sens., 212, 289–305.</p>
 */
@OperatorMetadata(alias = "Dual-Pol-Y3-Decomposition",
        category = "Radar/Polarimetric/Dual Polarimetry",
        authors = "SkyWatch",
        version = "1.0",
        copyright = "Copyright (C) 2026 by SkyWatch Space Applications Inc.",
        description = "Y3-DP / iDPSV three-component dual-pol decomposition (Mascolo & Cloude 2022/2024)")
public final class DualPolY3DecompositionOp extends Operator implements DualPolProcessor {

    @SourceProduct(alias = "source")
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(valueSet = {"3", "5", "7", "9", "11", "13", "15", "17", "19"}, defaultValue = "5", label = "Window Size")
    private int windowSize = 5;

    public static final String VOLUME_UNIFORM = "Uniform Random";
    public static final String VOLUME_BRAGG = "Bragg-like";
    public static final String VOLUME_DIPOLE = "Dipole Cloud";

    @Parameter(valueSet = {VOLUME_UNIFORM, VOLUME_BRAGG, VOLUME_DIPOLE},
            defaultValue = VOLUME_UNIFORM, label = "Volume Model",
            description = "Volume scattering shape (sets the (a, b) diagonal of the normalized volume matrix)")
    private String volumeModel = VOLUME_UNIFORM;

    @Parameter(description = "Output Ps (surface power)", defaultValue = "true", label = "Surface Power Ps")
    private boolean outputPs = true;

    @Parameter(description = "Output Pd (double-bounce power)", defaultValue = "true", label = "Double-bounce Power Pd")
    private boolean outputPd = true;

    @Parameter(description = "Output Pv (volume power)", defaultValue = "true", label = "Volume Power Pv")
    private boolean outputPv = true;

    private int halfWindowSize;
    private int sourceImageWidth;
    private int sourceImageHeight;
    private PolBandUtils.MATRIX sourceProductType;
    private PolBandUtils.PolSourceBand[] srcBandList;

    private double volA;   // co-pol coefficient of normalized volume diagonal
    private double volB;   // cross-pol coefficient

    private static final String PS = "Ps_Y3DP";
    private static final String PD = "Pd_Y3DP";
    private static final String PV = "Pv_Y3DP";

    @Override
    public void initialize() throws OperatorException {
        try {
            new InputProductValidator(sourceProduct).checkIfSARProduct();

            sourceProductType = PolBandUtils.getSourceProductType(sourceProduct);
            if (sourceProductType != PolBandUtils.MATRIX.C2) {
                throw new OperatorException("Dual-pol C2 matrix source product is expected.");
            }
            if (!outputPs && !outputPd && !outputPv) {
                throw new OperatorException("At least one output power band must be selected.");
            }

            switch (volumeModel) {
                case VOLUME_UNIFORM: volA = 0.5;     volB = 0.5;     break;
                case VOLUME_BRAGG:   volA = 2.0/3.0; volB = 1.0/3.0; break;
                case VOLUME_DIPOLE:  volA = 3.0/8.0; volB = 1.0/8.0; break;
                default: throw new OperatorException("Unknown volume model: " + volumeModel);
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
                sourceImageWidth, sourceImageHeight);

        final List<String> names = new ArrayList<>(3);
        if (outputPs) names.add(PS);
        if (outputPd) names.add(PD);
        if (outputPv) names.add(PV);

        for (PolBandUtils.PolSourceBand bandList : srcBandList) {
            final Band[] bands = OperatorUtils.addBands(targetProduct, names.toArray(new String[0]), bandList.suffix);
            bandList.addTargetBands(bands);
        }
        ProductUtils.copyProductNodes(sourceProduct, targetProduct);
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
        final int maxY = y0 + targetRectangle.height;
        final int maxX = x0 + targetRectangle.width;
        final Rectangle sourceRectangle = getSourceRectangle(x0, y0, targetRectangle.width, targetRectangle.height);

        final double[][] Cr = new double[2][2];
        final double[][] Ci = new double[2][2];

        for (final PolBandUtils.PolSourceBand bandList : srcBandList) {
            try {
                final TileSink[] sinks = new TileSink[bandList.targetBands.length];
                int j = 0;
                for (Band tb : bandList.targetBands) {
                    sinks[j++] = new TileSink(targetTiles.get(tb), tb.getName());
                }
                final TileIndex tgtIndex = new TileIndex(sinks[0].tile);

                final Tile[] sourceTiles = new Tile[bandList.srcBands.length];
                final ProductData[] dataBuffers = new ProductData[bandList.srcBands.length];
                for (int i = 0; i < bandList.srcBands.length; i++) {
                    sourceTiles[i] = getSourceTile(bandList.srcBands[i], sourceRectangle);
                    dataBuffers[i] = sourceTiles[i].getDataBuffer();
                }

                for (int y = y0; y < maxY; ++y) {
                    tgtIndex.calculateStride(y);
                    for (int x = x0; x < maxX; ++x) {
                        final int tgtIdx = tgtIndex.getIndex(x);

                        getMeanCovarianceMatrixC2(x, y, halfWindowSize, halfWindowSize,
                                sourceImageWidth, sourceImageHeight, sourceProductType,
                                sourceTiles, dataBuffers, Cr, Ci);

                        final double c11 = Cr[0][0];
                        final double c22 = Cr[1][1];
                        final double c12r = Cr[0][1];
                        // c12i = Ci[0][1]; not needed for branch decision, only for residual phase

                        // Volume amplitude from cross-pol channel.
                        double fv = c22 / volB;
                        double pv = fv * (volA + volB);

                        // Residual diagonal after volume removal.
                        double residualCo = c11 - fv * volA;
                        double residualCross = c22 - fv * volB;   // ≈ 0 by construction

                        if (residualCo < 0) {
                            // Volume over-estimated. Clip volume to the maximum that keeps Ps+Pd ≥ 0
                            // i.e. fv = c11 / volA (so residualCo = 0). Keeps Pv proportional to volA+volB.
                            fv = c11 / volA;
                            pv = fv * (volA + volB);
                            residualCo = 0;
                            residualCross = c22 - fv * volB;
                            if (residualCross < 0) {
                                // Both diagonals exhausted by volume — pure-volume pixel.
                                pv = c11 + c22;
                                residualCross = 0;
                            }
                        }

                        final double residualTrace = Math.max(0, residualCo + residualCross);

                        double ps = 0, pd = 0;
                        if (residualTrace > 0) {
                            // Branch decision: Re(c12) ≥ 0 → surface; Re(c12) < 0 → double-bounce.
                            if (c12r >= 0) {
                                ps = residualTrace;
                            } else {
                                pd = residualTrace;
                            }
                        }

                        for (TileSink sink : sinks) {
                            final float v;
                            if (sink.bandName.contains(PS)) v = (float) ps;
                            else if (sink.bandName.contains(PD)) v = (float) pd;
                            else if (sink.bandName.contains(PV)) v = (float) pv;
                            else continue;
                            sink.dataBuffer.setElemFloatAt(tgtIdx, v);
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

    private static final class TileSink {
        final Tile tile;
        final ProductData dataBuffer;
        final String bandName;

        TileSink(final Tile tile, final String bandName) {
            this.tile = tile;
            this.dataBuffer = tile.getDataBuffer();
            this.bandName = bandName;
        }
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(DualPolY3DecompositionOp.class);
        }
    }
}
