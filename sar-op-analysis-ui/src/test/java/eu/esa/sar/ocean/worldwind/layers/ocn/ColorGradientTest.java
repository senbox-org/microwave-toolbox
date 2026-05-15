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
package eu.esa.sar.ocean.worldwind.layers.ocn;

import org.junit.Test;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.worldwind.ProductRenderablesInfo;
import gov.nasa.worldwind.render.Renderable;
import gov.nasa.worldwindx.examples.analytics.AnalyticSurface;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class ColorGradientTest {

    @Test
    public void createColorSurface_createsSurfaceCorrectly() {
        GeoPos geoPos1 = new GeoPos(0, 0);
        GeoPos geoPos2 = new GeoPos(1, 1);
        double[] latValues = {0, 1};
        double[] lonValues = {0, 1};
        double[] vals = {0.5, 1.0};
        List<Renderable> renderableList = new ArrayList<>();
        ProductRenderablesInfo prodRenderInfo = new ProductRenderablesInfo();

        ColorGradient.createColorSurface(null, geoPos1, geoPos2, latValues, lonValues, vals, 2, 2, renderableList, prodRenderInfo, "comp");
        assertEquals(1, renderableList.size());
    }

    @Test
    public void createColorGradientAttributes_createsAttributesCorrectly() {
        AnalyticSurface.GridPointAttributes attributes = ColorGradient.createColorGradientAttributes(0.5, 0.0, 1.0, 0.0, 1.0, true);

        assertNotNull(attributes);
        assertEquals(0.5, attributes.getValue(), 0.0);
    }
}