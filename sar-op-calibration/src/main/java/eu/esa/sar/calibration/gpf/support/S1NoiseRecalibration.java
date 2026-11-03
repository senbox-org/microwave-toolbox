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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * Sentinel-1 noise vector retro-calibration (Recchia et al. 2025, Remote Sens. 17:3474, section 4.1):
 * n_new = n_old * 10^(k/10) with the noise calibration constant updates of Tables 4 (S1A) and 5 (S1B).
 * Valid for products generated from IPF 3.1.0 on and before the AUX_CAL update carrying these constants.
 */
public final class S1NoiseRecalibration {

    /** First IPF version for which the retro-calibration is valid. */
    public static final double MIN_IPF_VERSION = 3.10;

    static final String RESOURCE = "s1_noise_recalibration.properties";

    // loaded on first use: a missing resource fails the call with a clear message, not class initialisation
    private static volatile Properties constants;

    private S1NoiseRecalibration() {
    }

    /**
     * @param platform S1A, S1B, ...
     * @param swath    IW1..IW3, EW1..EW5
     * @param rxPol    receive polarisation, 'V' or 'H'
     * @return the linear factor, or 1 when no constant is published
     * @throws IllegalStateException if the constants resource is missing
     */
    public static double linearFactor(final String platform, final String swath, final char rxPol) {
        Properties p = constants;
        if (p == null) {
            p = load(RESOURCE);
            constants = p;
        }
        final String value = p.getProperty(platform + '.' + swath + '.' + rxPol);
        return value == null ? 1.0 : Math.pow(10.0, Double.parseDouble(value) / 10.0);
    }

    static Properties load(final String resourceName) {
        final Properties p = new Properties();
        try (InputStream in = S1NoiseRecalibration.class.getResourceAsStream(resourceName)) {
            if (in == null) {
                throw new IllegalStateException("Noise retro-calibration constants not found: resource " +
                        resourceName + " is missing from " + S1NoiseRecalibration.class.getPackageName());
            }
            p.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p;
    }
}
