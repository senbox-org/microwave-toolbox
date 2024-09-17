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
package eu.esa.sar.iogdal.sentinel1;

import com.bc.ceres.annotation.STTM;
import eu.esa.sar.commons.test.TestData;
import eu.esa.sar.iogdal.AbstractProductReaderPlugInGDALTest;
import org.esa.snap.core.dataio.ProductReader;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TestSentinel1GDALProductReaderPlugIn extends AbstractProductReaderPlugInGDALTest {

    public TestSentinel1GDALProductReaderPlugIn() {
        super(new Sentinel1GDALProductReaderPlugIn());
    }

    @Test
    public void testCreateReaderInstance() {
        ProductReader productReader = plugin.createReaderInstance();
        assertNotNull(productReader);
        assertTrue(productReader instanceof Sentinel1GDALProductReader);
    }

    @Test
    public void testGetFormatNames() {
        assertArrayEquals(new String[]{"SENTINEL-1 COG"}, plugin.getFormatNames());
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
    @STTM("SNAP-3588")
    public void testValidDecodeQualification() {
        isValidDecodeQualification(TestSentinel1GDALProductReader.inputS1_COGGRD);
        isValidDecodeQualification(TestSentinel1GDALProductReader.inputS1_COGGRD_ZIP);
        isValidDecodeQualification(TestSentinel1GDALProductReader.inputS1_COGGRD_COMPRESSED_ZIP);

        isInValidDecodeQualification(TestData.inputS1_GRD);
        isInValidDecodeQualification(TestData.inputS1_SLC);
    }
}
