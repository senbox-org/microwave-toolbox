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
package eu.esa.sar.sentinel1.gpf.etadcorrectors;

import com.bc.ceres.core.ProgressMonitor;
import com.bc.ceres.test.LongTestRunner;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * MEASUREMENT, not a pass/fail test. Quantifies the ETAD tropospheric height residual so the value of
 * closing it can be decided on a number rather than an estimate.
 * <p>
 * Background. {@code InterferogramOp.java:2853} removes
 * {@code secETADGradient * (refETADHeight - secETADHeight)} — the absolute gradient times the
 * difference between the two ETAD products' own height grids. It does NOT use a true DEM. So the
 * residual
 * <pre>
 *   (g_ref - g_sec) * (h_true - h_ETAD)
 * </pre>
 * is left uncorrected by BOTH the classical chain and the current GSLC design, and closing it is the
 * whole value of the proposed "Option D" (emit height/gradient from ETAD Option 1 and apply the term
 * in GSLCGeocodingOp at the true DEM height).
 * <p>
 * Two factors decide the magnitude, and neither needs burst correspondence between the two dates:
 * <ol>
 *   <li><b>Δg</b>, the differential tropospheric-delay-to-height gradient. Measured here by
 *       regressing {@code troposphericCorrectionRg} against {@code height} independently for each
 *       acquisition and differencing. g is a large-scale atmospheric property, so a per-product
 *       regression is representative.</li>
 *   <li><b>Sub-grid relief</b>, {@code h_true - h_ETAD}: the terrain the ~200 m ETAD height grid
 *       cannot represent. Bounded below here from the grid's own local slope; real terrain is rougher
 *       than a smooth interpolant, so treat the figure as a floor.</li>
 * </ol>
 * Uses a real same-track S1B pair 60 days apart. Run with:
 * <pre>
 *   mvn test -pl sar-op-sentinel1 -Dtest=ETADHeightResidualMeasurement -Denable.long.tests=true
 * </pre>
 */
@RunWith(LongTestRunner.class)
public class ETADHeightResidualMeasurement {

    // Same relative orbit (S1B, 175 orbits/cycle: 023812 - 022937 = 875 = 5 cycles = 60 days).
    private static final File ETAD_A = new File(TestData.inputSAR
            + "S1/ETAD/IW/S1B_IW_ETA__AXDV_20200815T173048_20200815T173116_022937_02B897_E56D.SAFE/manifest.safe");
    private static final File ETAD_B = new File(TestData.inputSAR
            + "S1/ETAD/IW/InSAR/S1B_IW_ETA__AXDV_20201014T173050_20201014T173118_023812_02D3FC_03B6.SAFE/manifest.safe");

    /** S1 C-band centre frequency, Hz. Matches TOPSCorrector's radarFrequency for this mission. */
    private static final double RADAR_FREQ_HZ = 5.405e9;
    /** ETAD IW ground posting, metres — from the annotation's gridGroundSampling. */
    private static final double GRID_POST_M = 199.0;

    @Test
    public void measureDifferentialGradientAndSubGridRelief() throws Exception {
        assumeTrue("ETAD pair not found", ETAD_A.exists() && ETAD_B.exists());

        try (Product a = ProductIO.readProduct(ETAD_A);
             Product b = ProductIO.readProduct(ETAD_B)) {

            final Fit fitA = fitGradient(a, "A 2020-08-15");
            final Fit fitB = fitGradient(b, "B 2020-10-14");
            assumeTrue("no usable IW1 height/tropo bursts in one or both products",
                    fitA != null && fitB != null);

            // s/m -> rad/m, exactly as TOPSCorrector.convertGradientToPhase does.
            final double gA = -2.0 * Math.PI * RADAR_FREQ_HZ * fitA.slopeSecPerMetre;
            final double gB = -2.0 * Math.PI * RADAR_FREQ_HZ * fitB.slopeSecPerMetre;
            final double dG = gA - gB;

            final double reliefFloor = Math.max(fitA.subGridReliefM, fitB.subGridReliefM);

            System.out.println();
            System.out.println("=== ETAD tropospheric height-residual measurement ===");
            System.out.printf("A  gradient  %+.6f rad/m   (%.4e s/m, n=%d, R^2=%.3f)%n",
                    gA, fitA.slopeSecPerMetre, fitA.n, fitA.r2);
            System.out.printf("B  gradient  %+.6f rad/m   (%.4e s/m, n=%d, R^2=%.3f)%n",
                    gB, fitB.slopeSecPerMetre, fitB.n, fitB.r2);
            System.out.printf("|dG|         %.6f rad/m   (%.1f%% of |gA|)%n",
                    Math.abs(dG), 100.0 * Math.abs(dG) / Math.max(1e-12, Math.abs(gA)));
            System.out.printf("height range A %.1f..%.1f m   B %.1f..%.1f m%n",
                    fitA.hMin, fitA.hMax, fitB.hMin, fitB.hMax);
            System.out.printf("sub-grid relief floor  %.1f m  (from local slope over %.0f m posting)%n",
                    reliefFloor, GRID_POST_M);
            System.out.println();
            System.out.printf("RESIDUAL Option D would close: |dG| * relief = %.3f rad  (floor)%n",
                    Math.abs(dG) * reliefFloor);
            System.out.printf("   ... over 500 m of sub-grid relief: %.3f rad%n", Math.abs(dG) * 500.0);
            System.out.printf("   ... over the full A height range (%.0f m): %.3f rad%n",
                    fitA.hMax - fitA.hMin, Math.abs(dG) * (fitA.hMax - fitA.hMin));
            System.out.println();
            System.out.printf("For scale: 1 rad of C-band LOS = %.1f mm%n", 0.055465e3 / (4.0 * Math.PI));
            System.out.println("=====================================================");

            // Sanity only — the point of this harness is the printed numbers.
            assertTrue("gradient should be non-zero and physically plausible (<5 rad/m)",
                    Math.abs(gA) > 1e-4 && Math.abs(gA) < 5.0);
        }
    }

    private static final class Fit {
        double slopeSecPerMetre, r2, hMin, hMax, subGridReliefM;
        int n;
    }

    /**
     * Least-squares regression of tropospheric range delay (s) against height (m) over every IW1
     * burst that carries both layers, pooled. Also measures the height grid's local slope, from which
     * the sub-grid relief floor follows.
     */
    private static Fit fitGradient(final Product p, final String label) throws Exception {

        final List<String> heightBands = new ArrayList<>();
        for (final String n : p.getBandNames()) {
            if (n.startsWith("IW1_") && n.endsWith("_height")) {
                heightBands.add(n);
            }
        }
        if (heightBands.isEmpty()) {
            System.out.println(label + ": no IW1 *_height bands; available sample: "
                    + String.join(", ", firstN(p.getBandNames(), 6)));
            return null;
        }

        double sx = 0, sy = 0, sxx = 0, sxy = 0, syy = 0;
        long n = 0;
        double hMin = Double.MAX_VALUE, hMax = -Double.MAX_VALUE;
        double slopeSum = 0;
        long slopeN = 0;

        for (final String hName : heightBands) {
            final String tName = hName.replace("_height", "_troposphericCorrectionRg");
            final Band hb = p.getBand(hName);
            final Band tb = p.getBand(tName);
            if (hb == null || tb == null) {
                continue;
            }
            final int w = hb.getRasterWidth(), h = hb.getRasterHeight();
            final float[] hv = new float[w * h];
            final float[] tv = new float[w * h];
            hb.readPixels(0, 0, w, h, hv, ProgressMonitor.NULL);
            tb.readPixels(0, 0, w, h, tv, ProgressMonitor.NULL);

            for (int i = 0; i < w * h; i++) {
                final double hh = hv[i], tt = tv[i];
                if (Double.isNaN(hh) || Double.isNaN(tt)) continue;
                sx += hh; sy += tt; sxx += hh * hh; sxy += hh * tt; syy += tt * tt;
                n++;
                if (hh < hMin) hMin = hh;
                if (hh > hMax) hMax = hh;
            }

            // Local height slope in metres per grid post, along range (row-major, width = range).
            for (int r = 0; r < h; r++) {
                for (int c = 0; c + 1 < w; c++) {
                    final double d = hv[r * w + c + 1] - hv[r * w + c];
                    if (!Double.isNaN(d)) { slopeSum += Math.abs(d); slopeN++; }
                }
            }
        }

        if (n < 100) {
            System.out.println(label + ": too few valid samples (" + n + ')');
            return null;
        }

        final Fit f = new Fit();
        f.n = (int) Math.min(n, Integer.MAX_VALUE);
        final double den = n * sxx - sx * sx;
        f.slopeSecPerMetre = den == 0 ? 0 : (n * sxy - sx * sy) / den;
        final double num = n * sxy - sx * sy;
        f.r2 = (den == 0 || (n * syy - sy * sy) == 0) ? 0
                : (num * num) / (den * (n * syy - sy * sy));
        f.hMin = hMin;
        f.hMax = hMax;
        // Mean |dh| between adjacent posts is the height change the grid resolves; roughly half of it
        // is the excursion a bilinear interpolant misses mid-cell. A floor, not an estimate.
        f.subGridReliefM = slopeN == 0 ? 0 : 0.5 * (slopeSum / slopeN);
        return f;
    }

    private static List<String> firstN(final String[] a, final int k) {
        final List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(k, a.length); i++) out.add(a[i]);
        return out;
    }
}
