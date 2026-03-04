package org.jlinda.core;

import org.apache.commons.math3.util.FastMath;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.OrbitStateVector;
import org.jblas.DoubleMatrix;
import org.jlinda.core.io.ResFile;
import org.jlinda.core.utils.DateUtils;
import org.jlinda.core.utils.LinearAlgebraUtils;
import org.jlinda.core.utils.PolyUtils;

import java.io.File;
import java.util.logging.Logger;

public final class Orbit {

    private static final Logger logger = SystemUtils.LOG;

    private boolean isInterpolated = false;

    private int numStateVectors;

    private double[] time;
    private double[] data_X;
    private double[] data_Y;
    private double[] data_Z;
    private double[] coeff_X;
    private double[] coeff_Y;
    private double[] coeff_Z;
    private int poly_degree;

    private static final int MAXITER = 10;
    private final static double CRITERPOS = FastMath.pow(10, -6);
    private final static double CRITERTIM = FastMath.pow(10, -10);

    // ellipsoid axes
    private final static double ell_a = Constants.WGS84_A;
    private final static double ell_b = Constants.WGS84_B;
    private final static double SOL = Constants.SOL;

    public Orbit() {
    }

    public Orbit(double[] timeVector, double[] xVector, double[] yVector, double[] zVector, int degree) {

        numStateVectors = timeVector.length;

        // state vectors
        time = timeVector;
        data_X = xVector;
        data_Y = yVector;
        data_Z = zVector;

        // polynomial coefficients
        poly_degree = degree;
        computeCoefficients();
    }

    public Orbit(double[][] stateVectors, int degree) {

        setOrbit(stateVectors);

        this.poly_degree = degree;
        computeCoefficients();
    }

    public void parseOrbit(File file) throws Exception {
        ResFile resFile = new ResFile(file);
        setOrbit(resFile.parseOrbit());
    }

    // TODO: refactor this one, split in definition and interpolation
    public Orbit(MetadataElement nestMetadataElement, int degree) {

        final OrbitStateVector[] orbitStateVectors = AbstractMetadata.getOrbitStateVectors(nestMetadataElement);

        numStateVectors = orbitStateVectors.length;

        time = new double[numStateVectors];
        data_X = new double[numStateVectors];
        data_Y = new double[numStateVectors];
        data_Z = new double[numStateVectors];

        for (int i = 0; i < numStateVectors; i++) {
            // convert time to seconds of the acquisition day
            time[i] = DateUtils.dateTimeToSecOfDay(orbitStateVectors[i].time.toString());
            data_X[i] = orbitStateVectors[i].x_pos;
            data_Y[i] = orbitStateVectors[i].y_pos;
            data_Z[i] = orbitStateVectors[i].z_pos;
        }

        poly_degree = degree;
        computeCoefficients();
    }

    public void setOrbit(double[][] stateVectors) {

        numStateVectors = stateVectors.length;

        time = new double[numStateVectors];
        data_X = new double[numStateVectors];
        data_Y = new double[numStateVectors];
        data_Z = new double[numStateVectors];

        for (int i = 0; i < stateVectors.length; i++) {
            time[i] = stateVectors[i][0];
            data_X[i] = stateVectors[i][1];
            data_Y[i] = stateVectors[i][2];
            data_Z[i] = stateVectors[i][3];
        }
    }

    // TODO: switch on interpolation method, either spline method or degree of polynomial
    private void computeCoefficients() {

        this.coeff_X = PolyUtils.polyFitNormalized(new DoubleMatrix(time), new DoubleMatrix(data_X), poly_degree);
        this.coeff_Y = PolyUtils.polyFitNormalized(new DoubleMatrix(time), new DoubleMatrix(data_Y), poly_degree);
        this.coeff_Z = PolyUtils.polyFitNormalized(new DoubleMatrix(time), new DoubleMatrix(data_Z), poly_degree);

        isInterpolated = true;
    }

    public void computeCoefficients(int degree) {
        poly_degree = degree;
        computeCoefficients();
    }

    public Point lph2xyz(final double line, final double pixel, final double height, final SLCImage slcimage) throws Exception {
        final double azTime = slcimage.line2ta(line);
        final double rgTime = slcimage.pix2tr(pixel);
        final Point approxXYZ = slcimage.getApproxXYZCentreOriginal();
        return lph2xyz(azTime, rgTime, height, approxXYZ);
    }

    public Point lph2xyz(final double azTime, final double rgTime, final double height, final Point approxXYZCentre)
            throws Exception {

        Point ellipsoidPosition = new Point(approxXYZCentre); // returned

        // allocate matrices
        double[] equationSet = new double[3];
        double[][] partialsXYZ = new double[3][3];

        // Use combined method to get satellite state
        final SatPos satPos = getSatPos(azTime);
        final Point satellitePosition = satPos.position;
        final Point satelliteVelocity = satPos.velocity;

        // Pre-calculate constant terms for the loop
        final double rgTime2 = rgTime * rgTime;
        final double ellA_h_2 = FastMath.pow(ell_a + height, 2);
        final double ellB_h_2 = FastMath.pow(ell_b + height, 2);

        // iterate for the solution
        for (int iter = 0; iter <= MAXITER; iter++) {

            // update equations and solve system
            final Point dsat_P = ellipsoidPosition.min(satellitePosition);

            equationSet[0] = -eq1_Doppler(satelliteVelocity, dsat_P);
            equationSet[1] = -(dsat_P.norm2() - SOL * SOL * rgTime2);
            equationSet[2] = -eq3_Ellipsoid(ellipsoidPosition, height);

            partialsXYZ[0][0] = satelliteVelocity.x;
            partialsXYZ[0][1] = satelliteVelocity.y;
            partialsXYZ[0][2] = satelliteVelocity.z;
            partialsXYZ[1][0] = 2.0 * dsat_P.x;
            partialsXYZ[1][1] = 2.0 * dsat_P.y;
            partialsXYZ[1][2] = 2.0 * dsat_P.z;
            partialsXYZ[2][0] = (2.0 * ellipsoidPosition.x) / ellA_h_2;
            partialsXYZ[2][1] = (2.0 * ellipsoidPosition.y) / ellA_h_2;
            partialsXYZ[2][2] = (2.0 * ellipsoidPosition.z) / ellB_h_2;

            double[] ellipsoidPositionSolution = LinearAlgebraUtils.solve33(partialsXYZ, equationSet);

            // update solution
            ellipsoidPosition.x += ellipsoidPositionSolution[0];
            ellipsoidPosition.y += ellipsoidPositionSolution[1];
            ellipsoidPosition.z += ellipsoidPositionSolution[2];

            // check convergence
            if (Math.abs(ellipsoidPositionSolution[0]) < CRITERPOS &&
                    Math.abs(ellipsoidPositionSolution[1]) < CRITERPOS &&
                    Math.abs(ellipsoidPositionSolution[2]) < CRITERPOS) {
                //logger.info("INFO: ellipsoidPosition (converged): {"+ellipsoidPosition+"} ");
                break;

            } else if (iter >= MAXITER) {
                //logger.warning("line, pix -> x,y,z: maximum iterations ( {"+MAXITER+"} ) reached.");
                //logger.warning("Criterium (m): {"+CRITERPOS+"}  dx,dy,dz = {"+ ArrayUtils.toString(ellipsoidPositionSolution)+"}");

                if (MAXITER > 10) {
                    logger.severe("lp2xyz : MAXITER limit reached! lp2xyz() estimation is diverging?!");
                    throw new Exception("Orbit.lp2xyz : MAXITER limit reached! lp2xyz() estimation is diverging?!");
                }

            }
        }

        return ellipsoidPosition;
    }

    public Point lp2xyz(final Point sarPixel, final SLCImage slcimage) throws Exception {
        return lph2xyz(sarPixel.y, sarPixel.x, 0, slcimage);
    }

    public Point lp2xyz(final double line, final double pixel, final SLCImage slcimage) throws Exception {
        return lph2xyz(line, pixel, 0, slcimage);
    }

    public Point xyz2orb(final Point pointOnEllips, final SLCImage slcimage) {
        // return satellite position
        // Point pointTime = xyz2t(pointOnEllips,slcimage);
        return getXYZ(xyz2t(pointOnEllips, slcimage).y); // inlined
    }

    public Point lp2orb(final Point sarPixel, final SLCImage slcimage) throws Exception {
        // return satellite position
        return getXYZ(xyz2t(lp2xyz(sarPixel, slcimage), slcimage).y); // inlined
    }

    public Point xyz2t(final Point pointOnEllips, final SLCImage slcimage) {

        Point delta;

        // inital value
        double timeAzimuth = slcimage.line2ta(0.5 * slcimage.getApproxRadarCentreOriginal().y);

        int iter;
        double solution = 0;
        for (iter = 0; iter <= MAXITER; ++iter) {
            SatPos satPos = getSatPos(timeAzimuth);
            delta = pointOnEllips.min(satPos.position);

            // update solution
            solution = -eq1_Doppler(satPos.velocity, delta) / eq1_Doppler_dt(delta, satPos.velocity, satPos.acceleration);
            timeAzimuth += solution;

            if (Math.abs(solution) < CRITERTIM) {
                break;
            }
        }

        // Check number of iterations
        if (iter >= MAXITER) {
            //logger.warning("x,y,z -> line, pix: maximum iterations ( {"+MAXITER+"} ) reached. ");
            //logger.warning("Criterium (s): {"+CRITERTIM+"} dta (s)= {"+solution+"}");
        }

        // Compute range time

        // Update equations
        Point satellitePosition = getXYZ(timeAzimuth);
        delta = pointOnEllips.min(satellitePosition);
        double timeRange = delta.norm() / SOL;

        return new Point(timeRange, timeAzimuth);
    }

    public Point xyz2t(final Point pointOnEllips, final double sceneCentreAzimuthTime) {

        Point delta;

        // inital value
        double timeAzimuth = sceneCentreAzimuthTime;

        int iter;
        double solution = 0;
        for (iter = 0; iter <= MAXITER; ++iter) {
            SatPos satPos = getSatPos(timeAzimuth);
            delta = pointOnEllips.min(satPos.position);

            // update solution
            solution = -eq1_Doppler(satPos.velocity, delta) / eq1_Doppler_dt(delta, satPos.velocity, satPos.acceleration);
            timeAzimuth += solution;

            if (Math.abs(solution) < CRITERTIM) {
                break;
            }
        }

        // Check number of iterations
        if (iter >= MAXITER) {
            //logger.warning("x,y,z -> line, pix: maximum iterations ( {"+MAXITER+"} ) reached. ");
            //logger.warning("Criterium (s): {"+CRITERTIM+"} dta (s)= {"+solution+"}");
        }

        // Compute range time

        // Update equations
        Point satellitePosition = getXYZ(timeAzimuth);
        delta = pointOnEllips.min(satellitePosition);
        double timeRange = delta.norm() / SOL;

        return new Point(timeRange, timeAzimuth);
    }


    public Point xyz2lp(final Point pointOnEllips, final SLCImage slcimage) {

        // Compute tazi, tran
        Point time = xyz2t(pointOnEllips, slcimage);

        return new Point(slcimage.tr2pix(time.x), slcimage.ta2line(time.y));
    }

    public Point ell2lp(final double[] phi_lam_height, final SLCImage slcimage) {
        return xyz2lp(Ellipsoid.ell2xyz(phi_lam_height), slcimage);
    }

    public double[] lp2ell(final Point sarPixel, final SLCImage slcimage) throws Exception {
        return Ellipsoid.xyz2ell(lp2xyz(sarPixel, slcimage));
    }

    public double[] lph2ell(final double line, final double pixel, final double height, final SLCImage slcimage) throws Exception {
        return Ellipsoid.xyz2ell(lph2xyz(line, pixel, height, slcimage));
    }

    public double[] lph2ell(final Point sarPixel, final SLCImage slcimage) throws Exception {
        return Ellipsoid.xyz2ell(lph2xyz(sarPixel.x, sarPixel.y, sarPixel.z, slcimage));
    }

    public double[] lph2ell(final Point sarPixel, final double height, final SLCImage slcimage) throws Exception {
        return Ellipsoid.xyz2ell(lph2xyz(sarPixel.x, sarPixel.y, height, slcimage));
    }

    public static class SatPos {
        public Point position;
        public Point velocity;
        public Point acceleration;

        public SatPos(Point position, Point velocity, Point acceleration) {
            this.position = position;
            this.velocity = velocity;
            this.acceleration = acceleration;
        }
    }

    public SatPos getSatPos(final double azTime) {
        // Normalize time for polynomial evaluation
        final double t = (azTime - time[time.length / 2]) / 10.0;

        // Horner's method for simultaneous evaluation of polynomial and its derivatives
        // P(t) = c_n*t^n + ... + c_1*t + c_0
        // P'(t) = n*c_n*t^(n-1) + ... + c_1
        // P''(t) = n*(n-1)*c_n*t^(n-2) + ... + 2*c_2
        double posX = coeff_X[poly_degree], posY = coeff_Y[poly_degree], posZ = coeff_Z[poly_degree];
        double velX = 0, velY = 0, velZ = 0;
        double accX = 0, accY = 0, accZ = 0;

        for (int i = poly_degree - 1; i >= 0; i--) {
            accX = accX * t + 2 * velX;
            accY = accY * t + 2 * velY;
            accZ = accZ * t + 2 * velZ;

            velX = velX * t + posX;
            velY = velY * t + posY;
            velZ = velZ * t + posZ;

            posX = posX * t + coeff_X[i];
            posY = posY * t + coeff_Y[i];
            posZ = posZ * t + coeff_Z[i];
        }

        // Apply scaling factors for derivatives (due to normalized time)
        // Velocity is dP/d(azTime) = dP/dt * dt/d(azTime) = P' * (1/10)
        // Acceleration is d^2P/d(azTime)^2 = d/d(azTime)(P'/10) = d/dt(P'/10) * dt/d(azTime) = (P''/10) * (1/10) = P''/100
        return new SatPos(new Point(posX, posY, posZ),
                new Point(velX / 10.0, velY / 10.0, velZ / 10.0),
                new Point(accX / 100.0, accY / 100.0, accZ / 100.0));
    }

    public Point getXYZ(final double azTime) {

        // normalize time
        double azTimeNormal = (azTime - time[time.length / 2]) / 10.0;

        return new Point(
                PolyUtils.polyVal1D(azTimeNormal, coeff_X),
                PolyUtils.polyVal1D(azTimeNormal, coeff_Y),
                PolyUtils.polyVal1D(azTimeNormal, coeff_Z));
    }

    public Point getXYZDot(double azTime) {

        // normalize time
        azTime = (azTime - time[time.length / 2]) / 10.0;

        int DEGREE = coeff_X.length - 1;

        double x = coeff_X[1];
        double y = coeff_Y[1];
        double z = coeff_Z[1];

        for (int i = 2; i <= DEGREE; ++i) {
            double powT = i * FastMath.pow(azTime, i - 1);
            x += coeff_X[i] * powT;
            y += coeff_Y[i] * powT;
            z += coeff_Z[i] * powT;
        }

        return new Point(x/10.0, y/10.0, z/10.0);
    }

    public Point getXYZDotDot(final double azTime) {

        // normalize time
        double azTimeNormal = (azTime - time[time.length / 2]) / 10.0d;

        // NOTE: orbit interpolator is simple polynomial
        // 2a_2 + 2*3a_3*t^1 + 3*4a_4*t^2...

        double x=0, y=0, z=0;
        for (int i = 2; i <= poly_degree; ++i) {
            double powT = ((i - 1) * i) * FastMath.pow(azTimeNormal, i - 2);
            x += coeff_X[i] * powT;
            y += coeff_Y[i] * powT;
            z += coeff_Z[i] * powT;
        }

        return new Point(x/100.0, y/100.0, z/100.0);

    }

    public double eq1_Doppler(final Point satVelocity, final Point pointOnEllips) {
        return satVelocity.in(pointOnEllips);
    }

    private double eq1_Doppler_dt(final Point pointEllipsSat, final Point satVelocity, final Point satAcceleration) {
        return satAcceleration.in(pointEllipsSat) - satVelocity.x*satVelocity.x - satVelocity.y*satVelocity.y - satVelocity.z*satVelocity.z;
    }

    public double eq2_Range(final Point pointEllipsSat, final double rgTime) {
        return pointEllipsSat.in(pointEllipsSat) - FastMath.pow(SOL * rgTime, 2);
    }

    public double eq3_Ellipsoid(final Point pointOnEllips, final double height) {
        return ((pointOnEllips.x*pointOnEllips.x + pointOnEllips.y*pointOnEllips.y) / FastMath.pow(ell_a + height, 2)) +
                FastMath.pow(pointOnEllips.z / (ell_b + height), 2) - 1.0;
    }

    public double eq3_Ellipsoid(final Point pointOnEllips) {
        return eq3_Ellipsoid(pointOnEllips, 0);
    }

    public double eq3_Ellipsoid(final Point pointOnEllips, final double semiMajorA, final double semiMinorB, final double height) {
        return ((pointOnEllips.x*pointOnEllips.x + pointOnEllips.y*pointOnEllips.y) / FastMath.pow(semiMajorA + height, 2)) +
                FastMath.pow(pointOnEllips.z / (semiMinorB + height), 2) - 1.0;
    }

    public int getNumStateVectors() {
        return numStateVectors;
    }

    public double[] getTime() {
        return time;
    }

    public double[] getData_X() {
        return data_X;
    }

    public double[] getData_Y() {
        return data_Y;
    }

    public double[] getData_Z() {
        return data_Z;
    }

    public double[] getCoeff_X() {
        return coeff_X;
    }

    public double[] getCoeff_Y() {
        return coeff_Y;
    }

    public double[] getCoeff_Z() {
        return coeff_Z;
    }

    public int getPoly_degree() {
        return poly_degree;
    }

    public void setPoly_degree(int degree) {
        poly_degree = degree;
    }

    public boolean isInterpolated() {
        return isInterpolated;
    }

    public double computeEarthRadius(Point p, SLCImage metadata) throws Exception {
        return this.lp2xyz(p, metadata).norm();
    }

    public double computeOrbitRadius(Point p, SLCImage metadata) {
        double azimuthTime = metadata.line2ta(p.y);
        return this.getXYZ(azimuthTime).norm();
    }

    public double computeAzimuthDelta(Point sarPixel, SLCImage metadata) {
        Point pointOnOrbit = this.getXYZ(metadata.line2ta(sarPixel.y));
        Point pointOnOrbitPlusOne = this.getXYZ(metadata.line2ta(sarPixel.y + 1));
        return Math.abs(metadata.getMlAz() * pointOnOrbit.distance(pointOnOrbitPlusOne));
    }

    public double computeAzimuthResolution(Point sarPixel, SLCImage metadata) {
        return (metadata.getPRF() / metadata.getAzimuthBandwidth()) * (this.computeAzimuthDelta(sarPixel, metadata) / metadata.getMlAz());
    }
}
