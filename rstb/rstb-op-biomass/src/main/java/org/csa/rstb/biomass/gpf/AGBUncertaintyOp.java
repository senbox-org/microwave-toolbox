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
package org.csa.rstb.biomass.gpf;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.datamodel.Band;
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
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;

import java.awt.Rectangle;
import java.util.Map;

/**
 * Above-Ground Biomass (AGB) uncertainty quantification by analytic
 * error-budget propagation.
 *
 * <p>Combines four uncorrelated error sources in quadrature:</p>
 * <ol>
 *   <li><b>SAR radiometric</b> (per-pixel backscatter standard error, dB),
 *       converted to a relative AGB error via the regression sensitivity
 *       coefficient {@link #sensitivityPerDb}.</li>
 *   <li><b>Regression-model residual</b> (RMSE of the AGB regression fit
 *       used upstream — Mitchard, BIOMASAR, etc.).</li>
 *   <li><b>Allometric equation</b> (relative uncertainty, %).</li>
 *   <li><b>Pixel-level spatial sampling</b> (relative %; effective number
 *       of independent looks in the AGB window).</li>
 * </ol>
 *
 * <p>The combined relative standard error at each pixel is</p>
 * <pre>
 *   sigma_AGB / AGB = sqrt( (s_rad * sigma_dB)^2
 *                         + (rmse_model / AGB)^2
 *                         + (sigma_alloRel)^2
 *                         + (sigma_spatialRel)^2 )
 * </pre>
 * <p>Outputs the absolute standard error {@code AGB_SE [Mg/ha]} and the
 * 90 % half-width confidence interval ({@code 1.645 * AGB_SE}).</p>
 *
 * <p>Reference: <i>IPCC 2024 Tier-1 forest biomass uncertainty
 * methodology.</i> Scientific Data 13, 107.
 * <a href="https://doi.org/10.1038/s41597-024-03930-9">10.1038/s41597-024-03930-9</a>.
 * Compatible with the uncertainty layers published by CCI Biomass v5 ATBD
 * (ESA Climate Change Initiative).</p>
 *
 * <p>This operator is intentionally agnostic to the upstream AGB
 * algorithm: it consumes an existing AGB band plus optional per-input
 * uncertainty bands and produces a single SE band plus a 90 % CI band.
 * For full Monte-Carlo propagation across non-linear chains, run the
 * upstream operators on perturbed inputs and combine outside this op.</p>
 */
@OperatorMetadata(alias = "AGB-Uncertainty",
        category = "Radar/Biomass",
        authors = "SkyWatch",
        version = "1.0",
        copyright = "Copyright (C) 2026 by SkyWatch Space Applications Inc.",
        description = "Analytic error-budget propagation for AGB maps (IPCC Tier-1 / CCI Biomass v5 style).")
public final class AGBUncertaintyOp extends Operator {

    @SourceProduct(alias = "source")
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(description = "AGB band [Mg/ha].",
            rasterDataNodeType = Band.class,
            label = "AGB Band")
    private String agbBandName;

    @Parameter(description = "Optional radiometric SE band [dB] (e.g. residual speckle stddev of backscatter).",
            rasterDataNodeType = Band.class,
            label = "Radiometric SE Band [dB] (Optional)")
    private String radioSEBandName;

    @Parameter(description = "Regression model sensitivity per dB. For AGB = a*exp(b*sigma0_dB), use b * 10/ln(10) ~ 0.23.",
            defaultValue = "0.23",
            label = "Sensitivity per dB")
    private double sensitivityPerDb = 0.23;

    @Parameter(description = "Fixed radiometric SE [dB] when no radiometric SE band is provided.",
            defaultValue = "0.5",
            label = "Fixed Radiometric SE [dB]")
    private double fixedRadioSE = 0.5;

    @Parameter(description = "Regression RMSE [Mg/ha] (absolute, from the upstream AGB regression fit).",
            defaultValue = "20.0",
            label = "Regression RMSE [Mg/ha]")
    private double regressionRMSE = 20.0;

    @Parameter(description = "Relative allometric equation uncertainty (e.g. 0.15 = 15%).",
            defaultValue = "0.15",
            label = "Allometric Rel. Uncertainty")
    private double allometricRelative = 0.15;

    @Parameter(description = "Relative spatial / sampling uncertainty (e.g. 0.10 = 10%).",
            defaultValue = "0.10",
            label = "Spatial Rel. Uncertainty")
    private double spatialRelative = 0.10;

    @Parameter(description = "z-multiplier for the CI band. 1.645 = 90% (default), 1.96 = 95%.",
            defaultValue = "1.645",
            label = "CI z-multiplier")
    private double ciZ = 1.645;

    private static final double NO_DATA_VALUE = -1.0;
    private static final String PRODUCT_SUFFIX = "_UNC";
    private static final String SE_BAND_NAME = "AGB_SE";
    private static final String CI_BAND_NAME = "AGB_CI";

    private Band agbBand;
    private Band radioSEBand;
    private Band seBand;
    private Band ciBand;
    private double agbNoData = Double.NaN;

    @Override
    public void initialize() throws OperatorException {
        try {
            if (agbBandName == null || agbBandName.isEmpty()) {
                agbBand = sourceProduct.getBand("AGB");
                if (agbBand == null) {
                    throw new OperatorException("AGB band not found. Specify agbBandName explicitly.");
                }
            } else {
                agbBand = sourceProduct.getBand(agbBandName);
                if (agbBand == null) {
                    throw new OperatorException("AGB band '" + agbBandName + "' not found.");
                }
            }
            agbNoData = agbBand.isNoDataValueUsed() ? agbBand.getNoDataValue() : Double.NaN;

            if (radioSEBandName != null && !radioSEBandName.isEmpty()) {
                radioSEBand = sourceProduct.getBand(radioSEBandName);
                if (radioSEBand == null) {
                    throw new OperatorException("Radiometric SE band '" + radioSEBandName + "' not found.");
                }
            }

            createTargetProduct();
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private void createTargetProduct() {
        targetProduct = new Product(sourceProduct.getName() + PRODUCT_SUFFIX,
                sourceProduct.getProductType(),
                sourceProduct.getSceneRasterWidth(),
                sourceProduct.getSceneRasterHeight());
        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        seBand = targetProduct.addBand(SE_BAND_NAME, ProductData.TYPE_FLOAT32);
        seBand.setUnit("Mg/ha");
        seBand.setNoDataValue(NO_DATA_VALUE);
        seBand.setNoDataValueUsed(true);
        seBand.setDescription("AGB 1-sigma standard error (quadrature error budget)");

        ciBand = targetProduct.addBand(CI_BAND_NAME, ProductData.TYPE_FLOAT32);
        ciBand.setUnit("Mg/ha");
        ciBand.setNoDataValue(NO_DATA_VALUE);
        ciBand.setNoDataValueUsed(true);
        ciBand.setDescription("AGB CI half-width = " + ciZ + " * SE");
    }

    @Override
    public void computeTileStack(final Map<Band, Tile> targetTileMap, final Rectangle rect,
                                 final ProgressMonitor pm) throws OperatorException {
        try {
            final Tile agbTile = getSourceTile(agbBand, rect);
            final ProductData agbData = agbTile.getDataBuffer();
            final TileIndex agbIndex = new TileIndex(agbTile);

            final Tile radioTile = (radioSEBand != null) ? getSourceTile(radioSEBand, rect) : null;
            final ProductData radioData = (radioTile != null) ? radioTile.getDataBuffer() : null;
            final TileIndex radioIndex = (radioTile != null) ? new TileIndex(radioTile) : null;

            final Tile seTile = targetTileMap.get(seBand);
            final Tile ciTile = targetTileMap.get(ciBand);
            final ProductData seData = seTile.getDataBuffer();
            final ProductData ciData = ciTile.getDataBuffer();
            final TileIndex tgtIndex = new TileIndex(seTile);

            final int x0 = rect.x;
            final int y0 = rect.y;
            final int xMax = x0 + rect.width;
            final int yMax = y0 + rect.height;

            for (int y = y0; y < yMax; y++) {
                agbIndex.calculateStride(y);
                tgtIndex.calculateStride(y);
                if (radioIndex != null) radioIndex.calculateStride(y);

                for (int x = x0; x < xMax; x++) {
                    final double agb = agbData.getElemDoubleAt(agbIndex.getIndex(x));

                    double se = NO_DATA_VALUE;
                    double ci = NO_DATA_VALUE;
                    if (!Double.isNaN(agb) && agb != agbNoData && agb > 0.0) {
                        final double radioSe = (radioData != null)
                                ? radioData.getElemDoubleAt(radioIndex.getIndex(x))
                                : fixedRadioSE;
                        se = computeStandardError(agb, radioSe);
                        ci = ciZ * se;
                    }
                    final int idx = tgtIndex.getIndex(x);
                    seData.setElemFloatAt(idx, (float) se);
                    ciData.setElemFloatAt(idx, (float) ci);
                }
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        } finally {
            pm.done();
        }
    }

    /**
     * Compute the absolute AGB standard error [Mg/ha] for a single pixel
     * with AGB value {@code agb} [Mg/ha] and radiometric standard error
     * {@code radioSeDb} [dB] using the configured error budget.
     */
    double computeStandardError(final double agb, final double radioSeDb) {
        // Relative contributions (each is sigma_x / agb)
        final double relRad = sensitivityPerDb * radioSeDb;
        final double relModel = regressionRMSE / agb;
        final double relAllometric = allometricRelative;
        final double relSpatial = spatialRelative;

        final double relTotal2 = relRad * relRad
                + relModel * relModel
                + relAllometric * relAllometric
                + relSpatial * relSpatial;

        return agb * Math.sqrt(relTotal2);
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(AGBUncertaintyOp.class);
        }
    }
}
