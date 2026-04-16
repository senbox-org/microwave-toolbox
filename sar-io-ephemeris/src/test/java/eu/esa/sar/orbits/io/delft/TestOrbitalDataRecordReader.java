/*
 * Copyright (C) 2015 by Array Systems Computing Inc. http://www.array.ca
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
package eu.esa.sar.orbits.io.delft;

import org.esa.snap.core.util.ResourceInstaller;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Assert;
import org.junit.Test;

import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * OrbitalDataRecordReader Tester.
 *
 * @author lveci
 */
public class TestOrbitalDataRecordReader {

    private final static String envisatOrbitFilePath = "eu/esa/sar/orbits/envisat_ODR.051";
    private final static String ers1OrbitFilePath = "eu/esa/sar/orbits/ers1_ODR.079";
    private final static String ers2OrbitFilePath = "eu/esa/sar/orbits/ers2_ODR.015";
    private final Path basePath = ResourceInstaller.findModuleCodeBasePath(this.getClass());

    @Test
    public void testOpenFile() {

        final OrbitalDataRecordReader reader = new OrbitalDataRecordReader();

        Assert.assertTrue(reader.OpenOrbitFile(basePath.resolve(envisatOrbitFilePath)));
    }

    @Test
    public void testReadHeader() {

        final OrbitalDataRecordReader reader = new OrbitalDataRecordReader();

        if (reader.OpenOrbitFile(basePath.resolve(envisatOrbitFilePath))) {

            reader.parseHeader1();
            reader.parseHeader2();
        } else {
            Assert.assertTrue(false);
        }
    }

    @Test
    public void testReadERS1OrbitFiles() throws Exception {
        readOrbitFile("ERS1 ORD", basePath.resolve(ers1OrbitFilePath));
    }

    @Test
    public void testReadERS2OrbitFile() throws Exception {
        readOrbitFile("ERS2 ORD", basePath.resolve(ers2OrbitFilePath));
    }

    @Test
    public void testReadEnvisatOrbitFile() throws Exception {
        readOrbitFile("Envisat ORD", basePath.resolve(envisatOrbitFilePath));
    }

    private static void readOrbitFile(final String name, final Path path) throws Exception {
        final OrbitalDataRecordReader reader = new OrbitalDataRecordReader();
        final boolean res = reader.readOrbitFile(path);
        assert(res);

        final OrbitalDataRecordReader.OrbitDataRecord[] orbits = reader.getDataRecords();
        final StringBuilder str = new StringBuilder(name+ " Num Orbits " + orbits.length);
        for (int i = 0; i < 2; ++i) {
            str.append(" Orbit time " + orbits[i].time);
            str.append(" lat " + orbits[i].latitude);
            str.append(" lng " + orbits[i].longitude);
            str.append(" hgt " + orbits[i].heightOfCenterOfMass);
        }
        TestUtils.log.info(str.toString());
    }

    // --- New tests ---

    @Test
    public void testSingletonInstance() {
        OrbitalDataRecordReader instance1 = OrbitalDataRecordReader.getInstance();
        OrbitalDataRecordReader instance2 = OrbitalDataRecordReader.getInstance();
        assertNotNull(instance1);
        assertSame(instance1, instance2);
    }

    @Test
    public void testEnvisatHeaderValues() throws Exception {
        final OrbitalDataRecordReader reader = new OrbitalDataRecordReader();
        assertTrue(reader.readOrbitFile(basePath.resolve(envisatOrbitFilePath)));

        assertNotNull(reader.getProductSpecifier());
        assertNotNull(reader.getSatelliteName());
        assertTrue(reader.getNumRecords() > 0);
        assertTrue(reader.getArcStart() > 0);
    }

    @Test
    public void testERS1HeaderValues() throws Exception {
        final OrbitalDataRecordReader reader = new OrbitalDataRecordReader();
        assertTrue(reader.readOrbitFile(basePath.resolve(ers1OrbitFilePath)));

        String satellite = reader.getSatelliteName();
        assertNotNull(satellite);
        assertTrue(reader.getNumRecords() > 0);
        assertTrue(reader.getVersion() > 0);
    }

    @Test
    public void testERS2HeaderValues() throws Exception {
        final OrbitalDataRecordReader reader = new OrbitalDataRecordReader();
        assertTrue(reader.readOrbitFile(basePath.resolve(ers2OrbitFilePath)));

        assertNotNull(reader.getSatelliteName());
        assertTrue(reader.getNumRecords() > 0);
    }

    @Test
    public void testDataRecordsNotEmpty() throws Exception {
        final OrbitalDataRecordReader reader = new OrbitalDataRecordReader();
        assertTrue(reader.readOrbitFile(basePath.resolve(envisatOrbitFilePath)));

        OrbitalDataRecordReader.OrbitDataRecord[] records = reader.getDataRecords();
        assertNotNull(records);
        assertEquals(reader.getNumRecords(), records.length);

        // Verify first record has reasonable values
        assertTrue(records[0].time > 0);
    }

    @Test
    public void testOpenNonExistentFile() {
        final OrbitalDataRecordReader reader = new OrbitalDataRecordReader();
        assertFalse(reader.OpenOrbitFile(Path.of("/nonexistent/path/orbit.file")));
    }

    @Test
    public void testInvalidArcNumberConstant() {
        assertEquals(-1, OrbitalDataRecordReader.invalidArcNumber);
    }

    @Test
    public void testOrbitDataRecordFields() {
        OrbitalDataRecordReader.OrbitDataRecord record = new OrbitalDataRecordReader.OrbitDataRecord();
        assertEquals(0, record.time);
        assertEquals(0, record.latitude);
        assertEquals(0, record.longitude);
        assertEquals(0, record.heightOfCenterOfMass);
    }

    @Test
    public void testOrbitPositionRecordDefaults() {
        OrbitalDataRecordReader.OrbitPositionRecord record = new OrbitalDataRecordReader.OrbitPositionRecord();
        assertEquals(0.0, record.utcTime, 0.0);
        assertEquals(0.0, record.xPos, 0.0);
        assertEquals(0.0, record.yPos, 0.0);
        assertEquals(0.0, record.zPos, 0.0);
    }

    @Test
    public void testOrbitVectorDefaults() {
        OrbitalDataRecordReader.OrbitVector vector = new OrbitalDataRecordReader.OrbitVector();
        assertEquals(0.0, vector.utcTime, 0.0);
        assertEquals(0.0, vector.xPos, 0.0);
        assertEquals(0.0, vector.yPos, 0.0);
        assertEquals(0.0, vector.zPos, 0.0);
        assertEquals(0.0, vector.xVel, 0.0);
        assertEquals(0.0, vector.yVel, 0.0);
        assertEquals(0.0, vector.zVel, 0.0);
    }

    @Test
    public void testRecordCountConsistency() throws Exception {
        final OrbitalDataRecordReader reader = new OrbitalDataRecordReader();
        assertTrue(reader.readOrbitFile(basePath.resolve(envisatOrbitFilePath)));

        int numRecords = reader.getNumRecords();
        OrbitalDataRecordReader.OrbitDataRecord[] records = reader.getDataRecords();
        assertEquals(numRecords, records.length);
    }
}
