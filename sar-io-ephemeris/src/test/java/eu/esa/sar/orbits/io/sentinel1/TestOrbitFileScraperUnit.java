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

import org.junit.Test;

import static org.junit.Assert.*;

public class TestOrbitFileScraperUnit {

    // --- getNeighouringMonth ---

    @Test
    public void testGetNeighouringMonthEarlyInMonth() {
        // Day < 15 should go to previous month
        OrbitFileScraper.NewDate result = OrbitFileScraper.getNeighouringMonth(2024, 6, 10);
        assertEquals(2024, result.year);
        assertEquals(5, result.month);
    }

    @Test
    public void testGetNeighouringMonthLateInMonth() {
        // Day >= 15 should go to next month
        OrbitFileScraper.NewDate result = OrbitFileScraper.getNeighouringMonth(2024, 6, 20);
        assertEquals(2024, result.year);
        assertEquals(7, result.month);
    }

    @Test
    public void testGetNeighouringMonthJanuaryEarly() {
        // January early → December of previous year
        OrbitFileScraper.NewDate result = OrbitFileScraper.getNeighouringMonth(2024, 1, 5);
        assertEquals(2023, result.year);
        assertEquals(12, result.month);
    }

    @Test
    public void testGetNeighouringMonthDecemberLate() {
        // December late → January of next year
        OrbitFileScraper.NewDate result = OrbitFileScraper.getNeighouringMonth(2024, 12, 20);
        assertEquals(2025, result.year);
        assertEquals(1, result.month);
    }

    @Test
    public void testGetNeighouringMonthDay14() {
        // Day 14 (< 15) → previous month
        OrbitFileScraper.NewDate result = OrbitFileScraper.getNeighouringMonth(2024, 3, 14);
        assertEquals(2024, result.year);
        assertEquals(2, result.month);
    }

    @Test
    public void testGetNeighouringMonthDay15() {
        // Day 15 (>= 15) → next month
        OrbitFileScraper.NewDate result = OrbitFileScraper.getNeighouringMonth(2024, 3, 15);
        assertEquals(2024, result.year);
        assertEquals(4, result.month);
    }

    @Test
    public void testGetNeighouringMonthDay1() {
        OrbitFileScraper.NewDate result = OrbitFileScraper.getNeighouringMonth(2024, 7, 1);
        assertEquals(2024, result.year);
        assertEquals(6, result.month);
    }

    @Test
    public void testGetNeighouringMonthDay31() {
        OrbitFileScraper.NewDate result = OrbitFileScraper.getNeighouringMonth(2024, 7, 31);
        assertEquals(2024, result.year);
        assertEquals(8, result.month);
    }

    // --- NewDate ---

    @Test
    public void testNewDateConstructor() {
        OrbitFileScraper.NewDate date = new OrbitFileScraper.NewDate(2024, 6);
        assertEquals(2024, date.year);
        assertEquals(6, date.month);
    }

    // --- RemoteOrbitFile ---

    @Test
    public void testRemoteOrbitFileConstructor() {
        OrbitFileScraper.RemoteOrbitFile rof = new OrbitFileScraper.RemoteOrbitFile(
                "http://example.com/orbits/", "S1A_OPER_AUX_POEORB.zip");
        assertEquals("http://example.com/orbits/", rof.remotePath);
        assertEquals("S1A_OPER_AUX_POEORB.zip", rof.fileName);
    }

    @Test
    public void testRemoteOrbitFileFields() {
        OrbitFileScraper.RemoteOrbitFile rof = new OrbitFileScraper.RemoteOrbitFile("path", "name.eof");
        rof.remotePath = "new_path";
        rof.fileName = "new_name.eof";
        assertEquals("new_path", rof.remotePath);
        assertEquals("new_name", rof.fileName.replace(".eof", ""));
    }

    // --- SentinelPODOrbitFile constants ---

    @Test
    public void testOrbitTypeConstants() {
        assertEquals("Sentinel Restituted", SentinelPODOrbitFile.RESTITUTED);
        assertEquals("Sentinel Precise", SentinelPODOrbitFile.PRECISE);
    }

    @Test
    public void testOrbitTypeConstantsDistinct() {
        assertNotEquals(SentinelPODOrbitFile.RESTITUTED, SentinelPODOrbitFile.PRECISE);
    }
}
