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
import org.esa.snap.core.datamodel.TiePointGrid;
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
 * Water Cloud Model (WCM) soil / vegetation backscatter decoupling.
 *
 * <p>Reference: Attema, E. P. W. and Ulaby, F. T. (1978).
 * <i>Vegetation Modeled as a Water Cloud.</i> Radio Science 13(2),
 * 357-364. DOI:
 * <a href="https://doi.org/10.1029/RS013i002p00357">10.1029/RS013i002p00357</a></p>
 *
 * <p><b>Model:</b></p>
 * <pre>
 *   sigma0_obs(lin) = sigma0_veg + tau^2 * sigma0_soil
 *   sigma0_veg      = A * V * cos(theta) * (1 - tau^2)
 *   tau^2           = exp(-2 * B * V / cos(theta))
 * </pre>
 * <p>where</p>
 * <ul>
 *   <li><b>sigma0_obs</b> — observed backscatter (linear, m^2/m^2)</li>
 *   <li><b>sigma0_soil</b> — bare-soil backscatter from an independent
 *       model (e.g. Oh 1992, Dubois 1995, IEM — available in
 *       {@code rstb-op-soil-moisture})</li>
 *   <li><b>V</b> — vegetation water content [kg/m^2] (the unknown)</li>
 *   <li><b>A, B</b> — empirical vegetation parameters; band &amp;
 *       polarisation dependent</li>
 *   <li><b>theta</b> — local incidence angle</li>
 * </ul>
 *
 * <p>Inversion: bisection on V &gt; 0 such that the WCM forward model matches
 * sigma0_obs. Bracket: [0, V_max]. Output: vegetation water content V,
 * vegetation contribution sigma0_veg, attenuated soil contribution
 * tau^2 * sigma0_soil.</p>
 *
 * <p>Literature parameter values:</p>
 * <ul>
 *   <li>C-band VV: A &asymp; 0.0012, B &asymp; 0.091 (Bindlish &amp; Barros 2001)</li>
 *   <li>C-band VH: A &asymp; 0.0014, B &asymp; 0.084 (Bindlish &amp; Barros 2001)</li>
 *   <li>L-band HV: A &asymp; 0.0026, B &asymp; 0.025 (Fassoni-Andrade et al. 2020)</li>
 * </ul>
 *
 * <p>Inputs:</p>
 * <ul>
 *   <li>sigma0 band (observed backscatter, linear or dB)</li>
 *   <li>sigma0_soil band (linear) <i>or</i> a constant fixed-soil-backscatter</li>
 *   <li>Optional per-pixel incidence-angle band (defaults to TPG / constant)</li>
 * </ul>
 *
 * <p>Outputs:</p>
 * <ul>
 *   <li><code>vegWaterContent</code> [kg/m^2] — V</li>
 *   <li><code>vegBackscatter</code> [linear] — sigma0_veg</li>
 *   <li><code>soilBackscatterAttenuated</code> [linear] — tau^2 * sigma0_soil</li>
 * </ul>
 *
 * <p>The operator is intentionally band-agnostic; users supply A, B and
 * a soil model upstream. Soil moisture retrieval itself remains in
 * {@code rstb-op-soil-moisture}; this op only handles the
 * <i>decoupling</i> step.</p>
 */
@OperatorMetadata(alias = "WCM-Soil-Vegetation-Decoupling",
        category = "Radar/Biomass",
        authors = "SkyWatch",
        version = "1.0",
        copyright = "Copyright (C) 2026 by SkyWatch Space Applications Inc.",
        description = "Water Cloud Model soil/vegetation backscatter decoupling (Attema & Ulaby 1978).")
public final class WCMSoilVegetationOp extends Operator {

    @SourceProduct(alias = "source")
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(description = "Observed backscatter band (sigma0 / gamma0).",
            rasterDataNodeType = Band.class,
            label = "Backscatter Band")
    private String backscatterBandName;

    @Parameter(description = "Optional bare-soil backscatter band (linear). If empty, use the constant fixedSoilSigma0.",
            rasterDataNodeType = Band.class,
            label = "Soil Backscatter Band (Linear, Optional)")
    private String soilBackscatterBandName;

    @Parameter(description = "Optional per-pixel incidence-angle band [degrees]. If empty, fall back to TPG / constant.",
            rasterDataNodeType = Band.class,
            label = "Incidence-Angle Band (Optional)")
    private String incidenceAngleBandName;

    @Parameter(description = "True if input backscatter is in dB; false for linear.",
            defaultValue = "true",
            label = "Backscatter in dB")
    private boolean backscatterIsDb = true;

    @Parameter(description = "Fixed bare-soil sigma0 [linear] when no soil band is provided. "
            + "Default 0.05 ~ -13 dB (moist bare soil at C-band, mid-incidence).",
            defaultValue = "0.05",
            label = "Fixed Soil sigma0 [linear]")
    private double fixedSoilSigma0 = 0.05;

    @Parameter(description = "Fixed incidence angle [degrees] when no band/TPG is available.",
            defaultValue = "30.0",
            label = "Fixed Incidence Angle [deg]")
    private double fixedIncidenceDeg = 30.0;

    @Parameter(description = "WCM vegetation parameter A.",
            defaultValue = "0.0014",
            label = "Parameter A")
    private double paramA = 0.0014;

    @Parameter(description = "WCM vegetation parameter B [m^2/kg].",
            defaultValue = "0.084",
            label = "Parameter B")
    private double paramB = 0.084;

    @Parameter(description = "Maximum vegetation water content searched [kg/m^2]. Higher = more iterations but supports denser canopies.",
            defaultValue = "8.0",
            label = "Max V [kg/m^2]")
    private double maxV = 8.0;

    private static final double NO_DATA_VALUE = -1.0;
    private static final String PRODUCT_SUFFIX = "_WCM";
    private static final String VEG_WC_BAND_NAME = "vegWaterContent";
    private static final String VEG_BS_BAND_NAME = "vegBackscatter";
    private static final String SOIL_ATTEN_BAND_NAME = "soilBackscatterAttenuated";

    private Band backscatterBand;
    private Band soilBand;
    private Band incidenceBand;
    private TiePointGrid incidenceTpg;

    private Band vegWcBand;
    private Band vegBsBand;
    private Band soilAttenBand;

    private double backscatterNoData = Double.NaN;

    @Override
    public void initialize() throws OperatorException {
        try {
            if (backscatterBandName == null || backscatterBandName.isEmpty()) {
                throw new OperatorException("Backscatter band name required.");
            }
            backscatterBand = sourceProduct.getBand(backscatterBandName);
            if (backscatterBand == null) {
                throw new OperatorException("Backscatter band '" + backscatterBandName + "' not found.");
            }
            backscatterNoData = backscatterBand.isNoDataValueUsed() ? backscatterBand.getNoDataValue() : Double.NaN;

            if (soilBackscatterBandName != null && !soilBackscatterBandName.isEmpty()) {
                soilBand = sourceProduct.getBand(soilBackscatterBandName);
                if (soilBand == null) {
                    throw new OperatorException("Soil backscatter band '" + soilBackscatterBandName + "' not found.");
                }
            }

            if (incidenceAngleBandName != null && !incidenceAngleBandName.isEmpty()) {
                incidenceBand = sourceProduct.getBand(incidenceAngleBandName);
            }
            if (incidenceBand == null) {
                incidenceTpg = sourceProduct.getTiePointGrid("incident_angle");
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

        vegWcBand = targetProduct.addBand(VEG_WC_BAND_NAME, ProductData.TYPE_FLOAT32);
        vegWcBand.setUnit("kg/m^2");
        vegWcBand.setNoDataValue(NO_DATA_VALUE);
        vegWcBand.setNoDataValueUsed(true);
        vegWcBand.setDescription("WCM vegetation water content V");

        vegBsBand = targetProduct.addBand(VEG_BS_BAND_NAME, ProductData.TYPE_FLOAT32);
        vegBsBand.setUnit("linear");
        vegBsBand.setNoDataValue(NO_DATA_VALUE);
        vegBsBand.setNoDataValueUsed(true);
        vegBsBand.setDescription("WCM vegetation backscatter contribution");

        soilAttenBand = targetProduct.addBand(SOIL_ATTEN_BAND_NAME, ProductData.TYPE_FLOAT32);
        soilAttenBand.setUnit("linear");
        soilAttenBand.setNoDataValue(NO_DATA_VALUE);
        soilAttenBand.setNoDataValueUsed(true);
        soilAttenBand.setDescription("Attenuated soil backscatter tau^2 * sigma0_soil");
    }

    @Override
    public void computeTileStack(final Map<Band, Tile> targetTileMap, final Rectangle rect,
                                 final ProgressMonitor pm) throws OperatorException {
        try {
            final Tile bsTile = getSourceTile(backscatterBand, rect);
            final ProductData bsData = bsTile.getDataBuffer();
            final TileIndex bsIndex = new TileIndex(bsTile);

            final Tile soilTile = (soilBand != null) ? getSourceTile(soilBand, rect) : null;
            final ProductData soilData = (soilTile != null) ? soilTile.getDataBuffer() : null;
            final TileIndex soilIndex = (soilTile != null) ? new TileIndex(soilTile) : null;

            final Tile incTile = (incidenceBand != null) ? getSourceTile(incidenceBand, rect) : null;
            final ProductData incData = (incTile != null) ? incTile.getDataBuffer() : null;
            final TileIndex incIndex = (incTile != null) ? new TileIndex(incTile) : null;

            final Tile vegWcTile = targetTileMap.get(vegWcBand);
            final Tile vegBsTile = targetTileMap.get(vegBsBand);
            final Tile soilAttenTile = targetTileMap.get(soilAttenBand);
            final ProductData vegWcData = vegWcTile.getDataBuffer();
            final ProductData vegBsData = vegBsTile.getDataBuffer();
            final ProductData soilAttenData = soilAttenTile.getDataBuffer();
            final TileIndex tgtIndex = new TileIndex(vegWcTile);

            final int x0 = rect.x;
            final int y0 = rect.y;
            final int xMax = x0 + rect.width;
            final int yMax = y0 + rect.height;

            final Result tmp = new Result();

            for (int y = y0; y < yMax; y++) {
                bsIndex.calculateStride(y);
                tgtIndex.calculateStride(y);
                if (soilIndex != null) soilIndex.calculateStride(y);
                if (incIndex != null) incIndex.calculateStride(y);

                for (int x = x0; x < xMax; x++) {
                    double bs = bsData.getElemDoubleAt(bsIndex.getIndex(x));
                    if (bs != backscatterNoData && !Double.isNaN(bs)) {
                        if (backscatterIsDb) bs = Math.pow(10.0, bs / 10.0);
                    } else {
                        bs = Double.NaN;
                    }

                    final double soil = (soilData != null)
                            ? soilData.getElemDoubleAt(soilIndex.getIndex(x))
                            : fixedSoilSigma0;
                    final double theta;
                    if (incData != null) {
                        theta = incData.getElemDoubleAt(incIndex.getIndex(x));
                    } else if (incidenceTpg != null) {
                        theta = incidenceTpg.getPixelDouble(x, y);
                    } else {
                        theta = fixedIncidenceDeg;
                    }

                    final int idx = tgtIndex.getIndex(x);
                    if (Double.isNaN(bs) || soil <= 0.0 || theta <= 0.0 || theta >= 90.0) {
                        vegWcData.setElemFloatAt(idx, (float) NO_DATA_VALUE);
                        vegBsData.setElemFloatAt(idx, (float) NO_DATA_VALUE);
                        soilAttenData.setElemFloatAt(idx, (float) NO_DATA_VALUE);
                        continue;
                    }

                    invertWCM(bs, soil, theta, paramA, paramB, maxV, tmp);
                    vegWcData.setElemFloatAt(idx, (float) tmp.V);
                    vegBsData.setElemFloatAt(idx, (float) tmp.vegBackscatter);
                    soilAttenData.setElemFloatAt(idx, (float) tmp.soilAttenuated);
                }
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        } finally {
            pm.done();
        }
    }

    /** Per-pixel WCM inversion result. */
    public static final class Result {
        public double V = NO_DATA_VALUE;
        public double vegBackscatter = NO_DATA_VALUE;
        public double soilAttenuated = NO_DATA_VALUE;
    }

    /**
     * Invert the Water Cloud Model for vegetation water content V given the
     * observed backscatter, soil backscatter, incidence angle, and model
     * parameters A, B.
     *
     * <p>Forward model:
     * <code>f(V) = A*V*cos(theta)*(1 - tau^2) + tau^2 * sigma0_soil</code>
     * with <code>tau^2 = exp(-2*B*V/cos(theta))</code>.</p>
     *
     * <p>f(V) is monotonically increasing in V on (0, V_max] when
     * <code>A * cos(theta) &gt; B * sigma0_soil</code> (typical case for
     * forest at moderate incidence) and monotonically decreasing otherwise
     * (when soil dominates). Either way, bisection converges to a root
     * provided sigma0_obs is bracketed.</p>
     */
    static void invertWCM(final double sigma0Obs, final double sigma0Soil,
                          final double thetaDeg,
                          final double A, final double B, final double vMax,
                          final Result out) {
        out.V = NO_DATA_VALUE;
        out.vegBackscatter = NO_DATA_VALUE;
        out.soilAttenuated = NO_DATA_VALUE;

        final double cosT = Math.cos(Math.toRadians(thetaDeg));
        if (!(cosT > 1.0e-6)) return;

        // Evaluate forward model at the bracket endpoints.
        final double fLo = forwardWCM(0.0, sigma0Soil, A, B, cosT);  // = sigma0_soil
        final double fHi = forwardWCM(vMax, sigma0Soil, A, B, cosT);

        if (sigma0Obs < Math.min(fLo, fHi) || sigma0Obs > Math.max(fLo, fHi)) {
            // Out of bracket — possible model mismatch (snow, urban, water).
            return;
        }

        double lo = 0.0;
        double hi = vMax;
        double fL = fLo;
        for (int iter = 0; iter < 60; iter++) {
            final double mid = 0.5 * (lo + hi);
            final double fM = forwardWCM(mid, sigma0Soil, A, B, cosT);
            // Decide bracket half by sign of (fM - sigma0Obs) relative to (fL - sigma0Obs).
            if ((fM - sigma0Obs) * (fL - sigma0Obs) <= 0.0) {
                hi = mid;
            } else {
                lo = mid;
                fL = fM;
            }
            if (hi - lo < 1.0e-6) break;
        }
        final double V = 0.5 * (lo + hi);
        final double tau2 = Math.exp(-2.0 * B * V / cosT);
        out.V = V;
        out.vegBackscatter = A * V * cosT * (1.0 - tau2);
        out.soilAttenuated = tau2 * sigma0Soil;
    }

    private static double forwardWCM(final double V, final double sigma0Soil,
                                     final double A, final double B, final double cosT) {
        final double tau2 = Math.exp(-2.0 * B * V / cosT);
        return A * V * cosT * (1.0 - tau2) + tau2 * sigma0Soil;
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(WCMSoilVegetationOp.class);
        }
    }
}
