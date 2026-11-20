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
package eu.esa.sar.calibration.gpf.calibrators.tornadovm;

import uk.ac.manchester.tornado.api.KernelContext;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Per-pixel Sentinel-1 calibration of amplitude input, the inner loop of
 * {@link eu.esa.sar.calibration.gpf.calibrators.Sentinel1Calibrator#computeTile} written as a TornadoVM
 * kernel. One thread computes one pixel of a dense, row-major tile.
 */
public final class Sentinel1CalibrationKernel {

    // Indices into the integer parameter buffer.
    static final int P_WIDTH = 0;
    static final int P_HEIGHT = 1;
    static final int P_FIRST_PIXEL_X = 2;
    static final int INT_PARAMS = 3;

    private Sentinel1CalibrationKernel() {
    }

    /**
     * @param intParams     tile width, tile height, x of the first column in the swath
     * @param noData        single element: the source no-data value
     * @param amplitude     source amplitude samples
     * @param rowVector     calibration vector index before each row
     * @param rowMuY        azimuth interpolation weight of each row
     * @param pixels        range pixel positions of all calibration vectors, concatenated
     * @param vectorOffsets start of each calibration vector in {@code pixels} and {@code lut}
     * @param vectorCounts  number of positions of each calibration vector
     * @param lut           calibration LUT of the output band, laid out like {@code pixels}
     * @param target        calibrated output
     */
    public static void calibrate(KernelContext context, IntArray intParams, FloatArray noData,
                                 FloatArray amplitude, IntArray rowVector, FloatArray rowMuY,
                                 IntArray pixels, IntArray vectorOffsets, IntArray vectorCounts,
                                 FloatArray lut, FloatArray target) {
        int x = context.globalIdx;
        int y = context.globalIdy;
        int width = intParams.get(P_WIDTH);
        if (x < width && y < intParams.get(P_HEIGHT)) {
            int k = y * width + x;
            float dn = amplitude.get(k);
            float noDataValue = noData.get(0);
            if (dn != dn || dn == noDataValue) {
                target.set(k, noDataValue);
            } else {
                int v0 = rowVector.get(y);
                int o0 = vectorOffsets.get(v0);
                int o1 = vectorOffsets.get(v0 + 1);
                int count = vectorCounts.get(v0);
                int pixelX = intParams.get(P_FIRST_PIXEL_X) + x;

                // Interval of the calibration vector that contains pixelX, as Sentinel1Calibrator.getPixelIndex.
                int lo = 0;
                int hi = count - 1;
                int idx = -1;
                while (lo <= hi) {
                    int mid = (lo + hi) >>> 1;
                    if (pixels.get(o0 + mid) <= pixelX) {
                        idx = mid;
                        lo = mid + 1;
                    } else {
                        hi = mid - 1;
                    }
                }
                if (idx >= count - 1) {
                    idx = count - 2;
                }
                if (idx < 0) {
                    idx = 0;
                }

                int p0 = pixels.get(o0 + idx);
                float muX = (pixelX - p0) / (float) (pixels.get(o0 + idx + 1) - p0);
                float muY = rowMuY.get(y);
                float lutVal = (1 - muY) * ((1 - muX) * lut.get(o0 + idx) + muX * lut.get(o0 + idx + 1))
                        + muY * ((1 - muX) * lut.get(o1 + idx) + muX * lut.get(o1 + idx + 1));
                float calibrationFactor = 1.0f / (lutVal * lutVal);
                target.set(k, dn * dn * calibrationFactor);
            }
        }
    }
}
