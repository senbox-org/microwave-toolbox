/*
 * Copyright (C) 2019 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.io.paz;

import eu.esa.sar.commons.test.ProductValidator;
import eu.esa.sar.commons.test.ReaderTest;
import eu.esa.sar.commons.test.SARTests;
import org.esa.snap.core.datamodel.Product;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assume.assumeTrue;

/**
 * Test Product Reader.
 *
 * @author lveci
 */
public class TestPazProductReader extends ReaderTest {

    private final static File inputMGDMetaXML = new File(SARTests.inputPathProperty + "SAR/PAZ/NewDelhi/PAZ1_SAR__MGD_RE___SC_S_SRA_20180616T004650_20180616T004712/PAZ1_SAR__MGD_RE___SC_S_SRA_20180616T004650_20180616T004712.xml");
    private final static File inputMGDFolder = new File(SARTests.inputPathProperty + "SAR/PAZ/NewDelhi/PAZ1_SAR__MGD_RE___SC_S_SRA_20180616T004650_20180616T004712");

    private final static File inputSSCMetaXML = new File(SARTests.inputPathProperty + "SAR/PAZ/Mojave Interferometric pair/PAZ1_SAR__SSC______SM_S_SRA_20180520T014220_20180520T014228/PAZ1_SAR__SSC______SM_S_SRA_20180520T014220_20180520T014228.xml");
    private final static File inputSSCFolder = new File(SARTests.inputPathProperty + "SAR/PAZ/Mojave Interferometric pair/PAZ1_SAR__SSC______SM_S_SRA_20180520T014220_20180520T014228");

    public TestPazProductReader() {
        super(new PazProductReaderPlugIn());
    }

    @Before
    public void setUp() {
        // If any of the file does not exist: the test will be ignored
        assumeTrue(inputMGDMetaXML + " not found", inputMGDMetaXML.exists());
        assumeTrue(inputMGDFolder + " not found", inputMGDFolder.exists());
        assumeTrue(inputSSCMetaXML + " not found", inputSSCMetaXML.exists());
        assumeTrue(inputSSCFolder + " not found", inputSSCFolder.exists());
    }

    @Test
    public void testOpeningMGDMetadata() throws Exception {
        Product prod = testReader(inputMGDMetaXML.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"Amplitude_HH","Intensity_HH"});
    }

    @Test
    public void testOpeningSSCMetadata() throws Exception {
        Product prod = testReader(inputSSCMetaXML.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"i_HH","q_HH","Intensity_HH"});
    }
}
