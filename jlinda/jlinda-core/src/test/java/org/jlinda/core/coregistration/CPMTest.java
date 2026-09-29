/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc.
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
package org.jlinda.core.coregistration;

import org.esa.snap.core.datamodel.GcpDescriptor;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Placemark;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductNodeGroup;
import org.jlinda.core.Window;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Regression test for the coregistration polynomial outlier-removal (datasnooping) loop in {@link CPM}.
 *
 * <p>The estimation must remove the actual <em>outlier</em> GCP, and the surviving set of GCPs must be
 * independent of the order in which the slave GCPs were inserted into the group. {@code CrossCorrelationOp}
 * populates the slave GCP group from worker threads, so the insertion order is non-deterministic; if the
 * datasnooping loop is sensitive to that order, the coregistration warp — and therefore the interferogram
 * phase — becomes non-deterministic between runs.
 *
 * <p>This guards against the regression where {@code maxWSum_idx} was never reassigned inside the loop,
 * causing the head of the list (rather than the worst outlier) to be removed each iteration.
 */
public class CPMTest {

    private static final int WIDTH = 1000;
    private static final int HEIGHT = 1000;
    private static final int GRID = 5; // 5x5 = 25 control points
    private static final double SHIFT_X = 3.0; // perfect constant offset that a degree-1 poly fits exactly
    private static final double SHIFT_Y = -2.0;
    private static final String OUTLIER_NAME = "gcp_13"; // a non-head, interior point
    private static final float CRITICAL_VALUE = 1.95996398454005f; // matches WarpOp rmsThreshold 0.05

    @Test
    public void testOutlierRemovalIsOrderIndependent() throws Exception {

        final List<int[]> masterPositions = buildMasterGrid();

        // Two slave orderings of the *same* GCP pairs: natural order and reversed order.
        final ProductNodeGroup<Placemark> masterA = newGroup("masterA");
        final ProductNodeGroup<Placemark> slaveA = newGroup("slaveA");
        fill(masterA, slaveA, masterPositions, false);

        final ProductNodeGroup<Placemark> masterB = newGroup("masterB");
        final ProductNodeGroup<Placemark> slaveB = newGroup("slaveB");
        fill(masterB, slaveB, masterPositions, true);

        final Window window = new Window(0, HEIGHT, 0, WIDTH);

        final CPM cpmA = new CPM(1, 20, CRITICAL_VALUE, window, masterA, slaveA);
        cpmA.computeCPM();

        final CPM cpmB = new CPM(1, 20, CRITICAL_VALUE, window, masterB, slaveB);
        cpmB.computeCPM();

        final List<String> survivorsA = survivorNames(cpmA);
        final List<String> survivorsB = survivorNames(cpmB);

        // The exact set of surviving GCPs must not depend on insertion order.
        assertEquals("Surviving GCP set must be independent of slave GCP insertion order",
                survivorsA, survivorsB);

        // And the single planted outlier must be the one that was removed.
        assertFalse("Outlier GCP must be removed by datasnooping", survivorsA.contains(OUTLIER_NAME));

        // With one outlier out of 25, exactly one observation should be dropped.
        assertEquals("Exactly the outlier should be removed", masterPositions.size() - 1, survivorsA.size());
    }

    private static List<int[]> buildMasterGrid() {
        final List<int[]> positions = new ArrayList<>();
        final int spacingX = WIDTH / (GRID + 1);
        final int spacingY = HEIGHT / (GRID + 1);
        for (int j = 1; j <= GRID; j++) {
            for (int i = 1; i <= GRID; i++) {
                positions.add(new int[]{i * spacingX, j * spacingY});
            }
        }
        return positions;
    }

    private void fill(final ProductNodeGroup<Placemark> masterGroup,
                      final ProductNodeGroup<Placemark> slaveGroup,
                      final List<int[]> masterPositions,
                      final boolean reversed) {

        // master pins are always inserted in the same (deterministic) grid order, mirroring addGCPGrid
        final List<Placemark> masterPins = new ArrayList<>();
        final List<Placemark> slavePins = new ArrayList<>();
        for (int k = 0; k < masterPositions.size(); k++) {
            final int[] m = masterPositions.get(k);
            final String name = "gcp_" + (k + 1);

            masterPins.add(pin(name, m[0], m[1]));

            final double sx;
            final double sy;
            if (name.equals(OUTLIER_NAME)) {
                sx = m[0] + SHIFT_X + 25.0; // gross outlier
                sy = m[1] + SHIFT_Y - 25.0;
            } else {
                sx = m[0] + SHIFT_X;
                sy = m[1] + SHIFT_Y;
            }
            slavePins.add(pin(name, sx, sy));
        }

        for (Placemark p : masterPins) {
            masterGroup.add(p);
        }

        // the slave group insertion order is what CrossCorrelationOp leaves non-deterministic
        if (reversed) {
            Collections.reverse(slavePins);
        }
        for (Placemark p : slavePins) {
            slaveGroup.add(p);
        }
    }

    private static Placemark pin(final String name, final double x, final double y) {
        return Placemark.createPointPlacemark(GcpDescriptor.getInstance(), name, name, "",
                new PixelPos(x, y), null, null);
    }

    private static ProductNodeGroup<Placemark> newGroup(final String name) {
        final Product product = new Product(name, "test", WIDTH, HEIGHT);
        return new ProductNodeGroup<>(product, "ground_control_points", true);
    }

    private static List<String> survivorNames(final CPM cpm) {
        final List<String> names = new ArrayList<>();
        for (Placemark p : cpm.getSlaveGCPList()) {
            names.add(p.getName());
        }
        Collections.sort(names);
        return names;
    }
}
