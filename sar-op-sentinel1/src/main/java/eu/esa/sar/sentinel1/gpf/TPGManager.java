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
package eu.esa.sar.sentinel1.gpf;

import org.esa.snap.core.datamodel.TiePointGrid;

import java.util.HashMap;
import java.util.Map;

/**
 * Temporary solution to removing band TPGs from product.
 */
public class TPGManager {

    private static TPGManager _instance = null;

    private final Map<String, TiePointGrid> etadTPGMap = new HashMap<>();

    private TPGManager() {

    }

    private String createKey(final TiePointGrid tpg) {
        return tpg.getName();
    }

    public static TPGManager instance() {
        if(_instance == null) {
            _instance = new TPGManager();
        }
        return _instance;
    }

	public void setTPG(final String tpgName, final int tpgWidth, final int tpgHeight, final float[] tgpData) {
		TiePointGrid tpg = new TiePointGrid(tpgName, tpgWidth, tpgHeight, 0, 0, 1, 1, tgpData);
        etadTPGMap.put(tpgName, tpg);
	}

    public TiePointGrid getTPG(final String tpgName) {
        return etadTPGMap.get(tpgName);
    }

    public TiePointGrid getTPG(final String layer, final int burstIndex, final String suffix) {
        for (String tpgName : etadTPGMap.keySet()) {
            if (tpgName.startsWith(layer) && tpgName.endsWith(burstIndex + "_" + suffix)) {
                return etadTPGMap.get(tpgName);
            }
        }
        return null;
    }

    public boolean hasTPG(final String layer, final String suffix) {
        for (String tpgName : etadTPGMap.keySet()) {
            if (tpgName.startsWith(layer) && tpgName.contains(suffix)) {
                return true;
            }
        }
        return false;
    }

    public void removeAllTPGs() {
        etadTPGMap.clear();
    }
}
