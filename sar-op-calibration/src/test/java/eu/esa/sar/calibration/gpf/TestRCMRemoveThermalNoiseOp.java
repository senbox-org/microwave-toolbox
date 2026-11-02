/*
 * Copyright (C) 2025 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
import org.esa.snap.core.datamodel.TiePointGrid;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import java.util.Arrays;

import static eu.esa.sar.calibration.gpf.support.TestRCMNoiseModelSupport.load;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link RCMRemoveThermalNoiseOp}.
 * Synthetic products use the small noiseLevels/lutBeta fixtures under src/test/resources/rcm:
 * beta noise reference -30/-20/-10 dB at pixels 0/10/20, beta gain A = 100.
 */
public class TestRCMRemoveThermalNoiseOp extends ProcessorTest {

    private final static OperatorSpi spi = new RCMRemoveThermalNoiseOp.Spi();

    @Test
    public void testSpiCreatesOperator() {
        final RCMRemoveThermalNoiseOp op = (RCMRemoveThermalNoiseOp) spi.createOperator();
        assertNotNull(op);
    }

    @Test
    public void testOperatorMetadata() {
        final OperatorMetadata md = RCMRemoveThermalNoiseOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull("RCMRemoveThermalNoiseOp must declare @OperatorMetadata", md);
        assertEquals("RCM-Thermal-Noise-Removal", md.alias());
        assertEquals("Radar/Radiometric", md.category());
    }

    @Test
    public void testInitializeWithoutSourceProductThrows() {
        final RCMRemoveThermalNoiseOp op = (RCMRemoveThermalNoiseOp) spi.createOperator();
        try {
            op.getTargetProduct();
            fail("Expected OperatorException when no source product is set");
        } catch (OperatorException expected) {
            // success
        } catch (RuntimeException expected) {
            // Operator framework may wrap the failure in a different RuntimeException
        }
    }

    @Test
    public void testInitializeWithNonRcmProductThrows() {
        final Product product = TestUtils.createProduct("GRD", 10, 10);
        final RCMRemoveThermalNoiseOp op = (RCMRemoveThermalNoiseOp) spi.createOperator();
        op.setSourceProduct(product);
        try {
            op.getTargetProduct();
            fail("Expected failure when source product is not an RCM product");
        } catch (OperatorException expected) {
            // success
        } catch (RuntimeException expected) {
            // framework may wrap in other runtime exceptions
        }
    }

    @Test
    public void subtractsCalibratedNoiseInDnSquared() throws Exception {
        final Product src = createRcmGrd(21, 4, false);
        fillBand(src.getBand("Amplitude_HV"), 10f);                 // DN^2 = 100
        final Product trg = process(src);
        final float[] v = new float[1];
        trg.getBand("Intensity_HV").readPixels(10, 0, 1, 1, v, ProgressMonitor.NULL);
        // beta noise -20 dB = 0.01, A = 100 -> noise DN^2 = 1
        assertEquals(99.0, v[0], 1e-4);
    }

    @Test
    public void tileOffsetDoesNotShiftNoise() throws Exception {
        final Product src = createRcmGrd(21, 4, false);
        fillBand(src.getBand("Amplitude_HV"), 10f);
        final Product trg = process(src);
        trg.setPreferredTileSize(4, 4);                              // pixel 15 lies in a tile with x0 = 12
        final float[] one = new float[1];
        trg.getBand("Intensity_HV").readPixels(15, 1, 1, 1, one, ProgressMonitor.NULL);
        // linear interpolation of beta noise between 0.01 (x=10) and 0.1 (x=20) at x=15 -> 0.055; * A
        assertEquals(100.0 - 5.5, one[0], 1e-4);
    }

    @Test
    public void subsetGivesSameNoiseAsFullProduct() throws Exception {
        final Product full = createRcmGrd(21, 4, false);
        fillBand(full.getBand("Amplitude_HV"), 10f);
        final float[] fullValue = new float[1];
        process(full).getBand("Intensity_HV").readPixels(15, 0, 1, 1, fullValue, ProgressMonitor.NULL);

        // pixels 10..20 of the full product, with the subset offset recorded as the subset operator does
        final Product sub = createRcmGrd(11, 4, false);
        fillBand(sub.getBand("Amplitude_HV"), 10f);
        AbstractMetadata.getAbstractedMetadata(sub).setAttributeInt(AbstractMetadata.subset_offset_x, 10);
        final float[] subValue = new float[1];
        process(sub).getBand("Intensity_HV").readPixels(5, 0, 1, 1, subValue, ProgressMonitor.NULL);
        assertEquals(fullValue[0], subValue[0], 1e-4);
    }

    @Test
    public void refusesAlreadyDenoisedProduct() throws Exception {
        final Product src = createRcmGrd(21, 4, true);
        try {
            process(src);
            fail("expected OperatorException");
        } catch (OperatorException expected) {
            // success
        }
    }

    @Test
    public void refusesPolarisationWithoutNoiseLevels() throws Exception {
        final Product src = createRcmGrd(21, 4, false);
        TestUtils.createBand(src, "Amplitude_HH", ProductData.TYPE_FLOAT32, Unit.AMPLITUDE, 21, 4, false);
        try {
            process(src);
            fail("expected OperatorException: no fallback to another polarisation's noise");
        } catch (OperatorException expected) {
            // success
        }
    }

    @Test
    public void complexProducesIntensityNotScaledComponents() throws Exception {
        // i = 3, q = 4 -> |DN|^2 = 25; SLC noise = 0.01 * 100^2 = 100 -> negative -> clipped to floor
        final Product src = createRcmGrd(21, 4, false);
        src.removeBand(src.getBand("Amplitude_HV"));
        final Band i = TestUtils.createBand(src, "i_HV", ProductData.TYPE_FLOAT32, Unit.REAL, 21, 4, false);
        final Band q = TestUtils.createBand(src, "q_HV", ProductData.TYPE_FLOAT32, Unit.IMAGINARY, 21, 4, false);
        fillBand(i, 3f);
        fillBand(q, 4f);
        AbstractMetadata.getAbstractedMetadata(src).setAttributeString(AbstractMetadata.SAMPLE_TYPE, "COMPLEX");
        final Product trg = process(src);
        assertNotNull(trg.getBand("Intensity_HV"));
        assertNull(trg.getBand("q_HV"));
        final float[] v = new float[1];
        trg.getBand("Intensity_HV").readPixels(10, 0, 1, 1, v, ProgressMonitor.NULL);
        assertEquals(Sentinel1RemoveThermalNoiseOp.trgFloorValue, v[0], 0);
    }

    @Test
    public void clipFloorZeroGivesEsaEq2() throws Exception {
        final Product src = createRcmGrd(21, 4, false);
        fillBand(src.getBand("Amplitude_HV"), 0.5f);                // DN^2 = 0.25 < noise DN^2 = 1
        final RCMRemoveThermalNoiseOp op = (RCMRemoveThermalNoiseOp) spi.createOperator();
        op.setSourceProduct(src);
        op.setParameter("clipFloorValue", 0.0);
        final float[] v = new float[1];
        op.getTargetProduct().getBand("Intensity_HV").readPixels(10, 0, 1, 1, v, ProgressMonitor.NULL);
        assertEquals(0.0f, v[0], 0f);
    }

    @Test
    public void rejectsMultilookedProduct() throws Exception {
        final Product src = createRcmGrd(21, 4, false);
        final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(src);
        abs.setAttributeDouble(AbstractMetadata.range_spacing, 20.0);
        abs.setAttributeDouble(AbstractMetadata.azimuth_spacing, 20.0);
        assertNotNull("original spacing accepted", process(src));
        abs.setAttributeDouble(AbstractMetadata.range_spacing, 40.0);
        try {
            process(src);
            fail("expected OperatorException");
        } catch (OperatorException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("multilooking"));
        }
    }

    @Test
    public void bothFlagsFalseLeavesImageUnchanged() throws Exception {
        final Product src = createRcmGrd(21, 4, false);
        fillBand(src.getBand("Amplitude_HV"), 10f);
        final RCMRemoveThermalNoiseOp op = (RCMRemoveThermalNoiseOp) spi.createOperator();
        op.setSourceProduct(src);
        op.setParameter("removeThermalNoise", false);
        op.setParameter("outputNoise", true);
        final float[] v = new float[1];
        op.getTargetProduct().getBand("Intensity_HV").readPixels(10, 0, 1, 1, v, ProgressMonitor.NULL);
        assertEquals(100.0f, v[0], 1e-4);
    }

    @Test
    public void complexPixelWithZeroIIsNotNoData() throws Exception {
        // int16 SLC: I == 0 is a valid sample when Q != 0; |DN|^2 = 400, SLC noise 0.01 * 100^2 = 100
        final Product src = createRcmGrd(21, 4, false);
        src.removeBand(src.getBand("Amplitude_HV"));
        final Band i = TestUtils.createBand(src, "i_HV", ProductData.TYPE_FLOAT32, Unit.REAL, 21, 4, false);
        final Band q = TestUtils.createBand(src, "q_HV", ProductData.TYPE_FLOAT32, Unit.IMAGINARY, 21, 4, false);
        fillBand(i, 0f);
        fillBand(q, 20f);
        AbstractMetadata.getAbstractedMetadata(src).setAttributeString(AbstractMetadata.SAMPLE_TYPE, "COMPLEX");
        final float[] v = new float[1];
        process(src).getBand("Intensity_HV").readPixels(10, 0, 1, 1, v, ProgressMonitor.NULL);
        assertEquals(300.0f, v[0], 1e-3);
    }

    @Test
    public void clipFloorZeroIsNotNoData() throws Exception {
        // ESA Eq-2 clips to 0; the RCM no-data value is 0, so clipped pixels must not be flagged as no-data
        final Product src = createRcmGrd(21, 4, false);
        fillBand(src.getBand("Amplitude_HV"), 0.5f);                // DN^2 = 0.25 < noise DN^2 = 1
        src.getBand("Amplitude_HV").setNoDataValue(0);
        src.getBand("Amplitude_HV").setNoDataValueUsed(true);
        final RCMRemoveThermalNoiseOp op = (RCMRemoveThermalNoiseOp) spi.createOperator();
        op.setSourceProduct(src);
        op.setParameter("clipFloorValue", 0.0);
        final Band band = op.getTargetProduct().getBand("Intensity_HV");
        final float[] v = new float[1];
        band.readPixels(10, 0, 1, 1, v, ProgressMonitor.NULL);
        assertEquals(0.0f, v[0], 0f);
        assertTrue("clipped pixel flagged as no-data", !band.isNoDataValueUsed() || band.getNoDataValue() != v[0]);
    }

    @Test
    public void refusesCalibratedComplexInput() throws Exception {
        // complex output of Calibration holds sqrt(sigma0), not DN
        final Product src = createRcmGrd(21, 4, false);
        src.removeBand(src.getBand("Amplitude_HV"));
        TestUtils.createBand(src, "i_HV", ProductData.TYPE_FLOAT32, Unit.REAL, 21, 4, false);
        TestUtils.createBand(src, "q_HV", ProductData.TYPE_FLOAT32, Unit.IMAGINARY, 21, 4, false);
        final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(src);
        abs.setAttributeString(AbstractMetadata.SAMPLE_TYPE, "COMPLEX");
        abs.setAttributeInt(AbstractMetadata.abs_calibration_flag, 1);
        try {
            process(src);
            fail("expected OperatorException");
        } catch (OperatorException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("calibrated complex"));
        }
    }

    // beta noise at x = 10 is 0.01; incidence angle 30 deg: sigma0 noise 0.01 sin 30 = 0.005, gamma0 0.01 tan 30

    @Test
    public void sigma0InputSubtractsBetaNoiseTimesSinTheta() throws Exception {
        assertEquals(0.1 - 0.005, calibratedValue("Sigma0", false), 1e-6);
    }

    @Test
    public void gamma0InputSubtractsBetaNoiseTimesTanTheta() throws Exception {
        assertEquals(0.1 - 0.01 * Math.tan(Math.toRadians(30)), calibratedValue("Gamma0", false), 1e-6);
    }

    @Test
    public void beta0InputSubtractsBetaNoise() throws Exception {
        assertEquals(0.1 - 0.01, calibratedValue("Beta0", false), 1e-6);
    }

    @Test
    public void reIntroduceAddsCalibratedNoise() throws Exception {
        assertEquals(0.1 + 0.005, calibratedValue("Sigma0", true), 1e-6);
    }

    @Test
    public void reIntroduceAddsNoiseInDnSquared() throws Exception {
        final Product src = createRcmGrd(21, 4, true);
        fillBand(src.getBand("Amplitude_HV"), 10f);                 // DN^2 = 100, noise DN^2 = 1
        final RCMRemoveThermalNoiseOp op = (RCMRemoveThermalNoiseOp) spi.createOperator();
        op.setSourceProduct(src);
        op.setParameter("removeThermalNoise", false);
        op.setParameter("reIntroduceThermalNoise", true);
        final float[] v = new float[1];
        op.getTargetProduct().getBand("Intensity_HV").readPixels(10, 0, 1, 1, v, ProgressMonitor.NULL);
        assertEquals(101.0, v[0], 1e-4);
    }

    /** Pixel (10, 0) of a calibrated HV band holding 0.1, with noise removed or re-introduced. */
    private static double calibratedValue(final String prefix, final boolean reIntroduce) throws Exception {
        final int w = 21, h = 4;
        final Product src = createRcmGrd(w, h, reIntroduce);
        src.removeBand(src.getBand("Amplitude_HV"));
        final Band band = TestUtils.createBand(src, prefix + "_HV", ProductData.TYPE_FLOAT32, Unit.INTENSITY,
                w, h, false);
        fillBand(band, 0.1f);
        final float[] angles = new float[4];
        Arrays.fill(angles, 30f);
        src.addTiePointGrid(new TiePointGrid(OperatorUtils.TPG_INCIDENT_ANGLE, 2, 2, 0.5, 0.5, w - 1, h - 1, angles));
        AbstractMetadata.getAbstractedMetadata(src).setAttributeInt(AbstractMetadata.abs_calibration_flag, 1);

        final RCMRemoveThermalNoiseOp op = (RCMRemoveThermalNoiseOp) spi.createOperator();
        op.setSourceProduct(src);
        op.setParameter("removeThermalNoise", !reIntroduce);
        op.setParameter("reIntroduceThermalNoise", reIntroduce);
        final float[] v = new float[1];
        op.getTargetProduct().getBand(prefix + "_HV").readPixels(10, 0, 1, 1, v, ProgressMonitor.NULL);
        return v[0];
    }

    private static Product createRcmGrd(final int w, final int h, final boolean noiseRemoved) throws Exception {
        return createRcmGrd(w, h, noiseRemoved, "HV", "HV");
    }

    /**
     * @param bandPol polarisation in the band name (the reader uses RCH/RCV for compact pol)
     * @param filePol polarisation in the calibration file names (CH/CV for compact pol)
     */
    private static Product createRcmGrd(final int w, final int h, final boolean noiseRemoved,
                                        final String bandPol, final String filePol) throws Exception {
        final Product p = TestUtils.createProduct("GRD", w, h);
        TestUtils.createBand(p, "Amplitude_" + bandPol, ProductData.TYPE_FLOAT32, Unit.AMPLITUDE, w, h, true);
        final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(p);
        abs.setAttributeString(AbstractMetadata.MISSION, "RCM");
        abs.setAttributeString(AbstractMetadata.SAMPLE_TYPE, "DETECTED");
        abs.setAttributeDouble(AbstractMetadata.range_spacing, 20.0);
        abs.setAttributeDouble(AbstractMetadata.azimuth_spacing, 20.0);
        final MetadataElement orig = AbstractMetadata.addOriginalProductMetadata(p.getMetadataRoot());
        final MetadataElement cal = new MetadataElement("calibration");
        orig.addElement(cal);
        cal.addElement(load("noiseLevels_HV_small.xml", "noiseLevels_" + filePol));
        cal.addElement(load("lutBeta_HV_small.xml", "lutBeta_" + filePol));
        // same layout as a real product.xml read by the RCM reader
        final MetadataElement product = load("product_small.xml", null);
        product.getElement("imageGenerationParameters").getElement("sarProcessingInformation")
                .setAttributeString("noiseSubtractionPerformed", String.valueOf(noiseRemoved));
        orig.addElement(product);
        return p;
    }

    private static boolean outputNoiseFlag(final Product trg) {
        return Boolean.parseBoolean(AbstractMetadata.getOriginalProductMetadata(trg).getElement("product")
                .getElement("imageGenerationParameters").getElement("sarProcessingInformation")
                .getAttributeString("noiseSubtractionPerformed"));
    }

    @Test
    public void compactPolBandsUseTheirNoiseFiles() throws Exception {
        final Product src = createRcmGrd(21, 4, false, "RCH", "CH");
        fillBand(src.getBand("Amplitude_RCH"), 10f);
        final float[] v = new float[1];
        process(src).getBand("Intensity_RCH").readPixels(10, 0, 1, 1, v, ProgressMonitor.NULL);
        assertEquals(99.0, v[0], 1e-4);
    }

    @Test
    public void noiseFlagIsUnchangedWhenNothingIsRemovedOrAdded() throws Exception {
        final Product src = createRcmGrd(21, 4, true);
        final RCMRemoveThermalNoiseOp op = (RCMRemoveThermalNoiseOp) spi.createOperator();
        op.setSourceProduct(src);
        op.setParameter("removeThermalNoise", false);
        op.setParameter("outputNoise", true);
        assertTrue(outputNoiseFlag(op.getTargetProduct()));
    }

    @Test
    public void noiseFlagIsSetAfterRemoval() throws Exception {
        assertTrue(outputNoiseFlag(process(createRcmGrd(21, 4, false))));
    }

    @Test
    public void refusesSubsampledSubset() throws Exception {
        // the subset builder keeps the full-resolution spacing; only the history records the subsampling
        final Product src = createRcmGrd(21, 4, false);
        final MetadataElement history = new MetadataElement(Product.HISTORY_ROOT_NAME);
        final MetadataElement latest = new MetadataElement("SubsetInfo");
        latest.setAttributeInt("SubSampling.x", 1);
        latest.setAttributeInt("SubSampling.y", 1);
        final MetadataElement earlier = new MetadataElement("SubsetInfo");     // nested earlier subset
        earlier.setAttributeInt("SubSampling.x", 1);
        earlier.setAttributeInt("SubSampling.y", 2);
        latest.addElement(earlier);
        history.addElement(latest);
        src.getMetadataRoot().addElement(history);
        try {
            process(src);
            fail("expected OperatorException");
        } catch (OperatorException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("subsampling"));
        }
    }

    @Test
    public void subsetWithoutSubsamplingIsAccepted() throws Exception {
        final Product src = createRcmGrd(21, 4, false);
        final MetadataElement history = new MetadataElement(Product.HISTORY_ROOT_NAME);
        final MetadataElement info = new MetadataElement("SubsetInfo");
        info.setAttributeInt("SubSampling.x", 1);
        info.setAttributeInt("SubSampling.y", 1);
        history.addElement(info);
        src.getMetadataRoot().addElement(history);
        assertNotNull(process(src).getBand("Intensity_HV"));
    }

    @Test
    public void refusesCalibratedProductWithUnknownBandType() throws Exception {
        // abs_calibration_flag set but the band is not Sigma0/Gamma0/Beta0: subtracting DN^2 noise would be wrong
        final Product src = createRcmGrd(21, 4, false);
        AbstractMetadata.getAbstractedMetadata(src).setAttributeInt(AbstractMetadata.abs_calibration_flag, 1);
        try {
            process(src);
            fail("expected OperatorException");
        } catch (OperatorException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("calibration type"));
        }
    }

    @Test
    public void complexInputGivesDetectedSampleType() throws Exception {
        final Product src = createRcmGrd(21, 4, false);
        src.removeBand(src.getBand("Amplitude_HV"));
        fillBand(TestUtils.createBand(src, "i_HV", ProductData.TYPE_FLOAT32, Unit.REAL, 21, 4, false), 3f);
        fillBand(TestUtils.createBand(src, "q_HV", ProductData.TYPE_FLOAT32, Unit.IMAGINARY, 21, 4, false), 4f);
        AbstractMetadata.getAbstractedMetadata(src).setAttributeString(AbstractMetadata.SAMPLE_TYPE, "COMPLEX");
        final Product trg = process(src);
        assertEquals("DETECTED", AbstractMetadata.getAbstractedMetadata(trg).getAttributeString(AbstractMetadata.SAMPLE_TYPE));
    }

    @Test
    public void detectedSlcKeepsComplexCalibrationConvention() throws Exception {
        // intensity of an SLC written by this operator (SAMPLE_TYPE DETECTED, product type SLC): noise |DN|^2 = NE * A^2
        final Product src = createRcmGrd(21, 4, true);
        src.setProductType("SLC");
        src.removeBand(src.getBand("Amplitude_HV"));
        fillBand(TestUtils.createBand(src, "Intensity_HV", ProductData.TYPE_FLOAT32, Unit.INTENSITY, 21, 4, false), 100f);
        final RCMRemoveThermalNoiseOp op = (RCMRemoveThermalNoiseOp) spi.createOperator();
        op.setSourceProduct(src);
        op.setParameter("removeThermalNoise", false);
        op.setParameter("reIntroduceThermalNoise", true);
        final float[] v = new float[1];
        op.getTargetProduct().getBand("Intensity_HV").readPixels(10, 0, 1, 1, v, ProgressMonitor.NULL);
        assertEquals(100.0 + 0.01 * 100 * 100, v[0], 1e-3);
    }

    @Test
    public void refusesMlcProduct() throws Exception {
        final Product src = createRcmGrd(21, 4, false);
        src.removeBand(src.getBand("Amplitude_HV"));
        TestUtils.createBand(src, "C11", ProductData.TYPE_FLOAT32, Unit.INTENSITY, 21, 4, false);
        TestUtils.createBand(src, "C12_real", ProductData.TYPE_FLOAT32, Unit.REAL, 21, 4, false);
        TestUtils.createBand(src, "C12_imag", ProductData.TYPE_FLOAT32, Unit.IMAGINARY, 21, 4, false);
        TestUtils.createBand(src, "C22", ProductData.TYPE_FLOAT32, Unit.INTENSITY, 21, 4, false);
        try {
            process(src);
            fail("expected OperatorException");
        } catch (OperatorException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("RCM MLC products are not supported"));
        }
    }

    @Test
    public void noiseBandUsesNaNNoData() throws Exception {
        // the source no-data (0) is a valid noise level in dB
        final Product src = createRcmGrd(21, 4, false);
        src.getBand("Amplitude_HV").setNoDataValue(0);
        src.getBand("Amplitude_HV").setNoDataValueUsed(true);
        final RCMRemoveThermalNoiseOp op = (RCMRemoveThermalNoiseOp) spi.createOperator();
        op.setSourceProduct(src);
        op.setParameter("outputNoise", true);
        final Band nep = op.getTargetProduct().getBand(Sentinel1RemoveThermalNoiseOp.NOISE_EQUIVALENT_POWER + "_HV");
        assertNotNull(nep);
        assertTrue("noise no-data " + nep.getNoDataValue(), Double.isNaN(nep.getNoDataValue()));
        final float[] v = new float[1];
        nep.readPixels(10, 0, 1, 1, v, ProgressMonitor.NULL);
        assertEquals(0.0f, v[0], 1e-4);                             // noise DN^2 = 1 -> 0 dB, a valid value
    }

    private static void fillBand(final Band band, final float value) {
        final float[] data = new float[band.getRasterWidth() * band.getRasterHeight()];
        Arrays.fill(data, value);
        band.setData(ProductData.createInstance(data));
    }

    private static Product process(final Product src) {
        final RCMRemoveThermalNoiseOp op = (RCMRemoveThermalNoiseOp) spi.createOperator();
        op.setSourceProduct(src);
        return op.getTargetProduct();
    }
}
