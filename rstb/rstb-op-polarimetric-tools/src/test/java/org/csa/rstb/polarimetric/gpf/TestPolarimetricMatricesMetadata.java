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
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Metadata contract of {@link PolarimetricMatricesOp}, on synthetic products so it runs without
 * the polarimetric test data set.
 */
public class TestPolarimetricMatricesMetadata {

    private final static OperatorSpi spi = new PolarimetricMatricesOp.Spi();

    /**
     * A dual-pol complex product, the input Polarimetric-Matrices takes for C2.
     */
    private static Product createDualPolComplexProduct() {
        final Product product = new Product("dualpol", "SLC", 4, 4);
        addComplexPair(product, "i_VV", "q_VV");
        addComplexPair(product, "i_VH", "q_VH");

        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(product.getMetadataRoot());
        absRoot.setAttributeString(AbstractMetadata.MISSION, "RADARSAT-2");
        absRoot.setAttributeString(AbstractMetadata.SAMPLE_TYPE, "COMPLEX");
        absRoot.setAttributeDouble(AbstractMetadata.radar_frequency, 5405.0);
        return product;
    }

    private static void addComplexPair(final Product product, final String iName, final String qName) {
        final Band iBand = new Band(iName, ProductData.TYPE_FLOAT32, 4, 4);
        iBand.setUnit(Unit.REAL);
        product.addBand(iBand);
        final Band qBand = new Band(qName, ProductData.TYPE_FLOAT32, 4, 4);
        qBand.setUnit(Unit.IMAGINARY);
        product.addBand(qBand);
    }

    private static Product runMatrices(final String matrixType) {
        final PolarimetricMatricesOp op = (PolarimetricMatricesOp) spi.createOperator();
        op.setSourceProduct(createDualPolComplexProduct());
        op.SetMatrixType(matrixType);
        return op.getTargetProduct();
    }

    /**
     * A quad-pol complex product, the input Polarimetric-Matrices takes for C3.
     */
    private static Product createQuadPolComplexProduct() {
        final Product product = new Product("quadpol", "SLC", 4, 4);
        addComplexPair(product, "i_HH", "q_HH");
        addComplexPair(product, "i_HV", "q_HV");
        addComplexPair(product, "i_VH", "q_VH");
        addComplexPair(product, "i_VV", "q_VV");

        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(product.getMetadataRoot());
        absRoot.setAttributeString(AbstractMetadata.MISSION, "RADARSAT-2");
        absRoot.setAttributeString(AbstractMetadata.SAMPLE_TYPE, "COMPLEX");
        absRoot.setAttributeDouble(AbstractMetadata.radar_frequency, 5405.0);
        return product;
    }

    /**
     * checkSourceProductType() explicitly supports converting a C3 product to T3, and
     * PolarimetricDecompositionOp / PolarimetricClassificationOp / OrientationAngleCorrectionOp
     * all accept a matrix product while gating on InputProductValidator.checkIfSLC(). So the
     * SAMPLE_TYPE of a matrix product has to stay COMPLEX: marking the output DETECTED turns
     * every one of those chains into "Source product should be a single look complex SLC product".
     */
    @Test
    public void testC3OutputCanBeConvertedToT3() {
        final PolarimetricMatricesOp c3Op = (PolarimetricMatricesOp) spi.createOperator();
        c3Op.setSourceProduct(createQuadPolComplexProduct());
        c3Op.SetMatrixType(PolarimetricMatricesOp.C3);
        final Product c3Product = c3Op.getTargetProduct();

        final PolarimetricMatricesOp t3Op = (PolarimetricMatricesOp) spi.createOperator();
        t3Op.setSourceProduct(c3Product);
        t3Op.SetMatrixType(PolarimetricMatricesOp.T3);
        final Product t3Product = t3Op.getTargetProduct();

        assertNotNull(t3Product);
        assertNotNull("T33 missing", t3Product.getBand("T33"));
    }

    @Test
    public void testC2OutputIsFlaggedAsPolsar() {
        final Product targetProduct = runMatrices(PolarimetricMatricesOp.C2);

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(targetProduct);
        assertEquals(1, absRoot.getAttributeInt(AbstractMetadata.polsarData));
    }

    @Test
    public void testC2OutputHasAllMatrixBands() {
        final Product targetProduct = runMatrices(PolarimetricMatricesOp.C2);

        for (String name : new String[]{"C11", "C12_real", "C12_imag", "C22"}) {
            assertNotNull(name + " missing", targetProduct.getBand(name));
        }
    }
}
