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
 * HV-Intensity Above-Ground Biomass (AGB) retrieval — empirical exponential
 * / power-law fit applied to a single HV cross-polarisation backscatter band.
 *
 * <p>Reference: Mitchard, E. T. A., Saatchi, S. S., Woodhouse, I. H. et al.
 * (2009). <i>Measuring biomass changes due to woody encroachment and
 * deforestation / degradation in a forest-savanna boundary region of central
 * Africa using multi-temporal L-band radar backscatter.</i> Remote Sensing
 * of Environment 113(7), 1453-1461. DOI:
 * <a href="https://doi.org/10.1016/j.rse.2009.03.001">10.1016/j.rse.2009.03.001</a>
 * </p>
 *
 * <p>Three model families are supported. Let {@code s} be the HV
 * backscatter; {@code s_lin} = linear (m^2/m^2 or unitless intensity),
 * {@code s_dB} = decibel value (10 log10(s_lin)).</p>
 *
 * <ul>
 *   <li><b>Mitchard exponential (default):</b> AGB = a * (1 - exp(-b * s_lin))</li>
 *   <li><b>Saatchi power law:</b> AGB = a * s_lin^b
 *       (equivalent to log(AGB) = log(a) + b * log(s_lin))</li>
 *   <li><b>Decibel exponential:</b> AGB = exp(a + b * s_dB)</li>
 * </ul>
 *
 * <p>Coefficients are region-specific; sensible pantropical defaults are
 * provided but users should re-calibrate against local field plots
 * (e.g., Chave et al. 2014 allometric AGB) before operational use.
 * Suggested starting points from Mitchard 2009 / Saatchi 2011:</p>
 * <ul>
 *   <li><i>Mitchard 2009 Cameroon woodland savanna, L-band HV:</i>
 *       a = 31.6, b = 0.069 (linear backscatter scaled to ~0..1).</li>
 *   <li><i>Pantropical L-band HV, log-log:</i> a = 30, b = 0.5 (Saatchi 2011 supp.).</li>
 * </ul>
 *
 * <p>Inputs:</p>
 * <ul>
 *   <li>One HV-pol backscatter band (linear or dB; see {@link #inputIsDb}).</li>
 *   <li>Optional forest mask: pixels not in forest are written as no-data.</li>
 * </ul>
 *
 * <p>Output: AGB band in Mg/ha (FLOAT32). Pixels with input no-data or
 * out-of-range HV are written as no-data (-1).</p>
 */
@OperatorMetadata(alias = "HV-Intensity-AGB-Fit",
        category = "Radar/Biomass",
        authors = "SkyWatch",
        version = "1.0",
        copyright = "Copyright (C) 2026 by SkyWatch Space Applications Inc.",
        description = "Empirical Above-Ground Biomass from L-band HV backscatter (Mitchard et al. 2009 / Saatchi et al. 2011).")
public final class HVIntensityAGBOp extends Operator {

    @SourceProduct(alias = "source")
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(description = "HV backscatter band (sigma0_HV / gamma0_HV).",
            rasterDataNodeType = Band.class,
            label = "HV Source Band")
    private String hvBandName;

    @Parameter(description = "Optional forest mask band. Non-zero = forest. If empty, no masking is applied.",
            rasterDataNodeType = Band.class,
            label = "Forest Mask Band")
    private String forestMaskBandName;

    @Parameter(description = "True if the HV band carries decibel values (10*log10 sigma0); false for linear.",
            defaultValue = "false",
            label = "HV in dB")
    private boolean inputIsDb = false;

    @Parameter(valueSet = {MODEL_MITCHARD_EXP, MODEL_SAATCHI_POWER, MODEL_DB_EXP},
            defaultValue = MODEL_MITCHARD_EXP,
            description = "AGB inversion model family",
            label = "Model")
    private String model = MODEL_MITCHARD_EXP;

    @Parameter(description = "Coefficient a (model-dependent; see help).",
            defaultValue = "31.6",
            label = "Coefficient a")
    private double coefA = 31.6;

    @Parameter(description = "Coefficient b (model-dependent; see help).",
            defaultValue = "0.069",
            label = "Coefficient b")
    private double coefB = 0.069;

    @Parameter(description = "Minimum HV (linear or dB depending on inputIsDb) below which AGB is masked.",
            defaultValue = "1.0e-6",
            label = "HV Floor")
    private double hvFloor = 1.0e-6;

    @Parameter(description = "Clip AGB to this maximum [Mg/ha] (saturation guard).",
            defaultValue = "500.0",
            label = "AGB Saturation Clip")
    private double agbClip = 500.0;

    public static final String MODEL_MITCHARD_EXP = "Mitchard 2009 exponential";
    public static final String MODEL_SAATCHI_POWER = "Saatchi 2011 power law";
    public static final String MODEL_DB_EXP = "Decibel exponential";

    private static final double NO_DATA_VALUE = -1.0;
    private static final String PRODUCT_SUFFIX = "_AGB";
    private static final String AGB_BAND_NAME = "AGB";

    private Band hvBand;
    private Band forestMaskBand;
    private Band agbBand;
    private double srcNoDataValue = Double.NaN;

    @Override
    public void initialize() throws OperatorException {
        try {
            if (hvBandName == null || hvBandName.isEmpty()) {
                final Band detected = findHVBand(sourceProduct);
                if (detected == null) {
                    throw new OperatorException("No HV band found in source product. "
                            + "Specify hvBandName explicitly.");
                }
                hvBand = detected;
            } else {
                hvBand = sourceProduct.getBand(hvBandName);
                if (hvBand == null) {
                    throw new OperatorException("HV source band '" + hvBandName + "' not found.");
                }
            }
            srcNoDataValue = hvBand.isNoDataValueUsed() ? hvBand.getNoDataValue() : Double.NaN;

            if (forestMaskBandName != null && !forestMaskBandName.isEmpty()) {
                forestMaskBand = sourceProduct.getBand(forestMaskBandName);
                if (forestMaskBand == null) {
                    throw new OperatorException("Forest mask band '" + forestMaskBandName + "' not found.");
                }
            }

            createTargetProduct();
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    /** Search the source product for an HV-cross-pol backscatter band. */
    private static Band findHVBand(final Product src) {
        for (Band b : src.getBands()) {
            final String name = b.getName();
            final String upper = name.toUpperCase();
            // Common Sentinel-1 / ALOS / generic naming patterns
            if ((upper.contains("SIGMA0") || upper.contains("GAMMA0") || upper.contains("BETA0"))
                    && upper.contains("HV")) {
                return b;
            }
        }
        // Fallback: any band with HV in the name
        for (Band b : src.getBands()) {
            if (b.getName().toUpperCase().contains("HV")) {
                return b;
            }
        }
        return null;
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
        agbBand.setDescription("Above-ground biomass from HV backscatter (" + model + ", a=" + coefA + ", b=" + coefB + ")");
    }

    @Override
    public void computeTile(final Band targetBand, final Tile targetTile, final ProgressMonitor pm)
            throws OperatorException {
        try {
            final Rectangle rect = targetTile.getRectangle();
            final Tile srcTile = getSourceTile(hvBand, rect);
            final ProductData srcData = srcTile.getDataBuffer();
            final TileIndex srcIndex = new TileIndex(srcTile);

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
                srcIndex.calculateStride(y);
                tgtIndex.calculateStride(y);
                if (maskIndex != null) maskIndex.calculateStride(y);
                for (int x = x0; x < xMax; x++) {
                    final double hv = srcData.getElemDoubleAt(srcIndex.getIndex(x));
                    final boolean isForest = maskData == null
                            || maskData.getElemIntAt(maskIndex.getIndex(x)) != 0;

                    double agb = NO_DATA_VALUE;
                    if (isForest && hv > hvFloor && hv != srcNoDataValue && !Double.isNaN(hv)) {
                        agb = invertAGB(hv);
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
     * Map HV backscatter to AGB. {@code hv} is in the band's native unit
     * (dB if {@link #inputIsDb} else linear). Model selection lives here so
     * the choice is a compile-time switch inside the hot loop after Java's
     * JIT inlines it.
     */
    double invertAGB(final double hv) {
        switch (model) {
            case MODEL_MITCHARD_EXP: {
                final double sLin = inputIsDb ? Math.pow(10.0, hv / 10.0) : hv;
                return coefA * (1.0 - Math.exp(-coefB * sLin));
            }
            case MODEL_SAATCHI_POWER: {
                final double sLin = inputIsDb ? Math.pow(10.0, hv / 10.0) : hv;
                if (sLin <= 0.0) return Double.NaN;
                return coefA * Math.pow(sLin, coefB);
            }
            case MODEL_DB_EXP: {
                final double sDb = inputIsDb ? hv : 10.0 * Math.log10(Math.max(hv, hvFloor));
                return Math.exp(coefA + coefB * sDb);
            }
            default:
                throw new OperatorException("Unknown model: " + model);
        }
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(HVIntensityAGBOp.class);
        }
    }
}
