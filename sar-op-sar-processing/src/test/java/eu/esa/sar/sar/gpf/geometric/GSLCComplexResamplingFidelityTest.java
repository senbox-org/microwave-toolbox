/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc.
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
package eu.esa.sar.sar.gpf.geometric;

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.dataop.resamp.Resampling;
import org.esa.snap.core.dataop.resamp.ResamplingFactory;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.internal.TileImpl;
import org.junit.Test;

import java.awt.Rectangle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Interpolation-fidelity contract for the SM complex resampling stage of
 * {@link GSLCGeocodingOp} ({@code GSLCResamplingRaster} + the real SNAP
 * {@code BISINC_5_POINT} kernel machinery).
 * <p>
 * A focused SLC's range spectrum is BASEBAND: the absolute-range phase of each
 * scatterer is a per-scatterer constant that lives in the speckle, not a
 * lattice carrier on the sample grid. Multiplying every source column by
 * {@code exp(+j*4*pi*R(x)/lambda)} before the sinc kernel (the former
 * "pre-flatten" step) therefore injects a coherent carrier at the ALIASED
 * frequency {@code (4*pi*rangeSpacing/lambda) mod 2*pi}, shifting the range
 * spectrum before interpolation. For ERS (rangeSpacing 7.905 m, lambda
 * 0.0566 m) the alias is -0.4989 cycles/pixel — essentially Nyquist — so
 * sub-pixel interpolation of the pre-flattened signal collapses. Measured on
 * real ERS-1 SLC data: a 5-point sinc at mu=0.5 achieves coherence gamma=0.997
 * on the raw baseband i/q versus gamma=0.093 on the pre-flattened signal.
 * <p>
 * This test pins the contract with synthetic band-limited data at ERS-like
 * parameters: complex noise band-limited to 0.8x Nyquist, resampled at
 * fractional range positions through the real raster + kernel path, must agree
 * with the analytic band-limited reference with coherence gamma &gt; 0.95.
 * Against the pre-flatten implementation this test FAILS with gamma ~0.1;
 * with baseband interpolation (flatten applied AFTER the kernel) it passes.
 * <p>
 * The raster is driven through reflection so the same test compiles and runs
 * against both the legacy (pre-flatten, {@code setSlantRangeAtCenter}) and the
 * fixed (baseband, no center dependency) implementations: if a per-column
 * range carrier is ever reintroduced ahead of the kernel — with or without the
 * legacy restore convention — the coherence collapses and this test fails.
 */
public class GSLCComplexResamplingFidelityTest {

    // ERS-1/2 SLC geometry: the worst measured alias case (-0.4989 cyc/px).
    private static final double RANGE_SPACING = 7.90489;   // m
    private static final double WAVELENGTH = 0.0565646;    // m
    private static final double NEAR_EDGE_SLANT_RANGE = 845000.0; // m

    private static final int W = 256;
    private static final int H = 16;
    private static final int NUM_COMPONENTS = 128;
    private static final double BAND_LIMIT = 0.4; // |f| <= 0.4 cyc/px = 0.8 x Nyquist
    private static final double NO_DATA = -32768.0;

    /** Random band-limited complex signal: sum of unit phasors with seeded freq/phase. */
    private static final class BandLimitedSignal {
        final double[] fr = new double[NUM_COMPONENTS];
        final double[] fa = new double[NUM_COMPONENTS];
        final double[] ph = new double[NUM_COMPONENTS];

        BandLimitedSignal(long seed) {
            final Random rng = new Random(seed);
            for (int k = 0; k < NUM_COMPONENTS; k++) {
                fr[k] = (2.0 * rng.nextDouble() - 1.0) * BAND_LIMIT;
                fa[k] = (2.0 * rng.nextDouble() - 1.0) * BAND_LIMIT;
                ph[k] = 2.0 * Math.PI * rng.nextDouble();
            }
        }

        /** Exact analytic value at (possibly fractional) sample position (x, y). */
        double[] valueAt(final double x, final double y) {
            double re = 0.0, im = 0.0;
            for (int k = 0; k < NUM_COMPONENTS; k++) {
                final double arg = 2.0 * Math.PI * (fr[k] * x + fa[k] * y) + ph[k];
                re += Math.cos(arg);
                im += Math.sin(arg);
            }
            return new double[]{re, im};
        }
    }

    /** Reflection wrapper around the (private) GSLCResamplingRaster inner class. */
    private static final class RasterHandle {
        final Object instance;
        final Resampling.Raster raster;
        final Method setReturnReal;
        final Method setSlantRangeAtCenter; // null on the fixed (baseband) implementation

        RasterHandle(Tile tileI, Tile tileQ, double[] fdcPerColumn, double lineTimeIntervalSec)
                throws Exception {
            final Class<?> cls = Class.forName(
                    "eu.esa.sar.sar.gpf.geometric.GSLCGeocodingOp$GSLCResamplingRaster");
            final Constructor<?> ctor = cls.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            final int argc = ctor.getParameterCount();
            if (argc == 10) {
                // legacy: (tileI, tileQ, rangeSpacing, wavelength, nearEdgeSlantRange,
                //          width, height, nearRangeOnLeft, fdcPerColumn, lineTimeIntervalSec)
                instance = ctor.newInstance(tileI, tileQ, RANGE_SPACING, WAVELENGTH,
                        NEAR_EDGE_SLANT_RANGE, W, H, true, fdcPerColumn, lineTimeIntervalSec);
            } else if (argc == 6) {
                // fixed: (tileI, tileQ, width, height, fdcPerColumn, lineTimeIntervalSec)
                instance = ctor.newInstance(tileI, tileQ, W, H, fdcPerColumn, lineTimeIntervalSec);
            } else {
                fail("Unexpected GSLCResamplingRaster constructor arity: " + argc);
                throw new IllegalStateException();
            }
            raster = (Resampling.Raster) instance;
            setReturnReal = cls.getDeclaredMethod("setReturnReal", boolean.class);
            setReturnReal.setAccessible(true);
            Method center = null;
            try {
                center = cls.getDeclaredMethod("setSlantRangeAtCenter", double.class, double.class);
                center.setAccessible(true);
            } catch (NoSuchMethodException ignored) {
                // fixed implementation: samples are center-independent
            }
            setSlantRangeAtCenter = center;
        }

        double[] resampleAt(final Resampling resampling, final Resampling.Index index,
                            final double rangeIndex, final double azimuthIndex) throws Exception {
            if (setSlantRangeAtCenter != null) {
                // Legacy convention: emulate the SM tile loop, which sets the
                // target's slant range / range index before every kernel call.
                setSlantRangeAtCenter.invoke(instance,
                        NEAR_EDGE_SLANT_RANGE + rangeIndex * RANGE_SPACING, rangeIndex);
            }
            resampling.computeCornerBasedIndex(rangeIndex, azimuthIndex, W, H, index);
            setReturnReal.invoke(instance, true);
            final double i = resampling.resample(raster, index);
            setReturnReal.invoke(instance, false);
            final double q = resampling.resample(raster, index);
            if (setSlantRangeAtCenter != null) {
                // Legacy convention: the raster pre-multiplied every sample by
                // exp(+j*4*pi*R/lambda); the SM loop's outputFlattened=false path
                // restores the natural SLC value with exp(-j*phase). Apply the same
                // restore so both implementations are compared on the SAME product
                // convention (the natural, non-flattened SLC sample).
                final double phase = 4.0 * Math.PI
                        * (NEAR_EDGE_SLANT_RANGE + rangeIndex * RANGE_SPACING) / WAVELENGTH;
                final double[] restored = new double[2];
                GSLCGeocodingOp.multiplyByExpMinusJPhi(i, q,
                        Math.cos(phase), Math.sin(phase), restored);
                return restored;
            }
            return new double[]{i, q};
        }
    }

    private static Tile makeTile(final Band band, final float[] data) {
        band.setData(ProductData.createInstance(data));
        return new TileImpl(band, band.getSourceImage().getData(new Rectangle(0, 0, W, H)));
    }

    /** Build i/q tiles holding the sampled band-limited signal on the integer grid. */
    private static Tile[] makeSignalTiles(final BandLimitedSignal signal) {
        final Product product = new Product("fidelity", "test", W, H);
        final Band bandI = product.addBand("i", ProductData.TYPE_FLOAT32);
        final Band bandQ = product.addBand("q", ProductData.TYPE_FLOAT32);
        bandI.setNoDataValue(NO_DATA);
        bandQ.setNoDataValue(NO_DATA);

        final float[] dataI = new float[W * H];
        final float[] dataQ = new float[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                final double[] v = signal.valueAt(x, y);
                dataI[y * W + x] = (float) v[0];
                dataQ[y * W + x] = (float) v[1];
            }
        }
        return new Tile[]{makeTile(bandI, dataI), makeTile(bandQ, dataQ)};
    }

    /**
     * Complex coherence between the resampled signal and the analytic band-limited
     * reference over a row of fractional range positions.
     */
    private static double measureCoherence(final double mu) throws Exception {
        final BandLimitedSignal signal = new BandLimitedSignal(20260808L);
        final Tile[] tiles = makeSignalTiles(signal);
        final RasterHandle handle = new RasterHandle(tiles[0], tiles[1], null, 0.0);

        final Resampling resampling =
                ResamplingFactory.createResampling(ResamplingFactory.BISINC_5_POINT_INTERPOLATION_NAME);
        final Resampling.Index index = resampling.createIndex();

        final double yTgt = 8.0; // integer azimuth: isolates the range interpolation axis
        double xRe = 0.0, xIm = 0.0, pInterp = 0.0, pRef = 0.0;
        for (int x = 20; x <= 235; x++) {
            final double p = x + mu;
            final double[] interp = handle.resampleAt(resampling, index, p, yTgt);
            final double[] ref = signal.valueAt(p, yTgt);
            // interp * conj(ref)
            xRe += interp[0] * ref[0] + interp[1] * ref[1];
            xIm += interp[1] * ref[0] - interp[0] * ref[1];
            pInterp += interp[0] * interp[0] + interp[1] * interp[1];
            pRef += ref[0] * ref[0] + ref[1] * ref[1];
        }
        return Math.hypot(xRe, xIm) / Math.sqrt(pInterp * pRef);
    }

    /**
     * The core defect test: band-limited complex data interpolated at half-pixel
     * positions (the worst case for any aliased carrier) through the real
     * GSLCResamplingRaster + BISINC_5 machinery must reproduce the analytic
     * signal with coherence &gt; 0.95. The pre-flatten implementation scores
     * gamma ~0.1 here because the injected ERS carrier aliases to -0.4989
     * cycles/pixel before the kernel sees the data.
     */
    @Test
    public void testBandLimitedFidelityAtHalfPixel_ErsPhaseStep() throws Exception {
        final double gamma = measureCoherence(0.5);
        System.out.printf("GSLC complex resampling fidelity: gamma(mu=0.5) = %.4f%n", gamma);
        assertTrue(String.format(
                "Complex resampling fidelity at mu=0.5 with ERS-like range carrier handling: " +
                "gamma=%.4f (expected > 0.95). A pre-kernel per-column range 'pre-flatten' " +
                "aliases the ERS carrier to -0.4989 cyc/px and destroys sub-pixel interpolation.",
                gamma), gamma > 0.95);
    }

    /** Same contract at quarter-pixel positions. */
    @Test
    public void testBandLimitedFidelityAtQuarterPixel_ErsPhaseStep() throws Exception {
        final double gamma = measureCoherence(0.25);
        System.out.printf("GSLC complex resampling fidelity: gamma(mu=0.25) = %.4f%n", gamma);
        assertTrue(String.format(
                "Complex resampling fidelity at mu=0.25: gamma=%.4f (expected > 0.95).", gamma),
                gamma > 0.95);
    }

    /**
     * The azimuth Doppler-centroid deramp inside the raster is CORRECT physics
     * (the azimuth spectrum is centred on f_dc, not on zero) and must be kept:
     * at integer source positions the kernel is a delta, so the raster output
     * must equal the raw sample rotated by exp(-j*2*pi*f_dc*y*dt), with the SM
     * loop's target-side reramp exp(+j*phi_az) restoring the natural sample.
     */
    @Test
    public void testAzimuthDerampPreservedAtIntegerPositions() throws Exception {
        final BandLimitedSignal signal = new BandLimitedSignal(4711L);
        final Tile[] tiles = makeSignalTiles(signal);

        final double fdc = 1500.0;          // Hz, ERS-like residual Doppler centroid
        final double dt = 0.0006;           // s, line time interval
        final double[] fdcPerColumn = new double[W];
        java.util.Arrays.fill(fdcPerColumn, fdc);

        final RasterHandle handle = new RasterHandle(tiles[0], tiles[1], fdcPerColumn, dt);
        final Resampling resampling =
                ResamplingFactory.createResampling(ResamplingFactory.BISINC_5_POINT_INTERPOLATION_NAME);
        final Resampling.Index index = resampling.createIndex();

        final int xTgt = 100;
        final int yTgt = 9;
        final double[] out = handle.resampleAt(resampling, index, xTgt, yTgt);

        // Undo the azimuth deramp at the target position (the SM loop's reramp step).
        final double phiAz = 2.0 * Math.PI * fdc * yTgt * dt;
        final double[] reramped = new double[2];
        GSLCGeocodingOp.multiplyByExpJPhi(out[0], out[1],
                Math.cos(phiAz), Math.sin(phiAz), reramped);

        final double[] expected = signal.valueAt(xTgt, yTgt);
        final double scale = Math.hypot(expected[0], expected[1]);
        assertEquals("azimuth deramp+reramp must round-trip the raw sample (real part)",
                expected[0], reramped[0], 1e-3 * Math.max(scale, 1.0));
        assertEquals("azimuth deramp+reramp must round-trip the raw sample (imag part)",
                expected[1], reramped[1], 1e-3 * Math.max(scale, 1.0));
    }
}
