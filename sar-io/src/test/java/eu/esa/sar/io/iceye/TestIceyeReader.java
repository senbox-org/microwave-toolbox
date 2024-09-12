/*
 * Copyright (C) 2024 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.io.iceye;

import eu.esa.sar.commons.test.ProductValidator;
import eu.esa.sar.commons.test.ReaderTest;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Product;
import org.junit.Test;

import java.io.File;


/**
 * @author Ahmad Hamouda
 */
public class TestIceyeReader extends ReaderTest {

    private final static File SL_GRD_ImageFile = new File(TestData.inputSAR + "Iceye/SLC/ICEYE_SLC_GRD_Example_Spotlight_SAR_Imagery/ICEYE_GRD_Data_Jurong_Island_Singapore_SL_092019/ICEYE_GRD_SL_10402_20190920T075151.tif");
    private final static File SL_SLC_ImageFile = new File(TestData.inputSAR + "Iceye/SLC/ICEYE_SLC_GRD_Example_Spotlight_SAR_Imagery/ICEYE_SLC_Data_Jurong_Island_Singapore_SL_092019/ICEYE_SLC_SL_10402_20190920T075151.h5");

    private final static File SL_GRD_MetadataFile = new File(TestData.inputSAR + "Iceye/SLC/ICEYE_SLC_GRD_Example_Spotlight_SAR_Imagery/ICEYE_GRD_Data_Jurong_Island_Singapore_SL_092019/ICEYE_GRD_SL_10402_20190920T075151.xml");
    private final static File SL_SLC_MetadataFile = new File(TestData.inputSAR + "Iceye/SLC/ICEYE_SLC_GRD_Example_Spotlight_SAR_Imagery/ICEYE_SLC_Data_Jurong_Island_Singapore_SL_092019/ICEYE_SLC_SL_10402_20190920T075151.xml");

    public TestIceyeReader() {
        super(new IceyeProductReaderPlugIn());
    }

    @Test
    public void testReadSL_meta_GRD() throws Exception {
        if(SL_GRD_MetadataFile.exists()) {
            Product prod = testReader(SL_GRD_MetadataFile.toPath());

            final ProductValidator validator = new ProductValidator(prod);
            validator.validateProduct();
            validator.validateMetadata();
            validator.validateBands(new String[]{"Amplitude_VV", "Intensity_VV"});
        }
    }

    @Test
    public void testReadSL_meta_SLC() throws Exception {
        if(SL_SLC_MetadataFile.exists()) {
            Product prod = testReader(SL_SLC_MetadataFile.toPath());

            final ProductValidator validator = new ProductValidator(prod);
            validator.validateProduct();
            validator.validateMetadata();
            validator.validateBands(new String[]{"i_VV", "q_VV", "Intensity_VV"});
        }
    }

    @Test
    public void testReadSL_GRD() throws Exception {
        if(SL_GRD_ImageFile.exists()) {
            Product prod = testReader(SL_GRD_ImageFile.toPath());

            final ProductValidator validator = new ProductValidator(prod);
            validator.validateProduct();
            validator.validateMetadata();
            validator.validateBands(new String[]{"Amplitude_VV", "Intensity_VV"});
        }
    }

    @Test
    public void testReadSL_SLC() throws Exception {
        if(SL_SLC_ImageFile.exists()) {
            Product prod = testReader(SL_SLC_ImageFile.toPath());

            final ProductValidator validator = new ProductValidator(prod);
            validator.validateProduct();
            validator.validateMetadata();
            validator.validateBands(new String[]{"i_VV", "q_VV", "Intensity_VV"});
        }
    }
}
