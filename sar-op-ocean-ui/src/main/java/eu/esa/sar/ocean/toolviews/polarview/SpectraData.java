/*
 * Copyright (C) 2015 by Array Systems Computing Inc. http://www.array.ca
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
package eu.esa.sar.ocean.toolviews.polarview;

import eu.esa.sar.ocean.toolviews.polarview.polarplot.PolarData;

/**
 * Ocean Swell Spectra Data Interface
 */
public interface SpectraData {

    enum WaveProductType {CROSS_SPECTRA, WAVE_SPECTRA}

    enum SpectraUnit {REAL, IMAGINARY, AMPLITUDE, INTENSITY}

    void setWaveProductType(final SpectraData.WaveProductType waveProductType);

    SpectraData.WaveProductType getWaveProductType();

    String[] getSpectraMetadata(final int rec) throws Exception;

    PolarData getPolarData(final int currentRec, final SpectraUnit spectraUnit) throws Exception;

    String[] updateReadouts(final double[] rTh, final int currentRecord);

    double getWindSpeed();

    double getWindDirection();

    int getNumRecords();

    double getMinRadius();

    double getMaxRadius();

    default int sign(final float f) {
        return f < 0.0F ? -1 : 1;
    }

    default boolean isASAR() {
        return this instanceof SpectraDataAsar;
    }
}
