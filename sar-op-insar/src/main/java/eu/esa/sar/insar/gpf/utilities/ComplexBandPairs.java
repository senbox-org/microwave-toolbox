/*
 * Copyright (C) 2026 SkyWatch Space Applications Inc. https://www.skywatch.com
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
package eu.esa.sar.insar.gpf.utilities;

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;

import java.util.ArrayList;
import java.util.List;

/**
 * The i/q band pairs among a stack product's reference or secondary band names.
 * <p>
 * A product can hold several pairs: CreateStack gives each polarisation of a dual-pol secondary its own
 * tag (i/q_CH_sec1_D, i/q_CV_sec2_D). Demodulate and Remodulate used to walk the names keeping the last
 * {@code i_} and treating every other band as {@code q_}, so all but one pair was lost and any extra
 * band (a DemodPhase band, an amplitude band) could be taken for the imaginary part.
 */
final class ComplexBandPairs {

    private ComplexBandPairs() {
    }

    /**
     * Pairs each {@code i_<rest>} band with its {@code q_<rest>} partner, in the order the i bands are
     * named. An i band whose partner is absent from {@code bandNames} or from the product (dropped by a
     * band subset, for example) is skipped; non-complex bands are ignored.
     *
     * @return {i, q} band arrays
     */
    static List<Band[]> of(final Product product, final String[] bandNames) {
        final List<Band[]> pairs = new ArrayList<>();
        for (final String name : bandNames) {
            if (!name.startsWith("i_")) {
                continue;
            }
            final String qName = "q_" + name.substring(2);
            if (!contains(bandNames, qName)) {
                continue;
            }
            final Band bandI = product.getBand(name);
            final Band bandQ = product.getBand(qName);
            if (bandI != null && bandQ != null) {
                pairs.add(new Band[]{bandI, bandQ});
            }
        }
        return pairs;
    }

    private static boolean contains(final String[] names, final String name) {
        for (final String n : names) {
            if (n.equals(name)) {
                return true;
            }
        }
        return false;
    }
}
