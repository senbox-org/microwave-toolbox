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
package eu.esa.sar.orbits.io.prare;

import org.junit.Test;

import static org.junit.Assert.*;

public class TestPrareOrbitReader {

    // --- Singleton ---

    @Test
    public void testGetInstance() {
        PrareOrbitReader reader = PrareOrbitReader.getInstance();
        assertNotNull(reader);
    }

    @Test
    public void testGetInstanceReturnsSameObject() {
        PrareOrbitReader instance1 = PrareOrbitReader.getInstance();
        PrareOrbitReader instance2 = PrareOrbitReader.getInstance();
        assertSame(instance1, instance2);
    }

    // --- Record classes ---

    @Test
    public void testDataSetIdentificationRecordDefaults() {
        PrareOrbitReader.DataSetIdentificationRecord record = new PrareOrbitReader.DataSetIdentificationRecord();
        assertNull(record.recKey);
        assertNull(record.prodID);
        assertNull(record.datTyp);
    }

    @Test
    public void testDataSetIdentificationRecordFields() {
        PrareOrbitReader.DataSetIdentificationRecord record = new PrareOrbitReader.DataSetIdentificationRecord();
        record.recKey = "STTERR";
        record.prodID = "ERS-2_PRC_001";
        record.datTyp = "PRCRES";
        assertEquals("STTERR", record.recKey);
        assertEquals("ERS-2_PRC_001", record.prodID);
        assertEquals("PRCRES", record.datTyp);
    }

    @Test
    public void testDataHeaderRecordDefaults() {
        PrareOrbitReader.DataHeaderRecord record = new PrareOrbitReader.DataHeaderRecord();
        assertNull(record.recKey);
        assertEquals(0.0f, record.start, 0.0f);
        assertEquals(0.0f, record.end, 0.0f);
        assertEquals(0, record.modID);
        assertEquals(0, record.relID);
        assertEquals(0, record.rmsFit);
        assertEquals(0, record.sigPos);
        assertEquals(0, record.sigVel);
        assertEquals(0, record.qualit);
        assertEquals(0.0f, record.tdtUtc, 0.0f);
    }

    @Test
    public void testDataHeaderRecordFields() {
        PrareOrbitReader.DataHeaderRecord record = new PrareOrbitReader.DataHeaderRecord();
        record.recKey = "STTHDR";
        record.start = 100.5f;
        record.end = 155.3f;
        record.obsTyp = "DOPPL";
        record.obsLev = "PRELIM";
        record.modID = 2;
        record.relID = 1;
        record.rmsFit = 50;
        record.sigPos = 30;
        record.sigVel = 10;
        record.qualit = 1;
        record.tdtUtc = 64.184f;
        record.cmmnt = "Test comment";

        assertEquals("STTHDR", record.recKey);
        assertEquals(100.5f, record.start, 0.001f);
        assertEquals(155.3f, record.end, 0.001f);
        assertEquals("DOPPL", record.obsTyp);
        assertEquals(2, record.modID);
        assertEquals(50, record.rmsFit);
    }

    @Test
    public void testTrajectoryRecordDefaults() {
        PrareOrbitReader.TrajectoryRecord record = new PrareOrbitReader.TrajectoryRecord();
        assertNull(record.recKey);
        assertEquals(0, record.satID);
        assertEquals(0.0f, record.tTagD, 0.0f);
        assertEquals(0L, record.tTagMs);
        assertEquals(0L, record.xSat);
        assertEquals(0L, record.ySat);
        assertEquals(0L, record.zSat);
    }

    @Test
    public void testTrajectoryRecordFields() {
        PrareOrbitReader.TrajectoryRecord record = new PrareOrbitReader.TrajectoryRecord();
        record.recKey = "STTREC";
        record.satID = 7;
        record.orbTyp = "P";
        record.tTagD = 500.0f;
        record.tTagMs = 43200000000L;
        record.xSat = 4000000000L;
        record.ySat = 2000000000L;
        record.zSat = 5000000000L;
        record.xDSat = 1000000L;
        record.yDSat = 2000000L;
        record.zDSat = 3000000L;
        record.roll = 0.5f;
        record.pitch = -0.3f;
        record.yaw = 1.2f;
        record.ascArc = 1;
        record.check = 999;
        record.quali = 0;
        record.radCor = 50;

        assertEquals("STTREC", record.recKey);
        assertEquals(7, record.satID);
        assertEquals(4000000000L, record.xSat);
        assertEquals(0.5f, record.roll, 0.001f);
        assertEquals(1, record.ascArc);
    }

    @Test
    public void testQualityParameterRecordDefaults() {
        PrareOrbitReader.QualityParameterRecord record = new PrareOrbitReader.QualityParameterRecord();
        assertNull(record.recKey);
        assertNull(record.qPName);
        assertNull(record.qPValue);
        assertNull(record.qPUnit);
        assertNull(record.qPRefVal);
    }

    @Test
    public void testQualityParameterRecordFields() {
        PrareOrbitReader.QualityParameterRecord record = new PrareOrbitReader.QualityParameterRecord();
        record.recKey = "STTERR";
        record.qPName = "RMS fit residuals";
        record.qPValue = "0.0450";
        record.qPUnit = "meters";
        record.qPRefVal = "0.0500";

        assertEquals("STTERR", record.recKey);
        assertEquals("RMS fit residuals", record.qPName);
        assertEquals("0.0450", record.qPValue);
        assertEquals("meters", record.qPUnit);
    }

    // --- Reader state before any file is read ---

    @Test
    public void testReaderInitialState() {
        PrareOrbitReader reader = PrareOrbitReader.getInstance();
        assertNull(reader.getDataSetIdentificationRecord());
        assertNull(reader.getDataHeaderRecord());
    }
}
