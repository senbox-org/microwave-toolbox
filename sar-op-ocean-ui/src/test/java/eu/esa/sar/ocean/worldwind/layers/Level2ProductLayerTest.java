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
package eu.esa.sar.ocean.worldwind.layers;

import gov.nasa.worldwind.awt.WorldWindowGLCanvas;
import org.esa.snap.worldwind.ColorBarLegend;
import org.junit.Before;
import org.junit.Test;
import org.esa.snap.core.datamodel.Product;

import javax.swing.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class Level2ProductLayerTest {

    private Level2ProductLayer level2ProductLayer;
    private Product mockProduct;
    private WorldWindowGLCanvas mockWwd;

    @Before
    public void setUp() {
        level2ProductLayer = new Level2ProductLayer();
        mockProduct = mock(Product.class);
        mockWwd = mock(WorldWindowGLCanvas.class);
    }

    @Test
    public void getSuitability_returnsSuitableForOCNProduct() {
        when(mockProduct.getProductType()).thenReturn("OCN");
        assertEquals(Level2ProductLayer.Suitability.INTENDED, level2ProductLayer.getSuitability(mockProduct));

        when(mockProduct.getProductType()).thenReturn("SLC");
        assertEquals(Level2ProductLayer.Suitability.UNSUITABLE, level2ProductLayer.getSuitability(mockProduct));
    }

    @Test
    public void createColorBarLegend_createsLegendWithCorrectAttributes() {
        level2ProductLayer.createColorBarLegend(0, 10, "Test Legend", "testComp");

        ColorBarLegend legend = level2ProductLayer.theColorBarLegendHash.get("testComp");
        assertNotNull(legend);
    }

    @Test
    public void setComponentVisible_updatesVisibilityCorrectly() {
        level2ProductLayer.createColorBarLegend(0, 10, "Test Legend", "testComp");
        level2ProductLayer.setComponentVisible("testComp", mockWwd);

        assertTrue(level2ProductLayer.theColorBarLegendHash.get("testComp").isVisible());
    }

    @Test
    public void getControlPanel_initializesControlPanelCorrectly() {
        JPanel controlPanel = level2ProductLayer.getControlPanel(mockWwd);

        assertNotNull(controlPanel);
        assertEquals(6, controlPanel.getComponentCount());
    }
}

