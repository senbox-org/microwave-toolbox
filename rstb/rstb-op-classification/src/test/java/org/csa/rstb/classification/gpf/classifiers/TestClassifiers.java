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
package org.csa.rstb.classification.gpf.classifiers;

import eu.esa.sar.commons.polsar.PolBandUtils;
import org.esa.snap.core.datamodel.Band;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the concrete classifier implementations.
 *
 * Covers the inexpensive contracts each classifier must honour: default
 * constructor succeeds, target band name is stable, number-of-classes is
 * declared, and the stack-processing capability flag matches what the
 * algorithm actually supports. These checks catch accidental rename or
 * refactor regressions without needing a polarimetric source product.
 */
public class TestClassifiers {

    private static Map<Band, PolBandUtils.PolSourceBand> emptyBandMap() {
        return new HashMap<>();
    }

    // ---------- CloudePottier (T3) ----------

    @Test
    public void testCloudePottierTargetBandNameIsStable() {
        final CloudePottier c = new CloudePottier(
                PolBandUtils.MATRIX.T3, 10, 10, /*winSize*/ 5,
                emptyBandMap(), /*op*/ null);
        assertEquals("H_alpha_class", c.getTargetBandName());
    }

    @Test
    public void testCloudePottierDeclaresNineClasses() {
        final CloudePottier c = new CloudePottier(
                PolBandUtils.MATRIX.T3, 10, 10, 5, emptyBandMap(), null);
        assertEquals(9, c.getNumClasses());
    }

    @Test
    public void testCloudePottierCanProcessStacks() {
        final CloudePottier c = new CloudePottier(
                PolBandUtils.MATRIX.T3, 10, 10, 5, emptyBandMap(), null);
        assertTrue(c.canProcessStacks());
    }

    @Test
    public void testCloudePottierCreatesIndexCoding() {
        final CloudePottier c = new CloudePottier(
                PolBandUtils.MATRIX.T3, 10, 10, 5, emptyBandMap(), null);
        assertNotNull(c.createIndexCoding());
    }

    // ---------- CloudePottierC2 (C2 dual-pol) ----------

    @Test
    public void testCloudePottierC2SharesTargetBandNameWithT3Variant() {
        final CloudePottierC2 c = new CloudePottierC2(
                PolBandUtils.MATRIX.C2, 10, 10, 5, emptyBandMap(), null);
        assertEquals("H_alpha_class", c.getTargetBandName());
    }

    @Test
    public void testCloudePottierC2DeclaresNineClasses() {
        final CloudePottierC2 c = new CloudePottierC2(
                PolBandUtils.MATRIX.C2, 10, 10, 5, emptyBandMap(), null);
        assertEquals(9, c.getNumClasses());
    }

    // ---------- HAlphaWishart (T3) ----------

    @Test
    public void testHAlphaWishartTargetBandName() {
        final HAlphaWishart c = new HAlphaWishart(
                PolBandUtils.MATRIX.T3, 10, 10, 5, emptyBandMap(),
                /*maxIterations*/ 10, null);
        assertEquals("H_alpha_wishart_class", c.getTargetBandName());
    }

    @Test
    public void testHAlphaWishartDeclaresNineClasses() {
        final HAlphaWishart c = new HAlphaWishart(
                PolBandUtils.MATRIX.T3, 10, 10, 5, emptyBandMap(), 10, null);
        assertEquals(9, c.getNumClasses());
    }

    @Test
    public void testHAlphaWishartCanProcessStacks() {
        final HAlphaWishart c = new HAlphaWishart(
                PolBandUtils.MATRIX.T3, 10, 10, 5, emptyBandMap(), 10, null);
        assertTrue(c.canProcessStacks());
    }

    // ---------- HAlphaWishartC2 (C2 dual-pol) ----------

    @Test
    public void testHAlphaWishartC2TargetBandNameMatchesT3Variant() {
        final HAlphaWishartC2 c = new HAlphaWishartC2(
                PolBandUtils.MATRIX.C2, 10, 10, /*winX*/ 5, /*winY*/ 5,
                emptyBandMap(), 10, null);
        assertEquals("H_alpha_wishart_class", c.getTargetBandName());
    }

    @Test
    public void testHAlphaWishartC2DeclaresNineClasses() {
        final HAlphaWishartC2 c = new HAlphaWishartC2(
                PolBandUtils.MATRIX.C2, 10, 10, 5, 5, emptyBandMap(), 10, null);
        assertEquals(9, c.getNumClasses());
    }

    // ---------- FreemanDurdenWishart ----------

    @Test
    public void testFreemanDurdenWishartTargetBandName() {
        final FreemanDurdenWishart c = new FreemanDurdenWishart(
                PolBandUtils.MATRIX.T3, 10, 10, 5, emptyBandMap(),
                /*maxIterations*/ 10, /*numInitialClasses*/ 24, /*numClasses*/ 6,
                /*mixedCategoryThreshold*/ 0.5, null);
        assertEquals("Freeman_Durden_wishart_class", c.getTargetBandName());
    }

    @Test
    public void testFreemanDurdenWishartDeclaresUserSpecifiedClassCount() {
        final FreemanDurdenWishart c = new FreemanDurdenWishart(
                PolBandUtils.MATRIX.T3, 10, 10, 5, emptyBandMap(),
                10, 24, /*numClasses*/ 7, 0.5, null);
        assertEquals(7, c.getNumClasses());
    }

    @Test
    public void testFreemanDurdenWishartDoesNotProcessStacks() {
        final FreemanDurdenWishart c = new FreemanDurdenWishart(
                PolBandUtils.MATRIX.T3, 10, 10, 5, emptyBandMap(), 10, 24, 6, 0.5, null);
        assertFalse("FreemanDurdenWishart overrides canProcessStacks to false",
                c.canProcessStacks());
    }

    // ---------- GeneralWishart ----------

    @Test
    public void testGeneralWishartTargetBandName() {
        final GeneralWishart c = new GeneralWishart(
                PolBandUtils.MATRIX.T3, 10, 10, 5, emptyBandMap(),
                10, /*numInitialClasses*/ 24, /*numClasses*/ 6, 0.5,
                /*decomposition*/ "Freeman-Durden Decomposition", null);
        assertEquals("General_wishart_class", c.getTargetBandName());
    }

    @Test
    public void testGeneralWishartDeclaresUserSpecifiedClassCount() {
        final GeneralWishart c = new GeneralWishart(
                PolBandUtils.MATRIX.T3, 10, 10, 5, emptyBandMap(),
                10, 24, /*numClasses*/ 8, 0.5, "Freeman-Durden Decomposition", null);
        assertEquals(8, c.getNumClasses());
    }

    @Test
    public void testGeneralWishartDoesNotProcessStacks() {
        final GeneralWishart c = new GeneralWishart(
                PolBandUtils.MATRIX.T3, 10, 10, 5, emptyBandMap(),
                10, 24, 6, 0.5, "Freeman-Durden Decomposition", null);
        assertFalse(c.canProcessStacks());
    }
}
