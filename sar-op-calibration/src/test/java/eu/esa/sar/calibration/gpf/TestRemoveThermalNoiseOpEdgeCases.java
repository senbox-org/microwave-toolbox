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
import eu.esa.sar.commons.Sentinel1Utils;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.datamodel.metadata.AbstractMetadataIO;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Burst geometry and noise-vector edge cases of {@link Sentinel1RemoveThermalNoiseOp}, on the committed synthetic
 * TOPS SLC metadata (one IW1 VH burst of 148 lines, image lines 148..295 of the swath; six range vectors at swath
 * lines -148, 0, 148, ..., 592).
 */
public class TestRemoveThermalNoiseOpEdgeCases {

    private static final int W = 10, H = 148;
    private static final String SWATH_START = "2018-11-15T12:50:02.386138";
    private static final double ATI = 2.055556299999998e-03;     // azimuthTimeInterval of the fixture, s
    private static final double DT = 1.0 / 86400.0;              // 1 s in days

    // ---- first burst line (TOPS, IPF 2.9+) ----

    @Test
    public void untrimmedBurstListStartsAtLineZeroDespiteShiftedVectorLines() throws Exception {
        // full swath (burst list starts at the swath start, no subset) with range-vector lines annotated 80 lines
        // late (>= linesPerBurst / 2): rounding the annotated line would put the first burst at line 148 and leave
        // lines 0..147 outside every burst
        final Product src = createProduct("Sentinel-1 IPF 002.91");
        final MetadataElement orig = AbstractMetadata.getOriginalProductMetadata(src);
        AbstractMetadata.getAbstractedMetadata(src).setAttributeInt(AbstractMetadata.subset_offset_y, 0);
        final MetadataElement annotation = orig.getElement("annotation").getElementAt(0).getElement("product");
        annotation.getElement("imageAnnotation").getElement("imageInformation")
                .setAttributeString("productFirstLineUtcTime", SWATH_START);
        annotation.getElement("swathTiming").getElement("burstList").getElementAt(0)
                .setAttributeString("azimuthTime", SWATH_START);
        for (MetadataElement v : rangeVectors(src)) {
            v.setAttributeString("line", String.valueOf(Integer.parseInt(v.getAttributeString("line")) + 80));
        }
        final float nep = nepAt(src, 5, 20);
        assertTrue("NEP = " + nep, Float.isFinite(nep));
    }

    @Test
    public void firstBurstLineOfUntrimmedListIsZero() {
        // vectors annotated 6 lines late (>= 10 / 2); the burst list starts at the swath start
        final Sentinel1Utils.NoiseVector[] r = {vector(0, 6), vector(7, 16), vector(14, 26)};
        assertEquals(0, Sentinel1RemoveThermalNoiseOp.firstBurstLine(r, new double[]{0, 7 * DT, 14 * DT}, 10, DT, 0));
    }

    @Test
    public void firstBurstLineOfTrimmedListFollowsTheVectorLine() {
        // TOPSAR-Split bursts 2..3 of a swath starting at t = 0
        final Sentinel1Utils.NoiseVector[] r = {vector(0, 0), vector(7, 10), vector(14, 20), vector(21, 30)};
        assertEquals(20, Sentinel1RemoveThermalNoiseOp.firstBurstLine(r, new double[]{14 * DT, 21 * DT}, 10, DT, 0));
        assertEquals("swath start unknown", 20,
                Sentinel1RemoveThermalNoiseOp.firstBurstLine(r, new double[]{14 * DT, 21 * DT}, 10, DT, Double.NaN));
    }

    // ---- range-only noise vectors (IPF 2.50 to 2.9) ----

    @Test
    public void legacySplitSlcMapsLinesByBurstTime() throws Exception {
        // TOPSAR-Split rewrites numberOfLines to the split height (148) while the noise ADS still spans the whole
        // swath: (stopTime - startTime) / (numberOfLines - 1) would put line 168 seconds past the swath end.
        // Vector k carries the noise value k; line 168 is line 20 of the burst starting at vector 3's time.
        final Product src = createLegacyProduct();
        int k = 1;
        for (MetadataElement v : rangeVectors(src)) {
            setLut(v, "0 3 6 9", k + " " + k + " " + k + " " + k);
            k++;
        }
        final double t3 = 5.140583, t4 = 7.905307;
        final double expected = 3 + 20 * ATI / (t4 - t3);
        assertEquals(10 * Math.log10(expected), nepAt(src, 5, 20), 1e-3);
    }

    @Test
    public void legacyDuplicatePixelIndicesDoNotGiveNaN() throws Exception {
        final Product src = createLegacyProduct();
        for (MetadataElement v : rangeVectors(src)) {
            setLut(v, "0 3 3 9", "1 1 4 4");
        }
        // pixel 4 lies in the segment 3..9 where the noise is 4
        assertEquals(10 * Math.log10(4), nepAt(src, 4, 20), 1e-4);
    }

    @Test
    public void legacySingleNoiseVector() throws Exception {
        final Product src = createLegacyProduct();
        final MetadataElement list = noiseDataSet(src).getElement("noiseRangeVectorList");
        final MetadataElement[] vectors = list.getElements();
        for (int i = 0; i < vectors.length; i++) {
            if (i != 2) {
                list.removeElement(vectors[i]);
            }
        }
        list.setAttributeString("count", "1");
        setLut(list.getElementAt(0), "0 3 6 9", "2 2 2 2");
        assertEquals(10 * Math.log10(2), nepAt(src, 5, 20), 1e-4);
    }

    @Test
    public void noiseVectorsOfSplitProductHaveNoNullEntries() throws Exception {
        // a split product keeps one swath: the array must not be padded to 3 swaths x 1 polarisation with nulls
        final Product src = createLegacyProduct();
        final Sentinel1RemoveThermalNoiseOp.ThermalNoiseInfo[] noise = Sentinel1RemoveThermalNoiseOp
                .getThermalNoiseVectors(AbstractMetadata.getOriginalProductMetadata(src), List.of("VH"), 3);
        assertEquals(1, noise.length);
        assertNotNull(noise[0]);
    }

    // ---- band selection and parameters ----

    @Test
    public void dbBandsAreNotCorrected() throws Exception {
        final Product src = createProduct("Sentinel-1 IPF 002.91");
        TestUtils.createBand(src, "Intensity_db_IW1_VH", ProductData.TYPE_FLOAT32, Unit.INTENSITY_DB, W, H, true);
        final Sentinel1RemoveThermalNoiseOp op = new Sentinel1RemoveThermalNoiseOp();
        op.setSourceProduct(src);
        final Product trg = op.getTargetProduct();
        assertNotNull(trg.getBand("Intensity_IW1_VH"));
        assertNull(trg.getBand("Intensity_db_IW1_VH"));
    }

    @Test
    public void recalibrationRequestedOnStripmapIsWarned() throws Exception {
        final Product src = createLegacyProduct();
        final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(src);
        abs.setAttributeString(AbstractMetadata.ProcessingSystemIdentifier, "Sentinel-1 IPF 003.40");
        abs.setAttributeString(AbstractMetadata.MISSION, "SENTINEL-1A");
        abs.setAttributeString(AbstractMetadata.ACQUISITION_MODE, "SM");
        final List<String> warnings = new ArrayList<>();
        final Handler handler = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                if (record.getLevel() == Level.WARNING) {
                    warnings.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        SystemUtils.LOG.addHandler(handler);
        try {
            final Sentinel1RemoveThermalNoiseOp op = new Sentinel1RemoveThermalNoiseOp();
            op.setSourceProduct(src);
            op.setParameter("applyNoiseRecalibration", true);
            assertNotNull(op.getTargetProduct());
        } finally {
            SystemUtils.LOG.removeHandler(handler);
        }
        assertTrue(warnings.toString(), warnings.stream().anyMatch(m -> m.contains("retro-calibration")));
    }

    // ---- fixtures ----

    private static Sentinel1Utils.NoiseVector vector(final double timeSeconds, final int line) {
        return new Sentinel1Utils.NoiseVector(new ProductData.UTC(timeSeconds * DT), line,
                new int[]{0, 9}, new float[]{1, 1});
    }

    private static float nepAt(final Product src, final int x, final int y) throws Exception {
        final Sentinel1RemoveThermalNoiseOp op = new Sentinel1RemoveThermalNoiseOp();
        op.setSourceProduct(src);
        op.setParameter("outputNoise", true);
        final float[] v = new float[1];
        op.getTargetProduct().getBand("NEP_IW1_VH").readPixels(x, y, 1, 1, v, ProgressMonitor.NULL);
        return v[0];
    }

    private static MetadataElement noiseDataSet(final Product src) {
        return AbstractMetadata.getOriginalProductMetadata(src).getElement("noise").getElementAt(0).getElement("noise");
    }

    private static MetadataElement[] rangeVectors(final Product src) {
        return noiseDataSet(src).getElement("noiseRangeVectorList").getElements();
    }

    private static void setLut(final MetadataElement vector, final String pixels, final String values) {
        vector.getElement("pixel").setAttributeString("pixel", pixels);
        vector.getElement("noiseRangeLut").setAttributeString("noiseRangeLut", values);
    }

    private static Product createProduct(final String processingSystem) throws Exception {
        final Product p = TestUtils.createProduct("SLC", W, H);
        TestUtils.createBand(p, "i_IW1_VH", ProductData.TYPE_INT16, Unit.REAL, W, H, true);
        TestUtils.createBand(p, "q_IW1_VH", ProductData.TYPE_INT16, Unit.IMAGINARY, W, H, true);
        AbstractMetadataIO.Load(p, p.getMetadataRoot(), new File("src/test/resources/metadata.xml"));
        AbstractMetadata.getAbstractedMetadata(p).setAttributeString(AbstractMetadata.ProcessingSystemIdentifier,
                processingSystem);
        return p;
    }

    /** IPF 2.60 split product: numberOfLines rewritten to the split height, noise ADS spanning the whole swath. */
    private static Product createLegacyProduct() throws Exception {
        final Product p = createProduct("Sentinel-1 IPF 002.60");
        final MetadataElement orig = AbstractMetadata.getOriginalProductMetadata(p);
        for (MetadataElement annotation : orig.getElement("annotation").getElements()) {
            annotation.getElement("product").getElement("imageAnnotation").getElement("imageInformation")
                    .setAttributeString("numberOfLines", String.valueOf(H));
        }
        for (MetadataElement noise : orig.getElement("noise").getElements()) {
            final MetadataElement ads = noise.getElement("noise").getElement("adsHeader");
            ads.setAttributeString("startTime", SWATH_START);
            ads.setAttributeString("stopTime", "2018-11-15T12:50:13.716364");
        }
        return p;
    }
}
