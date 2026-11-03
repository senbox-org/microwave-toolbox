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
package eu.esa.sar.calibration.gpf;

import com.bc.ceres.core.ProgressMonitor;
import com.bc.ceres.test.LongTestRunner;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Characterisation (golden-value) tests for {@link Sentinel1RemoveThermalNoiseOp}: hashes of 64x64 noise
 * blocks at fixed locations, so refactoring of the noise LUT construction is provably behaviour-preserving.
 * A hash may only be re-baselined with a written reason next to it.
 */
@RunWith(LongTestRunner.class)
public class TestRemoveThermalNoiseOpGolden {

    private static final File GRD = new File(TestData.inputSAR +
            "S1/GRD/S1A_IW_GRDH_1SDV_20240508T062559_20240508T062624_053776_0688DB_1A13.SAFE.zip");
    private static final File SLC = new File(TestData.inputSAR +
            "S1/S1A_IW_SLC__1SDV_20180620T222319_20180620T222347_022446_026E52_B227.zip");

    private static final int B = 64;

    /** IPF 3.71 IW GRD, 2-D (range x azimuth) noise. */
    @Test
    public void grdNoiseBlocks() throws Exception {
        assumeTrue(GRD.exists());
        final Product src = ProductIO.readProduct(GRD);
        try {
            final Band nep = noiseProduct(src).getBand("NEP_VH");
            final int w = nep.getRasterWidth(), h = nep.getRasterHeight();
            final int iw1End = swathLastRangeSample(src, "IW1");
            final Map<String, Integer> actual = new LinkedHashMap<>();
            actual.put("grd.centre", hash(nep, w / 2, h / 2));
            actual.put("grd.nearRange", hash(nep, 10, h / 2));
            actual.put("grd.iw1iw2", hash(nep, iw1End - B / 2, h / 2));
            check(actual, GRD_GOLDEN);
        } finally {
            src.dispose();
        }
    }

    /** IPF 2.90 IW SLC, per-burst noise. */
    @Test
    public void slcNoiseBlocks() throws Exception {
        assumeTrue(SLC.exists());
        final Product src = ProductIO.readProduct(SLC);
        try {
            final Band nep = noiseProduct(src).getBand("NEP_IW1_VH");
            final int w = nep.getRasterWidth(), h = nep.getRasterHeight();
            final int linesPerBurst = linesPerBurst(src);
            final Map<String, Integer> actual = new LinkedHashMap<>();
            actual.put("slc.centre", hash(nep, w / 2, h / 2));
            actual.put("slc.nearRange", hash(nep, 10, h / 2));
            actual.put("slc.burstEdge", hash(nep, w / 2, 2 * linesPerBurst - B / 2));
            check(actual, SLC_GOLDEN);
        } finally {
            src.dispose();
        }
    }

    // Baselines generated with the operator as of 2026-10-15 (before the S1NoiseLutBuilder refactor).
    private static final Map<String, Integer> GRD_GOLDEN = Map.of(
            "grd.centre", -1691806249,
            "grd.nearRange", -1939905571,
            "grd.iw1iw2", -1554517037);
    private static final Map<String, Integer> SLC_GOLDEN = Map.of(
            "slc.centre", -242943766,
            "slc.nearRange", -1232553453,
            "slc.burstEdge", 1465361415);

    private static void check(final Map<String, Integer> actual, final Map<String, Integer> golden) {
        actual.forEach((k, v) -> System.out.println("GOLDEN " + k + "=" + v));
        for (Map.Entry<String, Integer> e : actual.entrySet()) {
            assertEquals(e.getKey(), golden.get(e.getKey()), e.getValue());
        }
    }

    private static Product noiseProduct(final Product src) {
        final Sentinel1RemoveThermalNoiseOp op = new Sentinel1RemoveThermalNoiseOp();
        op.setSourceProduct(src);
        op.setParameter("outputNoise", true);
        op.setParameter("selectedPolarisations", new String[]{"VH"});
        return op.getTargetProduct();
    }

    private static int hash(final Band band, final int x, final int y) throws Exception {
        final float[] block = new float[B * B];
        band.readPixels(x, y, B, B, block, ProgressMonitor.NULL);
        assertTrue("block at " + x + "," + y + " is constant", Arrays.stream(boxed(block)).distinct().count() > 1);
        return Arrays.hashCode(block);
    }

    private static Float[] boxed(final float[] a) {
        final Float[] b = new Float[a.length];
        for (int i = 0; i < a.length; i++) b[i] = a[i];
        return b;
    }

    private static MetadataElement firstAnnotation(final Product p) {
        return AbstractMetadata.getOriginalProductMetadata(p).getElement("annotation").getElementAt(0)
                .getElement("product");
    }

    private static int swathLastRangeSample(final Product p, final String swath) {
        final MetadataElement list = firstAnnotation(p).getElement("swathMerging").getElement("swathMergeList");
        for (MetadataElement merge : list.getElements()) {
            if (swath.equals(merge.getAttributeString("swath"))) {
                return merge.getElement("swathBoundsList").getElementAt(0).getAttributeInt("lastRangeSample");
            }
        }
        throw new IllegalStateException("no swath " + swath);
    }

    private static int linesPerBurst(final Product p) {
        return firstAnnotation(p).getElement("swathTiming").getAttributeInt("linesPerBurst");
    }
}
