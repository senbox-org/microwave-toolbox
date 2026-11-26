/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.io.nisar.subreaders;

import com.bc.ceres.annotation.STTM;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Time-axis units that NISAR writes differently in the geolocation grid and the image axes; when they
 * do not compare equal the grid cannot be placed on the image.
 */
public class TestNisarUnits {

    private static final String REFERENCE = NisarSubReader.normalisedUnits("seconds since 2008-10-12 00:00:00");

    @Test
    @STTM("SNAP-4384")
    public void trailingZeroFractionsAreTheSameTime() {
        assertEquals(REFERENCE, NisarSubReader.normalisedUnits("seconds since 2008-10-12 00:00:00.000000000"));
    }

    @Test
    @STTM("SNAP-4384")
    public void isoTSeparatorIsTheSameTime() {
        assertEquals(REFERENCE, NisarSubReader.normalisedUnits("seconds since 2008-10-12T00:00:00"));
        assertEquals(REFERENCE, NisarSubReader.normalisedUnits(" seconds  since 2008-10-12T00:00:00.000 "));
    }

    @Test
    @STTM("SNAP-4384")
    public void differentEpochsDiffer() {
        assertNotEquals(REFERENCE, NisarSubReader.normalisedUnits("seconds since 2008-10-13 00:00:00"));
        assertNotEquals(REFERENCE, NisarSubReader.normalisedUnits("seconds since 2008-10-12 00:00:00.5"));
    }
}
