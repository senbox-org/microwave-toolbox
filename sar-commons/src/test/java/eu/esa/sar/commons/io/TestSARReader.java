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
package eu.esa.sar.commons.io;

import org.junit.Test;

import static org.junit.Assert.*;

public class TestSARReader {

    // --- findPolarizationInBandName ---

    @Test
    public void testFindPolarizationHH() {
        assertEquals("HH", SARReader.findPolarizationInBandName("Intensity_HH"));
        assertEquals("HH", SARReader.findPolarizationInBandName("i_HH_mst"));
        assertEquals("HH", SARReader.findPolarizationInBandName("Amplitude_H/H"));
        assertEquals("HH", SARReader.findPolarizationInBandName("Band_H-H_data"));
    }

    @Test
    public void testFindPolarizationVV() {
        assertEquals("VV", SARReader.findPolarizationInBandName("Intensity_VV"));
        assertEquals("VV", SARReader.findPolarizationInBandName("q_VV"));
        assertEquals("VV", SARReader.findPolarizationInBandName("Amplitude_V/V"));
        assertEquals("VV", SARReader.findPolarizationInBandName("Band_V-V_data"));
    }

    @Test
    public void testFindPolarizationHV() {
        assertEquals("HV", SARReader.findPolarizationInBandName("Intensity_HV"));
        assertEquals("HV", SARReader.findPolarizationInBandName("Amplitude_H/V"));
        assertEquals("HV", SARReader.findPolarizationInBandName("Band_H-V"));
    }

    @Test
    public void testFindPolarizationVH() {
        assertEquals("VH", SARReader.findPolarizationInBandName("Intensity_VH"));
        assertEquals("VH", SARReader.findPolarizationInBandName("Amplitude_V/H"));
        assertEquals("VH", SARReader.findPolarizationInBandName("Band_V-H"));
    }

    @Test
    public void testFindPolarizationNone() {
        assertNull(SARReader.findPolarizationInBandName("Intensity"));
        assertNull(SARReader.findPolarizationInBandName("elevation"));
        assertNull(SARReader.findPolarizationInBandName(""));
    }

    @Test
    public void testFindPolarizationCaseInsensitive() {
        assertEquals("HH", SARReader.findPolarizationInBandName("intensity_hh"));
        assertEquals("VV", SARReader.findPolarizationInBandName("amplitude_vv"));
    }

    // --- checkIfCrossMeridian ---

    @Test
    public void testCheckIfCrossMeridianTrue() {
        // Longitudes spanning across the antimeridian (e.g. 170 to -170)
        float[] lons = {170.0f, 175.0f, -175.0f, -170.0f};
        assertTrue(SARReader.checkIfCrossMeridian(lons));
    }

    @Test
    public void testCheckIfCrossMeridianFalse() {
        // Normal range not crossing meridian
        float[] lons = {10.0f, 11.0f, 12.0f, 13.0f};
        assertFalse(SARReader.checkIfCrossMeridian(lons));
    }

    @Test
    public void testCheckIfCrossMeridianNarrowRange() {
        float[] lons = {-1.0f, 0.0f, 1.0f};
        assertFalse(SARReader.checkIfCrossMeridian(lons));
    }

    @Test
    public void testCheckIfCrossMeridianWideButNotCrossing() {
        // 260 degrees apart but still on same side - doesn't exceed 270 threshold
        float[] lons = {-130.0f, 130.0f};
        assertFalse(SARReader.checkIfCrossMeridian(lons));
    }

    @Test
    public void testCheckIfCrossMeridianExactThreshold() {
        // Exactly 270 degrees apart — should NOT cross (threshold is > 270)
        float[] lons = {-135.0f, 135.0f};
        assertFalse(SARReader.checkIfCrossMeridian(lons));
    }

    @Test
    public void testCheckIfCrossMeridianAboveThreshold() {
        // More than 270 degrees apart
        float[] lons = {-170.0f, 170.0f};
        assertTrue(SARReader.checkIfCrossMeridian(lons));
    }
}
