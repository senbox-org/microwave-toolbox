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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds TPGs and per-burst index arrays produced during ETAD-driven coregistration.
 *
 * Originally a process-wide singleton — that broke under concurrent BackGeocodingOp instances in the
 * same JVM. The new shape is per-instance: callers (e.g. BackGeocodingOp) hold their own TPGManager
 * and pass it to whichever consumer needs to read the TPGs back. The static {@link #instance()} accessor
 * is retained for backwards compatibility (legacy callers continue to share a single instance), but new
 * code should construct a fresh manager via {@code new TPGManager()} to avoid cross-instance leakage.
 */
public class TPGManager {

    // Initialization-on-demand holder idiom: thread-safe lazy init without explicit synchronization.
    private static final class Holder {
        static final TPGManager INSTANCE = new TPGManager();
    }

    private final Map<String, TiePointGrid> etadTPGMap = new ConcurrentHashMap<>();
    private final Map<String, int[]> etadBurstsMap = new ConcurrentHashMap<>();

    /** Public so callers can hold a per-operator instance instead of sharing the global one. */
    public TPGManager() {
    }

    public static TPGManager instance() {
        return Holder.INSTANCE;
    }

    public void setTPG(final String tpgName, final int tpgWidth, final int tpgHeight, final float[] tgpData) {
        TiePointGrid tpg = new TiePointGrid(tpgName, tpgWidth, tpgHeight, 0, 0, 1, 1, tgpData);
        etadTPGMap.put(tpgName, tpg);
    }

    public TiePointGrid getTPG(final String tpgName) {
        return etadTPGMap.get(tpgName);
    }

    public void setBurstIndexArray(final String burstIndexArrayName, final int[] burstIndexArray) {
        etadBurstsMap.put(burstIndexArrayName, burstIndexArray);
    }

    public int[] getBurstIndexArray(final String burstIndexArrayName) {
        return etadBurstsMap.get(burstIndexArrayName);
    }

    public TiePointGrid getTPG(final String layer, final int burstIndex, final String suffix) {
        for (Map.Entry<String, TiePointGrid> e : etadTPGMap.entrySet()) {
            final String tpgName = e.getKey();
            if (tpgName.startsWith(layer) && tpgName.endsWith(burstIndex + "_" + suffix)) {
                return e.getValue();
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
        etadBurstsMap.clear();
    }
}
