package org.jlinda.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Accuracy of {@link Orbit} against an analytic orbit, whatever the polynomial degree asked for and
 * however long the state-vector span. A single least-squares polynomial over all vectors missed the
 * nodes by 11-14 mm at degree 3 on a Sentinel-1 Apply-Orbit product (1 s vectors, ~50 s) and by
 * 36-229 m on a BIOMASS product (261 vectors, 1 s) — measured by snap-validation orbit_accuracy.
 */
public class OrbitInterpolationTest {

    private static final double MU = 3.986004418e14;
    private static final double R = 7.07e6;                      // Sentinel-1-like orbit radius
    private static final double OMEGA = Math.sqrt(MU / (R * R * R));
    private static final double INC = Math.toRadians(98.18);
    private static final double T0 = 36000.0;                    // seconds of day

    private static double[] truePos(double t) {
        final double th = OMEGA * (t - T0) + 0.7;
        return new double[]{R * Math.cos(th), R * Math.sin(th) * Math.cos(INC), R * Math.sin(th) * Math.sin(INC)};
    }

    private static double[] trueVel(double t) {
        final double th = OMEGA * (t - T0) + 0.7;
        final double s = R * OMEGA;
        return new double[]{-s * Math.sin(th), s * Math.cos(th) * Math.cos(INC), s * Math.cos(th) * Math.sin(INC)};
    }

    private static double[] trueAcc(double t) {
        final double[] p = truePos(t);
        final double w2 = OMEGA * OMEGA;
        return new double[]{-w2 * p[0], -w2 * p[1], -w2 * p[2]};
    }

    private static double[][] stateVectors(int n, double spacing) {
        final double[][] sv = new double[n][];
        for (int i = 0; i < n; i++) {
            final double t = T0 + i * spacing;
            final double[] p = truePos(t);
            sv[i] = new double[]{t, p[0], p[1], p[2]};
        }
        return sv;
    }

    private static double dist(Point a, double[] b) {
        return Math.sqrt((a.x - b[0]) * (a.x - b[0]) + (a.y - b[1]) * (a.y - b[1]) + (a.z - b[2]) * (a.z - b[2]));
    }

    /** max position, velocity and acceleration error over the whole vector span at 10 Hz */
    private static double[] maxErrors(Orbit orbit, double[][] sv) {
        final double t0 = sv[0][0], t1 = sv[sv.length - 1][0];
        double ep = 0, ev = 0, ea = 0;
        for (double t = t0; t <= t1 + 1e-9; t += 0.1) {
            ep = Math.max(ep, dist(orbit.getXYZ(t), truePos(t)));
            ev = Math.max(ev, dist(orbit.getXYZDot(t), trueVel(t)));
            ea = Math.max(ea, dist(orbit.getXYZDotDot(t), trueAcc(t)));
        }
        return new double[]{ep, ev, ea};
    }

    private static void assertAccurate(Orbit orbit, double[][] sv) {
        final double[] e = maxErrors(orbit, sv);
        assertTrue("position error " + e[0] + " m", e[0] < 1e-4);      // 0.1 mm
        assertTrue("velocity error " + e[1] + " m/s", e[1] < 1e-5);    // 0.01 mm/s
        assertTrue("acceleration error " + e[2] + " m/s2", e[2] < 1e-5);
    }

    @Test
    public void degree3OnApplyOrbitSpan() {
        // Sentinel-1 IW product after Apply-Orbit: 1 s vectors over the product +- 10 s
        final double[][] sv = stateVectors(50, 1.0);
        assertAccurate(new Orbit(sv, 3), sv);
    }

    @Test
    public void degree4OnLongSpan() {
        // BIOMASS annotation: 261 vectors at 1 s for a 21 s product
        final double[][] sv = stateVectors(261, 1.0);
        assertAccurate(new Orbit(sv, 4), sv);
        assertAccurate(new Orbit(sv, 3), sv);
    }

    @Test
    public void tenSecondSpacing() {
        // Sentinel-1 annotation / POEORB spacing
        final double[][] sv = stateVectors(21, 10.0);
        assertAccurate(new Orbit(sv, 3), sv);
    }

    @Test
    public void anyDegreeGivesTheSameOrbit() {
        final double[][] sv = stateVectors(50, 1.0);
        final Orbit ref = new Orbit(sv, 3);
        for (int degree = 1; degree <= 5; degree++) {
            final Orbit o = new Orbit(sv, degree);
            for (double t = T0; t <= T0 + 49; t += 0.37) {
                assertEquals(0.0, o.getXYZ(t).distance(ref.getXYZ(t)), 1e-9);
            }
        }
    }

    @Test
    public void fewVectorsPassThroughTheNodes() {
        final double[][] sv = stateVectors(5, 10.0);
        final Orbit orbit = new Orbit(sv, 3);
        for (double[] v : sv) {
            assertEquals(0.0, dist(orbit.getXYZ(v[0]), new double[]{v[1], v[2], v[3]}), 1e-6);
        }
    }

    @Test
    public void repeatedVectorIsTolerated() {
        final double[][] base = stateVectors(50, 1.0);
        final double[][] sv = new double[51][];
        System.arraycopy(base, 0, sv, 0, 25);
        sv[25] = base[24].clone();                                   // same epoch twice
        System.arraycopy(base, 25, sv, 26, 25);
        assertAccurate(new Orbit(sv, 3), base);
    }

    @Test
    public void satPosAgreesWithTheSeparateAccessors() {
        final double[][] sv = stateVectors(50, 1.0);
        final Orbit orbit = new Orbit(sv, 3);
        for (double t = T0 + 0.05; t < T0 + 49; t += 1.3) {
            final Orbit.SatPos sp = orbit.getSatPos(t);
            assertEquals(0.0, sp.position.distance(orbit.getXYZ(t)), 1e-9);
            assertEquals(0.0, sp.velocity.distance(orbit.getXYZDot(t)), 1e-9);
            assertEquals(0.0, sp.acceleration.distance(orbit.getXYZDotDot(t)), 1e-9);
        }
    }

    @Test
    public void setOrbitThenComputeCoefficients() {
        // the ResFile path: setOrbit(...) and computeCoefficients(degree) separately
        final double[][] sv = stateVectors(50, 1.0);
        final Orbit orbit = new Orbit();
        orbit.setOrbit(sv);
        orbit.computeCoefficients(3);
        assertAccurate(orbit, sv);
    }
}
