/*
 * Copyright (C) 2018 SkyWatch Space Applications Inc. https://www.skywatch.com
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
package org.csa.rstb.polarimetric.gpf.support;

/**
 * Polarimetric parameters extracted from a 2x2 (compact-pol or dual-pol)
 * covariance matrix.
 *
 * <p><b>Status:</b> v1 only computes {@link #Span}. The
 * {@link #PedestalHeight} and {@link #RVI} fields are returned as zero
 * and are reserved for future implementation
 * (Cloude 2007 pedestal-height; Kim & van Zyl 2009 / Trudel et al. 2012
 * RVI families).</p>
 */
public class PolarimetricParameters {

    /** Total backscatter power: Span = C11 + C22. */
    public double Span;

    /** Reserved for future implementation. Currently always 0. */
    public double PedestalHeight;

    /** Reserved for future implementation. Currently always 0. */
    public double RVI;

    /**
     * Compute polarimetric parameters for the given 2x2 complex covariance
     * matrix.
     *
     * @param Cr               real part of the mean covariance matrix
     * @param Ci               imaginary part of the mean covariance matrix
     * @param compactMode      "RCH" or "LCH" (right- / left-circular hybrid)
     * @param useRCMConvention RCM polarisation convention flag
     * @return populated parameter object
     */
    public static PolarimetricParameters computePolarimetricParameters(
            final double[][] Cr, final double[][] Ci, final String compactMode, final boolean useRCMConvention) {
        final PolarimetricParameters parameters = new PolarimetricParameters();
        parameters.Span = Cr[0][0] + Cr[1][1];
        parameters.PedestalHeight = 0.0;
        parameters.RVI = 0.0;
        return parameters;
    }
}
