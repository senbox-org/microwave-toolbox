/*
 * Copyright (C) 2024 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.utilities.gpf.ui;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;

public class BandSelectOpUITest {

    private BandSelectOpUI bandSelectOpUI;

    @Before
    public void setUp() {
        bandSelectOpUI = new BandSelectOpUI();
    }

    @Test
    public void getPolarizationsReturnsCorrectPolarizations() {
        String[] bandNames = {"IW1_VH", "IW2_VV", "IW3_VH"};
        String[] expectedPolarizations = {"VH", "VV"};

        String[] actualPolarizations = bandSelectOpUI.getPolarizations(bandNames);

        assertArrayEquals(expectedPolarizations, actualPolarizations);
    }

    @Test
    public void getPolarizationsReturnsEmptyArrayWhenNoPolarizations() {
        String[] bandNames = {"IW1", "IW2", "IW3"};
        String[] expectedPolarizations = {};

        String[] actualPolarizations = bandSelectOpUI.getPolarizations(bandNames);

        assertArrayEquals(expectedPolarizations, actualPolarizations);
    }
}