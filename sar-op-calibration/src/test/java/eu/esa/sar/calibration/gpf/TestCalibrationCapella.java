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
package eu.esa.sar.calibration.gpf;

import com.bc.ceres.annotation.STTM;
import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.TiePointGrid;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Capella annotates the radiometric quantity of each product ({@code collect.image.radiometry}):
 * SLCs are beta0, GEO/GEC are sigma0. The calibrator used to treat every product as sigma0, so a
 * beta0 SLC came out as Sigma0 = beta0 and, once Beta0 bands were derived from sigma0,
 * Beta0 = beta0 / sin(theta): Terrain-Flattening 1.5 to 3 dB too high.
 */
public class TestCalibrationCapella {

    private static final int W = 8;
    private static final int H = 4;
    private static final float I = 0.3f;
    private static final float Q = 0.4f;     // |DN|^2 = 0.25

    @Test

    @STTM("SNAP-4377")
    public void testBetaNoughtSlcKeepsBeta0AndDerivesSigma0() throws Exception {
        final Product target = calibrate(slc("beta_nought"));
        final TiePointGrid inc = target.getTiePointGrid("incident_angle");
        for (int x = 0; x < W; x++) {
            final double sinTheta = Math.sin(Math.toRadians(inc.getPixelDouble(x, 1)));
            assertEquals("Beta0 at x=" + x, 0.25, sample(target, "Beta0_HH", x), 1e-6);
            assertEquals("Sigma0 at x=" + x, 0.25 * sinTheta, sample(target, "Sigma0_HH", x), 1e-6);
        }
    }

    @Test

    @STTM("SNAP-4377")
    public void testSigmaNoughtProductIsSigma0() throws Exception {
        final Product target = calibrate(slc("sigma_nought"));
        final TiePointGrid inc = target.getTiePointGrid("incident_angle");
        for (int x = 0; x < W; x++) {
            final double sinTheta = Math.sin(Math.toRadians(inc.getPixelDouble(x, 1)));
            assertEquals("Sigma0 at x=" + x, 0.25, sample(target, "Sigma0_HH", x), 1e-6);
            assertEquals("Beta0 at x=" + x, 0.25 / sinTheta, sample(target, "Beta0_HH", x), 1e-6);
        }
    }

    @Test

    @STTM("SNAP-4377")
    public void testMissingRadiometryOnSlcIsBeta0() throws Exception {
        final Product target = calibrate(slc(null));
        assertEquals(0.25, sample(target, "Beta0_HH", 3), 1e-6);
    }

    private static Product calibrate(final Product source) {
        final CalibrationOp op = (CalibrationOp) new CalibrationOp.Spi().createOperator();
        op.setSourceProduct(source);
        op.setParameter("outputSigmaBand", true);
        op.setParameter("outputBetaBand", true);
        return op.getTargetProduct();
    }

    private static double sample(final Product product, final String bandName, final int x) throws Exception {
        final Band band = product.getBand(bandName);
        assertNotNull(bandName, band);
        final float[] v = new float[1];
        band.readPixels(x, 1, 1, 1, v, ProgressMonitor.NULL);
        return v[0];
    }

    private static Product slc(final String radiometry) {
        final Product product = TestUtils.createProduct("SLC", W, H);
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        absRoot.setAttributeString(AbstractMetadata.MISSION, "Capella");
        absRoot.setAttributeString(AbstractMetadata.SAMPLE_TYPE, "COMPLEX");
        absRoot.setAttributeString(AbstractMetadata.mds1_tx_rx_polar, "HH");
        absRoot.setAttributeDouble(AbstractMetadata.radar_frequency, 9650.0);
        absRoot.setAttributeDouble(AbstractMetadata.incidence_near, 25.0);
        absRoot.setAttributeDouble(AbstractMetadata.incidence_far, 40.0);

        if (radiometry != null) {
            final MetadataElement image = new MetadataElement("image");
            image.setAttributeString("radiometry", radiometry);
            final MetadataElement collect = new MetadataElement("collect");
            collect.addElement(image);
            final MetadataElement productMetadata = new MetadataElement("ProductMetadata");
            productMetadata.addElement(collect);
            AbstractMetadata.addOriginalProductMetadata(product.getMetadataRoot()).addElement(productMetadata);
        }

        addConstantBand(product, "i_HH", Unit.REAL, I);
        addConstantBand(product, "q_HH", Unit.IMAGINARY, Q);

        final float[] inc = new float[W * 2];
        for (int r = 0; r < 2; r++) {
            for (int c = 0; c < W; c++) {
                inc[r * W + c] = 25.0f + 2.0f * c;
            }
        }
        product.addTiePointGrid(new TiePointGrid("incident_angle", W, 2, 0.5, 0.5, 1, H - 1, inc));
        return product;
    }

    private static void addConstantBand(final Product product, final String name, final String unit,
                                        final float value) {
        final Band band = new Band(name, ProductData.TYPE_FLOAT32, W, H);
        band.setUnit(unit);
        final float[] data = new float[W * H];
        java.util.Arrays.fill(data, value);
        band.setData(ProductData.createInstance(data));
        product.addBand(band);
    }
}
