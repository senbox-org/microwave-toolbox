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
import eu.esa.sar.commons.test.MetadataValidator;
import eu.esa.sar.commons.test.ProductValidator;
import eu.esa.sar.commons.test.ReaderTest;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assume.assumeTrue;

/**
 * Test Product Reader.
 *
 * @author lveci
 */
public class TestCapellaSpotProductReader extends ReaderTest {

    final static File inputSpotSLCMeta = new File(TestData.inputSAR + "Capella/Spot/SLC/CAPELLA_C02_SP_SLC_HH_20201209213329_20201209213332.json");
    final static File inputSpotSLCTif = new File(TestData.inputSAR + "Capella/Spot/SLC/CAPELLA_C02_SP_SLC_HH_20201209213329_20201209213332.tif");
    final static File inputSpotSLCFolder = new File(TestData.inputSAR + "Capella/Spot/SLC");

    final static MetadataValidator.Options options = new MetadataValidator.Options();

    public TestCapellaSpotProductReader() {
        super(new CapellaProductReaderPlugIn());
    }

    @Before
    public void setUp() {
        // If any of the file does not exist: the test will be ignored
        assumeTrue(inputSpotSLCMeta + " not found", inputSpotSLCMeta.exists());
        assumeTrue(inputSpotSLCFolder + " not found", inputSpotSLCFolder.exists());

        options.validateSRGR = false;
        options.validateDopplerCentroids = false;
    }

    @Test
    public void testOpeningSLCFolder() throws Exception {
        Product prod = testReader(inputSpotSLCFolder.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata(options);
        validator.validateBands(new String[] {"i_HH","q_HH","Intensity_HH"});
    }

    @Test
    public void testOpeningSLCMetadata() throws Exception {
        Product prod = testReader(inputSpotSLCMeta.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata(options);
        validator.validateBands(new String[] {"i_HH","q_HH","Intensity_HH"});
    }

    @Test
    public void testOpeningSLCTif() throws Exception {
        Product prod = testReader(inputSpotSLCTif.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata(options);
        validator.validateBands(new String[] {"i_HH","q_HH","Intensity_HH"});
    }

    @Test
    @STTM("SNAP-4017")
    public void testTimesInAbsMetadata() throws Exception {
        Product prod = testReader((new File("src/test/resources/eu/esa/sar/io/capella/Capella_Spot_SLC_extended.json").toPath()));
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(prod);
        final String firstLintTime = absRoot.getAttributeUTC(AbstractMetadata.first_line_time).toString();
        final String lastLineTime = absRoot.getAttributeUTC(AbstractMetadata.last_line_time).toString();
        final double lineTimeInterval = absRoot.getAttributeDouble(AbstractMetadata.line_time_interval);

        final String expFirstLineTime = "09-DEC-2020 21:33:30.540582";
        final String expLastLineTime = "09-DEC-2020 21:33:31.294998";
        final double expLineTimeInterval = 6.225066666666666E-5;

        Assert.assertEquals(expFirstLineTime, firstLintTime);
        Assert.assertEquals(expLastLineTime, lastLineTime);
        Assert.assertEquals(expLineTimeInterval, lineTimeInterval, 1e-4);
    }
}
