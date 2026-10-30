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
package org.csa.rstb.calibration;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.calibration.gpf.CalibrationOp;
import eu.esa.sar.calibration.gpf.RCMRemoveThermalNoiseOp;
import eu.esa.sar.calibration.gpf.support.Calibrator;
import eu.esa.sar.calibration.gpf.support.CalibratorRegistry;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link RCMCalibrator}.
 */
public class TestRCMCalibrator {

    @Test
    public void testDefaultConstructor() {
        final RCMCalibrator cal = new RCMCalibrator();
        assertNotNull(cal);
    }

    @Test
    public void testSupportedMissionsIsExactlyRCM() {
        final String[] missions = new RCMCalibrator().getSupportedMissions();
        assertNotNull(missions);
        assertArrayEquals("RCMCalibrator should declare exactly mission RCM",
                new String[] { "RCM" }, missions);
    }

    @Test
    public void testSetExternalAuxFileRejectsNonNullFile() {
        final RCMCalibrator cal = new RCMCalibrator();
        try {
            cal.setExternalAuxFile(new File("dummy.xml"));
            fail("RCMCalibrator must reject an external auxiliary file");
        } catch (OperatorException expected) {
            assertTrue("Error message should explain the rejection",
                    expected.getMessage().contains("RCM"));
        }
    }

    @Test
    public void testSetExternalAuxFileAcceptsNull() {
        // Passing null must always be allowed, otherwise factory construction breaks.
        new RCMCalibrator().setExternalAuxFile(null);
    }

    @Test
    public void testRegisteredInCalibratorRegistry() throws Exception {
        final Calibrator cal = CalibratorRegistry.getInstance().getCalibrator("RCM");
        assertNotNull("RCM mission must resolve to a registered calibrator", cal);
        assertTrue(cal instanceof RCMCalibrator);
    }

    @Test
    public void testRegistryLookupIsCaseInsensitive() throws Exception {
        final Calibrator cal = CalibratorRegistry.getInstance().getCalibrator("rcm");
        assertNotNull(cal);
        assertTrue(cal instanceof RCMCalibrator);
    }

    @Test
    public void testRegistryReturnsFreshInstanceEachCall() throws Exception {
        final Calibrator first = CalibratorRegistry.getInstance().getCalibrator("RCM");
        final Calibrator second = CalibratorRegistry.getInstance().getCalibrator("RCM");
        assertNotNull(first);
        assertNotNull(second);
        assertTrue("Registry must return a new instance per call", first != second);
    }

    @Test
    public void testRegisteredAsServiceProvider() {
        final Calibrator[] all = CalibratorRegistry.getInstance().getAllCalibrators();
        assertNotNull(all);
        final boolean found = Arrays.stream(all)
                .anyMatch(c -> c instanceof RCMCalibrator);
        assertTrue("RCMCalibrator must appear in CalibratorRegistry.getAllCalibrators()", found);
    }

    @Test
    public void testFirstSupportedMissionResolvesViaRegistry() throws Exception {
        final String[] missions = new RCMCalibrator().getSupportedMissions();
        for (String mission : missions) {
            final Calibrator resolved = CalibratorRegistry.getInstance().getCalibrator(mission);
            assertNotNull("Mission " + mission + " declared but not reachable through the registry",
                    resolved);
            assertEquals(RCMCalibrator.class, resolved.getClass());
        }
    }

    // Synthetic HV product: beta noise -20 dB (0.01), lutBeta and lutSigma gains A = 100, offset 0.

    @Test
    public void clippedZeroAfterNoiseRemovalIsNotNoData() throws Exception {
        // ESA Eq-2 floor 0 on a product with no-data 0: the noise op moves no-data to NaN, Calibration must keep it
        final Product src = createRcmProduct("GRD", false);
        final Band amp = TestUtils.createBand(src, "Amplitude_HV", ProductData.TYPE_FLOAT32, Unit.AMPLITUDE, W, H, false);
        fill(amp, 0.5f);                                    // DN^2 = 0.25 < noise DN^2 = 0.01 * 100 = 1
        amp.setNoDataValue(0);
        amp.setNoDataValueUsed(true);

        final RCMRemoveThermalNoiseOp noiseOp = new RCMRemoveThermalNoiseOp();
        noiseOp.setSourceProduct(src);
        noiseOp.setParameter("clipFloorValue", 0.0);
        final Product denoised = noiseOp.getTargetProduct();

        final Band sigma0 = calibrate(denoised).getBand("Sigma0_HV");
        assertNotNull(sigma0);
        final float[] v = new float[1];
        sigma0.readPixels(10, 0, 1, 1, v, ProgressMonitor.NULL);
        assertEquals(0.0f, v[0], 0f);
        assertTrue("clipped pixel flagged as no-data (no-data " + sigma0.getNoDataValue() + ")",
                !sigma0.isNoDataValueUsed() || sigma0.getNoDataValue() != v[0]);
    }

    @Test
    public void grcUsesComplexCalibration() throws Exception {
        // RCM-SP-53-0419 7.5.1: complex products including SLC and GRC: sigma0 = |DN|^2 / A^2
        final Product src = createRcmProduct("GRC", true);
        fill(TestUtils.createBand(src, "i_HV", ProductData.TYPE_FLOAT32, Unit.REAL, W, H, false), 3f);
        fill(TestUtils.createBand(src, "q_HV", ProductData.TYPE_FLOAT32, Unit.IMAGINARY, W, H, false), 4f);
        final float[] v = new float[1];
        calibrate(src).getBand("Sigma0_HV").readPixels(10, 0, 1, 1, v, ProgressMonitor.NULL);
        assertEquals(25.0 / (100 * 100), v[0], 1e-7);
    }

    @Test
    public void grdUsesDetectedCalibration() throws Exception {
        final Product src = createRcmProduct("GRD", false);
        fill(TestUtils.createBand(src, "Amplitude_HV", ProductData.TYPE_FLOAT32, Unit.AMPLITUDE, W, H, false), 5f);
        final float[] v = new float[1];
        calibrate(src).getBand("Sigma0_HV").readPixels(10, 0, 1, 1, v, ProgressMonitor.NULL);
        assertEquals(25.0 / 100, v[0], 1e-6);
    }

    private static final int W = 21, H = 4;

    private static Product calibrate(final Product src) {
        final CalibrationOp op = (CalibrationOp) new CalibrationOp.Spi().createOperator();
        op.setSourceProduct(src);
        return op.getTargetProduct();
    }

    private static void fill(final Band band, final float value) {
        final float[] data = new float[band.getRasterWidth() * band.getRasterHeight()];
        Arrays.fill(data, value);
        band.setData(ProductData.createInstance(data));
    }

    /** RCM product without image bands, with the metadata read by RCMCalibrator and the RCM noise removal. */
    private static Product createRcmProduct(final String productType, final boolean complex) {
        final Product p = TestUtils.createProduct(productType, W, H);
        final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(p);
        abs.setAttributeString(AbstractMetadata.MISSION, "RCM");
        abs.setAttributeString(AbstractMetadata.PRODUCT_TYPE, productType);
        abs.setAttributeString(AbstractMetadata.SAMPLE_TYPE, complex ? "COMPLEX" : "DETECTED");
        abs.setAttributeDouble(AbstractMetadata.radar_frequency, 5405.0);
        abs.setAttributeDouble(AbstractMetadata.range_spacing, 20.0);
        abs.setAttributeDouble(AbstractMetadata.azimuth_spacing, 20.0);

        final MetadataElement orig = AbstractMetadata.addOriginalProductMetadata(p.getMetadataRoot());
        final MetadataElement cal = new MetadataElement("calibration");
        orig.addElement(cal);
        final MetadataElement noise = new MetadataElement("noiseLevels_HV");
        final MetadataElement ref = new MetadataElement("referenceNoiseLevel");
        ref.setAttributeString("sarCalibrationType", "Beta Nought");
        ref.setAttributeString("pixelFirstNoiseValue", "0");
        ref.setAttributeString("stepSize", "10");
        ref.setAttributeString("numberOfValues", "3");
        ref.setAttributeString("noiseLevelValues", "-20 -20 -20");
        noise.addElement(ref);
        cal.addElement(noise);
        cal.addElement(lut("lutBeta_HV"));
        cal.addElement(lut("lutSigma_HV"));

        final MetadataElement spi = new MetadataElement("sarProcessingInformation");
        spi.setAttributeString("noiseSubtractionPerformed", "false");
        final MetadataElement igp = new MetadataElement("imageGenerationParameters");
        igp.addElement(spi);
        final MetadataElement product = new MetadataElement("product");
        product.addElement(igp);
        orig.addElement(product);
        return p;
    }

    private static MetadataElement lut(final String name) {
        final MetadataElement lut = new MetadataElement(name);
        lut.setAttributeString("pixelFirstLutValue", "0");
        lut.setAttributeString("stepSize", "10");
        lut.setAttributeString("numberOfValues", "3");
        lut.setAttributeString("offset", "0");
        lut.setAttributeString("gains", "100 100 100");
        return lut;
    }
}
