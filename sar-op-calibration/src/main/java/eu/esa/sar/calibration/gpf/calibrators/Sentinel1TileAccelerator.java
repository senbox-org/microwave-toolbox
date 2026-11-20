/*
 * Copyright (C) 2026 by Michalis Papadimitriou
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
package eu.esa.sar.calibration.gpf.calibrators;

import eu.esa.sar.commons.Sentinel1Utils;

/**
 * Optional accelerated version of the per-pixel loop in {@link Sentinel1Calibrator#computeTile}, for
 * detected (amplitude) input such as GRD products.
 * <p>
 * Implementations are found with {@link java.util.ServiceLoader} and are only used when the
 * {@value Sentinel1Calibrator#USE_TORNADOVM_PREFERENCE} preference is set. The Java loop stays the
 * reference: anything the accelerator does not cover, or a failure, falls back to it.
 */
public interface Sentinel1TileAccelerator {

    /**
     * Input and output of one tile. The pixel arrays are dense and row-major, {@code width * height}.
     *
     * @param width          tile width in pixels
     * @param height         tile height in pixels
     * @param firstPixelX    x of the first tile column in the full swath (tile x0 plus subset offset)
     * @param amplitude      source amplitude samples
     * @param noDataValue    source no-data value; NaN samples are treated the same way
     * @param vectors        calibration vectors of the swath
     * @param calType        LUT used for the output band
     * @param rowVectorIndex index of the calibration vector before each tile row
     * @param rowMuY         azimuth interpolation weight of each tile row
     * @param target         receives the calibrated values
     */
    record Request(int width, int height, int firstPixelX,
                   float[] amplitude, float noDataValue,
                   Sentinel1Utils.CalibrationVector[] vectors, Sentinel1Calibrator.CALTYPE calType,
                   int[] rowVectorIndex, float[] rowMuY,
                   float[] target) {
    }

    /**
     * Calibrates one tile.
     *
     * @param request tile input and output buffers
     * @return {@code true} if {@link Request#target} was filled, {@code false} if the caller should use
     * the Java loop instead
     */
    boolean calibrate(Request request);
}
