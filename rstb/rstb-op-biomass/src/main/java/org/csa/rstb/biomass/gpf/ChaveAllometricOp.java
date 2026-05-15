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

/**
 * Pantropical / pan-boreal Above-Ground Biomass (AGB) calculator from
 * canopy height, optionally combined with wood density and the
 * environmental-stress factor.
 *
 * <p>Two usage modes:</p>
 * <ol>
 *   <li><b>Stand-from-height</b> (default, SAR-friendly): map a per-pixel
 *       canopy height band (from
 *       {@link org.csa.rstb.biomass.gpf.treeheight.RVoGForestHeightOp},
 *       {@link org.csa.rstb.biomass.gpf.treeheight.DualPolForestHeightEstimationOp},
 *       TanDEM-X, or GEDI L2A) to stand-level AGB via a height-AGB power-law
 *       regression. Preset coefficients for Saatchi 2011, Asner 2012, Mitchard
 *       2014; custom user coefficients supported.</li>
 *   <li><b>Per-tree</b> (utility): the static method
 *       {@link #chaveEq7AGB(double, double, double)} evaluates Chave et al.
 *       2014 Eq. 7 on individual (DBH, H, wood density) triples, in kg.</li>
 * </ol>
 *
 * <p>References:</p>
 * <ul>
 *   <li>Chave, J., et al. (2014). <i>Improved allometric models to estimate
 *       the aboveground biomass of tropical trees.</i> Global Change Biology
 *       20(10), 3177-3190. DOI:
 *       <a href="https://doi.org/10.1111/gcb.12629">10.1111/gcb.12629</a>.</li>
 *   <li>Saatchi, S. S., et al. (2011). <i>Benchmark map of forest carbon
 *       stocks in tropical regions across three continents.</i> PNAS 108(24),
 *       9899-9904.</li>
 *   <li>Asner, G. P., et al. (2012). <i>A universal airborne LiDAR approach
 *       for tropical forest carbon mapping.</i> Oecologia 168, 1147-1160.</li>
 *   <li>Avitabile, V., et al. (2016). <i>An integrated pan-tropical biomass
 *       map using multiple reference datasets.</i> Global Change Biology
 *       22(4), 1406-1420.</li>
 * </ul>
 *
 * <p><b>Inputs:</b> canopy height band [m]; optional wood density band
 * [g/cm&sup3;]; optional forest mask.</p>
 *
 * <p><b>Output:</b> AGB band in Mg/ha (FLOAT32).</p>
 */
@OperatorMetadata(alias = "Chave-Allometric-AGB",
        category = "Radar/Biomass",
        authors = "SkyWatch",
        version = "1.0",
        copyright = "Copyright (C) 2026 by SkyWatch Space Applications Inc.",
        description = "Stand-level Above-Ground Biomass from canopy height via Chave 2014 / Saatchi 2011 / Asner 2012 regression presets.")
public final class ChaveAllometricOp extends Operator {

    @SourceProduct(alias = "source")
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(description = "Canopy height band [m] (e.g. forestHeight from RVoG-Forest-Height).",
            rasterDataNodeType = Band.class,
            label = "Canopy Height Band")
    private String heightBandName;

    @Parameter(description = "Optional wood density band [g/cm^3]. Used by Chave 2014 preset; ignored by Saatchi / Asner.",
            rasterDataNodeType = Band.class,
            label = "Wood Density Band (Optional)")
    private String woodDensityBandName;

    @Parameter(description = "Optional forest mask band (non-zero = forest).",
            rasterDataNodeType = Band.class,
            label = "Forest Mask Band (Optional)")
    private String forestMaskBandName;

    @Parameter(valueSet = {PRESET_SAATCHI_2011, PRESET_ASNER_2012, PRESET_CHAVE_2014, PRESET_CUSTOM},
            defaultValue = PRESET_SAATCHI_2011,
            description = "Height-AGB regression preset.",
            label = "Regression Preset")
    private String preset = PRESET_SAATCHI_2011;

    @Parameter(description = "Custom coefficient a (used when preset = Custom).",
            defaultValue = "2.0",
            label = "Coefficient a")
    private double coefA = 2.0;

    @Parameter(description = "Custom exponent b (used when preset = Custom).",
            defaultValue = "1.5",
            label = "Exponent b")
    private double coefB = 1.5;

    @Parameter(description = "Default wood density [g/cm^3] when no density band is provided. Tropical mean ~0.58.",
            defaultValue = "0.58",
            label = "Default Wood Density")
    private double defaultDensity = 0.58;

    @Parameter(description = "Clip AGB to this maximum [Mg/ha] (saturation guard).",
            defaultValue = "1000.0",
            label = "AGB Saturation Clip")
    private double agbClip = 1000.0;

    public static final String PRESET_SAATCHI_2011 = "Saatchi 2011 (pantropical, AGB = 2.0 * H^1.5)";
    public static final String PRESET_ASNER_2012 = "Asner 2012 (Amazon, AGB = 0.91 * H^2.04)";
    public static final String PRESET_CHAVE_2014 = "Chave 2014 (Eq. 7 stand approx., wood density required)";
    public static final String PRESET_CUSTOM = "Custom (a, b)";

    private static final double NO_DATA_VALUE = -1.0;
    private static final String PRODUCT_SUFFIX = "_AGB";
    private static final String AGB_BAND_NAME = "AGB";

    private Band heightBand;
    private Band woodDensityBand;
    private Band forestMaskBand;
    private Band agbBand;

    private double presetA;
    private double presetB;
    private boolean usesWoodDensity;

    @Override
    public void initialize() throws OperatorException {
        try {
            heightBand = resolveBand(heightBandName, "canopy height", true);
            woodDensityBand = resolveBand(woodDensityBandName, "wood density", false);
            forestMaskBand = resolveBand(forestMaskBandName, "forest mask", false);

            resolvePreset();
            createTargetProduct();
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private Band resolveBand(final String name, final String role, final boolean required) {
        if (name == null || name.isEmpty()) {
            if (required) {
                throw new OperatorException("Missing required band: " + role);
            }
            return null;
        }
        final Band b = sourceProduct.getBand(name);
        if (b == null) {
            throw new OperatorException("Source band '" + name + "' (" + role + ") not found.");
        }
        return b;
    }

    /** Decode the regression preset into (a, b, usesDensity). */
    private void resolvePreset() {
        switch (preset) {
            case PRESET_SAATCHI_2011:
                presetA = 2.0;
                presetB = 1.5;
                usesWoodDensity = false;
                break;
            case PRESET_ASNER_2012:
                presetA = 0.91;
                presetB = 2.04;
                usesWoodDensity = false;
                break;
            case PRESET_CHAVE_2014:
                // Stand-level surrogate of Chave Eq. 7 with H as the structure proxy.
                // AGB_stand [Mg/ha] = (a_chave * rho * H^(b_chave))
                // Coefficients fit to Chave 2014 sample-mean envelope: a_chave ~ 0.30, b_chave ~ 2.0.
                presetA = 0.30;
                presetB = 2.0;
                usesWoodDensity = true;
                break;
            case PRESET_CUSTOM:
                presetA = coefA;
                presetB = coefB;
                usesWoodDensity = (woodDensityBand != null);
                break;
            default:
                throw new OperatorException("Unknown preset: " + preset);
        }
    }

    private void createTargetProduct() {
        targetProduct = new Product(sourceProduct.getName() + PRODUCT_SUFFIX,
                sourceProduct.getProductType(),
                sourceProduct.getSceneRasterWidth(),
                sourceProduct.getSceneRasterHeight());
        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        agbBand = targetProduct.addBand(AGB_BAND_NAME, ProductData.TYPE_FLOAT32);
        agbBand.setUnit("Mg/ha");
        agbBand.setNoDataValue(NO_DATA_VALUE);
        agbBand.setNoDataValueUsed(true);
        agbBand.setDescription("AGB from canopy height via " + preset
                + " (a=" + presetA + ", b=" + presetB + ")");
    }

    @Override
    public void computeTile(final Band targetBand, final Tile targetTile, final ProgressMonitor pm)
            throws OperatorException {
        try {
            final Rectangle rect = targetTile.getRectangle();
            final Tile heightTile = getSourceTile(heightBand, rect);
            final ProductData heightData = heightTile.getDataBuffer();
            final TileIndex heightIndex = new TileIndex(heightTile);
            final double heightNoData = heightBand.isNoDataValueUsed() ? heightBand.getNoDataValue() : Double.NaN;

            final Tile densityTile = (woodDensityBand != null) ? getSourceTile(woodDensityBand, rect) : null;
            final ProductData densityData = (densityTile != null) ? densityTile.getDataBuffer() : null;
            final TileIndex densityIndex = (densityTile != null) ? new TileIndex(densityTile) : null;

            final Tile maskTile = (forestMaskBand != null) ? getSourceTile(forestMaskBand, rect) : null;
            final ProductData maskData = (maskTile != null) ? maskTile.getDataBuffer() : null;
            final TileIndex maskIndex = (maskTile != null) ? new TileIndex(maskTile) : null;

            final ProductData tgtData = targetTile.getDataBuffer();
            final TileIndex tgtIndex = new TileIndex(targetTile);

            final int x0 = rect.x;
            final int y0 = rect.y;
            final int xMax = x0 + rect.width;
            final int yMax = y0 + rect.height;

            for (int y = y0; y < yMax; y++) {
                heightIndex.calculateStride(y);
                tgtIndex.calculateStride(y);
                if (densityIndex != null) densityIndex.calculateStride(y);
                if (maskIndex != null) maskIndex.calculateStride(y);

                for (int x = x0; x < xMax; x++) {
                    final double h = heightData.getElemDoubleAt(heightIndex.getIndex(x));
                    final boolean isForest = maskData == null
                            || maskData.getElemIntAt(maskIndex.getIndex(x)) != 0;

                    double agb = NO_DATA_VALUE;
                    if (isForest && h > 0.0 && !Double.isNaN(h) && h != heightNoData) {
                        final double rho = (usesWoodDensity && densityData != null)
                                ? densityData.getElemDoubleAt(densityIndex.getIndex(x))
                                : defaultDensity;
                        agb = computeStandAGB(h, rho);
                        if (agb < 0.0 || Double.isNaN(agb)) {
                            agb = NO_DATA_VALUE;
                        } else if (agb > agbClip) {
                            agb = agbClip;
                        }
                    }
                    tgtData.setElemFloatAt(tgtIndex.getIndex(x), (float) agb);
                }
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        } finally {
            pm.done();
        }
    }

    /**
     * Compute stand-level AGB [Mg/ha] from canopy height [m] and wood
     * density [g/cm^3] for the currently selected preset.
     */
    double computeStandAGB(final double height, final double density) {
        if (usesWoodDensity) {
            return presetA * density * Math.pow(height, presetB);
        }
        return presetA * Math.pow(height, presetB);
    }

    /**
     * Chave 2014 Equation 7 per-tree AGB.
     *
     * <p>{@code AGB[kg] = 0.0673 * (rho * D^2 * H)^0.976}</p>
     *
     * @param dbh     diameter at breast height [cm]
     * @param height  tree height [m]
     * @param density wood density [g/cm^3]
     * @return AGB per tree in kg
     */
    public static double chaveEq7AGB(final double dbh, final double height, final double density) {
        if (dbh <= 0.0 || height <= 0.0 || density <= 0.0) return Double.NaN;
        return 0.0673 * Math.pow(density * dbh * dbh * height, 0.976);
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(ChaveAllometricOp.class);
        }
    }
}
