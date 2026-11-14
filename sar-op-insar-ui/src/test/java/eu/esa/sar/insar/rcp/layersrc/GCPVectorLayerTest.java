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
package eu.esa.sar.insar.rcp.layersrc;

import org.esa.snap.core.datamodel.MetadataElement;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

/**
 * WarpOp and OffsetTrackingOp write the GCP coordinates as ref_x/ref_y/sec_x/sec_y; products written before the
 * rename carry mst_x/mst_y/slv_x/slv_y. The GCP movement layer must read both.
 */
public class GCPVectorLayerTest {

    @Test
    public void testReadsReferenceSecondaryAttributes() {
        final MetadataElement gcp = new MetadataElement("GCP_0");
        gcp.setAttributeDouble("ref_x", 10.0);
        gcp.setAttributeDouble("ref_y", 20.0);
        gcp.setAttributeDouble("sec_x", 13.5);
        gcp.setAttributeDouble("sec_y", 18.25);
        gcp.setAttributeDouble("rms", 0.1);

        assertArrayEquals(new double[]{10.0, 20.0, 13.5, 18.25}, GCPVectorLayer.readCoordinates(gcp), 0.0);
    }

    @Test
    public void testFallsBackToLegacyMasterSlaveAttributes() {
        final MetadataElement gcp = new MetadataElement("GCP_0");
        gcp.setAttributeDouble("mst_x", 1.0);
        gcp.setAttributeDouble("mst_y", 2.0);
        gcp.setAttributeDouble("slv_x", 3.0);
        gcp.setAttributeDouble("slv_y", 4.0);

        assertArrayEquals(new double[]{1.0, 2.0, 3.0, 4.0}, GCPVectorLayer.readCoordinates(gcp), 0.0);
    }
}
