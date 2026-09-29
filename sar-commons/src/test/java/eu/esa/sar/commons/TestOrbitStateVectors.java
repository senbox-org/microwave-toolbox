/*
 * Copyright (C) 2016 by Array Systems Computing Inc. http://www.array.ca
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

import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.engine_utilities.datamodel.OrbitStateVector;
import org.esa.snap.engine_utilities.datamodel.PosVector;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class TestOrbitStateVectors {

    private OrbitStateVector[] vectors;

    private static OrbitStateVector createVector(double timeMjd, double xp, double yp, double zp,
                                                  double xv, double yv, double zv) {
        // ProductData.UTC(days) sets the MJD directly
        return new OrbitStateVector(new ProductData.UTC(timeMjd), xp, yp, zp, xv, yv, zv);
    }

    @Before
    public void setUp() {
        // Create a set of orbit state vectors with linear position and velocity
        // Time in MJD: 0.0, 0.001, 0.002, 0.003, 0.004
        vectors = new OrbitStateVector[5];
        for (int i = 0; i < 5; i++) {
            vectors[i] = createVector(
                    i * 0.001,
                    1000.0 * i, 2000.0 * i, 3000.0 * i,
                    1000.0, 2000.0, 3000.0
            );
        }
    }

    @Test
    public void testConstructorWithOrbitVectors() {
        OrbitStateVectors orbit = new OrbitStateVectors(vectors);
        assertNotNull(orbit.orbitStateVectors);
        assertEquals(5, orbit.orbitStateVectors.length);
    }

    @Test
    public void testConstructorWithImageHeight() {
        double firstLineUTC = 0.0;
        double lineTimeInterval = 0.0001;
        int imageHeight = 10;

        OrbitStateVectors orbit = new OrbitStateVectors(vectors, firstLineUTC, lineTimeInterval, imageHeight);
        assertNotNull(orbit.sensorPosition);
        assertNotNull(orbit.sensorVelocity);
        assertEquals(imageHeight, orbit.sensorPosition.length);
        assertEquals(imageHeight, orbit.sensorVelocity.length);
    }

    @Test
    public void testGetPositionVelocityInterpolation() {
        OrbitStateVectors orbit = new OrbitStateVectors(vectors);

        // Interpolate at middle time
        double midTime = 0.002;
        OrbitStateVectors.PositionVelocity pv = orbit.getPositionVelocity(midTime);

        assertNotNull(pv);
        assertNotNull(pv.position);
        assertNotNull(pv.velocity);

        // For linearly spaced data, interpolation at midpoint should give mid-values
        assertEquals(2000.0, pv.position.x, 1.0);
        assertEquals(4000.0, pv.position.y, 1.0);
        assertEquals(6000.0, pv.position.z, 1.0);
    }

    @Test
    public void testGetPositionVelocityAtKnownPoint() {
        OrbitStateVectors orbit = new OrbitStateVectors(vectors);

        // Interpolate at an exact vector time
        double time = vectors[2].time_mjd;
        OrbitStateVectors.PositionVelocity pv = orbit.getPositionVelocity(time);

        // Should match the exact vector values
        assertEquals(vectors[2].x_pos, pv.position.x, 0.1);
        assertEquals(vectors[2].y_pos, pv.position.y, 0.1);
        assertEquals(vectors[2].z_pos, pv.position.z, 0.1);
    }

    @Test
    public void testGetPositionVelocityCaching() {
        OrbitStateVectors orbit = new OrbitStateVectors(vectors);

        double time = 0.0015;
        OrbitStateVectors.PositionVelocity pv1 = orbit.getPositionVelocity(time);
        OrbitStateVectors.PositionVelocity pv2 = orbit.getPositionVelocity(time);

        // Should return the same cached object
        assertSame(pv1, pv2);
    }

    @Test
    public void testGetPositionVelocityConstantVelocity() {
        OrbitStateVectors orbit = new OrbitStateVectors(vectors);

        double time = 0.0015;
        OrbitStateVectors.PositionVelocity pv = orbit.getPositionVelocity(time);

        // Velocity is constant across all vectors
        assertEquals(1000.0, pv.velocity.x, 1.0);
        assertEquals(2000.0, pv.velocity.y, 1.0);
        assertEquals(3000.0, pv.velocity.z, 1.0);
    }

    @Test
    public void testGetPosition() {
        OrbitStateVectors orbit = new OrbitStateVectors(vectors);

        PosVector pos = new PosVector();
        orbit.getPosition(0.002, pos);

        assertEquals(2000.0, pos.x, 1.0);
        assertEquals(4000.0, pos.y, 1.0);
        assertEquals(6000.0, pos.z, 1.0);
    }

    @Test
    public void testRemoveRedundantVectors() {
        // Create vectors with duplicate timestamps
        // Times: 0.0, 0.001, 0.002, 0.002 (dupe), 0.004, 0.005
        OrbitStateVector[] dupes = new OrbitStateVector[6];
        double[] times = {0.0, 0.001, 0.002, 0.002, 0.004, 0.005};
        for (int i = 0; i < 6; i++) {
            dupes[i] = createVector(times[i], i * 100.0, i * 200.0, i * 300.0, 100.0, 200.0, 300.0);
        }

        // Use the 4-arg constructor which correctly handles de-duped arrays
        OrbitStateVectors orbit = new OrbitStateVectors(dupes, 0.0, 0.001, 3);
        // Duplicate should be removed: 6 input vectors → 5 unique times
        assertTrue(orbit.orbitStateVectors.length < dupes.length);
        assertEquals(5, orbit.orbitStateVectors.length);
    }

    @Test
    public void testSensorPositionArrayPopulated() {
        double firstLineUTC = 0.0;
        double lineTimeInterval = 0.0005;
        int imageHeight = 5;

        OrbitStateVectors orbit = new OrbitStateVectors(vectors, firstLineUTC, lineTimeInterval, imageHeight);

        for (int i = 0; i < imageHeight; i++) {
            assertNotNull(orbit.sensorPosition[i]);
            assertNotNull(orbit.sensorVelocity[i]);
        }

        // First line position should correspond to firstLineUTC interpolation
        PosVector firstPos = orbit.sensorPosition[0];
        assertNotNull(firstPos);
    }

    @Test
    public void testPositionVelocityDefaultValues() {
        OrbitStateVectors.PositionVelocity pv = new OrbitStateVectors.PositionVelocity();
        assertNotNull(pv.position);
        assertNotNull(pv.velocity);
        assertEquals(0.0, pv.position.x, 0.0);
        assertEquals(0.0, pv.velocity.x, 0.0);
    }
}
