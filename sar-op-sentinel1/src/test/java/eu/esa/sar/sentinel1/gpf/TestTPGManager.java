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
package eu.esa.sar.sentinel1.gpf;

import org.esa.snap.core.datamodel.TiePointGrid;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link TPGManager}.
 */
public class TestTPGManager {

    @Before
    public void resetState() {
        // TPGManager is a shared singleton; clear state between tests so they are independent.
        TPGManager.instance().removeAllTPGs();
    }

    @Test
    public void testInstanceIsSingleton() {
        final TPGManager a = TPGManager.instance();
        final TPGManager b = TPGManager.instance();
        assertNotNull(a);
        assertSame(a, b);
    }

    @Test
    public void testSetAndGetTPG() {
        final float[] data = new float[4];  // 2x2 grid
        TPGManager.instance().setTPG("etadLayer_IW1_VV_b0_phase", 2, 2, data);

        final TiePointGrid tpg = TPGManager.instance().getTPG("etadLayer_IW1_VV_b0_phase");
        assertNotNull("Registered TPG must be retrievable by name", tpg);
        assertEquals("etadLayer_IW1_VV_b0_phase", tpg.getName());
        assertEquals(2, tpg.getGridWidth());
        assertEquals(2, tpg.getGridHeight());
    }

    @Test
    public void testGetTPGReturnsNullForUnknownName() {
        assertNull(TPGManager.instance().getTPG("unregistered"));
    }

    @Test
    public void testGetTPGByLayerBurstSuffix() {
        TPGManager.instance().setTPG("heightLayer_IW1_VV_b3_phase", 2, 2, new float[4]);
        TPGManager.instance().setTPG("heightLayer_IW1_VV_b4_phase", 2, 2, new float[4]);

        final TiePointGrid found = TPGManager.instance().getTPG("heightLayer", 3, "phase");
        assertNotNull(found);
        assertEquals("heightLayer_IW1_VV_b3_phase", found.getName());
    }

    @Test
    public void testGetTPGByLayerBurstSuffixReturnsNullWhenNoMatch() {
        TPGManager.instance().setTPG("heightLayer_IW1_VV_b0_phase", 2, 2, new float[4]);
        assertNull(TPGManager.instance().getTPG("heightLayer", 99, "phase"));
        assertNull(TPGManager.instance().getTPG("otherLayer", 0, "phase"));
    }

    @Test
    public void testHasTPG() {
        TPGManager.instance().setTPG("ionoLayer_IW2_VV_b7_phase", 2, 2, new float[4]);

        assertTrue(TPGManager.instance().hasTPG("ionoLayer", "phase"));
        assertFalse(TPGManager.instance().hasTPG("ionoLayer", "missing"));
        assertFalse(TPGManager.instance().hasTPG("wrongPrefix", "phase"));
    }

    @Test
    public void testRemoveAllTPGsClearsState() {
        TPGManager.instance().setTPG("a", 2, 2, new float[4]);
        TPGManager.instance().setTPG("b", 2, 2, new float[4]);
        assertNotNull(TPGManager.instance().getTPG("a"));

        TPGManager.instance().removeAllTPGs();

        assertNull(TPGManager.instance().getTPG("a"));
        assertNull(TPGManager.instance().getTPG("b"));
    }

    @Test
    public void testSetAndGetBurstIndexArray() {
        final int[] bursts = { 0, 1, 2, 3 };
        TPGManager.instance().setBurstIndexArray("IW1_VV", bursts);

        final int[] retrieved = TPGManager.instance().getBurstIndexArray("IW1_VV");
        assertArrayEquals(bursts, retrieved);
    }

    @Test
    public void testGetBurstIndexArrayReturnsNullForUnknownKey() {
        assertNull(TPGManager.instance().getBurstIndexArray("IW99_ZZ"));
    }

    @Test
    public void testSetTPGOverwritesExistingEntry() {
        TPGManager.instance().setTPG("overwrite_me", 2, 2, new float[4]);
        TPGManager.instance().setTPG("overwrite_me", 4, 4, new float[16]);

        final TiePointGrid tpg = TPGManager.instance().getTPG("overwrite_me");
        assertNotNull(tpg);
        assertEquals(4, tpg.getGridWidth());
        assertEquals(4, tpg.getGridHeight());
    }

    @Test
    public void testRemoveAllTPGsDoesNotClearBurstArrays() {
        TPGManager.instance().setBurstIndexArray("IW1_VV", new int[] { 1, 2 });
        TPGManager.instance().setTPG("layer_b0", 2, 2, new float[4]);

        TPGManager.instance().removeAllTPGs();

        // removeAllTPGs only clears the TPG map, not burst arrays — pin that contract.
        assertNotNull(TPGManager.instance().getBurstIndexArray("IW1_VV"));
    }
}
