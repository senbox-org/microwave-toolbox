package eu.esa.sar.sar.gpf.geometric;

import eu.esa.sar.commons.Sentinel1Utils;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.dataio.ProductIO;
import org.junit.Test;

import java.io.File;

import static org.junit.Assume.assumeTrue;

/**
 * Mechanism probe for the cross-acquisition azimuth-carrier residual.
 * <p>
 * Measured on the fixture (GSLC ifg minus classical, binned by source azimuth line): a per-burst
 * quadratic phase — burst 1 slope −0.071→+0.249 rad/line (excursion ≈ 141 rad), burst 2
 * +0.174→+0.049 (≈ 161 rad). Hypothesis: this is exactly the difference of the two acquisitions'
 * TOPS deramp carriers, which each GSLC leg restores independently:
 * ifg carrier phase = −(φd_A − φd_B) with φd from {@link GSLCGeocodingOp#computeDerampDemodPhaseAt}.
 * <p>
 * This probe computes that difference directly from the two SLCs' annotated FM rates and burst
 * times at the exact source positions recorded in the diag bands, fits the same per-burst
 * quadratic, and prints both for comparison. No interferogram involved — if the predicted curves
 * match the measured ones, the mechanism is confirmed.
 */
public class GSLCCarrierDiffProbeTest {

    private static final File DIR = new File(System.getProperty("gslc.diagDir", "E:/Output/gslcdiag"));

    @Test
    public void predictCarrierDifference() throws Exception {
        final File mSlc = new File(DIR, "m.dim");
        final File sSlc = new File(DIR, "s.dim");
        final File mgd = new File(DIR, "mgd.dim");
        final File sgd = new File(DIR, "sgd.dim");
        assumeTrue("fixtures not present", mSlc.exists() && sSlc.exists() && mgd.exists() && sgd.exists());

        final Product pm = ProductIO.readProduct(mSlc);
        final Product ps = ProductIO.readProduct(sSlc);
        final Sentinel1Utils suA = new Sentinel1Utils(pm);
        suA.computeDopplerRate();
        suA.computeReferenceTime();
        final Sentinel1Utils suB = new Sentinel1Utils(ps);
        suB.computeDopplerRate();
        suB.computeReferenceTime();
        final Sentinel1Utils.SubSwathInfo[] swA = suA.getSubSwath();
        final Sentinel1Utils.SubSwathInfo[] swB = suB.getSubSwath();

        final Product pa = ProductIO.readProduct(mgd);
        final Product pb = ProductIO.readProduct(sgd);
        final Band aRg = pa.getBand("diag_rangeIndex");
        final Band aAz = pa.getBand("diag_azimuthIndex");
        final Band aBu = pa.getBand("diag_burst");
        final Band bRg = pb.getBand("diag_rangeIndex");
        final Band bAz = pb.getBand("diag_azimuthIndex");
        final Band bBu = pb.getBand("diag_burst");

        // March down a map column of leg A; find the same ground point in leg B via geocoding.
        // Whole columns are read in single block calls — per-pixel readPixels is pathologically
        // slow (each call can re-inflate a full tile).
        final int xA = 2800;
        final int hA = pa.getSceneRasterHeight();
        final double[] colARg = new double[hA];
        final double[] colAAz = new double[hA];
        final double[] colABu = new double[hA];
        aRg.readPixels(xA, 0, 1, hA, colARg);
        aAz.readPixels(xA, 0, 1, hA, colAAz);
        aBu.readPixels(xA, 0, 1, hA, colABu);

        final int wB = pb.getSceneRasterWidth();
        final int hB = pb.getSceneRasterHeight();
        // leg B columns near the corresponding x — grids are near-parallel, so a small x-window
        // of full columns covers all NN lookups
        final GeoPos geo = new GeoPos();
        final PixelPos pixB = new PixelPos();
        pa.getSceneGeoCoding().getGeoPos(new PixelPos(xA + 0.5, hA / 2f), geo);
        pb.getSceneGeoCoding().getPixelPos(geo, pixB);
        final int xB0 = Math.max(0, (int) Math.floor(pixB.x) - 8);
        final int nBx = Math.min(17, wB - xB0);
        final double[][] colBRg = new double[nBx][hB];
        final double[][] colBAz = new double[nBx][hB];
        final double[][] colBBu = new double[nBx][hB];
        for (int i = 0; i < nBx; i++) {
            bRg.readPixels(xB0 + i, 0, 1, hB, colBRg[i]);
            bAz.readPixels(xB0 + i, 0, 1, hB, colBAz[i]);
            bBu.readPixels(xB0 + i, 0, 1, hB, colBBu[i]);
        }

        System.out.println("CARRIER-PROBE  pred = -(phi_dA - phi_dB) fitted per burst");
        final java.util.Map<Integer, java.util.List<double[]>> perBurst = new java.util.HashMap<>();
        for (int y = 60; y < hA - 60; y += 4) {
            final int burstA = (int) Math.rint(colABu[y]);
            if (burstA < 1) continue;
            final double rgA = colARg[y];
            final double azA = colAAz[y];

            pa.getSceneGeoCoding().getGeoPos(new PixelPos(xA + 0.5, y + 0.5), geo);
            pb.getSceneGeoCoding().getPixelPos(geo, pixB);
            final int xB = (int) Math.floor(pixB.x);
            final int yB = (int) Math.floor(pixB.y);
            if (xB < xB0 || xB >= xB0 + nBx || yB < 0 || yB >= hB) continue;
            final int burstB = (int) Math.rint(colBBu[xB - xB0][yB]);
            if (burstB < 1) continue;
            final double rgB = colBRg[xB - xB0][yB];
            final double azB = colBAz[xB - xB0][yB];

            final double phiA = GSLCGeocodingOp.computeDerampDemodPhaseAt(swA, 1, burstA - 1, rgA, azA);
            final double phiB = GSLCGeocodingOp.computeDerampDemodPhaseAt(swB, 1, burstB - 1, rgB, azB);
            final double pred = -(phiA - phiB);
            perBurst.computeIfAbsent(burstA, k -> new java.util.ArrayList<>())
                    .add(new double[]{azA - (burstA - 1) * swA[0].linesPerBurst, pred});
        }

        for (final int b : new java.util.TreeSet<>(perBurst.keySet())) {
            final java.util.List<double[]> pts = perBurst.get(b);
            final int n = pts.size();
            // quadratic LS fit pred = a2*l^2 + a1*l + a0 via normal equations
            double s0 = n, s1 = 0, s2 = 0, s3 = 0, s4 = 0, t0 = 0, t1 = 0, t2 = 0;
            for (final double[] p : pts) {
                final double l = p[0], f = p[1];
                s1 += l; s2 += l * l; s3 += l * l * l; s4 += l * l * l * l;
                t0 += f; t1 += f * l; t2 += f * l * l;
            }
            // solve 3x3
            final double[][] M = {{s4, s3, s2}, {s3, s2, s1}, {s2, s1, s0}};
            final double[] r = {t2, t1, t0};
            final double[] c = solve3(M, r);
            final double l0 = pts.get(0)[0], l1 = pts.get(n - 1)[0];
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (final double[] p : pts) { lo = Math.min(lo, p[1]); hi = Math.max(hi, p[1]); }
            System.out.printf("CARRIER-PROBE burst %d: n=%d lines %.0f..%.0f%n", b, n, l0, l1);
            System.out.printf("CARRIER-PROBE    quad a2=%+.5e rad/line^2  a1=%+.5f rad/line%n", c[0], c[1]);
            System.out.printf("CARRIER-PROBE    slope start=%+.4f mid=%+.4f end=%+.4f rad/line   excursion=%.1f rad%n",
                    2 * c[0] * l0 + c[1], 2 * c[0] * (l0 + l1) / 2 + c[1], 2 * c[0] * l1 + c[1], hi - lo);
        }
        System.out.println("CARRIER-PROBE MEASURED (from ifg minus classical):");
        System.out.println("CARRIER-PROBE    burst 1: a2=+1.126e-04  slope -0.071..+0.249  excursion 141 rad");
        System.out.println("CARRIER-PROBE    burst 2: a2=-4.498e-05  slope +0.174..+0.049  excursion 161 rad");

        pa.dispose(); pb.dispose(); pm.dispose(); ps.dispose();
    }

    private static double[] solve3(final double[][] m, final double[] r) {
        final double det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
                - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
        final double[] out = new double[3];
        for (int k = 0; k < 3; k++) {
            final double[][] mm = {{m[0][0], m[0][1], m[0][2]}, {m[1][0], m[1][1], m[1][2]}, {m[2][0], m[2][1], m[2][2]}};
            for (int i = 0; i < 3; i++) mm[i][k] = r[i];
            out[k] = (mm[0][0] * (mm[1][1] * mm[2][2] - mm[1][2] * mm[2][1])
                    - mm[0][1] * (mm[1][0] * mm[2][2] - mm[1][2] * mm[2][0])
                    + mm[0][2] * (mm[1][0] * mm[2][1] - mm[1][1] * mm[2][0])) / det;
        }
        return out;
    }
}
