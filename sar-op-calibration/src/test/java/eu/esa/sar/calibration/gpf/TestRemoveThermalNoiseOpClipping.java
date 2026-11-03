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

import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.datamodel.metadata.AbstractMetadataIO;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import java.io.File;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Negative-value handling (ESA MPC-0392 v2.3 section 6.2) and noise re-introduction of
 * {@link Sentinel1RemoveThermalNoiseOp}, on the committed synthetic TOPS SLC. Pixel 0 has no noise annotation
 * (passes through); pixel 1 has noise of about 0.54. Clipping is tested with i = q = 0.1 (|DN|^2 = 0.02 below the
 * noise), re-introduction with the increasing int16 fixture (pixel 1 |DN|^2 = 2).
 */
public class TestRemoveThermalNoiseOpClipping {

    @Test
    public void clipFloorZeroGivesEsaEq2() throws Exception {
        final float[] v = read(process(createLowPowerProduct(), Map.of("clipFloorValue", 0.0)), "Intensity_IW1_VH");
        assertEquals(0.0f, v[1], 0f);
    }

    @Test
    public void defaultFloorIsUnchanged() throws Exception {
        final float[] v = read(process(createLowPowerProduct(), Map.of()), "Intensity_IW1_VH");
        assertEquals(Sentinel1RemoveThermalNoiseOp.trgFloorValue, v[1], 0f);
    }

    @Test
    public void noClippingKeepsNegatives() throws Exception {
        final float[] v = read(process(createLowPowerProduct(), Map.of("clipNegativeValues", false)), "Intensity_IW1_VH");
        assertTrue("pixel 1 = " + v[1], v[1] < 0f);
    }

    @Test
    public void reintroduceUncalibratedAddsNoise() throws Exception {
        final Product removed = process(createTOPSSLCProduct(), Map.of("clipNegativeValues", false));
        final Product restored = process(removed, Map.of("removeThermalNoise", false, "reIntroduceThermalNoise", true));
        final float[] v = read(restored, "Intensity_IW1_VH");
        assertEquals(0.0, v[0], 1e-3);     // |DN|^2 of pixel 0
        assertEquals(2.0, v[1], 1e-3);     // |DN|^2 of pixel 1
    }

    @Test
    public void bothFlagsFalseLeavesImageUnchanged() throws Exception {
        final float[] v = read(process(createTOPSSLCProduct(),
                Map.of("removeThermalNoise", false, "outputNoise", true)), "Intensity_IW1_VH");
        assertEquals(2.0, v[1], 1e-6);                 // |DN|^2 of pixel 1, no noise subtracted
    }

    @Test
    public void noiseBandIsPositiveWhenReintroducing() throws Exception {
        final Product removed = process(createTOPSSLCProduct(), Map.of("clipNegativeValues", false));
        final Product restored = process(removed, Map.of("removeThermalNoise", false,
                "reIntroduceThermalNoise", true, "outputNoise", true));
        final float[] nep = read(restored, "NEP_IW1_VH");
        final float[] nepRemoved = read(process(createTOPSSLCProduct(), Map.of("outputNoise", true)), "NEP_IW1_VH");
        assertEquals(nepRemoved[1], nep[1], 1e-4);
    }

    @Test
    public void clipFloorZeroIsNotNoData() throws Exception {
        // ESA Eq-2 clips to 0; with a no-data value of 0 the clipped pixels would vanish from later averaging
        final Product src = createLowPowerProduct();
        src.getBand("i_IW1_VH").setNoDataValue(0);
        src.getBand("q_IW1_VH").setNoDataValue(0);
        final Product trg = process(src, Map.of("clipFloorValue", 0.0));
        final org.esa.snap.core.datamodel.Band band = trg.getBand("Intensity_IW1_VH");
        final float[] v = read(trg, "Intensity_IW1_VH");
        assertEquals(0.0f, v[1], 0f);
        assertTrue("clipped pixel flagged as no-data", !band.isNoDataValueUsed() || band.getNoDataValue() != v[1]);
    }

    @Test
    public void sourceNoDataIsWrittenAsTargetNoDataOnFloorCollision() throws Exception {
        // floor 0 == source no-data 0: the target no-data becomes NaN, and source no-data pixels must be written
        // as NaN (not as 0, which would now read as a valid clipped pixel)
        final Product src = createTOPSSLCProduct();          // pixel 0: i = q = 0 = source no-data
        src.getBand("i_IW1_VH").setNoDataValue(0);
        src.getBand("q_IW1_VH").setNoDataValue(0);
        final Product trg = process(src, Map.of("clipFloorValue", 0.0));
        assertTrue(Double.isNaN(trg.getBand("Intensity_IW1_VH").getNoDataValue()));
        final float[] v = read(trg, "Intensity_IW1_VH");
        assertTrue("pixel 0 = " + v[0], Float.isNaN(v[0]));
    }

    @Test
    public void noiseBandNoDataIsNaN() throws Exception {
        // 0 dB is a valid noise level, so the noise band must not inherit the image no-data (0 for int16 input)
        final Product trg = process(createTOPSSLCProduct(), Map.of("outputNoise", true));
        assertTrue(Double.isNaN(trg.getBand("NEP_IW1_VH").getNoDataValue()));
    }

    private static float[] read(final Product p, final String band) throws Exception {
        final float[] v = new float[2];
        p.getBand(band).readPixels(0, 0, 2, 1, v, ProgressMonitor.NULL);
        return v;
    }

    private static Product process(final Product src, final Map<String, Object> params) {
        final Sentinel1RemoveThermalNoiseOp op = new Sentinel1RemoveThermalNoiseOp();
        op.setSourceProduct(src);
        params.forEach(op::setParameter);
        return op.getTargetProduct();
    }

    private static Product createLowPowerProduct() throws Exception {
        final Product p = createTOPSSLCProduct();
        for (String name : new String[]{"i_IW1_VH", "q_IW1_VH"}) {
            final org.esa.snap.core.datamodel.Band old = p.getBand(name);
            final String unit = old.getUnit();
            p.removeBand(old);
            final float[] data = new float[10 * 148];
            java.util.Arrays.fill(data, 0.1f);
            final org.esa.snap.core.datamodel.Band b = new org.esa.snap.core.datamodel.Band(name, ProductData.TYPE_FLOAT32, 10, 148);
            b.setUnit(unit);
            b.setNoDataValue(-9999);
            b.setData(ProductData.createInstance(data));
            p.addBand(b);
        }
        return p;
    }

    private static Product createTOPSSLCProduct() throws Exception {
        final int w = 10, h = 148;
        final Product srcProduct = TestUtils.createProduct("SLC", w, h);
        TestUtils.createBand(srcProduct, "i_IW1_VH", ProductData.TYPE_INT16, Unit.REAL, w, h, true);
        TestUtils.createBand(srcProduct, "q_IW1_VH", ProductData.TYPE_INT16, Unit.IMAGINARY, w, h, true);
        AbstractMetadataIO.Load(srcProduct, srcProduct.getMetadataRoot(), new File("src/test/resources/metadata.xml"));
        return srcProduct;
    }
}
