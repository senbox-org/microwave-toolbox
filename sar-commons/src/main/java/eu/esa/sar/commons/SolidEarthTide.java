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
package eu.esa.sar.commons;

/**
 * Solid Earth Tide displacement (IERS 2010 step-1 body tides) using
 * low-precision Sun and Moon ephemerides.
 * <p>
 * The vertical body-tide displacement reaches ~30 cm peak; for InSAR pairs the
 * relevant signal is the *difference* between two acquisition epochs, typically
 * a few millimetres to a few centimetres. Not applying SET leaves a systematic
 * tilted-fringe bias in any GSLC-based interferogram.
 * <p>
 * This implementation:
 * <ul>
 *   <li>Uses Meeus low-precision Sun (chapter 25) and Moon (chapter 47) positions —
 *       accurate to a few arcminutes, which translates to sub-millimetre SET error.</li>
 *   <li>Applies the IERS 2010 Conventions, Eq. 7.5 step-1 displacement (Wahr 1981)
 *       with degree-2 Love numbers {h2=0.6078, l2=0.0847}.</li>
 *   <li>Does NOT apply step-2 (frequency-dependent) or permanent-tide corrections.
 *       These contribute at the sub-cm level and cancel in differential InSAR.</li>
 * </ul>
 * Reference: IERS Technical Note 36 (2010), Chapter 7.1.1.
 */
public final class SolidEarthTide {

    /** WGS84 semi-major axis (m). */
    public static final double A_EARTH = 6378137.0;
    /** WGS84 first eccentricity squared. */
    public static final double E2_EARTH = 6.69437999014e-3;

    /** Ratio of solar to Earth gravitational parameter (GM_Sun / GM_Earth). */
    public static final double GM_SUN_OVER_GM_EARTH = 332946.0487;
    /** Ratio of lunar to Earth gravitational parameter (GM_Moon / GM_Earth). */
    public static final double GM_MOON_OVER_GM_EARTH = 1.0 / 81.30056907;

    /** Degree-2 vertical Love number (IERS 2010 nominal). */
    public static final double H2 = 0.6078;
    /** Degree-2 horizontal (Shida) Love number (IERS 2010 nominal). */
    public static final double L2 = 0.0847;

    /** Mean Earth-Sun distance (m). Used by the low-precision ephemeris. */
    public static final double AU = 1.495978707e11;

    private SolidEarthTide() {}

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Compute the SET displacement at a site in the local ENU frame.
     *
     * @param mjdUtc UTC Modified Julian Date (e.g. 54934.0 for 2009-04-13 00:00:00 UTC).
     * @param latDeg geodetic latitude (degrees, +N).
     * @param lonDeg geodetic longitude (degrees, +E).
     * @param altM   altitude above the WGS84 ellipsoid (m).
     * @return {east, north, up} displacement in metres.
     */
    public static double[] computeEnuDisplacement(final double mjdUtc, final double latDeg,
                                                  final double lonDeg, final double altM) {
        final double[] dEcef = computeEcefDisplacement(mjdUtc, latDeg, lonDeg, altM);
        return ecefToEnu(dEcef, latDeg, lonDeg);
    }

    /**
     * Compute the SET displacement at a site in the ECEF frame.
     *
     * @param mjdUtc UTC Modified Julian Date.
     * @param latDeg geodetic latitude (degrees, +N).
     * @param lonDeg geodetic longitude (degrees, +E).
     * @param altM   altitude above the WGS84 ellipsoid (m).
     * @return {dx, dy, dz} displacement in metres (ECEF).
     */
    public static double[] computeEcefDisplacement(final double mjdUtc, final double latDeg,
                                                   final double lonDeg, final double altM) {
        final double[] siteEcef = geodeticToEcef(latDeg, lonDeg, altM);
        // Sun/Moon ephemerides in the GCRS (Geocentric Celestial Reference System,
        // close enough to inertial for SET). Rotate to ECEF using GMST.
        final double[] sunIcrs = sunPositionGcrs(mjdUtc);
        final double[] moonIcrs = moonPositionGcrs(mjdUtc);
        final double gmst = greenwichMeanSiderealTime(mjdUtc);
        final double[] sunEcef = rotateInertialToEcef(sunIcrs, gmst);
        final double[] moonEcef = rotateInertialToEcef(moonIcrs, gmst);
        return stepOneDisplacement(siteEcef, sunEcef, moonEcef);
    }

    // -----------------------------------------------------------------------
    // IERS 2010 step-1 displacement (Eq. 7.5)
    // -----------------------------------------------------------------------

    /**
     * Sum the degree-2 body-tide displacement contributions from Sun and Moon.
     * Inputs are ECEF position vectors in metres; returns an ECEF displacement
     * vector in metres.
     */
    static double[] stepOneDisplacement(final double[] siteEcef,
                                        final double[] sunEcef,
                                        final double[] moonEcef) {
        final double[] dSun = oneBodyDisplacement(siteEcef, sunEcef, GM_SUN_OVER_GM_EARTH);
        final double[] dMoon = oneBodyDisplacement(siteEcef, moonEcef, GM_MOON_OVER_GM_EARTH);
        return new double[] {
                dSun[0] + dMoon[0],
                dSun[1] + dMoon[1],
                dSun[2] + dMoon[2]
        };
    }

    /**
     * Single-body degree-2 body-tide displacement at a site, per IERS 2010 Eq. 7.5:
     * <pre>
     *   delta_r = (GM_j/GM_E) * a^4 / R_j^3 *
     *             [ h2 * r_hat * (1.5 (R_hat . r_hat)^2 - 0.5)
     *               + 3 * l2 * (R_hat . r_hat) * (R_hat - (R_hat . r_hat) * r_hat) ]
     * </pre>
     *
     * @param siteEcef  ECEF position of the site (m).
     * @param bodyEcef  ECEF position of the perturbing body (m).
     * @param gmRatio   GM_body / GM_earth.
     */
    static double[] oneBodyDisplacement(final double[] siteEcef, final double[] bodyEcef,
                                        final double gmRatio) {
        final double rSite = norm(siteEcef);
        final double rBody = norm(bodyEcef);
        final double[] siteHat = scale(siteEcef, 1.0 / rSite);
        final double[] bodyHat = scale(bodyEcef, 1.0 / rBody);
        final double cosTheta = dot(siteHat, bodyHat);

        final double factor = gmRatio * Math.pow(A_EARTH, 4) / Math.pow(rBody, 3);
        final double aRadial = factor * H2 * (1.5 * cosTheta * cosTheta - 0.5);
        final double aTransverse = 3.0 * factor * L2 * cosTheta;

        final double[] radial = scale(siteHat, aRadial);
        // tangential component: aTransverse * (bodyHat - cosTheta * siteHat)
        final double[] tangential = {
                aTransverse * (bodyHat[0] - cosTheta * siteHat[0]),
                aTransverse * (bodyHat[1] - cosTheta * siteHat[1]),
                aTransverse * (bodyHat[2] - cosTheta * siteHat[2])
        };
        return new double[] {
                radial[0] + tangential[0],
                radial[1] + tangential[1],
                radial[2] + tangential[2]
        };
    }

    // -----------------------------------------------------------------------
    // Low-precision Sun and Moon ephemerides (Meeus chapters 25 and 47)
    // -----------------------------------------------------------------------

    /**
     * Geocentric Sun position in the GCRS (inertial) frame at the given UTC MJD.
     * Returns x, y, z in metres. Accurate to ~few arcminutes, which is more
     * than enough for SET (sub-mm error in the body tide).
     * Reference: Meeus, "Astronomical Algorithms" 2nd ed., chapter 25.
     */
    static double[] sunPositionGcrs(final double mjdUtc) {
        final double jd = mjdUtc + 2400000.5;
        // Julian centuries since J2000
        final double T = (jd - 2451545.0) / 36525.0;

        // Geometric mean longitude (deg)
        final double L0 = normalizeDeg(280.46646 + T * (36000.76983 + T * 0.0003032));
        // Mean anomaly (deg)
        final double M = normalizeDeg(357.52911 + T * (35999.05029 - T * 0.0001537));
        final double Mrad = Math.toRadians(M);

        // Equation of the centre
        final double C = (1.914602 - T * (0.004817 + T * 0.000014)) * Math.sin(Mrad)
                       + (0.019993 - T * 0.000101) * Math.sin(2.0 * Mrad)
                       + 0.000289 * Math.sin(3.0 * Mrad);

        // True longitude and true anomaly
        final double trueLon = L0 + C;
        final double v = M + C;

        // Distance to Sun in AU (Meeus 25.5)
        final double e = 0.016708634 - T * (0.000042037 + T * 0.0000001267);
        final double rAU = 1.000001018 * (1.0 - e * e) / (1.0 + e * Math.cos(Math.toRadians(v)));
        final double rM = rAU * AU;

        // Obliquity of the ecliptic (mean, IAU 1980)
        final double eps = Math.toRadians(meanObliquityDeg(T));

        // Ecliptic to equatorial; Sun ecliptic latitude is ~0
        final double lon = Math.toRadians(trueLon);
        final double x = rM * Math.cos(lon);
        final double y = rM * Math.sin(lon) * Math.cos(eps);
        final double z = rM * Math.sin(lon) * Math.sin(eps);
        return new double[] {x, y, z};
    }

    /**
     * Geocentric Moon position in the GCRS (inertial) frame at the given UTC MJD.
     * Returns x, y, z in metres. Truncated low-precision series; accurate to
     * ~few arcminutes (~few hundred km in Moon position), still sub-mm for SET.
     * Reference: Meeus, chapter 47 (abbreviated).
     */
    static double[] moonPositionGcrs(final double mjdUtc) {
        final double jd = mjdUtc + 2400000.5;
        final double T = (jd - 2451545.0) / 36525.0;

        // Mean longitude (deg)
        final double Lp = normalizeDeg(218.3164477 + T * (481267.88123421 - T * 0.0015786));
        // Mean elongation Moon-Sun
        final double D = normalizeDeg(297.8501921 + T * (445267.1114034 - T * 0.0018819));
        // Sun's mean anomaly
        final double M = normalizeDeg(357.5291092 + T * (35999.0502909 - T * 0.0001536));
        // Moon's mean anomaly
        final double Mp = normalizeDeg(134.9633964 + T * (477198.8675055 + T * 0.0087414));
        // Moon's argument of latitude
        final double F = normalizeDeg(93.2720950 + T * (483202.0175233 - T * 0.0036539));

        final double Drad = Math.toRadians(D);
        final double Mrad = Math.toRadians(M);
        final double Mprad = Math.toRadians(Mp);
        final double Frad = Math.toRadians(F);

        // Geocentric longitude (deg) — abbreviated series with the largest periodic terms
        double dLon = 0.0;
        dLon += 6.288774 * Math.sin(Mprad);
        dLon += 1.274027 * Math.sin(2.0 * Drad - Mprad);
        dLon += 0.658314 * Math.sin(2.0 * Drad);
        dLon += 0.213618 * Math.sin(2.0 * Mprad);
        dLon += -0.185116 * Math.sin(Mrad);
        dLon += -0.114332 * Math.sin(2.0 * Frad);

        // Latitude (deg) — abbreviated
        double lat = 0.0;
        lat += 5.128122 * Math.sin(Frad);
        lat += 0.280602 * Math.sin(Mprad + Frad);
        lat += 0.277693 * Math.sin(Mprad - Frad);
        lat += 0.173237 * Math.sin(2.0 * Drad - Frad);

        // Distance to Moon (km), Meeus 47.3 (abbreviated)
        double dKm = 385000.56;
        dKm += -20905.355 * Math.cos(Mprad);
        dKm += -3699.111 * Math.cos(2.0 * Drad - Mprad);
        dKm += -2955.968 * Math.cos(2.0 * Drad);
        dKm += -569.925 * Math.cos(2.0 * Mprad);

        final double lon = Math.toRadians(Lp + dLon);
        final double latRad = Math.toRadians(lat);
        final double rM = dKm * 1000.0;

        final double eps = Math.toRadians(meanObliquityDeg(T));

        final double xEcl = rM * Math.cos(latRad) * Math.cos(lon);
        final double yEcl = rM * Math.cos(latRad) * Math.sin(lon);
        final double zEcl = rM * Math.sin(latRad);

        // Ecliptic to equatorial rotation about X axis by -eps
        final double x = xEcl;
        final double y = yEcl * Math.cos(eps) - zEcl * Math.sin(eps);
        final double z = yEcl * Math.sin(eps) + zEcl * Math.cos(eps);
        return new double[] {x, y, z};
    }

    /** Mean obliquity of the ecliptic, IAU 1980 (degrees). */
    static double meanObliquityDeg(final double T) {
        // 23deg 26' 21.448" minus secular terms (IAU 1980 truncated)
        return 23.43929111 - T * (0.0130041667 + T * (1.6e-7 - T * 5.0277778e-7));
    }

    /**
     * Greenwich Mean Sidereal Time in radians, IAU 1982 series (truncated).
     * Input is UTC MJD; for SET we treat UTC ≈ UT1 (sub-second error → sub-mm
     * SET error).
     */
    static double greenwichMeanSiderealTime(final double mjdUtc) {
        final double Tu = (mjdUtc - 51544.5) / 36525.0; // centuries since J2000 UTC
        // GMST at 0h UT1 (seconds)
        double gmstSec = 67310.54841 + Tu * (8640184.812866 * 36525.0 / 36525.0 /* keep clarity */)
                + Tu * Tu * 0.093104 - Tu * Tu * Tu * 6.2e-6;
        // Add fractional day (UTC fractional part) as sidereal angle
        final double utFraction = mjdUtc - Math.floor(mjdUtc);
        gmstSec += utFraction * 86400.0 * 1.00273790935;
        // Wrap to [0, 86400)
        gmstSec = ((gmstSec % 86400.0) + 86400.0) % 86400.0;
        return gmstSec * (2.0 * Math.PI / 86400.0);
    }

    /** Rotate an inertial (GCRS) vector to ECEF by the GMST angle. */
    static double[] rotateInertialToEcef(final double[] vIcrs, final double gmst) {
        final double c = Math.cos(gmst);
        final double s = Math.sin(gmst);
        return new double[] {
                c * vIcrs[0] + s * vIcrs[1],
               -s * vIcrs[0] + c * vIcrs[1],
                vIcrs[2]
        };
    }

    // -----------------------------------------------------------------------
    // Geodetic / ECEF / ENU
    // -----------------------------------------------------------------------

    /** Geodetic (lat, lon, h) on WGS84 to ECEF (m). */
    static double[] geodeticToEcef(final double latDeg, final double lonDeg, final double altM) {
        final double lat = Math.toRadians(latDeg);
        final double lon = Math.toRadians(lonDeg);
        final double sinLat = Math.sin(lat);
        final double N = A_EARTH / Math.sqrt(1.0 - E2_EARTH * sinLat * sinLat);
        final double cosLat = Math.cos(lat);
        return new double[] {
                (N + altM) * cosLat * Math.cos(lon),
                (N + altM) * cosLat * Math.sin(lon),
                (N * (1.0 - E2_EARTH) + altM) * sinLat
        };
    }

    /** Rotate an ECEF displacement to the local ENU frame at (latDeg, lonDeg). */
    static double[] ecefToEnu(final double[] dEcef, final double latDeg, final double lonDeg) {
        final double lat = Math.toRadians(latDeg);
        final double lon = Math.toRadians(lonDeg);
        final double sLat = Math.sin(lat), cLat = Math.cos(lat);
        final double sLon = Math.sin(lon), cLon = Math.cos(lon);
        final double e = -sLon * dEcef[0] + cLon * dEcef[1];
        final double n = -sLat * cLon * dEcef[0] - sLat * sLon * dEcef[1] + cLat * dEcef[2];
        final double u =  cLat * cLon * dEcef[0] + cLat * sLon * dEcef[1] + sLat * dEcef[2];
        return new double[] {e, n, u};
    }

    // -----------------------------------------------------------------------
    // Small vector helpers
    // -----------------------------------------------------------------------

    private static double norm(final double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    private static double dot(final double[] a, final double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] scale(final double[] v, final double s) {
        return new double[] {v[0] * s, v[1] * s, v[2] * s};
    }

    private static double normalizeDeg(final double x) {
        double r = x % 360.0;
        if (r < 0) r += 360.0;
        return r;
    }
}
