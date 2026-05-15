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
package org.csa.rstb.biomass.gpf.treeheight;

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
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;

import java.awt.Rectangle;

/**
 * Random-Volume-over-Ground (RVoG) PolInSAR forest height retrieval.
 *
 * <p>References:</p>
 * <ul>
 *   <li>Treuhaft, R. N. and Siqueira, P. R. (2000). <i>Vertical structure of
 *       vegetated land surfaces from interferometric and polarimetric radar.</i>
 *       Radio Science 35(1), 141-177.</li>
 *   <li>Cloude, S. R. and Papathanassiou, K. P. (2003). <i>Three-stage
 *       inversion process for deriving forest structure from polarimetric SAR
 *       interferometry.</i> IEE Proceedings - Radar, Sonar and Navigation
 *       150(3), 125-134. DOI:
 *       <a href="https://doi.org/10.1049/ip-rsn:20030449">10.1049/ip-rsn:20030449</a></li>
 *   <li>Cloude, S. R. (2010). <i>Polarimetric SAR Interferometry.</i> IEEE
 *       TGRS 48(8), 2957-2972 (3SI consolidation).</li>
 * </ul>
 *
 * <p><b>Algorithm (v1 simplified SinC-RVoG):</b></p>
 *
 * <p>The polarimetric interferometric coherence under a Random Volume over
 * Ground model is</p>
 * <pre>
 *   gamma(w) = exp(j*phi0) * (gamma_v + m(w)*gamma_g) / (1 + m(w))
 * </pre>
 * <p>where {@code phi0} is the ground topographic phase, {@code gamma_v} is
 * the pure volume coherence, {@code gamma_g} the ground-only coherence, and
 * {@code m(w)} the ground-to-volume scattering ratio per polarisation
 * channel.</p>
 *
 * <p>For exponential extinction sigma over the canopy of height
 * {@code hv}, with vertical interferometric wavenumber {@code kz} (from the
 * baseline / geometry):</p>
 * <pre>
 *   gamma_v(sigma, hv) = (2*sigma / (cos(theta)*(2*sigma + j*kz*cos(theta))))
 *                        * [exp((2*sigma + j*kz*cos(theta))*hv/cos(theta)) - 1]
 *                        / [exp(2*sigma*hv/cos(theta)) - 1]
 * </pre>
 *
 * <p>At zero extinction (the canonical first-order simplification) this
 * collapses to the SinC form
 * <code>gamma_v = sinc(kz*hv/(2*pi)) * exp(j*kz*hv/2)</code>, so</p>
 * <pre>
 *   |gamma_v(hv)| = |sinc(kz*hv / (2*pi))|
 * </pre>
 * <p>Inverting |gamma_v| numerically for {@code hv} yields the canopy
 * height. Full 3-stage inversion (line-fit to extract gamma_g and the
 * extinction sigma simultaneously) is deferred to v2 and is the natural
 * upgrade path for this operator; the architecture here exposes the
 * relevant entry points
 * ({@link #invertHeightSinC(double, double)}) so the 3SI loop can be
 * layered on later without breaking existing callers.</p>
 *
 * <p><b>Inputs:</b></p>
 * <ul>
 *   <li>One coherence-magnitude band (e.g. {@code coh_opt_1} from
 *       {@link org.csa.rstb.polarimetric.gpf.CoherenceOptimizationOp}, or
 *       any pairwise coherence from {@code CoherenceOp}). Treated as the
 *       observed |gamma_v| after ground-coherence compensation.</li>
 *   <li>kz (vertical interferometric wavenumber). Either a constant via the
 *       {@link #kz} parameter, or a per-pixel band (e.g. {@code wavenumber}
 *       emitted by {@code MultiMasterInSAROp}) selected via
 *       {@link #kzBandName}.</li>
 * </ul>
 *
 * <p><b>Output:</b> forest height [m]; pixels where |gamma| is below
 * {@link #minCoh} or above 1 are written as no-data.</p>
 *
 * <p><b>v2 follow-on (not yet implemented):</b> full 3-stage inversion with
 * line fit on the complex coherence locus and joint (hv, sigma) retrieval.</p>
 */
@OperatorMetadata(alias = "RVoG-Forest-Height",
        category = "Radar/Biomass",
        authors = "SkyWatch",
        version = "1.0",
        copyright = "Copyright (C) 2026 by SkyWatch Space Applications Inc.",
        description = "Random-Volume-over-Ground forest-height retrieval from PolInSAR coherence (Cloude & Papathanassiou 2003 / Cloude 2010).")
public final class RVoGForestHeightOp extends Operator {

    @SourceProduct(alias = "source")
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(description = "Volume coherence magnitude band (e.g. coh_opt_1 from CoherenceOptimization).",
            rasterDataNodeType = Band.class,
            label = "Coherence Band")
    private String coherenceBandName;

    @Parameter(description = "Optional vertical interferometric wavenumber band [rad/m]. If empty, use the constant kz.",
            rasterDataNodeType = Band.class,
            label = "kz Band (Optional)")
    private String kzBandName;

    @Parameter(description = "Constant vertical wavenumber kz [rad/m] when no kz band is provided.",
            defaultValue = "0.10",
            label = "kz [rad/m]")
    private double kz = 0.10;

    @Parameter(description = "Minimum |coherence| to attempt inversion. Below this, output no-data.",
            interval = "(0, 1)",
            defaultValue = "0.30",
            label = "Minimum |gamma|")
    private double minCoh = 0.30;

    @Parameter(description = "Maximum |coherence| to attempt inversion. Above this, sinc^-1 saturates (small hv).",
            interval = "(0, 1)",
            defaultValue = "0.99",
            label = "Maximum |gamma|")
    private double maxCoh = 0.99;

    @Parameter(description = "Maximum forest height searched [m]. Hard upper bound on output.",
            defaultValue = "60.0",
            label = "Max Height [m]")
    private double maxHeight = 60.0;

    private static final double NO_DATA_VALUE = -1.0;
    private static final String PRODUCT_SUFFIX = "_FH";
    private static final String HEIGHT_BAND_NAME = "forestHeight";

    private Band coherenceBand;
    private Band kzBand;
    private Band heightBand;
    private double cohNoDataValue = Double.NaN;

    @Override
    public void initialize() throws OperatorException {
        try {
            if (coherenceBandName == null || coherenceBandName.isEmpty()) {
                coherenceBand = findCoherenceBand(sourceProduct);
                if (coherenceBand == null) {
                    throw new OperatorException("No coherence band found in source product. "
                            + "Specify coherenceBandName explicitly.");
                }
            } else {
                coherenceBand = sourceProduct.getBand(coherenceBandName);
                if (coherenceBand == null) {
                    throw new OperatorException("Coherence band '" + coherenceBandName + "' not found.");
                }
            }
            cohNoDataValue = coherenceBand.isNoDataValueUsed() ? coherenceBand.getNoDataValue() : Double.NaN;

            if (kzBandName != null && !kzBandName.isEmpty()) {
                kzBand = sourceProduct.getBand(kzBandName);
                if (kzBand == null) {
                    throw new OperatorException("kz band '" + kzBandName + "' not found.");
                }
            }

            createTargetProduct();
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    /** Discover a coherence band by unit/name heuristics. */
    private static Band findCoherenceBand(final Product src) {
        for (Band b : src.getBands()) {
            final String unit = b.getUnit();
            if (unit != null && unit.contains("coherence")) {
                return b;
            }
        }
        for (Band b : src.getBands()) {
            final String name = b.getName().toLowerCase();
            if (name.startsWith("coh") || name.startsWith("gamma")) {
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

        heightBand = targetProduct.addBand(HEIGHT_BAND_NAME, ProductData.TYPE_FLOAT32);
        heightBand.setUnit("m");
        heightBand.setNoDataValue(NO_DATA_VALUE);
        heightBand.setNoDataValueUsed(true);
        heightBand.setDescription("RVoG (zero-extinction SinC) forest height from coherence band "
                + coherenceBand.getName());
    }

    @Override
    public void computeTile(final Band targetBand, final Tile targetTile, final ProgressMonitor pm)
            throws OperatorException {
        try {
            final Rectangle rect = targetTile.getRectangle();
            final Tile cohTile = getSourceTile(coherenceBand, rect);
            final ProductData cohData = cohTile.getDataBuffer();
            final TileIndex cohIndex = new TileIndex(cohTile);

            final Tile kzTile = (kzBand != null) ? getSourceTile(kzBand, rect) : null;
            final ProductData kzData = (kzTile != null) ? kzTile.getDataBuffer() : null;
            final TileIndex kzIndex = (kzTile != null) ? new TileIndex(kzTile) : null;

            final ProductData tgtData = targetTile.getDataBuffer();
            final TileIndex tgtIndex = new TileIndex(targetTile);

            final int x0 = rect.x;
            final int y0 = rect.y;
            final int xMax = x0 + rect.width;
            final int yMax = y0 + rect.height;

            for (int y = y0; y < yMax; y++) {
                cohIndex.calculateStride(y);
                tgtIndex.calculateStride(y);
                if (kzIndex != null) kzIndex.calculateStride(y);

                for (int x = x0; x < xMax; x++) {
                    final double gamma = cohData.getElemDoubleAt(cohIndex.getIndex(x));
                    final double kzLocal = (kzData != null) ? kzData.getElemDoubleAt(kzIndex.getIndex(x)) : kz;

                    double hv = NO_DATA_VALUE;
                    if (!Double.isNaN(gamma) && gamma != cohNoDataValue
                            && gamma >= minCoh && gamma <= maxCoh
                            && kzLocal > 0.0) {
                        hv = invertHeightSinC(gamma, kzLocal);
                        if (hv < 0.0 || hv > maxHeight || Double.isNaN(hv)) {
                            hv = (hv > maxHeight) ? maxHeight : NO_DATA_VALUE;
                        }
                    }
                    tgtData.setElemFloatAt(tgtIndex.getIndex(x), (float) hv);
                }
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        } finally {
            pm.done();
        }
    }

    /**
     * Invert |gamma| = |sinc(kz * hv / (2 pi))| for hv on the principal
     * branch (0, 2 pi / kz). Uses bisection because sinc is monotonically
     * decreasing on this interval. Returns NaN if no valid root.
     *
     * <p>The sinc convention here is the unnormalised
     * <code>sinc(x) = sin(pi x)/(pi x)</code> with x = kz*hv/(2 pi).</p>
     */
    static double invertHeightSinC(final double gamma, final double kz) {
        if (gamma <= 0.0 || gamma >= 1.0 || kz <= 0.0) return Double.NaN;

        // First-zero of sinc is at x = 1, i.e. kz*hv/(2 pi) = 1, hv = 2 pi / kz.
        final double hMax = 2.0 * Math.PI / kz;

        double lo = 0.0;
        double hi = hMax;
        // Bisect to find hv such that sinc(kz*hv/(2 pi)) = gamma.
        for (int iter = 0; iter < 60; iter++) {
            final double mid = 0.5 * (lo + hi);
            final double x = kz * mid / (2.0 * Math.PI);
            final double s = (x == 0.0) ? 1.0 : Math.sin(Math.PI * x) / (Math.PI * x);
            // sinc is monotonically decreasing on (0, 1); compare to target gamma
            if (s > gamma) {
                lo = mid;
            } else {
                hi = mid;
            }
            if (hi - lo < 1.0e-4) break;
        }
        return 0.5 * (lo + hi);
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(RVoGForestHeightOp.class);
        }
    }
}
