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
        fill(masterA, slaveA, masterPositions, false, 25.0, -25.0);

        final ProductNodeGroup<Placemark> masterB = newGroup("masterB");
        final ProductNodeGroup<Placemark> slaveB = newGroup("slaveB");
        fill(masterB, slaveB, masterPositions, true, 25.0, -25.0);

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

    /**
     * An outlier with an error in the azimuth (line) direction only. Doris COREGPM removes the observation with the
     * largest summed statistic wTestL^2 + wTestP^2; picking the maximum of the range statistic alone selects an
     * arbitrary point here (all range residuals are zero) and never removes the azimuth outlier.
     */
    @Test
    public void testAzimuthOnlyOutlierIsRemoved() throws Exception {

        final List<int[]> masterPositions = buildMasterGrid();

        final ProductNodeGroup<Placemark> master = newGroup("master");
        final ProductNodeGroup<Placemark> slave = newGroup("slave");
        fill(master, slave, masterPositions, false, 0.0, -25.0);

        final CPM cpm = new CPM(1, 20, CRITICAL_VALUE, new Window(0, HEIGHT, 0, WIDTH), master, slave);
        cpm.computeCPM();

        final List<String> survivors = survivorNames(cpm);
        assertFalse("Azimuth outlier GCP must be removed by datasnooping", survivors.contains(OUTLIER_NAME));
        assertEquals("Exactly the azimuth outlier should be removed", masterPositions.size() - 1, survivors.size());
    }

    /** Fewer GCPs than unknowns: the lists stay empty, so callers looping to getNumObservations() must not overrun. */
    @Test
    public void testTooFewGCPsReportsNoObservations() {
        final ProductNodeGroup<Placemark> master = newGroup("master");
        final ProductNodeGroup<Placemark> slave = newGroup("slave");
        for (int k = 0; k < 2; k++) {   // degree 1 has 3 unknowns
            master.add(pin("gcp_" + k, 100 + 100 * k, 100));
            slave.add(pin("gcp_" + k, 103 + 100 * k, 98));
        }
        final CPM cpm = new CPM(1, 20, CRITICAL_VALUE, new Window(0, HEIGHT, 0, WIDTH), master, slave);
        assertFalse(cpm.isValid());
        assertEquals(0, cpm.getNumObservations());
    }

    /** As many GCPs as unknowns leaves no redundancy for the outlier tests, which divide by n - u. */
    @Test
    public void testExactlyDeterminedIsInvalid() {
        final ProductNodeGroup<Placemark> master = newGroup("master");
        final ProductNodeGroup<Placemark> slave = newGroup("slave");
        final int[][] pos = {{100, 100}, {800, 150}, {400, 900}};   // degree 1 has 3 unknowns
        for (int k = 0; k < pos.length; k++) {
            master.add(pin("gcp_" + k, pos[k][0], pos[k][1]));
            slave.add(pin("gcp_" + k, pos[k][0] + 3, pos[k][1] - 2));
        }
        final CPM cpm = new CPM(1, 20, CRITICAL_VALUE, new Window(0, HEIGHT, 0, WIDTH), master, slave);
        assertFalse(cpm.isValid());
        assertEquals(0, cpm.getNumObservations());
    }

    /**
     * One GCP more than unknowns, one of them flagged by the w-test: removing it would leave n == u and divide by
     * zero in the overall model test. Outlier removal must stop with n = u + 1 instead.
     */
    @Test
    public void testOutlierRemovalKeepsOneRedundantObservation() throws Exception {
        final ProductNodeGroup<Placemark> master = newGroup("master");
        final ProductNodeGroup<Placemark> slave = newGroup("slave");
        final int[][] pos = {{100, 100}, {800, 150}, {400, 900}, {850, 800}};
        for (int k = 0; k < pos.length; k++) {
            final double err = k == 3 ? 0.4 : 0.0;   // flagged by the w-test, yet within the overall model test
            master.add(pin("gcp_" + k, pos[k][0], pos[k][1]));
            slave.add(pin("gcp_" + k, pos[k][0] + SHIFT_X + err, pos[k][1] + SHIFT_Y));
        }
        final CPM cpm = new CPM(1, 20, CRITICAL_VALUE, new Window(0, HEIGHT, 0, WIDTH), master, slave);
        cpm.computeCPM();
        assertEquals(4, cpm.getNumObservations());
    }

    /** Identical Reference/Secondary positions skip estimation; the per-GCP rms must still be filled. */
    @Test
    public void testIdentityOffsetsFillRms() throws Exception {
        final CPM cpm = constantShiftCPM(0.0, 0.0);
        cpm.computeCPM();
        cpm.computeEstimationStats();
        for (int i = 0; i < cpm.getNumObservations(); i++) {
            assertEquals(0.0, cpm.getRMS(i), 1e-12);
        }
    }

    /** dx = +1, dy = -1 cancel in |dx+dy|; such a shift is not the identity and must be estimated. */
    @Test
    public void testCancellingOffsetsAreEstimated() throws Exception {
        final CPM cpm = constantShiftCPM(1.0, -1.0);
        cpm.computeCPM();
        cpm.computeEstimationStats();
        // the identity model would leave a residual of sqrt(2) px; the estimated shift fits exactly
        assertEquals(0.0, cpm.getRMS(0), 1e-6);
    }

    private CPM constantShiftCPM(final double dx, final double dy) {
        final ProductNodeGroup<Placemark> master = newGroup("master");
        final ProductNodeGroup<Placemark> slave = newGroup("slave");
        final List<int[]> positions = buildMasterGrid();
        for (int k = 0; k < positions.size(); k++) {
            final int[] m = positions.get(k);
            master.add(pin("gcp_" + k, m[0], m[1]));
            slave.add(pin("gcp_" + k, m[0] + dx, m[1] + dy));
        }
        return new CPM(1, 20, CRITICAL_VALUE, new Window(0, HEIGHT, 0, WIDTH), master, slave);
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
                      final boolean reversed,
                      final double outlierDx,
                      final double outlierDy) {

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
                sx = m[0] + SHIFT_X + outlierDx; // gross outlier
                sy = m[1] + SHIFT_Y + outlierDy;
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
