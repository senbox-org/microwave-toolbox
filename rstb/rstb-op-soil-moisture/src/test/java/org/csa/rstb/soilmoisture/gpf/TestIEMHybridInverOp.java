/*
 * Copyright (C) 2019 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package org.csa.rstb.soilmoisture.gpf;

import com.bc.ceres.core.ProgressMonitor;
import org.csa.rstb.soilmoisture.gpf.support.IEMInverBase;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.util.ResourceInstaller;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import java.nio.file.Path;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit test for IEMHybridInverOperator.
 *
 * The 2x3 test product is built from the shipped LUT auxdata/sm_luts/IEM_cb_nt_RDC_RSAT2_QP.mat
 * (columns rms, cl, RDC, sigmaHH, sigmaVV; 13252 rows per integer incidence angle starting at 18 deg).
 * Each pixel's four sigmas are copied verbatim from one LUT row (row index given per pixel) in the
 * angle sections of the AM and PM incidence angles, and each row was chosen so that no other row
 * lies within 0.05 dB of it. An exact search must therefore return that row's rms, cl and RDC.
 */
public class TestIEMHybridInverOp {

    private final static OperatorSpi spi = new IEMHybridInverOp.Spi();
    // target rms and RDC bands are float32
    private final static double epsilon = 1.0e-4d;

    final private int rows = 3;
    final private int cols = 2;

    // LUT rows used per pixel:                     1508,    4200,    6900,    9100,    11300,   0
    private static final double[] SIGMA_HH_AM = {-12.117, -8.6295, -5.8998, -4.8353, -17.865, -15.056};
    private static final double[] SIGMA_VV_AM = {-11.082, -7.5433, -4.6176, -4.072, -18.528, -14.331};
    private static final double[] THETA_AM = {18.1, 21.2, 19.0, 20.0, 20.3, 19.3};
    private static final double[] SIGMA_HH_PM = {-19.379, -13.289, -7.5829, -6.7779, -14.06, -18.13};
    private static final double[] SIGMA_VV_PM = {-16.604, -11.006, -4.8143, -4.887, -14.08, -16.378};
    private static final double[] THETA_PM = {30.3, 31.4, 30.9, 32.2, 32.4, 30.6};

    // rms, cl, RDC of the LUT rows above
    private static final double[] EXACT_RMS = {0.3, 0.5, 0.8, 1.1, 1.7, 0.3};
    private static final double[] EXACT_CL = {13.0, 4.6, 1.4, 3.8, 2.2, 1.0};
    private static final double[] EXACT_RDC = {10.214416, 5.9536, 20.6116, 14.961424, 11.888704, 3.125824};

    // Default N=5 nearest neighbours + 5x5 RDC outlier filter (threshold 0.5, outliers replaced by the
    // neighbourhood mean). Values from an independent brute-force implementation of the documented
    // algorithm (IEMInverBase.getBestKDTreeNeighbour) over the same LUT; pixels 1, 2 and 5 are outliers.
    // The javaml KDTree keeps only the first inserted (lowest) LUT row for duplicate sigma keys.
    private static final double[] FILTERED_RMS = {0.3, 0.4, 0.78, 1.1, 1.7, 0.88};
    private static final double[] FILTERED_CL = {13.0, 3.4, 4.92, 3.8, 2.2, 5.0};
    private static final double[] FILTERED_RDC = {10.214416, 7.246864, 9.228794, 14.961424, 11.888704, 12.725949};

    private static final double[] CLAY = {13.0, 23.0, 3.0, 3.0, 99.0, 23.0};
    private static final double[] SAND = {15.0, 83.0, 43.0, 98.0, 26.0, 75.0};

    @Test
    public void testSpiCreatesOperator() {
        final IEMHybridInverOp op = (IEMHybridInverOp) spi.createOperator();
        assertNotNull(op);
    }

    @Test
    public void testOperatorMetadata() {
        final OperatorMetadata md = IEMHybridInverOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("IEM-Hybrid-Inversion", md.alias());
    }

    @Test
    public void testCreate() {
        Product srcProduct = createOneTestProduct(10, 10, false, false);

        IEMHybridInverOp op = (IEMHybridInverOp) spi.createOperator();
        op.setSourceProduct(srcProduct);
        Product trgProduct = op.getTargetProduct();

        assertNotNull(trgProduct);
        assertTrue(trgProduct.containsBand("RDC"));
    }

    private static void addBand(final Product prod, final String name, final String unit, final double[] values) {
        final Band band = prod.addBand(name, ProductData.TYPE_FLOAT64);
        band.setUnit(unit);
        final double[] data = new double[prod.getSceneRasterWidth() * prod.getSceneRasterHeight()];
        System.arraycopy(values, 0, data, 0, Math.min(values.length, data.length));
        band.setData(ProductData.createInstance(data));
    }

    private static Product createOneTestProduct(final int w, final int h, final boolean clay, final boolean sand) {

        final Product product = TestUtils.createProduct("backscatter and LIA", w, h);
        final double[] zeros = new double[w * h];

        // AM image
        addBand(product, "Sigma0_HH_sec2_11Oct2013", Unit.INTENSITY_DB, SIGMA_HH_AM);
        addBand(product, "Sigma0_VV_ref_11Oct2013", Unit.INTENSITY_DB, SIGMA_VV_AM);
        addBand(product, "incidenceAngleFromEllipsoid_sec4_11Oct2013", Unit.DEGREES, THETA_AM);
        addBand(product, "Sigma0_HV_sec3_11Oct2103", Unit.INTENSITY_DB, zeros);
        addBand(product, "Sigma0_VH_sec1_11Oct2103", Unit.INTENSITY_DB, zeros);

        // PM image
        addBand(product, "Sigma0_HH_sec7_11Oct2013", Unit.INTENSITY_DB, SIGMA_HH_PM);
        addBand(product, "Sigma0_VV_sec5_11Oct2013", Unit.INTENSITY_DB, SIGMA_VV_PM);
        addBand(product, "incidenceAngleFromEllipsoid_sec9_11Oct2013", Unit.DEGREES, THETA_PM);
        addBand(product, "Sigma0_HV_sec8_11Oct2103", Unit.INTENSITY_DB, zeros);
        addBand(product, "Sigma0_VH_sec6_11Oct2103", Unit.INTENSITY_DB, zeros);

        final MetadataElement metadata0 = AbstractMetadata.getAbstractedMetadata(product);
        metadata0.setAttributeDouble(AbstractMetadata.incidence_near, 18.1d);
        metadata0.setAttributeDouble(AbstractMetadata.incidence_far, 21.2d);

        final MetadataElement secMetadata = AbstractMetadata.getSecondaryMetadata(product.getMetadataRoot());
        final MetadataElement metadata1 = new MetadataElement("sec");
        metadata1.setAttributeDouble(AbstractMetadata.incidence_near, 30.3d);
        metadata1.setAttributeDouble(AbstractMetadata.incidence_far, 32.4d);
        secMetadata.addElement(metadata1);

        if (clay) {
            addBand(product, "AAFC Canada Clay Pct", Unit.REAL, CLAY);
        }
        if (sand) {
            addBand(product, "AAFC Canada Sand Pct", Unit.REAL, SAND);
        }
        return product;
    }

    /**
     * With N=1 the inversion is a pure LUT lookup: sigmas copied from a LUT row must give back that row.
     */
    @Test
    public void testExactLUTRowsAreInverted() throws Exception {
        for (boolean clay : new boolean[]{false, true}) {
            for (boolean sand : new boolean[]{false, true}) {
                final Product target = runInversion(clay, sand, 1);
                assertBand(target, "rms", EXACT_RMS);
                assertBand(target, "cl", EXACT_CL);
                assertBand(target, "RDC", EXACT_RDC);
                assertPassThroughBands(target, clay, sand);
            }
        }
    }

    /**
     * Default parameters (N=5 nearest neighbours) also run the spatial RDC outlier filter.
     */
    @Test
    public void testDefaultInversionWithOutlierFilter() throws Exception {
        final Product target = runInversion(true, true, null);
        assertBand(target, "rms", FILTERED_RMS);
        assertBand(target, "cl", FILTERED_CL);
        assertBand(target, "RDC", FILTERED_RDC);
        assertPassThroughBands(target, true, true);
    }

    private Product runInversion(final boolean clay, final boolean sand, final Integer n) {
        final Product sourceProduct = createOneTestProduct(cols, rows, clay, sand);
        final Path resourcePath = ResourceInstaller.findModuleCodeBasePath(IEMInverBase.class).resolve("auxdata/sm_luts");

        final IEMHybridInverOp op = (IEMHybridInverOp) spi.createOperator();
        op.setSourceProduct(sourceProduct);
        op.setLUTFile(resourcePath.resolve("IEM_cb_nt_RDC_RSAT2_QP.mat").toFile().getAbsolutePath());
        op.setOptionalOutputs(true);
        if (n != null) {
            op.setParameter("N", n);
        }
        return op.getTargetProduct();
    }

    private void assertPassThroughBands(final Product target, final boolean clay, final boolean sand) throws Exception {
        assertEquals(clay, target.containsBand("AAFC Canada Clay Pct"));
        assertEquals(sand, target.containsBand("AAFC Canada Sand Pct"));
        if (clay) {
            assertBand(target, "AAFC Canada Clay Pct", CLAY);
        }
        if (sand) {
            assertBand(target, "AAFC Canada Sand Pct", SAND);
        }
    }

    private void assertBand(final Product target, final String bandName, final double[] expected) throws Exception {
        final Band band = target.getBand(bandName);
        assertNotNull(bandName, band);
        final double[] actual = new double[cols * rows];
        band.readPixels(0, 0, cols, rows, actual, ProgressMonitor.NULL);
        assertArrayEquals(bandName, expected, actual, epsilon);
    }
}
