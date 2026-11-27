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
package eu.esa.sar.orbits.io;

import eu.esa.sar.commons.test.TestData;
import eu.esa.sar.orbits.io.sentinel1.Sentinel1OrbitFileReader;
import org.esa.snap.engine_utilities.datamodel.Orbits;
import com.bc.ceres.annotation.STTM;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Apply-Orbit resamples Sentinel-1 POEORB vectors (10 s apart) to the product's 1 s vectors. A cubic
 * fitted separately to 4 positions and to 4 velocities left 2.4 mm of position error, and positions
 * whose derivative disagreed with the velocities by ~0.5 mm/s (snap-validation orbit_accuracy run
 * 20261126T215414Z). An 8-point Lagrange polynomial through the positions, with the velocity taken
 * as its derivative, fixes both.
 * <p>
 * Times are MJD doubles, which resolve only 0.08 us (2014) to 0.16 us (2026): ~1 mm along track.
 * The analytic orbit is therefore built at exact epochs (whole seconds from {@link #MJD0}) and the
 * truth for a query is taken at the query's own offset from MJD0.
 */
public class TestOrbitInterpolation {

    private static final double MU = 3.986004418e14;
    private static final double R = 7.07e6;                      // Sentinel-1-like orbit radius
    private static final double OMEGA = Math.sqrt(MU / (R * R * R));
    private static final double INC = Math.toRadians(98.18);
    private static final double MJD0 = 9670.95;                  // days since 2000-01-01, as ProductData.UTC
    private static final double DAY = 86400.0;

    private static final File POEORB = new File(TestData.inputSAR +
            "Orbits/S1A_OPER_AUX_POEORB_OPOD_20140911T123201_V20140820T225944_20140822T005944.EOF");

    /** analytic circular orbit, {x, y, z, vx, vy, vz} at sec seconds after MJD0 */
    private static double[] truthAt(double sec) {
        final double th = OMEGA * sec + 0.7;
        final double s = R * OMEGA;
        return new double[]{
                R * Math.cos(th), R * Math.sin(th) * Math.cos(INC), R * Math.sin(th) * Math.sin(INC),
                -s * Math.sin(th), s * Math.cos(th) * Math.cos(INC), s * Math.cos(th) * Math.sin(INC)};
    }

    /** the truth at the time an MJD double stands for, relative to MJD0 */
    private static double[] truth(double mjd) {
        return truthAt((mjd - MJD0) * DAY);
    }

    /** vectors at exact epochs i * spacing seconds after MJD0, tagged with the nearest MJD double */
    private static List<Orbits.OrbitVector> analyticVectors(int n, double spacingSeconds) {
        final List<Orbits.OrbitVector> osv = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final double[] p = truthAt(i * spacingSeconds);
            osv.add(new Orbits.OrbitVector(MJD0 + i * spacingSeconds / DAY, p[0], p[1], p[2], p[3], p[4], p[5]));
        }
        return osv;
    }

    private static double posError(Orbits.OrbitVector v, double[] t) {
        return Math.sqrt(sq(v.xPos - t[0]) + sq(v.yPos - t[1]) + sq(v.zPos - t[2]));
    }

    private static double velError(Orbits.OrbitVector v, double[] t) {
        return Math.sqrt(sq(v.xVel - t[3]) + sq(v.yVel - t[4]) + sq(v.zVel - t[5]));
    }

    private static double sq(double x) {
        return x * x;
    }

    @Test
    @STTM("SNAP-4392")
    public void tenSecondVectorsAreInterpolatedToSubMillimetre() {
        final List<Orbits.OrbitVector> osv = analyticVectors(30, 10.0);
        double ep = 0, ev = 0;
        for (double sec = 0; sec <= 290.0; sec += 0.1) {              // 10 Hz, edge segments included
            final double mjd = MJD0 + sec / DAY;
            final Orbits.OrbitVector v = OrbitInterpolation.interpolate(osv, mjd);
            assertEquals(mjd, v.utcMJD, 0.0);
            ep = Math.max(ep, posError(v, truth(mjd)));
            ev = Math.max(ev, velError(v, truth(mjd)));
        }
        assertTrue("position error " + ep + " m", ep < 1e-5);        // 0.01 mm
        assertTrue("velocity error " + ev + " m/s", ev < 1e-6);      // 0.001 mm/s
    }

    @Test
    @STTM("SNAP-4392")
    public void reproducesTheVectorsAtTheirEpochs() {
        final List<Orbits.OrbitVector> osv = analyticVectors(12, 10.0);
        for (Orbits.OrbitVector node : osv) {
            final Orbits.OrbitVector v = OrbitInterpolation.interpolate(osv, node.utcMJD);
            final double[] t = truth(node.utcMJD);
            assertTrue(posError(v, t) < 1e-5);
            assertTrue(velError(v, t) < 1e-6);
        }
    }

    @Test
    @STTM("SNAP-4392")
    public void velocityIsTheDerivativeOfPosition() {
        final List<Orbits.OrbitVector> osv = analyticVectors(30, 10.0);
        final double h = 0.01;                                         // seconds
        for (double sec = 1.3; sec < 288; sec += 7.1) {
            final Orbits.OrbitVector a = OrbitInterpolation.interpolate(osv, MJD0 + (sec - h) / DAY);
            final Orbits.OrbitVector b = OrbitInterpolation.interpolate(osv, MJD0 + (sec + h) / DAY);
            final Orbits.OrbitVector v = OrbitInterpolation.interpolate(osv, MJD0 + sec / DAY);
            final double dt = (b.utcMJD - a.utcMJD) * DAY;
            final double dx = (b.xPos - a.xPos) / dt - v.xVel;
            final double dy = (b.yPos - a.yPos) / dt - v.yVel;
            final double dz = (b.zPos - a.zPos) / dt - v.zVel;
            assertTrue("t=" + sec + " s", Math.sqrt(dx * dx + dy * dy + dz * dz) < 1e-4);
        }
    }

    @Test
    @STTM("SNAP-4392")
    public void fewerVectorsThanTheWindow() {
        final List<Orbits.OrbitVector> four = analyticVectors(4, 10.0);
        for (Orbits.OrbitVector node : four) {                            // a cubic through all four
            assertTrue(posError(OrbitInterpolation.interpolate(four, node.utcMJD), truth(node.utcMJD)) < 1e-5);
        }
        final List<Orbits.OrbitVector> one = analyticVectors(1, 10.0);
        final Orbits.OrbitVector w = OrbitInterpolation.interpolate(one, MJD0);
        assertEquals(one.get(0).xPos, w.xPos, 0.0);
        assertEquals(one.get(0).zVel, w.zVel, 0.0);
    }

    @Test
    @STTM("SNAP-4392")
    public void realPoeorbLeaveOneOut() throws Exception {
        // every other POEORB vector dropped (20 s spacing), the dropped ones interpolated back
        assumeTrue(POEORB + " not found", POEORB.exists());
        final Sentinel1OrbitFileReader reader = new Sentinel1OrbitFileReader(POEORB);
        reader.read();
        final List<Orbits.OrbitVector> all = reader.getOrbitStateVectors();
        final List<Orbits.OrbitVector> even = new ArrayList<>();
        for (int i = 0; i < all.size(); i += 2) {
            even.add(all.get(i));
        }
        final double t0 = all.get(0).utcMJD;
        double ep = 0, ev = 0;
        for (int i = 3; i < all.size() - 3; i += 2) {
            final Orbits.OrbitVector o = all.get(i);
            final Orbits.OrbitVector v = OrbitInterpolation.interpolate(even, o.utcMJD);
            // the file's epochs are whole seconds: move the file's vector to the time its MJD double stands for
            final double sec = (o.utcMJD - t0) * DAY, shift = sec - Math.rint(sec);
            final double[] fileAtQuery = {o.xPos + o.xVel * shift, o.yPos + o.yVel * shift, o.zPos + o.zVel * shift,
                    o.xVel, o.yVel, o.zVel};
            ep = Math.max(ep, posError(v, fileAtQuery));
            ev = Math.max(ev, velError(v, fileAtQuery));
        }
        // The cubic gave 37 mm here (velocity 0.04 mm/s). The velocity is compared with the file's,
        // which disagree with the file's own positions by up to 0.07 mm/s at a few epochs.
        assertTrue("position error " + ep + " m", ep < 2e-4);        // 0.2 mm at 20 s spacing
        assertTrue("velocity error " + ev + " m/s", ev < 1e-4);      // 0.1 mm/s
    }
}
