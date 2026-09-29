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
package eu.esa.sar.insar.gpf.phaselinking;

/**
 * Statistically-Homogeneous-Pixel (SHP) selector.
 *
 * Given the amplitude time series of a candidate pixel and a reference
 * (centre) pixel - both length N over the stack - decide whether the
 * candidate shares the centre's amplitude distribution.
 *
 * Two-sample tests (Ferretti SqueeSAR 2011, FRInGE / MiaplPy):
 *  - {@link KSSelector}        two-sample Kolmogorov-Smirnov (default)
 *  - {@link ADSelector}        two-sample Anderson-Darling
 *  - {@link TLogSelector}      Welch t-test on log-amplitude (fast mode)
 */
public interface SHPSelector {

    /**
     * @param centre    length-N amplitudes of the centre pixel (the one being phase-linked)
     * @param candidate length-N amplitudes of the candidate neighbour
     * @return true if the candidate is statistically homogeneous to centre
     */
    boolean accept(double[] centre, double[] candidate);

    /**
     * Hook called once per centre pixel so the implementation can cache
     * derived quantities (sorted copy, log-mean, log-var, etc.).
     */
    default void prepareCentre(double[] centre) {
        // default no-op
    }
}
