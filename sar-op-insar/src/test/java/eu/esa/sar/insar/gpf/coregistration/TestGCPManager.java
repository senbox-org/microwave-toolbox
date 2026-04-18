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
package eu.esa.sar.insar.gpf.coregistration;

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Placemark;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.ProductNodeGroup;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

/**
 * Unit tests for {@link GCPManager}.
 */
public class TestGCPManager {

    private Product product;
    private Band band1;
    private Band band2;

    @Before
    public void setUp() {
        // Reset the singleton's state so tests are independent of each other's order.
        GCPManager.instance().removeAllGcpGroups();

        product = new Product("p", "T", 4, 4);
        band1 = product.addBand("b1", ProductData.TYPE_FLOAT32);
        band2 = product.addBand("b2", ProductData.TYPE_FLOAT32);
    }

    @Test
    public void testInstanceIsSingleton() {
        final GCPManager a = GCPManager.instance();
        final GCPManager b = GCPManager.instance();
        assertNotNull(a);
        assertSame(a, b);
    }

    @Test
    public void testGetGcpGroupCreatesEmptyGroupOnFirstAccess() {
        final ProductNodeGroup<Placemark> group = GCPManager.instance().getGcpGroup(band1);
        assertNotNull(group);
        assertEquals(0, group.getNodeCount());
    }

    @Test
    public void testGetGcpGroupReturnsSameGroupForSameBand() {
        final ProductNodeGroup<Placemark> first = GCPManager.instance().getGcpGroup(band1);
        final ProductNodeGroup<Placemark> second = GCPManager.instance().getGcpGroup(band1);
        assertSame("second call must return the memoized group", first, second);
    }

    @Test
    public void testGetGcpGroupReturnsSeparateGroupsPerBand() {
        final ProductNodeGroup<Placemark> g1 = GCPManager.instance().getGcpGroup(band1);
        final ProductNodeGroup<Placemark> g2 = GCPManager.instance().getGcpGroup(band2);
        assertNotNull(g1);
        assertNotNull(g2);
        // Each band must have its own placemark group.
        if (g1 == g2) {
            throw new AssertionError("different bands must map to different GCP groups");
        }
    }

    @Test
    public void testRemoveAllGcpGroupsClearsState() {
        final ProductNodeGroup<Placemark> before = GCPManager.instance().getGcpGroup(band1);
        assertNotNull(before);

        GCPManager.instance().removeAllGcpGroups();

        final ProductNodeGroup<Placemark> after = GCPManager.instance().getGcpGroup(band1);
        assertNotNull(after);
        // After a clear, a fresh group must be created — not the old one.
        if (before == after) {
            throw new AssertionError("removeAllGcpGroups should evict the cached group");
        }
    }
}
