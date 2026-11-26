/*
 * Copyright (C) 2026 Skywatch. https://www.skywatch.com
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
package eu.esa.sar.insar.gpf.coregistration;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.GPF;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

/**
 * Cross-Correlation + Warp on a temporally decorrelated SLC pair: two 256 x 256 HH subsets of RADARSAT-2 FQ10W
 * scenes over Manitoba cropland, 24 days apart, whose fine-registration correlation is mostly 0.1-0.3.
 * <p>
 * A coherence threshold above 1 makes complex fine registration reject every GCP deterministically, so:
 * with fine registration on, Warp must fail loudly (it used to write all-zero Secondaries into a product flagged
 * as coregistered); with applyFineRegistration=false the threshold must not matter (it used to be forced on for
 * complex input) and the amplitude-only coregistration must align the pair.
 */
public class TestCoregistrationDecorrelatedPair {

    static {
        TestUtils.initTestEnvironment();
    }

    private static final File REF = new File(TestData.inputSAR + "RS2/Manitoba/subsets/RS2_FQ10W_20120613_HH_256.dim");
    private static final File SEC = new File(TestData.inputSAR + "RS2/Manitoba/subsets/RS2_FQ10W_20120707_HH_256.dim");

    @Before
    public void setUp() {
        assumeTrue(REF + " not found", REF.exists());
        assumeTrue(SEC + " not found", SEC.exists());
    }

    private static Product coregister(final boolean applyFineRegistration) throws Exception {
        final Map<String, Object> stackParams = new HashMap<>();
        stackParams.put("extent", "Master");
        stackParams.put("initialOffsetMethod", "Product Geolocation");
        final Product stack = GPF.createProduct("CreateStack", stackParams,
                TestUtils.readSourceProduct(REF), TestUtils.readSourceProduct(SEC));

        final Map<String, Object> ccParams = new HashMap<>();
        ccParams.put("numGCPtoGenerate", 400);
        ccParams.put("applyFineRegistration", applyFineRegistration);
        ccParams.put("coherenceThreshold", 1.01);   // no GCP can pass complex fine registration
        final Product cc = GPF.createProduct("Cross-Correlation", ccParams, stack);

        final Map<String, Object> warpParams = new HashMap<>();
        warpParams.put("warpPolynomialOrder", 1);
        return GPF.createProduct("Warp", warpParams, cc);
    }

    private static Band band(final Product p, final String prefix, final String role) {
        for (Band b : p.getBands()) {
            if (b.getName().startsWith(prefix) && b.getName().contains(role)) {
                return b;
            }
        }
        throw new AssertionError("no " + prefix + "*" + role + "* band in " + java.util.Arrays.toString(p.getBandNames()));
    }

    private static double[] amplitude(final Product p, final String role) throws Exception {
        final int w = p.getSceneRasterWidth(), h = p.getSceneRasterHeight();
        final double[] i = new double[w * h];
        final double[] q = new double[w * h];
        band(p, "i_HH", role).readPixels(0, 0, w, h, i, ProgressMonitor.NULL);
        band(p, "q_HH", role).readPixels(0, 0, w, h, q, ProgressMonitor.NULL);
        final double[] a = new double[w * h];
        for (int k = 0; k < a.length; k++) {
            a[k] = Math.sqrt(i[k] * i[k] + q[k] * q[k]);
        }
        return a;
    }

    @Test
    public void testWarpFailsLoudlyWithoutEnoughGCPs() throws Exception {
        final Product warped = coregister(true);
        final Band sec = band(warped, "i_HH", "_sec");
        try {
            sec.readPixels(0, 0, 16, 16, new float[256], ProgressMonitor.NULL);
            fail("Warp must not write a Secondary without a warp model");
        } catch (OperatorException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("not enough valid GCPs"));
        }
    }

    @Test
    public void testAmplitudeOnlyCoregistrationIsHonoured() throws Exception {
        final Product warped = coregister(false);
        assertEquals(1, AbstractMetadata.getAbstractedMetadata(warped).getAttributeInt(AbstractMetadata.coregistered_stack));

        final int w = warped.getSceneRasterWidth(), h = warped.getSceneRasterHeight();
        final double[] ref = amplitude(warped, "_ref_");
        final double[] sec = amplitude(warped, "_sec");

        int nonZero = 0;
        for (double v : sec) {
            if (v != 0) {
                nonZero++;
            }
        }
        assertTrue("warped Secondary is mostly empty: " + nonZero, nonZero > sec.length * 0.9);

        // the warped Secondary must correlate with the Reference best at zero lag
        final double c0 = correlation(ref, sec, w, h, 0, 0);
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                if (dx != 0 || dy != 0) {
                    final double c = correlation(ref, sec, w, h, dx, dy);
                    assertTrue("correlation at lag (" + dx + "," + dy + ") " + c + " >= zero-lag " + c0, c < c0);
                }
            }
        }
        // single-look amplitudes of this decorrelated pair correlate at ~0.1; with ~50k samples the noise level of
        // the estimate is ~0.005, so 0.05 is far above chance
        assertTrue("zero-lag amplitude correlation " + c0, c0 > 0.05);
    }

    /** Pearson correlation of a and b shifted by (dx, dy), over the 16-pixel-inset interior. */
    private static double correlation(final double[] a, final double[] b, final int w, final int h,
                                      final int dx, final int dy) {
        final int m = 16;
        double sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0;
        int n = 0;
        for (int y = m; y < h - m; y++) {
            for (int x = m; x < w - m; x++) {
                final double va = a[y * w + x];
                final double vb = b[(y + dy) * w + (x + dx)];
                sa += va; sb += vb; saa += va * va; sbb += vb * vb; sab += va * vb;
                n++;
            }
        }
        final double cov = sab / n - (sa / n) * (sb / n);
        return cov / Math.sqrt((saa / n - (sa / n) * (sa / n)) * (sbb / n - (sb / n) * (sb / n)));
    }
}
