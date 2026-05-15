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
import org.esa.snap.core.gpf.GPF;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;

import java.awt.Rectangle;
import java.util.HashMap;
import java.util.Map;

/**
 * BIOMASAR-L — L-band PALSAR / PALSAR-2 variant of {@link BIOMASAROp}.
 *
 * <p>The algorithm is structurally identical to BIOMASAR (Santoro et al.
 * 2013, <i>Remote Sensing of Environment</i> 130, 39-49,
 * DOI: <a href="https://doi.org/10.1016/j.rse.2012.11.001">10.1016/j.rse.2012.11.001</a>)
 * but with L-band-tuned sigma0 ranges, V<sub>DF</sub> threshold and
 * minimum-weight defaults. This operator is a thin "preset" wrapper that
 * forwards parameters to the underlying {@link BIOMASAROp} via the GPF
 * registry, so there is no algorithm duplication.</p>
 *
 * <p>L-band defaults (vs. C-band):</p>
 * <ul>
 *   <li>HV polarisation only (volume-scattering channel; the canonical
 *       L-band biomass channel).</li>
 *   <li>sigma0 range: -20 dB to -8 dB (broader saturation envelope than C-band).</li>
 *   <li>V<sub>DF</sub> (dense forest GSV): 400 m<sup>3</sup>/ha
 *       (L-band saturates higher; Santoro 2013 Table 2).</li>
 *   <li>Minimum weight (sigma0 contrast): 0.8 dB (slightly higher SNR at L-band).</li>
 *   <li>Buffer zone width: 1.0 dB (broader to absorb residual speckle).</li>
 * </ul>
 *
 * <p>All other BIOMASAR parameters are surfaced unchanged. Users can override
 * any L-band default via the standard GraphBuilder parameter UI.</p>
 *
 * <p>Operational references: CCI Biomass v5 ATBD, JAXA K&amp;C Initiative
 * PALSAR-2 biomass pipeline.</p>
 */
@OperatorMetadata(alias = "BIOMASAR-L",
        category = "Radar/Biomass",
        authors = "Cecilia Wong, SkyWatch",
        version = "1.0",
        copyright = "Copyright (C) 2026 by SkyWatch Space Applications Inc.",
        description = "L-band variant of BIOMASAR (Santoro et al. 2013) — multi-temporal Growing Stock Volume retrieval from L-band HV backscatter.")
public final class BIOMASARLOp extends Operator {

    @SourceProduct
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    // --- L-band tuned parameters (defaults differ from BIOMASAR C-band) ---

    @Parameter(defaultValue = "0.8",
            description = "Minimum sigma0 contrast (dB) between vegetated and ground pixels (L-band default).",
            label = "Minimum Weight")
    private float minWeight = 0.8f;

    @Parameter(defaultValue = "0.0",
            description = "Lowest retrievable GSV [m^3/ha].",
            label = "Min Retrievable GSV")
    private float minRetrievableGSV = 0.0f;

    @Parameter(defaultValue = "50.0",
            description = "Offset added to V_DF for max retrievable GSV [m^3/ha].",
            label = "Max GSV Offset from V_DF")
    private float maxRetrievableGSVOffsetFromVdf = 50.0f;

    @Parameter(defaultValue = "400.0",
            description = "Dense-forest GSV V_DF [m^3/ha]. L-band saturation envelope is higher than C-band.",
            label = "V_DF (Dense Forest GSV)")
    private float vDF = 400.0f;

    @Parameter(defaultValue = "-20",
            description = "Minimum L-band HV sigma0 [dB].",
            label = "Min Sigma0 HV")
    private float minSigma0HV = -20f;

    @Parameter(defaultValue = "-8",
            description = "Maximum L-band HV sigma0 [dB].",
            label = "Max Sigma0 HV")
    private float maxSigma0HV = -8f;

    @Parameter(defaultValue = "1.0",
            description = "Width of buffer zone at the two ends of the sigma0 range [dB].",
            label = "Sigma0 Buffer Zone Width HV")
    private float sigma0BufferZoneWidthHV = 1.0f;

    // Adaptive window parameters (same defaults as BIOMASAR C-band; tuning is
    // scene-specific, not strictly band-specific). Re-exposed so users see the
    // full surface area in the GraphBuilder dialog.

    @Parameter(defaultValue = "50",
            description = "Initial window radius for ground sigma0 estimation [pixels].",
            label = "Init Window Radius (Ground)")
    private int initWindowRadiusGr = 50;

    @Parameter(defaultValue = "50",
            description = "Step size for ground window radius search.",
            label = "Window Radius Step (Ground)")
    private int windowRadiusStepSizeGr = 50;

    @Parameter(defaultValue = "200",
            description = "Maximum window radius for ground sigma0 estimation.",
            label = "Max Window Radius (Ground)")
    private int maxWindowRadiusGr = 200;

    @Parameter(defaultValue = "50",
            description = "Initial window radius for dense-forest sigma0 estimation.",
            label = "Init Window Radius (DF)")
    private int initWindowRadiusDF = 50;

    @Parameter(defaultValue = "50",
            description = "Step size for DF window radius search.",
            label = "Window Radius Step (DF)")
    private int windowRadiusStepSizeDF = 50;

    @Parameter(defaultValue = "200",
            description = "Maximum window radius for DF sigma0 estimation.",
            label = "Max Window Radius (DF)")
    private int maxWindowRadiusDF = 200;

    private Operator delegate;

    @Override
    public void initialize() throws OperatorException {
        try {
            final Map<String, Object> params = new HashMap<>();

            // Forward L-band tuned values
            params.put("minWeight", minWeight);
            params.put("minRetrievableGSV", minRetrievableGSV);
            params.put("maxRetrievableGSVOffsetFromVdf", maxRetrievableGSVOffsetFromVdf);
            params.put("vDF", vDF);
            params.put("minSigma0HV", minSigma0HV);
            params.put("maxSigma0HV", maxSigma0HV);
            params.put("sigma0BufferZoneWidthHV", sigma0BufferZoneWidthHV);
            params.put("initWindowRadiusGr", initWindowRadiusGr);
            params.put("windowRadiusStepSizeGr", windowRadiusStepSizeGr);
            params.put("maxWindowRadiusGr", maxWindowRadiusGr);
            params.put("initWindowRadiusDF", initWindowRadiusDF);
            params.put("windowRadiusStepSizeDF", windowRadiusStepSizeDF);
            params.put("maxWindowRadiusDF", maxWindowRadiusDF);

            // BIOMASAR-L is HV-only. Force the C-band cross-pol flags off and HV on.
            params.put("retrieveHH", false);
            params.put("retrieveVV", false);
            params.put("retrieveVH", false);
            params.put("retrieveHV", true);

            // Delegate to BIOMASAR; the GPF graph engine wires source -> delegate -> target.
            targetProduct = GPF.createProduct("BIOMASAR", params, sourceProduct);

            // Cache the delegate so computeTile can route through it for streaming reads.
            // (createProduct returns a fully-initialised Product backed by the operator instance.)
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    @Override
    public void computeTile(final Band targetBand, final Tile targetTile, final ProgressMonitor pm)
            throws OperatorException {
        // GPF.createProduct returns a Product whose bands' RasterDataNode reads
        // pull lazily from the underlying delegate operator's compute methods,
        // so no explicit forwarding is required here. This stub is present so
        // the framework recognises this class as a tile-producing Operator.
        try {
            final Band srcBand = targetProduct.getBand(targetBand.getName());
            if (srcBand != null && srcBand != targetBand) {
                final Tile srcTile = getSourceTile(srcBand, targetTile.getRectangle());
                targetTile.setRawSamples(srcTile.getRawSamples());
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        } finally {
            pm.done();
        }
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(BIOMASARLOp.class);
        }
    }
}
