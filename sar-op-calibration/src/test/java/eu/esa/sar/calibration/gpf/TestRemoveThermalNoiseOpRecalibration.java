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
import eu.esa.sar.calibration.gpf.support.S1NoiseRecalibration;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.datamodel.metadata.AbstractMetadataIO;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;

/**
 * Optional retro-calibration of the noise vectors (Recchia et al. 2025, Remote Sens. 17:3474, eq. 14).
 */
public class TestRemoveThermalNoiseOpRecalibration {

    @Test
    public void recalibrationScalesNoiseBySwathConstant() throws Exception {
        final float off = nep(create("Sentinel-1 IPF 003.40"), false);
        final float on = nep(create("Sentinel-1 IPF 003.40"), true);
        final double expectedDb = 10.0 * Math.log10(S1NoiseRecalibration.linearFactor("S1A", "IW1", 'H'));
        assertEquals(off + expectedDb, on, 1e-4);
    }

    @Test
    public void notAppliedBeforeIpf310() throws Exception {
        assertEquals(nep(create("Sentinel-1 IPF 002.91"), false), nep(create("Sentinel-1 IPF 002.91"), true), 0f);
    }

    private static float nep(final Product src, final boolean recalibrate) throws Exception {
        final Sentinel1RemoveThermalNoiseOp op = new Sentinel1RemoveThermalNoiseOp();
        op.setSourceProduct(src);
        op.setParameter("outputNoise", true);
        op.setParameter("applyNoiseRecalibration", recalibrate);
        final float[] v = new float[1];
        op.getTargetProduct().getBand("NEP_IW1_VH").readPixels(5, 20, 1, 1, v, ProgressMonitor.NULL);
        return v[0];
    }

    private static Product create(final String processingSystem) throws Exception {
        final int w = 10, h = 148;
        final Product p = TestUtils.createProduct("SLC", w, h);
        TestUtils.createBand(p, "i_IW1_VH", ProductData.TYPE_INT16, Unit.REAL, w, h, true);
        TestUtils.createBand(p, "q_IW1_VH", ProductData.TYPE_INT16, Unit.IMAGINARY, w, h, true);
        AbstractMetadataIO.Load(p, p.getMetadataRoot(), new File("src/test/resources/metadata.xml"));
        AbstractMetadata.getAbstractedMetadata(p).setAttributeString(AbstractMetadata.ProcessingSystemIdentifier, processingSystem);
        AbstractMetadata.getAbstractedMetadata(p).setAttributeString(AbstractMetadata.MISSION, "SENTINEL-1A");
        return p;
    }
}
