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
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.StackUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;

import java.awt.Rectangle;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * SAR forest-disturbance detection by per-pixel CUSUM (Cumulative Sum)
 * change-point detection on a multi-temporal SAR backscatter stack.
 *
 * <p>v1 algorithm: a simple one-sided CUSUM tuned to detect monotonic
 * backscatter drops typical of deforestation / clear-cut events. A
 * baseline period (first N acquisitions, default 12) supplies the
 * historical mean and std-dev; then the CUSUM statistic</p>
 * <pre>
 *   S(t) = max(0, S(t-1) + (mean0 - x(t) - k * std0))
 * </pre>
 * <p>accumulates evidence of a downward step. Disturbance is flagged the
 * first time S exceeds {@code h * std0}. Output: binary disturbance mask,
 * change-time index (acquisitions since start), and CUSUM magnitude at
 * the detected change.</p>
 *
 * <p><b>References:</b></p>
 * <ul>
 *   <li>Page, E. S. (1954). <i>Continuous Inspection Schemes.</i> Biometrika
 *       41, 100-115. (CUSUM origin.)</li>
 *   <li>Reiche, J., et al. (2021). <i>Forest disturbance alerts for the
 *       Congo Basin using Sentinel-1.</i> Environmental Research Letters
 *       16, 024005. (RADD algorithm, the operational reference.)</li>
 *   <li>Verbesselt, J., et al. (2010). <i>Detecting trend and seasonal
 *       changes in satellite image time series.</i> Remote Sensing of
 *       Environment 114, 106-115. (BFAST — full breakpoint detection,
 *       Tier 3 upgrade target.)</li>
 * </ul>
 *
 * <p>Input: coregistered multi-temporal stack of cross-pol backscatter
 * (S-1 VH or PALSAR-2 HV). The operator discovers per-acquisition bands
 * via {@link StackUtils#getReferenceBandNames(Product)} +
 * {@link StackUtils#getSecondaryProductNames(Product)} (same convention
 * used by InSAR / PhaseLinking).</p>
 *
 * <p>Output: <code>disturbanceMask</code> [0/1], <code>changeIndex</code>
 * (zero-based acquisition index of detected change), and
 * <code>cusumMag</code> (CUSUM value at the change point, useful for
 * confidence ranking).</p>
 *
 * <p>v2 / Tier 3 upgrade target: replace CUSUM with full BFAST
 * (seasonal harmonic + trend + iterative breakpoint).</p>
 */
@OperatorMetadata(alias = "SAR-Disturbance-Detection",
        category = "Radar/Biomass",
        authors = "SkyWatch",
        version = "1.0",
        copyright = "Copyright (C) 2026 by SkyWatch Space Applications Inc.",
        description = "CUSUM-based forest-disturbance detection on multi-temporal SAR backscatter (v1; Reiche 2021 RADD-style; BFAST upgrade in v2).")
public final class SARDisturbanceDetectionOp extends Operator {

    @SourceProduct(alias = "source")
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(description = "Number of leading acquisitions used to estimate baseline mean/std.",
            defaultValue = "12",
            label = "Baseline Length")
    private int baselineLength = 12;

    @Parameter(description = "CUSUM slack constant k (units of baseline std). "
            + "Smaller -> more sensitive but noisier.",
            defaultValue = "0.5",
            label = "CUSUM Slack k")
    private double cusumK = 0.5;

    @Parameter(description = "CUSUM detection threshold h (units of baseline std). "
            + "Larger -> fewer false alarms.",
            defaultValue = "5.0",
            label = "CUSUM Threshold h")
    private double cusumH = 5.0;

    @Parameter(description = "True if input bands are in dB; false for linear.",
            defaultValue = "true",
            label = "Inputs in dB")
    private boolean inputIsDb = true;

    @Parameter(description = "Minimum baseline std to attempt detection; below this the pixel is too noisy / too uniform.",
            defaultValue = "0.05",
            label = "Min Baseline Std")
    private double minBaselineStd = 0.05;

    private static final int NO_DISTURBANCE = 0;
    private static final int DISTURBED = 1;
    private static final int NO_DATA_MASK = 255;
    private static final double NO_DATA_VALUE = -1.0;
    private static final String PRODUCT_SUFFIX = "_DIST";
    private static final String MASK_BAND_NAME = "disturbanceMask";
    private static final String CHANGE_INDEX_BAND_NAME = "changeIndex";
    private static final String CUSUM_MAG_BAND_NAME = "cusumMag";

    /** Chronologically sorted per-acquisition source bands. */
    private Band[] sortedSrcBands;
    private Band maskBand;
    private Band changeIndexBand;
    private Band cusumMagBand;

    @Override
    public void initialize() throws OperatorException {
        try {
            sortedSrcBands = discoverChronologicalStack(sourceProduct);
            if (sortedSrcBands.length < baselineLength + 1) {
                throw new OperatorException("Need at least " + (baselineLength + 1)
                        + " acquisitions; found " + sortedSrcBands.length);
            }
            createTargetProduct();
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    /**
     * Discover the per-acquisition backscatter bands in a coregistered stack
     * product, sorted chronologically.
     *
     * <p>Uses the same SNAP convention as PhaseLinking / InSAR:
     * reference (master) bands come from
     * {@link StackUtils#getReferenceBandNames(Product)} and secondaries from
     * {@link StackUtils#getSecondaryProductNames(Product)}.</p>
     */
    static Band[] discoverChronologicalStack(final Product src) throws Exception {
        final DateFormat fmt = ProductData.UTC.createDateFormat("ddMMMyyyy");

        final List<Acq> acqs = new ArrayList<>();

        final MetadataElement refRoot = AbstractMetadata.getAbstractedMetadata(src);
        final String refDate = org.esa.snap.engine_utilities.gpf.OperatorUtils.getAcquisitionDate(refRoot);
        final Date refDateParsed = fmt.parse(refDate);
        final String[] refBandNames = StackUtils.getReferenceBandNames(src);
        addAcq(src, refBandNames, refDate, refDateParsed, acqs);

        final MetadataElement secondaryRoot = StackUtils.findSecondaryMetadataRoot(src);
        if (secondaryRoot != null) {
            for (String secProdName : StackUtils.getSecondaryProductNames(src)) {
                final MetadataElement secMeta = secondaryRoot.getElement(secProdName);
                if (secMeta == null) continue;
                final String date = org.esa.snap.engine_utilities.gpf.OperatorUtils.getAcquisitionDate(secMeta);
                final Date dateParsed = fmt.parse(date);
                final String[] secBandNames = StackUtils.getSecondaryBandNames(src, secProdName);
                addAcq(src, secBandNames, date, dateParsed, acqs);
            }
        }

        acqs.sort(Comparator.comparing(a -> a.date));
        final Band[] out = new Band[acqs.size()];
        for (int i = 0; i < out.length; i++) out[i] = acqs.get(i).band;
        return out;
    }

    private static void addAcq(final Product src, final String[] bandNames,
                               final String dateStr, final Date dateParsed,
                               final List<Acq> acqs) {
        for (String name : bandNames) {
            final String upper = name.toUpperCase();
            // Prefer cross-pol backscatter
            if ((upper.contains("VH") || upper.contains("HV"))
                    && (upper.contains("SIGMA0") || upper.contains("GAMMA0") || upper.contains("BETA0"))) {
                final Band b = src.getBand(name);
                if (b != null) {
                    acqs.add(new Acq(b, dateParsed));
                    return; // one band per acquisition
                }
            }
        }
        // Fallback: first band that matches the date
        for (String name : bandNames) {
            final Band b = src.getBand(name);
            if (b != null && (b.getName().contains(dateStr) || acqs.isEmpty())) {
                acqs.add(new Acq(b, dateParsed));
                return;
            }
        }
    }

    private static final class Acq {
        final Band band;
        final Date date;
        Acq(final Band band, final Date date) {
            this.band = band;
            this.date = date;
        }
    }

    private void createTargetProduct() {
        targetProduct = new Product(sourceProduct.getName() + PRODUCT_SUFFIX,
                sourceProduct.getProductType(),
                sourceProduct.getSceneRasterWidth(),
                sourceProduct.getSceneRasterHeight());
        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        maskBand = targetProduct.addBand(MASK_BAND_NAME, ProductData.TYPE_UINT8);
        maskBand.setUnit("class");
        maskBand.setNoDataValue(NO_DATA_MASK);
        maskBand.setNoDataValueUsed(true);
        maskBand.setDescription("Disturbance mask: 1=disturbed, 0=undisturbed");

        changeIndexBand = targetProduct.addBand(CHANGE_INDEX_BAND_NAME, ProductData.TYPE_INT16);
        changeIndexBand.setNoDataValue(-1);
        changeIndexBand.setNoDataValueUsed(true);
        changeIndexBand.setDescription("Acquisition index of detected change (0-based, -1 if no change)");

        cusumMagBand = targetProduct.addBand(CUSUM_MAG_BAND_NAME, ProductData.TYPE_FLOAT32);
        cusumMagBand.setNoDataValue(NO_DATA_VALUE);
        cusumMagBand.setNoDataValueUsed(true);
        cusumMagBand.setDescription("CUSUM statistic at change-detection point (confidence proxy)");
    }

    @Override
    public void computeTileStack(final Map<Band, Tile> targetTileMap, final Rectangle rect,
                                 final ProgressMonitor pm) throws OperatorException {
        try {
            final int n = sortedSrcBands.length;
            final Tile[] srcTiles = new Tile[n];
            final ProductData[] srcData = new ProductData[n];
            final TileIndex[] srcIndex = new TileIndex[n];
            for (int i = 0; i < n; i++) {
                srcTiles[i] = getSourceTile(sortedSrcBands[i], rect);
                srcData[i] = srcTiles[i].getDataBuffer();
                srcIndex[i] = new TileIndex(srcTiles[i]);
            }

            final Tile maskTile = targetTileMap.get(maskBand);
            final Tile changeTile = targetTileMap.get(changeIndexBand);
            final Tile magTile = targetTileMap.get(cusumMagBand);
            final ProductData maskData = maskTile.getDataBuffer();
            final ProductData changeData = changeTile.getDataBuffer();
            final ProductData magData = magTile.getDataBuffer();
            final TileIndex tgtIndex = new TileIndex(maskTile);

            final double[] series = new double[n];
            final Result result = new Result();

            final int x0 = rect.x;
            final int y0 = rect.y;
            final int xMax = x0 + rect.width;
            final int yMax = y0 + rect.height;

            for (int y = y0; y < yMax; y++) {
                for (int k = 0; k < n; k++) srcIndex[k].calculateStride(y);
                tgtIndex.calculateStride(y);

                for (int x = x0; x < xMax; x++) {
                    for (int k = 0; k < n; k++) {
                        series[k] = srcData[k].getElemDoubleAt(srcIndex[k].getIndex(x));
                        if (!inputIsDb && series[k] > 0.0) {
                            series[k] = 10.0 * Math.log10(series[k]);
                        }
                    }

                    detectCusum(series, baselineLength, cusumK, cusumH, minBaselineStd, result);

                    final int tgt = tgtIndex.getIndex(x);
                    maskData.setElemIntAt(tgt, result.disturbed ? DISTURBED : NO_DISTURBANCE);
                    changeData.setElemIntAt(tgt, result.changeIndex);
                    magData.setElemFloatAt(tgt, (float) result.cusumMag);
                }
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        } finally {
            pm.done();
        }
    }

    /** Result of a single-pixel CUSUM run. */
    public static final class Result {
        public boolean disturbed;
        public int changeIndex;
        public double cusumMag;
    }

    /**
     * One-sided CUSUM for downward steps.
     *
     * @param series       chronological time series (dB)
     * @param baseline     number of leading samples used for mu0, sigma0
     * @param k            slack constant (units of sigma0)
     * @param h            detection threshold (units of sigma0)
     * @param minSigma0    minimum baseline std-dev to attempt detection
     * @param out          populated with disturbance/changeIndex/cusumMag
     */
    static void detectCusum(final double[] series, final int baseline,
                            final double k, final double h, final double minSigma0,
                            final Result out) {
        out.disturbed = false;
        out.changeIndex = -1;
        out.cusumMag = 0.0;

        if (series.length <= baseline) return;

        // Baseline statistics (skip NaN)
        double mu0 = 0.0;
        int nValid = 0;
        for (int i = 0; i < baseline; i++) {
            if (!Double.isNaN(series[i])) {
                mu0 += series[i];
                nValid++;
            }
        }
        if (nValid < 3) return;
        mu0 /= nValid;

        double sumSq = 0.0;
        for (int i = 0; i < baseline; i++) {
            if (!Double.isNaN(series[i])) {
                final double d = series[i] - mu0;
                sumSq += d * d;
            }
        }
        final double sigma0 = Math.sqrt(sumSq / nValid);
        if (sigma0 < minSigma0) return;

        // CUSUM accumulator over the post-baseline period
        final double slack = k * sigma0;
        final double threshold = h * sigma0;
        double S = 0.0;
        for (int t = baseline; t < series.length; t++) {
            if (Double.isNaN(series[t])) continue;
            S = Math.max(0.0, S + (mu0 - series[t] - slack));
            if (S > threshold) {
                out.disturbed = true;
                out.changeIndex = t;
                out.cusumMag = S;
                return;
            }
        }
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(SARDisturbanceDetectionOp.class);
        }
    }
}
