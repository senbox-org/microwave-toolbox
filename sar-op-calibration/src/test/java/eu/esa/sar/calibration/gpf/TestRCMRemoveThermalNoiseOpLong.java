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
import com.bc.ceres.test.LongTestRunner;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * End-to-end RCM thermal noise removal on a ScanSAR (SC50MA, HH/HV) GRD product.
 */
@RunWith(LongTestRunner.class)
public class TestRCMRemoveThermalNoiseOpLong {

    private static final File SC50 = new File(TestData.inputSAR +
            "RCM/EODMS/GRD/RCM1_OK1024567_PK1025865_1_SC50MA_20191205_225938_HH_HV_GRD/manifest.safe");

    /**
     * Burst-periodic (scalloping) modulation in HV must drop after denoising. Line power is folded by position
     * within each burst over all bursts of the far-range beam, which averages out the scene and leaves the
     * azimuth noise pattern.
     */
    @Test
    public void scanSarHvScallopingIsReduced() throws Exception {
        assumeTrue(SC50.exists());
        final Product src = ProductIO.readProduct(SC50);
        try {
            final Product trg = denoise(src, false);
            final List<int[]> bursts = beamBursts(src, "SC-8");
            final double before = foldedModulation(src.getBand("Amplitude_HV"), true, bursts);
            final double after = foldedModulation(trg.getBand("Intensity_HV"), false, bursts);
            System.out.println("folded burst modulation before=" + before + " after=" + after);
            // measured 2026-10-30: before 0.0781, after 0.0662 (ratio 0.85); a mis-centred azimuth scaling
            // vector adds burst-periodic modulation instead of removing it
            assertTrue("before=" + before + " after=" + after, after < 0.9 * before);
        } finally {
            src.dispose();
        }
    }

    private static final File CP_GRC = new File(TestData.inputSAR +
            "RCM/OpenData/Winnipeg/RCM1_OK1052791_PK1052795_1_3MCP24_20200219_123901_CH_CV_GRC/manifest.safe");
    private static final File CP_SLC = new File(TestData.inputSAR +
            "RCM/OpenData/Winnipeg/RCM1_OK1050595_PK1051816_1_3MCP24_20200219_123901_CH_CV_SLC/manifest.safe");

    /** Compact-pol bands (RCH/RCV) use the CH/CV noise files, for detected and complex products. */
    @Test
    public void compactPolProductsAreProcessed() throws Exception {
        for (File f : new File[]{CP_GRC, CP_SLC}) {
            assumeTrue(f.exists());
            final Product src = ProductIO.readProduct(f);
            try {
                final RCMRemoveThermalNoiseOp op = new RCMRemoveThermalNoiseOp();
                op.setSourceProduct(src);
                op.setParameter("outputNoise", true);
                final Product trg = op.getTargetProduct();
                final float[] v = new float[1];
                trg.getBand("NEP_RCH").readPixels(100, 100, 1, 1, v, ProgressMonitor.NULL);
                // NEP is the noise in |DN|^2 (dB): finite and not no-data
                final String name = f.getParentFile().getName();
                System.out.println(name + " NEP_RCH " + v[0] + " dB");
                assertTrue(name + " NEP " + v[0], !Float.isNaN(v[0]) && v[0] != trg.getBand("NEP_RCH").getNoDataValue()
                        && Math.abs(v[0]) < 200);
                assertTrue(trg.getBand("Intensity_RCV") != null);
            } finally {
                src.dispose();
            }
        }
    }

    /** A multilooked real product (abstracted spacing changed) is rejected by the resample guard. */
    @Test
    public void multilookedRealProductIsRejected() throws Exception {
        assumeTrue(SC50.exists());
        final Product src = ProductIO.readProduct(SC50);
        try {
            final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(src);
            abs.setAttributeDouble(AbstractMetadata.range_spacing, abs.getAttributeDouble(AbstractMetadata.range_spacing) * 4);
            final RCMRemoveThermalNoiseOp op = new RCMRemoveThermalNoiseOp();
            op.setSourceProduct(src);
            try {
                op.getTargetProduct();
                org.junit.Assert.fail("expected OperatorException");
            } catch (org.esa.snap.core.gpf.OperatorException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("multilooking"));
            }
        } finally {
            src.dispose();
        }
    }

    /** Every polarisation gets its own model: HH and HV noise bands must differ. */
    @Test
    public void eachPolarisationUsesItsOwnNoise() throws Exception {
        assumeTrue(SC50.exists());
        final Product src = ProductIO.readProduct(SC50);
        try {
            final Product trg = denoise(src, true);
            final float[] hh = new float[1];
            final float[] hv = new float[1];
            trg.getBand("NEP_HH").readPixels(5000, 4000, 1, 1, hh, ProgressMonitor.NULL);
            trg.getBand("NEP_HV").readPixels(5000, 4000, 1, 1, hv, ProgressMonitor.NULL);
            assertNotEquals(hh[0], hv[0], 1e-3);
        } finally {
            src.dispose();
        }
    }

    private static Product denoise(final Product src, final boolean outputNoise) {
        final RCMRemoveThermalNoiseOp op = new RCMRemoveThermalNoiseOp();
        op.setSourceProduct(src);
        op.setParameter("outputNoise", outputNoise);
        op.setParameter("clipNegativeValues", false);
        return op.getTargetProduct();
    }

    /** {first pixel, last pixel, first line, last line} of every burst of the given beam. */
    private static List<int[]> beamBursts(final Product p, final String beam) {
        final MetadataElement burstMap = AbstractMetadata.getOriginalProductMetadata(p)
                .getElement("product").getElement("grdBurstMap");
        final List<int[]> list = new ArrayList<>();
        for (MetadataElement b : burstMap.getElements()) {
            if (b.getName().equals("burstAttributes") && beam.equals(b.getAttributeString("beam"))) {
                list.add(new int[]{Integer.parseInt(b.getAttributeString("topLeftPixel")),
                        Integer.parseInt(b.getAttributeString("bottomRightPixel")),
                        Integer.parseInt(b.getAttributeString("topLeftLine")),
                        Integer.parseInt(b.getAttributeString("bottomRightLine"))});
            }
        }
        if (list.isEmpty()) {
            throw new IllegalStateException("beam " + beam + " not found");
        }
        return list;
    }

    /**
     * Mean power over 200 columns centred in the beam, binned by line offset from the burst centre
     * (10-line bins, over all bursts); returns (max - min) / mean of the binned profile.
     */
    private static double foldedModulation(final Band band, final boolean isAmplitude, final List<int[]> bursts)
            throws Exception {
        final int bin = 10;
        final int nBins = 40;                       // offsets -200..+199 lines
        final double[] sum = new double[nBins];
        final int[] count = new int[nBins];
        final int w = 200;
        final float[] row = new float[w];
        for (int[] b : bursts) {
            final int x0 = (b[0] + b[1]) / 2 - w / 2;
            final int centre = (b[2] + b[3]) / 2;
            for (int y = b[2]; y <= b[3]; y++) {
                if (y < 0 || y >= band.getRasterHeight()) {
                    continue;
                }
                final int k = Math.floorDiv(y - centre, bin) + nBins / 2;
                if (k < 0 || k >= nBins) {
                    continue;
                }
                band.readPixels(x0, y, w, 1, row, ProgressMonitor.NULL);
                for (float v : row) {
                    sum[k] += isAmplitude ? (double) v * v : v;
                    count[k]++;
                }
            }
        }
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, total = 0;
        int used = 0;
        for (int k = 0; k < nBins; k++) {
            if (count[k] < 20 * w) {                 // need at least 20 lines per bin
                continue;
            }
            final double m = sum[k] / count[k];
            min = Math.min(min, m);
            max = Math.max(max, m);
            total += m;
            used++;
        }
        return (max - min) / (total / used);
    }
}
