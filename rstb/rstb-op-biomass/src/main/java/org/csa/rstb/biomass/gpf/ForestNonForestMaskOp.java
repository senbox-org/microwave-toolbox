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
 * Forest / non-forest binary mask from SAR backscatter (Sentinel-1
 * C-band or ALOS-2 L-band).
 *
 * <p>Reference: Cartus, O., &amp; Santoro, M. (2020). <i>Assessing
 * Forest/Non-Forest Separability Using Sentinel-1 C-Band Synthetic Aperture
 * Radar.</i> Remote Sensing 12(11), 1899. DOI:
 * <a href="https://doi.org/10.3390/rs12111899">10.3390/rs12111899</a></p>
 *
 * <p><b>Algorithm (v1, rule-based):</b></p>
 * <ol>
 *   <li>Threshold the cross-pol channel (VH for Sentinel-1, HV for ALOS-2).
 *       Forest typically returns VH &gt; -19 dB (S-1) or HV &gt; -16 dB
 *       (PALSAR-2). Below threshold the pixel is classified non-forest.</li>
 *   <li>Optionally apply a co-pol/cross-pol ratio test
 *       (VV/VH for S-1, HH/HV for PALSAR-2). Persistent water and bare
 *       soil have high ratio; forest is intermediate.</li>
 *   <li>Optionally apply a temporal-stability test using a coefficient of
 *       variation (CV = std / mean) band when available. Forest has low
 *       CV (stable backscatter year-round); croplands and barren land
 *       have high CV.</li>
 * </ol>
 *
 * <p>Inputs:</p>
 * <ul>
 *   <li>Cross-pol backscatter (VH or HV), dB or linear (selectable).</li>
 *   <li>Optional co-pol backscatter (VV or HH).</li>
 *   <li>Optional CV band (temporal coefficient of variation).</li>
 * </ul>
 *
 * <p>Output: <code>forestMask</code> band, UINT8, 1 = forest, 0 = non-forest.
 * No-data inputs yield no-data output (255).</p>
 *
 * <p>v1 is rule-based; a Random Forest / SVM classifier (Cartus 2020 Fig.
 * 7) is the recommended v2 upgrade once a training-data ingestion pipeline
 * is wired in.</p>
 */
@OperatorMetadata(alias = "Forest-NonForest-Mask",
        category = "Radar/Biomass",
        authors = "SkyWatch",
        version = "1.0",
        copyright = "Copyright (C) 2026 by SkyWatch Space Applications Inc.",
        description = "Rule-based forest / non-forest binary mask from SAR backscatter (Cartus & Santoro 2020).")
public final class ForestNonForestMaskOp extends Operator {

    @SourceProduct(alias = "source")
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(description = "Cross-polarisation band (VH for Sentinel-1, HV for ALOS-2).",
            rasterDataNodeType = Band.class,
            label = "Cross-pol Band (VH / HV)")
    private String crossPolBandName;

    @Parameter(description = "Optional co-polarisation band (VV / HH) for ratio test.",
            rasterDataNodeType = Band.class,
            label = "Co-pol Band (VV / HH, Optional)")
    private String coPolBandName;

    @Parameter(description = "Optional temporal coefficient-of-variation band (std/mean of multi-temporal stack).",
            rasterDataNodeType = Band.class,
            label = "Temporal CV Band (Optional)")
    private String cvBandName;

    @Parameter(description = "True if input backscatter bands are in dB; false for linear.",
            defaultValue = "true",
            label = "Inputs in dB")
    private boolean inputIsDb = true;

    @Parameter(description = "Cross-pol forest threshold in dB. Pixels with cross-pol below this are non-forest. "
            + "Default -19 dB suits S-1 boreal/temperate. Use ~-16 dB for ALOS-2 PALSAR-2.",
            defaultValue = "-19.0",
            label = "Cross-pol Threshold [dB]")
    private double crossPolThresholdDb = -19.0;

    @Parameter(description = "Max co-pol / cross-pol ratio (dB) for forest. Higher = excludes urban / dry-soil. "
            + "Use 12 dB for tropics, 15 dB for boreal. Set to 100 to disable.",
            defaultValue = "12.0",
            label = "Max Co/Cross Ratio [dB]")
    private double maxRatioDb = 12.0;

    @Parameter(description = "Max temporal coefficient of variation for forest (0.0 disables). "
            + "Cartus 2020 uses ~0.5 for S-1 IW annual.",
            defaultValue = "0.0",
            label = "Max Temporal CV")
    private double maxCV = 0.0;

    private static final int FOREST = 1;
    private static final int NON_FOREST = 0;
    private static final int NO_DATA = 255;
    private static final String PRODUCT_SUFFIX = "_FNF";
    private static final String MASK_BAND_NAME = "forestMask";

    private Band crossPolBand;
    private Band coPolBand;
    private Band cvBand;
    private Band maskBand;
    private double crossPolNoDataValue = Double.NaN;

    @Override
    public void initialize() throws OperatorException {
        try {
            if (crossPolBandName == null || crossPolBandName.isEmpty()) {
                crossPolBand = findCrossPolBand(sourceProduct);
                if (crossPolBand == null) {
                    throw new OperatorException("No cross-pol (VH/HV) band found. Specify crossPolBandName explicitly.");
                }
            } else {
                crossPolBand = sourceProduct.getBand(crossPolBandName);
                if (crossPolBand == null) {
                    throw new OperatorException("Cross-pol band '" + crossPolBandName + "' not found.");
                }
            }
            crossPolNoDataValue = crossPolBand.isNoDataValueUsed() ? crossPolBand.getNoDataValue() : Double.NaN;

            if (coPolBandName != null && !coPolBandName.isEmpty()) {
                coPolBand = sourceProduct.getBand(coPolBandName);
                if (coPolBand == null) {
                    throw new OperatorException("Co-pol band '" + coPolBandName + "' not found.");
                }
            }
            if (cvBandName != null && !cvBandName.isEmpty()) {
                cvBand = sourceProduct.getBand(cvBandName);
                if (cvBand == null) {
                    throw new OperatorException("CV band '" + cvBandName + "' not found.");
                }
            }

            createTargetProduct();
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private static Band findCrossPolBand(final Product src) {
        for (Band b : src.getBands()) {
            final String name = b.getName().toUpperCase();
            if ((name.contains("SIGMA0") || name.contains("GAMMA0") || name.contains("BETA0"))
                    && (name.contains("VH") || name.contains("HV"))) {
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

        maskBand = targetProduct.addBand(MASK_BAND_NAME, ProductData.TYPE_UINT8);
        maskBand.setUnit("class");
        maskBand.setNoDataValue(NO_DATA);
        maskBand.setNoDataValueUsed(true);
        maskBand.setDescription("Forest / non-forest mask: 1=forest, 0=non-forest. (Cartus 2020 rule-based v1)");
    }

    @Override
    public void computeTile(final Band targetBand, final Tile targetTile, final ProgressMonitor pm)
            throws OperatorException {
        try {
            final Rectangle rect = targetTile.getRectangle();

            final Tile crossPolTile = getSourceTile(crossPolBand, rect);
            final ProductData crossPolData = crossPolTile.getDataBuffer();
            final TileIndex crossPolIndex = new TileIndex(crossPolTile);

            final Tile coPolTile = (coPolBand != null) ? getSourceTile(coPolBand, rect) : null;
            final ProductData coPolData = (coPolTile != null) ? coPolTile.getDataBuffer() : null;
            final TileIndex coPolIndex = (coPolTile != null) ? new TileIndex(coPolTile) : null;

            final Tile cvTile = (cvBand != null) ? getSourceTile(cvBand, rect) : null;
            final ProductData cvData = (cvTile != null) ? cvTile.getDataBuffer() : null;
            final TileIndex cvIndex = (cvTile != null) ? new TileIndex(cvTile) : null;

            final ProductData tgtData = targetTile.getDataBuffer();
            final TileIndex tgtIndex = new TileIndex(targetTile);

            final int x0 = rect.x;
            final int y0 = rect.y;
            final int xMax = x0 + rect.width;
            final int yMax = y0 + rect.height;

            for (int y = y0; y < yMax; y++) {
                crossPolIndex.calculateStride(y);
                tgtIndex.calculateStride(y);
                if (coPolIndex != null) coPolIndex.calculateStride(y);
                if (cvIndex != null) cvIndex.calculateStride(y);

                for (int x = x0; x < xMax; x++) {
                    final double crossPol = crossPolData.getElemDoubleAt(crossPolIndex.getIndex(x));

                    final int cls;
                    if (Double.isNaN(crossPol) || crossPol == crossPolNoDataValue) {
                        cls = NO_DATA;
                    } else {
                        final double crossPolDb = inputIsDb ? crossPol : 10.0 * Math.log10(Math.max(crossPol, 1.0e-12));
                        boolean forest = crossPolDb >= crossPolThresholdDb;

                        if (forest && coPolData != null) {
                            final double coPol = coPolData.getElemDoubleAt(coPolIndex.getIndex(x));
                            final double coPolDb = inputIsDb ? coPol : 10.0 * Math.log10(Math.max(coPol, 1.0e-12));
                            final double ratioDb = coPolDb - crossPolDb;
                            if (ratioDb > maxRatioDb) {
                                forest = false;
                            }
                        }

                        if (forest && cvData != null && maxCV > 0.0) {
                            final double cv = cvData.getElemDoubleAt(cvIndex.getIndex(x));
                            if (cv > maxCV) {
                                forest = false;
                            }
                        }

                        cls = forest ? FOREST : NON_FOREST;
                    }
                    tgtData.setElemIntAt(tgtIndex.getIndex(x), cls);
                }
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        } finally {
            pm.done();
        }
    }

    /**
     * Per-pixel decision rule, exposed for unit testing.
     *
     * @param crossPolDb cross-pol backscatter in dB
     * @param coPolDb    co-pol backscatter in dB (NaN to skip ratio test)
     * @param cv         coefficient of variation (NaN to skip CV test)
     * @return {@link #FOREST} or {@link #NON_FOREST}
     */
    int classify(final double crossPolDb, final double coPolDb, final double cv) {
        if (crossPolDb < crossPolThresholdDb) return NON_FOREST;
        if (!Double.isNaN(coPolDb)) {
            final double ratioDb = coPolDb - crossPolDb;
            if (ratioDb > maxRatioDb) return NON_FOREST;
        }
        if (!Double.isNaN(cv) && maxCV > 0.0 && cv > maxCV) return NON_FOREST;
        return FOREST;
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(ForestNonForestMaskOp.class);
        }
    }
}
