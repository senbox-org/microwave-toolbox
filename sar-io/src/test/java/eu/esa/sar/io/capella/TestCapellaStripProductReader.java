/*
 * Copyright (C) 2021 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.io.capella;

import com.bc.ceres.annotation.STTM;
import com.bc.ceres.glevel.MultiLevelImage;
import eu.esa.sar.commons.test.MetadataValidator;
import eu.esa.sar.commons.test.ProductValidator;
import eu.esa.sar.commons.test.ReaderTest;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.Tile;
import org.junit.Before;
import org.junit.Test;

import java.awt.*;
import java.awt.image.Raster;
import java.io.File;

import static org.junit.Assume.assumeTrue;

/**
 * Test Product Reader.
 *
 * @author lveci
 */
public class TestCapellaStripProductReader extends ReaderTest {

    final static File inputSMGEOMeta = new File(TestData.inputSAR + "Capella/Strip/GEO/CAPELLA_C02_SM_GEO_HH_20201118185123_20201118185127.json");
    final static File inputSMGEOTif = new File(TestData.inputSAR + "Capella/Strip/GEO/CAPELLA_C02_SM_GEO_HH_20201118185123_20201118185127.tif");
    final static File inputSMGEOFolder = new File(TestData.inputSAR + "Capella/Strip/GEO");

    final static File inputSMSLCMeta = new File(TestData.inputSAR + "Capella/Strip/SLC/CAPELLA_C02_SM_SLC_HH_20201118185123_20201118185127.json");
    final static File inputSMSLCTif = new File(TestData.inputSAR + "Capella/Strip/SLC/CAPELLA_C02_SM_SLC_HH_20201118185123_20201118185127.tif");
    final static File inputSMSLCFolder = new File(TestData.inputSAR + "Capella/Strip/SLC");

    final static MetadataValidator.Options options = new MetadataValidator.Options();

    public TestCapellaStripProductReader() {
        super(new CapellaProductReaderPlugIn());
    }

    @Before
    public void setUp() {
        // If any of the file does not exist: the test will be ignored
        assumeTrue(inputSMGEOMeta + " not found", inputSMGEOMeta.exists());
        assumeTrue(inputSMGEOFolder + " not found", inputSMGEOFolder.exists());
        assumeTrue(inputSMSLCMeta + " not found", inputSMSLCMeta.exists());
        assumeTrue(inputSMSLCFolder + " not found", inputSMSLCFolder.exists());

        options.validateSRGR = false;
        options.validateDopplerCentroids = false;
    }

    @Test
    public void testOpeningGEOFolder() throws Exception {
        Product prod = testReader(inputSMGEOFolder.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata(options);
        validator.validateBands(new String[] {"Sigma0_HH"});
    }

    @Test
    public void testOpeningGEOMetadata() throws Exception {
        Product prod = testReader(inputSMGEOMeta.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata(options);
        validator.validateBands(new String[] {"Sigma0_HH"});
    }

    @Test
    public void testOpeningGEOTif() throws Exception {
        Product prod = testReader(inputSMGEOTif.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata(options);
        validator.validateBands(new String[] {"Sigma0_HH"});
    }

    @Test
    public void testOpeningSLCFolder() throws Exception {
        Product prod = testReader(inputSMSLCFolder.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata(options);
        validator.validateBands(new String[] {"i_HH", "q_HH", "Intensity_HH"});
    }

    @Test
    public void testOpeningSLCMetadata() throws Exception {
        Product prod = testReader(inputSMSLCMeta.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata(options);
        validator.validateBands(new String[] {"i_HH","q_HH","Intensity_HH"});
    }

    @Test
    public void testOpeningSLCTif() throws Exception {
        Product prod = testReader(inputSMSLCTif.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata(options);
        validator.validateBands(new String[] {"i_HH","q_HH","Intensity_HH"});
    }

    @Test
    @STTM("SNAP-3628")
    public void testReadingSLCTif() throws Exception {

        final float[] expected = new float[] {
                -0.1321992f, -0.0081046745f, 0.07546648f, 0.14987005f, 0.027369885f,
                -0.033880197f,  0.029495701f, -0.14641559f, -0.112933986f, 0.03481024f,
                0.11080817f,  0.028831383f, -0.1646179f, -0.06962048f, 0.17431693f,
                -0.0039859056f, -0.11532553f, -0.039593328f, 0.20633703f, 0.23503555f,
                -0.06789326f, -0.13804519f, 0.020992436f, 0.26041248f, 0.15305877f
        };

        Product prod = testReader(inputSMSLCTif.toPath());
        final Band band = prod.getBand("i_HH");
        final MultiLevelImage image = band.getSourceImage();
        final Raster raster = image.getData(new Rectangle(0, 0, 5, 5));
        int i = 0;
        for (int y = 0; y < 5; ++y) {
            for (int x = 0; x < 5; ++x) {
                final float v = raster.getSampleFloat(x, y, 0);
                assumeTrue(Math.abs(v - expected[i++]) <= 1e-8);
            }
        }
    }
}
