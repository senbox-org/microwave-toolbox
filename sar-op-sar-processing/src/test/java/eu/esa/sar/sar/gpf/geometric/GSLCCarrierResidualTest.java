package eu.esa.sar.sar.gpf.geometric;

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.dataio.ProductIO;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Regression bound on the cross-acquisition TOPS azimuth-carrier residual.
 * <p>
 * With the carrier restored on each leg independently, a two-acquisition GSLC interferogram
 * carries a per-burst quadratic azimuth phase (measured 141/161 rad excursion per burst on the
 * S1A/S1D fixture — ~24 spurious fringes). With carrier-free legs the interferogram phase binned
 * by source azimuth line must wander only by the physical signal (atmosphere/deformation, a few
 * radians). This test bins the finished fixture interferogram by azimuth line, per burst, and
 * bounds the unwrapped excursion of the line-mean phase — no classical control needed.
 * <p>
 * File-gated on the carrier-free fixture products; the self-pair tests are structurally blind to
 * this defect class (one acquisition ⇒ identical carriers ⇒ perfect cancellation), so this
 * two-acquisition bound is the only automated guard.
 */
public class GSLCCarrierResidualTest {

    private static final File DIR = new File(System.getProperty("gslc.diagDir", "E:/Output/gslcdiag"));

    /**
     * Max tolerated QUADRATIC sag of the per-line mean phase within one burst (rad).
     * <p>
     * The azimuth-carrier defect is a per-burst quadratic (−π·ktA(η−ηrefA)² + π·ktB(η−ηrefB)²):
     * measured sag ~60 rad per burst with the carrier restored. A carrier-free interferogram may
     * still carry a global LINEAR ramp (orbital-baseline-like, benign, removed by standard InSAR
     * ramp handling — measured ~0.02 rad/px), so the bound targets the quadratic term only.
     */
    private static final double MAX_QUAD_SAG_RAD = 15.0;

    @Test
    public void carrierResidualBoundedPerBurst() throws Exception {
        final File ifgFile = new File(DIR, "gslc_ifg_cf.dim");
        final File diagFile = new File(DIR, "mgcf.dim");
        assumeTrue("carrier-free fixture products not present", ifgFile.exists() && diagFile.exists());

        final Product ifg = ProductIO.readProduct(ifgFile);
        final Product diag = ProductIO.readProduct(diagFile);
        try {
            Band bi = null, bq = null;
            for (final Band b : ifg.getBands()) {
                if (b.getName().startsWith("i_ifg")) bi = b;
                if (b.getName().startsWith("q_ifg")) bq = b;
            }
            assertNotNull("ifg i band", bi);
            assertNotNull("ifg q band", bq);
            final Band az = diag.getBand("diag_azimuthIndex");
            final Band bu = diag.getBand("diag_burst");
            assertNotNull("diag bands required (run geocode with -Dgslc.diagGeometry=true)", az);

            // integer grid offset diag -> ifg (both co-lattice by construction)
            final GeoPos geo = diag.getSceneGeoCoding().getGeoPos(new PixelPos(100.5f, 100.5f), null);
            final PixelPos pp = ifg.getSceneGeoCoding().getPixelPos(geo, null);
            final int ox = (int) Math.round(pp.x - 100.5);
            final int oy = (int) Math.round(pp.y - 100.5);

            final int x0 = 2000, w = 1024;
            final int h = Math.min(diag.getSceneRasterHeight(), ifg.getSceneRasterHeight() - oy) - 4;
            final double[] azRow = new double[w];
            final double[] buRow = new double[w];
            final float[] iRow = new float[w];
            final float[] qRow = new float[w];

            final int linesPerBurst = 1600;   // generous upper bound; used for bin layout only
            final double[][] re = new double[3][linesPerBurst];
            final double[][] im = new double[3][linesPerBurst];
            final int[][] cnt = new int[3][linesPerBurst];

            for (int y = 0; y < h; y++) {
                final int iy = y + oy;
                if (iy < 0 || iy >= ifg.getSceneRasterHeight()) continue;
                az.readPixels(x0, y, w, 1, azRow);
                bu.readPixels(x0, y, w, 1, buRow);
                bi.readPixels(x0 + ox, iy, w, 1, iRow);
                bq.readPixels(x0 + ox, iy, w, 1, qRow);
                for (int k = 0; k < w; k++) {
                    final int b = (int) Math.rint(buRow[k]);
                    if (b < 1 || b > 2) continue;
                    if (iRow[k] == 0 && qRow[k] == 0) continue;
                    final int line = (int) Math.rint(azRow[k]) - (b - 1) * 1504;
                    if (line < 0 || line >= linesPerBurst) continue;
                    final double m = Math.hypot(iRow[k], qRow[k]);
                    if (m <= 0) continue;
                    re[b][line] += iRow[k] / m;
                    im[b][line] += qRow[k] / m;
                    cnt[b][line]++;
                }
            }

            for (int b = 1; b <= 2; b++) {
                // sequential unwrap of the per-line mean phase
                final java.util.List<double[]> pts = new java.util.ArrayList<>();
                double prev = Double.NaN, unwrapped = 0;
                for (int l = 0; l < linesPerBurst; l++) {
                    if (cnt[b][l] < 300) continue;
                    final double a = Math.atan2(im[b][l], re[b][l]);
                    if (!Double.isNaN(prev)) {
                        double d = a - prev;
                        while (d > Math.PI) d -= 2 * Math.PI;
                        while (d < -Math.PI) d += 2 * Math.PI;
                        unwrapped += d;
                    }
                    prev = a;
                    pts.add(new double[]{l, unwrapped});
                }
                if (pts.size() < 100) continue;   // burst not covered by this strip

                // quadratic LS fit: phase = a2*l^2 + a1*l + a0. The carrier signature is the
                // QUADRATIC term; a linear ramp (orbital-baseline-like) is benign.
                final int n = pts.size();
                double s1 = 0, s2 = 0, s3 = 0, s4 = 0, t0 = 0, t1 = 0, t2 = 0;
                for (final double[] p : pts) {
                    final double l = p[0], f = p[1];
                    s1 += l; s2 += l * l; s3 += l * l * l; s4 += l * l * l * l;
                    t0 += f; t1 += f * l; t2 += f * l * l;
                }
                final double[][] M = {{s4, s3, s2}, {s3, s2, s1}, {s2, s1, (double) n}};
                final double[] r = {t2, t1, t0};
                final double det = M[0][0] * (M[1][1] * M[2][2] - M[1][2] * M[2][1])
                        - M[0][1] * (M[1][0] * M[2][2] - M[1][2] * M[2][0])
                        + M[0][2] * (M[1][0] * M[2][1] - M[1][1] * M[2][0]);
                final double detA2 = r[0] * (M[1][1] * M[2][2] - M[1][2] * M[2][1])
                        - M[0][1] * (r[1] * M[2][2] - M[1][2] * r[2])
                        + M[0][2] * (r[1] * M[2][1] - M[1][1] * r[2]);
                final double a2 = detA2 / det;
                final double span = pts.get(n - 1)[0] - pts.get(0)[0];
                final double quadSag = Math.abs(a2) * (span / 2) * (span / 2);
                System.out.printf(
                        "CARRIER-RESIDUAL burst %d: lines=%d  a2=%+.3e rad/line^2  quad sag=%.1f rad  " +
                                "(carrier-restored measures ~60 rad sag)%n",
                        b, n, a2, quadSag);
                assertTrue(String.format(
                        "burst %d azimuth-carrier quadratic too large: %.1f rad sag (limit %.0f). " +
                                "Are both legs carrier-free (outputAzimuthCarrier=false)?",
                        b, quadSag, MAX_QUAD_SAG_RAD),
                        quadSag < MAX_QUAD_SAG_RAD);
            }
        } finally {
            ifg.dispose();
            diag.dispose();
        }
    }
}
