/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.calibration.gpf.support;

/**
 * Per-pixel thermal-noise subtraction in the linear power domain.
 * Follows ESA MPC-0392 v2.3 section 6: a noise value of 0 means the noise is unknown (Eq-1);
 * negative results are optionally clipped (Eq-2), or kept for later averaging (Eq-3).
 */
public final class NoiseSubtraction {

    private NoiseSubtraction() {
    }

    /**
     * @param power power value (|DN|^2 or a calibrated backscatter coefficient)
     * @param noise noise in the same units; 0 = unknown; negative = re-introduce noise; NaN or infinite
     *              (e.g. a zero calibration LUT) is treated as unknown like 0, so the power passes through
     * @param clip  replace negative results by {@code floor}
     * @param floor value used for clipped pixels (0 for ESA Eq-2, 1e-5 for the historic SNAP behaviour)
     */
    public static double apply(final double power, final double noise, final boolean clip, final double floor) {
        if (noise == 0.0 || !Double.isFinite(noise)) {
            return power;
        }
        final double value = power - noise;
        return (clip && value < 0.0) ? floor : value;
    }
}
