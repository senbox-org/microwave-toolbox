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
 * Unit test for IEMMultiAngleInverOperator.
 *
 * The 2x3 test products are built from the shipped LUT auxdata/sm_luts/IEMC_cb_nt_RDC_RSAT2_QP.mat
 * (columns rms, RDC, sigmaHH, sigmaVV; 13252 rows per integer incidence angle starting at 18 deg).
 * For every polarization combination each pixel's two sigmas are copied verbatim from one LUT row
 * (row index given per case) in the angle sections of the AM and PM incidence angles. Each row was
 * chosen so that no row with different (rms, RDC) lies within 0.01 dB of it (the LUT contains exact
 * duplicate rows, which are harmless). An exact search must therefore return that row's rms and RDC.
 */
public class TestIEMMultiAngleInverOp {

    private final static OperatorSpi spi = new IEMMultiAngleInverOp.Spi();
    // target rms and RDC bands are float32
    private final static double epsilon = 1.0e-4d;

    final private int rows = 3;
    final private int cols = 2;

    private static final double[] THETA_AM = {18.1, 21.2, 18.8, 20.0, 19.6, 19.3};
    private static final double[] THETA_PM = {30.3, 31.4, 30.9, 20.2, 32.4, 30.6};

    private static final double[] CLAY = {13.0, 23.0, 3.0, 3.0, 99.0, 23.0};
    private static final double[] SAND = {15.0, 83.0, 43.0, 98.0, 26.0, 75.0};

    private enum POL {
        // LUT rows 4661, 8351, 10778, 12067, 12489, 7365
        HH1HH2("HH1-HH2", true, true,
                new double[]{-4.8333, -9.5035, -4.6291, -18.962, -28.991, -3.8821},
                new double[]{-9.0497, -12.074, -5.8613, -18.962, -21.885, -7.6281},
                new double[]{0.5, 1.0, 1.5, 2.0, 2.2, 0.8},
                new double[]{33.64, 4.0804, 32.672656, 23.775376, 14.318656, 24.6016},
                new double[]{1.5, 1.4, 1.3, 2.0, 2.2, 0.8},
                new double[]{19.889738, 25.801658, 20.083206, 23.775376, 14.318656, 24.6016}),
        // LUT rows 4642, 8435, 10728, 12090, 12573, 7161
        HH1VV2("HH1-VV2", true, false,
                new double[]{-5.9764, -3.8177, -6.3562, -19.711, -27.549, -4.3628},
                new double[]{-7.2708, -4.88, -6.8404, -19.213, -18.966, -5.8159},
                new double[]{0.5, 1.0, 1.5, 2.0, 2.2, 0.8},
                new double[]{17.673616, 23.775376, 13.69, 16.289296, 33.64, 19.114384},
                new double[]{0.5, 1.0, 1.5, 2.0, 1.16, 0.8},
                new double[]{17.673616, 23.775376, 13.69, 16.289296, 18.108534, 19.114384}),
        // LUT rows 4034, 8700, 10632, 12162, 12531, 6929
        VV1VV2("VV1-VV2", false, false,
                new double[]{-10.365, -2.9008, -4.7665, -17.824, -27.406, -7.2238},
                new double[]{-12.958, -4.6232, -5.4695, -17.824, -19.562, -9.6107},
                new double[]{0.5, 1.0, 1.5, 2.0, 2.2, 0.8},
                new double[]{4.0804, 27.164944, 25.441936, 32.672656, 24.6016, 5.9536},
                new double[]{1.5, 1.0, 1.5, 1.2, 2.2, 0.3},
                new double[]{23.166947, 27.164944, 25.441936, 17.448496, 24.6016, 16.9744}),
        // LUT rows 4666, 8352, 10647, 12052, 12406, 7068
        VV1HH2("VV1-HH2", false, true,
                new double[]{-3.5303, -8.6304, -8.0881, -19.774, -28.865, -3.0426},
                new double[]{-8.8654, -11.652, -9.4699, -20.225, -21.986, -7.5725},
                new double[]{0.5, 1.0, 1.5, 2.0, 2.2, 0.8},
                new double[]{38.6884, 4.426816, 7.246864, 13.075456, 13.69, 25.441936},
                new double[]{1.5, 1.4, 1.6, 2.0, 2.2, 1.44},
                new double[]{12.776214, 19.628531, 10.7584, 13.075456, 13.69, 15.425507});

        final String sigmaPol;
        final boolean amIsHH, pmIsHH;
        final double[] sigmaAM, sigmaPM;
        final double[] exactRMS, exactRDC;
        // Default N=5 nearest neighbours + 5x5 RDC outlier filter (threshold 0.5, outliers replaced by the
        // neighbourhood mean). Values from an independent brute-force implementation of the documented
        // algorithm (IEMInverBase.getBestKDTreeNeighbour) over the same LUT. Note the javaml KDTree keeps
        // only the first inserted (lowest) LUT row for duplicate sigma keys, so neighbours are distinct keys.
        final double[] filteredRMS, filteredRDC;

        POL(String sigmaPol, boolean amIsHH, boolean pmIsHH, double[] sigmaAM, double[] sigmaPM,
            double[] exactRMS, double[] exactRDC, double[] filteredRMS, double[] filteredRDC) {
            this.sigmaPol = sigmaPol;
            this.amIsHH = amIsHH;
            this.pmIsHH = pmIsHH;
            this.sigmaAM = sigmaAM;
            this.sigmaPM = sigmaPM;
            this.exactRMS = exactRMS;
            this.exactRDC = exactRDC;
            this.filteredRMS = filteredRMS;
            this.filteredRDC = filteredRDC;
        }
    }

    @Test
    public void testSpiCreatesOperator() {
        final IEMMultiAngleInverOp op = (IEMMultiAngleInverOp) spi.createOperator();
        assertNotNull(op);
    }

    @Test
    public void testOperatorMetadata() {
        final OperatorMetadata md = IEMMultiAngleInverOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("IEM-Multi-Angle-Inversion", md.alias());
    }

    @Test
    public void testCreate() {
        Product srcProduct = createOneTestProduct(POL.HH1VV2, 10, 10, false, false);

        IEMMultiAngleInverOp op = (IEMMultiAngleInverOp) spi.createOperator();
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

    /**
     * Creates a test product; the sigma of the polarization not used by the given combination is zero.
     */
    private static Product createOneTestProduct(final POL pol, final int w, final int h, final boolean clay, final boolean sand) {

        final Product product = TestUtils.createProduct("backscatter and LIA", w, h);
        final double[] zeros = new double[w * h];

        // AM image
        addBand(product, "Sigma0_HH_sec2_11Oct2013", Unit.INTENSITY_DB, pol.amIsHH ? pol.sigmaAM : zeros);
        addBand(product, "Sigma0_VV_ref_11Oct2013", Unit.INTENSITY_DB, pol.amIsHH ? zeros : pol.sigmaAM);
        addBand(product, "incidenceAngleFromEllipsoid_sec4_11Oct2013", Unit.DEGREES, THETA_AM);
        addBand(product, "Sigma0_HV_sec3_11Oct2103", Unit.INTENSITY_DB, zeros);
        addBand(product, "Sigma0_VH_sec1_11Oct2103", Unit.INTENSITY_DB, zeros);

        // PM image
        addBand(product, "Sigma0_HH_sec7_11Oct2013", Unit.INTENSITY_DB, pol.pmIsHH ? pol.sigmaPM : zeros);
        addBand(product, "Sigma0_VV_sec5_11Oct2013", Unit.INTENSITY_DB, pol.pmIsHH ? zeros : pol.sigmaPM);
        addBand(product, "incidenceAngleFromEllipsoid_sec9_11Oct2013", Unit.DEGREES, THETA_PM);
        addBand(product, "Sigma0_HV_sec8_11Oct2103", Unit.INTENSITY_DB, zeros);
        addBand(product, "Sigma0_VH_sec6_11Oct2103", Unit.INTENSITY_DB, zeros);

        final MetadataElement metadata0 = AbstractMetadata.getAbstractedMetadata(product);
        metadata0.setAttributeDouble(AbstractMetadata.incidence_near, 18.1d);
        metadata0.setAttributeDouble(AbstractMetadata.incidence_far, 21.2d);

        final MetadataElement secMetadata = AbstractMetadata.getSecondaryMetadata(product.getMetadataRoot());
        final MetadataElement metadata1 = new MetadataElement("sec");
        metadata1.setAttributeDouble(AbstractMetadata.incidence_near, 20.2d);
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
        for (POL pol : POL.values()) {
            final Product target = runInversion(pol, true, true, 1);
            assertBand(pol + " rms", target, "rms", pol.exactRMS);
            assertBand(pol + " RDC", target, "RDC", pol.exactRDC);
            assertPassThroughBands(target, true, true);
        }
        // without soil-texture bands nothing is passed through and the inversion is unchanged
        final Product target = runInversion(POL.HH1HH2, false, false, 1);
        assertBand("no texture RDC", target, "RDC", POL.HH1HH2.exactRDC);
        assertPassThroughBands(target, false, false);
    }

    /**
     * Default parameters (N=5 nearest neighbours) also run the spatial RDC outlier filter.
     */
    @Test
    public void testDefaultInversionWithOutlierFilter() throws Exception {
        for (POL pol : POL.values()) {
            final Product target = runInversion(pol, true, true, null);
            assertBand(pol + " rms", target, "rms", pol.filteredRMS);
            assertBand(pol + " RDC", target, "RDC", pol.filteredRDC);
            assertPassThroughBands(target, true, true);
        }
    }

    private Product runInversion(final POL pol, final boolean clay, final boolean sand, final Integer n) {
        final Product sourceProduct = createOneTestProduct(pol, cols, rows, clay, sand);
        final Path resourcePath = ResourceInstaller.findModuleCodeBasePath(IEMInverBase.class).resolve("auxdata/sm_luts");

        final IEMMultiAngleInverOp op = (IEMMultiAngleInverOp) spi.createOperator();
        op.setSourceProduct(sourceProduct);
        op.setLUTFile(resourcePath.resolve("IEMC_cb_nt_RDC_RSAT2_QP.mat").toFile().getAbsolutePath());
        op.setSigmaPol(pol.sigmaPol);
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
            assertBand("clay", target, "AAFC Canada Clay Pct", CLAY);
        }
        if (sand) {
            assertBand("sand", target, "AAFC Canada Sand Pct", SAND);
        }
    }

    private void assertBand(final String msg, final Product target, final String bandName, final double[] expected) throws Exception {
        final Band band = target.getBand(bandName);
        assertNotNull(msg, band);
        final double[] actual = new double[cols * rows];
        band.readPixels(0, 0, cols, rows, actual, ProgressMonitor.NULL);
        assertArrayEquals(msg, expected, actual, epsilon);
    }
}
