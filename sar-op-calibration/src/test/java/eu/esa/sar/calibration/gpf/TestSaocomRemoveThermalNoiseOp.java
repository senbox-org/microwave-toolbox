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
import eu.esa.sar.commons.test.ProcessorTest;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link SaocomRemoveThermalNoiseOp}.
 * These tests cover operator metadata and sanity checks that do not require a
 * full SAOCOM input product. End-to-end processing is exercised by integration
 * tests that provide real product data.
 */
public class TestSaocomRemoveThermalNoiseOp extends ProcessorTest {

    private final static OperatorSpi spi = new SaocomRemoveThermalNoiseOp.Spi();

    @Test
    public void testSpiCreatesOperator() {
        final SaocomRemoveThermalNoiseOp op = (SaocomRemoveThermalNoiseOp) spi.createOperator();
        assertNotNull(op);
    }

    @Test
    public void testOperatorMetadata() {
        final OperatorMetadata md = SaocomRemoveThermalNoiseOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull("SaocomRemoveThermalNoiseOp must declare @OperatorMetadata", md);
        assertEquals("SAOCOM-Thermal-Noise-Removal", md.alias());
        assertEquals("Radar/Radiometric", md.category());
    }

    @Test
    public void testInitializeWithoutSourceProductThrows() {
        final SaocomRemoveThermalNoiseOp op = (SaocomRemoveThermalNoiseOp) spi.createOperator();
        try {
            op.getTargetProduct();
            fail("Expected OperatorException when no source product is set");
        } catch (OperatorException expected) {
            // success
        } catch (RuntimeException expected) {
            // framework may wrap in other runtime exceptions
        }
    }

    @Test
    public void testInitializeWithNonSaocomProductThrows() {
        final Product product = TestUtils.createProduct("GRD", 10, 10);
        final SaocomRemoveThermalNoiseOp op = (SaocomRemoveThermalNoiseOp) spi.createOperator();
        op.setSourceProduct(product);
        try {
            op.getTargetProduct();
            fail("Expected failure when source product is not a SAOCOM product");
        } catch (OperatorException expected) {
            // success
        } catch (RuntimeException expected) {
            // framework may wrap in other runtime exceptions
        }
    }

    // ---- synthetic SAOCOM products ----

    private static final int W = 4;
    private static final int H = 3;

    private static Product createSaocom(final boolean complex) {
        final Product p = TestUtils.createProduct(complex ? "SLC" : "DI", W, H);
        final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(p);
        abs.setAttributeString(AbstractMetadata.MISSION, "SAOCOM");
        abs.setAttributeString(AbstractMetadata.SAMPLE_TYPE, complex ? "COMPLEX" : "DETECTED");
        return p;
    }

    private static void addConstantBand(final Product p, final String name, final String unit, final float value) {
        final Band band = new Band(name, ProductData.TYPE_FLOAT32, W, H);
        band.setUnit(unit);
        band.setNoDataValueUsed(true);
        band.setNoDataValue(0);
        final float[] data = new float[W * H];
        Arrays.fill(data, value);
        band.setData(ProductData.createInstance(data));
        p.addBand(band);
    }

    /** Same layout as the SAOCOM reader: Abstracted_Metadata/noise/noiseLut-*.xml holding a noiseLut vector. */
    private static void addNoiseLut(final Product p, final String pol, final String vector) {
        final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(p);
        MetadataElement noise = abs.getElement("noise");
        if (noise == null) {
            noise = new MetadataElement("noise");
            abs.addElement(noise);
        }
        final MetadataElement file = new MetadataElement("noiseLut-" + pol.toLowerCase() + ".xml");
        file.setAttributeString("noiseLut", vector);
        noise.addElement(file);
    }

    private static Product process(final Product src, final Object... params) {
        final SaocomRemoveThermalNoiseOp op = (SaocomRemoveThermalNoiseOp) spi.createOperator();
        op.setSourceProduct(src);
        for (int i = 0; i < params.length; i += 2) {
            op.setParameter((String) params[i], params[i + 1]);
        }
        return op.getTargetProduct();
    }

    private static float pixel(final Product trg, final String band) throws Exception {
        final float[] v = new float[1];
        trg.getBand(band).readPixels(1, 1, 1, 1, v, ProgressMonitor.NULL);
        return v[0];
    }

    private static String flag(final Product p) {
        return AbstractMetadata.getAbstractedMetadata(p).getAttributeString("thermal_noise_removed", null);
    }

    /**
     * SLC: |z|^2 = 3^2 + 4^2 = 25 and NESZ = 9, so |z'|^2 = 16. Both i and q are scaled by the same real factor
     * sqrt(16/25) = 0.8: i = 2.4, q = 3.2, and the phase atan2(q, i) is unchanged.
     */
    @Test
    public void testComplexInputScalesIAndQByTheSameFactor() throws Exception {
        final Product src = createSaocom(true);
        addConstantBand(src, "i_HH", Unit.REAL, 3f);
        addConstantBand(src, "q_HH", Unit.IMAGINARY, 4f);
        addNoiseLut(src, "HH", "9 9");

        final Product trg = process(src);
        final float i = pixel(trg, "i_HH");
        final float q = pixel(trg, "q_HH");
        assertEquals(2.4f, i, 1e-5f);
        assertEquals(3.2f, q, 1e-5f);
        assertEquals(Math.atan2(4, 3), Math.atan2(q, i), 1e-6);
    }

    /** SLC without a noise LUT: i and q pass through unchanged (not replaced by |z|^2). */
    @Test
    public void testComplexInputWithoutLutPassesThrough() throws Exception {
        final Product src = createSaocom(true);
        addConstantBand(src, "i_HH", Unit.REAL, 3f);
        addConstantBand(src, "q_HH", Unit.IMAGINARY, 4f);

        final Product trg = process(src);
        assertEquals(3f, pixel(trg, "i_HH"), 1e-6f);
        assertEquals(4f, pixel(trg, "q_HH"), 1e-6f);
        assertNull("no noise removed, so no flag", flag(trg));
    }

    /** Detected intensity: 10 - 4 = 6. */
    @Test
    public void testIntensityNoiseSubtraction() throws Exception {
        final Product src = createSaocom(false);
        addConstantBand(src, "Sigma0_HH", Unit.INTENSITY, 10f);
        addNoiseLut(src, "HH", "4 4");

        assertEquals(6f, pixel(process(src), "Sigma0_HH"), 1e-6f);
    }

    /** Detected intensity stays power with the intensity unit: 25 - 9 = 16. */
    @Test
    public void testIntensityStaysPower() throws Exception {
        final Product src = createSaocom(false);
        addConstantBand(src, "Intensity_HH", Unit.INTENSITY, 25f);
        addNoiseLut(src, "HH", "9 9");

        final Product trg = process(src);
        assertEquals(16f, pixel(trg, "Intensity_HH"), 1e-6f);
        assertEquals(Unit.INTENSITY, trg.getBand("Intensity_HH").getUnit());
    }

    /** Amplitude in, amplitude out: the noise is removed in power (25 - 9 = 16), then the root is taken (4). */
    @Test
    public void testAmplitudeNoiseRemovalReturnsAmplitude() throws Exception {
        final Product src = createSaocom(false);
        addConstantBand(src, "Amplitude_HH", Unit.AMPLITUDE, 5f);
        addNoiseLut(src, "HH", "9 9");

        final Product trg = process(src);
        assertEquals(4f, pixel(trg, "Amplitude_HH"), 1e-5f);
        assertEquals(Unit.AMPLITUDE, trg.getBand("Amplitude_HH").getUnit());
        assertEquals("true", flag(trg));
    }

    /** Re-introduction on amplitude: 4^2 + 9 = 25, written as amplitude 5. */
    @Test
    public void testAmplitudeNoiseReIntroductionReturnsAmplitude() throws Exception {
        final Product src = createSaocom(false);
        addConstantBand(src, "Amplitude_HH", Unit.AMPLITUDE, 4f);
        addNoiseLut(src, "HH", "9 9");
        AbstractMetadata.getAbstractedMetadata(src).setAttributeString("thermal_noise_removed", "true");

        final Product trg = process(src, "removeThermalNoise", false, "reIntroduceThermalNoise", true);
        assertEquals(5f, pixel(trg, "Amplitude_HH"), 1e-5f);
        assertEquals("false", flag(trg));
    }

    /** Amplitude without a noise LUT passes through unchanged (not squared). */
    @Test
    public void testAmplitudeWithoutLutPassesThrough() throws Exception {
        final Product src = createSaocom(false);
        addConstantBand(src, "Amplitude_HH", Unit.AMPLITUDE, 5f);

        final Product trg = process(src);
        assertEquals(5f, pixel(trg, "Amplitude_HH"), 1e-6f);
        assertNull("no noise removed, so no flag", flag(trg));
    }

    private static void setNoData(final Product p, final String band, final double noData) {
        p.getBand(band).setNoDataValue(noData);
    }

    /** A non-zero no-data value on an amplitude band is compared against the amplitude (not amp^2) and kept. */
    @Test
    public void testAmplitudeNonZeroNoDataPassesThrough() throws Exception {
        final Product src = createSaocom(false);
        addConstantBand(src, "Amplitude_HH", Unit.AMPLITUDE, -9999f);
        setNoData(src, "Amplitude_HH", -9999);
        // large enough that treating the fill as data would visibly change it (amp^2 = 99980001)
        addNoiseLut(src, "HH", "1e8 1e8");

        assertEquals(-9999f, pixel(process(src), "Amplitude_HH"), 0f);
    }

    /** A complex sample is no-data when i equals the no-data value (not |z|^2); i and q both keep it. */
    @Test
    public void testComplexNonZeroNoDataPassesThrough() throws Exception {
        final Product src = createSaocom(true);
        addConstantBand(src, "i_HH", Unit.REAL, -9999f);
        addConstantBand(src, "q_HH", Unit.IMAGINARY, -9999f);
        setNoData(src, "i_HH", -9999);
        setNoData(src, "q_HH", -9999);
        addNoiseLut(src, "HH", "1e8 1e8");

        final Product trg = process(src);
        assertEquals(-9999f, pixel(trg, "i_HH"), 0f);
        assertEquals(-9999f, pixel(trg, "q_HH"), 0f);
    }

    /** Over-subtraction on amplitude: the floor is applied in power, then the root is taken. */
    @Test
    public void testAmplitudeClipFloorAppliedInPower() throws Exception {
        final Product src = createSaocom(false);
        addConstantBand(src, "Amplitude_HH", Unit.AMPLITUDE, 2f);
        addNoiseLut(src, "HH", "9 9");

        assertEquals((float) Math.sqrt(1e-5f), pixel(process(src), "Amplitude_HH"), 1e-6f);
    }

    @Test
    public void testSelectedPolarisationsHonoured() throws Exception {
        final Product src = createSaocom(false);
        addConstantBand(src, "Sigma0_HH", Unit.INTENSITY, 10f);
        addConstantBand(src, "Sigma0_HV", Unit.INTENSITY, 5f);
        addNoiseLut(src, "HH", "4 4");
        addNoiseLut(src, "HV", "1 1");

        final Product trg = process(src, "selectedPolarisations", new String[]{"HV"});
        assertNull("HH was not selected", trg.getBand("Sigma0_HH"));
        assertNotNull(trg.getBand("Sigma0_HV"));
        assertEquals(4f, pixel(trg, "Sigma0_HV"), 1e-6f);
    }

    @Test
    public void testSelectedPolarisationsHonouredForComplexPairs() {
        final Product src = createSaocom(true);
        addConstantBand(src, "i_HH", Unit.REAL, 3f);
        addConstantBand(src, "q_HH", Unit.IMAGINARY, 4f);
        addConstantBand(src, "i_HV", Unit.REAL, 1f);
        addConstantBand(src, "q_HV", Unit.IMAGINARY, 1f);

        final Product trg = process(src, "selectedPolarisations", new String[]{"hh"});
        assertNotNull(trg.getBand("i_HH"));
        assertNotNull(trg.getBand("q_HH"));
        assertNull(trg.getBand("i_HV"));
        assertNull(trg.getBand("q_HV"));
    }

    @Test
    public void testSelectedPolarisationNotInProductIsRefused() {
        final Product src = createSaocom(false);
        addConstantBand(src, "Sigma0_HH", Unit.INTENSITY, 10f);
        try {
            process(src, "selectedPolarisations", new String[]{"VV"});
            fail("Expected refusal: no band for the selected polarisation");
        } catch (OperatorException expected) {
            // success
        }
    }

    @Test
    public void testFlagSetAfterRemovalAndClearedAfterReIntroduction() throws Exception {
        final Product src = createSaocom(false);
        addConstantBand(src, "Sigma0_HH", Unit.INTENSITY, 10f);
        addNoiseLut(src, "HH", "4 4");

        final Product removed = process(src);
        assertEquals("true", flag(removed));
        assertNull("source metadata must not change", flag(src));

        final Product restored = process(removed, "removeThermalNoise", false, "reIntroduceThermalNoise", true);
        assertEquals("false", flag(restored));
        assertEquals(10f, pixel(restored, "Sigma0_HH"), 1e-6f);
    }

    @Test
    public void testRemovingTwiceIsRefused() {
        final Product src = createSaocom(false);
        addConstantBand(src, "Sigma0_HH", Unit.INTENSITY, 10f);
        addNoiseLut(src, "HH", "4 4");
        final Product removed = process(src);
        try {
            process(removed);
            fail("Expected refusal: noise already removed");
        } catch (OperatorException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("already been removed"));
        }
    }

    @Test
    public void testReIntroducingWithoutRemovalIsRefused() {
        final Product src = createSaocom(false);
        addConstantBand(src, "Sigma0_HH", Unit.INTENSITY, 10f);
        addNoiseLut(src, "HH", "4 4");
        try {
            process(src, "removeThermalNoise", false, "reIntroduceThermalNoise", true);
            fail("Expected refusal: noise was never removed");
        } catch (OperatorException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("has not been removed"));
        }
    }

    @Test
    public void testRemoveAndReIntroduceTogetherIsRefused() {
        final Product src = createSaocom(false);
        addConstantBand(src, "Sigma0_HH", Unit.INTENSITY, 10f);
        addNoiseLut(src, "HH", "4 4");
        try {
            process(src, "removeThermalNoise", true, "reIntroduceThermalNoise", true);
            fail("Expected refusal: both remove and re-introduce selected");
        } catch (OperatorException expected) {
            // success
        }
    }
}
