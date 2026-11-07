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
import org.esa.snap.core.dataio.ProductSubsetBuilder;
import org.esa.snap.core.dataio.ProductSubsetDef;
import org.esa.snap.core.datamodel.Product;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assume.assumeTrue;

/**
 * Noise of a subset of a subset must equal the noise of the same pixels of the full product. Relies on
 * snap-engine ProductSubsetBuilder accumulating subset_offset_x/y over nested subsets.
 */
@RunWith(LongTestRunner.class)
public class TestRemoveThermalNoiseOpNestedSubsetLong {

    private static final File GRD = new File(TestData.inputSAR +
            "S1/GRD/S1A_IW_GRDH_1SDV_20240508T062559_20240508T062624_053776_0688DB_1A13.SAFE.zip");

    @Test
    public void subsetOfSubsetGetsTheSameNoiseAsTheFullProduct() throws Exception {
        assumeTrue(GRD.exists());
        final Product full = ProductIO.readProduct(GRD);
        try {
            final ProductSubsetDef first = new ProductSubsetDef();
            first.setRegion(4000, 3000, 8000, 6000);
            final Product subset1 = ProductSubsetBuilder.createProductSubset(full, first, "s1", "");
            final ProductSubsetDef second = new ProductSubsetDef();
            second.setRegion(1000, 2000, 2000, 2000);
            final Product subset2 = ProductSubsetBuilder.createProductSubset(subset1, second, "s2", "");

            final float[] a = new float[256];
            final float[] b = new float[256];
            noise(full).getBand("NEP_VH").readPixels(5000, 5000, 256, 1, a, ProgressMonitor.NULL);
            noise(subset2).getBand("NEP_VH").readPixels(0, 0, 256, 1, b, ProgressMonitor.NULL);
            assertArrayEquals(a, b, 1e-4f);
        } finally {
            full.dispose();
        }
    }

    private static Product noise(final Product src) {
        final Sentinel1RemoveThermalNoiseOp op = new Sentinel1RemoveThermalNoiseOp();
        op.setSourceProduct(src);
        op.setParameter("outputNoise", true);
        op.setParameter("selectedPolarisations", new String[]{"VH"});
        return op.getTargetProduct();
    }
}
