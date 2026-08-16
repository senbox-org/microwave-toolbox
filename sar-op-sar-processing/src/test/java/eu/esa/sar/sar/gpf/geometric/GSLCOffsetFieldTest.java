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

import eu.esa.sar.commons.test.ProcessorTest;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Spec for the stripmap spatially-varying coregistration offset fields
 * ({@code rangeOffsetPoly}/{@code azimuthOffsetPoly}) on {@link GSLCGeocodingOp}.
 * The fields correct data-vs-annotation registration DRIFT (first measured on the
 * 1995 ERS-1/ERS-2 tandem VMP pair: ~1.7 px range drift across the swath) that a
 * scalar bias cannot represent; they shift only where the source SLC is read while
 * the restored carrier phase stays geometric.
 */
public class GSLCOffsetFieldTest extends ProcessorTest {

    private final static OperatorSpi spi = new GSLCGeocodingOp.Spi();
    private final static File capellaFile = TestData.inputCapella_StripmapSLC;

    // ---- parameter parsing -------------------------------------------------------------

    @Test
    public void testParseOffsetPoly_BlankDisables() {
        assertNull(GSLCGeocodingOp.parseOffsetPoly(null, "p"));
        assertNull(GSLCGeocodingOp.parseOffsetPoly("", "p"));
        assertNull(GSLCGeocodingOp.parseOffsetPoly("   ", "p"));
    }

    @Test
    public void testParseOffsetPoly_AllZeroDisables() {
        assertNull(GSLCGeocodingOp.parseOffsetPoly("0,0,0", "p"));
    }

    @Test
    public void testParseOffsetPoly_ParsesThreeTerms() {
        final double[] c = GSLCGeocodingOp.parseOffsetPoly(" 0.41, -3.5e-4 , 6e-5 ", "p");
        assertNotNull(c);
        assertArrayEquals(new double[]{0.41, -3.5e-4, 6e-5}, c, 0.0);
    }

    @Test(expected = OperatorException.class)
    public void testParseOffsetPoly_RejectsWrongArity() {
        GSLCGeocodingOp.parseOffsetPoly("0.4,-3e-4", "p");
    }

    @Test(expected = OperatorException.class)
    public void testParseOffsetPoly_RejectsNonNumeric() {
        GSLCGeocodingOp.parseOffsetPoly("0.4,x,0", "p");
    }

    @Test
    public void testEvalOffsetPoly() {
        assertEquals(0.0, GSLCGeocodingOp.evalOffsetPoly(null, 100, 200), 0.0);
        final double[] c = {0.4, -3e-4, 5e-5};
        assertEquals(0.4 - 3e-4 * 1000 + 5e-5 * 2000,
                GSLCGeocodingOp.evalOffsetPoly(c, 1000, 2000), 1e-12);
    }

    // ---- operator wiring: a constant field must reproduce the scalar-offset output ------

    /**
     * The scalar path applies a constant offset by shifting the annotation references
     * (nearEdgeSlantRange / firstLineUTC); the field path adds to the solved indices.
     * For a constant field the two must produce the same pixels — this pins the field
     * application to the actual data lookup (a metadata-only implementation would fail).
     */
    @Test
    public void testConstantFieldEquivalentToScalarOffset() throws Exception {
        assumeTrue(capellaFile + " not found", capellaFile.exists());
        try (final Product sourceProduct = TestUtils.readSourceProduct(capellaFile)) {

            final Product scalar = runGslc(sourceProduct, op -> {
                op.setParameter("rangeOffsetPixels", 0.6);
                op.setParameter("azimuthOffsetPixels", -0.4);
            });
            final Product field = runGslc(sourceProduct, op -> {
                op.setParameter("rangeOffsetPoly", "0.6,0,0");
                op.setParameter("azimuthOffsetPoly", "-0.4,0,0");
            });

            assertEquals(scalar.getSceneRasterWidth(), field.getSceneRasterWidth());
            assertEquals(scalar.getSceneRasterHeight(), field.getSceneRasterHeight());

            final Band bScalar = firstRealBand(scalar);
            final Band bField = firstRealBand(field);
            final java.awt.Rectangle r = findValidBlock(bScalar);
            // Same gating as the other GSLC integration tests: environments where the
            // fixture's DEM tiles are unavailable geocode to an empty raster — skip, don't fail.
            assumeTrue("fixture geocoded empty (DEM unavailable?) — skipping", r != null);
            final int w = r.width, h = r.height;
            final float[] pScalar = new float[w * h];
            final float[] pField = new float[w * h];
            bScalar.readPixels(r.x, r.y, w, h, pScalar);
            bField.readPixels(r.x, r.y, w, h, pField);

            int nCompared = 0;
            double maxAbsDiff = 0.0;
            for (int i = 0; i < w * h; i++) {
                if (pScalar[i] == 0f || pField[i] == 0f) continue;
                nCompared++;
                maxAbsDiff = Math.max(maxAbsDiff, Math.abs(pScalar[i] - pField[i]));
            }
            assertTrue("no valid pixels compared", nCompared > 1000);
            // The scalar path perturbs the Doppler-centroid reference by the annotation
            // shift (harmless, sub-mHz); allow only round-off-scale differences.
            assertEquals("constant field must reproduce the scalar-offset output", 0.0,
                    maxAbsDiff, 1e-3);

            scalar.dispose();
            field.dispose();
        }
    }

    /** A non-zero field must actually move the data (guards against a silently ignored param). */
    @Test
    public void testFieldChangesOutput() throws Exception {
        assumeTrue(capellaFile + " not found", capellaFile.exists());
        try (final Product sourceProduct = TestUtils.readSourceProduct(capellaFile)) {

            final Product plain = runGslc(sourceProduct, op -> { });
            final Product shifted = runGslc(sourceProduct, op ->
                    op.setParameter("rangeOffsetPoly", "0.5,0,0"));

            final Band bPlain = firstRealBand(plain);
            final Band bShifted = firstRealBand(shifted);
            final java.awt.Rectangle r = findValidBlock(bPlain);
            assumeTrue("fixture geocoded empty (DEM unavailable?) — skipping", r != null);
            final int w = r.width, h = r.height;
            final float[] pPlain = new float[w * h];
            final float[] pShifted = new float[w * h];
            bPlain.readPixels(r.x, r.y, w, h, pPlain);
            bShifted.readPixels(r.x, r.y, w, h, pShifted);

            int nCompared = 0;
            double maxAbsDiff = 0.0;
            for (int i = 0; i < w * h; i++) {
                if (pPlain[i] == 0f || pShifted[i] == 0f) continue;
                nCompared++;
                maxAbsDiff = Math.max(maxAbsDiff, Math.abs(pPlain[i] - pShifted[i]));
            }
            assertTrue("no valid pixels compared", nCompared > 1000);
            assertTrue("a 0.5-px offset field must change the resampled data", maxAbsDiff > 0.0);

            plain.dispose();
            shifted.dispose();
        }
    }

    // ---- helpers ------------------------------------------------------------------------

    private interface OpConfig {
        void apply(GSLCGeocodingOp op);
    }

    private static Product runGslc(final Product source, final OpConfig config) {
        final GSLCGeocodingOp op = (GSLCGeocodingOp) spi.createOperator();
        op.setSourceProduct(source);
        op.setParameter("demName", "Copernicus 30m Global DEM");
        op.setParameter("imgResamplingMethod", "BISINC_5_POINT_INTERPOLATION");
        op.setParameter("nodataValueAtSea", false);
        config.apply(op);
        return op.getTargetProduct();
    }

    /**
     * Scan a coarse grid for a 256x256 block that is mostly non-zero (geocoded rasters are a
     * rotated footprint inside a bounding box — a fixed centre block may fall on fill).
     */
    private static java.awt.Rectangle findValidBlock(final Band band) throws java.io.IOException {
        final int bs = 256;
        final int W = band.getRasterWidth(), H = band.getRasterHeight();
        final float[] buf = new float[bs * bs];
        for (int y = H / 4; y + bs < H * 3 / 4; y += Math.max(bs, H / 8)) {
            for (int x = W / 4; x + bs < W * 3 / 4; x += Math.max(bs, W / 8)) {
                band.readPixels(x, y, bs, bs, buf);
                int nValid = 0;
                for (final float v : buf) {
                    if (v != 0f) nValid++;
                }
                if (nValid > bs * bs * 3 / 4) {
                    return new java.awt.Rectangle(x, y, bs, bs);
                }
            }
        }
        return null;
    }

    private static Band firstRealBand(final Product p) {
        for (final Band b : p.getBands()) {
            if (b.getUnit() != null && b.getUnit().contains("real")) {
                return b;
            }
        }
        throw new IllegalStateException("no real (i) band in " + p.getName());
    }
}
