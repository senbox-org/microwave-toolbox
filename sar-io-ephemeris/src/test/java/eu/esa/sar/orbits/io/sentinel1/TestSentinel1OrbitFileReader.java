/*
 * Copyright (C) 2021 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.orbits.io.sentinel1;

import org.esa.snap.core.datamodel.ProductData;
import org.junit.Test;

import java.text.ParseException;

import static org.junit.Assert.*;

public class TestSentinel1OrbitFileReader {

    private static final String POEORB_FILENAME =
            "S1A_OPER_AUX_POEORB_OPOD_20140526T151322_V20140509T225944_20140511T005944.EOF";

    private static final String RESORB_FILENAME =
            "S1A_OPER_AUX_RESORB_OPOD_20140611T152302_V20140525T151921_20140525T183641.EOF";

    // --- getValidityStartFromFilename ---

    @Test
    public void testGetValidityStartFromFilenamePOEORB() {
        String vStart = Sentinel1OrbitFileReader.getValidityStartFromFilename(POEORB_FILENAME);
        assertNotNull(vStart);
        assertEquals("UTC=2014-05-09T22:59:44", vStart);
    }

    @Test
    public void testGetValidityStartFromFilenameRESORB() {
        String vStart = Sentinel1OrbitFileReader.getValidityStartFromFilename(RESORB_FILENAME);
        assertNotNull(vStart);
        assertEquals("UTC=2014-05-25T15:19:21", vStart);
    }

    // --- getValidityStopFromFilename ---

    @Test
    public void testGetValidityStopFromFilenamePOEORB() {
        String vStop = Sentinel1OrbitFileReader.getValidityStopFromFilename(POEORB_FILENAME);
        assertNotNull(vStop);
        assertEquals("UTC=2014-05-11T00:59:44", vStop);
    }

    @Test
    public void testGetValidityStopFromFilenameRESORB() {
        String vStop = Sentinel1OrbitFileReader.getValidityStopFromFilename(RESORB_FILENAME);
        assertNotNull(vStop);
        assertEquals("UTC=2014-05-25T18:36:41", vStop);
    }

    // --- getValidityStartFromFilenameUTC ---

    @Test
    public void testGetValidityStartFromFilenameUTC() throws ParseException {
        ProductData.UTC utc = Sentinel1OrbitFileReader.getValidityStartFromFilenameUTC(POEORB_FILENAME);
        assertNotNull(utc);
        assertTrue(utc.getMJD() > 0);
    }

    @Test
    public void testGetValidityStopFromFilenameUTC() throws ParseException {
        ProductData.UTC utc = Sentinel1OrbitFileReader.getValidityStopFromFilenameUTC(POEORB_FILENAME);
        assertNotNull(utc);
        assertTrue(utc.getMJD() > 0);
    }

    @Test
    public void testValidityStartBeforeStop() throws ParseException {
        ProductData.UTC start = Sentinel1OrbitFileReader.getValidityStartFromFilenameUTC(POEORB_FILENAME);
        ProductData.UTC stop = Sentinel1OrbitFileReader.getValidityStopFromFilenameUTC(POEORB_FILENAME);
        assertNotNull(start);
        assertNotNull(stop);
        assertTrue(stop.getMJD() > start.getMJD());
    }

    // --- toUTC ---

    @Test
    public void testToUTC() throws ParseException {
        ProductData.UTC utc = Sentinel1OrbitFileReader.toUTC("UTC=2015-08-27T22:59:43.000000");
        assertNotNull(utc);
        assertTrue(utc.getMJD() > 0);
    }

    @Test
    public void testToUTCConsistentWithFilenameParser() throws ParseException {
        // Verify the toUTC method produces reasonable values
        ProductData.UTC utc1 = Sentinel1OrbitFileReader.toUTC("UTC=2014-05-09T22:59:44");
        ProductData.UTC utc2 = Sentinel1OrbitFileReader.getValidityStartFromFilenameUTC(POEORB_FILENAME);
        assertNotNull(utc1);
        assertNotNull(utc2);
        // They should represent the same time
        assertEquals(utc1.getMJD(), utc2.getMJD(), 0.001);
    }

    // --- isWithinRange ---

    @Test
    public void testIsWithinRangeTrue() throws ParseException {
        // Create a time that's within the validity period
        ProductData.UTC withinTime = Sentinel1OrbitFileReader.toUTC("UTC=2014-05-10T12:00:00");
        assertTrue(Sentinel1OrbitFileReader.isWithinRange(POEORB_FILENAME, withinTime));
    }

    @Test
    public void testIsWithinRangeFalseBeforeStart() throws ParseException {
        ProductData.UTC beforeTime = Sentinel1OrbitFileReader.toUTC("UTC=2014-05-08T00:00:00");
        assertFalse(Sentinel1OrbitFileReader.isWithinRange(POEORB_FILENAME, beforeTime));
    }

    @Test
    public void testIsWithinRangeFalseAfterEnd() throws ParseException {
        ProductData.UTC afterTime = Sentinel1OrbitFileReader.toUTC("UTC=2014-05-12T00:00:00");
        assertFalse(Sentinel1OrbitFileReader.isWithinRange(POEORB_FILENAME, afterTime));
    }

    @Test
    public void testIsWithinRangeExactStart() throws ParseException {
        // Exact start time should be within range (>= start)
        ProductData.UTC startTime = Sentinel1OrbitFileReader.getValidityStartFromFilenameUTC(POEORB_FILENAME);
        assertTrue(Sentinel1OrbitFileReader.isWithinRange(POEORB_FILENAME, startTime));
    }

    @Test
    public void testIsWithinRangeExactEnd() throws ParseException {
        // Exact end time should NOT be within range (< end, not <=)
        ProductData.UTC endTime = Sentinel1OrbitFileReader.getValidityStopFromFilenameUTC(POEORB_FILENAME);
        assertFalse(Sentinel1OrbitFileReader.isWithinRange(POEORB_FILENAME, endTime));
    }

    @Test
    public void testIsWithinRangeInvalidFilename() throws ParseException {
        ProductData.UTC time = Sentinel1OrbitFileReader.toUTC("UTC=2014-05-10T12:00:00");
        assertFalse(Sentinel1OrbitFileReader.isWithinRange("invalid_filename.eof", time));
    }

    // --- dateFormat constants ---

    @Test
    public void testDateFormatsNotNull() {
        assertNotNull(Sentinel1OrbitFileReader.dateFormat);
        assertNotNull(Sentinel1OrbitFileReader.orbitDateFormat);
    }

    // --- Constructor ---

    @Test
    public void testConstructorWithNonExistentFile() {
        // Constructor should not throw — reading happens later
        Sentinel1OrbitFileReader reader = new Sentinel1OrbitFileReader(new java.io.File("nonexistent.eof"));
        assertNotNull(reader);
        assertNotNull(reader.getOrbitStateVectors());
        assertEquals(0, reader.getOrbitStateVectors().size());
    }

    // --- Header methods with no file read ---

    @Test
    public void testHeaderMethodsBeforeRead() {
        Sentinel1OrbitFileReader reader = new Sentinel1OrbitFileReader(new java.io.File("test.eof"));
        assertNull(reader.getMissionFromHeader());
        assertNull(reader.getFileTypeFromHeader());
        assertNull(reader.getValidityStartFromHeader());
        assertNull(reader.getValidityStopFromHeader());
    }
}
