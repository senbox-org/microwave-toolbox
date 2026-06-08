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
package eu.esa.sar.insar.gpf;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.SARUtils;
import eu.esa.sar.insar.gpf.timeseries.ClosurePhase;
import eu.esa.sar.insar.gpf.timeseries.Network;
import eu.esa.sar.insar.gpf.timeseries.WeightedLSQ;
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
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;

import java.awt.Rectangle;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small-Baseline Subset (SBAS) inversion (Berardino et al., IEEE TGRS 2002).
 *
 * Inverts a stack of unwrapped small-baseline interferograms for per-epoch
 * phase / displacement and a weighted linear-velocity model. Coherence per
 * pair drives a CRLB-style noise weight; Tikhonov regularisation
 * activates automatically when the active sub-network is ill-conditioned.
 *
 * Input bands (per SnaphuImportOp / MultiMasterInSAROp output conventions):
 *  - Unw_Phase_ifg_&lt;...&gt;_&lt;masterDate&gt;_&lt;slaveDate&gt;  (Unit.PHASE / Unit.ABS_PHASE)
 *  - coh_&lt;...&gt;_&lt;masterDate&gt;_&lt;slaveDate&gt;             (Unit.COHERENCE)
 *
 * Outputs:
 *  - phase_&lt;date&gt;            per-epoch phase relative to reference (rad)
 *  - displacement_&lt;date&gt;     mm, d = - lambda / (4 pi) * phase
 *  - velocity                weighted linear fit, mm/yr
 *  - velocity_uncertainty    1-sigma velocity (mm/yr)
 *  - temporal_coherence      Pepe-style residual metric in [0, 1]
 *  - residual_&lt;m&gt;_&lt;s&gt;     per-pair residual (if outputResiduals)
 *  - closure_phase_rms       per-pixel RMS triplet closure (if outputClosurePhase)
 */
@OperatorMetadata(alias = "SBASInversion",
        category = "Radar/Interferometric/Time-Series",
        authors = "SkyWatch",
        version = "1.0",
        copyright = "Copyright (C) 2026 by SkyWatch Space Applications Inc.",
        description = "Small-Baseline Subset (SBAS) network inversion to per-epoch displacement and velocity.")
public class SBASInversionOp extends Operator {

    @SourceProduct
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(description = "Reference epoch date (ddMMMyyyy). If empty, the median epoch is used.",
            defaultValue = "",
            label = "Reference Epoch Date")
    private String referenceEpochDate = "";

    @Parameter(description = "Drop equations whose coherence is below this threshold",
            defaultValue = "0.3",
            label = "Coherence Threshold")
    private double coherenceMin = 0.3;

    @Parameter(description = "Number of independent looks assumed for the coherence-derived weight",
            defaultValue = "100",
            label = "Coherence Looks")
    private int coherenceLooks = 100;

    @Parameter(description = "Tikhonov regularisation weight (relative to trace(A^T W A)/(N-1))",
            defaultValue = "0.001",
            label = "Regularisation Weight")
    private double regWeight = 1.0e-3;

    @Parameter(description = "cond(A^T W A) above which Tikhonov regularisation activates",
            defaultValue = "1.0e6",
            label = "Condition Threshold")
    private double condThreshold = 1.0e6;

    @Parameter(description = "Emit per-pair residual bands",
            defaultValue = "false",
            label = "Output Residuals")
    private boolean outputResiduals = false;

    @Parameter(description = "Emit RMS triplet closure-phase band",
            defaultValue = "true",
            label = "Output Closure Phase RMS")
    private boolean outputClosurePhase = true;

    @Parameter(description = "Emit velocity and uncertainty bands",
            defaultValue = "true",
            label = "Output Velocity")
    private boolean outputVelocity = true;

    private static final String PRODUCT_SUFFIX = "_SBAS";
    private static final String UNW_BAND_PREFIX = "Unw_Phase_ifg";
    private static final String COH_BAND_PREFIX = "coh_";
    private static final Pattern DATE_PATTERN =
            Pattern.compile("(\\d{1,2}[A-Za-z]{3}\\d{4})");

    private double wavelength;          // radar wavelength, m
    private Network network;
    private WeightedLSQ solver;

    /** Per-pair source bands (length M, aligned with network pair index). */
    private Band[] unwBands;
    private Band[] cohBands;

    /** Per-epoch target bands (length N - 1, excluding reference). */
    private Band[] phaseBands;
    private Band[] displacementBands;

    /** Optional outputs. */
    private Band velocityBand;
    private Band velocityUncertaintyBand;
    private Band temporalCoherenceBand;
    private Band closurePhaseBand;
    private Band[] residualBands; // length M when outputResiduals=true

    private double[] tYears;            // length N: time of each epoch in years from t0

    @Override
    public void initialize() throws OperatorException {
        try {
            final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);
            wavelength = SARUtils.getRadarWavelength(absRoot);

            buildNetwork();
            solver = new WeightedLSQ(network.designMatrix(), regWeight, condThreshold);

            createTargetProduct();

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private void buildNetwork() throws Exception {
        final DateFormat dateFormat = ProductData.UTC.createDateFormat("ddMMMyyyy");

        // Gather unwrapped-phase + coherence band pairs by date pair
        final Map<String, Band> unwByPair = new LinkedHashMap<>();
        final Map<String, Band> cohByPair = new HashMap<>();
        final Set<String> uniqueDates = new TreeSet<>();
        final Map<String, Date> parsed = new HashMap<>();

        for (Band b : sourceProduct.getBands()) {
            final String name = b.getName();
            final String unit = b.getUnit();
            if (unit == null) continue;
            final String[] dates = extractLastTwoDates(name);
            if (dates == null) continue;
            final String pairKey = dates[0] + "_" + dates[1];

            if (name.startsWith(UNW_BAND_PREFIX) ||
                    Unit.PHASE.equals(unit) || Unit.ABS_PHASE.equals(unit)) {
                if (!name.toLowerCase().contains("unw")) continue;
                unwByPair.put(pairKey, b);
                uniqueDates.add(dates[0]);
                uniqueDates.add(dates[1]);
                parsed.computeIfAbsent(dates[0], d -> parseSafe(dateFormat, d));
                parsed.computeIfAbsent(dates[1], d -> parseSafe(dateFormat, d));
            } else if (Unit.COHERENCE.equals(unit) || name.startsWith(COH_BAND_PREFIX)) {
                cohByPair.put(pairKey, b);
            }
        }

        if (unwByPair.isEmpty()) {
            throw new OperatorException(
                    "SBASInversionOp: no Unw_Phase_ifg_<...>_<masterDate>_<slaveDate> bands found in source product.");
        }

        // Chronological epoch list
        final List<String> epochDates = new ArrayList<>(uniqueDates);
        epochDates.sort(Comparator.comparing(parsed::get));
        final int N = epochDates.size();
        if (N < 3) {
            throw new OperatorException("SBASInversionOp: at least 3 unique epochs required (found " + N + ").");
        }

        final Map<String, Integer> dateToIdx = new HashMap<>();
        final List<Long> epochMjd = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            dateToIdx.put(epochDates.get(i), i);
            epochMjd.add(parsed.get(epochDates.get(i)).getTime());
        }

        final int M = unwByPair.size();
        final int[] pm = new int[M];
        final int[] ps = new int[M];
        final List<Band> unwList = new ArrayList<>(M);
        final List<Band> cohList = new ArrayList<>(M);
        int k = 0;
        for (Map.Entry<String, Band> e : unwByPair.entrySet()) {
            final String[] dates = e.getKey().split("_");
            final int im = dateToIdx.get(dates[0]);
            final int is = dateToIdx.get(dates[1]);
            // ensure master < slave chronologically (swap if needed and flip the phase sign at compute time)
            if (epochMjd.get(im) <= epochMjd.get(is)) {
                pm[k] = im;
                ps[k] = is;
            } else {
                pm[k] = is;
                ps[k] = im;
            }
            unwList.add(e.getValue());
            cohList.add(cohByPair.get(e.getKey()));
            k++;
        }

        // Resolve reference epoch
        final int refIdx;
        if (referenceEpochDate == null || referenceEpochDate.trim().isEmpty()) {
            refIdx = N / 2;
        } else {
            final Integer idx = dateToIdx.get(referenceEpochDate.trim());
            if (idx == null) {
                throw new OperatorException("SBASInversionOp: referenceEpochDate '" + referenceEpochDate +
                        "' not in stack. Available: " + epochDates);
            }
            refIdx = idx;
        }

        network = new Network(epochDates, epochMjd, pm, ps, refIdx);
        unwBands = unwList.toArray(new Band[0]);
        cohBands = cohList.toArray(new Band[0]);

        // Pre-compute time in years from epoch[0]
        tYears = new double[N];
        final long t0 = epochMjd.get(0);
        for (int i = 0; i < N; i++) {
            tYears[i] = (epochMjd.get(i) - t0) / (1000.0 * 86400.0 * 365.25);
        }
    }

    private static Date parseSafe(final DateFormat df, final String s) {
        try {
            return df.parse(s);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not parse epoch date '" + s + "' as ddMMMyyyy", ex);
        }
    }

    /** Returns the LAST TWO ddMMMyyyy tokens in the band name, in order, or null if &lt; 2. */
    private static String[] extractLastTwoDates(final String name) {
        final Matcher m = DATE_PATTERN.matcher(name);
        String d1 = null, d2 = null;
        while (m.find()) {
            d1 = d2;
            d2 = m.group(1);
        }
        if (d1 == null || d2 == null) return null;
        return new String[]{d1, d2};
    }

    private void createTargetProduct() {
        targetProduct = new Product(sourceProduct.getName() + PRODUCT_SUFFIX,
                sourceProduct.getProductType(),
                sourceProduct.getSceneRasterWidth(),
                sourceProduct.getSceneRasterHeight());
        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        final int N = network.numEpochs();
        phaseBands = new Band[N];
        displacementBands = new Band[N];
        final double k = -wavelength / (4.0 * Math.PI) * 1000.0; // mm

        for (int i = 0; i < N; i++) {
            final String d = network.epochDate(i);
            final Band ph = targetProduct.addBand("phase_" + d, ProductData.TYPE_FLOAT32);
            ph.setUnit(Unit.PHASE);
            ph.setNoDataValue(Double.NaN);
            ph.setNoDataValueUsed(true);
            ph.setDescription("Per-epoch phase relative to reference epoch " + network.epochDate(network.refIndex()));
            phaseBands[i] = ph;

            final Band disp = targetProduct.addBand("displacement_" + d, ProductData.TYPE_FLOAT32);
            disp.setUnit("mm");
            disp.setNoDataValue(Double.NaN);
            disp.setNoDataValueUsed(true);
            disp.setDescription("LOS displacement, mm, " + d + " - reference (" + k + " * phase)");
            displacementBands[i] = disp;
        }

        if (outputVelocity) {
            velocityBand = targetProduct.addBand("velocity", ProductData.TYPE_FLOAT32);
            velocityBand.setUnit("mm/year");
            velocityBand.setNoDataValue(Double.NaN);
            velocityBand.setNoDataValueUsed(true);

            velocityUncertaintyBand = targetProduct.addBand("velocity_uncertainty", ProductData.TYPE_FLOAT32);
            velocityUncertaintyBand.setUnit("mm/year");
            velocityUncertaintyBand.setNoDataValue(Double.NaN);
            velocityUncertaintyBand.setNoDataValueUsed(true);
        }

        temporalCoherenceBand = targetProduct.addBand("temporal_coherence", ProductData.TYPE_FLOAT32);
        temporalCoherenceBand.setUnit(Unit.COHERENCE);
        temporalCoherenceBand.setNoDataValue(Double.NaN);
        temporalCoherenceBand.setNoDataValueUsed(true);

        if (outputResiduals) {
            final int M = network.numPairs();
            residualBands = new Band[M];
            for (int kk = 0; kk < M; kk++) {
                final int[] pair = network.pair(kk);
                final String name = "residual_" + network.epochDate(pair[0]) + "_" + network.epochDate(pair[1]);
                final Band r = targetProduct.addBand(name, ProductData.TYPE_FLOAT32);
                r.setUnit(Unit.PHASE);
                r.setNoDataValue(Double.NaN);
                r.setNoDataValueUsed(true);
                residualBands[kk] = r;
            }
        }

        if (outputClosurePhase) {
            closurePhaseBand = targetProduct.addBand("closure_phase_rms", ProductData.TYPE_FLOAT32);
            closurePhaseBand.setUnit(Unit.PHASE);
            closurePhaseBand.setNoDataValue(Double.NaN);
            closurePhaseBand.setNoDataValueUsed(true);
        }
    }

    @Override
    public void computeTileStack(final Map<Band, Tile> targetTileMap, final Rectangle targetRectangle,
                                 final ProgressMonitor pm) throws OperatorException {
        try {
            final int M = network.numPairs();
            final int N = network.numEpochs();
            final int refIdx = network.refIndex();

            final Tile[] unwTiles = new Tile[M];
            final Tile[] cohTiles = new Tile[M];
            for (int kk = 0; kk < M; kk++) {
                unwTiles[kk] = getSourceTile(unwBands[kk], targetRectangle);
                cohTiles[kk] = getSourceTile(cohBands[kk], targetRectangle);
            }

            final ProductData[] unwBuf = new ProductData[M];
            final ProductData[] cohBuf = new ProductData[M];
            final TileIndex[] srcIdx = new TileIndex[M];
            for (int kk = 0; kk < M; kk++) {
                unwBuf[kk] = unwTiles[kk].getDataBuffer();
                cohBuf[kk] = cohTiles[kk].getDataBuffer();
                srcIdx[kk] = new TileIndex(unwTiles[kk]);
            }

            // Per-pair sign for swapped pairs (we always solved with master < slave).
            // We already swapped pm/ps to enforce master < slave at network-build time,
            // but the unwrapped-phase band remained the original sign convention (slave - master
            // = positive deformation away from satellite). We do NOT flip phi here -- the
            // design-matrix entries (-1 at master col, +1 at slave col) match the
            // (slave - master) convention.

            final Tile[] phaseTgt = new Tile[N];
            final Tile[] dispTgt = new Tile[N];
            final TileIndex[] phaseIdx = new TileIndex[N];
            for (int i = 0; i < N; i++) {
                phaseTgt[i] = targetTileMap.get(phaseBands[i]);
                dispTgt[i] = targetTileMap.get(displacementBands[i]);
                phaseIdx[i] = new TileIndex(phaseTgt[i]);
            }

            final Tile velTile = (velocityBand != null) ? targetTileMap.get(velocityBand) : null;
            final Tile velSigmaTile = (velocityUncertaintyBand != null) ? targetTileMap.get(velocityUncertaintyBand) : null;
            final Tile tempCohTile = targetTileMap.get(temporalCoherenceBand);
            final Tile closureTile = (closurePhaseBand != null) ? targetTileMap.get(closurePhaseBand) : null;
            final TileIndex velIdx = (velTile != null) ? new TileIndex(velTile) : null;
            final TileIndex velSigmaIdx = (velSigmaTile != null) ? new TileIndex(velSigmaTile) : null;
            final TileIndex tempCohIdx = new TileIndex(tempCohTile);
            final TileIndex closureIdx = (closureTile != null) ? new TileIndex(closureTile) : null;

            final Tile[] residualTgt = (residualBands != null) ? new Tile[M] : null;
            final TileIndex[] residualIdx = (residualBands != null) ? new TileIndex[M] : null;
            if (residualBands != null) {
                for (int kk = 0; kk < M; kk++) {
                    residualTgt[kk] = targetTileMap.get(residualBands[kk]);
                    residualIdx[kk] = new TileIndex(residualTgt[kk]);
                }
            }

            final double dispScale = -wavelength / (4.0 * Math.PI) * 1000.0; // mm/rad
            final double L = Math.max(1, coherenceLooks);

            final double[] phi = new double[M];
            final double[] w = new double[M];
            final WeightedLSQ.Result res = new WeightedLSQ.Result();

            final int x0 = targetRectangle.x;
            final int y0 = targetRectangle.y;
            final int xMax = x0 + targetRectangle.width;
            final int yMax = y0 + targetRectangle.height;

            for (int y = y0; y < yMax; y++) {
                for (int kk = 0; kk < M; kk++) srcIdx[kk].calculateStride(y);
                for (int i = 0; i < N; i++) phaseIdx[i].calculateStride(y);
                if (velIdx != null) velIdx.calculateStride(y);
                if (velSigmaIdx != null) velSigmaIdx.calculateStride(y);
                tempCohIdx.calculateStride(y);
                if (closureIdx != null) closureIdx.calculateStride(y);
                if (residualIdx != null) {
                    for (int kk = 0; kk < M; kk++) residualIdx[kk].calculateStride(y);
                }

                for (int xpx = x0; xpx < xMax; xpx++) {
                    for (int kk = 0; kk < M; kk++) {
                        final int srcI = srcIdx[kk].getIndex(xpx);
                        final double p = unwBuf[kk].getElemDoubleAt(srcI);
                        final double c = cohBuf[kk].getElemDoubleAt(srcI);
                        phi[kk] = p;
                        final double cClamp = Math.min(0.9999, Math.max(0.0, c));
                        if (cClamp < coherenceMin || Double.isNaN(p)) {
                            w[kk] = 0.0;
                        } else {
                            final double var = (1.0 - cClamp * cClamp) / (2.0 * L * cClamp * cClamp);
                            w[kk] = (var > 0.0) ? 1.0 / var : 0.0;
                        }
                    }

                    solver.solve(phi, w, res);
                    if (!res.ok) {
                        writeNoData(xpx, refIdx, phaseTgt, dispTgt, phaseIdx, velTile, velIdx,
                                velSigmaTile, velSigmaIdx, tempCohTile, tempCohIdx,
                                closureTile, closureIdx, residualTgt, residualIdx, M, N);
                        continue;
                    }

                    // Pepe-style temporal coherence on residuals: |mean(exp(j r_k))|
                    double sR = 0.0, sI = 0.0;
                    int valid = 0;
                    for (int kk = 0; kk < M; kk++) {
                        if (w[kk] <= 0.0) continue;
                        sR += Math.cos(res.residuals[kk]);
                        sI += Math.sin(res.residuals[kk]);
                        valid++;
                    }
                    final double tempCoh = (valid > 0) ? Math.sqrt(sR * sR + sI * sI) / valid : 0.0;

                    // Write per-epoch phase and displacement (phase[refIdx] = 0)
                    for (int i = 0; i < N; i++) {
                        final double pn;
                        if (i == refIdx) {
                            pn = 0.0;
                        } else {
                            final int colIdx = (i < refIdx) ? i : i - 1;
                            pn = res.x[colIdx];
                        }
                        final int pi = phaseIdx[i].getIndex(xpx);
                        phaseTgt[i].getDataBuffer().setElemFloatAt(pi, (float) pn);
                        dispTgt[i].getDataBuffer().setElemFloatAt(pi, (float) (dispScale * pn));
                    }

                    if (velTile != null) {
                        final double[] fit = weightedLinearFit(N, refIdx, res);
                        velTile.getDataBuffer().setElemFloatAt(velIdx.getIndex(xpx), (float) (dispScale * fit[0]));
                        velSigmaTile.getDataBuffer().setElemFloatAt(velSigmaIdx.getIndex(xpx),
                                (float) Math.abs(dispScale * fit[1]));
                    }

                    tempCohTile.getDataBuffer().setElemFloatAt(tempCohIdx.getIndex(xpx), (float) tempCoh);

                    if (closureTile != null) {
                        final double rmsClosure = ClosurePhase.rms(network, phi);
                        closureTile.getDataBuffer().setElemFloatAt(closureIdx.getIndex(xpx), (float) rmsClosure);
                    }

                    if (residualTgt != null) {
                        for (int kk = 0; kk < M; kk++) {
                            final int ri = residualIdx[kk].getIndex(xpx);
                            final double r = (w[kk] > 0.0) ? res.residuals[kk] : Double.NaN;
                            residualTgt[kk].getDataBuffer().setElemFloatAt(ri, (float) r);
                        }
                    }
                }
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        } finally {
            pm.done();
        }
    }

    private static int epochToColumn(final int epochIdx, final int refIdx) {
        if (epochIdx == refIdx) return -1;
        return (epochIdx < refIdx) ? epochIdx : epochIdx - 1;
    }

    /**
     * Unweighted OLS linear fit of x_i = v * t_i + b over the N epochs (with x_ref = 0 at t_ref).
     * Returns [v_phase, sigma_v] in radians-per-year and 1-sigma uncertainty.
     *
     * v2: replace with per-epoch sigma from sum_k w_k * |A_{k,i}|.
     */
    private double[] weightedLinearFit(final int N, final int refIdx,
                                       final WeightedLSQ.Result res) {
        double sx = 0.0, sy = 0.0, sxy = 0.0, sxx = 0.0;
        for (int i = 0; i < N; i++) {
            final double xi = tYears[i] - tYears[refIdx];
            final int colI = epochToColumn(i, refIdx);
            final double yi = (colI < 0) ? 0.0 : res.x[colI];
            sx += xi;
            sy += yi;
            sxy += xi * yi;
            sxx += xi * xi;
        }
        final double denom = N * sxx - sx * sx;
        if (Math.abs(denom) < 1.0e-12) return new double[]{0.0, Double.NaN};
        final double v = (N * sxy - sx * sy) / denom;
        final double b = (sy - v * sx) / N;

        double rss = 0.0;
        for (int i = 0; i < N; i++) {
            final double xi = tYears[i] - tYears[refIdx];
            final int colI = epochToColumn(i, refIdx);
            final double yi = (colI < 0) ? 0.0 : res.x[colI];
            final double e = yi - (v * xi + b);
            rss += e * e;
        }
        final double sigma2 = (N > 2) ? rss / (N - 2) : 0.0;
        final double varV = sigma2 * N / denom;
        return new double[]{v, Math.sqrt(Math.max(varV, 0.0))};
    }

    private void writeNoData(final int x, final int refIdx,
                             final Tile[] phaseTgt, final Tile[] dispTgt, final TileIndex[] phaseIdx,
                             final Tile velTile, final TileIndex velIdx,
                             final Tile velSigmaTile, final TileIndex velSigmaIdx,
                             final Tile tempCohTile, final TileIndex tempCohIdx,
                             final Tile closureTile, final TileIndex closureIdx,
                             final Tile[] residualTgt, final TileIndex[] residualIdx,
                             final int M, final int N) {
        for (int i = 0; i < N; i++) {
            final int pi = phaseIdx[i].getIndex(x);
            phaseTgt[i].getDataBuffer().setElemFloatAt(pi, Float.NaN);
            dispTgt[i].getDataBuffer().setElemFloatAt(pi, Float.NaN);
        }
        if (velTile != null) velTile.getDataBuffer().setElemFloatAt(velIdx.getIndex(x), Float.NaN);
        if (velSigmaTile != null) velSigmaTile.getDataBuffer().setElemFloatAt(velSigmaIdx.getIndex(x), Float.NaN);
        tempCohTile.getDataBuffer().setElemFloatAt(tempCohIdx.getIndex(x), Float.NaN);
        if (closureTile != null) closureTile.getDataBuffer().setElemFloatAt(closureIdx.getIndex(x), Float.NaN);
        if (residualTgt != null) {
            for (int kk = 0; kk < M; kk++) {
                residualTgt[kk].getDataBuffer().setElemFloatAt(residualIdx[kk].getIndex(x), Float.NaN);
            }
        }
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(SBASInversionOp.class);
        }
    }
}
