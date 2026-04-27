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
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.dataop.dem.ElevationModel;
import org.esa.snap.core.dataop.dem.ElevationModelDescriptor;
import org.esa.snap.core.dataop.dem.ElevationModelRegistry;
import org.esa.snap.core.dataop.resamp.ResamplingFactory;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.dem.dataio.FileElevationModel;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.TileIndex;

import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Empirical phase-elevation tropospheric correction.
 * <p>
 * Stratified tropospheric delay is, to first order, linear (or quadratic) in
 * topographic elevation. This operator estimates that relationship per
 * unwrapped-phase band by least-squares fit on coherence-masked samples drawn
 * from a regular grid across the scene, then subtracts the modelled phase
 * from every pixel.
 * <p>
 * Model: phase(z) = c0 + c1 * z   (linear)
 *        phase(z) = c0 + c1 * z + c2 * z * z   (quadratic)
 * <p>
 * This is the "tropo_phase_elevation" approach used in MintPy / GIAnT and the
 * empirical fallback in GACOS-less workflows. It is intentionally simple: it
 * removes only the topography-correlated component of atmospheric delay and
 * leaves turbulent residuals untouched. For strong stratification (large
 * relief, humid lower atmosphere) it typically removes 30–80 % of the
 * tropospheric phase variance.
 * <p>
 * Inputs: a product with one or more unwrapped phase bands (Unit.PHASE) and
 * optionally a coherence band per phase band. The operator auto-pairs each
 * phase band with the first coherence band sharing its master/slave suffix.
 */
@OperatorMetadata(alias = "EmpiricalTropoCorrection",
        category = "Radar/Interferometric/Filtering",
        authors = "SkyWatch / microwave-toolbox contributors",
        version = "1.0",
        copyright = "Copyright (C) 2026",
        description = "Empirical phase-elevation tropospheric correction (linear/quadratic LSQ fit, " +
                "subtract topography-correlated phase per unwrapped interferogram).")
public class EmpiricalTropoCorrectionOp extends Operator {

    @SourceProduct
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(description = "The digital elevation model.",
            defaultValue = "Copernicus 30m Global DEM",
            label = "Digital Elevation Model")
    private String demName = "Copernicus 30m Global DEM";

    @Parameter(defaultValue = ResamplingFactory.BILINEAR_INTERPOLATION_NAME,
            label = "DEM Resampling Method")
    private String demResamplingMethod = ResamplingFactory.BILINEAR_INTERPOLATION_NAME;

    @Parameter(label = "External DEM")
    private File externalDEMFile = null;

    @Parameter(label = "DEM No Data Value", defaultValue = "0")
    private double externalDEMNoDataValue = 0;

    @Parameter(valueSet = {"linear", "quadratic"}, defaultValue = "linear",
            label = "Regression model",
            description = "linear: phase = c0 + c1*z. quadratic: phase = c0 + c1*z + c2*z*z.")
    private String model = "linear";

    @Parameter(description = "Stride (in pixels) of the regression sample grid. " +
            "Smaller values fit on more samples (slower, possibly more robust to local outliers).",
            defaultValue = "20", interval = "[1, 1000]",
            label = "Sample stride")
    private int sampleStride = 20;

    @Parameter(description = "Skip samples whose coherence is below this threshold. " +
            "Ignored if no coherence band is found.",
            defaultValue = "0.3", interval = "[0, 1]",
            label = "Coherence threshold")
    private double coherenceThreshold = 0.3;

    @Parameter(description = "Skip pixels whose DEM elevation is at or below this floor (meters). " +
            "Useful to exclude ocean / no-data sea-level samples that flatten the slope.",
            defaultValue = "1.0",
            label = "Minimum elevation (m)")
    private double minElevation = 1.0;

    private static final String PRODUCT_SUFFIX = "_TropoCorr";
    private static final Logger logger = Logger.getLogger(EmpiricalTropoCorrectionOp.class.getName());

    private ElevationModel dem = null;
    private FileElevationModel fileElevationModel = null;
    private double demNoDataValue = 0;
    private boolean isElevationModelAvailable = false;
    private boolean isQuadratic = false;

    // Per-band fit coefficients [c0, c1] or [c0, c1, c2]; null until fitted.
    private final Map<String, double[]> coefficientMap = new HashMap<>();
    // Source phase band -> matching coherence band (may be null).
    private final Map<Band, Band> phaseToCoherence = new HashMap<>();
    // Target phase band -> source phase band.
    private final Map<Band, Band> targetToSource = new HashMap<>();

    @Override
    public void initialize() throws OperatorException {
        try {
            isQuadratic = "quadratic".equalsIgnoreCase(model);
            createTargetProduct();
        } catch (Throwable t) {
            throw new OperatorException(t);
        }
    }

    @Override
    public void dispose() {
        if (fileElevationModel != null) {
            fileElevationModel.dispose();
        }
    }

    private void createTargetProduct() {
        targetProduct = new Product(sourceProduct.getName() + PRODUCT_SUFFIX,
                sourceProduct.getProductType(),
                sourceProduct.getSceneRasterWidth(),
                sourceProduct.getSceneRasterHeight());
        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        final List<Band> phaseBands = new ArrayList<>();
        final List<Band> coherenceBands = new ArrayList<>();
        for (Band b : sourceProduct.getBands()) {
            if (b.getUnit() == null) {
                continue;
            }
            if (Unit.PHASE.equals(b.getUnit())) {
                phaseBands.add(b);
            } else if (Unit.COHERENCE.equals(b.getUnit())) {
                coherenceBands.add(b);
            }
        }
        if (phaseBands.isEmpty()) {
            throw new OperatorException("No band with unit '" + Unit.PHASE +
                    "' found. Run unwrapping (Snaphu Import) before this operator.");
        }

        for (Band phase : phaseBands) {
            // Pair phase band with coherence band by best-matching name suffix.
            final Band coh = matchCoherence(phase, coherenceBands);
            phaseToCoherence.put(phase, coh);

            final Band tgt = targetProduct.addBand(phase.getName(), ProductData.TYPE_FLOAT32);
            tgt.setUnit(Unit.PHASE);
            tgt.setNoDataValueUsed(phase.isNoDataValueUsed());
            tgt.setNoDataValue(phase.getNoDataValue());
            ProductUtils.copySpectralBandProperties(phase, tgt);
            targetToSource.put(tgt, phase);
        }

        // Pass through any non-phase bands the user might want to keep
        // (coherence, intensity, virtual phase) without modification.
        for (Band b : sourceProduct.getBands()) {
            if (targetProduct.getBand(b.getName()) == null) {
                ProductUtils.copyBand(b.getName(), sourceProduct, targetProduct, true);
            }
        }
    }

    private static Band matchCoherence(final Band phase, final List<Band> coherenceBands) {
        if (coherenceBands.isEmpty()) {
            return null;
        }
        // Strip the leading "Phase_" / "Unw_Phase_" prefix and try to match the
        // remaining master/slave date suffix against each coherence band name.
        final String pname = phase.getName();
        final int us = pname.indexOf('_');
        final String suffix = us >= 0 ? pname.substring(us) : pname;
        for (Band c : coherenceBands) {
            if (c.getName().endsWith(suffix)) {
                return c;
            }
        }
        // Fallback: if there's exactly one coherence band, assume it pairs.
        return coherenceBands.size() == 1 ? coherenceBands.get(0) : null;
    }

    @Override
    public void computeTile(final Band targetBand, final Tile targetTile, ProgressMonitor pm) throws OperatorException {
        final Band sourceBand = targetToSource.get(targetBand);
        if (sourceBand == null) {
            // Pass-through band (coherence, intensity, etc.) — let the framework
            // handle it via the band copy in createTargetProduct.
            return;
        }
        try {
            if (!isElevationModelAvailable) {
                getElevationModel();
            }
            final double[] coeff = getOrFitCoefficients(sourceBand);
            applyCorrection(sourceBand, targetBand, targetTile, coeff);
        } catch (Throwable t) {
            throw new OperatorException(t);
        }
    }

    private synchronized void getElevationModel() throws Exception {
        if (isElevationModelAvailable) {
            return;
        }
        if (externalDEMFile != null) {
            fileElevationModel = new FileElevationModel(externalDEMFile, demResamplingMethod, externalDEMNoDataValue);
            demNoDataValue = externalDEMNoDataValue;
        } else {
            final ElevationModelRegistry registry = ElevationModelRegistry.getInstance();
            final ElevationModelDescriptor descriptor = registry.getDescriptor(demName);
            if (descriptor == null) {
                throw new OperatorException("DEM '" + demName + "' is not supported.");
            }
            dem = descriptor.createDem(ResamplingFactory.createResampling(demResamplingMethod));
            if (dem == null) {
                throw new OperatorException("DEM '" + demName + "' is not installed.");
            }
            demNoDataValue = dem.getDescriptor().getNoDataValue();
        }
        isElevationModelAvailable = true;
    }

    private double getElevation(final int x, final int y) throws Exception {
        final PixelPos pixelPos = new PixelPos(x + 0.5, y + 0.5);
        final GeoPos geoPos = sourceProduct.getSceneGeoCoding().getGeoPos(pixelPos, null);
        if (geoPos == null || !geoPos.isValid()) {
            return demNoDataValue;
        }
        return externalDEMFile == null ? dem.getElevation(geoPos) : fileElevationModel.getElevation(geoPos);
    }

    private double[] getOrFitCoefficients(final Band sourceBand) throws Exception {
        double[] cached = coefficientMap.get(sourceBand.getName());
        if (cached != null) {
            return cached;
        }
        synchronized (coefficientMap) {
            cached = coefficientMap.get(sourceBand.getName());
            if (cached != null) {
                return cached;
            }
            cached = fitCoefficients(sourceBand);
            coefficientMap.put(sourceBand.getName(), cached);
            return cached;
        }
    }

    /**
     * Sample the source product on a stride grid, collect (z, phase) pairs
     * passing coherence + DEM masks, and solve normal equations for c0..ck.
     */
    private double[] fitCoefficients(final Band sourceBand) throws Exception {
        final Band cohBand = phaseToCoherence.get(sourceBand);
        final int width = sourceProduct.getSceneRasterWidth();
        final int height = sourceProduct.getSceneRasterHeight();

        final int stride = Math.max(1, sampleStride);
        final int sampleW = (width + stride - 1) / stride;
        final int sampleH = (height + stride - 1) / stride;

        // We pull samples by reading whole-image stride rows so we don't trigger
        // millions of single-pixel tile fetches.
        final Tile phaseTile = getSourceTile(sourceBand, new Rectangle(0, 0, width, height));
        final Tile cohTile = cohBand != null ? getSourceTile(cohBand, new Rectangle(0, 0, width, height)) : null;
        final ProductData phaseData = phaseTile.getDataBuffer();
        final ProductData cohData = cohTile != null ? cohTile.getDataBuffer() : null;
        final TileIndex pIdx = new TileIndex(phaseTile);
        final TileIndex cIdx = cohTile != null ? new TileIndex(cohTile) : null;

        final double phaseNoData = sourceBand.getNoDataValue();
        final boolean phaseNoDataUsed = sourceBand.isNoDataValueUsed();

        // Accumulators for normal equations of phi = c0 + c1 z (+ c2 z^2).
        final int order = isQuadratic ? 3 : 2;
        final double[][] A = new double[order][order];
        final double[] b = new double[order];
        int n = 0;

        for (int sy = 0; sy < sampleH; sy++) {
            final int y = Math.min(sy * stride, height - 1);
            pIdx.calculateStride(y);
            if (cIdx != null) cIdx.calculateStride(y);
            for (int sx = 0; sx < sampleW; sx++) {
                final int x = Math.min(sx * stride, width - 1);
                final double phi = phaseData.getElemDoubleAt(pIdx.getIndex(x));
                if (phaseNoDataUsed && phi == phaseNoData) continue;
                if (Double.isNaN(phi) || Double.isInfinite(phi)) continue;
                if (cohData != null) {
                    final float coh = cohData.getElemFloatAt(cIdx.getIndex(x));
                    if (Float.isNaN(coh) || coh < coherenceThreshold) continue;
                }
                final double z = getElevation(x, y);
                if (z == demNoDataValue || z <= minElevation) continue;
                if (Double.isNaN(z) || Double.isInfinite(z)) continue;

                // x-vector for this sample: [1, z] or [1, z, z^2].
                final double[] xv = new double[order];
                xv[0] = 1.0;
                xv[1] = z;
                if (isQuadratic) xv[2] = z * z;
                for (int i = 0; i < order; i++) {
                    b[i] += xv[i] * phi;
                    for (int j = 0; j < order; j++) {
                        A[i][j] += xv[i] * xv[j];
                    }
                }
                n++;
            }
        }

        if (n < order * 5) {
            logger.warning("Tropospheric fit for band '" + sourceBand.getName() +
                    "' had only " + n + " valid samples; skipping correction (zero coefficients).");
            return new double[order];
        }

        final double[] coeff = solveSymmetric(A, b);
        logger.info("Tropospheric fit '" + sourceBand.getName() + "' (n=" + n + "): " +
                formatCoeff(coeff));
        return coeff;
    }

    private static String formatCoeff(final double[] c) {
        final StringBuilder sb = new StringBuilder("phase(z) = ");
        for (int i = 0; i < c.length; i++) {
            if (i > 0) sb.append(" + ");
            sb.append(String.format("%.6e", c[i]));
            if (i == 1) sb.append("*z");
            else if (i == 2) sb.append("*z^2");
        }
        return sb.toString();
    }

    /**
     * Gauss-Jordan elimination on a small symmetric system. Order is at most 3
     * (linear or quadratic regression), so a hand-rolled solver is plenty.
     */
    private static double[] solveSymmetric(final double[][] A, final double[] b) {
        final int n = b.length;
        final double[][] M = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(A[i], 0, M[i], 0, n);
            M[i][n] = b[i];
        }
        for (int i = 0; i < n; i++) {
            // Partial pivot.
            int piv = i;
            for (int r = i + 1; r < n; r++) {
                if (Math.abs(M[r][i]) > Math.abs(M[piv][i])) piv = r;
            }
            if (piv != i) {
                final double[] tmp = M[i]; M[i] = M[piv]; M[piv] = tmp;
            }
            final double diag = M[i][i];
            if (Math.abs(diag) < 1e-30) {
                // Singular: bail with zero coefficients (no correction applied).
                return new double[n];
            }
            for (int j = i; j <= n; j++) M[i][j] /= diag;
            for (int r = 0; r < n; r++) {
                if (r == i) continue;
                final double f = M[r][i];
                if (f == 0.0) continue;
                for (int j = i; j <= n; j++) M[r][j] -= f * M[i][j];
            }
        }
        final double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = M[i][n];
        return x;
    }

    private void applyCorrection(final Band sourceBand, final Band targetBand,
                                 final Tile targetTile, final double[] coeff) throws Exception {
        final Rectangle r = targetTile.getRectangle();
        final Tile srcTile = getSourceTile(sourceBand, r);
        final ProductData srcData = srcTile.getDataBuffer();
        final ProductData tgtData = targetTile.getDataBuffer();
        final TileIndex sIdx = new TileIndex(srcTile);
        final TileIndex tIdx = new TileIndex(targetTile);

        final double phaseNoData = sourceBand.getNoDataValue();
        final boolean phaseNoDataUsed = sourceBand.isNoDataValueUsed();
        final boolean quadratic = coeff.length >= 3;
        final int yMax = r.y + r.height;
        final int xMax = r.x + r.width;

        for (int y = r.y; y < yMax; y++) {
            sIdx.calculateStride(y);
            tIdx.calculateStride(y);
            for (int x = r.x; x < xMax; x++) {
                final double phi = srcData.getElemDoubleAt(sIdx.getIndex(x));
                if (phaseNoDataUsed && phi == phaseNoData) {
                    tgtData.setElemFloatAt(tIdx.getIndex(x), (float) phi);
                    continue;
                }
                final double z = getElevation(x, y);
                if (z == demNoDataValue || Double.isNaN(z) || Double.isInfinite(z)) {
                    // No DEM here: leave the original phase untouched rather
                    // than fabricating a correction from extrapolated z.
                    tgtData.setElemFloatAt(tIdx.getIndex(x), (float) phi);
                    continue;
                }
                double model = coeff[0] + coeff[1] * z;
                if (quadratic) model += coeff[2] * z * z;
                tgtData.setElemFloatAt(tIdx.getIndex(x), (float) (phi - model));
            }
        }
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(EmpiricalTropoCorrectionOp.class);
        }
    }
}
