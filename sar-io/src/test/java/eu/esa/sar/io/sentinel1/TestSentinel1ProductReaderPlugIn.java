/*
 * Copyright (C) 2023 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.io.sentinel1;

import eu.esa.sar.commons.test.TestData;
import eu.esa.sar.io.AbstractProductReaderPlugInTest;
import org.esa.snap.core.dataio.ProductReader;
import org.junit.Test;

import static eu.esa.sar.io.sentinel1.TestSentinel1ProductReader.inputGRDFolder;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TestSentinel1ProductReaderPlugIn extends AbstractProductReaderPlugInTest {

    public TestSentinel1ProductReaderPlugIn() {
        super(new Sentinel1ProductReaderPlugIn());
    }

    @Test
    public void testCreateReaderInstance() {
        ProductReader productReader = plugin.createReaderInstance();
        assertNotNull(productReader);
        assertTrue(productReader instanceof Sentinel1ProductReader);
    }

    @Test
    public void testGetFormatNames() {
        assertArrayEquals(new String[]{"SENTINEL-1"}, plugin.getFormatNames());
    }

    @Test
    public void testGetDefaultFileExtensions() {
        assertArrayEquals(new String[]{".safe",".zip"}, plugin.getDefaultFileExtensions());
    }

    @Override
    protected String[] getValidPrimaryMetadataFileNames() {
        return new String[] {"manifest.safe"};
    }

    @Override
    protected String[] getInvalidPrimaryMetadataFileNames() {
        return new String[] {"manifest.xml"};
    }

    @Test
    public void testValidDecodeQualification() {
        isValidDecodeQualification(TestData.inputS1_GRD);
        isValidDecodeQualification(inputGRDFolder);

        isValidDecodeQualification(TestData.inputS1_SLC);

        isValidDecodeQualification(TestS1OCNInputProductValidator.inputS1_IW_OCN);
        isValidDecodeQualification(TestS1OCNInputProductValidator.inputS1_WV_OCN);
        isValidDecodeQualification(TestS1OCNInputProductValidator.inputS1_WV_SLC);

        isInValidDecodeQualification(TestSentinel1ETADProductReader.inputS1ETAD_IW);
        isInValidDecodeQualification(TestSentinel1ETADProductReader.inputS1ETAD_SM);
        isInValidDecodeQualification(TestSentinel1ETADProductReader.inputS1ETAD_SM_ZIP);
    }
}
