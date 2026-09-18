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

import eu.esa.sar.commons.test.ProcessorTest;
import eu.esa.sar.iogdal.iceye.IceyeCOGReaderPlugIn;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.dataio.DecodeQualification;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Calibration of ICEYE products, end to end from the reader.
 * <p>
 * The Open Data GRD stores quantised DN with a GDAL SCALE item, so its amplitude
 * band carries a scaling factor. {@code IceyeCalibrator} reads the raw tile
 * buffer for speed, which used to bypass that scaling and calibrate the DN
 * instead of the amplitude - a silent factor of SCALE^2, about 2 dB here, and a
 * disagreement with {@code Intensity_VV} and every other consumer of the same
 * band. {@link #testOpenDataGrdCalibrationUsesScaledAmplitude()} pins it.
 * <p>
 * The committed COG window runs everywhere; the full-size and legacy products
 * are skipped when the shared test tree is not present.
 *
 * @author Luis Veci
 */
public class TestCalibrationIceye extends ProcessorTest {

    private final static OperatorSpi spi = new CalibrationOp.Spi();

    private static final String FIXTURE = "/eu/esa/sar/iogdal/iceye/opendata/"
            + "ICEYE_6Q31WW_20251110T182058Z_7010715_X56_SLEDF_crop_GRD.tif";

    private final static File inputLegacyGRD = new File(TestData.inputSAR
            + "Iceye/GRD/ICEYE_GRD_SM_7247_20190801T043405.tif");
    private final static File inputOpenDataSLC = new File(TestData.inputSAR
            + "Iceye/OpenData/ICEYE_6Q31WW_20251110T182058Z_7010715_X56_SLEDF_SLC.tif");

    private static File extractedWindow;

    /**
     * The fixture arrives from the sar-io-gdal test-jar, so it has to be
     * unpacked before a reader can open it. The name is kept intact - the
     * plug-in keys off the ICEYE prefix and the _GRD suffix.
     */
    private static synchronized File openDataGrdWindow() throws IOException {
        if (extractedWindow != null) {
            return extractedWindow;
        }
        final String name = FIXTURE.substring(FIXTURE.lastIndexOf('/') + 1);
        final File dir = Files.createTempDirectory("iceye-calibration").toFile();
        dir.deleteOnExit();
        final File target = new File(dir, name);
        try (InputStream in = TestCalibrationIceye.class.getResourceAsStream(FIXTURE)) {
            assertNotNull("missing fixture " + FIXTURE, in);
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        target.deleteOnExit();
        extractedWindow = target;
        return target;
    }

    private final List<Product> opened = new ArrayList<>();

    /**
     * These products are large - the SLC is 15.8 GB - and an undisposed GDAL
     * dataset survives the test that opened it. Leaving them around made
     * unrelated tests later in the same fork fail reading image metadata.
     */
    @After
    public void disposeOpenedProducts() {
        for (Product product : opened) {
            product.dispose();
        }
        opened.clear();
    }

    /**
     * The ICEYE plug-in is named explicitly rather than left to {@code ProductIO}
     * or {@code CommonReaders}. These are calibration tests: which reader SNAP
     * picks for a {@code .tif} is a separate question, and a contested one - a
     * generic GDAL driver claims the Cloud-Optimized deliveries too, and
     * {@code CommonReaders} short-cuts every {@code .tif} to the plain GeoTIFF
     * reader. Depending on either here would make these tests fail for a reason
     * that has nothing to do with calibration.
     */
    private Product read(final File file) throws IOException {
        final Product product = new IceyeCOGReaderPlugIn().createReaderInstance()
                .readProductNodes(file, null);
        assertNotNull("no reader for " + file, product);
        opened.add(product);
        return product;
    }

    private Product calibrate(final Product source) {
        final CalibrationOp op = (CalibrationOp) spi.createOperator();
        op.setSourceProduct(source);
        final Product target = op.getTargetProduct();
        assertNotNull(target);
        opened.add(target);
        return target;
    }

    private static double sample(final Band band, final int x, final int y) throws IOException {
        final double[] value = new double[1];
        band.readPixels(x, y, 1, 1, value);
        return value[0];
    }

    /** The reader has to hand the calibrator a product it recognises as ICEYE at all. */
    @Test
    public void testOpenDataGrdCalibrates() throws Exception {
        final Product source = read(openDataGrdWindow());
        final Product target = calibrate(source);

        final Band sigma0 = target.getBand("Sigma0_VV");
        assertNotNull("no Sigma0_VV in " + String.join(", ", target.getBandNames()), sigma0);
        assertEquals(Unit.INTENSITY, sigma0.getUnit());
        assertEquals(source.getSceneRasterWidth(), target.getSceneRasterWidth());
        assertEquals(source.getSceneRasterHeight(), target.getSceneRasterHeight());

        assertEquals(1, AbstractMetadata.getAbstractedMetadata(target)
                .getAttributeInt(AbstractMetadata.abs_calibration_flag));
    }

    /**
     * GRD is detected and ground range, so sigma0 is simply
     * {@code calibration_factor * amplitude^2} - with amplitude the scaled value,
     * not the stored DN.
     */
    @Test
    public void testOpenDataGrdCalibrationUsesScaledAmplitude() throws Exception {
        final Product source = read(openDataGrdWindow());
        final Band amplitude = source.getBand("Amplitude_VV");
        assertTrue("fixture should carry a GDAL SCALE", amplitude.isScalingApplied());

        final double calibrationFactor = AbstractMetadata.getAbstractedMetadata(source)
                .getAttributeDouble(AbstractMetadata.calibration_factor);

        final Product target = calibrate(source);
        final Band sigma0 = target.getBand("Sigma0_VV");

        for (int[] p : new int[][]{{0, 0}, {1, 0}, {0, 1}, {100, 200}, {255, 512}, {511, 1023}}) {
            final double scaledAmplitude = sample(amplitude, p[0], p[1]);
            final double expected = calibrationFactor * scaledAmplitude * scaledAmplitude;
            assertEquals("sigma0 at x=" + p[0] + " y=" + p[1],
                    expected, sample(sigma0, p[0], p[1]), expected * 1e-6);
        }
    }

    /** Without the scaling the result would be low by SCALE^2, so check it is not. */
    @Test
    public void testScalingIsNotSilentlyDropped() throws Exception {
        final Product source = read(openDataGrdWindow());
        final Band amplitude = source.getBand("Amplitude_VV");
        final double scale = amplitude.getScalingFactor();
        assertTrue("fixture should have a scale other than 1", Math.abs(scale - 1.0) > 0.1);

        final double calibrationFactor = AbstractMetadata.getAbstractedMetadata(source)
                .getAttributeDouble(AbstractMetadata.calibration_factor);

        final Product target = calibrate(source);
        final double sigma0 = sample(target.getBand("Sigma0_VV"), 100, 200);

        final double rawDn = sample(amplitude, 100, 200) / scale;
        final double ifScalingWereDropped = calibrationFactor * rawDn * rawDn;
        assertTrue("sigma0 " + sigma0 + " looks like the unscaled DN result",
                Math.abs(sigma0 - ifScalingWereDropped) > ifScalingWereDropped * 0.1);
        assertEquals(ifScalingWereDropped * scale * scale, sigma0, sigma0 * 1e-6);
    }

    @Test
    public void testSigma0IsFiniteAndPositive() throws Exception {
        final Product source = read(openDataGrdWindow());
        final Band sigma0 = calibrate(source).getBand("Sigma0_VV");

        final float[] samples = new float[64 * 64];
        sigma0.readPixels(200, 400, 64, 64, samples);
        boolean anyPositive = false;
        for (float value : samples) {
            assertTrue("NaN in sigma0", !Float.isNaN(value));
            assertTrue("negative sigma0 " + value, value >= 0);
            anyPositive |= value > 0;
        }
        assertTrue("sigma0 window is entirely zero", anyPositive);
    }

    /**
     * The legacy GRD stores plain DN with no scaling, so it must come through
     * exactly as before this change.
     */
    @Test
    public void testLegacyGrdIsUnaffectedByTheScalingFix() throws Exception {
        assumeTrue(inputLegacyGRD + " not found", inputLegacyGRD.exists());

        final Product source = read(inputLegacyGRD);
        final Band amplitude = source.getBand("Amplitude_VV");
        assertTrue("legacy GRD should carry no scaling", !amplitude.isScalingApplied());

        final double calibrationFactor = AbstractMetadata.getAbstractedMetadata(source)
                .getAttributeDouble(AbstractMetadata.calibration_factor);

        final Band sigma0 = calibrate(source).getBand("Sigma0_VV");
        for (int[] p : new int[][]{{0, 0}, {100, 200}, {5000, 10000}}) {
            final double dn = sample(amplitude, p[0], p[1]);
            final double expected = calibrationFactor * dn * dn;
            assertEquals("sigma0 at x=" + p[0] + " y=" + p[1],
                    expected, sample(sigma0, p[0], p[1]), expected * 1e-6);
        }
    }

    /**
     * SLC is complex and slant range, so the incidence angle enters the result;
     * this also exercises the tie point grid the reader builds.
     * <p>
     * The reader is named explicitly rather than left to {@code ProductIO}. The
     * SLC is a BigTIFF, for which GDAL's generic GTiff driver also reports
     * INTENDED, and {@code ProductIO.getProductReaderForInput} takes the first
     * INTENDED plug-in in registration order - so which reader wins is a matter
     * of ServiceLoader ordering. See {@link #testBigTiffIsContestedByTheGenericGdalDriver()}.
     */
    @Test
    public void testOpenDataSlcCalibrates() throws Exception {
        assumeTrue(inputOpenDataSLC + " not found", inputOpenDataSLC.exists());

        final Product source = read(inputOpenDataSLC);
        assertEquals("SLC-COG", source.getProductType());
        final Product target = calibrate(source);

        final Band sigma0 = target.getBand("Sigma0_VV");
        assertNotNull("no Sigma0_VV in " + String.join(", ", target.getBandNames()), sigma0);
        assertEquals(Unit.INTENSITY, sigma0.getUnit());

        final float[] samples = new float[32 * 32];
        sigma0.readPixels(source.getSceneRasterWidth() / 2, source.getSceneRasterHeight() / 2,
                32, 32, samples);
        for (float value : samples) {
            assertTrue("NaN in sigma0", !Float.isNaN(value));
            assertTrue("negative sigma0 " + value, value >= 0);
        }
    }

    /**
     * Documents a precedence conflict that is not the ICEYE reader's to settle.
     * <p>
     * For a BigTIFF, GDAL's generic GTiff driver claims INTENDED alongside the
     * ICEYE plug-in, and {@code ProductIO} simply takes the first INTENDED it
     * meets. When the generic driver wins, the product opens as band_1/band_2
     * with no SAR metadata and Calibration rejects it with "Input should be a
     * SAR product". Classic TIFFs are unaffected - there the GDAL driver reports
     * only SUITABLE, so the ICEYE plug-in wins on qualification.
     * <p>
     * The test asserts the ICEYE plug-in's own verdict, which is all this
     * repository controls; deciding that a mission reader should outrank a
     * generic driver belongs in snap-engine.
     */
    @Test
    public void testBigTiffIsContestedByTheGenericGdalDriver() {
        assumeTrue(inputOpenDataSLC + " not found", inputOpenDataSLC.exists());

        assertEquals(DecodeQualification.INTENDED,
                new IceyeCOGReaderPlugIn().getDecodeQualification(inputOpenDataSLC));
    }
}
