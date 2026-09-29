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
import org.esa.snap.core.datamodel.IndexCoding;
import org.junit.Test;

import java.awt.Rectangle;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link PolClassifierBase}.
 *
 * Exercises the pure-logic methods that are part of the classifier base class:
 *   - {@link PolClassifierBase#getSourceRectangle} (static overload)
 *   - {@link PolClassifierBase#createIndexCoding}
 *   - {@link PolClassifierBase.ClusterInfo#setClusterCenter}
 *   - NODATACLASS constant
 *
 * These require no source product and no operator wiring.
 */
public class TestPolClassifierBase {

    // A 9-class probe: matches HAlphaWishart / CloudePottier defaults and lets
    // us exercise the full switch in createIndexCoding().
    private static final PolClassifierBase NINE_CLASS_PROBE = new PolClassifierBase(
            PolBandUtils.MATRIX.T3, 10, 10, 3, 3,
            new HashMap<Band, PolBandUtils.PolSourceBand>(), null) {
        @Override
        public int getNumClasses() { return 9; }
    };

    private static final PolClassifierBase FOUR_CLASS_PROBE = new PolClassifierBase(
            PolBandUtils.MATRIX.C3, 10, 10, 3, 3,
            new HashMap<Band, PolBandUtils.PolSourceBand>(), null) {
        @Override
        public int getNumClasses() { return 4; }
    };

    // ---------- Constants ----------

    @Test
    public void testNoDataClassConstantIsZero() {
        assertEquals(0, PolClassifierBase.NODATACLASS);
    }

    @Test
    public void testDefaultCanProcessStacksIsTrue() {
        assertTrue(NINE_CLASS_PROBE.canProcessStacks());
    }

    // ---------- Static getSourceRectangle ----------

    @Test
    public void testStaticGetSourceRectangleInterior() {
        final Rectangle r = PolClassifierBase.getSourceRectangle(
                /*tx0,ty0*/ 10, 10, /*tw,th*/ 8, 8,
                /*win*/ 5, 5, /*src*/ 100, 100);

        // halfWin = 2 on each side → interior: [10-2 .. 10+8-1+2] = [8..19] → width 12
        assertEquals(8, r.x);
        assertEquals(8, r.y);
        assertEquals(12, r.width);
        assertEquals(12, r.height);
    }

    @Test
    public void testStaticGetSourceRectangleClampsToOrigin() {
        final Rectangle r = PolClassifierBase.getSourceRectangle(
                0, 0, 5, 5, 7, 7, 100, 100);
        // left/top clamp to 0; right = min(0+5-1+3, 99)=7 → width 8
        assertEquals(0, r.x);
        assertEquals(0, r.y);
        assertEquals(8, r.width);
        assertEquals(8, r.height);
    }

    @Test
    public void testStaticGetSourceRectangleClampsToExtent() {
        final Rectangle r = PolClassifierBase.getSourceRectangle(
                95, 95, 5, 5, 7, 7, 100, 100);
        // left = max(0, 95-3)=92; right = min(95+5-1+3, 99)=99 → width 8
        assertEquals(92, r.x);
        assertEquals(92, r.y);
        assertEquals(8, r.width);
        assertEquals(8, r.height);
    }

    @Test
    public void testStaticGetSourceRectangleEvenWindowUsesIntegerHalf() {
        // Integer division: halfWindow = 4/2 = 2.
        // x0 = max(0, 10-2) = 8; xMax = min(10+4-1+2, 99) = 15; width = 15-8+1 = 8.
        final Rectangle r = PolClassifierBase.getSourceRectangle(
                10, 10, 4, 4, 4, 4, 100, 100);
        assertEquals(8, r.x);
        assertEquals(8, r.y);
        assertEquals(8, r.width);
        assertEquals(8, r.height);
    }

    // ---------- createIndexCoding ----------

    @Test
    public void testCreateIndexCodingNameIsClusterClasses() {
        final IndexCoding ic = NINE_CLASS_PROBE.createIndexCoding();
        assertNotNull(ic);
        assertEquals("Cluster_classes", ic.getName());
    }

    @Test
    public void testCreateIndexCodingIncludesNoDataAndAllClasses() {
        final IndexCoding ic = NINE_CLASS_PROBE.createIndexCoding();

        // 9 numbered classes + a "no data" entry = 10 indices.
        assertEquals(10, ic.getNumAttributes());
        assertNotNull(ic.getIndex("no data"));
        for (int i = 1; i <= 9; i++) {
            assertNotNull("missing class_" + i, ic.getIndex("class_" + i));
        }
    }

    @Test
    public void testCreateIndexCodingFourClassesStopsAtFour() {
        final IndexCoding ic = FOUR_CLASS_PROBE.createIndexCoding();
        // 4 numbered classes + "no data".
        assertEquals(5, ic.getNumAttributes());
        assertNotNull(ic.getIndex("class_1"));
        assertNotNull(ic.getIndex("class_4"));
        assertNull("class_5 should not exist for a 4-class classifier", ic.getIndex("class_5"));
    }

    @Test
    public void testCreateIndexCodingClassDescriptionsForKnownIndices() {
        final IndexCoding ic = NINE_CLASS_PROBE.createIndexCoding();
        assertEquals("Dihedral Reflector",
                ic.getIndex("class_1").getDescription());
        assertEquals("Dipole", ic.getIndex("class_2").getDescription());
        assertEquals("Bragg Surface", ic.getIndex("class_3").getDescription());
        assertEquals("Non-feasible", ic.getIndex("class_9").getDescription());
    }

    // ---------- ClusterInfo ----------

    @Test
    public void testClusterInfoSetClusterCenter3x3RecordsZoneAndSize() {
        final PolClassifierBase.ClusterInfo info = new PolClassifierBase.ClusterInfo();
        final double[][] mr = {
                { 1.0, 0.0, 0.0 },
                { 0.0, 1.0, 0.0 },
                { 0.0, 0.0, 1.0 }
        };
        final double[][] mi = new double[3][3];

        info.setClusterCenter(/*zoneIdx*/ 5, mr, mi, /*size*/ 42);

        // Cluster centre matrices are copied, not aliased.
        assertNotNull(info.centerRe);
        assertNotNull(info.centerIm);
        assertEquals(3, info.centerRe.length);
        assertEquals(3, info.centerRe[0].length);
        assertArrayEquals(mr[0], info.centerRe[0], 1e-12);
        assertEquals(0.0, info.centerIm[0][0], 1e-12);
        // Inverse of identity is identity → log det of identity ≈ 0.
        assertEquals(0.0, info.logDet, 1e-6);
        assertEquals(1.0, info.invCenterRe[0][0], 1e-6);
        assertEquals(1.0, info.invCenterRe[1][1], 1e-6);
        assertEquals(1.0, info.invCenterRe[2][2], 1e-6);
    }

    @Test
    public void testClusterInfoSetClusterCenter2x2RecordsZoneAndSize() {
        final PolClassifierBase.ClusterInfo info = new PolClassifierBase.ClusterInfo();
        final double[][] mr = {
                { 2.0, 0.0 },
                { 0.0, 2.0 }
        };
        final double[][] mi = new double[2][2];

        info.setClusterCenter(/*zoneIdx*/ 3, mr, mi, /*size*/ 11);

        assertNotNull(info.invCenterRe);
        assertEquals(2, info.invCenterRe.length);
        // diag(2,2) → inverse is diag(0.5, 0.5), log det = log(4) ≈ 1.3862944.
        assertEquals(0.5, info.invCenterRe[0][0], 1e-9);
        assertEquals(0.5, info.invCenterRe[1][1], 1e-9);
        assertEquals(Math.log(4.0), info.logDet, 1e-9);
    }

    @Test
    public void testClusterInfoCopiesMatrixData() {
        final PolClassifierBase.ClusterInfo info = new PolClassifierBase.ClusterInfo();
        final double[][] mr = { { 1.0, 2.0 }, { 2.0, 4.0 } };
        final double[][] mi = new double[2][2];
        info.setClusterCenter(1, mr, mi, 1);

        // Mutate the source after the call — the stored centre should not change.
        mr[0][0] = 999.0;
        assertEquals("cluster centre must be copied, not aliased", 1.0, info.centerRe[0][0], 1e-12);
    }

    // ---------- getSourceRectangle via instance probe ----------

    @Test
    public void testInstanceGetSourceRectangleReachable() {
        final HashMap<Band, PolBandUtils.PolSourceBand> empty = new HashMap<>();
        final ProbeBase p = new ProbeBase(empty);
        final Rectangle r = p.sourceRect(10, 10, 4, 4);
        assertNotNull(r);
        assertTrue(r.width > 0 && r.height > 0);
    }

    private static final class ProbeBase extends PolClassifierBase {
        ProbeBase(Map<Band, PolBandUtils.PolSourceBand> bandMap) {
            super(PolBandUtils.MATRIX.T3, 50, 50, 5, 5, bandMap, null);
        }
        @Override public int getNumClasses() { return 3; }
        Rectangle sourceRect(int tx0, int ty0, int tw, int th) {
            return getSourceRectangle(tx0, ty0, tw, th);
        }
    }
}
