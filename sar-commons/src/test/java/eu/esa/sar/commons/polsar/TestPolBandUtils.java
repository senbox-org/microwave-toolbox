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
package eu.esa.sar.commons.polsar;

import org.junit.Test;

import static org.junit.Assert.*;

public class TestPolBandUtils {

    // --- isDualPol ---

    @Test
    public void testIsDualPolTrue() {
        assertTrue(PolBandUtils.isDualPol(PolBandUtils.MATRIX.DUAL_HH_HV));
        assertTrue(PolBandUtils.isDualPol(PolBandUtils.MATRIX.DUAL_VH_VV));
        assertTrue(PolBandUtils.isDualPol(PolBandUtils.MATRIX.DUAL_HH_VV));
        assertTrue(PolBandUtils.isDualPol(PolBandUtils.MATRIX.C2));
        assertTrue(PolBandUtils.isDualPol(PolBandUtils.MATRIX.LCHCP));
        assertTrue(PolBandUtils.isDualPol(PolBandUtils.MATRIX.RCHCP));
    }

    @Test
    public void testIsDualPolFalse() {
        assertFalse(PolBandUtils.isDualPol(PolBandUtils.MATRIX.C3));
        assertFalse(PolBandUtils.isDualPol(PolBandUtils.MATRIX.T3));
        assertFalse(PolBandUtils.isDualPol(PolBandUtils.MATRIX.C4));
        assertFalse(PolBandUtils.isDualPol(PolBandUtils.MATRIX.T4));
        assertFalse(PolBandUtils.isDualPol(PolBandUtils.MATRIX.FULL));
        assertFalse(PolBandUtils.isDualPol(PolBandUtils.MATRIX.UNKNOWN));
    }

    // --- isQuadPol ---

    @Test
    public void testIsQuadPolTrue() {
        assertTrue(PolBandUtils.isQuadPol(PolBandUtils.MATRIX.C3));
        assertTrue(PolBandUtils.isQuadPol(PolBandUtils.MATRIX.T3));
        assertTrue(PolBandUtils.isQuadPol(PolBandUtils.MATRIX.C4));
        assertTrue(PolBandUtils.isQuadPol(PolBandUtils.MATRIX.T4));
    }

    @Test
    public void testIsQuadPolFalse() {
        assertFalse(PolBandUtils.isQuadPol(PolBandUtils.MATRIX.DUAL_HH_HV));
        assertFalse(PolBandUtils.isQuadPol(PolBandUtils.MATRIX.FULL));
        assertFalse(PolBandUtils.isQuadPol(PolBandUtils.MATRIX.UNKNOWN));
    }

    // --- isFullPol ---

    @Test
    public void testIsFullPolTrue() {
        assertTrue(PolBandUtils.isFullPol(PolBandUtils.MATRIX.FULL));
    }

    @Test
    public void testIsFullPolFalse() {
        assertFalse(PolBandUtils.isFullPol(PolBandUtils.MATRIX.DUAL_HH_HV));
        assertFalse(PolBandUtils.isFullPol(PolBandUtils.MATRIX.C3));
        assertFalse(PolBandUtils.isFullPol(PolBandUtils.MATRIX.T4));
        assertFalse(PolBandUtils.isFullPol(PolBandUtils.MATRIX.UNKNOWN));
    }

    // --- band name getters ---

    @Test
    public void testGetComplexBandNames() {
        String[] names = PolBandUtils.getComplexBandNames();
        assertEquals(2, names.length);
        assertEquals("i_", names[0]);
        assertEquals("q_", names[1]);
    }

    @Test
    public void testGetC2BandNames() {
        String[] names = PolBandUtils.getC2BandNames();
        assertEquals(4, names.length);
        assertEquals("C11", names[0]);
        assertEquals("C22", names[3]);
    }

    @Test
    public void testGetC3BandNames() {
        String[] names = PolBandUtils.getC3BandNames();
        assertEquals(9, names.length);
        assertEquals("C11", names[0]);
        assertEquals("C33", names[8]);
    }

    @Test
    public void testGetC4BandNames() {
        String[] names = PolBandUtils.getC4BandNames();
        assertEquals(16, names.length);
        assertEquals("C11", names[0]);
        assertEquals("C44", names[15]);
    }

    @Test
    public void testGetT3BandNames() {
        String[] names = PolBandUtils.getT3BandNames();
        assertEquals(9, names.length);
        assertEquals("T11", names[0]);
        assertEquals("T33", names[8]);
    }

    @Test
    public void testGetT4BandNames() {
        String[] names = PolBandUtils.getT4BandNames();
        assertEquals(16, names.length);
        assertEquals("T11", names[0]);
        assertEquals("T44", names[15]);
    }

    @Test
    public void testGetG4BandNames() {
        String[] names = PolBandUtils.getG4BandNames();
        assertEquals(4, names.length);
        assertEquals("g0", names[0]);
        assertEquals("g3", names[3]);
    }

    @Test
    public void testGetLCHModeS2BandNames() {
        String[] names = PolBandUtils.getLCHModeS2BandNames();
        assertEquals(4, names.length);
        assertEquals("i_LCH", names[0]);
        assertEquals("q_LCV", names[3]);
    }

    @Test
    public void testGetRCHModeS2BandNames() {
        String[] names = PolBandUtils.getRCHModeS2BandNames();
        assertEquals(4, names.length);
        assertEquals("i_RCH", names[0]);
        assertEquals("q_RCV", names[3]);
    }

    // --- isBandForMatrixElement ---

    @Test
    public void testIsBandForMatrixElementMatch() {
        assertTrue(PolBandUtils.isBandForMatrixElement("CC11", "C11"));
        assertTrue(PolBandUtils.isBandForMatrixElement("TT33_real", "T33"));
    }

    @Test
    public void testIsBandForMatrixElementNoMatch() {
        assertFalse(PolBandUtils.isBandForMatrixElement("CC22", "C11"));
    }

    @Test
    public void testIsBandForMatrixElementTooShort() {
        assertFalse(PolBandUtils.isBandForMatrixElement("C", "C11"));
    }

    // --- MATRIX enum values ---

    @Test
    public void testMatrixEnumValues() {
        PolBandUtils.MATRIX[] values = PolBandUtils.MATRIX.values();
        assertEquals(12, values.length);
    }

    // --- PolSourceBand ---

    @Test
    public void testPolSourceBandConstructor() {
        PolBandUtils.PolSourceBand psb = new PolBandUtils.PolSourceBand("product1", new org.esa.snap.core.datamodel.Band[0], "_mst");
        assertEquals("product1", psb.productName);
        assertEquals("_mst", psb.suffix);
        assertEquals(0, psb.srcBands.length);
        assertFalse(psb.spanMinMaxSet);
    }

    @Test
    public void testPolSourceBandSpanDefaults() {
        PolBandUtils.PolSourceBand psb = new PolBandUtils.PolSourceBand("p", new org.esa.snap.core.datamodel.Band[0], "");
        assertTrue(psb.spanMin > 1e+29);
        assertTrue(psb.spanMax < -1e+29);
    }
}
