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
import eu.esa.sar.sentinel1.gpf.TOPSARSplitOp;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assume.assumeTrue;

/**
 * Thermal noise of a TOPSAR-Split burst subset must equal the noise of the same lines of the whole swath.
 */
@RunWith(LongTestRunner.class)
public class TestRemoveThermalNoiseOpSplitLong {

    private static final File SLC = new File(TestData.inputSAR +
            "S1/S1A_IW_SLC__1SDV_20180620T222319_20180620T222347_022446_026E52_B227.zip");

    @Test
    public void splitBurstsGetTheSameNoiseAsTheFullSwath() throws Exception {
        assumeTrue(SLC.exists());
        final Product full = ProductIO.readProduct(SLC);
        try {
            final TOPSARSplitOp split = new TOPSARSplitOp();
            split.setSourceProduct(full);
            split.setParameter("subswath", "IW2");
            split.setParameter("selectedPolarisations", new String[]{"VV"});
            split.setParameter("firstBurstIndex", 4);
            split.setParameter("lastBurstIndex", 6);
            final Product subset = split.getTargetProduct();
            final int offsetY = AbstractMetadata.getAbstractedMetadata(subset).getAttributeInt(AbstractMetadata.subset_offset_y);

            final Band fullNep = noise(full).getBand("NEP_IW2_VV");
            final Band subNep = noise(subset).getBand("NEP_IW2_VV");
            final int w = subNep.getRasterWidth();
            final int lpb = subNep.getRasterHeight() / 3;
            for (int y : new int[]{10, lpb + 10, 2 * lpb + 10, 3 * lpb - 10}) {
                final float[] a = new float[w];
                final float[] b = new float[w];
                fullNep.readPixels(0, offsetY + y, w, 1, a, ProgressMonitor.NULL);
                subNep.readPixels(0, y, w, 1, b, ProgressMonitor.NULL);
                assertArrayEquals("line " + y, a, b, 1e-4f);
            }
        } finally {
            full.dispose();
        }
    }

    @Test
    public void splitThenCropGetsTheSameNoiseAsTheFullSwath() throws Exception {
        assumeTrue(SLC.exists());
        final Product full = ProductIO.readProduct(SLC);
        try {
            final TOPSARSplitOp split = new TOPSARSplitOp();
            split.setSourceProduct(full);
            split.setParameter("subswath", "IW2");
            split.setParameter("selectedPolarisations", new String[]{"VV"});
            split.setParameter("firstBurstIndex", 2);
            split.setParameter("lastBurstIndex", 6);
            final Product splitProduct = split.getTargetProduct();
            final int lpb = splitProduct.getSceneRasterHeight() / 5;
            // crop to the third selected burst (burst 4 of the swath)
            final org.esa.snap.core.dataio.ProductSubsetDef def = new org.esa.snap.core.dataio.ProductSubsetDef();
            def.setRegion(0, 2 * lpb, splitProduct.getSceneRasterWidth(), lpb);
            final Product crop = org.esa.snap.core.dataio.ProductSubsetBuilder.createProductSubset(splitProduct, def, "crop", "");
            final int offsetY = AbstractMetadata.getAbstractedMetadata(crop).getAttributeInt(AbstractMetadata.subset_offset_y);

            final org.esa.snap.core.datamodel.Band fullNep = noise(full).getBand("NEP_IW2_VV");
            final org.esa.snap.core.datamodel.Band cropNep = noise(crop).getBand("NEP_IW2_VV");
            final int w = cropNep.getRasterWidth();
            for (int y : new int[]{10, lpb / 2, lpb - 10}) {
                final float[] a = new float[w];
                final float[] b = new float[w];
                fullNep.readPixels(0, offsetY + y, w, 1, a, ProgressMonitor.NULL);
                cropNep.readPixels(0, y, w, 1, b, ProgressMonitor.NULL);
                assertArrayEquals("line " + y, a, b, 1e-4f);
            }
        } finally {
            full.dispose();
        }
    }

    private static Product noise(final Product src) {
        final Sentinel1RemoveThermalNoiseOp op = new Sentinel1RemoveThermalNoiseOp();
        op.setSourceProduct(src);
        op.setParameter("outputNoise", true);
        op.setParameter("selectedPolarisations", new String[]{"VV"});
        return op.getTargetProduct();
    }
}
