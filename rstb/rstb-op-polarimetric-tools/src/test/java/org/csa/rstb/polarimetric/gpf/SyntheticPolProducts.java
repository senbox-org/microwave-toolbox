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
package org.csa.rstb.polarimetric.gpf;

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;

import java.util.Arrays;

/**
 * In-memory synthetic polarimetric products for unit tests (no external test data).
 */
final class SyntheticPolProducts {

    private SyntheticPolProducts() {
    }

    static Product newSarProduct(final int w, final int h, final boolean complex) {
        final Product product = new Product("synthetic", complex ? "SLC" : "GRD", w, h);
        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(product.getMetadataRoot());
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.radar_frequency, 5405.0);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SAMPLE_TYPE, complex ? "COMPLEX" : "DETECTED");
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.MISSION, "RS2");
        return product;
    }

    static Band addBand(final Product product, final String name, final String unit, final float[] values) {
        final Band band = product.addBand(name, ProductData.TYPE_FLOAT32);
        band.setUnit(unit);
        band.setData(ProductData.createInstance(values));
        return band;
    }

    static Band addConstantBand(final Product product, final String name, final String unit, final double value) {
        final float[] values = new float[product.getSceneRasterWidth() * product.getSceneRasterHeight()];
        Arrays.fill(values, (float) value);
        return addBand(product, name, unit, values);
    }

    /**
     * Spatially constant C3 product.
     * c13 = &lt;S_HH S_VV*&gt;; c12 and c23 are zero (reflection symmetry).
     */
    static Product createConstantC3Product(final int w, final int h, final boolean complex,
                                           final double c11, final double c22, final double c33,
                                           final double c13Re, final double c13Im) {
        final Product product = newSarProduct(w, h, complex);
        addConstantBand(product, "C11", Unit.INTENSITY, c11);
        addConstantBand(product, "C12_real", Unit.REAL, 0);
        addConstantBand(product, "C12_imag", Unit.IMAGINARY, 0);
        addConstantBand(product, "C13_real", Unit.REAL, c13Re);
        addConstantBand(product, "C13_imag", Unit.IMAGINARY, c13Im);
        addConstantBand(product, "C22", Unit.INTENSITY, c22);
        addConstantBand(product, "C23_real", Unit.REAL, 0);
        addConstantBand(product, "C23_imag", Unit.IMAGINARY, 0);
        addConstantBand(product, "C33", Unit.INTENSITY, c33);
        return product;
    }

    /**
     * FULL (8-band i/q) scattering-matrix product. kRe/kIm are [4][w*h] with channel order HH, HV, VH, VV.
     */
    static Product createFullProduct(final int w, final int h, final double[][] kRe, final double[][] kIm) {
        final Product product = newSarProduct(w, h, true);
        final String[] pols = {"HH", "HV", "VH", "VV"};
        for (int c = 0; c < 4; c++) {
            final float[] re = new float[w * h];
            final float[] im = new float[w * h];
            for (int p = 0; p < w * h; p++) {
                re[p] = (float) kRe[c][p];
                im[p] = (float) kIm[c][p];
            }
            addBand(product, "i_" + pols[c], Unit.REAL, re);
            addBand(product, "q_" + pols[c], Unit.IMAGINARY, im);
        }
        return product;
    }

    /** Mark the product as a coregistered stack with one reference and one secondary acquisition. */
    static void makeStack(final Product product, final String[] refBands, final String secName, final String[] secBands) {
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        absRoot.setAttributeInt(AbstractMetadata.coregistered_stack, 1);
        final MetadataElement secRoot = new MetadataElement(AbstractMetadata.SECONDARY_METADATA_ROOT);
        secRoot.setAttributeString(AbstractMetadata.REFERENCE_BANDS, String.join(" ", refBands));
        final MetadataElement sec = new MetadataElement(secName);
        sec.setAttributeString(AbstractMetadata.SECONDARY_BANDS, String.join(" ", secBands));
        secRoot.addElement(sec);
        product.getMetadataRoot().addElement(secRoot);
    }

    static float[] readBand(final Product product, final String bandName) throws Exception {
        final Band band = product.getBand(bandName);
        final int w = product.getSceneRasterWidth();
        final int h = product.getSceneRasterHeight();
        final float[] values = new float[w * h];
        band.readPixels(0, 0, w, h, values, com.bc.ceres.core.ProgressMonitor.NULL);
        return values;
    }
}
