/*
 * Copyright (C) 2014 by Array Systems Computing Inc. http://www.array.ca
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
package eu.esa.microwave.dat.layers.maptools;

import org.esa.snap.ui.layer.AbstractLayerEditor;

import javax.swing.*;

/**
 * Editor for GeoTags
 */
public class MapToolsLayerEditor extends AbstractLayerEditor {

    @Override
    protected MapToolsLayer getCurrentLayer() {
        return (MapToolsLayer) super.getCurrentLayer();
    }

    @Override
    public JComponent createControl() {
        return getCurrentLayer().getMapToolsOptions().createPanel();
    }
}
