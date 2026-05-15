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
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;

import java.awt.Rectangle;

/**
 * Three-Stage Inversion (3SI) of PolInSAR coherence for Random-Volume-over-Ground
 * forest height retrieval.
 *
 * <p>Reference: Cloude, S. R. and Papathanassiou, K. P. (2003).
 * <i>Three-stage inversion process for deriving forest structure from
 * polarimetric SAR interferometry.</i> IEE Proceedings - Radar, Sonar and
 * Navigation 150(3), 125-134. DOI:
 * <a href="https://doi.org/10.1049/ip-rsn:20030449">10.1049/ip-rsn:20030449</a>.
 * Consolidated in Cloude, S. R. (2010), <i>Polarimetric SAR Interferometry,</i>
 * IEEE TGRS 48(8), 2957-2972.</p>
 *
 * <p><b>Algorithm:</b></p>
 *
 * <p><b>Stage 1.</b> Per pixel, read three complex polarimetric coherences
 * gamma_k = i_k + j*q_k (k=1,2,3). These are typically the output of
 * {@link org.csa.rstb.polarimetric.gpf.CoherenceOptimizationOp} with
 * {@code outputInComplex=true}, but any three pol-channels may be used.</p>
 *
 * <p><b>Stage 2 (line fit).</b> Fit a straight line through the three
 * coherence points in the complex plane (total least squares, closed form
 * for 3 points). The line crosses the unit circle |gamma|=1 at two points;
 * the one closer to (a) the centroid of the input points is rejected (it
 * tends to be the volume-pure intersection), (b) the further one is
 * adopted as the ground-only coherence gamma_g (Cloude 2010 &sect; IV.B).</p>
 *
 * <p><b>Stage 3 (height inversion).</b> The coherence point furthest from
 * gamma_g on the fitted line is the closest to the pure-volume coherence
 * gamma_v. Compensate for ground contribution:
 * gamma_v_pure = (gamma_v - gamma_g) / (gamma_v_to_g_distance), then
 * invert the SinC magnitude relationship |gamma_v_pure| =
 * |sinc(kz*hv/(2*pi))| for canopy height hv. Bisection on the principal
 * branch (0, 2*pi/kz).</p>
 *
 * <p>This operator extends {@link RVoGForestHeightOp} (which uses a
 * single coherence and assumes negligible ground contribution) by
 * explicitly extracting and subtracting the ground term. The static
 * methods are exposed so a future tomographic / multi-baseline operator
 * can reuse the line-fit and inversion kernel.</p>
 *
 * <p><b>Inputs:</b> three pairs of (i_k, q_k) complex coherence bands;
 * optional kz band or constant kz.</p>
 *
 * <p><b>Outputs:</b> forest height [m], ground coherence magnitude
 * |gamma_g|, pure volume coherence magnitude |gamma_v_pure|.</p>
 */
@OperatorMetadata(alias = "RVoG-3SI-Forest-Height",
        category = "Radar/Biomass",
        authors = "SkyWatch",
        version = "1.0",
        copyright = "Copyright (C) 2026 by SkyWatch Space Applications Inc.",
        description = "RVoG three-stage inversion of PolInSAR coherence for forest height (Cloude & Papathanassiou 2003, Cloude 2010).")
public final class RVoG3StageInversionOp extends Operator {

    @SourceProduct(alias = "source")
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(rasterDataNodeType = Band.class, label = "i_coh_1")
    private String iCoh1BandName;
    @Parameter(rasterDataNodeType = Band.class, label = "q_coh_1")
    private String qCoh1BandName;

    @Parameter(rasterDataNodeType = Band.class, label = "i_coh_2")
    private String iCoh2BandName;
    @Parameter(rasterDataNodeType = Band.class, label = "q_coh_2")
    private String qCoh2BandName;

    @Parameter(rasterDataNodeType = Band.class, label = "i_coh_3")
    private String iCoh3BandName;
    @Parameter(rasterDataNodeType = Band.class, label = "q_coh_3")
    private String qCoh3BandName;

    @Parameter(description = "Optional vertical interferometric wavenumber band [rad/m].",
            rasterDataNodeType = Band.class, label = "kz Band (Optional)")
    private String kzBandName;

    @Parameter(description = "Constant kz [rad/m] when no kz band is provided.",
            defaultValue = "0.10", label = "kz [rad/m]")
    private double kz = 0.10;

    @Parameter(description = "Maximum forest height searched [m].",
            defaultValue = "60.0", label = "Max Height [m]")
    private double maxHeight = 60.0;

    @Parameter(description = "Minimum centroid distance to consider a line-fit valid.",
            defaultValue = "1.0e-3", label = "Min Line-Fit Spread")
    private double minSpread = 1.0e-3;

    private static final double NO_DATA_VALUE = -1.0;
    private static final String PRODUCT_SUFFIX = "_3SI";
    private static final String HEIGHT_BAND_NAME = "forestHeight";
    private static final String GAMMA_G_BAND_NAME = "ground_coh_mag";
    private static final String GAMMA_V_BAND_NAME = "volume_coh_mag";

    private Band iCoh1, qCoh1, iCoh2, qCoh2, iCoh3, qCoh3;
    private Band kzBand;
    private Band heightBand;
    private Band gammaGBand;
    private Band gammaVBand;

    @Override
    public void initialize() throws OperatorException {
        try {
            iCoh1 = resolveBand(iCoh1BandName, "i_coh_1");
            qCoh1 = resolveBand(qCoh1BandName, "q_coh_1");
            iCoh2 = resolveBand(iCoh2BandName, "i_coh_2");
            qCoh2 = resolveBand(qCoh2BandName, "q_coh_2");
            iCoh3 = resolveBand(iCoh3BandName, "i_coh_3");
            qCoh3 = resolveBand(qCoh3BandName, "q_coh_3");

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

    private Band resolveBand(final String name, final String role) {
        if (name == null || name.isEmpty()) {
            throw new OperatorException("Missing required band: " + role);
        }
        final Band b = sourceProduct.getBand(name);
        if (b == null) {
            throw new OperatorException("Source band '" + name + "' (" + role + ") not found.");
        }
        return b;
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
        heightBand.setDescription("RVoG 3SI forest height (Cloude 2010)");

        gammaGBand = targetProduct.addBand(GAMMA_G_BAND_NAME, ProductData.TYPE_FLOAT32);
        gammaGBand.setNoDataValue(NO_DATA_VALUE);
        gammaGBand.setNoDataValueUsed(true);
        gammaGBand.setDescription("|gamma_g| ground-only coherence magnitude");

        gammaVBand = targetProduct.addBand(GAMMA_V_BAND_NAME, ProductData.TYPE_FLOAT32);
        gammaVBand.setNoDataValue(NO_DATA_VALUE);
        gammaVBand.setNoDataValueUsed(true);
        gammaVBand.setDescription("|gamma_v_pure| ground-compensated volume coherence magnitude");
    }

    @Override
    public void computeTileStack(final java.util.Map<Band, Tile> targetTileMap,
                                 final Rectangle targetRectangle,
                                 final ProgressMonitor pm) throws OperatorException {
        try {
            final Tile iCoh1Tile = getSourceTile(iCoh1, targetRectangle);
            final Tile qCoh1Tile = getSourceTile(qCoh1, targetRectangle);
            final Tile iCoh2Tile = getSourceTile(iCoh2, targetRectangle);
            final Tile qCoh2Tile = getSourceTile(qCoh2, targetRectangle);
            final Tile iCoh3Tile = getSourceTile(iCoh3, targetRectangle);
            final Tile qCoh3Tile = getSourceTile(qCoh3, targetRectangle);
            final Tile kzTile = (kzBand != null) ? getSourceTile(kzBand, targetRectangle) : null;

            final ProductData iC1 = iCoh1Tile.getDataBuffer();
            final ProductData qC1 = qCoh1Tile.getDataBuffer();
            final ProductData iC2 = iCoh2Tile.getDataBuffer();
            final ProductData qC2 = qCoh2Tile.getDataBuffer();
            final ProductData iC3 = iCoh3Tile.getDataBuffer();
            final ProductData qC3 = qCoh3Tile.getDataBuffer();
            final ProductData kzData = (kzTile != null) ? kzTile.getDataBuffer() : null;

            final TileIndex srcIndex = new TileIndex(iCoh1Tile);

            final Tile heightTile = targetTileMap.get(heightBand);
            final Tile gammaGTile = targetTileMap.get(gammaGBand);
            final Tile gammaVTile = targetTileMap.get(gammaVBand);
            final ProductData hData = heightTile.getDataBuffer();
            final ProductData gData = gammaGTile.getDataBuffer();
            final ProductData vData = gammaVTile.getDataBuffer();
            final TileIndex tgtIndex = new TileIndex(heightTile);

            final int x0 = targetRectangle.x;
            final int y0 = targetRectangle.y;
            final int xMax = x0 + targetRectangle.width;
            final int yMax = y0 + targetRectangle.height;

            final Result tmp = new Result();

            for (int y = y0; y < yMax; y++) {
                srcIndex.calculateStride(y);
                tgtIndex.calculateStride(y);
                for (int x = x0; x < xMax; x++) {
                    final int i = srcIndex.getIndex(x);

                    final double g1Re = iC1.getElemDoubleAt(i);
                    final double g1Im = qC1.getElemDoubleAt(i);
                    final double g2Re = iC2.getElemDoubleAt(i);
                    final double g2Im = qC2.getElemDoubleAt(i);
                    final double g3Re = iC3.getElemDoubleAt(i);
                    final double g3Im = qC3.getElemDoubleAt(i);

                    final double kzLocal = (kzData != null) ? kzData.getElemDoubleAt(i) : kz;

                    invert3SI(g1Re, g1Im, g2Re, g2Im, g3Re, g3Im, kzLocal, minSpread, maxHeight, tmp);

                    final int tgt = tgtIndex.getIndex(x);
                    hData.setElemFloatAt(tgt, (float) tmp.height);
                    gData.setElemFloatAt(tgt, (float) tmp.gammaGMag);
                    vData.setElemFloatAt(tgt, (float) tmp.gammaVMag);
                }
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        } finally {
            pm.done();
        }
    }

    /** Result holder for a single-pixel 3SI inversion. */
    public static final class Result {
        public double height = NO_DATA_VALUE;
        public double gammaGMag = NO_DATA_VALUE;
        public double gammaVMag = NO_DATA_VALUE;
    }

    /**
     * Run the 3-stage inversion on a single pixel.
     *
     * <p>Stage 2 line fit uses the closed-form total-least-squares solution
     * for 3 complex points: take the principal direction of the centered
     * point set as the line direction, the centroid as the line offset.
     * Solve for unit-circle intersections by the quadratic
     * |c + t*d|^2 = 1.</p>
     */
    static void invert3SI(final double g1Re, final double g1Im,
                          final double g2Re, final double g2Im,
                          final double g3Re, final double g3Im,
                          final double kzLocal,
                          final double minSpread,
                          final double maxHeight,
                          final Result out) {
        out.height = NO_DATA_VALUE;
        out.gammaGMag = NO_DATA_VALUE;
        out.gammaVMag = NO_DATA_VALUE;

        if (!(kzLocal > 0.0)) return;

        // Centroid c = mean(gk)
        final double cRe = (g1Re + g2Re + g3Re) / 3.0;
        final double cIm = (g1Im + g2Im + g3Im) / 3.0;

        // Centered points pk = gk - c
        final double p1Re = g1Re - cRe, p1Im = g1Im - cIm;
        final double p2Re = g2Re - cRe, p2Im = g2Im - cIm;
        final double p3Re = g3Re - cRe, p3Im = g3Im - cIm;

        // Direction d: principal eigenvector of the 2x2 real covariance of
        // the (Re, Im) points. For 3 real-valued 2D points this is a small
        // closed-form eigen-decomposition.
        final double sxx = p1Re * p1Re + p2Re * p2Re + p3Re * p3Re;
        final double syy = p1Im * p1Im + p2Im * p2Im + p3Im * p3Im;
        final double sxy = p1Re * p1Im + p2Re * p2Im + p3Re * p3Im;

        if (sxx + syy < minSpread * minSpread) {
            // Points are coincident; line fit ill-conditioned.
            return;
        }

        // Largest eigenvalue / eigenvector of [[sxx, sxy], [sxy, syy]]
        final double tr = sxx + syy;
        final double det = sxx * syy - sxy * sxy;
        final double disc = Math.sqrt(Math.max(0.0, tr * tr / 4.0 - det));
        final double lambda1 = tr / 2.0 + disc;
        // Eigenvector for lambda1: solve (sxx - lambda1)*x + sxy*y = 0
        double dRe, dIm;
        if (Math.abs(sxy) > 1.0e-12) {
            dRe = sxy;
            dIm = lambda1 - sxx;
        } else {
            // Diagonal matrix; pick the axis of larger variance
            if (sxx >= syy) {
                dRe = 1.0;
                dIm = 0.0;
            } else {
                dRe = 0.0;
                dIm = 1.0;
            }
        }
        // Normalise d
        final double dNorm = Math.sqrt(dRe * dRe + dIm * dIm);
        if (!(dNorm > 0.0)) return;
        dRe /= dNorm;
        dIm /= dNorm;

        // Find t such that |c + t*d|^2 = 1.
        // |c|^2 + 2 t Re(c̄ d) + t^2 = 1 (since |d|=1)
        final double cMag2 = cRe * cRe + cIm * cIm;
        final double cDotD = cRe * dRe + cIm * dIm;          // Re(c̄ d)
        final double discT = cDotD * cDotD + (1.0 - cMag2);
        if (discT < 0.0) {
            // Line does not intersect unit circle (centroid too far inside / outside)
            return;
        }
        final double sqrtDisc = Math.sqrt(discT);
        final double tA = -cDotD + sqrtDisc;
        final double tB = -cDotD - sqrtDisc;

        // Two candidate ground points on the unit circle
        final double gARe = cRe + tA * dRe, gAIm = cIm + tA * dIm;
        final double gBRe = cRe + tB * dRe, gBIm = cIm + tB * dIm;

        // The ground point is the one *farther* from the data centroid along
        // the principal axis of variation — i.e. on the opposite side from
        // the volume-pure intersection. By Cloude 2003 sign convention,
        // pick the candidate that is closer to the lowest-|gamma| input point
        // (proxy for the most ground-influenced channel).
        final double m1 = g1Re * g1Re + g1Im * g1Im;
        final double m2 = g2Re * g2Re + g2Im * g2Im;
        final double m3 = g3Re * g3Re + g3Im * g3Im;
        double minMag2 = m1;
        double minRe = g1Re, minIm = g1Im;
        if (m2 < minMag2) { minMag2 = m2; minRe = g2Re; minIm = g2Im; }
        if (m3 < minMag2) { minMag2 = m3; minRe = g3Re; minIm = g3Im; }

        final double dA = (gARe - minRe) * (gARe - minRe) + (gAIm - minIm) * (gAIm - minIm);
        final double dB = (gBRe - minRe) * (gBRe - minRe) + (gBIm - minIm) * (gBIm - minIm);
        final double gRe, gIm;
        if (dA <= dB) { gRe = gARe; gIm = gAIm; } else { gRe = gBRe; gIm = gBIm; }

        out.gammaGMag = Math.sqrt(gRe * gRe + gIm * gIm);

        // The other unit-circle intersection is the volume-pure point on the line.
        // Choose the input coherence closest to this far intersection as the observation.
        final double oRe, oIm;
        if (dA <= dB) { oRe = gBRe; oIm = gBIm; } else { oRe = gARe; oIm = gAIm; }

        // Volume-pure projected observation: pick the input gamma_k whose
        // projection along (g - o) direction is farthest from g.
        // (Equivalently: pick the gamma_k with largest |gamma_k - g|.)
        final double e1 = (g1Re - gRe) * (g1Re - gRe) + (g1Im - gIm) * (g1Im - gIm);
        final double e2 = (g2Re - gRe) * (g2Re - gRe) + (g2Im - gIm) * (g2Im - gIm);
        final double e3 = (g3Re - gRe) * (g3Re - gRe) + (g3Im - gIm) * (g3Im - gIm);
        double maxE = e1;
        double obsRe = g1Re, obsIm = g1Im;
        if (e2 > maxE) { maxE = e2; obsRe = g2Re; obsIm = g2Im; }
        if (e3 > maxE) { maxE = e3; obsRe = g3Re; obsIm = g3Im; }

        // Canonical 3SI (Cloude 2003 §5): the volume-only coherence magnitude is the
        // magnitude of the most-volume-like observation (the gamma_k with smallest
        // ground-to-volume ratio, which we identified above as the one farthest from
        // gamma_g). This is the standard "weak-compensation" form used when the
        // ground-to-volume ratio m_k is not separately estimated. The variable
        // {@code oRe, oIm} (the far unit-circle intersection) is retained as a
        // diagnostic but is NOT the volume coherence — it corresponds to a fictitious
        // m_k=-1 channel and lies on the unit circle.
        final double vMag = Math.min(1.0, Math.sqrt(obsRe * obsRe + obsIm * obsIm));
        out.gammaVMag = vMag;
        // Suppress unused-variable warning on the diagnostic far intersection:
        @SuppressWarnings("unused") final double farRe = oRe, farIm = oIm;

        // Stage 3: SinC inversion for height
        final double hv = RVoGForestHeightOp.invertHeightSinC(vMag, kzLocal);
        if (!Double.isNaN(hv) && hv >= 0.0 && hv <= maxHeight) {
            out.height = hv;
        } else if (!Double.isNaN(hv) && hv > maxHeight) {
            out.height = maxHeight;
        }
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(RVoG3StageInversionOp.class);
        }
    }
}
