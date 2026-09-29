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
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.eo.Constants;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * MEASUREMENT, not a pass/fail test. Settles whether ETAD's {@code bistaticCorrectionAz} layer
 * duplicates the bistatic azimuth residual that {@code GSLCGeocodingOp} always applies to
 * Sentinel-1, so the fix can be based on the data rather than on an assumption about the ETAD
 * Product Format Specification.
 * <p>
 * The situation. {@code Sentinel1Level1Directory:491} sets {@code bistatic_correction_applied = 1}
 * for every S1 product this toolbox reads, so {@code GSLCGeocodingOp:600-603} takes
 * {@code skipBistaticCorrection = true} with {@code bistaticCorrectionRefRange =
 * slant_range_to_first_pixel}, and the range-dependent residual branch then always executes
 * (TOPS {@code 1547-1551}, SM {@code 2171-2177}):
 * <pre>
 *   zeroDopplerTime += (slantRange - refRange) / lightSpeedInMetersPerDay
 * </pre>
 * i.e. {@code dt_gslc(R) = (R - R_near) / c} — zero at near range, growing across the swath.
 * <p>
 * The two candidate ETAD conventions are ~20x apart and therefore trivial to tell apart:
 * <ul>
 *   <li><b>FULL</b> bistatic term ~ {@code R/c} ~ 2.7 ms, large and strictly positive.</li>
 *   <li><b>RESIDUAL</b> relative to the IPF bulk correction ~ 0 to 0.2 ms, near zero somewhere in
 *       the swath.</li>
 * </ul>
 * If ETAD carries the residual and matches {@code dt_gslc} in magnitude and range trend, the two are
 * the same quantity and GSLC must not re-apply it once ETAD has resampled the image.
 * <p>
 * Run with:
 * <pre>
 *   mvn test -pl sar-op-sentinel1 -Dtest=ETADBistaticMeasurement \
 *            -Denable.long.tests=true -Dtests.data.dir=E:/TestData/s1tbx
 * </pre>
 */
@RunWith(LongTestRunner.class)
public class ETADBistaticMeasurement {

    private static final File ETAD = new File(TestData.inputSAR
            + "S1/ETAD/IW/S1B_IW_ETA__AXDV_20200815T173048_20200815T173116_022937_02B897_E56D.SAFE/manifest.safe");
    /** The matching IW1 split of the same acquisition, for slant-range geometry. */
    private static final File SLC = new File(TestData.inputSAR
            + "S1/ETAD/IW/S1B_IW_SLC__1SDV_20200815T173048_20200815T173116_022937_02B897_F7CF_Orb_IW1.dim");

    @Test
    public void measureBistaticLayerAgainstGslcResidual() throws Exception {
        assumeTrue("ETAD/SLC pair not found", ETAD.exists() && SLC.exists());

        try (Product etad = ProductIO.readProduct(ETAD);
             Product slc = ProductIO.readProduct(SLC)) {

            // --- what GSLC applies -------------------------------------------------------------
            final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(slc);
            final double rNear = AbstractMetadata.getAttributeDouble(
                    abs, AbstractMetadata.slant_range_to_first_pixel);
            final double rangeSpacing = AbstractMetadata.getAttributeDouble(
                    abs, AbstractMetadata.range_spacing);
            final int width = slc.getSceneRasterWidth();
            final double rFar = rNear + (width - 1) * rangeSpacing;

            // dt_gslc(R) = (R - rNear)/c, so it spans 0 .. (rFar-rNear)/c
            final double dtGslcMax = (rFar - rNear) / Constants.lightSpeed;

            // --- what ETAD carries -------------------------------------------------------------
            String bandName = null;
            for (final String n : etad.getBandNames()) {
                if (n.startsWith("IW1_") && n.endsWith("_bistaticCorrectionAz")) {
                    bandName = n;
                    break;
                }
            }
            assumeTrue("no IW1 *_bistaticCorrectionAz band in the ETAD product", bandName != null);

            final Band bb = etad.getBand(bandName);
            final int w = bb.getRasterWidth(), h = bb.getRasterHeight();
            final float[] v = new float[w * h];
            bb.readPixels(0, 0, w, h, v, ProgressMonitor.NULL);

            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0;
            long n = 0;
            for (final float f : v) {
                if (Float.isNaN(f)) continue;
                min = Math.min(min, f); max = Math.max(max, f); sum += f; n++;
            }
            assumeTrue("no valid bistatic samples", n > 0);
            final double mean = sum / n;

            // Range trend: mean of the first vs last grid column (grid is row-major, width = range).
            double firstCol = 0, lastCol = 0;
            int fc = 0, lc = 0;
            for (int r = 0; r < h; r++) {
                final float a = v[r * w], b = v[r * w + (w - 1)];
                if (!Float.isNaN(a)) { firstCol += a; fc++; }
                if (!Float.isNaN(b)) { lastCol += b; lc++; }
            }
            final double nearMean = fc == 0 ? Double.NaN : firstCol / fc;
            final double farMean = lc == 0 ? Double.NaN : lastCol / lc;
            final double etadSpan = farMean - nearMean;

            System.out.println();
            System.out.println("=== ETAD bistatic vs GSLC residual ===");
            System.out.printf("band %s  (%d x %d)%n", bandName, w, h);
            System.out.printf("SLC IW1: rNear %.1f km  rFar %.1f km  width %d  rgSpacing %.4f m%n",
                    rNear / 1000.0, rFar / 1000.0, width, rangeSpacing);
            System.out.println();
            System.out.printf("GSLC residual dt spans      0 .. %.4f ms   (= (rFar-rNear)/c)%n",
                    dtGslcMax * 1e3);
            System.out.printf("ETAD bistatic layer  min %.4f  max %.4f  mean %.4f  ms%n",
                    min * 1e3, max * 1e3, mean * 1e3);
            System.out.printf("ETAD near-range col mean %.4f ms   far-range col mean %.4f ms%n",
                    nearMean * 1e3, farMean * 1e3);
            System.out.printf("ETAD across-swath span      %.4f ms%n", etadSpan * 1e3);
            System.out.println();
            final double ratio = Math.abs(dtGslcMax) < 1e-12 ? Double.NaN
                    : Math.abs(etadSpan) / Math.abs(dtGslcMax);
            System.out.printf("span ratio ETAD/GSLC = %.3f%n", ratio);
            System.out.printf("|mean| / GSLC span   = %.2f%n", Math.abs(mean) / dtGslcMax);
            System.out.println();
            System.out.println("VERDICT GUIDE:");
            System.out.println("  span ratio ~1 and |mean| comparable to the GSLC span");
            System.out.println("     -> ETAD carries the SAME residual: GSLC must NOT re-apply it.");
            System.out.println("  |mean| >> GSLC span (mean ~2.7 ms, i.e. ~R/c)");
            System.out.println("     -> ETAD carries the FULL term: conventions differ, needs care.");
            System.out.println("  span ratio ~0 (flat across range)");
            System.out.println("     -> not the same quantity; no double-correction from this layer.");
            System.out.println("======================================");

            assertTrue("bistatic layer should be finite", !Double.isNaN(mean));
        }
    }
}
