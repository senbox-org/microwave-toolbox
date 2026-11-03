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
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.datamodel.metadata.AbstractMetadataIO;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertTrue;

/**
 * Noise vectors annotated by IPF < 2.50 must be rescaled (ESA MPC-0243 section 6) before thermal noise removal.
 */
public class TestRemoveThermalNoiseOpLegacy {

    @Test
    public void ipf250ProductIsDenoised() throws Exception {
        assertTrue(Float.isFinite(nepAt(createTOPSSLCProduct("Sentinel-1 IPF 002.60"))));
    }

    /**
     * IW noise vectors before IPF 2.50 are refused. Even rescaled by knoise * Adn^2 (MPC-0243) the NESZ of two
     * IPF 2.34 IW SLCs (Etna, 2014-08-09 and 2014-08-21) is -43.6..-47.6 dB, 20-23 dB below the empirical noise
     * floor (-21..-25 dB, darkest 4x16 block means of VH), so noise removal would remove almost nothing.
     */
    @Test
    public void iwBeforeIpf250IsRefused() throws Exception {
        final Sentinel1RemoveThermalNoiseOp op = new Sentinel1RemoveThermalNoiseOp();
        op.setSourceProduct(createTOPSSLCProduct("Sentinel-1 IPF 002.36"));
        try {
            op.getTargetProduct();
            org.junit.Assert.fail("expected OperatorException");
        } catch (org.esa.snap.core.gpf.OperatorException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("2.50"));
        }
    }

    @Test
    public void stripmapBeforeIpf250IsRefused() throws Exception {
        // stripmap noise vectors before IPF 2.50 are ~100 dB too low and have no published rescaling
        final Product src = createTOPSSLCProduct("Sentinel-1 IPF 002.36");
        org.esa.snap.engine_utilities.datamodel.AbstractMetadata.getAbstractedMetadata(src)
                .setAttributeString(org.esa.snap.engine_utilities.datamodel.AbstractMetadata.ACQUISITION_MODE, "SM");
        final Sentinel1RemoveThermalNoiseOp op = new Sentinel1RemoveThermalNoiseOp();
        op.setSourceProduct(src);
        try {
            op.getTargetProduct();
            org.junit.Assert.fail("expected OperatorException");
        } catch (org.esa.snap.core.gpf.OperatorException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("2.50"));
        }
    }

    private static float nepAt(final Product src) throws Exception {
        final Sentinel1RemoveThermalNoiseOp op = new Sentinel1RemoveThermalNoiseOp();
        op.setSourceProduct(src);
        op.setParameter("outputNoise", true);
        final float[] v = new float[1];
        op.getTargetProduct().getBand("NEP_IW1_VH").readPixels(5, 20, 1, 1, v, ProgressMonitor.NULL);
        return v[0];
    }

    private static Product createTOPSSLCProduct(final String processingSystem) throws Exception {
        final int w = 10, h = 148;
        final Product srcProduct = TestUtils.createProduct("SLC", w, h);
        TestUtils.createBand(srcProduct, "i_IW1_VH", ProductData.TYPE_INT16, Unit.REAL, w, h, true);
        TestUtils.createBand(srcProduct, "q_IW1_VH", ProductData.TYPE_INT16, Unit.IMAGINARY, w, h, true);
        AbstractMetadataIO.Load(srcProduct, srcProduct.getMetadataRoot(), new File("src/test/resources/metadata.xml"));
        AbstractMetadata.getAbstractedMetadata(srcProduct)
                .setAttributeString(AbstractMetadata.ProcessingSystemIdentifier, processingSystem);
        // the pre-2.9 (range-only) path needs line count and ADS times that the fixture omits
        final MetadataElement orig = AbstractMetadata.getOriginalProductMetadata(srcProduct);
        for (MetadataElement annotation : orig.getElement("annotation").getElements()) {
            annotation.getElement("product").getElement("imageAnnotation").getElement("imageInformation")
                    .setAttributeString("numberOfLines", String.valueOf(h));
        }
        for (MetadataElement noise : orig.getElement("noise").getElements()) {
            final MetadataElement ads = noise.getElement("noise").getElement("adsHeader");
            ads.setAttributeString("startTime", "2018-11-15T12:50:02.000000");
            ads.setAttributeString("stopTime", "2018-11-15T12:50:13.000000");
        }
        orig.addElement(createDnCalibration());
        return srcProduct;
    }

    /** Minimal calibration annotation with a constant DN LUT of 300 (the value used by IPF 2.3x products). */
    private static MetadataElement createDnCalibration() {
        final MetadataElement ads = new MetadataElement("adsHeader");
        ads.setAttributeString("polarisation", "VH");
        ads.setAttributeString("swath", "IW1");
        ads.setAttributeString("startTime", "2018-11-15T12:50:02.000000");
        ads.setAttributeString("stopTime", "2018-11-15T12:50:13.000000");

        final MetadataElement pixel = new MetadataElement("pixel");
        pixel.setAttributeString("pixel", "0 9");
        pixel.setAttributeString("count", "2");
        final MetadataElement dn = new MetadataElement("dn");
        dn.setAttributeString("dn", "300 300");
        final MetadataElement vector = new MetadataElement("calibrationVector");
        vector.setAttributeString("azimuthTime", "2018-11-15T12:50:02.000000");
        vector.setAttributeString("line", "0");
        vector.addElement(pixel);
        vector.addElement(dn);
        final MetadataElement vectorList = new MetadataElement("calibrationVectorList");
        vectorList.setAttributeString("count", "1");
        vectorList.addElement(vector);

        final MetadataElement cal = new MetadataElement("calibration");
        cal.addElement(ads);
        cal.addElement(vectorList);
        final MetadataElement dataSet = new MetadataElement("calibration-s1a-iw1-slc-vh.xml");
        dataSet.addElement(cal);
        final MetadataElement calibration = new MetadataElement("calibration");
        calibration.addElement(dataSet);
        return calibration;
    }
}
