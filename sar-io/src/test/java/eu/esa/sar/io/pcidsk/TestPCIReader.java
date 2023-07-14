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
package eu.esa.sar.io.pcidsk;

import eu.esa.sar.commons.test.ProductValidator;
import eu.esa.sar.commons.test.ReaderTest;
import eu.esa.sar.commons.test.SARTests;
import org.esa.snap.core.datamodel.Product;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assume.assumeTrue;

/**
 * Test PCI Product Reader.
 *
 * @author lveci
 */
public class TestPCIReader extends ReaderTest {

    private final static File file = new File(SARTests.inputPathProperty + "SAR/pcidsk/kompsat2_pcidsk_msc.pix");

    final static ProductValidator.Options productOptions = new ProductValidator.Options();

    @Before
    public void setup() {
        assumeTrue(file + " not found", file.exists());
        productOptions.verifyTimes = false;
        productOptions.verifyBands = false;
    }

    public TestPCIReader() {
        super(new PCIReaderPlugIn());
    }

    @Test
    public void testOpeningFile() throws Exception {
        verifyTime = false;
        Product prod = testReader(file.toPath());

        ProductValidator validator = new ProductValidator(prod, productOptions);
        validator.validateProduct();
    }
}