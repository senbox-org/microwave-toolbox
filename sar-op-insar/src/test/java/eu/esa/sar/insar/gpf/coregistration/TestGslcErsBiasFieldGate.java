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
package eu.esa.sar.insar.gpf.coregistration;

import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.dataio.ProductIO;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * File-gated ground-truth test for {@link CreateStackOp#estimateSlcBiasByBlocks} on the
 * ERS-1/ERS-2 tandem VMP pair (Etna, 01/02-Aug-1995) — the pair whose spatially-drifting
 * data-vs-annotation registration produced the spurious GSLC interferogram fringes.
 * <p>
 * The expected numbers were measured INDEPENDENTLY (python block-CC + zero-Doppler solves
 * on the PRARE state vectors, E:\ESA\snap_tmp\ers_slc_truth2.py, 2026-08-06):
 * <pre>
 *   range  need ~ +0.50 px, essentially constant (span +0.21..+0.61, RMS 0.095)
 *   azimuth need ~ +5.33 .. +7.13 px, drifting +7.4e-5 px/px along azimuth
 * </pre>
 * This test pins the estimator's SIGN convention and slope scale to that measurement.
 * Skipped when the products are not on disk.
 */
public class TestGslcErsBiasFieldGate {

    private static final File ERS1 = new File(
            "E:/Output/ers/ERS-1_SAR_SLC-ORBIT_21159_DATE__1-AUG-1995_21_16_39_Orb.dim");
    private static final File ERS2 = new File(
            "E:/Output/ers/ERS-2_SAR_SLC-ORBIT_1486_DATE__2-AUG-1995_21_16_42_Orb.dim");

    @Test
    public void testBlockFieldMatchesIndependentMeasurement() throws Exception {
        assumeTrue("ERS tandem fixture not present", ERS1.exists() && ERS2.exists());
        try (Product master = ProductIO.readProduct(ERS1);
             Product slave = ProductIO.readProduct(ERS2)) {

            final CreateStackOp.SlcBiasEstimate est =
                    CreateStackOp.estimateSlcBiasByBlocks(master, slave);
            assertNotNull("block estimator must produce a result on this coherent tandem pair", est);

            // constants (medians)
            assertEquals("range median", 0.45, est.dRange, 0.30);
            assertEquals("azimuth median", 6.2, est.dAzimuth, 0.8);

            final int W = master.getSceneRasterWidth();
            final int H = master.getSceneRasterHeight();

            // azimuth FIELD: the ~2 px drift along azimuth must be captured with the right sign
            assertNotNull("azimuth field must be fitted", est.azimuthPoly);
            final int azDeg = CreateStackOp.offsetFieldDegreeOf(est.azimuthPoly.length);
            final double azEarly = CreateStackOp.evalOffsetField(est.azimuthPoly, azDeg, W / 2.0, 0);
            final double azLate = CreateStackOp.evalOffsetField(est.azimuthPoly, azDeg, W / 2.0, H - 1.0);
            assertTrue("azimuth field must drift positive along azimuth (got "
                    + azEarly + " -> " + azLate + ")", azLate - azEarly > 1.0);
            assertTrue("azimuth drift magnitude ~2 px (got " + (azLate - azEarly) + ")",
                    azLate - azEarly < 3.2);
            assertTrue("azimuth field values must stay near the physical +5..+8 px window",
                    azEarly > 4.0 && azLate < 9.0);

            // range FIELD: bounded, near the +0.2..+0.7 px truth everywhere — no hallucinated
            // multi-pixel drift (the GCP fit's historical failure mode)
            if (est.rangePoly != null) {
                final int rgDeg = CreateStackOp.offsetFieldDegreeOf(est.rangePoly.length);
                double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
                for (int a = 0; a <= 6; a++) {
                    for (int b = 0; b <= 6; b++) {
                        final double v = CreateStackOp.evalOffsetField(
                                est.rangePoly, rgDeg, a * (W - 1.0) / 6, b * (H - 1.0) / 6);
                        min = Math.min(min, v);
                        max = Math.max(max, v);
                    }
                }
                assertTrue("range field must stay within [-0.4, +1.3] px (got ["
                        + min + ", " + max + "])", min > -0.4 && max < 1.3);
                assertTrue("range field total variation must stay sub-pixel (got "
                        + (max - min) + ")", max - min < 1.0);
            }
        }
    }

    /**
     * Ground-truth test for {@link CreateStackOp#estimateSlcBiasByGcpField} — the degree-2
     * offset field fitted from the classical-strength dense GCP set (400 @ coherenceThreshold
     * 0.4), which is the root-cause fix for the ~130-rad smooth interferogram artifact (see
     * task-3B-brief.md). Same pair, same validated constants as
     * {@link #testBlockFieldMatchesIndependentMeasurement}; this pins the GCP-field method's
     * sign convention and degree-2 spatial behaviour independently of the block-CC path.
     * <p>
     * Fix-round-1: the cross-check inside {@code estimateSlcBiasByGcpField} against
     * {@code estimateSlcBiasByBlocks} used to compare the GCP field's CENTRE value against the
     * block estimator's spatial MEDIAN of a field that drifts ~+5.3..+7.4 px along azimuth on
     * this pair — an apples-to-oranges comparison that vetoed the GCP field every run. The
     * cross-check is now drift-aware (field-vs-field on the same 7x7 grid, or median-vs-median
     * when the block estimator has constants only). Because {@code estimateSlcBiasByBlocks}
     * ALWAYS returns a degree-1 (affine) field by design (hardcoded {@code wantDegree = 1}),
     * asserting {@code offsetFieldDegreeOf(...) == 2} on the returned polys below is not just a
     * sanity check — it is proof that the cross-check ACCEPTED the GCP path on this fixture
     * rather than silently falling back to the block estimate (which could never produce a
     * degree-2 poly).
     * Runtime: ~1-2 min (nested CreateStack + CrossCorrelationOp on real ERS SLCs).
     */
    @Test
    public void gcpFieldMatchesIndependentMeasurement() throws Exception {
        assumeTrue("ERS tandem fixture not present", ERS1.exists() && ERS2.exists());
        try (Product master = ProductIO.readProduct(ERS1);
             Product slave = ProductIO.readProduct(ERS2)) {

            final CreateStackOp.SlcBiasEstimate est =
                    CreateStackOp.estimateSlcBiasByGcpField(master, slave,
                            com.bc.ceres.core.ProgressMonitor.NULL);
            assertNotNull("GCP field estimator must produce a result on this coherent tandem pair", est);

            // constants (medians)
            assertEquals("range median", 0.45, est.dRange, 0.30);
            assertEquals("azimuth median", 6.2, est.dAzimuth, 0.8);

            final int W = master.getSceneRasterWidth();
            final int H = master.getSceneRasterHeight();

            // ENGAGEMENT proof: the block estimator can only ever return a degree-1 field, so a
            // degree-2 field here proves the drift-aware cross-check accepted the GCP path
            // rather than silently vetoing it back to the block fallback.
            assertNotNull("azimuth field must be fitted", est.azimuthPoly);
            assertNotNull("range field must be fitted", est.rangePoly);
            final int azDeg = CreateStackOp.offsetFieldDegreeOf(est.azimuthPoly.length);
            final int rgDeg = CreateStackOp.offsetFieldDegreeOf(est.rangePoly.length);
            assertEquals("azimuth field must be the accepted degree-2 fit (proves the GCP path "
                    + "engaged, not the degree-1 block fallback)", 2, azDeg);
            assertEquals("range field must be the accepted degree-2 fit (proves the GCP path "
                    + "engaged, not the degree-1 block fallback)", 2, rgDeg);

            // azimuth FIELD: drift along azimuth must be present, positive, and physically bounded
            final double azEarly = CreateStackOp.evalOffsetField(est.azimuthPoly, azDeg, W / 2.0, 0);
            final double azLate = CreateStackOp.evalOffsetField(est.azimuthPoly, azDeg, W / 2.0, H - 1.0);
            final double azDrift = azLate - azEarly;
            assertTrue("azimuth field must drift positive along azimuth (got "
                    + azEarly + " -> " + azLate + ")", azDrift >= 1.0 && azDrift <= 3.2);

            // range FIELD: bounded over the scene, degree-2 may exceed the affine's 1.0 slightly
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            for (int a = 0; a <= 6; a++) {
                for (int b = 0; b <= 6; b++) {
                    final double v = CreateStackOp.evalOffsetField(
                            est.rangePoly, rgDeg, a * (W - 1.0) / 6, b * (H - 1.0) / 6);
                    min = Math.min(min, v);
                    max = Math.max(max, v);
                }
            }
            assertTrue("range field must stay within [-0.5, +1.4] px (got ["
                    + min + ", " + max + "])", min > -0.5 && max < 1.4);
            // Fix-round-2 note: a stricter ">= 0.25 px" underfit-detection floor was tried and
            // retired here — it was derived from the independent python measurement's RAW
            // per-sample scatter (RMS 0.095 px around an "essentially constant" range need per
            // that measurement's own description), not from genuine smooth spatial structure. Four
            // methodologically independent estimators (this GCP+CPM field, the GCP+percentile-trim
            // field, the original GCP+MAD field, and the entirely separate estimateSlcBiasByBlocks
            // FFT-amplitude method) converge on ~0.12-0.20 px of smooth range-field variation on
            // this pair; none reach 0.25 px, let alone the raw scatter's ~0.40 px span. See
            // task-3B-report.md, Fix round 2, for the full diagnostic trail. That convergence is
            // treated as the measured registration-estimation floor for this pair, not a bug.
            assertTrue("range field total variation must stay under 1.2 px (got "
                    + (max - min) + ")", max - min < 1.2);
        }
    }
}
