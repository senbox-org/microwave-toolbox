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
 * Each closure is wrapped to (-pi, pi] before it is squared, and the RMS is taken over the
 * triplet set. The wrap removes whole multiples of 2 pi, so this metric does NOT reveal
 * unwrapping errors (a 2 pi jump in one pair closes to 0); it measures the non-integer
 * inconsistency of the network (noise, phase-bias / fading signal, filtering artefacts).
 * When there is no closed triplet the closure is undefined and NaN is returned, so that a
 * sequential network is not mistaken for a perfectly closed one.
 */
public final class ClosurePhase {

    private ClosurePhase() {
    }

    /**
     * @param network        the SBAS network
     * @param unwrappedPhase length-M unwrapped phase observations
     * @return RMS wrapped closure phase (radians), or NaN when the network has no triplets
     */
    public static double rms(final Network network, final double[] unwrappedPhase) {
        return rms(network, unwrappedPhase, null);
    }

    /**
     * RMS closure over the triplets whose three pairs are all used at this pixel.
     *
     * @param network        the SBAS network
     * @param unwrappedPhase length-M unwrapped phase observations
     * @param weights        length-M pair weights; a triplet with any weight &lt;= 0 (pair dropped,
     *                       e.g. below the coherence threshold) is skipped. Null uses every triplet.
     * @return RMS wrapped closure phase (radians), or NaN when no triplet is usable
     */
    public static double rms(final Network network, final double[] unwrappedPhase, final double[] weights) {
        final int Q = network.numTriplets();
        double sum = 0.0;
        int used = 0;
        for (int q = 0; q < Q; q++) {
            final int[] t = network.triplet(q);
            if (weights != null && (!(weights[t[0]] > 0.0) || !(weights[t[1]] > 0.0) || !(weights[t[2]] > 0.0))) {
                continue;
            }
            final double phiAB = unwrappedPhase[t[0]];
            final double phiBC = unwrappedPhase[t[1]];
            final double phiAC = unwrappedPhase[t[2]];
            final double c = wrap(phiAB + phiBC - phiAC);
            sum += c * c;
            used++;
        }
        return used == 0 ? Double.NaN : Math.sqrt(sum / used);
    }

    /** Wrap an angle to (-pi, pi]. */
    public static double wrap(final double phi) {
        double p = phi;
        while (p > Math.PI) p -= 2.0 * Math.PI;
        while (p <= -Math.PI) p += 2.0 * Math.PI;
        return p;
    }
}
