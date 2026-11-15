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

import eu.esa.sar.calibration.gpf.calibrators.Sentinel1Calibrator;
import eu.esa.sar.calibration.gpf.calibrators.Sentinel1TileAccelerator;
import eu.esa.sar.commons.Sentinel1Utils;
import org.esa.snap.core.util.SystemUtils;
import uk.ac.manchester.tornado.api.GridScheduler;
import uk.ac.manchester.tornado.api.KernelContext;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.WorkerGrid2D;
import uk.ac.manchester.tornado.api.common.TornadoDevice;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.runtime.TornadoRuntimeProvider;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs {@link Sentinel1CalibrationKernel} through TornadoVM on the TornadoVM default device. With
 * several backends installed, {@code -Dtornado.enabled.backends=opencl|cuda|metal} picks one.
 * <p>
 * One execution plan is kept per set of calibration LUTs (swath, polarisation and output type), for
 * the last {@value #MAX_PLANS} sets used. The LUTs are copied to the device once; the tile buffers are
 * sized for the largest tile seen so far and copied on every call. SNAP computes tiles from several
 * threads, so calls are serialised.
 */
public final class TornadoVmSentinel1TileAccelerator implements Sentinel1TileAccelerator {

    private static final int BLOCK = 16;
    private static final int MAX_PLANS = 8;
    private static final AtomicLong TILES_CALIBRATED = new AtomicLong();

    /** Calibration vectors are compared by identity: each calibrator instance reads its own. */
    private record LutKey(Sentinel1Utils.CalibrationVector[] vectors, Sentinel1Calibrator.CALTYPE calType) {
    }

    private final Map<LutKey, TilePlan> plans = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<LutKey, TilePlan> eldest) {
            if (size() > MAX_PLANS) {
                eldest.getValue().close();
                return true;
            }
            return false;
        }
    };

    /** Number of tiles computed by all instances, for tests and diagnostics. */
    static long tilesCalibrated() {
        return TILES_CALIBRATED.get();
    }

    @Override
    public synchronized boolean calibrate(final Request request) {
        final LutKey key = new LutKey(request.vectors(), request.calType());
        TilePlan plan = plans.get(key);
        if (plan == null || request.width() > plan.width || request.height() > plan.height) {
            final int width = plan == null ? request.width() : Math.max(plan.width, request.width());
            final int height = plan == null ? request.height() : Math.max(plan.height, request.height());
            if (plan != null) {
                plan.close();
            }
            plan = new TilePlan(request.vectors(), request.calType(), width, height);
            plans.put(key, plan);
        }
        plan.run(request);
        TILES_CALIBRATED.incrementAndGet();
        return true;
    }

    /** Device buffers and the compiled execution plan for one LUT set. */
    private static final class TilePlan implements AutoCloseable {
        final int width;
        final int height;
        final IntArray intParams = new IntArray(Sentinel1CalibrationKernel.INT_PARAMS);
        final FloatArray noData = new FloatArray(1);
        final FloatArray amplitude;
        final IntArray rowVector;
        final FloatArray rowMuY;
        final FloatArray target;
        final TornadoExecutionPlan executionPlan;

        TilePlan(final Sentinel1Utils.CalibrationVector[] vectors, final Sentinel1Calibrator.CALTYPE calType,
                 final int width, final int height) {
            this.width = width;
            this.height = height;
            amplitude = new FloatArray(width * height);
            target = new FloatArray(width * height);
            rowVector = new IntArray(height);
            rowMuY = new FloatArray(height);

            final IntArray offsets = new IntArray(vectors.length);
            final IntArray counts = new IntArray(vectors.length);
            int total = 0;
            for (int v = 0; v < vectors.length; v++) {
                offsets.set(v, total);
                counts.set(v, vectors[v].pixels.length);
                total += vectors[v].pixels.length;
            }
            final IntArray pixels = new IntArray(total);
            final FloatArray lut = new FloatArray(total);
            for (int v = 0; v < vectors.length; v++) {
                final float[] lutValues = Sentinel1Calibrator.getVector(calType, vectors[v]);
                for (int p = 0; p < vectors[v].pixels.length; p++) {
                    pixels.set(offsets.get(v) + p, vectors[v].pixels[p]);
                    lut.set(offsets.get(v) + p, lutValues[p]);
                }
            }

            final TaskGraph graph = new TaskGraph("s1cal")
                    .transferToDevice(DataTransferMode.FIRST_EXECUTION, pixels, offsets, counts, lut)
                    .transferToDevice(DataTransferMode.EVERY_EXECUTION, intParams, noData, amplitude, rowVector, rowMuY)
                    .task("calibrate", Sentinel1CalibrationKernel::calibrate, new KernelContext(), intParams, noData,
                            amplitude, rowVector, rowMuY, pixels, offsets, counts, lut, target)
                    .transferToHost(DataTransferMode.EVERY_EXECUTION, target);

            final WorkerGrid2D worker = new WorkerGrid2D(roundUp(width), roundUp(height));
            worker.setLocalWork(BLOCK, BLOCK, 1);
            executionPlan = new TornadoExecutionPlan(graph.snapshot())
                    .withGridScheduler(new GridScheduler("s1cal.calibrate", worker));

            final TornadoDevice device = TornadoRuntimeProvider.getTornadoRuntime().getDefaultDevice();
            SystemUtils.LOG.info("Sentinel-1 calibration: TornadoVM " + device.getTornadoVMBackend() +
                    " device " + device.getPhysicalDevice().getDeviceName() + ", " + width + " x " + height +
                    " tiles, " + vectors.length + " calibration vectors");
        }

        void run(final Request request) {
            final int pixelsInTile = request.width() * request.height();
            intParams.set(Sentinel1CalibrationKernel.P_WIDTH, request.width());
            intParams.set(Sentinel1CalibrationKernel.P_HEIGHT, request.height());
            intParams.set(Sentinel1CalibrationKernel.P_FIRST_PIXEL_X, request.firstPixelX());
            noData.set(0, request.noDataValue());
            for (int r = 0; r < request.height(); r++) {
                rowVector.set(r, request.rowVectorIndex()[r]);
                rowMuY.set(r, request.rowMuY()[r]);
            }
            MemorySegment.copy(request.amplitude(), 0, amplitude.getSegment(), ValueLayout.JAVA_FLOAT, 0, pixelsInTile);

            executionPlan.execute();

            MemorySegment.copy(target.getSegment(), ValueLayout.JAVA_FLOAT, 0, request.target(), 0, pixelsInTile);
        }

        @Override
        public void close() {
            try {
                executionPlan.close();
            } catch (Exception e) {
                SystemUtils.LOG.fine("Sentinel-1 calibration: closing TornadoVM plan failed: " + e);
            }
        }

        private static int roundUp(final int value) {
            return (value + BLOCK - 1) / BLOCK * BLOCK;
        }
    }
}
