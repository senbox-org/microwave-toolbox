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
package eu.esa.sar.calibration.gpf.support;

import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;

/**
 * Sentinel-1 IPF version of a product. Repackaged products (e.g. CDSE COG) name their own software first in the
 * manifest processing history; the IPF entry ("Sentinel-1 IPF") is nested below it and takes precedence.
 */
public final class S1IpfVersion {

    private static final String IPF_SOFTWARE = "Sentinel-1 IPF";

    private S1IpfVersion() {
    }

    public static double get(final Product product) {
        final String id = AbstractMetadata.getAbstractedMetadata(product)
                .getAttributeString(AbstractMetadata.ProcessingSystemIdentifier, "");
        if (id.contains(IPF_SOFTWARE)) {
            final int space = id.lastIndexOf(' ');
            return parse(id.substring(space + 1));
        }
        // another processor (e.g. a COG repackager) is named first: its own version is not the IPF version
        final String nested = findIpfVersion(AbstractMetadata.getOriginalProductMetadata(product));
        if (nested == null) {
            throw new OperatorException("Unable to determine the Sentinel-1 IPF version from '" + id + "'");
        }
        return parse(nested);
    }

    private static double parse(final String version) {
        try {
            return Double.parseDouble(version.trim());
        } catch (NumberFormatException e) {
            throw new OperatorException("Unable to parse the Sentinel-1 IPF version '" + version + "'");
        }
    }

    /** Depth-first search for a "software" element named Sentinel-1 IPF; the first in document order wins. */
    private static String findIpfVersion(final MetadataElement elem) {
        if (elem == null) {
            return null;
        }
        if (elem.getName().equals("software") && IPF_SOFTWARE.equals(elem.getAttributeString("name", null))) {
            final String version = elem.getAttributeString("version", "");
            return version.isEmpty() ? null : version;
        }
        for (MetadataElement child : elem.getElements()) {
            final String v = findIpfVersion(child);
            if (v != null) {
                return v;
            }
        }
        return null;
    }
}
