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
package eu.esa.sar.insar.gpf.timeseries;

/**
 * Per-triplet closure phase and RMS aggregate.
 *
 *   phi_closure(a, b, c) = phi_{a,b} + phi_{b,c} - phi_{a,c}
 *
 * A wrap-corrected stack has all closures near zero; multiples of 2 pi
 * indicate unwrapping errors and feed downstream bridging / triplet
 * correction.
 *
 * For each triplet we compute the magnitude of the closure modulo 2 pi
 * (sign carried via atan2 of the residual complex phasor) and aggregate
 * the RMS over the triplet set.
 */
public final class ClosurePhase {

    private ClosurePhase() {
    }

    /**
     * @param network        the SBAS network
     * @param unwrappedPhase length-M unwrapped phase observations
     * @return RMS closure phase (radians), or 0 when there are no triplets
     */
    public static double rms(final Network network, final double[] unwrappedPhase) {
        final int Q = network.numTriplets();
        if (Q == 0) return 0.0;
        double sum = 0.0;
        for (int q = 0; q < Q; q++) {
            final int[] t = network.triplet(q);
            final double phiAB = unwrappedPhase[t[0]];
            final double phiBC = unwrappedPhase[t[1]];
            final double phiAC = unwrappedPhase[t[2]];
            final double c = wrap(phiAB + phiBC - phiAC);
            sum += c * c;
        }
        return Math.sqrt(sum / Q);
    }

    /** Wrap an angle to (-pi, pi]. */
    public static double wrap(final double phi) {
        double p = phi;
        while (p > Math.PI) p -= 2.0 * Math.PI;
        while (p <= -Math.PI) p += 2.0 * Math.PI;
        return p;
    }
}
