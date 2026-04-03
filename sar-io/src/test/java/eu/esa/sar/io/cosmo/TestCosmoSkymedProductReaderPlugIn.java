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
package eu.esa.sar.io.cosmo;

import org.esa.snap.core.dataio.DecodeQualification;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.util.SystemUtils;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TestCosmoSkymedProductReaderPlugIn {

    protected CosmoSkymedReaderPlugIn plugin = new CosmoSkymedReaderPlugIn();

    @Test
    public void testCreateReaderInstance() {
        ProductReader productReader = plugin.createReaderInstance();
        assertNotNull(productReader);
        assertTrue(productReader instanceof CosmoSkymedReader);
    }

    @Test
    public void testGetFormatNames() {
        assertArrayEquals(new String[]{"CosmoSkymed"}, plugin.getFormatNames());
    }

    @Test
    public void testGetDefaultFileExtensions() {
        assertArrayEquals(new String[]{".h5",".attribs.xml",".tif"}, plugin.getDefaultFileExtensions());
    }

    @Test
    public void testDecodeQualificationRoot() {
        isInValidDecodeQualification(new File("/"));
        isInValidDecodeQualification(new File("c:\\"));
        isInValidDecodeQualification(new File("z:\\"));
    }

    protected void isValidDecodeQualification(final File file) {
        if(!file.exists()) {
            SystemUtils.LOG.warning(plugin +": isValidDecodeQualification file " + file + " not found");
            return;
        }
        DecodeQualification decodeQualification = plugin.getDecodeQualification(file);
        assertEquals(file.getPath() + " DecodeQualification="+decodeQualification,
                DecodeQualification.INTENDED, decodeQualification);
    }

    protected void isInValidDecodeQualification(final File file) {
        DecodeQualification decodeQualification = plugin.getDecodeQualification(file);
        assertEquals(file.getPath() + " DecodeQualification="+decodeQualification,
                DecodeQualification.UNABLE, decodeQualification);
    }

    @Test
    public void testAvailableProductIOReader() {
        for(String formatName : plugin.getFormatNames()) {
            ProductReader productReader = ProductIO.getProductReader(formatName);
            assertNotNull("ProductReader not found for " +formatName, productReader);
        }
    }

    @Test
    public void testGetDescription() {
        final String desc = plugin.getDescription(null);
        assertNotNull("ReaderPlugIn description invalid", desc);
        assertFalse("ReaderPlugIn description invalid", desc.isEmpty());
    }

    @Test
    public void testValidDecodeQualification() {
        isValidDecodeQualification(TestCosmoSkymedReader.inputSCS_H5);
        isValidDecodeQualification(TestCosmoSkymedReader.inputDGM_H5);
        isValidDecodeQualification(TestCosmoSkymedReader.inputSC_DGM_H5);
        isValidDecodeQualification(TestCosmoSkymedReader.inputSC_SCS_H5);
        isValidDecodeQualification(TestCosmoSkymedReader.inputSM_SCS_H5);

        isValidDecodeQualification(TestCosmoSkymedGeotiffReader.inputSM_GeoTiff_1B_tif);
        isValidDecodeQualification(TestCosmoSkymedGeotiffReader.inputSM_GeoTiff_1B_xml);
        isValidDecodeQualification(TestCosmoSkymedGeotiffReader.inputSC_GeoTiff_DGM_tif);
        isValidDecodeQualification(TestCosmoSkymedGeotiffReader.inputSC_GeoTiff_GEC_tif);
    }
}
