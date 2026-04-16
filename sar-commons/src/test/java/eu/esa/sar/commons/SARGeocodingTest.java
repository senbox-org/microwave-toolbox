package eu.esa.sar.commons;

import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.engine_utilities.datamodel.OrbitStateVector;
import org.esa.snap.engine_utilities.datamodel.PosVector;
import org.esa.snap.engine_utilities.eo.Constants;
import org.junit.Test;

import static org.junit.Assert.*;

public class SARGeocodingTest {

    // --- computeGroundRange ---

    @Test
    public void testComputeGroundRange() {
        final int sourceImageWidth = 13638;
        final double groundRangeSpacing = 40.0;
        final double slantRange = 970530.4563706246;
        final double[] srgrCoeff = new double[]{631472.8789249088, 0.3166902223370849, 7.865149038750229E-7,
                -3.957704908474775E-13, -2.925522813361202E-19, 6.3335584020426205E-25, -2.9413809749818036E-32,
                -1.200473077501047E-36, 1.8014886333677E-42, -1.0241528233671593E-48, -2.2068205749647573E-55,
                6.021394462107571E-61, -2.373033835294464E-67};
        final double ground_range_origin = 0.0;
        final double groundRangeExp = 545516.6183853149;

        final double groundRange = SARGeocoding.computeGroundRange(sourceImageWidth, groundRangeSpacing, slantRange,
                srgrCoeff, ground_range_origin);

        assertEquals(groundRangeExp, groundRange, 1e-3);
    }

    @Test
    public void testComputeGroundRangeSlantRangeBelowLower() {
        // Slant range smaller than lower bound should return -1
        final double[] srgrCoeff = new double[]{800000.0, 1.0, 0.0};
        double result = SARGeocoding.computeGroundRange(1000, 10.0, 500000.0, srgrCoeff, 0.0);
        assertEquals(-1.0, result, 0.0);
    }

    @Test
    public void testComputeGroundRangeSlantRangeAboveUpper() {
        // Slant range larger than upper bound should return -1
        final double[] srgrCoeff = new double[]{100.0, 1.0, 0.0};
        double result = SARGeocoding.computeGroundRange(100, 1.0, 999999.0, srgrCoeff, 0.0);
        assertEquals(-1.0, result, 0.0);
    }

    // --- getPixelSpacingInDegree / getPixelSpacingInMeter ---

    @Test
    public void testGetPixelSpacingInDegree() {
        double spacingMeter = 10.0;
        double spacingDeg = SARGeocoding.getPixelSpacingInDegree(spacingMeter);

        // Should be approximately 10 / semiMajorAxis * RTOD
        double expected = spacingMeter / Constants.semiMajorAxis * Constants.RTOD;
        assertEquals(expected, spacingDeg, 1e-12);
        assertTrue(spacingDeg > 0);
        assertTrue(spacingDeg < 1.0); // 10 meters is much less than 1 degree
    }

    @Test
    public void testGetPixelSpacingInMeter() {
        double spacingDeg = 0.001;
        double spacingMeter = SARGeocoding.getPixelSpacingInMeter(spacingDeg);

        double expected = spacingDeg * Constants.semiMinorAxis * Constants.DTOR;
        assertEquals(expected, spacingMeter, 1e-6);
        assertTrue(spacingMeter > 0);
    }

    @Test
    public void testPixelSpacingRoundTrip() {
        // Converting meters -> degrees -> meters should return approximately the original value
        // Note: not exact because getPixelSpacingInDegree uses semiMajorAxis and
        // getPixelSpacingInMeter uses semiMinorAxis
        double originalMeters = 100.0;
        double deg = SARGeocoding.getPixelSpacingInDegree(originalMeters);
        double backToMeters = SARGeocoding.getPixelSpacingInMeter(deg);

        // Should be close but not exact due to semiMajor vs semiMinor
        assertEquals(originalMeters, backToMeters, 1.0);
    }

    // --- Constants ---

    @Test
    public void testNonValidZeroDopplerTime() {
        assertEquals(-99999.0, SARGeocoding.NonValidZeroDopplerTime, 0.0);
    }

    @Test
    public void testNonValidIncidenceAngle() {
        assertEquals(-99999.0, SARGeocoding.NonValidIncidenceAngle, 0.0);
    }

    @Test
    public void testIncidenceAngleStringConstants() {
        assertNotNull(SARGeocoding.USE_PROJECTED_INCIDENCE_ANGLE_FROM_DEM);
        assertNotNull(SARGeocoding.USE_LOCAL_INCIDENCE_ANGLE_FROM_DEM);
        assertNotNull(SARGeocoding.USE_INCIDENCE_ANGLE_FROM_ELLIPSOID);
    }

    // --- computeSlantRange ---

    @Test
    public void testComputeSlantRange() {
        OrbitStateVector[] vectors = new OrbitStateVector[3];
        for (int i = 0; i < 3; i++) {
            vectors[i] = new OrbitStateVector(new ProductData.UTC(i * 0.001),
                    7000000.0 + i * 1000.0, 0.0, 0.0,
                    1000.0, 0.0, 0.0);
        }

        OrbitStateVectors orbit = new OrbitStateVectors(vectors);
        PosVector earthPoint = new PosVector(6371000.0, 0.0, 0.0);
        PosVector sensorPos = new PosVector();

        double slantRange = SARGeocoding.computeSlantRange(0.001, orbit, earthPoint, sensorPos);

        assertTrue(slantRange > 0);
        // Distance from ~7001000 to 6371000 in x direction
        assertEquals(630000.0, slantRange, 1000.0);
    }

    // --- computeAtmosphericPathDelay ---

    @Test
    public void testComputeAtmosphericPathDelay() {
        // Typical Sentinel-1 geometry: satellite at ~700km altitude
        PosVector earthPoint = new PosVector(6371000.0, 0.0, 0.0);
        PosVector sensorPos = new PosVector(7071000.0, 0.0, 0.0); // ~700km above

        double delay = SARGeocoding.computeAtmosphericPathDelay(earthPoint, sensorPos, 45.0, 0.0);

        // Zenith dry delay is ~2.3m, slant delay should be larger (divided by cos(incidence))
        assertTrue(delay > 0);
        assertTrue(delay > 2.0); // at least the zenith delay
        assertTrue(delay < 20.0); // but not unreasonably large
    }

    @Test
    public void testComputeAtmosphericPathDelayAtHighAltitude() {
        PosVector earthPoint = new PosVector(6371000.0, 0.0, 0.0);
        PosVector sensorPos = new PosVector(7071000.0, 0.0, 0.0);

        double delaySeaLevel = SARGeocoding.computeAtmosphericPathDelay(earthPoint, sensorPos, 45.0, 0.0);
        double delayHighAlt = SARGeocoding.computeAtmosphericPathDelay(earthPoint, sensorPos, 45.0, 5000.0);

        // Higher altitude means less atmosphere, so less delay
        assertTrue(delayHighAlt < delaySeaLevel);
    }

    @Test
    public void testComputeAtmosphericPathDelayNegativeAltitude() {
        PosVector earthPoint = new PosVector(6371000.0, 0.0, 0.0);
        PosVector sensorPos = new PosVector(7071000.0, 0.0, 0.0);

        // Negative altitude should be clamped to 0
        double delay = SARGeocoding.computeAtmosphericPathDelay(earthPoint, sensorPos, 45.0, -100.0);
        double delayZero = SARGeocoding.computeAtmosphericPathDelay(earthPoint, sensorPos, 45.0, 0.0);
        assertEquals(delayZero, delay, 1e-10);
    }

    // --- getEarthPointZeroDopplerTime ---

    @Test
    public void testGetEarthPointZeroDopplerTimeNoSignChange() {
        // When all doppler frequencies have the same sign, should return NonValidZeroDopplerTime
        PosVector earthPoint = new PosVector(6371000.0, 0.0, 0.0);
        // Place sensor positions all on the same side so doppler freq never changes sign
        PosVector[] positions = new PosVector[3];
        PosVector[] velocities = new PosVector[3];
        for (int i = 0; i < 3; i++) {
            positions[i] = new PosVector(7000000.0 + i * 100.0, 0.0, 0.0);
            velocities[i] = new PosVector(0.0, 7500.0, 0.0); // velocity perpendicular to range
        }
        // Doppler = 2*(v . (P-S))/(|P-S|*lambda)
        // With velocity perpendicular to position difference, doppler ~ 0 but all same side
        // Use a setup where doppler has same sign
        positions[0] = new PosVector(7000000.0, 1000.0, 0.0);
        positions[1] = new PosVector(7000000.0, 2000.0, 0.0);
        positions[2] = new PosVector(7000000.0, 3000.0, 0.0);
        velocities[0] = new PosVector(-1.0, 0.0, 0.0);
        velocities[1] = new PosVector(-1.0, 0.0, 0.0);
        velocities[2] = new PosVector(-1.0, 0.0, 0.0);

        double result = SARGeocoding.getEarthPointZeroDopplerTime(
                0.0, 0.001, 0.05, earthPoint, positions, velocities);

        // All doppler freqs have the same sign, so this should be non-valid
        // Actually need to check — the doppler freq = 2*(v.(P-S))/(|P-S|*lambda)
        // v = (-1,0,0), P-S = (6371000-7000000, 0-y, 0) = (-629000, -y, 0)
        // v.(P-S) = (-1)*(-629000) = 629000 > 0 for all
        // So same sign → NonValidZeroDopplerTime
        assertEquals(SARGeocoding.NonValidZeroDopplerTime, result, 0.0);
    }

    // --- computeRangeIndex (slant range mode) ---

    @Test
    public void testComputeRangeIndexSlantRange() {
        // In slant range mode (srgrFlag=false), rangeIndex = (slantRange - nearEdge) / spacing
        double slantRange = 900000.0;
        double nearEdge = 800000.0;
        double spacing = 10.0;
        double firstUTC = 0.0;
        double lastUTC = 1.0;

        double rangeIndex = SARGeocoding.computeRangeIndex(
                false, 10000, firstUTC, lastUTC, spacing, 0.5,
                slantRange, nearEdge, null);

        assertEquals((slantRange - nearEdge) / spacing, rangeIndex, 1e-6);
    }

    @Test
    public void testComputeRangeIndexOutOfTimeRange() {
        // zeroDopplerTime outside [firstUTC, lastUTC] should return -1
        double rangeIndex = SARGeocoding.computeRangeIndex(
                false, 10000, 0.0, 1.0, 10.0, 2.0,
                900000.0, 800000.0, null);

        assertEquals(-1.0, rangeIndex, 0.0);
    }
}
