/*
 * Copyright (C) 2016 by Array Systems Computing Inc. http://www.array.ca
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
import org.esa.snap.core.datamodel.ProductNodeGroup;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Temporary solution to removing band GCPs from product.
 */
public class GCPManager {

    // Holder-idiom singleton: thread-safe lazy initialization without explicit locking.
    private static final class Holder {
        static final GCPManager INSTANCE = new GCPManager();
    }

    private final Map<String, ProductNodeGroup<Placemark>> bandGCPGroup = new ConcurrentHashMap<>();

    private GCPManager() {
    }

    private String createKey(final Band band) {
        return band.getProduct().getName() + '_' + band.getName();
    }

    public static GCPManager instance() {
        return Holder.INSTANCE;
    }

    public ProductNodeGroup<Placemark> getGcpGroup(final Band band) {
        return bandGCPGroup.computeIfAbsent(createKey(band),
                key -> new ProductNodeGroup<>(band.getProduct(), "ground_control_points", true));
    }

    public void removeAllGcpGroups() {
        bandGCPGroup.clear();
    }
}
